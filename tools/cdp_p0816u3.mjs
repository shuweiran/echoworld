/* cdp_p0816u2.mjs — P-0816-U 数值级视觉对齐验证（CDP 计算样式探针，v2）
 * 不用肉眼看图：getComputedStyle 数值对比「原型页 vs 实际对局页」的
 * design tokens 与关键组件样式（背景/面板/圆角/阴影/发光/字体/间距/动效键帧）。
 * 关键点：注入测试元素必须挂在 .workspace.proto-v2 内（--proto-* 变量作用域），
 *         并按 phase 临时切换 class 读取对应阶段色。
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const PROTO = 'http://localhost:8899';
const APP = 'http://localhost:4399/';
const BACKEND = 'http://localhost:8000';
const OUT = 'D:/roleplay-java/tmp/p0816u';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/probe2.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const Q = (s) => JSON.stringify(s);

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); }
    };
    return c;
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 30000);
      this.pending.set(id, (m) => { clearTimeout(t); resolve(m); });
      try { this.ws.send(JSON.stringify({ id, method, params })); } catch (e) { clearTimeout(t); reject(e); }
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error('eval ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 300));
    return r.result?.result?.value;
  }
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); log('[shot]', f); return true; } } catch (e) { } return false; }
}

async function newTab(url) {
  let tab;
  try {
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' });
    tab = await r.json();
  } catch (e) {
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`);
    tab = await r.json();
  }
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false });
  return cdp;
}
async function waitFor(cdp, expr, t = 30000, label = '') {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(500);
  }
  throw new Error('timeout ' + label);
}
async function clickSel(cdp, expr) {
  const r = await cdp.eval(`(()=>{ const el=${expr}; if(!el) return 'MISS'; el.click(); return 'OK'; })()`);
  if (String(r).startsWith('MISS')) throw new Error('click miss ' + expr);
}
async function restPost(path, body) {
  const r = await fetch(BACKEND + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  return await r.json();
}

/* ═══ 原型页探针（:root vars + 代表元素，页面内执行） ═══ */
const PROTO_PROBE = `(()=>{
  const root=getComputedStyle(document.documentElement);
  const vars={}; for(const n of ['--phase','--phase-strong','--phase-soft','--phase-glow','--bg','--panel','--panel-2','--line','--text','--dim','--dim2','--green','--grad']) vars[n]=root.getPropertyValue(n).trim();
  const bodyBg=getComputedStyle(document.body);
  const gs=(sel,props,psel)=>{ const el=document.querySelector(sel); if(!el) return null;
    const cs=getComputedStyle(el, psel||null); const o={}; for(const p of props) o[p]=cs.getPropertyValue(p); return o; };
  return {
    vars,
    bodyBg: { bg: bodyBg.getPropertyValue('background-image') + ' | ' + bodyBg.getPropertyValue('background-color') },
    topbar: gs('.topbar',['height','background-color','backdrop-filter','border-bottom-width','border-bottom-color','padding-left','gap']),
    logo: gs('.logo',['font-size','font-weight','letter-spacing']),
    badgePhase: gs('.badge-phase',['background-image','color','box-shadow','font-size','font-weight','border-radius','padding']),
    badgeRound: gs('.badge-round',['background-color','border','color','font-size']),
    badgeGoal: gs('.badge-goal',['background-color','border','color','font-size']),
    stepActive: gs('.step.active',['background-color','border','box-shadow','border-radius','padding']),
    stepDotActive: gs('.step.active .dot',['background-image','box-shadow','width','height','border-radius','font-size']),
    stepDotDone: gs('.step.done .dot',['background-image','box-shadow','width','height']),
    stepDotWait: gs('.step.wait .dot',['background-color','color','width','height']),
    stepName: gs('.step .s-name',['font-size','font-weight']),
    conn: gs('.conn',['width','height','background-color','margin-left']),
    char: gs('.char',['padding','border-radius','gap']),
    avatar: gs('.avatar',['width','height','border-radius','font-size','font-weight','background-image','box-shadow']),
    statusOn: gs('.status.on',['width','height','background-color','box-shadow','border-radius']),
    choiceBar: gs('.choice-bar',['background-image','border-radius','border','box-shadow','padding']),
    choice: gs('.choice',['border-radius','border','background-color','padding','font-size','color','border-left-width']),
    choiceSmall: gs('.choice small',['font-size','color']),
    locUnsearched: gs('.loc.unsearched',['border','border-radius','background-color','min-height','box-shadow']),
    locSearched: gs('.loc.searched',['border-color','border-radius','background-image']),
    locSearchedBefore: gs('.loc.searched',['content','background','width','height','border-radius','box-shadow','top','left'],'::before'),
    locCount: gs('.clue-count',['background-color','border','color','border-radius','top','right','font-size']),
    locCta: gs('.search-cta',['background-image','color','border-radius','box-shadow','padding','font-size','font-weight']),
    vnDialog: gs('.vn-dialog',['width','border-radius','background-image','border','box-shadow','padding']),
    vnAvatar: gs('.vn-avatar',['width','height','border-radius','font-size','box-shadow','position','left','top']),
    vnNameplate: gs('.vn-nameplate',['position','right','top','padding','border-radius','font-size','font-weight','box-shadow']),
    vnTextbox: gs('.vn-textbox',['margin-top','background-color','border','border-radius','padding','min-height','backdrop-filter']),
    vnLine: gs('.vn-line',['font-size','line-height','color','min-height']),
    sysLine: gs('.sys-line',['font-size','color','background-color','border','border-radius','padding']),
    vnTag: gs('.vn-tag',['padding','border-radius','font-size','font-weight','letter-spacing','color','box-shadow']),
    bubble: gs('.bubble.b-su',['background-color','border-left-width','border-left-color','border-radius','padding','font-size','line-height','color']),
    pressed: gs('.bubble.pressed',['border-color','box-shadow']),
    contra: gs('.contra',['background-image','color','font-size','border-radius','box-shadow']),
    talkBar: gs('.talk-bar',['background-color','border','border-radius','padding']),
    tBtn: gs('.t-btn',['border','background-color','color','padding','border-radius','font-size']),
    ammo: gs('.ammo',['border','background-color','border-radius','padding']),
    ammoCount: gs('.ammo-count',['background','color','box-shadow','border-radius','min-width','height','font-size']),
    hsInput: gs('.hs-input',['background-color','border','border-radius','padding','font-size']),
    chip: gs('.chip',['border','background-color','color','border-radius','padding','font-size']),
    chipOn: gs('.chip.on',['background','color','box-shadow']),
    clue: gs('.clue',['background-image','border','border-radius','box-shadow','padding']),
    clueId: gs('.clue-id',['background-color','border','color','font-size','font-weight','padding','border-radius']),
    locTag: gs('.loc-tag',['background-color','color','border-radius','padding','font-size']),
    matrix: gs('.matrix',['background-image','border','border-radius','box-shadow','padding']),
    mkDirect: gs('.mk.mk-s',['color','text-shadow','font-size','font-weight']),
    roCard: gs('.ro-card',['background-image','border','border-radius','padding','box-shadow']),
    trust: gs('.trust',['background-image','border','border-radius','box-shadow','padding']),
    trustNum: gs('.trust-num',['background-image','-webkit-background-clip','color','font-size','font-weight']),
    tcOn: gs('.tc.on',['background','box-shadow','height','border-radius']),
    sus: gs('.sus',['border-radius','background-image','border','box-shadow','padding']),
    susSel: gs('.sus.sel',['background-image','border','box-shadow']),
    susAvatar: gs('.sus .avatar',['width','height','border-radius','font-size','font-weight']),
    susName: gs('.sus-name',['font-size','font-weight']),
    selMark: gs('.sel-mark',['background','color','border-radius','box-shadow','width','height','top','right','font-size']),
    voteBar: gs('.vote-bar',['background-image','border','border-radius','box-shadow','padding']),
    voteBtn: gs('.vote-btn',['background-image','color','border-radius','box-shadow','padding','font-size','font-weight']),
    abstainBtn: gs('.abstain-btn',['border','background-color','color','border-radius','padding','font-size','font-weight']),
    vpNum: gs('.vp-num',['background-image','-webkit-background-clip','color','font-size','font-weight']),
    vpCellDone: gs('.vp-cell.done',['background','box-shadow','height','border-radius']),
    stat: gs('.stat',['background-image','border','border-radius','box-shadow','padding']),
    barTrack: gs('.bar-track',['height','border-radius','background-color']),
    slapTxt: gs('.slap-txt',['font-size','font-weight','letter-spacing','color','text-shadow']),
    tabs: gs('.tabs',['background-color','border-radius','padding']),
    tabOn: gs('.tab.on',['background-image','color','font-weight','box-shadow','border-radius','font-size']),
    floatBtn: gs('.float-btn',['width','height','border-radius','background-color','box-shadow']),
    toast: gs('.toast',['background-color','border','border-radius','box-shadow','font-size','padding']),
    h1: gs('h1',['font-size','font-weight','letter-spacing']),
    sub: gs('.sub',['font-size','color']),
    secTitle: gs('.sec-title',['font-size','color','letter-spacing']),
  };
})()`;

