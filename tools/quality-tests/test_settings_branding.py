from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
settings = (ROOT / "app/src/main/java/com/example/videobrowser/SettingsActivity.kt").read_text()
extractor = (ROOT / "app/src/main/java/com/example/videobrowser/YtDlpExtractor.kt").read_text()
downloads = (ROOT / "app/src/main/java/com/example/videobrowser/DownloadsActivity.kt").read_text()
main = (ROOT / "app/src/main/java/com/example/videobrowser/MainActivity.kt").read_text()

assert 'contentDescription = "رجوع"' in settings
assert 'scaleX = -1f' in settings
assert 'setOnClickListener { finish() }' in settings
assert 'محرك استخراج الفيديو' in settings
assert 'محرك استخراج المواقع (yt-dlp)' not in settings
assert 'xHamster' not in settings and 'Pornhub' not in settings and 'yt-dlp' not in settings
assert 'fun status(context: Context): String' in extractor
assert 'initialized -> "جاهز"' in extractor
assert 'اتصال تنزيل واحد' in downloads
assert 'اتصال yt-dlp واحد' not in downloads
assert 'محرك استخراج الفيديو:' in main
assert not (ROOT / 'tools/patch_ytdlp_xhamster.py').exists()
assert not (ROOT / 'app/src/main/assets/patch_ytdlp_xhamster.py').exists()
assert (ROOT / 'tools/patch_site_extractor.py').is_file()
assert (ROOT / 'app/src/main/assets/site_extractor_patch.py').is_file()
print('Settings branding/navigation and neutral internal filenames passed')
