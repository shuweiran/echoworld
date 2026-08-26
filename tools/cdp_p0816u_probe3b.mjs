/* cdp_p0816u_probe3b.mjs — 定点复核（复用已开 4399 tab：susSel/locSearched/avatar/clueNew 计算样式） */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';
const CDP_URL = 'http://127.0.0.1:9222';
const OUT = 'D:/echoworld/tmp/p0816u';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/probe3b.log`;
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

const PROBE = `(()=>{
  const ws=document.querySelector('.workspace.proto-v2'); if(!ws) return null;
  const phases=['phase-investigation','phase-discussion','phase-vote','phase-default'];
  phases.forEach(p=>ws.classList.remove(p)); ws.classList.add('phase-vote');
  const meas=(html, sel)=>{ const host=document.createElement('div');
    host.style.cssText='position:fixed;left:-9999px;top:0;width:360px;'; host.innerHTML=html;
    ws.appendChild(host); const el=host.querySelector(sel);
    const cs=el?getComputedStyle(el):null; const o=cs?{
      background:cs.getPropertyValue('background'),
      backgroundImage:cs.getPropertyValue('background-image'),
      border:cs.getPropertyValue('border'),
    }:null; host.remove(); return o; };
  phases.forEach(p=>ws.classList.remove(p));
  return {
    susSel: meas('<div class="proto-sus sel"></div>', '.proto-sus.sel'),
    susBase: meas('<div class="proto-sus"></div>', '.proto-sus'),
    locSearched: meas('<div class="proto-loc searched"></div>', '.proto-loc.searched'),
    locBase: meas('<div class="proto-loc"></div>', '.proto-loc'),
    clueCard: meas('<div class="proto-clue-card"></div>', '.proto-clue-card'),
    avatar: meas('<div class="proto-char"><span class="proto-char-avatar">a</span></div>', '.proto-char-avatar'),
  };
})()`;

async function main() {
  const tabs = await (await fetch(`${CDP_URL}/json`)).json();
  const appTabs = tabs.filter(t => t.type === 'page' && t.url.includes('localhost:4399'));
  log('4399 tabs:', appTabs.length);
  for (const t of appTabs) {
    try {
      const cdp = await CDP.connect(t.webSocketDebuggerUrl);
      await cdp.send('Runtime.enable');
      const r = await cdp.eval(PROBE);
      if (r) {
        log('probe3b OK on tab', t.id.slice(0, 8));
        writeFileSync(`${OUT}/probe3b.json`, JSON.stringify(r, null, 2), 'utf-8');
        log(JSON.stringify(r));
        process.exit(0);
      } else {
        log('tab', t.id.slice(0, 8), 'no proto-v2 workspace');
      }
    } catch (e) { log('tab err:', e.message.slice(0, 120)); }
  }
  log('none matched');
  process.exit(1);
}
main();