/* ═══ 实际页探针：vars（按 phase 切类）+ 注入测试元素测计算样式 ═══ */
const REAL_PROBE = `(phase)=>{
  const ws=document.querySelector('.workspace.proto-v2'); if(!ws) return null;
  const phases=['phase-investigation','phase-discussion','phase-vote','phase-default'];
  const had=phases.filter(p=>ws.classList.contains(p));
  phases.forEach(p=>ws.classList.remove(p));
  if(phase) ws.classList.add('phase-'+phase);
  const cs=getComputedStyle(ws);
  const vars={}; for(const n of ['--proto-phase','--proto-phase-strong','--proto-soft','--proto-glow','--proto-grad','--proto-bg','--proto-panel','--proto-panel-2','--proto-line','--proto-text','--proto-dim','--proto-dim2','--proto-green']) vars[n]=cs.getPropertyValue(n).trim();
  const wsBg = { bg: cs.getPropertyValue('background-image') + ' | ' + cs.getPropertyValue('background-color') };
  // 注入测量：host 挂进 ws（继承 --proto-* 变量），测完移除
  const meas=(html, sel, props, psel)=>{ const host=document.createElement('div');
    host.style.cssText='position:fixed;left:-9999px;top:0;width:360px;'; host.innerHTML=html;
    ws.appendChild(host);
    const el=host.querySelector(sel); let o=null;
    if(el){ const c=getComputedStyle(el, psel||null); o={}; for(const p of props) o[p]=c.getPropertyValue(p); }
    host.remove(); return o; };
  const M=(sel, html, props, psel)=>meas(html, sel, props, psel);
  // 顶栏/徽章用真实 DOM（theme-<phase> 类在 header 上，变量作用域独立）
  const live=(sel,props,psel)=>{ const el=document.querySelector(sel); if(!el) return null;
    const c=getComputedStyle(el, psel||null); const o={}; for(const p of props) o[p]=c.getPropertyValue(p); return o; };
  return {
    vars, wsBg,
    topbar: live('.proto-topbar',['height','background-color','backdrop-filter','border-bottom-width','border-bottom-color','padding-left','gap']),
    logo: live('.proto-top-logo',['font-size','font-weight','letter-spacing']),
    badgePhase: live('.proto-badge-phase',['background-image','color','box-shadow','font-size','font-weight','border-radius','padding']),
    badgeRound: live('.proto-badge-round',['background-color','border','color','font-size']),
    badgeGoal: live('.proto-goal-badge',['background-color','border','color','font-size']),
    stepActive: M('.proto-phase-step.current','<div class="proto-phase-step current"><span class="proto-phase-dot">x</span><span>n</span></div>',['background-color','border','box-shadow','border-radius','padding']),
    stepDotActive: M('.proto-phase-step.current .proto-phase-dot','<div class="proto-phase-step current"><span class="proto-phase-dot">x</span></div>',['background-image','box-shadow','width','height','border-radius','font-size']),
    stepDotDone: M('.proto-phase-step.done .proto-phase-dot','<div class="proto-phase-step done"><span class="proto-phase-dot">x</span></div>',['background-image','box-shadow','width','height']),
    stepDotWait: M('.proto-phase-step .proto-phase-dot','<div class="proto-phase-step"><span class="proto-phase-dot">x</span></div>',['background-color','color','width','height']),
    stepName: M('.proto-phase-name','<div class="proto-phase-step"><span class="proto-phase-name">n</span></div>',['font-size','font-weight']),
    char: M('.proto-char','<div class="proto-char"><span class="proto-char-name">n</span></div>',['padding','border-radius','gap']),
    avatar: M('.proto-char-avatar','<div class="proto-char"><span class="proto-char-avatar">a</span></div>',['width','height','border-radius','font-size','font-weight','background-image','box-shadow']),
    statusOn: M('.proto-char .dot.active','<div class="proto-char"><span class="dot active"></span></div>',['width','height','background-color','box-shadow','border-radius']),
    choiceBar: M('.proto-choice-bar','<div class="proto-choice-bar"></div>',['background-image','border-radius','border','box-shadow','padding']),
    choice: M('.proto-choice','<div class="proto-choice"><small>s</small></div>',['border-radius','border','background-color','padding','font-size','color','border-left-width']),
    choiceSmall: M('.proto-choice small','<div class="proto-choice"><small>s</small></div>',['font-size','color']),
    locUnsearched: M('.proto-loc.unsearched','<div class="proto-loc unsearched"></div>',['border','border-radius','background-color','min-height','box-shadow']),
    locSearched: M('.proto-loc.searched','<div class="proto-loc searched"></div>',['border-color','border-radius','background-image']),
    locSearchedBefore: M('.proto-loc.searched','<div class="proto-loc searched"></div>',['content','background','width','height','border-radius','box-shadow','top','left'],'::before'),
    locCount: M('.proto-loc-count','<div class="proto-loc searched"><span class="proto-loc-count">1</span></div>',['background-color','border','color','border-radius','top','right','font-size']),
    locCta: M('.proto-loc-cta','<div class="proto-loc unsearched"><span class="proto-loc-cta">cta</span></div>',['background-image','color','border-radius','box-shadow','padding','font-size','font-weight']),
    vnDialog: M('.proto-vn-dialog','<div class="proto-vn-dialog"></div>',['width','border-radius','background-image','border','box-shadow','padding']),
    vnAvatar: M('.proto-vn-avatar','<div class="proto-vn-avatar">a</div>',['width','height','border-radius','font-size','box-shadow','position','left','top']),
    vnNameplate: M('.proto-vn-nameplate','<div class="proto-vn-nameplate">n</div>',['position','right','top','padding','border-radius','font-size','font-weight','box-shadow']),
    vnTextbox: M('.proto-vn-textbox','<div class="proto-vn-textbox"></div>',['margin-top','background-color','border','border-radius','padding','min-height','backdrop-filter']),
    vnLine: M('.proto-vn-line','<p class="proto-vn-line">t</p>',['font-size','line-height','color','min-height']),
    sysLine: M('.proto-sys-line','<div class="proto-sys-line">t</div>',['font-size','color','background-color','border','border-radius','padding']),
    vnTag: M('.proto-msg-tag','<div class="proto-discuss-stream"><div class="proto-msg-meta"><span class="proto-msg-tag">n</span></div></div>',['padding','border-radius','font-size','font-weight','letter-spacing','color','box-shadow']),
    bubble: M('.proto-msg-text','<div class="proto-discuss-stream"><div class="proto-msg-text" style="--msg-c:#f472b6">t</div></div>',['background-color','border-left-width','border-left-color','border-radius','padding','font-size','line-height','color']),
    contra: M('.proto-msg-contra','<div class="proto-discuss-stream"><span class="proto-msg-contra">矛盾点？</span></div>',['background-image','color','font-size','border-radius','box-shadow']),
    talkBar: M('.proto-talk-bar','<div class="proto-talk-bar"></div>',['background-color','border','border-radius','padding']),
    tBtn: M('.proto-t-btn','<div class="proto-talk-bar"><button class="proto-t-btn">b</button></div>',['border','background-color','color','padding','border-radius','font-size']),
    ammo: M('.proto-ammo','<div class="proto-ammo"></div>',['border','background-color','border-radius','padding']),
    ammoCount: M('.proto-ammo-count','<div class="proto-ammo"><span class="proto-ammo-count">0</span></div>',['background','color','box-shadow','border-radius','min-width','height','font-size']),
    hsInput: M('.proto-hs-input','<input class="proto-hs-input">',['background-color','border','border-radius','padding','font-size']),
    chip: M('.proto-hs-chip','<div class="proto-hs-chips"><button class="proto-hs-chip">c</button></div>',['border','background-color','color','border-radius','padding','font-size']),
    chipOn: M('.proto-hs-chip.on','<div class="proto-hs-chips"><button class="proto-hs-chip on">c</button></div>',['background','color','box-shadow']),
    clue: M('.proto-clue-card','<div class="proto-clue-card"></div>',['background-image','border','border-radius','box-shadow','padding']),
    clueId: M('.proto-clue-id','<div class="proto-clue-card"><span class="proto-clue-id">CL-01</span></div>',['background-color','border','color','font-size','font-weight','padding','border-radius']),
    locTag: M('.proto-clue-loc','<div class="proto-clue-card"><span class="proto-clue-loc">l</span></div>',['background-color','color','border-radius','padding','font-size']),
    matrix: M('.proto-matrix','<div class="proto-matrix"></div>',['background-image','border','border-radius','box-shadow','padding']),
    mkDirect: M('.proto-mk.direct','<div class="proto-matrix"><span class="proto-mk direct">★</span></div>',['color','text-shadow','font-size','font-weight']),
    roleCard: M('.proto-role-card','<div class="proto-role-card"></div>',['background-image','border','border-radius','padding','box-shadow']),
    trust: M('.proto-trust','<div class="proto-trust"></div>',['background-image','border','border-radius','box-shadow','padding']),
    trustNum: M('.proto-trust-num','<div class="proto-trust"><span class="proto-trust-num">4/5</span></div>',['background-image','-webkit-background-clip','color','font-size','font-weight']),
    tcOn: M('.proto-tc.on','<div class="proto-trust"><span class="proto-tc on"></span></div>',['background','box-shadow','height','border-radius']),
    sus: M('.proto-sus','<div class="proto-sus"></div>',['border-radius','background-image','border','box-shadow','padding']),
    susSel: M('.proto-sus.sel','<div class="proto-sus sel"></div>',['background-image','border','box-shadow']),
    susAvatar: M('.proto-sus-avatar','<div class="proto-sus"><span class="proto-sus-avatar">a</span></div>',['width','height','border-radius','font-size','font-weight']),
    susName: M('.proto-sus-name','<div class="proto-sus"><span class="proto-sus-name">n</span></div>',['font-size','font-weight']),
    selMark: M('.proto-sus-mark','<div class="proto-sus sel"><span class="proto-sus-mark">✓</span></div>',['background','color','border-radius','box-shadow','width','height','top','right','font-size']),
    voteBar: M('.proto-vote-bar','<div class="proto-vote-bar"></div>',['background-image','border','border-radius','box-shadow','padding']),
    voteBtn: M('.proto-vote-btn','<button class="proto-vote-btn">b</button>',['background-image','color','border-radius','box-shadow','padding','font-size','font-weight']),
    abstainBtn: M('.proto-abstain-btn','<button class="proto-abstain-btn">b</button>',['border','background-color','color','border-radius','padding','font-size','font-weight']),
    vpNum: M('.proto-vp-num','<div class="proto-vote-progress"><span class="proto-vp-num">2/4</span></div>',['background-image','-webkit-background-clip','color','font-size','font-weight']),
    vpCellDone: M('.proto-vp-cell.done','<div class="proto-vp-cells"><span class="proto-vp-cell done"></span></div>',['background','box-shadow','height','border-radius']),
    stat: M('.proto-vote-stat','<div class="proto-vote-stat"></div>',['background-image','border','border-radius','box-shadow','padding']),
    barTrack: M('.proto-bar-track','<div class="proto-bar-track"></div>',['height','border-radius','background-color']),
    slapTxt: M('.proto-slap-txt','<div class="proto-slap"><span class="proto-slap-txt">拍案！</span></div>',['font-size','font-weight','letter-spacing','color','text-shadow']),
    tabs: M('.proto-tabs','<div class="proto-tabs"></div>',['background-color','border-radius','padding']),
    tabOn: M('.proto-tab.on','<div class="proto-tabs"><button class="proto-tab on">t</button></div>',['background-image','color','font-weight','box-shadow','border-radius','font-size']),
    floatBtn: M('.proto-drawer-fab','<button class="proto-drawer-fab">f</button>',['width','height','border-radius','background-color','box-shadow']),
    toast: M('.proto-toast','<div class="proto-toast">t</div>',['background-color','border','border-radius','box-shadow','font-size','padding']),
    investTitle: M('.proto-invest-title','<h1 class="proto-invest-title">t</h1>',['font-size','font-weight','letter-spacing']),
    discussTitle: M('.proto-discuss-title','<span class="proto-discuss-title">t</span>',['font-size','font-weight','letter-spacing']),
    voteTitle: M('.proto-vote-title','<span class="proto-vote-title">t</span>',['font-size','font-weight','letter-spacing']),
    paneLabel: M('.proto-pane-label','<div class="proto-pane-label">t</div>',['font-size','color','letter-spacing']),
    sectionLabel: M('.proto-section-label','<div class="proto-section-label">t</div>',['font-size','color','letter-spacing']),
    investSub: M('.proto-invest-sub','<p class="proto-invest-sub">t</p>',['font-size','color']),
  };
}`;

