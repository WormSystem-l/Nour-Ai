#!/usr/bin/env python3
"""فحص تمهيدي لمشروع Dart/Flutter بدون SDK.

بيتحقق من:
- توازن الأقواس والأقواس المربعة في كل ملف .dart (مع تجاهل النصوص والتعليقات)
- وجود كل ملف مستورد (import) فعلاً
- عدم وجود APIs مهجورة معروفة
- سلامة ملفات YAML و pubspec
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / "lib"

problems: list[str] = []
notes: list[str] = []


def strip_code(src: str) -> str:
    """يشيل التعليقات والنصوص عشان نعدّ الأقواس في الكود بس."""
    out: list[str] = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                i += 1
        elif c == "/" and nxt == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
        elif c in "'\"":
            quote = c
            triple = src[i : i + 3] == quote * 3
            i += 3 if triple else 1
            while i < n:
                if src[i] == "\\":
                    i += 2
                    continue
                if triple:
                    if src[i : i + 3] == quote * 3:
                        i += 3
                        break
                elif src[i] == quote:
                    i += 1
                    break
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_balance(path: Path) -> None:
    code = strip_code(path.read_text(encoding="utf-8"))
    pairs = {"(": ")", "[": "]", "{": "}"}
    closers = {v: k for k, v in pairs.items()}
    stack: list[tuple[str, int]] = []
    for index, ch in enumerate(code):
        if ch in pairs:
            stack.append((ch, index))
        elif ch in closers:
            if not stack:
                problems.append(f"{path.name}: قوس إغلاق '{ch}' زيادة")
                return
            opener, _ = stack.pop()
            if opener != closers[ch]:
                problems.append(
                    f"{path.name}: '{opener}' مقفول بـ '{ch}' — ترتيب غلط"
                )
                return
    if stack:
        line = code[: stack[-1][1]].count("\n") + 1
        problems.append(f"{path.name}: قوس '{stack[-1][0]}' مش مقفول (سطر ~{line})")


IMPORT_RE = re.compile(r"""^\s*(?:import|export)\s+['"]([^'"]+)['"]""", re.M)
DECL_RE = re.compile(r"^(?:class|enum|mixin|typedef|abstract class)\s+(\w+)", re.M)


def check_imports(path: Path) -> None:
    src = path.read_text(encoding="utf-8")
    for target in IMPORT_RE.findall(src):
        if target.startswith("dart:") or target.startswith("package:flutter"):
            continue
        if target.startswith("package:conanty/"):
            resolved = LIB / target[len("package:conanty/") :]
        else:
            resolved = (path.parent / target).resolve()
        if not resolved.exists():
            problems.append(f"{path.name}: مستورد مش موجود -> {target}")


def check_deprecated(path: Path) -> None:
    src = path.read_text(encoding="utf-8")
    for bad, hint in [
        ("withOpacity(", "استخدم withAlpha() بدالها"),
        ("MediaQuery.of(context).size", "استخدم MediaQuery.sizeOf(context)"),
        ("MaterialStateProperty", "اتغيّرت لـ WidgetStateProperty"),
        ("Color.value", "مهجورة في الإصدارات الجديدة"),
    ]:
        if bad in src:
            problems.append(f"{path.name}: API مهجور '{bad}' — {hint}")


def check_non_arabic_noise(path: Path) -> None:
    src = path.read_text(encoding="utf-8")
    for match in re.finditer(r"[\u4e00-\u9fff\uac00-\ud7af]", src):
        line = src[: match.start()].count("\n") + 1
        problems.append(f"{path.name}: حروف غريبة (CJK) في سطر {line}")


def check_pubspec() -> None:
    text = (ROOT / "pubspec.yaml").read_text(encoding="utf-8")
    if not re.search(r"^name:\s*conanty\s*$", text, re.M):
        problems.append("pubspec.yaml: اسم الحزمة مش 'conanty'")
    if "flutter_localizations:" not in text:
        problems.append("pubspec.yaml: flutter_localizations ناقصة")
    if "uses-material-design: true" not in text:
        problems.append("pubspec.yaml: uses-material-design مش مفعّلة")
    # تأكد إن مفيش assets معلنة من غير مجلد
    if re.search(r"^\s*assets:\s*$", text, re.M):
        notes.append("pubspec.yaml: فيه assets معلنة — اتأكد إن المجلد موجود")


def check_yaml(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "\t" in text:
        problems.append(f"{path.name}: فيه Tab — YAML مبيسمحش بـ Tab")
    if not text.startswith("name:"):
        problems.append(f"{path.name}: لازم يبدأ بـ name:")
    if "runs-on:" not in text:
        problems.append(f"{path.name}: مفيش runs-on")
    if "on:" not in text:
        problems.append(f"{path.name}: مفيش on:")
    try:
        import yaml  # type: ignore

        yaml.safe_load(text)
        notes.append(f"{path.name}: YAML صالح ✅ (pyyaml)")
    except ImportError:
        notes.append(f"{path.name}: pyyaml مش متاح — فحص نصي بس")
    except Exception as exc:  # noqa: BLE001
        problems.append(f"{path.name}: YAML غير صالح: {exc}")


def main() -> int:
    dart_files = sorted(ROOT.rglob("*.dart"))
    if not dart_files:
        problems.append("مفيش ملفات .dart")
        return report()

    for path in dart_files:
        check_balance(path)
        check_imports(path)
        check_deprecated(path)
        check_non_arabic_noise(path)

    check_pubspec()

    for workflow in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
        check_yaml(workflow)

    # إحصاء
    total_lines = sum(len(p.read_text(encoding="utf-8").splitlines()) for p in dart_files)
    notes.append(f"{len(dart_files)} ملف Dart — {total_lines} سطر")
    return report()


def report() -> int:
    print("=" * 62)
    for note in notes:
        print(f"  • {note}")
    print("=" * 62)
    if problems:
        print(f"❌ {len(problems)} مشكلة:")
        for problem in problems:
            print(f"   - {problem}")
        return 1
    print("✅ كل الفحوصات عدّت")
    return 0


if __name__ == "__main__":
    sys.exit(main())
