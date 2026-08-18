/* cdp_p0816l.mjs — P-0816-L 部署真机验证（CDP 脚本）
 * 前置：8000 已重启为新 jar（PID 29108，bundle index-CATKV_JB.js）；Edge CDP 9222 已启动
 * 目标：
 *   M1 剧本杀模式列表含后端场景卡（script_ 前缀）+ ☁️ 后端场景 chip + 预设卡
 *   M2 预设删除被拦截（alert「预设剧本不可删除」），卡片保留
 *   M3 后端 murder 卡 ✕ → confirm → 接受 → 卡片消失 + GET /api/scenes 已删（删的是本脚本自建的测试卡）
 *   G1 一般模式列表含后端 general 卡 + chip + 预设卡
 *   G2 后端 general 测试卡删除走通 + GET /api/scenes 已删
 *   G3 一般模式预设删除被拦截
 *   E  console 无新增错误（charanim 既有素材噪音除外）
 *   R  收尾：测试场景全部删净，GET /api/scenes 恢复初始 12 条
 * 输出：tmp/p0816l/（截图 + result.json + progress.log）
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://localhost:8000/';
const BACKEND = 'http://localhost:8000';
const OUT = 'D:/roleplay-java/tmp/p0816l';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };
const Q = (s) => JSON.stringify(s);

const TEST_MURDER_ID = 'script_p0816l_del_test';
const TEST_MURDER_NAME = '删除测试·P0816L后端剧本';
const TEST_GENERAL_ID = 'p0816l_del_test';
const TEST_GENERAL_NAME = 'P0816L删除测试场景';

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
        if (m.params.type === 'error' || m.params.type === 'warning') c.consoleLogs.push(m.params.type + ': ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 300));
      }
      else if (m.method === 'Runtime.exceptionThrown') c.consoleLogs.push('EXCEPTION: ' + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 300));
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

const CARD_CNT = `document.querySelectorAll('.script-item').length`;
const CARD_HAS = (title) => `(()=>{ const c=[...document.querySelectorAll('.script-item')].find(e=>e.textContent.includes(${Q(title)})); return !!c; })()`;
const CHIP_HAS = (txt) => `(()=>{ return [...document.querySelectorAll('.script-item')].some(e=>e.querySelector('.tag2') && e.textContent.includes(${Q(txt)})); })()`;

async function backendScenes() {
  const r = await fetch(BACKEND + '/api/scenes');
  return await r.json();
}

async function createTestScene(id, name, category) {
  const body = { scene_id: id, name, description: 'P-0816-L 部署验证临时场景（验证后删除）', category, initial_agent_names: [], default_roles: [], default_map: null, goals: null };
  const r = await fetch(BACKEND + '/api/scenes', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (!r.ok) throw new Error('create scene ' + id + ' failed http ' + r.status);
  return await r.json();
}

async function main() {
  // 初始库状态（应为 12 条，无测试残留）
  const initial = await backendScenes();
  const initialIds = initial.map(s => s.scene_id);
  check('P0 初始库 12 条且无测试残留', initialIds.length === 12 && !initialIds.includes(TEST_MURDER_ID) && !initialIds.includes(TEST_GENERAL_ID), `n=${initialIds.length}`);
  const initialMurderBackend = initial.filter(s => String(s.scene_id).startsWith('script_')).length;

  // 自建两条临时测试场景（murder 用 script_ 前缀 + general 用普通 id）
  await createTestScene(TEST_MURDER_ID, TEST_MURDER_NAME, 'general');
  await createTestScene(TEST_GENERAL_ID, TEST_GENERAL_NAME, 'general');
  const afterCreate = await backendScenes();
  check('P1 测试场景已创建（GET /api/scenes 可见）', afterCreate.some(s => s.scene_id === TEST_MURDER_ID) && afterCreate.some(s => s.scene_id === TEST_GENERAL_ID), `n=${afterCreate.length}`);

  // 打开浏览器 → 8000
  let tab;
  try { tab = await (await fetch(`${CDP_URL}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })).json(); }
  catch (e) { tab = await (await fetch(`${CDP_URL}/json/new?${encodeURIComponent('about:blank')}`)).json(); }
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `document.readyState === 'complete'`, 30000, 'load');
  await waitFor(cdp, `!!document.querySelector('.app2-nav-btn')`, 20000, 'app2 nav');
  check('P2 页面加载（8000 服务 index.html → 新 bundle 生效）', await cdp.eval(`document.title.includes('Roleplay v4')`));
  await cdp.shot('00-app-loaded.png');

  // 进入剧本选择页（默认剧本杀模式）
  await clickSel(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(e=>e.textContent.includes('剧本选择'))`);
  await waitFor(cdp, CARD_CNT + ' > 0', 15000, 'scripts list');
  await sleep(800);
  await cdp.shot('01-murder-list.png');

  // M1 剧本杀模式：后端卡 + 预设卡 + chip
  check('M1 后端 murder 测试卡可见（删除测试·P0816L后端剧本）', await cdp.eval(CARD_HAS(TEST_MURDER_NAME)));
  check('M1 既有后端 murder 卡可见（陆宅迷局）', await cdp.eval(CARD_HAS('陆宅迷局')));
  check('M1 预设剧本卡可见（民国旧宅疑云）', await cdp.eval(CARD_HAS('民国旧宅疑云')));
  check('M1 ☁️ 后端场景 chip 出现', await cdp.eval(CHIP_HAS('后端场景')));
  const murderCnt = await cdp.eval(CARD_CNT);
  check('M1 剧本杀列表数 = 3 预设 + 后端 script_ 数(7)', murderCnt === 3 + initialMurderBackend + 1, `cnt=${murderCnt} backend=${initialMurderBackend}+1测试`);

  // M2 预设删除被拦截
  await clickDelFirstPreset(cdp);
  const d1 = await waitDialog(cdp);
  check('M2 预设点击开 alert', !!d1 && d1.type === 'alert', d1 ? d1.message : 'no dialog');
  check('M2 文案=预设剧本不可删除', !!d1 && d1.message.includes('预设剧本不可删除'));
  await cdp.acceptDialog(true);
  await sleep(400);
  check('M2 预设卡片保留', (await cdp.eval(CARD_CNT)) === 3 + initialMurderBackend + 1);

  // M3 后端 murder 测试卡删除 → 卡片消失 + 后端已删
  await clickDelOf(cdp, TEST_MURDER_NAME);
  const d2 = await waitDialog(cdp);
  check('M3 confirm 弹窗出现', !!d2 && d2.type === 'confirm', d2 ? d2.message : 'no dialog');
  check('M3 confirm 文案含标题与「服务器」', !!d2 && d2.message.includes(TEST_MURDER_NAME) && d2.message.includes('服务器'));
  await cdp.acceptDialog(true);
  await waitFor(cdp, `!${CARD_HAS(TEST_MURDER_NAME)}`, 15000, 'backend murder card removed');
  await sleep(300);
  check('M3 卡片即时消失', !(await cdp.eval(CARD_HAS(TEST_MURDER_NAME))));
  await cdp.shot('02-murder-backend-deleted.png');
  const afterM3 = await backendScenes();
  check('M3 GET /api/scenes 已删测试卡', !afterM3.some(s => s.scene_id === TEST_MURDER_ID));
  check('M3 真实后端卡未误删（script_ 条数回到初始）', afterM3.filter(s => String(s.scene_id).startsWith('script_')).length === initialMurderBackend, 'script_ count=' + afterM3.filter(s => String(s.scene_id).startsWith('script_')).length);

  // 切一般模式
  await clickSel(cdp, `[...document.querySelectorAll('.chip2')].find(e=>e.textContent.includes('一般模式'))`);
  await waitFor(cdp, CARD_CNT + ' > 0', 15000, 'general list');
  await sleep(800);
  await cdp.shot('03-general-list.png');

  // G1 一般模式：后端卡 + chip + 预设
  check('G1 后端 general 测试卡可见', await cdp.eval(CARD_HAS(TEST_GENERAL_NAME)));
  check('G1 既有后端场景卡可见（默认场景）', await cdp.eval(CARD_HAS('默认场景')));
  check('G1 预设场景卡可见（街角咖啡馆）', await cdp.eval(CARD_HAS('街角咖啡馆')));
  check('G1 一般模式后端场景 chip 出现', await cdp.eval(CHIP_HAS('后端场景')));

  // G3 一般模式预设删除被拦截
  await clickDelFirstPreset(cdp);
  const d3 = await waitDialog(cdp);
  check('G3 一般模式预设删除被拦截（alert）', !!d3 && d3.type === 'alert' && d3.message.includes('预设剧本不可删除'));
  await cdp.acceptDialog(true);
  await sleep(300);

  // G2 后端 general 测试卡删除 → 卡片消失 + 后端已删
  await clickDelOf(cdp, TEST_GENERAL_NAME);
  const d4 = await waitDialog(cdp);
  check('G2 confirm 弹窗出现', !!d4 && d4.type === 'confirm', d4 ? d4.message : 'no dialog');
  await cdp.acceptDialog(true);
  await waitFor(cdp, `!${CARD_HAS(TEST_GENERAL_NAME)}`, 15000, 'backend general card removed');
  await sleep(300);
  check('G2 卡片即时消失', !(await cdp.eval(CARD_HAS(TEST_GENERAL_NAME))));
  await cdp.shot('04-general-backend-deleted.png');
  const afterG2 = await backendScenes();
  check('G2 GET /api/scenes 已删测试卡', !afterG2.some(s => s.scene_id === TEST_GENERAL_ID));

  // R 收尾：库恢复初始状态
  const final = await backendScenes();
  const finalIds = final.map(s => s.scene_id);
  check('R 库恢复初始 12 条（测试场景删净）', finalIds.length === 12 && initialIds.every(id => finalIds.includes(id)) && !finalIds.includes(TEST_MURDER_ID) && !finalIds.includes(TEST_GENERAL_ID), `n=${finalIds.length}`);

  // E console 检查（charanim 既有素材噪音除外）
  await sleep(500);
  const errs = cdp.consoleLogs.filter(l => !l.includes('charanim'));
  check('E console 无新增错误（charanim 噪音除外）', errs.length === 0, errs.slice(0, 5).join(' || '));

  // 汇总
  const result = { pass, fail, total: pass + fail, errs: errs.slice(0, 10), finalCount: finalIds.length };
  writeFileSync(`${OUT}/result.json`, JSON.stringify(result, null, 2));
  log(`==== SUMMARY pass=${pass} fail=${fail} total=${pass + fail} ====`);
  try { await fetch(`${CDP_URL}/json/close/${tab.id}`); } catch { }
  process.exit(fail > 0 ? 1 : 0);
}

main().catch(e => { log('FATAL', e.message); try { writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, fatal: e.message }, null, 2)); } catch { } process.exit(2); });
