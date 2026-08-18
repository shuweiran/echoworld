/* cdp_verify_roles.mjs — P-0818-D 第二轮验证：左栏不再出现「多余角色与身份」
 * 进一局剧本杀（默认 5 角色卡），搜证阶段统计左侧 .proto-char 数量与「未分配角色」标签。 */
import { writeFileSync, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';

const EDGE_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];
const PORT = 9255;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:8000/';
const OUT = 'D:/roleplay-java/work/verify_roles';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
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
        if (m.params.type === 'error') c.consoleLogs.push('ERROR: ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 300));
      }
      else if (m.method === 'Runtime.exceptionThrown') c.consoleLogs.push('EXCEPTION: ' + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 300));
    };
    return c;
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 40000);
      this.pending.set(id, (m) => { clearTimeout(t); resolve(m); });
      try { this.ws.send(JSON.stringify({ id, method, params })); } catch (e) { clearTimeout(t); reject(e); }
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error('eval ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 300));
    return r.result?.result?.value;
  }
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); log('[shot]', f); } } catch (e) { } }
}

async function waitFor(cdp, expr, t = 30000, label = '', step = 700) {
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
  if (!edge) throw new Error('Edge not found');
  const child = spawn(edge, [
    '--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    '--user-data-dir=C:\\Temp\\edge-verify-roles-' + Date.now(), 'about:blank',
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
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 30000, '首页顶栏');
  check('T1 首页渲染', true);

  await click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 30000, '剧本选择页');
  await click(cdp, `[...document.querySelectorAll('.chip2')].find(b => b.textContent.includes('剧本杀'))`);
  await waitFor(cdp, `[...document.querySelectorAll('.script-item')].some(c => c.textContent.includes('民国旧宅疑云'))`, 30000, '剧本杀列表');
  await click(cdp, `[...document.querySelectorAll('.script-item')].find(c => c.textContent.includes('民国旧宅疑云'))`);
  await waitFor(cdp, `document.body.textContent.includes('进入对局')`, 30000, '角色选择页');
  await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入对局'))`);
  await waitFor(cdp, `!!document.querySelector('.app-shell')`, 150000, '对局页挂载');
  check('T2 进入对局', true);

  // 等搜证阶段（完整剧本生成后）
  await waitFor(cdp, `!!document.querySelector('.proto-invest-title')`, 240000, '搜证阶段', 1500);
  check('T3 进入搜证阶段', true);
  await sleep(2000);
  await cdp.shot('01-invest.png');

  // 统计左侧栏角色卡：玩家卡 + 未分配角色卡
  const stats = await cdp.eval(`(() => {
    const chars = [...document.querySelectorAll('.proto-char')];
    const extraLabel = [...document.querySelectorAll('.proto-extra-label')].map(e => (e.textContent || '').trim());
    const names = chars.map(c => (c.textContent || '').replace(/\\s+/g, ' ').trim());
    return { total: chars.length, extraLabel, names: names.slice(0, 12) };
  })()`);
  log('LEFT PANEL:', JSON.stringify(stats, null, 2));
  check('T4 左侧角色卡数量 = 5（此前玩家+角色双名单会显示 10）', stats.total === 5, 'total=' + stats.total);
  check('T5 无「未分配角色」标签', stats.extraLabel.length === 0, JSON.stringify(stats.extraLabel));
  await cdp.shot('02-left-panel.png');

  log('CONSOLE ERRORS:', JSON.stringify(cdp.consoleLogs.slice(0, 6)));
  check('T6 console 0 错误', cdp.consoleLogs.length === 0, JSON.stringify(cdp.consoleLogs.slice(0, 3)));
  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, stats, consoleErrors: cdp.consoleLogs.slice(0, 6) }, null, 2));
  log(`RESULT ${pass}/${pass + fail}`);
  process.exit(0);
};

main().catch(e => { log('FATAL', e.message); process.exit(1); });
