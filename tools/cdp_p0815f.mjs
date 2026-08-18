/* cdp_p0815f.mjs — P-0815-F 2D 视图渲染性能/回归验证（CDP 端到端，AFTER bundle 实测）
 * 前置：tools/perf_proxy_p0815f.mjs 运行中（4182 = dist 新 bundle；/api → 8000；
 *       POST /api/scenes/map 被代理拦截返回 500 → 前端兜底 park 场景，零 LLM 地图成本）
 * 流程：剧本选择 → 一般模式 → 选剧本 → 角色选择（2D 探索模式 + 点亮 2 角色）→ 进入 2D 探索
 *       → PhaserSimulationView 挂载 → 世界运行：
 *         - 窗口 A（移动活跃期 6s）：Canvas stroke 数/秒 + rAF 帧间隔分位数
 *         - 窗口 B（静止期 6s）：同上（验证节流：位置不变 → 链接层不重绘 → stroke 数应大幅下降）
 *       + console 0 错误 + 截图（渲染回归检查）
 * 产出：tmp/p0815f/metrics_after.json + 截图
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9255;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4182/';
const OUT = 'D:/roleplay-java/tmp/p0815f';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
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

async function waitFor(cdp, expr, t = 40000, label = '') {
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
  window.__perf = { draws: [], frames: [], begin: performance.now() };
  const p0 = performance.now();
  // WebGL 渲染工作量代理：统计 drawElements/drawArrays 调用（Phaser 默认 WebGL 渲染）
  const origGetCtx = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function(type, ...args){
    const ctx = origGetCtx.call(this, type, ...args);
    if (ctx && (type === 'webgl' || type === 'webgl2')) {
      try {
        const de = ctx.drawElements.bind(ctx);
        ctx.drawElements = function(...a){ try{ window.__perf.draws.push(performance.now()-p0); }catch(e){} return de(...a); };
        const da = ctx.drawArrays.bind(ctx);
        ctx.drawArrays = function(...a){ try{ window.__perf.draws.push(performance.now()-p0); }catch(e){} return da(...a); };
        const cl = ctx.clear.bind(ctx);
        ctx.clear = function(...a){ try{ window.__perf.draws.push(performance.now()-p0); }catch(e){} return cl(...a); };
      } catch(e) {}
    }
    return ctx;
  };
  let last = performance.now();
  function loop(t){ window.__perf.frames.push(t-last); last = t; requestAnimationFrame(loop); }
  requestAnimationFrame(loop);
})();`;

function stats(frames, draws, dtSec) {
  const sorted = [...frames].sort((x, y) => x - y);
  const pct = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
  return {
    fps: +(frames.length / dtSec).toFixed(1),
    drawsPerSec: +(draws / dtSec).toFixed(0),
    frameMs: { p50: +pct(0.5).toFixed(1), p90: +pct(0.9).toFixed(1), p99: +pct(0.99).toFixed(1), max: +sorted[sorted.length - 1].toFixed(1) },
    jankGt50: frames.filter(f => f > 50).length,
  };
}

async function measureWindow(cdp, sec, label) {
  const before = await cdp.eval(`JSON.stringify({d:window.__perf.draws.length, f:window.__perf.frames.length})`);
  await sleep(sec * 1000);
  const after = await cdp.eval(`JSON.stringify({d:window.__perf.draws.length, f:window.__perf.frames.length, draws:window.__perf.draws.slice(-40000), frames:window.__perf.frames.slice(-40000)})`);
  const A = JSON.parse(after), B = JSON.parse(before);
  const m = stats(A.frames || [], (A.d - B.d), sec);
  log('[window ' + label + ']', JSON.stringify(m));
  return m;
}

async function main() {
  let child;
  child = spawn(EDGE, ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0815f-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
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
    for(const c of cards){ if(!c.classList.contains('selected')){ c.click(); n++; if(n>=2) break; } }
    return 'lit='+n;
  })()`);
  log('[lit]', lit);
  await sleep(400);
  await clickText(cdp, '.roles-footer button', '进入 2D 探索');
  await waitFor(cdp, `!!document.querySelector('.phaser-sim-view canvas')`, 45000, 'phaser canvas');
  await waitFor(cdp, `document.body.textContent.includes('SSE 已连接') || document.body.textContent.includes('角色已加载')`, 30000, 'sse');
  await sleep(5000); // 等角色加载 + 世界启动
  await cdp.shot('after_loaded.png');
  const winA = await measureWindow(cdp, 6, 'A_active');
  await cdp.shot('after_windowA.png');
  const winB = await measureWindow(cdp, 6, 'B_settled');
  await cdp.shot('after_windowB.png');

  const errs = cdp.consoleLogs.filter(l => l.startsWith('EXCEPTION') || l.startsWith('error'));
  const result = { bundle: 'after', windowA: winA, windowB: winB, consoleErrors: errs.length, consoleLogs: cdp.consoleLogs.slice(-10) };
  log('RESULT ' + JSON.stringify(result, null, 2));
  writeFileSync(`${OUT}/metrics_after.json`, JSON.stringify(result, null, 2));
  child.kill();
  process.exit(0);
}
main().catch(e => { console.log('FATAL', e?.message); try { child?.kill(); } catch { } process.exit(1); });
