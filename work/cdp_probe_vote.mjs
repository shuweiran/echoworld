import { writeFileSync, mkdirSync } from 'node:fs';
const PORT = 9252;
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = 'D:/echoworld/work/repro_script_v2';
mkdirSync(OUT, { recursive: true });

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
  const tab = tabs.find(t => t.type === 'page' && t.url.includes('127.0.0.1:4195'))
    ?? tabs.find(t => t.type === 'page' && !t.url.startsWith('devtools') && !t.url.startsWith('edge://'));
  if (!tab) throw new Error('no tab');
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Runtime.enable');
  const snap = await cdp.eval(`(() => {
    const q = s => [...document.querySelectorAll(s)];
    const chatRect = (() => { const el = document.querySelector('.script-gal-chat'); if (!el) return null; const r = el.getBoundingClientRect(); return { h: Math.round(r.height), y: Math.round(r.y), w: Math.round(r.width) }; })();
    const narr = q('.gal-narrator-text, .gal-log-line, .gal-wait-label').map(e => (e.textContent || '').trim()).filter(Boolean).slice(0, 10);
    const vote = document.querySelector('.proto-vote-title')?.textContent?.trim() || '';
    const invest = document.querySelector('.proto-invest-title')?.textContent?.trim() || '';
    const setup = document.querySelector('.proto-setup-title')?.textContent?.trim() || '';
    const reveal = q('.proto-reveal').map(e => (e.textContent || '').trim()).filter(Boolean).slice(0, 2);
    const inputLock = q('.script-gal-chat .gal-input-row, .script-gal-chat input').length;
    return { href: location.href, vote, invest, setup, reveal, chatRect, narr, inputLock };
  })()`);
  writeFileSync(`${OUT}/vote-snapshot.json`, JSON.stringify(snap, null, 2));
  console.log(JSON.stringify(snap, null, 2));
  process.exit(0);
};
main().catch(e => { console.error('FATAL', e.message); process.exit(1); });
