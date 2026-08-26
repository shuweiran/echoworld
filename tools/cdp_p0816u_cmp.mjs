/* cdp_p0816u_cmp.mjs — P-0816-U 并排对比图（原型 | 实际，1280x800 同比例）
 * 输出：tmp/p0816u/cmp-investigation.png / cmp-discussion.png / cmp-vote.png
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
const CDP_URL = 'http://127.0.0.1:9222';
const OUT = 'D:/echoworld/tmp/p0816u';
mkdirSync(OUT, { recursive: true });

const b64 = (f) => 'data:image/png;base64,' + readFileSync(`${OUT}/${f}`).toString('base64');

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
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); console.log('saved', f); return true; } } catch (e) { console.error(e.message); } return false; }
}

async function main() {
  const tab = await (await fetch(`${CDP_URL}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })).json();
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  for (const [proto, real, out] of [
    ['proto-investigation.png', 'real2-investigation.png', 'cmp-investigation.png'],
    ['proto-discussion.png', 'real2-discussion.png', 'cmp-discussion.png'],
    ['proto-vote.png', 'real2-vote.png', 'cmp-vote.png'],
  ]) {
    const html = `<!DOCTYPE html><html><head><meta charset="utf-8"><style>
      body{margin:0;background:#111;font-family:sans-serif}
      .wrap{display:flex;flex-direction:column}
      .cap{color:#9fb0c8;font-size:15px;padding:6px 10px;background:#0d1420;border-bottom:1px solid #2a3550}
      .row{display:flex}
      .row img{width:50vw;height:auto;display:block}
      .tag{position:absolute;font-size:12px;color:#7fd4a8;padding:2px 8px;background:rgba(0,0,0,.55);border-radius:4px}
    </style></head><body>
      <div class="wrap">
        <div class="cap">P-0816-U 视觉对齐：左侧=原型（最终蓝本）｜右侧=实际对局（新 bundle + 8000 后端）</div>
        <div class="row" style="position:relative">
          <span class="tag" style="left:10px;top:34px">原型 ${proto}</span>
          <span class="tag" style="left:calc(50% + 10px);top:34px">实际 ${real}</span>
          <img src="${b64(proto)}"><img src="${b64(real)}">
        </div>
      </div>
    </body></html>`;
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 810, deviceScaleFactor: 1, mobile: false });
    await cdp.send('Page.navigate', { url: 'data:text/html;charset=utf-8,' + encodeURIComponent(html) });
    await new Promise(r => setTimeout(r, 2500));
    await cdp.shot(out);
  }
  process.exit(0);
}
main().catch(e => { console.error('FATAL', e.message); process.exit(1); });
