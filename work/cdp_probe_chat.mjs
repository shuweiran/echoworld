import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';
const PORT = 9251;
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = 'D:/echoworld/work/repro_script';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
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
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 20000);
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

const main = async () => {
  const tabs = await (await fetch(`${BASE}/json/list`)).json();
  const tab = tabs.find(t => t.type === 'page' && t.url.includes('127.0.0.1:8000'))
    ?? tabs.find(t => t.type === 'page' && !t.url.startsWith('devtools') && !t.url.startsWith('edge://'));
  if (!tab) throw new Error('no tab');
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Runtime.enable');
  const html = await cdp.eval(`(() => {
    const loc = { href: location.href, hash: location.hash, title: document.title };
    const el = document.querySelector('.script-gal-chat');
    const cls = [...document.querySelectorAll('main, .proto-main, .conversation, .proto-invest, .proto-setup, .proto-discuss-main, .proto-vote-main, .proto-reveal')].map(e => e.className).join(' | ');
    const discuss = document.querySelector('.proto-discuss-main');
    const galText = [...document.querySelectorAll('.gal-dialog-text, .gal-narrator-text, .gal-log')].map(e => (e.textContent || '').trim()).filter(Boolean).slice(0, 10);
    const discussTurns = discuss ? [...discuss.querySelectorAll('.proto-msg, .proto-sys-line')].map(e => (e.textContent || '').trim().slice(0, 60)) : [];
    return { loc, cls, html: el ? el.outerHTML.slice(0, 3000) : 'NO .script-gal-chat',
      galText, discussTurns,
      body: document.body.textContent.replace(/\\s+/g, ' ').slice(0, 200) };
  })()`);
  writeFileSync(`${OUT}/script-gal-chat.html`, typeof html === 'string' ? html : JSON.stringify(html, null, 2));
  log('script-gal-chat HTML len', typeof html === 'string' ? html.length : 'obj');
  console.log(JSON.stringify(html, null, 2).slice(0, 5000));
  const rects = await cdp.eval(`(() => {
    const pick = s => { const el = document.querySelector(s); if (!el) return null; const r = el.getBoundingClientRect(); return { s, x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height), display: getComputedStyle(el).display, vis: getComputedStyle(el).visibility }; };
    return ['.script-gal-chat', '.gal-stage', '.gal-stage-chat', '.gal-dialog', '.gal-input-row', '.proto-main', '.conversation', '.gal-dialog-wrap'].map(pick);
  })()`);
  writeFileSync(`${OUT}/rects.json`, JSON.stringify(rects, null, 2));
  console.log(JSON.stringify(rects, null, 2));
  process.exit(0);
};
main().catch(e => { console.error('FATAL', e.message); process.exit(1); });
