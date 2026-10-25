#!/usr/bin/env bash
# يولّد مجلد android/ من قالب Flutter المطابق للإصدار المثبّت عندك.
# ليه؟ لأن ملفات Gradle/AGP مرتبطة بإصدار Flutter، وتوليدها تلقائيًا
# أضمن بكتير من نسخ ملفات جاهزة قد تتعارض مع إصدارك.

set -euo pipefail

cd "$(dirname "$0")/.."

if [ -d android ]; then
  echo "✅ مجلد android/ موجود بالفعل — مفيش حاجة تتعمل."
  exit 0
fi

if ! command -v flutter >/dev/null 2>&1; then
  echo "❌ flutter مش موجود في PATH. ثبّته الأول: https://docs.flutter.dev/get-started/install"
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

echo "⏳ بيتم توليد قالب أندرويد..."
flutter create \
  --platforms=android \
  --project-name conanty \
  --org com.conanty \
  "$tmp_dir/scaffold" >/dev/null

cp -r "$tmp_dir/scaffold/android" ./android

echo "✅ تم توليد android/."
echo "   الخطوة الجاية:"
echo "     flutter pub get"
echo "     flutter run          # على جهاز/محاكي متصل"
echo "     flutter build apk --release   # لإنتاج APK"
