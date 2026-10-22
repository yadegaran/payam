# پیام — پیام‌رسان امن و خودمیزبان

پیام‌رسان دونفره با رمزنگاری سرتاسری (E2E) و قابلیت‌های چت خصوصی تلگرام. هر کسی می‌تواند سرور خودش را با **یک دستور** راه بیندازد.

- **رمزنگاری سرتاسری:** پروتکل Double Ratchet با کتابخانه [vodozemac](https://github.com/matrix-org/vodozemac)، همان رمزنگاری Matrix/Element، با امنیت پیشرو.
- **بدون ذخیره پیام روی سرور:** پیام‌ها و فایل‌ها فقط به‌صورت رمزشده، فقط در RAM و حداکثر ۲۴ ساعت روی سرور می‌مانند.
- **بدون شماره تلفن:** هر کاربر یک نام کاربری ۸ حرفی دارد که از کلید رمزنگاری‌اش ساخته می‌شود.
- **جلوگیری از فایل‌های مخرب:** روی گوشی فرستنده و گیرنده بررسی می‌شوند (APK، EXE، اسکریپت و سند ماکرودار مسدودند).

[English](#english)

---

## راه‌اندازی سرور (یک دستور)

**پیش‌نیازها:**
- یک سرور Ubuntu 20.04+ یا Debian 11+ با حداقل ۱ گیگ RAM
- یک دامنه یا ساب‌دامین که رکورد A آن مستقیم به IP سرور اشاره کند. **پشت CDN نباشد:** CDN اتصال رمزشده را باز می‌کند و چون اپ کلید سرور را بار اول به خاطر می‌سپارد، با هر بار عوض شدن گواهی CDN اتصال قطع می‌شود.
- پورت‌های 80 و 443 از اینترنت باز باشند

```bash
curl -fsSL https://raw.githubusercontent.com/yadegaran/payam/main/install.sh | sudo bash -s -- --domain chat.example.com
```

تمام. نصاب این کارها را انجام می‌دهد:
- نصب Node.js اختصاصی در `/opt/payam` (به Node و npm سیستم دست نمی‌زند)
- ساخت سرویس systemd با کاربر جدا، روی یک پورت آزاد که فقط روی 127.0.0.1 گوش می‌دهد
- اضافه کردن یک سایت nginx و گرفتن گواهی Let's Encrypt با تمدید خودکار

**روی سروری که سایت‌ها و برنامه‌های دیگر دارد هم امن است:**
- قبل از هر تغییری تداخل‌ها را بررسی می‌کند: دامنه تکراری، پورت اشغال، وب‌سرور دیگر روی 80/443. اگر تداخلی باشد، بدون تغییر دادن چیزی متوقف می‌شود و دلیل را می‌گوید.
- بقیه سایت‌های nginx و گواهی‌ها را تغییر نمی‌دهد.
- اگر nginx تنظیمات جدید را نپذیرد، تنظیمات قبلی را برمی‌گرداند.
- مصرف حافظه را با systemd محدود می‌کند تا به بقیه سرویس‌ها فشار نیاورد.

| گزینه | توضیح |
|---|---|
| `--domain` | دامنه (اجباری) |
| `--email` | ایمیل برای هشدار انقضای گواهی (اختیاری) |
| `--port` | پورت داخلی (پیش‌فرض: اولین پورت آزاد از 8095) |
| `--max-ram` | سقف RAM برای پیام‌ها و فایل‌های در صف، به مگابایت (پیش‌فرض: یک‌چهارم RAM، حداکثر 1024) |
| `--uninstall` | حذف برنامه (حساب‌ها حفظ می‌شوند) |
| `--uninstall --purge` | حذف کامل، همراه با حساب‌ها و گواهی |

- **به‌روزرسانی:** همان دستور نصب را دوباره اجرا کنید.
- **دسترسی به GitHub ندارید؟** مخزن را کپی کنید و `sudo ./install.sh --domain ...` را از داخل آن اجرا کنید. نصاب به‌جای دانلود از GitHub از فایل‌های محلی استفاده می‌کند.

## نصب اپ

1. فایل APK را از [Releases](../../releases) بگیرید. اگر روی سرور شما فایل APK گذاشته شده باشد، از `https://chat.example.com/app.apk` هم در دسترس است.
2. موقع ساخت حساب، آدرس سرور را وارد کنید: `https://chat.example.com`
3. نام کاربری یا لینک دعوت خود را به دوستانتان بدهید. **همه طرف‌های گفتگو باید روی یک سرور حساب داشته باشند.** اپ می‌تواند تا ۳ حساب روی سرورهای مختلف داشته باشد.

## امنیت: سرور چه چیزی می‌بیند

| | سرور |
|---|---|
| متن پیام، عکس، فیلم، صدا، فایل | ❌ نمی‌بیند. روی گوشی رمز می‌شوند و فقط گوشی گیرنده بازشان می‌کند |
| پیام‌ها و فایل‌های در صف (گیرنده آفلاین) | فقط به‌صورت رمزشده، **فقط در RAM**، حداکثر ۲۴ ساعت |
| کلیدهای عمومی و پروفایل (نام، بیو، عکس) | ✅ روی دیسک. پروفایل عمومی است |
| چه کسی با چه کسی، چه زمانی، حجم پیام | ⚠ در لحظه می‌بیند. نصاب لاگ را خاموش می‌کند، اما مدیر سرور می‌تواند لاگ بگیرد |

**جلوگیری از جعل کلید توسط سرور:**
- نام کاربری از هش کلید ساخته می‌شود و اپ آن را خودکار بررسی می‌کند.
- لینک یا QR دعوت شامل اثر انگشت کامل کلید است.
- کد امنیتی ۶۰ رقمی را می‌توان حضوری مقایسه کرد.
- اگر کلید با این‌ها نخواند، اپ هشدار قرمز نشان می‌دهد و پیامی نمی‌فرستد.

**اتصال به سرور:** اپ کلید سرور را بار اول به خاطر می‌سپارد. اگر بعداً عوض شود، وصل نمی‌شود. نصاب گواهی را با `--reuse-key` تمدید می‌کند تا کلید ثابت بماند.

## ساخت اپ از سورس

پیش‌نیازها:
- Android Studio (یا SDK) و JDK 17+
- NDK r28 (`Sdk/ndk/28.x`)
- Rust با targetهای اندروید و cargo-ndk:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
```

روی ویندوز اگر Visual C++ Build Tools ندارید، از toolchain گنو استفاده کنید: `rustup default stable-x86_64-pc-windows-gnu`.

**ساخت:**

```bash
cd android
./gradlew :app:assembleDebug
```

کتابخانه رمزنگاری Rust (`android/crypto-rs`) خودکار کامپایل می‌شود.

**نسخه release با کلید امضای خودتان:**

```bash
keytool -genkeypair -keystore my-release.jks -alias payam -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Payam"
```

سپس فایل `android/keystore.properties` را بسازید. این فایل در git نمی‌رود:

```properties
storeFile=/path/to/my-release.jks
storePassword=...
keyAlias=payam
keyPassword=...
```

```bash
./gradlew :app:assembleRelease                                     # بدون سرور پیش‌فرض
./gradlew :app:assembleRelease -Ppayam.server=https://chat.example.com   # با آدرس سرور از پیش پرشده
```

## ساخت خودکار APK با GitHub Actions

فایل `.github/workflows/build.yml` با هر push همه تست‌ها را اجرا می‌کند و APK را می‌سازد. فایل ساخته‌شده از صفحه همان اجرا (Actions) در بخش Artifacts قابل دانلود است.

**انتشار نسخه امضاشده:** چهار secret زیر را در **Settings → Secrets and variables → Actions** تعریف کنید و بعد یک تگ بزنید:

| Secret | مقدار |
|---|---|
| `KEYSTORE_BASE64` | فایل keystore به‌صورت base64: `base64 -w0 my-release.jks` |
| `KEYSTORE_PASSWORD` | رمز keystore |
| `KEY_ALIAS` | نام کلید (مثلاً `payam`) |
| `KEY_PASSWORD` | رمز کلید |

```bash
git tag v1.0.0 && git push origin v1.0.0
```

با زدن تگ، APK امضاشده با نام `payam.apk` در Releases منتشر می‌شود و نصاب سرور هم آن را از همان‌جا برمی‌دارد. شماره نسخه از روی تگ گذاشته می‌شود. بدون این secretها، APK نسخه debug ساخته می‌شود و هیچ نسخه‌ای منتشر نمی‌شود.

## تست‌ها

```bash
cd server && npm install && node --test             # پروتکل، صف RAM، انقضای ۲۴ ساعته، سقف حافظه، حریم خصوصی
cd android/crypto-rs && cargo test --release        # Double Ratchet، رد پیام تکراری، fallback key، امضا
cd android && ./gradlew :app:testDebugUnitTest      # FileGuard، رمزنگاری فایل، نام کاربری، پشتیبان، لینک دعوت
```

## ساختار

```
install.sh   نصاب یک‌دستوری سرور
server/      سرور Node.js (فقط وابستگی: ws)
android/     اپ اندروید (Kotlin + Jetpack Compose)
  crypto-rs/   پل JNI به vodozemac
```

## محدودیت‌ها

- **چند دستگاه:** هر حساب فقط روی یک گوشی کار می‌کند.
- **پشتیبان:** فقط هویت و لیست مخاطبین پشتیبان گرفته می‌شود، نه تاریخچه چت.
- **ارتباط بین سرورها:** سرورها با هم ارتباط ندارند (فدراسیون نیست).
- **فراداده:** sealed sender پیاده نشده، پس سرور می‌داند چه کسی به چه کسی پیام می‌دهد.
- **دیتابیس گوشی:** دیتابیس چت روی گوشی رمزنگاری جداگانه ندارد و به رمزنگاری پیش‌فرض اندروید و جداسازی اپ‌ها اکتفا شده است.

## مجوز

[AGPL-3.0](LICENSE). اگر نسخه تغییریافته سرور یا اپ را در اختیار دیگران می‌گذارید، باید کد آن را هم منتشر کنید.

---

## English

**Payam** is a self-hosted, end-to-end encrypted 1:1 messenger:
- Android app (Kotlin/Compose) with vodozemac Double Ratchet
- A tiny Node.js relay that stores **no messages or files on disk**: ciphertext only, in RAM, for at most 24 h
- No phone numbers: usernames are derived from identity keys, so a server that swaps keys is detected
- Malicious files (APK/EXE/scripts/macro documents) are blocked on both phones by content inspection

**Install a server** (Ubuntu/Debian, domain pointing directly at the server, ports 80/443 open):

```bash
curl -fsSL https://raw.githubusercontent.com/yadegaran/payam/main/install.sh | sudo bash -s -- --domain chat.example.com
```

The installer is safe on shared servers:
- private Node.js, own system user, free loopback port
- adds one nginx site and a Let's Encrypt certificate (auto-renewed, stable key)
- checks for conflicts first and aborts without changing anything
- memory is capped by systemd

Re-run to update. `--uninstall [--purge]` to remove.

**App:** grab the APK from Releases and enter `https://chat.example.com` when signing up. Build from source as described above.

License: AGPL-3.0.
