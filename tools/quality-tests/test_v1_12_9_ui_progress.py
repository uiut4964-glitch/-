from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
main = (ROOT / "app/src/main/java/com/example/videobrowser/MainActivity.kt").read_text()
downloads = (ROOT / "app/src/main/java/com/example/videobrowser/DownloadsActivity.kt").read_text()
center = (ROOT / "app/src/main/java/com/example/videobrowser/DownloadCenter.kt").read_text()
bar = (ROOT / "app/src/main/java/com/example/videobrowser/SegmentBar.kt").read_text()

sheet_start = main.index("private fun showFormatSheet(")
sheet_end = main.index("private fun startDownload(", sheet_start)
sheet = main[sheet_start:sheet_end]
assert "GridLayout(this)" in sheet
assert "cardsRef" in sheet and "cornerRadius = dp(10).toFloat()" in sheet
assert "dlg.behavior.isDraggable = false" in sheet
assert "val sv = ScrollView(this)" in sheet
assert "root.addView(head)" in sheet and "root.addView(sv," in sheet and "root.addView(btns," in sheet
assert 'text = "تشغيل"' in sheet and 'else "تحميل"' in sheet
assert "إعادة التسمية" not in sheet
assert "DownloadCenter.refresh" not in sheet

assert "Engine.refreshYtDlpProgress(t)" in center
assert 'it.name.startsWith("p0.")' in center
assert '"مكتمل • "' in downloads
assert 'اتصال تنزيل واحد' in downloads
assert "setIndeterminate(" in downloads and "indeterminate && !hasProgressRange" in bar

print("v1.12.9 UI/progress source-contract checks passed")
