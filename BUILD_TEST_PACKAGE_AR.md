# VideoBrowser 1.12.6 — حزمة إصلاح مستخرج xHamster

هذه حزمة مصدر وليست APK مبنيًا. لم تتوفر Gradle أو Android SDK في بيئة تعديل المصدر، لذلك لم يُنفذ `assembleDebug` ولم يُختبر رابط xHamster الحقيقي على جهاز Android.

## التعديلات

- معالجة `KeyError('title')` في مستخرج xHamster الرسمي عندما يغيب `videoModel.title`.
- فك قيم المصادر المعتمة في `videoModel.sources` عبر `_decipher_format_url` وخوارزميات xHamster المضمّنة أصلًا في yt-dlp.
- الحفاظ على المصادر المباشرة كما هي، وفك التشفير فقط عند وجود قيمة غير URL أو مسار URL مشفر ببنية hex المعروفة.
- دعم `url` و`fallback` إذا جاءت بنية المصدر ككائن.
- إرفاق رقعة Python بالـAPK وإعادة تطبيقها بعد تحديث yt-dlp من داخل التطبيق.
- إزالة `--no-warnings` من استدعاء استخراج الصفحة، وتلخيص التحذيرات ذات الصلة فقط عند عودة النتيجة بلا صيغ، بعد إخفاء الروابط والمعاملات الحساسة.

## الاختبارات المحلية

- `test_patch_ytdlp_xhamster.py`: فحص fallback للعنوان، وتحويل المصدر المشفر، والتحقق من ZIP/zipapp مع بادئة shebang، وتكرار آمن للرقعة.
- جميع اختبارات JavaScript الحالية: scan, API, HLS, iframe, Shadow DOM, preroll, xHamster, Pornhub, ytdlp runtime, readiness, listing-preview.
- تحقق `bash -n tools/prepare_android_runtime.sh`.
- لم يتم تشغيل Android Gradle build؛ لا يتوفر Android SDK أو Gradle في بيئة التعديل.

## ما يجب التحقق منه في GitHub Actions والهاتف

1. أن ينجح `Build APK` ومرحلة patcher بعد مطابقة SHA-256 للـyt-dlp الرسمي.
2. أن يظهر التشخيص `xHamster title+source patch active`.
3. إعادة تجربة `https://xhamster.com/videos/super-xhKlm7R` ومراجعة عدد الصيغ وأعلى دقة حقيقية.
4. إذا لم تظهر الصيغ، إرسال سطر تحذيرات فك التشفير الجديد؛ سيحدد إن كانت القيم من خوارزمية غير مدعومة أو أن بنية الصفحة تغيرت مرة أخرى.
5. اختبار التنزيل والتشغيل والمدة والتقديم/التأخير، ثم إعادة اختبار Pornhub للتأكد من عدم تأثره.