function cmp(a, b, tol = 0.8) {
  if (a == null || b == null) return { ok: a == null && b == null, a, b, note: 'missing' };
  const na = parseFloat(a), nb = parseFloat(b);
  if (!isNaN(na) && !isNaN(nb)) return { ok: Math.abs(na - nb) <= tol, a, b };
  const sa = String(a).trim().toLowerCase(), sb = String(b).trim().toLowerCase();
  if (sa === 'none' || sb === 'none') return { ok: sa === sb, a, b };
  return { ok: sa === sb, a, b };
}
function compareObj(proto, real, map) {
  const rows = [];
  for (const [pKey, rKey] of map) {
    const p = proto?.[pKey], r = real?.[rKey];
    if (p == null && r == null) continue;
    if (typeof p === 'object' && p && typeof r === 'object' && r) {
      for (const prop of Object.keys(p)) {
        if (String(prop).startsWith('-webkit-') || String(prop).startsWith('background')) continue; // background 需语义比较
        const res = cmp(p[prop], r?.[prop]);
        rows.push({ key: `${pKey}.${prop}`, ...res });
      }
    } else {
      rows.push({ key: pKey, ...cmp(p, r) });
    }
  }
  return rows;
}
/* background-image 语义归一：linear-gradient 角度/色值可解析为 rgb 后比较（简化：直接文本比较，容差由人工复核） */
function bgSem(a, b) {
  if (!a || !b) return { ok: a === b, a, b };
  return { ok: a.replace(/\s+/g, '') === b.replace(/\s+/g, ''), a, b };
}

