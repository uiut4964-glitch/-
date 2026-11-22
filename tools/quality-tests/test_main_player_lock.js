const fs = require('fs');
const scan = fs.readFileSync(__dirname + '/SCAN_JS.js', 'utf8');

function makeVideo(id, parentClass, rect, options = {}) {
  const parent = { id: id + '-container', className: parentClass, parentElement: null,
    getAttribute(){ return null; }, querySelectorAll(){ return []; } };
  return {
    tagName:'VIDEO', __vbId:id, parentElement:parent,
    currentSrc:options.src || `https://cdn.example/${id}.mp4`, paused:options.paused ?? true,
    ended:false, readyState:4, currentTime:options.currentTime ?? 0, duration:420,
    videoWidth:1280, videoHeight:720, poster:'', __vbUserInitiated:!!options.userInitiated,
    getAttribute(name){ return name === 'src' ? this.currentSrc : null; },
    getBoundingClientRect(){ return rect; }, querySelectorAll(){ return []; }, addEventListener(){}
  };
}

const main = makeVideo('main-player', 'video-player', {left:120,top:80,right:920,bottom:530,width:800,height:450});
const preview = makeVideo('related-preview', 'video-card related-video', {left:40,top:300,right:440,bottom:525,width:400,height:225});
let videoNodes = [main, preview];
const pageUrl = 'https://www.pornhub.com/view_video.php?viewkey=ph5c62d67b739fc';
const posterUrl = 'https://cdn.example/pornhub-main-poster.jpg';
const document = {
  baseURI:pageUrl,
  querySelector(selector){
    return selector === 'meta[property=\"og:image\"]' ? {getAttribute(name){ return name === 'content' ? posterUrl : null; }} : null;
  },
  querySelectorAll(selector){ return selector === 'video' ? videoNodes : []; },
  addEventListener(){}
};
const window = { innerWidth:1000, innerHeight:700, location:{href:pageUrl}, VBVideoGesture:{onVideoEvent(){},onVideoGesture(){}} };
const perf = { timeOrigin:Date.now()-1000, getEntriesByType(){ return []; } };
const run = () => {
  const code = 'const result=' + scan.trim().replace(/;\\s*$/, '') + '; return JSON.parse(result);';
  return new Function('window','document','location','getComputedStyle','performance',code)(
    window, document, {href:pageUrl}, () => ({display:'block',visibility:'visible',opacity:'1'}), perf
  );
};

const first = run();
if (!first.p || first.p.id !== 'main-player') throw new Error('Initial canonical scan did not lock the main player');
if (first.poster !== posterUrl) throw new Error('Page-level og:image poster fallback was not returned');
const lockedId = window.__vbMainVideoId;
if (lockedId !== 'main-player') throw new Error('Unexpected main video lock value: ' + lockedId);

// Simulate a mobile page that removes/recreates the original player while related cards
// remain in the DOM. The preview is actively autoplaying and marked as user-interacted.
preview.paused = false;
preview.currentTime = 8;
preview.__vbUserInitiated = true;
videoNodes = [preview];
const second = run();
if (window.__vbMainVideoId !== lockedId) throw new Error('Autoplay preview replaced the canonical main-player lock');
if (second.p && second.p.id === 'related-preview') throw new Error('Preview was emitted as the canonical page main player');
if (second.poster !== posterUrl) throw new Error('Page-level poster fallback was lost during preview activity');
console.log('main-player lock and poster fallback test passed: related previews cannot replace the main player or page artwork');
