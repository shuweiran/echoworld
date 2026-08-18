/* cdp_p0816u_probe4.mjs — 修复复核：重载 4399 tab → susSel/locSearched/glow 验证 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';
const CDP_URL = 'http://127.0.0.1:9222';
const OUT = 'D:/roleplay-java/tmp/p0816u';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/probe4.log`;
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
  const meas=(html, sel, phase)=>{ phases.forEach(p=>ws.classList.remove(p)); if(phase) ws.classList.add(phase);
    const host=document.createElement('div');
    host.style.cssText='position:fixed;left:-9999px;top:0;width:360px;'; host.innerHTML=html;
    ws.appendChild(host); const el=host.querySelector(sel);
    const cs=el?getComputedStyle(el):null; const o=cs?{
      backgroundImage:cs.getPropertyValue('background-image'),
      background:cs.getPropertyValue('background'),
      border:cs.getPropertyValue('border'),
      boxShadow:cs.getPropertyValue('box-shadow'),
    }:null; host.remove(); phases.forEach(p=>ws.classList.remove(p)); return o; };
  // glow 变量值（vote 相位已修正为 .55 的 verify 放在 investigation）
  phases.forEach(p=>ws.classList.remove(p)); ws.classList.add('phase-investigation');
  const invGlow=getComputedStyle(ws).getPropertyValue('--proto-glow').trim();
  ws.classList.remove('phase-investigation'); ws.classList.add('phase-discussion');
  const discGlow=getComputedStyle(ws).getPropertyValue('--proto-glow').trim();
  phases.forEach(p=>ws.classList.remove(p));
  return {
    invGlow, discGlow,
    susSel: meas('<div class="proto-sus sel"></div>', '.proto-sus.sel', 'vote'),
    locSearched: meas('<div class="proto-loc searched"></div>', '.proto-loc.searched', 'vote'),
  };
})()`;

async function main() {
  const tabs = await (await fetch(`${CDP_URL}/json`)).json();
  const appTabs = tabs.filter(t => t.type === 'page' && t.url.includes('localhost:4399'));
  let done = false;
  for (const t of appTabs) {
    if (done) break;
    try {
      const cdp = await CDP.connect(t.webSocketDebuggerUrl);
      await cdp.send('Page.enable');
      await cdp.send('Runtime.enable');
      await cdp.send('Page.reload', { ignoreCache: true });
      await sleep(4000);
      for (let i = 0; i < 30; i++) {
        const has = await cdp.eval(`!!document.querySelector('.workspace.proto-v2')`).catch(() => false);
        if (has) break;
        await sleep(1000);
      }
      const r = await cdp.eval(PROBE);
      if (r) {
        log('probe4 on tab', t.id.slice(0, 8));
        log(JSON.stringify(r, null, 1));
        writeFileSync(`${OUT}/probe4.json`, JSON.stringify(r, null, 2), 'utf-8');
        done = true;
      } else {
        log('tab', t.id.slice(0, 8), 'no proto-v2 after reload');
      }
    } catch (e) { log('tab err:', e.message.slice(0, 120)); }
  }
  if (!done) { log('none verified'); process.exit(1); }
}
main();
