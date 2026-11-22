const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '../..');
const main = fs.readFileSync(path.join(root, 'app/src/main/java/com/example/videobrowser/MainActivity.kt'), 'utf8');
const api = fs.readFileSync(path.join(root, 'app/src/main/java/com/example/videobrowser/ApiCaptureScript.kt'), 'utf8');
const scan = fs.readFileSync(path.join(__dirname, 'SCAN_JS.js'), 'utf8');

function must(text, pattern, message) {
  if (!pattern.test(text)) throw new Error(message);
}

must(main, /private fun hasPrerollPlayerRenditions\([\s\S]{0,650}player-flashvars[\s\S]{0,120}player-xhamster/, 'Preroll capture does not require explicit player-declared content renditions');
must(main, /val pageCandidate = shouldUseYtDlpPageExtractor\(pageUrl\)[\s\S]{0,700}pageCandidate \|\| playerPrepared \|\| normalPlayback/, 'FAB readiness must support canonical pages, safely prepared generic players, and normal playback');
must(main, /if \(isPreroll && !hasPrerollPlayerRenditions\(t\)\)/, 'FAB click is not distinguishing explicit movie renditions from the ad currentSrc');
must(main, /if \(SupportedVideoPage\.isKnownSite\(topPageUrl\) && !SupportedVideoPage\.isSupported\(topPageUrl\)\) return/, 'Known-site listing frames can still be promoted as the main video');
must(main, /!SupportedVideoPage\.isKnownSite\(pageUrl\)[\s\S]{0,280}tab\.playerConfigObjectCount > 0[\s\S]{0,180}tab\.playerMediaDefinitionCount > 0/, 'Prepared-player fallback can activate on known-site listing previews or without explicit renditions');
must(main, /if \(!item\.optBoolean\(\"playing\", false\) \\|\\| !item\.optBoolean\(\"gesture\", false\)\) continue/, 'Iframe preview promotion does not require both active playback and a real in-player gesture');
must(main, /t\.playerVideoDurationSec = 0\.0[\s\S]{0,80}t\.playerAdReason = ""/, 'Player duration/ad reason are not reset on navigation');
must(main, /\.put\("adReason", item\.optString\("adReason"\)\.take\(80\)\)[\s\S]{0,120}\.put\("playerVideoDuration", item\.optDouble\("playerVideoDuration"/, 'Frame ad diagnostic state is not forwarded to native');
must(main, /"performance-manifest"\s*->\s*0[\s\S]{0,160}"player-flashvars"\s*->\s*3/, 'Fresh Resource Timing manifests are not prioritized over stale flashvars renditions');
if (/val ad = match\.optBoolean\("ad", false\) \|\| t\.primaryIsAd/.test(main)) {
  throw new Error('A stale ad flag can keep the content classified as an ad after transition');
}
must(api, /captureSource:\s*'performance-manifest'/, 'Iframe Resource Timing manifest capture is missing');
must(scan, /capture === 'player-flashvars' \|\| capture === 'player-xhamster' \|\| capture === 'performance-manifest'/, 'Main-frame preroll capture does not retain fresh performance-manifest candidates for duration validation');
must(api, /item\.meta\.captureSource === 'player-flashvars'[\s\S]{0,180}item\.meta\.captureSource === 'performance-manifest'/, 'Iframe preroll capture does not retain fresh performance-manifest candidates for duration validation');
must(main, /val expectedDuration = t\.expectedDurationSec\.takeIf \{ it > 12\.0 \}[\s\S]{0,100}t\.playerVideoDurationSec/, 'Resolver does not use the player-declared content duration during preroll');
console.log('Stage 3.5 invariants passed: player renditions and duration-gated fresh manifests during preroll, page-identity-gated FAB, per-page reset, and non-sticky ad transition');

must(scan, /player-xhamster/, 'Main-frame xHamster source extraction is missing');
must(api, /xhModelSources|videoModel/, 'Iframe xHamster initials extraction is missing');
must(api, /frameContentTransitionAt > 0 && entryAt < frameContentTransitionAt - 1200/, 'Iframe Resource Timing does not discard stale preroll manifests');
must(main, /filterDetails|تفاصيل صيغ استبعدها فلتر المحتوى/, 'Duration-filter rejection diagnostics are missing');
must(scan, /capture === 'player-xhamster'/, 'Main-frame xHamster sources are not retained during preroll');
must(api, /item\.meta\.captureSource === 'player-xhamster'/, 'Iframe xHamster sources are not retained during preroll');
must(main, /if \(t\.resolvedKey == key && cached\.isNullOrEmpty\(\)\) \{[\s\S]{0,240}t\.resolvedKey = ""/, 'An explicit tap cannot clear an empty-result cache and retry resolution');
must(main, /t\.ytdlpAttemptedUrl = ""[\s\S]{0,100}startYtDlpPreflight\(t, pageUrl\)/, 'An empty yt-dlp extraction cannot be retried after a page reload');
console.log('Stage 3.6 invariants passed: xHamster page/frame preroll renditions, opaque-value safety, stale-manifest filtering, retryable empty results, and duration diagnostics');
