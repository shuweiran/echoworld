/* cdp_perf_deep.mjs — 2D 视图深度实测（固定后端 8001 + 插桩 bundle dist-instr，代理 4183）
 * 采集：帧间隔分位数 / 长任务(>50ms) / JS 主线程 busy% / WebGL draw 数 / __perf2 计数（
 *       redrawLinks·applySnapshot·renderAgent·setConversations gate）/ 页面侧 SSE 到达率
 * 流程同 cdp_p0815f：剧本选择→一般模式→选剧本→角色选择（2D 探索 + 全亮角色）→进入 2D 探索
 * 产出：tmp/perf_deep/metrics.json + 截图
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9256;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4183/';
const OUT = 'D:/roleplay-java/tmp/perf_deep';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/cdp_progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); this.consoleLogs = []; }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); }
      else if (m.method === 'Runtime.consoleAPICalled') c.consoleLogs.push(m.params.type + ': ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 200));
      else if (m.method === 'Runtime.exceptionThrown') c.consoleLogs.push('EXCEPTION: ' + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 200));
    };
    return c;
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 60000);
      this.pending.set(id, (m) => { clearTimeout(t); resolve(m); });
      try { this.ws.send(JSON.stringify({ id, method, params })); } catch (e) { clearTimeout(t); reject(e); }
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error('eval ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 200));
    return r.result?.result?.value;
  }
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); log('[shot]', f); } } catch (e) { } }
}

async function waitFor(cdp, expr, t = 45000, label = '') {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(700);
  }
  throw new Error('timeout ' + label);
}
async function clickText(cdp, sel, text) {
  const r = await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`);
  if (String(r).startsWith('MISS')) throw new Error('click miss ' + sel + ' ' + text);
}

const INSTR = `(()=>{
  window.__meas = { frames: [], long: [], draws: 0, sseSnaps: 0, sseGap: [], lastSseT: null, jsWork: [] };
  const p0 = performance.now();
  // WebGL draw 计数
  const origGetCtx = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function(type, ...args){
    const ctx = origGetCtx.call(this, type, ...args);
    if (ctx && (type === 'webgl' || type === 'webgl2')) {
      try {
        const de = ctx.drawElements.bind(ctx); ctx.drawElements = function(...a){ window.__meas.draws++; return de(...a); };
        const da = ctx.drawArrays.bind(ctx); ctx.drawArrays = function(...a){ window.__meas.draws++; return da(...a); };
      } catch(e) {}
    }
    return ctx;
  };
  // 长任务
  try { new PerformanceObserver((l) => { for (const e of l.getEntries()) window.__meas.long.push({ d: e.duration, s: e.startTime - p0 }); }).observe({ entryTypes: ['longtask'] }); } catch(e) {}
  // rAF 帧间隔 + JS 每帧耗时（主线程 busy 代理）
  let last = performance.now();
  function loop(t){
    const now = performance.now();
    window.__meas.frames.push(t - last);
    window.__meas.jsWork.push(now - t);
    last = t;
    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);
  // SSE 到达率（页面侧第二连接）
  try {
    const es = new EventSource('/api/simulation/events');
    es.addEventListener('world_snapshot', () => {
      const now = performance.now();
      if (window.__meas.lastSseT != null) window.__meas.sseGap.push(now - window.__meas.lastSseT);
      window.__meas.lastSseT = now;
      window.__meas.sseSnaps++;
    });
  } catch(e) {}
})();`;

function stats(frames, dtSec) {
  const sorted = [...frames].sort((x, y) => x - y);
  const pct = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
  return {
    fps: +(frames.length / dtSec).toFixed(1),
    frameMs: { p50: +pct(0.5).toFixed(1), p90: +pct(0.9).toFixed(1), p99: +pct(0.99).toFixed(1), max: +sorted[sorted.length - 1].toFixed(1) },
    jankGt33: frames.filter(f => f > 33).length,
    jankGt50: frames.filter(f => f > 50).length,
  };
}

async function measureWindow(cdp, sec, label) {
  await cdp.eval(`window.__winStart = { f: window.__meas.frames.length, d: window.__meas.draws, l: window.__meas.long.length, j: window.__meas.jsWork.length, s: window.__meas.sseSnaps };`);
  await sleep(sec * 1000);
  const A = await cdp.eval(`(()=>{ const s = window.__winStart; const M = window.__meas;
    const fl = M.frames.slice(s.f); const jw = M.jsWork.slice(s.j); const lo = M.long.slice(s.l);
    const seg = (a) => { if (!a || a.length === 0) return { n: 0, sum: 0, p50: -1, p90: -1, p99: -1, max: -1 }; const srt = [...a].sort((x,y)=>x-y); const p=(p)=>srt[Math.min(srt.length-1, Math.floor(srt.length*p))]; return { n: a.length, sum: +a.reduce((x,y)=>x+y,0).toFixed(1), p50: +p(0.5).toFixed(1), p90: +p(0.9).toFixed(1), p99: +p(0.99).toFixed(1), max: +srt[srt.length-1].toFixed(1) }; };
    return JSON.stringify({
      frames: seg(fl), jsWork: seg(jw), longtasks: seg(lo.map(x=>x.d)),
      draws: M.draws - s.d, sseSnaps: M.sseSnaps - s.s,
      perf2: window.__perf2 || null,
    }); })()`);
  const m = JSON.parse(A);
  const busyPct = m.jsWork.n ? +(100 * m.jsWork.sum / (sec * 1000)).toFixed(1) : null;
  log('[window ' + label + ']', JSON.stringify({ fps: +((m.frames.n) / sec).toFixed(1), frameMs: m.frames, jankGt50: m.frames.n ? 0 : 0, longtasks: m.longtasks, drawsPerSec: +(m.draws / sec).toFixed(0), ssePerSec: +(m.sseSnaps / sec).toFixed(2), busyPct, perf2: m.perf2 }));
  return { ...m, busyPct, drawsPerSec: +(m.draws / sec).toFixed(0), ssePerSec: +(m.sseSnaps / sec).toFixed(2), fps: +((m.frames.n) / sec).toFixed(1) };
}

async function main() {
  let child;
  child = spawn(EDGE, ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-perfdeep-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 30; i++) { try { const r = await fetch(`${BASE}/json/version`); if (r.ok) break; } catch { } await sleep(500); }
  const list = await (await fetch(`${BASE}/json/list`)).json();
  const cdp = await CDP.connect(list.find(t => t.type === 'page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: INSTR });
  if (await cdp.eval('1+1') !== 2) throw new Error('cdp sanity');

  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `document.querySelectorAll('.app2-nav-btn').length>=5`, 25000, 'nav');
  await clickText(cdp, '.app2-nav-btn', '剧本选择');
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 15000, 'mode chips');
  await clickText(cdp, '.chip2', '一般模式');
  await sleep(600);
  await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp, `document.body.textContent.includes('角色选择')`, 20000, 'roles page');
  await sleep(500);
  await clickText(cdp, '.chip2', '2D 探索');
  await sleep(300);
  const lit = await cdp.eval(`(()=>{
    const cards = Array.from(document.querySelectorAll('.role-chip')).filter(c=>c.classList && !c.classList.contains('role-chip-add'));
    let n=0;
    for(const c of cards){ if(!c.classList.contains('selected')){ c.click(); n++; if(n>=8) break; } }
    return 'lit='+n+' total='+cards.length;
  })()`);
  log('[lit]', lit);
  await sleep(400);
  await clickText(cdp, '.roles-footer button', '进入 2D 探索');
  await waitFor(cdp, `!!document.querySelector('.phaser-sim-view canvas')`, 60000, 'phaser canvas');
  await waitFor(cdp, `document.body.textContent.includes('SSE 已连接') || document.body.textContent.includes('角色已加载')`, 30000, 'sse');
  await sleep(6000); // 等角色加载 + 世界启动 + 首轮对话
  await cdp.shot('after_loaded.png');
  const winA = await measureWindow(cdp, 8, 'A_active');
  await cdp.shot('after_windowA.png');
  const winB = await measureWindow(cdp, 8, 'B_settled');
  await cdp.shot('after_windowB.png');

  const errs = cdp.consoleLogs.filter(l => l.startsWith('EXCEPTION') || l.startsWith('error'));
  const result = { bundle: 'dist-instr', backend: '8001(fixed)', windowA: winA, windowB: winB, consoleErrors: errs.length, consoleLogs: cdp.consoleLogs.slice(-10) };
  log('RESULT ' + JSON.stringify(result, null, 2));
  writeFileSync(`${OUT}/metrics_fixed.json`, JSON.stringify(result, null, 2));
  child.kill();
  process.exit(0);
}
main().catch(e => { console.log('FATAL', e?.message); try { child?.kill(); } catch { } process.exit(1); });
