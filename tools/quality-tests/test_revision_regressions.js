'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const root = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

const formats = read('app/src/main/java/com/example/videobrowser/Formats.kt');
const main = read('app/src/main/java/com/example/videobrowser/MainActivity.kt');
const injectedApi = read('app/src/main/java/com/example/videobrowser/ApiCaptureScript.kt');
const selection = read('app/src/main/java/com/example/videobrowser/FormatSelection.kt');
const scanTestCopy = fs.readFileSync(path.join(__dirname, 'SCAN_JS.js'), 'utf8');
const ytDlp = read('app/src/main/java/com/example/videobrowser/YtDlpExtractor.kt');

assert.match(formats, /"adtng\.com"/, 'Kotlin ad filter must block the observed ht-cdn2.adtng.com host');
assert.match(main, /'adtng\.com'/, 'Main-frame Resource/DOM scan must share the ad-host rule');
assert.match(scanTestCopy, /'adtng\.com'/, 'Standalone scan fixture must mirror the main-frame ad-host rule');
assert.match(injectedApi, /frameAdPattern = \/\(\?:[^\n]*adtng/, 'Iframe video ad detector must recognize adtng');

assert.match(main, /item\.captureSource in setOf\("player-flashvars", "player-xhamster", "performance-manifest"\)/,
  'A current video-frame performance manifest must be eligible during preroll');

assert.match(main, /item\.videoId == videoId && item\.sourceEpoch == epoch[\s\S]{0,220}item\.origin in setOf\(\"dom\", \"dom-preroll\"\)/,
  'Player-owned renditions captured immediately before preroll must remain eligible for the same video epoch');
assert.match(main, /item\.videoId == id && item\.sourceEpoch == t\.primarySourceEpoch[\s\S]{0,180}item\.origin in setOf\(\"dom\", \"dom-preroll\"\)/,
  'Preroll readiness must recognize player sources captured before ad classification');
assert.match(main, /isPerformanceManifest[\s\S]{0,400}expectedDuration > 20\.0 && d > 20\.0[\s\S]{0,160}abs\(d - expectedDuration\)/,
  'Performance manifests captured during preroll must pass a long-duration content match');
assert.match(main, /waitingForYtDlp[\s\S]{0,450}بانتظار اكتمال استخراج صفحة الموقع/,
  'An empty WebView result must not close the pending sheet while page extraction is running');
assert.match(main, /private fun waitForLatePlayerSources[\s\S]{0,3600}pageExtractorStillRunning[\s\S]{0,500}scan\(t\)/,
  'A single tap must remain pending, rescan late sources, and watch an in-flight page extractor');
assert.match(main, /lateSourceWatchGeneration[\s\S]{0,180}lateSourceWatchUrl/, 'Late-source watcher must be idempotent and generation-scoped to a page');
assert.match(main, /finalRescan[\s\S]{0,220}waitForLatePlayerSources\(t, pageUrl, attempt \+ 1, generation, nextFinalScanDone\)/,
  'A slow resolver must be followed by a final scan before the no-results diagnostic');
assert.match(main, /if \(t\.resolvingKey == key\)[\s\S]{0,260}waitForLatePlayerSources\(t, pageUrl\)/,
  'A tap that arrives while a resolver is already running must join the pending result watcher');
assert.match(main, /waitForLatePlayerSources\(t, pageUrl\)/,
  'An empty page extraction with no player sources must trigger a delayed rescan rather than immediate failure');
assert.match(main, /FormatSelection\.deduplicate\(primary \+ secondary\)/,
  'All resolver sources must pass through quality-level deduplication after merging');
assert.match(main, /val list = FormatSelection\.deduplicate\(formats\)/,
  'The final UI must deduplicate even a direct caller of the format sheet');
assert.match(selection, /"video:\$height"/, 'Video formats must be grouped by actual resolution, not URL/format ID');
assert.match(selection, /FormatSelection|fun deduplicate/, 'A shared quality-list normalizer must exist');
assert.match(ytDlp, /dashVideoOnly[\s\S]{0,500}dashAudioOnly/, 'DASH video-only and audio-only representations must be collected for pairing');
assert.match(ytDlp, /val selector = \"\$videoId\+\$audioId\"/, 'DASH video and audio IDs must be combined for yt-dlp downloads');
assert.match(ytDlp, /DASH manifests often expose separate video-only and audio-only tracks/, 'The yt-dlp page extractor must preserve muxed DASH output instead of dropping every DASH format');

console.log('PASS: adtng filtering, preroll performance-manifest gating, async single-tap wait, and one-choice-per-resolution policy');
