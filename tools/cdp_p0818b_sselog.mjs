/* cdp_p0818b_sselog.mjs — P-0818-B 诊断：Gal 界面实际收到的 SSE agent_output 内容
 * 前置：static_proxy_p0818b.mjs（4194）+ 8000 后端。
 * 路径：首页 → 剧本选择 → 一般模式 → 街角咖啡馆 → 进入对局；
 * 注入 EventSource 记录器，dump agent_output / agent_token 事件与对话框文本。
 */
import { writeFileSync, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';

const EDGE_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];
const PORT = 9251;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4194/';
const OUT = 'D:/echoworld/tmp/p0818b';
mkdirSync(OUT, { recursive: true });
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(`${OUT}/progress.log`, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); } };
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
}

async function waitFor(cdp, expr, t = 30000, label = '', step = 300) {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(step);
  }
  throw new Error('waitFor timeout: ' + label + ' :: ' + expr);
}
const click = (cdp, expr) => cdp.eval(`(() => { const el = ${expr}; if (!el) return false; el.click(); return true; })()`);

async function launchEdge() {
  const edge = EDGE_CANDIDATES.find(p => { try { return existsSync(p); } catch { return false; } });
  const child = spawn(edge, [
    '--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    '--user-data-dir=C:\\Temp\\edge-p0818b-sse-' + Date.now(), 'about:blank',
  ], { stdio: 'ignore', detached: true });
  child.unref();
  for (let i = 0; i < 40; i++) {
    try { const r = await fetch(`${BASE}/json/version`); if (r.ok) return; } catch { }
    await sleep(500);
  }
  throw new Error('Edge CDP not ready');
}

const main = async () => {
  await launchEdge();
  const tab = await (await fetch(`${BASE}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })).json();
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 30000, '首页');
  // 注入 EventSource 记录器（进入对局前）
  await cdp.eval(`(() => {
    window.__sseLog = [];
    const NativeES = window.EventSource;
    window.EventSource = class extends NativeES {
      constructor(url, cfg) {
        super(url, cfg);
        this.addEventListener('message', (ev) => {
          try { const d = JSON.parse(ev.data); window.__sseLog.push({ evt: ev.type, data: d }); } catch { }
        });
        this.addEventListener('agent_output', (ev) => {
          try { const d = JSON.parse(ev.data); window.__sseLog.push({ evt: 'agent_output', data: d }); } catch { }
        });
      }
    };
    return true;
  })()`);
  await click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 30000, '剧本选择页');
  await click(cdp, `[...document.querySelectorAll('.chip2')].find(b => b.textContent.includes('一般模式'))`);
  await waitFor(cdp, `[...document.querySelectorAll('.script-item')].some(c => c.textContent.includes('街角咖啡馆'))`, 30000, '一般模式列表');
  await click(cdp, `[...document.querySelectorAll('.script-item')].find(c => c.textContent.includes('街角咖啡馆'))`);
  await waitFor(cdp, `document.body.textContent.includes('进入对局')`, 30000, '角色选择页');
  await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入对局'))`);
  await waitFor(cdp, `!!document.querySelector('.galg-page')`, 120000, 'Gal 视图');
  // 等第一条消息出现（无论完成/流式中），并捕获过程中的 SSE
  await sleep(8000);
  const snap = await cdp.eval(`(() => {
    const t = document.querySelector('.gal-dialog-text');
    const btn = document.querySelector('.tts-btn.tts-btn-gal');
    return {
      dialogText: t ? t.textContent : null,
      hasBtn: !!btn,
      sse: window.__sseLog.filter(x => x.evt === 'agent_output' || x.evt === 'agent_token' || (x.evt === 'message' && x.data && x.data.type === 'agent_output')),
    };
  })()`);
  log('dialogText: ' + JSON.stringify(snap.dialogText));
  log('hasBtn: ' + snap.hasBtn);
  const out = [];
  for (const e of snap.sse) {
    if (e.evt === 'agent_output') out.push({ evt: e.evt, agent: e.data.agent_name, len: (e.data.content || '').length, content: (e.data.content || '').slice(0, 120) });
    else if (e.evt === 'agent_token') out.push({ evt: e.evt, agent: e.data.agent_name, delta: (e.data.delta || '').slice(0, 60) });
  }
  log('SSE 事件: ' + JSON.stringify(out.slice(0, 12)));
  writeFileSync(`${OUT}/sse_diag.json`, JSON.stringify({ dialogText: snap.dialogText, hasBtn: snap.hasBtn, sse: out.slice(0, 30) }, null, 2));
  try { await cdp.send('Page.close'); } catch { }
};

main().catch(e => { console.error('FATAL', e); process.exit(2); });
