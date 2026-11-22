from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = (ROOT / "app/src/main/java/com/example/videobrowser/MainActivity.kt").read_text()
DOWNLOADS = (ROOT / "app/src/main/java/com/example/videobrowser/DownloadsActivity.kt").read_text()
THUMBS = (ROOT / "app/src/main/java/com/example/videobrowser/ThumbLoader.kt").read_text()

assert 'poster: pagePoster' in MAIN, "SCAN_JS must expose page-level poster metadata"
assert 'val pagePoster = o.optString("poster").trim()' in MAIN, "native scan must persist page poster"
assert 'takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let { t.poster = it }' in MAIN, "empty player poster must not erase an existing page poster"
assert 'private fun showTabs()' in MAIN and 'Dialog(this)' in MAIN, "tab chooser must use the side drawer dialog"
assert 'contentDescription = "إغلاق علامة التبويب' in MAIN, "each tab needs an accessible individual close action"
tabs_block = MAIN[MAIN.index('    private fun showTabs() {'):MAIN.index('    private fun go(input: String)', MAIN.index('    private fun showTabs() {'))]
assert '.setItems(' not in tabs_block and 'Dialog(this)' in tabs_block, "old unseparated AlertDialog tab list must be removed"
assert 'St.FAILED -> "فشل التنزيل • من القائمة يمكنك نسخ التفاصيل"' in DOWNLOADS, "failure text must be compact"
assert 'نسخ تفاصيل الخطأ' in DOWNLOADS and 'ClipData.newPlainText' in DOWNLOADS, "full failure details must remain copyable"
assert 'RETRY_FAILED_AFTER_MS' in THUMBS and 'failedAt[key] = System.currentTimeMillis()' in THUMBS, "temporary thumbnail failures must be retried"
assert 't.kind != "direct" && t.kind != "hls"' in THUMBS, "thumbnail frame fallback should include HLS streams"
assert 'import android.content.Context' in DOWNLOADS, "DownloadsActivity must import Context for clipboard service"
assert 'scroll.addView(rows, FrameLayout.LayoutParams(MATCH, WRAP))' in MAIN, "ScrollView child must use FrameLayout.LayoutParams"

print("Download UI/build regressions passed: poster propagation, tab drawer, compact/copyable errors, retryable thumbnails, and Kotlin Android imports/layout params")
