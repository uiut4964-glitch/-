'use strict';

const fs = require('fs');
const path = require('path');
const assert = require('assert');

const root = path.resolve(__dirname, '../..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');

const gradle = read('app/build.gradle.kts');
const settingsGradle = read('settings.gradle.kts');
const manifest = read('app/src/main/AndroidManifest.xml');
const extractor = read('app/src/main/java/com/example/videobrowser/YtDlpExtractor.kt');
const main = read('app/src/main/java/com/example/videobrowser/MainActivity.kt');
const downloads = read('app/src/main/java/com/example/videobrowser/DownloadCenter.kt');
const models = read('app/src/main/java/com/example/videobrowser/Models.kt');
const settings = read('app/src/main/java/com/example/videobrowser/SettingsActivity.kt');
const app = read('app/src/main/java/com/example/videobrowser/App.kt');
const workflow = read('.github/workflows/build.yml');
const prepare = read('tools/prepare_android_runtime.sh');
const xhamsterPatcher = read('tools/patch_site_extractor.py');
const notices = read('THIRD_PARTY_NOTICES.md');

assert.match(gradle, /org\.apache\.commons:commons-compress:1\.27\.1/,
  'The runtime extractor must preserve Unix symlinks in the Android Python runtime ZIP.');
assert.match(settingsGradle, /include\(\":app\"\)/,
  'The project must build one installable app module.');
assert.doesNotMatch(settingsGradle, /python-runtime/,
  'No separate Python runtime APK module should be required.');
assert.match(gradle, /useLegacyPackaging\s*=\s*true/,
  'The single app APK must package native runtime binaries so Android installs them in nativeLibraryDir.');
assert.doesNotMatch(manifest, /com\.example\.videobrowser\.pythonruntime/,
  'The main manifest must not require visibility to a helper package.');
assert.doesNotMatch(manifest, /android:extractNativeLibs=/,
  'AGP packaging DSL owns extractNativeLibs; the manifest should not declare the deprecated attribute.');

assert.match(extractor, /libpython\.so/);
assert.match(extractor, /libpython\.zip\.so/);
assert.match(extractor, /PYTHONHOME/);
assert.match(extractor, /LD_LIBRARY_PATH/);
assert.match(extractor, /import sys,curl_cffi/,
  'Runtime initialization must fail clearly unless curl_cffi is importable.');
assert.match(extractor, /--impersonate", "chrome"/,
  'Pornhub extraction and downloads must use the Chrome TLS impersonation backend.');
assert.match(extractor, /--cookies/);
assert.match(extractor, /createCookieFile/,
  'Cookies must be written to a temporary Netscape jar, not passed in a Cookie header.');
assert.match(extractor, /fun downloadToFile\(/,
  'Selected yt-dlp formats must be downloadable through the same curl_cffi runtime.');
assert.doesNotMatch(extractor, /YoutubeDL\.getInstance|YoutubeDLRequest|--add-headers.*Cookie/,
  'The older Python 3.8 wrapper and deprecated Cookie-header method must not remain.');
assert.match(extractor, /curl_cffi\.__version__/,
  'The current runtime must verify the bundled curl_cffi package.');

assert.match(workflow, /node test_ytdlp_curl_runtime\.js/);
assert.match(workflow, /prepare_android_runtime\.sh/);
assert.match(prepare, /python-3\.14\.8/);
assert.match(prepare, /app\/src\/main\/jniLibs/);
assert.match(extractor, /applicationInfo\.nativeLibraryDir/);
assert.doesNotMatch(extractor, /PYTHON_HELPER_PACKAGE|helperNativeLibraryDir/);
assert.match(extractor, /python\.canExecute\(\)/,
  'The app must verify that the APK-installed Python executable is runnable.');
assert.doesNotMatch(extractor, /pythonruntime|Runtime Helper APK/,
  'The runtime must not depend on a second installed package.');
assert.match(workflow, /gradle :app:assembleDebug/);
assert.doesNotMatch(workflow, /python-runtime:assembleDebug|python-runtime-debug/);
assert.doesNotMatch(workflow, /VideoBrowser-APK-and-Python-Runtime/);
assert.match(workflow, /name: VideoBrowser-APK/);
assert.match(prepare, /073430d0db34333a4a9d6b3e6801c3a00212f1a9632e05182c18165ab1ee1ca4/);
assert.match(prepare, /2026\.10\.26/);
assert.match(prepare, /e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3/);
assert.match(prepare, /site_extractor_patch\.py/,
  'The verified upstream zipapp must receive the xHamster compatibility patch before APK packaging.');
assert.match(prepare, /cp .*patch_site_extractor\.py.*site_extractor_patch\.py/,
  'The in-app patcher must also be packaged so later yt-dlp updates retain the fix.');
assert.match(xhamsterPatcher, /VB_XHAMSTER_TITLE_GUARD/);
assert.match(xhamsterPatcher, /VB_XHAMSTER_SOURCE_DECIPHER/);
assert.match(extractor, /applyXHamsterExtractorPatch\(context, ytDlpAsset\)/,
  'Existing installed yt-dlp copies must receive the xHamster patch after app upgrades.');
assert.match(extractor, /applyXHamsterExtractorPatch\(app, script\)/,
  'The in-app yt-dlp updater must reapply the xHamster patch after downloading upstream updates.');
assert.match(extractor, /initialized -> \"جاهز\"/);
assert.doesNotMatch(settings, /xHamster|Pornhub|yt-dlp/, 'Settings must not expose implementation or site names.');
const extractBlock = extractor.match(/fun extract\([\s\S]*?fun downloadToFile\(/);
assert.ok(extractBlock, 'The yt-dlp extraction method must exist.');
assert.doesNotMatch(extractBlock[0], /--no-warnings/,
  'Extractor warnings such as unknown xHamster cipher algorithm IDs must be visible in diagnostics.');
assert.match(extractor, /summarizeExtractorWarnings\(process\.stderr\)/,
  'When extraction returns no formats, safe cipher diagnostics should be summarized.');


const thumbnailBlock = extractor.match(/private fun thumbnailUrl\(info: JSONObject\): String \{([\s\S]*?)\n    \}\n\n    private fun mapFormats/);
assert.ok(thumbnailBlock, 'The yt-dlp page-thumbnail selector must exist.');
assert.doesNotMatch(thumbnailBlock[1], /Formats\.isPreviewUrl/,
  'A real thumbnail URL commonly contains thumb/poster/preview and must not be discarded by media-preview filtering.');
assert.match(main, /t\.expectedDurationSec = f\.durSec\.takeIf \{ it\.isFinite\(\) && it > 0\.0 \} \?: 0\.0/,
  'The selected format duration must be retained for the download record.');

assert.match(main, /startYtDlpPreflight\(t, url\)/,
  'Page-first extraction must start during navigation and retry after the document loads.');
assert.match(main, /consumeClipboardVideoUrlOnResume/);
assert.match(main, /pageCandidate \|\| playerPrepared \|\| normalPlayback/,
  'The FAB must turn red on a canonical supported video page, a validated prepared player, or normal playback.');
assert.match(main, /!SupportedVideoPage\.isKnownSite\(pageUrl\)/,
  'Player-prepared fallback must never activate on known-site listing pages.');
assert.match(main, /tab\.playerConfigObjectCount > 0/);
assert.match(main, /tab\.playerMediaDefinitionCount > 0/);
assert.match(main, /tab\.playerDefinitionQualities\.isNotBlank\(\)/);
assert.match(main, /ytdlpAttemptCount < 2/);
assert.match(main, /mergeFormatLists\(t\.resolvedFormats\.orEmpty\(\), result\.formats\)/,
  'yt-dlp output must be merged into the existing quality list.');
assert.match(main, /YtDlpExtractor\.status\(this@MainActivity\)/);
assert.match(main, /FormatSelection\.deduplicate\(primary \+ secondary\)/,
  'Merged WebView and yt-dlp formats must be collapsed to one downloadable choice per resolution.');
assert.match(main, /val list = FormatSelection\.deduplicate\(formats\)/,
  'The visible quality sheet must apply the one-choice-per-resolution policy.');
assert.match(downloads, /t\.ytDlpPageUrl\.isNotBlank\(\) && t\.ytDlpFormatId\.isNotBlank\(\)/,
  'yt-dlp-origin formats must bypass the legacy HttpURLConnection probe/download path.');
assert.match(downloads, /YtDlpExtractor\.downloadToFile\(/);
assert.match(downloads, /ytDlpDownloadedOutput/);
assert.match(models, /var ytDlpPageUrl = ""/);
assert.match(models, /var ytDlpFormatId = ""/);
assert.match(app, /YtDlpExtractor\.warmUp\(this\)/);
assert.match(settings, /YtDlpExtractor\.update\(this\)/);
assert.match(notices, /Python 3\.14\.8/);
assert.match(notices, /curl_cffi 0\.17\.0/);
assert.match(notices, /GNU GPL-3\.0/);

console.log('PASS: single-APK Python/curl_cffi runtime, early page extraction, clipboard URL pickup, red FAB readiness, Chrome impersonation, temporary cookies, yt-dlp extraction/download, workflow verification, and licenses.');
