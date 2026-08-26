/* cdp_p0816k_deg.mjs — P-0816-K 降级验证：阻断 GET /api/scenes 后剧本选择页仍正常显示预设/本地剧本
 * 前置：static_proxy_p0816k.mjs 运行中（4196）；Edge CDP 9222 已启动
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';
const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://127.0.0.1:4196/';
const OUT = 'D:/echoworld/tmp/p0816k';
mkdirSync(OUT, { recursive: true });
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(`${OUT}/progress.log`, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); this.consoleLogs = []; }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); }
      else if (m.method === 'Runtime.consoleAPICalled') {
        c.consoleLogs.push((m.params.type || '') + ': ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 200));
      }
      else if (m.method === 'Runtime.exceptionThrown') c.consoleLogs.push('EXCEPTION: ' + (m.params.exceptionDetails?.exception?.description || '').slice(0, 200));
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
async function waitFor(cdp, expr, t = 20000, label = '') {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(500);
  }
  throw new Error('timeout ' + label);
}
async function clickSel(cdp, expr) {
  const r = await cdp.eval(`(()=>{ const el=${expr}; if(!el) return 'MISS'; el.click(); return 'OK'; })()`);
  if (String(r).startsWith('MISS')) throw new Error('click miss ' + expr);
}
const Q = (s) => JSON.stringify(s);

async function main() {
  try {
    const tabs = await (await fetch(`${CDP_URL}/json`)).json();
    for (const t of tabs) { if (t.url && t.url.includes('127.0.0.1:4196')) { try { await fetch(`${CDP_URL}/json/close/${t.id}`); } catch { } } }
  } catch { }
  let tab;
  try { tab = await (await fetch(`${CDP_URL}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })).json(); }
  catch { tab = await (await fetch(`${CDP_URL}/json/new?${encodeURIComponent('about:blank')}`)).json(); }
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');

  // 阻断 /api/scenes（GET/POST/DELETE 全部 502 级别失败：直接 block 该 URL 模式）
  await cdp.send('Network.enable');
  await cdp.send('Network.setBlockedURLs', { urls: ['*api/scenes*'] });
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `document.readyState === 'complete'`, 30000, 'load');
  await waitFor(cdp, `!!document.querySelector('.app2-nav-btn')`, 20000, 'nav');

  await clickSel(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(e=>e.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `document.querySelectorAll('.script-item').length > 0`, 15000, 'list');
  await sleep(800);

  const cnt = await cdp.eval(`document.querySelectorAll('.script-item').length`);
  const hasPreset = await cdp.eval(`[...document.querySelectorAll('.script-item')].some(e=>e.textContent.includes('民国旧宅疑云'))`);
  const backendCards = await cdp.eval(`[...document.querySelectorAll('.script-item')].filter(e=>e.textContent.includes('后端场景')).length`);
  check('DEG 阻断 /api/scenes 后预设剧本仍显示', hasPreset, 'preset found');
  check('DEG 阻断后无后端场景卡（加载失败降级）', backendCards === 0, 'backendCards=' + backendCards);
  check('DEG 阻断后列表 = 3 预设（本地列表不受影响）', cnt === 3, 'cnt=' + cnt);
  const warn = cdp.consoleLogs.filter(l => l.includes('后端场景加载失败'));
  check('DEG console 出现降级 warn（轻提示）', warn.length > 0, warn[0] || 'no warn');
  check('DEG console 无未捕获异常', !cdp.consoleLogs.some(l => l.startsWith('EXCEPTION') || l.startsWith('error')), cdp.consoleLogs.slice(0, 3).join('|'));

  try { await fetch(`${CDP_URL}/json/close/${tab.id}`); } catch { }
  const result = { pass, fail, at: new Date().toISOString() };
  writeFileSync(`${OUT}/result-degrade.json`, JSON.stringify(result, null, 2));
  log('==== DEGRADE RESULT pass=' + pass + ' fail=' + fail + ' ====');
  process.exit(fail > 0 ? 1 : 0);
}
main().catch((e) => { log('FATAL', e.message); process.exit(2); });
