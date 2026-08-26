/* cdp_p0816k.mjs — P-0816-K 后端场景接入剧本选择页真机验证 CDP 脚本
 * 前置：tools/static_proxy_p0816k.mjs 运行中（4196 = P-0816-K dist bundle）；Edge CDP 9222 已启动；
 *       后端 8000 已有测试场景（curl 预置）：
 *         general: ca685c4b 'P-0816-K删除测试场景'；murder: script_删除测试剧本 '删除测试·后端剧本'
 * 目标：
 *   M1 剧本杀模式列表含后端场景卡（script_ 前缀）+ 预设卡 + ☁️ 后端场景 chip
 *   M2 预设删除被拦截（alert）
 *   M3 后端场景卡 ✕ → confirm → 接受 → 卡片消失 + 后端 GET /api/scenes 已删
 *   M4 后端 murder 卡点击 → 角色选择页正确解析（标题 + 后端角色名 chips）
 *   G1 一般模式列表含后端场景卡（非 script_ 前缀）
 *   G2 一般模式后端场景删除走通 + 后端已删
 *   G3 一般模式预设删除被拦截
 *   E  console 无新增错误
 * 产出：tmp/p0816k/（截图 + result.json + progress.log）
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://127.0.0.1:4196/';
const BACKEND = 'http://localhost:8000';
const OUT = 'D:/echoworld/tmp/p0816k';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };
const Q = (s) => JSON.stringify(s);

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); this.consoleLogs = []; this.dialogs = []; }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); }
      else if (m.method === 'Page.javascriptDialogOpening') c.dialogs.push({ type: m.params.type, message: m.params.message });
      else if (m.method === 'Runtime.consoleAPICalled') {
        if (m.params.type === 'error' || m.params.type === 'warning') c.consoleLogs.push(m.params.type + ': ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 200));
      }
      else if (m.method === 'Runtime.exceptionThrown') c.consoleLogs.push('EXCEPTION: ' + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 200));
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
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); log('[shot]', f); return true; } } catch (e) { } return false; }
  async acceptDialog(accept = true) {
    const r = await this.send('Page.handleJavaScriptDialog', { accept });
    return !r.error;
  }
}

async function waitFor(cdp, expr, t = 30000, label = '') {
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
// 点击会触发 JS 对话框（confirm/alert）阻塞主线程：用 setTimeout 派发，eval 不等待
async function clickDelOf(cdp, title) {
  const r = await cdp.eval(`(()=>{ const c=[...document.querySelectorAll('.script-item')].find(e=>e.textContent.includes(${Q(title)})); if(!c) return 'MISS'; const b=c.querySelector('.si-del'); if(!b) return 'MISS-BTN'; setTimeout(()=>b.click(), 10); return 'OK'; })()`);
  if (String(r).startsWith('MISS')) throw new Error('clickDel miss ' + title);
}
async function clickDelFirstPreset(cdp) {
  const r = await cdp.eval(`(()=>{ const c=[...document.querySelectorAll('.script-item')].find(e=>e.querySelector('.si-del-disabled')); if(!c) return 'MISS'; setTimeout(()=>c.querySelector('.si-del').click(), 10); return 'OK'; })()`);
  if (String(r).startsWith('MISS')) throw new Error('clickDelFirstPreset miss');
}
async function waitDialog(cdp, t = 10000) {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    if (cdp.dialogs.length > 0) return cdp.dialogs.shift();
    await sleep(120);
  }
  return null;
}

// —— 页面内断言探针 ——
const CARD_CNT = `document.querySelectorAll('.script-item').length`;
const CARD_HAS = (title) => `(()=>{ const c=[...document.querySelectorAll('.script-item')].find(e=>e.textContent.includes(${Q(title)})); return !!c; })()`;
const CHIP_HAS = (txt) => `(()=>{ return [...document.querySelectorAll('.script-item')].some(e=>e.querySelector('.tag2') && e.textContent.includes(${Q(txt)})); })()`;

async function backendScenes() {
  const r = await fetch(BACKEND + '/api/scenes');
  return await r.json();
}

async function main() {
  // 清理残留 4196 测试标签页
  try {
    const tabs = await (await fetch(`${CDP_URL}/json`)).json();
    for (const t of tabs) { if (t.url && t.url.includes('127.0.0.1:4196')) { try { await fetch(`${CDP_URL}/json/close/${t.id}`); } catch { } } }
  } catch { }
  // 新开标签页
  let tab;
  try {
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' });
    tab = await r.json();
  } catch (e) {
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent('about:blank')}`);
    tab = await r.json();
  }
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `document.readyState === 'complete'`, 30000, 'load');
  await waitFor(cdp, `!!document.querySelector('.app2-nav-btn')`, 20000, 'app2 nav');

  // 后端预置场景确认
  const before = await backendScenes();
  const ids = before.map(s => s.scene_id);
  check('P0 后端预置场景就绪（删除测试×2）', ids.includes('ca685c4b') && ids.includes('script_删除测试剧本'), ids.join(','));
  const murderBackendBefore = before.filter(s => String(s.scene_id).startsWith('script_')).length;

  // 进入剧本选择页（默认剧本杀模式）
  await clickSel(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(e=>e.textContent.includes('剧本选择'))`);
  await waitFor(cdp, CARD_CNT + ' > 0', 15000, 'scripts list');
  await sleep(600);

  // ── M1 剧本杀模式：后端场景卡 + 预设卡 + chip ──
  await cdp.shot('01-murder-list.png');
  check('M1 后端 murder 剧本卡可见（删除测试·后端剧本）', await cdp.eval(CARD_HAS('删除测试·后端剧本')));
  check('M1 既有后端 murder 剧本卡可见（陆宅迷局）', await cdp.eval(CARD_HAS('陆宅迷局')));
  check('M1 预设剧本卡可见（民国旧宅疑云）', await cdp.eval(CARD_HAS('民国旧宅疑云')));
  check('M1 后端场景 chip 出现（☁️ 后端场景）', await cdp.eval(CHIP_HAS('后端场景')), 'chip probe');
  const murderCardCnt = await cdp.eval(CARD_CNT);
  check('M1 剧本杀列表数量 = 3 预设 + 后端 script_ 场景数', murderCardCnt === 3 + murderBackendBefore, `cnt=${murderCardCnt} backend=${murderBackendBefore}`);

  // ── M2 预设删除被拦截 ──
  await clickDelFirstPreset(cdp);
  const d1 = await waitDialog(cdp);
  check('M2 预设点击弹 alert', !!d1 && d1.type === 'alert', d1 ? d1.message : 'no dialog');
  check('M2 文案=预设剧本不可删除', !!d1 && d1.message.includes('预设剧本不可删除'));
  await cdp.acceptDialog(true);
  await sleep(400);
  check('M2 预设卡片保留', (await cdp.eval(CARD_CNT)) === 3 + murderBackendBefore, 'cnt=' + await cdp.eval(CARD_CNT));

  // ── M3 后端场景删除（UI）→ 卡片消失 + 后端已删 ──
  await clickDelOf(cdp, '删除测试·后端剧本');
  const d2 = await waitDialog(cdp);
  check('M3 confirm 弹窗出现', !!d2 && d2.type === 'confirm', d2 ? d2.message : 'no dialog');
  check('M3 confirm 文案含标题与「服务器」', !!d2 && d2.message.includes('删除测试·后端剧本') && d2.message.includes('服务器'));
  await cdp.acceptDialog(true);
  await waitFor(cdp, `!${CARD_HAS('删除测试·后端剧本')}`, 15000, 'backend murder card removed');
  await sleep(300);
  check('M3 卡片即时消失', !(await cdp.eval(CARD_HAS('删除测试·后端剧本'))));
  await cdp.shot('02-murder-backend-deleted.png');
  const afterM3 = await backendScenes();
  check('M3 后端 GET /api/scenes 已删 script_删除测试剧本', !afterM3.some(s => s.scene_id === 'script_删除测试剧本'));

  // ── M4 后端 murder 卡点击 → 角色选择页正确解析（标题 + 后端角色名）──
  await clickSel(cdp, `[...document.querySelectorAll('.script-item')].find(e=>e.textContent.includes('陆宅迷局')).querySelector('.si-title')`);
  await waitFor(cdp, `document.body.textContent.includes('角色选择')`, 10000, 'role select page');
  await sleep(400);
  check('M4 角色选择页标题=陆宅迷局', await cdp.eval(`document.body.textContent.includes('陆宅迷局')`));
  check('M4 后端角色名 chip 出现（苏哲/林诗）', await cdp.eval(`document.body.textContent.includes('苏哲') && document.body.textContent.includes('林诗')`));
  await cdp.shot('03-role-select-backend-murder.png');

  // 回到剧本选择（一般模式）
  await clickSel(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(e=>e.textContent.includes('剧本选择'))`);
  await waitFor(cdp, CARD_CNT + ' > 0', 15000, 'scripts list 2');
  await clickSel(cdp, `[...document.querySelectorAll('.chip2')].find(e=>e.textContent.includes('一般模式'))`);
  await waitFor(cdp, CARD_CNT + ' > 0', 15000, 'general list');
  await sleep(600);

  // ── G1 一般模式：后端场景卡 + 预设卡 + chip ──
  await cdp.shot('04-general-list.png');
  check('G1 后端 general 场景卡可见（P-0816-K删除测试场景）', await cdp.eval(CARD_HAS('P-0816-K删除测试场景')));
  check('G1 既有后端场景卡可见（默认场景）', await cdp.eval(CARD_HAS('默认场景')));
  check('G1 预设场景卡可见（街角咖啡馆）', await cdp.eval(CARD_HAS('街角咖啡馆')));
  check('G1 一般模式后端场景 chip 出现', await cdp.eval(CHIP_HAS('后端场景')));

  // ── G3 一般模式预设删除被拦截 ──
  await clickDelFirstPreset(cdp);
  const d3 = await waitDialog(cdp);
  check('G3 一般模式预设删除被拦截（alert）', !!d3 && d3.type === 'alert' && d3.message.includes('预设剧本不可删除'));
  await cdp.acceptDialog(true);
  await sleep(300);

  // ── G2 一般模式后端场景删除 → 卡片消失 + 后端已删 ──
  await clickDelOf(cdp, 'P-0816-K删除测试场景');
  const d4 = await waitDialog(cdp);
  check('G2 confirm 弹窗出现', !!d4 && d4.type === 'confirm', d4 ? d4.message : 'no dialog');
  await cdp.acceptDialog(true);
  await waitFor(cdp, `!${CARD_HAS('P-0816-K删除测试场景')}`, 15000, 'backend general card removed');
  await sleep(300);
  check('G2 卡片即时消失', !(await cdp.eval(CARD_HAS('P-0816-K删除测试场景'))));
  await cdp.shot('05-general-backend-deleted.png');
  const afterG2 = await backendScenes();
  check('G2 后端 GET /api/scenes 已删 ca685c4b', !afterG2.some(s => s.scene_id === 'ca685c4b'));

  // ── E console 无新增错误 ──
  const errs = cdp.consoleLogs.filter(l => !l.includes('charanim') && !l.includes('favicon') && !l.includes('404'));
  check('E console 无新增错误', errs.length === 0, errs.slice(0, 5).join(' | '));

  // 清理：关掉测试标签页
  try { await fetch(`${CDP_URL}/json/close/${tab.id}`); } catch { }

  const result = { pass, fail, at: new Date().toISOString(), errors: errs.slice(0, 10) };
  writeFileSync(`${OUT}/result.json`, JSON.stringify(result, null, 2));
  log('==== RESULT pass=' + pass + ' fail=' + fail + ' ====');
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => { log('FATAL', e.message); process.exit(2); });
