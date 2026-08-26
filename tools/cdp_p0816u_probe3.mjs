/* cdp_p0816u_probe3.mjs — 定点复核 susSel / locSearched / avatar 计算样式（P-0816-U） */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';
const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://localhost:4399/';
const OUT = 'D:/echoworld/tmp/p0816u';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/probe3.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

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

async function main() {
  const cdp = await newTab(APP);
  await waitFor(cdp, `document.readyState === 'complete'`, 30000, 'load');
  await waitFor(cdp, `!!document.querySelector('.workspace.proto-v2')`, 20000, 'proto-v2');
  const r = await cdp.eval(`(()=>{
    const ws=document.querySelector('.workspace.proto-v2');
    // 切到 vote 相位（当前对局可能在任意阶段；探测不受影响）
    const phases=['phase-investigation','phase-discussion','phase-vote','phase-default'];
    phases.forEach(p=>ws.classList.remove(p)); ws.classList.add('phase-vote');
    const meas=(html, sel)=>{ const host=document.createElement('div');
      host.style.cssText='position:fixed;left:-9999px;top:0;width:360px;'; host.innerHTML=html;
      ws.appendChild(host); const el=host.querySelector(sel);
      const cs=el?getComputedStyle(el):null; const o=cs?{
        background:cs.getPropertyValue('background'),
        backgroundImage:cs.getPropertyValue('background-image'),
        border:cs.getPropertyValue('border'),
        borderTopColor:cs.getPropertyValue('border-top-color'),
      }:null; host.remove(); return o; };
    phases.forEach(p=>ws.classList.remove(p));
    return {
      susSel: meas('<div class="proto-sus sel"></div>', '.proto-sus.sel'),
      susBase: meas('<div class="proto-sus"></div>', '.proto-sus'),
      locSearched: meas('<div class="proto-loc searched"></div>', '.proto-loc.searched'),
      locBase: meas('<div class="proto-loc"></div>', '.proto-loc'),
      clueNew: meas('<div class="proto-clue-card"></div>', '.proto-clue-card'),
      avatar: meas('<div class="proto-char"><span class="proto-char-avatar">a</span></div>', '.proto-char-avatar'),
    };
  })()`);
  log('probe3:', JSON.stringify(r, null, 1));
  writeFileSync(`${OUT}/probe3.json`, JSON.stringify(r, null, 2), 'utf-8');
}
main().catch(e => { log('FATAL', e.message); process.exit(1); });