async function main() {
  const protoData = {};
  for (const name of ['investigation', 'discussion', 'vote']) {
    const cdp = await newTab(PROTO + '/' + name + '.html');
    await waitFor(cdp, `document.readyState === 'complete'`, 20000, 'proto ' + name);
    await sleep(800);
    protoData[name] = await cdp.eval(PROTO_PROBE);
    await cdp.send('Page.close').catch(() => {});
    log('proto ' + name + ' tokens OK');
  }

  /* 实际对局页：UI 进对局 → 三阶段探针 */
  const cdp = await newTab(APP);
  await waitFor(cdp, `document.readyState === 'complete'`, 30000, 'app load');
  await waitFor(cdp, `!!document.querySelector('.app2-nav-btn')`, 20000, 'app2 nav');
  await sleep(600);
  await clickSel(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(e=>e.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `document.querySelectorAll('.script-item').length > 0`, 15000, 'scripts list');
  await sleep(500);
  await cdp.eval(`(()=>{ const c=[...document.querySelectorAll('.script-item')].find(x=>x.textContent.includes('民国旧宅疑云'))||document.querySelector('.script-item'); if(c) c.click(); })()`);
  await waitFor(cdp, `!!document.querySelector('.role-chip')`, 20000, 'role select');
  await sleep(500);
  await cdp.eval(`(()=>{ const b=[...document.querySelectorAll('button')].find(x=>x.textContent.includes('进入对局')); if(b){ if(b.disabled){ const c=document.querySelector('.role-chip'); if(c) c.click(); } setTimeout(()=>b.click(),50); } })()`);
  await waitFor(cdp, `!!document.querySelector('.workspace.proto-v2') && !!document.querySelector('.proto-topbar')`, 45000, 'proto-v2 page');
  await waitFor(cdp, `(()=>{ const b=document.querySelector('.proto-badge-phase'); return b && b.textContent.includes('搜证'); })()`, 150000, 'investigation').catch(()=>log('warn: 未等到搜证徽章'));
  await sleep(3000);

  const real = {};
  const fastPoll = async (expr, t = 20000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < t) {
      try { const v = await cdp.eval(expr); if (v) return true; } catch { }
      await sleep(100);
    }
    return false;
  };
  for (const ph of ['investigation', 'discussion', 'vote']) {
    const emoji = ph === 'investigation' ? '搜证' : ph === 'discussion' ? '讨论' : '投票';
    if (ph === 'discussion') {
      // 讨论窗口极短（LLM 快，~3.5s 后引擎自动进投票）：徽章一变立即截图，不做睡眠
      const ok = await fastPoll(`(()=>{ const b=document.querySelector('.proto-badge-phase'); return b && b.textContent.includes(${Q(emoji)}); })()`);
      log('discussion badge fast-poll:', ok);
      await sleep(300);
    } else {
      await waitFor(cdp, `(()=>{ const b=document.querySelector('.proto-badge-phase'); return b && b.textContent.includes(${Q(emoji)}); })()`, 45000, 'phase ' + ph).catch(()=>log('warn: badge ' + emoji + ' 超时'));
      await sleep(ph === 'investigation' ? 6000 : 3500);
    }
    real[ph] = await cdp.eval(`(${REAL_PROBE})(${Q(ph)})`);
    real[ph].dom = await cdp.eval(`(()=>{
      const q=s=>document.querySelectorAll(s).length;
      return {
        locCards:q('.proto-loc'), choices:q('.proto-choice'), clueCards:q('.proto-clue-card'),
        phaseSteps:q('.proto-phase-step'), chars:q('.proto-char'), tabs:q('.proto-tab'),
        msgs:q('.proto-discuss-stream .proto-msg'), sysLines:q('.proto-sys-line'), talkBtns:q('.proto-t-btn'), ammo:q('.proto-ammo'),
        susCards:q('.proto-sus'), trust:q('.proto-trust')?1:0, voteBar:q('.proto-vote-bar')?1:0, stat:q('.proto-vote-stat')?1:0,
        investTitle:q('.proto-invest-title'), discussTitle:q('.proto-discuss-title'), voteTitle:q('.proto-vote-title'),
      };
    })()`);
    await cdp.shot('real2-' + ph + '.png');
    log('probe ' + ph + ' dom:', JSON.stringify(real[ph].dom));
    if (ph !== 'vote') {
      const adv = await restPost('/api/script/advance', {});
      log('advance ->', JSON.stringify(adv).slice(0, 160));
    }
  }

  /* 对比 → alignment.md */
  const lines = [];
  lines.push('# P-0816-U 视觉对齐数值对比（CDP 计算样式，无目测偏差）\n');
  lines.push('> 蓝本 = docs/ui-prototype 三张原型页；实际 = 8000 后端 + 4399 新 bundle 对局页（真实对局）。\n');
  const varPairs = [
    ['--bg', '--proto-bg'], ['--panel', '--proto-panel'], ['--panel-2', '--proto-panel-2'],
    ['--line', '--proto-line'], ['--text', '--proto-text'], ['--dim', '--proto-dim'],
    ['--dim2', '--proto-dim2'], ['--green', '--proto-green'],
    ['--phase', '--proto-phase'], ['--phase-strong', '--proto-phase-strong'],
    ['--phase-soft', '--proto-soft'], ['--phase-glow', '--proto-glow'],
  ];
  const elemMap = {
    topbar: 'topbar', logo: 'logo', badgePhase: 'badgePhase', badgeRound: 'badgeRound', badgeGoal: 'badgeGoal',
    stepActive: 'stepActive', stepDotActive: 'stepDotActive', stepDotDone: 'stepDotDone', stepDotWait: 'stepDotWait',
    stepName: 'stepName', char: 'char', avatar: 'avatar', statusOn: 'statusOn',
    choiceBar: 'choiceBar', choice: 'choice', choiceSmall: 'choiceSmall',
    locUnsearched: 'locUnsearched', locSearched: 'locSearched', locSearchedBefore: 'locSearchedBefore',
    locCount: 'locCount', locCta: 'locCta',
    vnDialog: 'vnDialog', vnAvatar: 'vnAvatar', vnNameplate: 'vnNameplate', vnTextbox: 'vnTextbox', vnLine: 'vnLine',
    sysLine: 'sysLine', vnTag: 'vnTag', bubble: 'bubble', contra: 'contra',
    talkBar: 'talkBar', tBtn: 'tBtn', ammo: 'ammo', ammoCount: 'ammoCount',
    hsInput: 'hsInput', chip: 'chip', chipOn: 'chipOn',
    clue: 'clue', clueId: 'clueId', locTag: 'locTag', matrix: 'matrix', mkDirect: 'mkDirect', roCard: 'roleCard',
    trust: 'trust', trustNum: 'trustNum', tcOn: 'tcOn',
    sus: 'sus', susSel: 'susSel', susAvatar: 'susAvatar', susName: 'susName', selMark: 'selMark',
    voteBar: 'voteBar', voteBtn: 'voteBtn', abstainBtn: 'abstainBtn', vpNum: 'vpNum', vpCellDone: 'vpCellDone',
    stat: 'stat', barTrack: 'barTrack', slapTxt: 'slapTxt',
    tabs: 'tabs', tabOn: 'tabOn', floatBtn: 'floatBtn', toast: 'toast',
    h1: 'investTitle', sub: 'investSub', secTitle: 'sectionLabel',
  };
  let total = 0, aligned = 0;
  const fails = [];
  const phases = ['investigation', 'discussion', 'vote'];
  for (const ph of phases) {
    const p = protoData[ph], r = real[ph];
    lines.push(`\n## ${ph}（${ph}.html ↔ 实际对局）\n`);
    lines.push('| 项 | 原型 | 实际 | 判定 |');
    lines.push('|---|---|---|---|');
    for (const [pv, rv] of varPairs) {
      const res = cmp(p.vars[pv], r.vars[rv]);
      total++; if (res.ok) aligned++; else fails.push(`${ph} var ${pv}↔${rv}`);
      lines.push(`| var ${pv.replace('--', '')} | ${p.vars[pv] || '-'} | ${r.vars[rv] || '-'} | ${res.ok ? '✅' : '❌'} |`);
    }
    // 背景（文本语义比较）
    for (const [pk, rk] of [['bodyBg', 'wsBg']]) {
      const res = bgSem(p[pk]?.bg, r[rk]?.bg);
      total++; if (res.ok) aligned++; else fails.push(`${ph} ${pk}`);
      lines.push(`| ${pk} | ${String(p[pk]?.bg ?? '-').slice(0, 70)} | ${String(r[rk]?.bg ?? '-').slice(0, 70)} | ${res.ok ? '✅' : '❌（结构近似，色值见下）'} |`);
    }
    for (const [pk, rk] of Object.entries(elemMap)) {
      const rows = compareObj(p[pk], r[rk], [[pk, rk]]);
      for (const row of rows) {
        if (row.a == null && row.b == null) continue;
        total++; if (row.ok) aligned++; else fails.push(`${ph} ${row.key}`);
        lines.push(`| ${row.key.replace('.', ' ') } | ${String(row.a ?? '-').slice(0, 80)} | ${String(row.b ?? '-').slice(0, 80)} | ${row.ok ? '✅' : '❌'} |`);
      }
      // 背景专项（bg / background-image）
      if (p[pk]?.['background-image'] || r[rk]?.['background-image'] || p[pk]?.background || r[rk]?.background) {
        const pa = p[pk]?.background ? p[pk].background : p[pk]?.['background-image'];
        const rb = r[rk]?.background ? r[rk].background : r[rk]?.['background-image'];
        if (pa && rb) {
          const res = bgSem(pa, rb);
          total++; if (res.ok) aligned++; else fails.push(`${ph} ${pk} bg`);
          lines.push(`| ${pk} bg | ${String(pa).slice(0, 80)} | ${String(rb).slice(0, 80)} | ${res.ok ? '✅' : '❌（渐变结构，人工复核截图）'} |`);
        }
      }
    }
    lines.push(`\nDOM 探针：\`${JSON.stringify(r.dom)}\``);
  }
  lines.push(`\n## 汇总`);
  lines.push(`- 对比项：${total}，完全对齐：${aligned}（${(aligned / Math.max(1, total) * 100).toFixed(1)}%）`);
  lines.push(`- ❌ 清单（${fails.length}）：${fails.slice(0, 40).join('、') || '无'}`);
  lines.push(`- 说明：渐变类 ❌ 多为「同结构不同数值表示」（如 rgba 书写差异/相位色差异），截图留证 tmp/p0816u/real2-*.png ↔ proto-*.png 供人工复核。`);
  writeFileSync(`${OUT}/alignment.md`, lines.join('\n'), 'utf-8');
  log(`ALIGNMENT total=${total} aligned=${aligned} fails=${fails.length}`);
  writeFileSync(`${OUT}/probe_real.json`, JSON.stringify(real, null, 2), 'utf-8');
  writeFileSync(`${OUT}/probe_proto.json`, JSON.stringify(protoData, null, 2), 'utf-8');
}

main().catch(e => { log('FATAL', e.message); process.exit(1); });
