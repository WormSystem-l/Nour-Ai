# Conanty 💬

واجهة محادثات **مستوحاة من واتساب**، مبنية بـ **Flutter** — عربي بالكامل و RTL،
بوضع فاتح وداكن، وشغالة ببيانات تجريبية محلية (مفيش سيرفر ولا حسابات).

> نسخة **UI فقط**: كل المحادثات والرسايل والحالات والمكالمات بيانات وهمية في
> `lib/data/demo_data.dart`. جاهزة إنك توصّلها بسيرفر بعدين.

---

## ✅ تم التحقق منه فعليًا

اتفحص المشروع بـ Flutter SDK حقيقي (مش تقديري):

| | |
|---|---|
| **Flutter** | 3.47.8 · stable |
| **Dart** | 3.13.7 |
| `flutter pub get` | ✅ نجح |
| `flutter analyze` | ✅ **No issues found** |
| `flutter test` | ✅ **12/12 اختبار عدّوا** |
| توليد قالب `android/` | ✅ نجح (AGP 9.1.0 · Gradle 9.3.1 · Kotlin 2.4.0) |

> البناء النهائي للـ APK (Gradle + Android SDK) لم يُنفّذ محليًا — بيحصل في
> GitHub Actions. الـ workflow بيستخدم **Java 21** لأن AGP 9 يحتاجه.

---

## ✨ المميزات

| | |
|---|---|
| **3 تبويبات** | المحادثات · الحالة · المكالمات |
| **شاشة محادثة كاملة** | فقاعات رسايل، وقت، علامات قراءة (✓ / ✓✓)، شريط كتابة |
| **رد تلقائي** | بعد ~0.9 ثانية، لإحساس المحادثة الحقيقية |
| **بحث فوري** | في أسماء المحادثات ونصوص آخر رسالة |
| **حالة القراءة** | عدّاد غير مقروء + تثبيت + كتم |
| **RTL كامل** | واجهة عربية من اليمين للشمال |
| **ثيم فاتح/داكن** | مع خيار «حسب النظام» |
| **بدون صور** | الأفاتار بحروف الاسم — مفيش مشاكل assets |

---

## 📱 إزاي تطلع ملف APK (الطريقة اللي طلبتها)

المشروع مجهّز لـ GitHub Actions. **مش محتاج تنصّب Flutter على جهازك.**

```bash
cd conanty
git init
git add .
git commit -m "Conanty: WhatsApp-like chat UI"
git branch -M main
git remote add origin https://github.com/USERNAME/conanty.git
git push -u origin main
```

بعد الـ push:

1. افتح الريبو على GitHub → تبويب **Actions**
2. هتلاقي workflow اسمه **Build APK** بدأ يشتغل لوحده
3. استنى ~5–8 دقايق لحد ما يبقى ✅
4. انزل تحت لـ **Artifacts** → حمّل **`conanty-apk`**
5. فك الضغط → هتلاقي `app-release.apk` → انقله للموبايل وثبّته

> لو عايز تشغّله يدوي: Actions → Build APK → **Run workflow**.

### إصدار مرقّم (Release)

```bash
git tag v1.0.0
git push origin v1.0.0
```

workflow **Release APK** هيبني نسخة لكل معمارية (`arm64-v8a`, `armeabi-v7a`, `x86_64`)
ويرفعهم على صفحة **Releases** في الريبو.

---

## 🖥️ التشغيل محليًا (اختياري)

