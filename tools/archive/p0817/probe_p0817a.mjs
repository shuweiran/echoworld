/* probe_p0817a.mjs — 试听按钮状态序列探测（点击后轮询按钮文案 200ms × 10） */
import { appendFileSync, mkdirSync } from 'node:fs';
const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://localhost:4499/';
const OUT = 'D:/roleplay-java/tmp/p0817a';
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => { const l = a.join(' '); appendFileSync(OUT + '/probe.log', l + '\n'); console.log(l); };

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
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error('eval ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 400));
    return r.result?.result?.value;
  }
}

async function newTab(url) {
  const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' });
  const tab = await r.json();
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  return cdp;
}

const main = async () => {
  const cdp = await newTab(APP);
  // 等首页 → 角色库 → 添加角色弹窗 → 勾选声线
  const wait = async (expr, t = 20000, label) => { const t0 = Date.now(); while (Date.now() - t0 < t) { try { if (await cdp.eval(expr)) return; } catch {} await sleep(500); } throw new Error('wait timeout ' + label); };
  await wait(`!!document.querySelector('.app2-topbar')`, 20000, 'home');
  await cdp.eval(`localStorage.clear(); location.reload(); true`);
  await wait(`!!document.querySelector('.app2-topbar')`, 20000, 'home2');
  await cdp.eval(`[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('角色库')).click(); true`);
  await wait(`document.body.textContent.includes('角色卡管理')`, 20000, 'roles');
  await cdp.eval(`[...document.querySelectorAll('.role-chip-add')][0].click(); true`);
  await wait(`!!document.querySelector('.modal-box') && document.body.textContent.includes('🎙️ MiMo 声线')`, 20000, 'modal');
  await cdp.eval(`[...document.querySelectorAll('.modal-box input[type=checkbox]')].find(i => i.closest('label').textContent.includes('MiMo 声线')).click(); true`);
  await sleep(400);

  // 捕获合成期间 AudioContext/解码错误
  const errs = [];
  cdp.ws.addEventListener('message', ev => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      errs.push((m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 200));
    }
    if (m.method === 'Runtime.exceptionThrown') {
      errs.push((m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 200));
    }
  });

  // 点击试听并轮询按钮状态
  await cdp.eval(`[...document.querySelectorAll('.modal-box button')].find(b => b.textContent.includes('试听')).click(); true`);
  for (let i = 0; i < 14; i++) {
    await sleep(250);
    const st = await cdp.eval(`(() => {
      const b = [...document.querySelectorAll('.modal-box button')].find(b => b.textContent.includes('试听') || b.textContent.includes('合成中') || b.textContent.includes('停止') || b.textContent.includes('失败'));
      const spin = document.querySelector('.tts-spin');
      return { text: b ? b.textContent.trim() : 'none', spin: !!spin, audioState: (window.__audioCtxProbe = window.__audioCtxProbe || (typeof AudioContext !== 'undefined' ? 'available' : 'missing')) };
    })()`);
    log('t+' + ((i + 1) * 250) + 'ms => ' + JSON.stringify(st));
    if (st.text.includes('试听') && i > 2) break;
  }
  log('console errors: ' + JSON.stringify(errs.slice(0, 8)));
  try { await cdp.send('Page.close'); } catch {}
  process.exit(0);
};
main().catch(e => { console.error('FATAL', e); process.exit(2); });
