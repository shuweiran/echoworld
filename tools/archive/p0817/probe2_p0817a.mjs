/* probe2_p0817a.mjs — 试听点击链路探针：注入 fetch 拦截 + 点击 + 轮询请求/按钮状态 */
import { appendFileSync, mkdirSync } from 'node:fs';
const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://localhost:4499/';
const OUT = 'D:/roleplay-java/tmp/p0817a';
mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => { const l = a.join(' '); appendFileSync(OUT + '/probe2.log', l + '\n'); console.log(l); };

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
  const wait = async (expr, t = 20000, label) => { const t0 = Date.now(); while (Date.now() - t0 < t) { try { if (await cdp.eval(expr)) return; } catch {} await sleep(500); } throw new Error('wait timeout ' + label); };
  await wait(`!!document.querySelector('.app2-topbar')`, 20000, 'home');
  await cdp.eval(`localStorage.clear(); location.reload(); true`);
  await wait(`!!document.querySelector('.app2-topbar')`, 20000, 'home2');
  // 注入 fetch 拦截
  await cdp.eval(`(() => {
    window.__ttsReq = [];
    const of = window.fetch.bind(window);
    window.fetch = (...args) => {
      const u = String(args[0] || '');
      if (u.includes('/api/tts/mimo')) window.__ttsReq.push({ url: u, t: Date.now() });
      return of(...args);
    };
    return true;
  })()`);
  await cdp.eval(`[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('角色库')).click(); true`);
  await wait(`document.body.textContent.includes('角色卡管理')`, 20000, 'roles');
  await cdp.eval(`[...document.querySelectorAll('.role-chip-add')][0].click(); true`);
  await wait(`!!document.querySelector('.modal-box') && document.body.textContent.includes('MiMo 声线')`, 20000, 'modal');
  await cdp.eval(`[...document.querySelectorAll('.modal-box input[type=checkbox]')].find(i => i.closest('label').textContent.includes('MiMo 声线')).click(); true`);
  await sleep(300);
  await cdp.eval(`(() => {
    const nameInput = [...document.querySelectorAll('.modal-box input')].find(i => i.placeholder && i.placeholder.includes('林晚秋'));
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    setter.call(nameInput, '探针角色');
    nameInput.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`);
  await sleep(300);
  const before = await cdp.eval(`(() => {
    const b = [...document.querySelectorAll('.modal-box button')].find(b => b.textContent.includes('试听'));
    return b ? { disabled: b.disabled, text: b.textContent.trim() } : null;
  })()`);
  log('click-before => ' + JSON.stringify(before));
  await cdp.eval(`[...document.querySelectorAll('.modal-box button')].find(b => b.textContent.includes('试听')).click(); true`);
  for (let i = 0; i < 12; i++) {
    await sleep(300);
    const st = await cdp.eval(`(() => {
      const b = [...document.querySelectorAll('.modal-box button')].find(b => b.textContent.includes('试听') || b.textContent.includes('合成中') || b.textContent.includes('停止') || b.textContent.includes('失败'));
      return { text: b ? b.textContent.trim() : 'none', reqs: window.__ttsReq.map(r => r.url) };
    })()`);
    log('t+' + ((i + 1) * 300) + 'ms => ' + JSON.stringify(st));
  }
  try { await cdp.send('Page.close'); } catch {}
  process.exit(0);
};
main().catch(e => { console.error('FATAL', e); process.exit(2); });
