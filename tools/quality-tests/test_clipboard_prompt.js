const fs = require('fs');
const path = require('path');
const assert = require('assert');

const mainPath = path.join(__dirname, '../../app/src/main/java/com/example/videobrowser/MainActivity.kt');
const source = fs.readFileSync(mainPath, 'utf8');

const scanStart = source.indexOf('private fun consumeClipboardVideoUrlOnResume()');
const scanEnd = source.indexOf('private fun normalizeClipboardWebUrl', scanStart);
assert(scanStart >= 0 && scanEnd > scanStart, 'Clipboard resume handler must exist');
const resumeHandler = source.slice(scanStart, scanEnd);
assert(resumeHandler.includes('normalizeClipboardWebUrl(rawCopied)'), 'Clipboard text must be validated as a web URL');
assert(resumeHandler.includes('showClipboardLinkPrompt(copied)'), 'A prompt must be shown for a new copied URL');
assert(!resumeHandler.includes('newTab(copied)'), 'A copied URL must never navigate automatically');
assert(!resumeHandler.includes('shouldUseYtDlpPageExtractor(copied)'), 'Prompt must work for ordinary web URLs, not video pages only');
assert(resumeHandler.includes('lastClipboardVideoUrl = copied'), 'Seen clipboard URL must be remembered to prevent repeated prompts after Cancel');

const normalizeStart = scanEnd;
const promptStart = source.indexOf('private fun showClipboardLinkPrompt', normalizeStart);
assert(promptStart > normalizeStart, 'Clipboard URL normalization must exist');
const normalizeHandler = source.slice(normalizeStart, promptStart);
assert(normalizeHandler.includes('https://') && normalizeHandler.includes('http://'), 'Only HTTP(S) URLs should be accepted');
assert(normalizeHandler.includes('uri.host'), 'URL must have a host');

const dismissStart = source.indexOf('private fun dismissClipboardLinkPrompt', promptStart);
const pauseStart = source.indexOf('override fun onPause()', dismissStart);
assert(dismissStart > promptStart && pauseStart > dismissStart, 'Prompt show/dismiss handlers must exist');
const promptHandlers = source.slice(promptStart, pauseStart);
assert(promptHandlers.includes('text = "الرابط المنسوخ"'), 'Prompt must have the copied-link heading');
assert(promptHandlers.includes('text = url'), 'Prompt must display the copied URL');
assert(promptHandlers.includes('actionButton("فتح"'), 'Prompt must have an Open action');
assert(promptHandlers.includes('actionButton("إلغاء"'), 'Prompt must have a Cancel action');
assert(promptHandlers.includes('content.addView(card'), 'Prompt must be a small overlay in the WebView FrameLayout');
assert(promptHandlers.includes('open.setOnClickListener') && promptHandlers.includes('newTab(url)'), 'Open must open the copied URL in a new tab');
assert(promptHandlers.includes('cancel.setOnClickListener { dismissClipboardLinkPrompt() }'), 'Cancel must only dismiss the prompt');
assert(!/cancel\.setOnClickListener\s*\{[^}]*loadUrl/s.test(promptHandlers), 'Cancel must not navigate or reload the page');
assert(!promptHandlers.includes('AlertDialog.Builder'), 'Clipboard prompt must not be a blocking dialog');

console.log('Clipboard prompt regression passed: non-blocking overlay, HTTP(S) validation, Open in new tab, Cancel only dismisses, and no automatic navigation');
