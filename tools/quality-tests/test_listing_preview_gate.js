const fs = require('fs');
const scan = fs.readFileSync(__dirname + '/SCAN_JS.js', 'utf8');

function run(url, videos) {
  const document = {
    baseURI: url,
    querySelectorAll(sel) { return sel === 'video' ? videos : []; },
    addEventListener() {}
  };
  const window = {
    innerWidth: 1280, innerHeight: 800, location: {href:url},
    VBVideoGesture: {onVideoEvent(){}, onVideoGesture(){}}
  };
  const performance = {timeOrigin:Date.now(), getEntriesByType(){return [];}};
  const getComputedStyle = () => ({display:'block', visibility:'visible', opacity:'1'});
  const exec = new Function('window','document','location','getComputedStyle','performance',
    'const result=' + scan.trim().replace(/;\\s*$/, '') + '; return JSON.parse(result);');
  return exec(window, document, {href:url}, getComputedStyle, performance);
}
function video(parent, width, height, opts={}) {
  return {
    tagName:'VIDEO', parentElement:parent, currentSrc:'blob:https://www.pornhub.com/video-test-' + width + 'x' + height,
    paused:opts.paused ?? true, ended:false, readyState:4, currentTime:opts.currentTime ?? 0, duration:120,
    videoWidth:width, videoHeight:height, poster:opts.poster || '',
    getAttribute(name){return name==='src'?this.currentSrc:null;},
    getBoundingClientRect(){return {left:10,top:10,right:10+width,bottom:10+height,width,height};},
    querySelectorAll(){return [];}, addEventListener(){}
  };
}
const makeContainer = (className) => ({id:'', className, parentElement:null, getAttribute(){return null;}, querySelectorAll(){return [];}});

// A listing page containing large autoplay previews must not produce a primary player or API fallback.
const cardA = makeContainer('video-card preview-video');
const cardB = makeContainer('related-video-card');
const autoPreviewA = video(cardA, 420, 236, {paused:false});
const autoPreviewB = video(cardB, 640, 360, {paused:false});
autoPreviewA.__vbUserInitiated = true;
autoPreviewB.__vbUserInitiated = true;
for (const preview of [autoPreviewA, autoPreviewB]) {
  preview.currentTime = 6;
  preview.__vbLastTime = 2;
  preview.__vbLastAt = Date.now() - 1000;
}
const listing = run('https://www.pornhub.com/videos', [autoPreviewA, autoPreviewB]);
if (listing.p) throw new Error('Listing page preview was incorrectly promoted to the primary video');

// On a canonical watch page, the main player wins; a more prominent-looking related preview is ignored.
const player = makeContainer('video-player');
const related = makeContainer('related-video-card');
const main = video(player, 800, 450, {poster:'https://cdn.example/main-poster.jpg'});
const preview = video(related, 980, 550, {poster:'https://cdn.example/preview-poster.jpg'});
const watch = run('https://www.pornhub.com/view_video.php?viewkey=abc123', [main, preview]);
if (!watch.p || watch.p.poster !== 'https://cdn.example/main-poster.jpg') {
  throw new Error('Main player metadata was not isolated from the related preview');
}
console.log('Listing preview gate passed: autoplaying/gesture-marked listing previews ignored; watch-page poster remains tied to the main player');