محتاج [Flutter SDK](https://docs.flutter.dev/get-started/install) مثبّت.

```bash
cd conanty
flutter pub get
./tool/setup_android.sh   # يولوّد مجلد android/ مرة واحدة
flutter run               # على محاكي أو موبايل متصل
```

### الاختبارات

```bash
flutter test
flutter analyze
```

---

## 🧱 ليه مفيش مجلد `android/` في المشروع؟

سؤال مهم — وده قرار مقصود.

ملفات `android/` (Gradle + AGP + Kotlin) **مرتبطة بإصدار Flutter**. لو جبتها
من مشروع تاني بإصدار مختلف، البناء بيفشل بأخطاء غامضة زي:

```
Could not find com.android.tools.build:gradle:X.Y.Z
Unsupported class file major version
```

الإصدار الحالي (Flutter 3.47.8) بيولّد:
`AGP 9.1.0` · `Gradle 9.3.1` · `Kotlin 2.4.0` · **Kotlin DSL** (`build.gradle.kts`)
· ويحتاج **JDK 21**.

عشان كده:

- **في CI**: الـ workflow بيولّد `android/` من قالب **نفس إصدار Flutter المثبّت**،
  في `$RUNNER_TEMP` (خارج المشروع)، وبعدين ينسخ `android/` بس. مفيش خطر إنه
  يلمس كودك.
- **محليًا**: نفس الحكاية بأمر واحد عبر `tool/setup_android.sh`.

لو حبيت تثبّت المجلد في الريبو بعد التوليد، اعمله commit عادي — الـ workflow
هيستخدمه زي ما هو (بيتحقق من وجوده الأول).

---

## 📂 هيكل المشروع

```
conanty/
├── .github/workflows/
│   ├── build-apk.yml          # بناء + رفع APK كـ artifact
│   └── release-apk.yml        # بناء ونشر على Releases عند عمل tag
├── lib/
│   ├── main.dart              # نقطة البداية
│   ├── app.dart               # MaterialApp + الثيم + اللغة
│   ├── theme/
│   │   ├── app_theme.dart     # ألوان واتساب + الثيمين
│   │   └── theme_controller.dart
│   ├── models/                # Chat · Message · StatusItem · CallEntry
│   ├── data/demo_data.dart    # ← كل البيانات التجريبية هنا
│   ├── utils/time_format.dart # تنسيق الوقت بالعربي
│   ├── widgets/               # Avatar · ChatTile · MessageBubble
│   └── screens/               # Home · Chats · Chat · Status · Calls · Settings
├── test/
│   ├── widget_test.dart       # اختبارات الواجهة (فتح محادثة، إرسال، بحث)
│   └── time_format_test.dart  # اختبارات تنسيق الوقت
├── tool/setup_android.sh
├── analysis_options.yaml
├── pubspec.yaml
└── README.md
```

---

## 🎨 التخصيص

**الاسم والشعار**: عدّل `pubspec.yaml` (الحقل `name`) و `android:label`
بعد توليد `android/`، أو غيّر عنوان الـ AppBar في `lib/screens/home_screen.dart`.

**الألوان**: كلها في `lib/theme/app_theme.dart` → `AppColors`.

```dart
static const Color teal = Color(0xFF00A884);  // اللون الأساسي
```

**البيانات**: `lib/data/demo_data.dart` — ثلاث دوال:
`buildDemoChats()` · `buildDemoStatuses()` · `buildDemoCalls()`.

**اسم التطبيق و appId**: في الـ workflow مكتوب `--org com.conanty` و
`--project-name conanty`. غيّرهم قبل أول تشغيل لو عايز appId مختلف.

---

## 🔐 توقيع نسخة الإصدار (لما تنشر على Google Play)

الـ APK الحالي **موقّع بمفتاح debug** — بيتثبّت عادي على أي موبايل، لكن مش
مناسب للنشر. للتوقيع الحقيقي:

```bash
keytool -genkey -v -keystore ~/conanty-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias conanty
```

حوّل الملف لـ base64 وأضفه كـ secret:

```bash
base64 -w 0 ~/conanty-release.jks > keystore.txt
```

أضف في GitHub → Settings → Secrets:

| Secret | القيمة |
|---|---|
| `KEYSTORE_BASE64` | محتوى `keystore.txt` |
| `KEYSTORE_PASSWORD` | كلمة سر الـ keystore |
| `KEY_ALIAS` | `conanty` |
| `KEY_PASSWORD` | كلمة سر المفتاح |

وبعدها ضيف خطوة قبل البناء في الـ workflow تفك الـ keystore وتضبط
`android/key.properties` — (مش مضروبة هنا عشان المشروع يبني من غير أي secrets).

---

## 🩺 استكشاف الأخطاء

| المشكلة | الحل |
|---|---|
| `flutter: command not found` | استخدم Actions، أو ثبّت Flutter SDK |
| البناء واقف عند `pub get` | اتأكد إن فيه إنترنت، وجرّب `flutter pub cache repair` |
| `Execution failed for task ':app:...'` | امسح `android/` وشغّل `./tool/setup_android.sh` تاني |
| الـ APK مش بيتثبّت | اتأكد إن «تثبيت من مصادر غير معروفة» مفعّل |
| الخط العربي مش ظاهر صح | الجهاز لازم يكون فيه خط عربي — أندرويد بيوفره افتراضيًا |
| `analyze` بيشتكي من تحذيرات | الـ CI متظبّط على `--no-fatal-warnings`، فمش هتكسر |

---

## 🚀 خطوات جاية (لو حبيت تكمّل)

1. **شات حقيقي** — Supabase أو Firebase (أسهل)، أو Node + Socket.io
2. **تخزين محلي** — `sqflite` أو `hive` لحفظ الرسايل بعد قفل التطبيق
3. **صورة البروفايل** — `image_picker` + رفع
4. **إشعارات** — `firebase_messaging`
5. **مجموعات** — إضافة `senderName` للرسالة + شاشة معلومات الجروب
6. **مكالمات** — WebRTC (شغل كبير، يفضل خدمة جاهزة)

---

## 📄 الترخيص

استخدمه زي ما تحب. الأسماء والعلامات التجارية في البيانات التجريبية للتوضيح فقط.
