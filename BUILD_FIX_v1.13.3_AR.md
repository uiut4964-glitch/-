# إصلاح أخطاء بناء v1.13.3

- إضافة `import android.content.Context` إلى `DownloadsActivity.kt` لاستخدام `Context.CLIPBOARD_SERVICE`.
- استبدال `ScrollView.LayoutParams` بـ `FrameLayout.LayoutParams` عند إضافة محتوى إلى `ScrollView`؛ لأن `ScrollView` يرث معاملات التخطيط من `FrameLayout`.
- رفع `versionCode` إلى 36 و`versionName` إلى 1.13.3.

هذه تغييرات ترجمة/بناء فقط، ولا تغيّر منطق التنزيل أو واجهات التطبيق.
