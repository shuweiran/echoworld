/* cdp_p0816j.mjs — P-0816-J 剧本卡删除按钮真机验证 CDP 脚本
 * 前置：tools/static_proxy_p0816j.mjs 运行中（4195 = P-0816-J dist bundle）；Edge CDP 9222 已启动
 * 目标：剧本选择页三类剧本删除行为实测——
 *   A 生成剧本（localStorage roleplay_demo2_generated_v1 注入）→ ✕ → confirm 弹窗 → 接受 → 卡片消失 + localStorage 槽位置空
 *   B 预设剧本（mockData 常量）→ ✕ → alert「预设剧本不可删除」→ 卡片保留（删除被拦截）
 *   C 一般模式生成剧本同样走通 + 刷新后无残留（localStorage 持久化验证）
 * 产出：tmp/p0816j/（截图 + result.json + progress.log）
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://127.0.0.1:4195/';
const OUT = 'D:/roleplay-java/tmp/p0816j';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };

// 注入数据：模拟「AI 生成剧本」落 localStorage（键 roleplay_demo2_generated_v1，结构 {murder, general}）
const SEED = {
  murder: {
    id: 'gen_test_murder', title: '删除测试·民国旧宅', tags: ['悬疑'],
    background: '用于验证删除功能的测试剧本。', playerMin: 4, playerMax: 6,
    plot: '', relations: [],
    roles: [{ id: 'r1', name: '测试角色甲', avatar: '🕵️', intro: '', personality: '', talkStyle: '', secret: '秘密', hasSecret: true, source: 'ai', homeScripts: ['gen_test_murder'] }],
    clues: [], locations: [], truth: '', killerId: '', source: 'ai',
  },
  general: {
    id: 'gen_test_general', title: '删除测试·雨夜书店', emoji: '🌍', theme: '现代', tags: ['现代'],
    desc: '用于验证删除功能的测试场景。', background: '测试背景', relations: [],
    roles: [], map: { width: 24, height: 16 }, opening: '', source: 'ai',
  },
};

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
const Q = (s) => JSON.stringify(s);

// —— 页面内断言探针 ——
const CARD_CNT = `document.querySelectorAll('.script-item').length`;
const CARD_HAS = (title) => `(()=>{ const c=[...document.querySelectorAll('.script-item')].find(e=>e.textContent.includes(${Q(title)})); return !!c; })()`;
const DEL_BTN_OF = (title, cls = '.si-del') => `(()=>{ const c=[...document.querySelectorAll('.script-item')].find(e=>e.textContent.includes(${Q(title)})); return c ? !!c.querySelector(${Q(cls)}) : false; })()`;
const LS_STATE = `(()=>{ try { return JSON.parse(localStorage.getItem('roleplay_demo2_generated_v1')||'null'); } catch(e){ return {err:String(e)}; } })()`;

async function waitDialog(cdp, t = 10000) {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    if (cdp.dialogs.length > 0) return cdp.dialogs.shift();
    await sleep(120);
  }
  return null;
}

// ── 主流程 ──
async function main() {
  // 清理残留的 4195 测试标签页（上次运行可能遗留挂起对话框的页）
  try {
    const tabs = await (await fetch(`${CDP_URL}/json`)).json();
    for (const t of tabs) { if (t.url && t.url.includes('127.0.0.1:4195')) { try { await fetch(`${CDP_URL}/json/close/${t.id}`); } catch { } } }
  } catch { }
  // 新开标签页
  let tab;
  try {
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' });
    tab = await r.json();
  } catch (e) {
    // 旧式 GET 兜底
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent('about:blank')}`);
    tab = await r.json();
  }
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `document.readyState === 'complete'`, 30000, 'load');
  await waitFor(cdp, `!!document.querySelector('.app2-nav-btn')`, 20000, 'app2 nav');

  // 注入生成剧本数据 + 刷新
  await cdp.eval(`localStorage.setItem('roleplay_demo2_generated_v1', ${Q(JSON.stringify(SEED))}); 'seeded'`);
  await cdp.send('Page.reload', { ignoreCache: true });
  await waitFor(cdp, `document.readyState === 'complete'`, 30000, 'reload');
  await waitFor(cdp, `!!document.querySelector('.app2-nav-btn')`, 20000, 'nav2');

  // 进入剧本选择页（默认剧本杀模式）
  await clickSel(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(e=>e.textContent.includes('剧本选择'))`);
  await waitFor(cdp, CARD_CNT + ' > 0', 15000, 'scripts list');

  // ── A1 生成剧本卡可见 + 有删除按钮 ──
  await sleep(400);
  await cdp.shot('01-list-with-generated.png');
  check('A1 生成剧本卡展示（含标题）', await cdp.eval(CARD_HAS('删除测试·民国旧宅')));
  check('A1 预设剧本卡 3 张 + 生成 1 张', (await cdp.eval(CARD_CNT)) === 4, 'cnt=' + await cdp.eval(CARD_CNT));
  check('A1 生成剧本卡有删除按钮', await cdp.eval(DEL_BTN_OF('删除测试·民国旧宅')));

  // ── A2 点击删除 → confirm 弹窗 → 接受 → 卡片消失 + localStorage 槽位置空 ──
  await clickDelOf(cdp, '删除测试·民国旧宅');
  const d1 = await waitDialog(cdp);
  check('A2 confirm 弹窗出现', !!d1 && d1.type === 'confirm', d1 ? d1.message : 'no dialog');
  check('A2 confirm 文案含标题', !!d1 && d1.message.includes('删除测试·民国旧宅'));
  await cdp.acceptDialog(true);
  await waitFor(cdp, `!${CARD_HAS('删除测试·民国旧宅')}`, 15000, 'murder card removed');
  await sleep(300);
  const ls1 = await cdp.eval(LS_STATE);
  check('A2 卡片即时消失（界面无残留）', !(await cdp.eval(CARD_HAS('删除测试·民国旧宅'))));
  check('A2 localStorage murder 槽位置空', ls1 && ls1.murder === null, JSON.stringify(ls1));
  await cdp.shot('02-murder-deleted.png');

  // ── B 预设剧本删除被拦截（alert 提示）──
  await clickDelFirstPreset(cdp);
  const d2 = await waitDialog(cdp);
  check('B 预设剧本点击弹提示', !!d2 && d2.type === 'alert', d2 ? d2.message : 'no dialog');
  check('B 提示文案=预设剧本不可删除', !!d2 && d2.message.includes('预设剧本不可删除'));
  await cdp.acceptDialog(true);
  await sleep(400);
  check('B 预设剧本卡片保留（删除被拦截）', (await cdp.eval(CARD_CNT)) === 3, 'cnt=' + await cdp.eval(CARD_CNT));
  check('B 预设删除按钮为禁用态样式', await cdp.eval(`[...document.querySelectorAll('.script-item')].every(c=>c.querySelector('.si-del-disabled'))`));
  await cdp.shot('03-preset-blocked.png');

  // ── C 一般模式：生成剧本删除 + 预设拦截 ──
  await clickSel(cdp, `[...document.querySelectorAll('.chip2')].find(e=>e.textContent.includes('一般模式'))`);
  await sleep(400);
  check('C 一般模式生成剧本卡展示', await cdp.eval(CARD_HAS('删除测试·雨夜书店')));
  await clickDelOf(cdp, '删除测试·雨夜书店');
  const d3 = await waitDialog(cdp);
  check('C 一般模式 confirm 弹窗出现', !!d3 && d3.type === 'confirm', d3 ? d3.message : 'no dialog');
  await cdp.acceptDialog(true);
  await waitFor(cdp, `!${CARD_HAS('删除测试·雨夜书店')}`, 15000, 'general card removed');
  const ls2 = await cdp.eval(LS_STATE);
  check('C 一般模式卡片消失 + localStorage general 槽位置空', ls2 && ls2.general === null, JSON.stringify(ls2));
  // 一般模式预设拦截
  await clickDelFirstPreset(cdp);
  const d4 = await waitDialog(cdp);
  check('C 一般模式预设删除被拦截（alert 提示）', !!d4 && d4.type === 'alert' && d4.message.includes('预设剧本不可删除'));
  await cdp.acceptDialog(true);
  await sleep(300);
  check('C 一般模式预设卡片保留', (await cdp.eval(CARD_CNT)) === 3, 'cnt=' + await cdp.eval(CARD_CNT));
  await cdp.shot('04-general-deleted.png');

  // ── D 刷新后无残留（localStorage 持久化验证）──
  await cdp.send('Page.reload', { ignoreCache: true });
  await waitFor(cdp, `document.readyState === 'complete'`, 30000, 'reload2');
  await waitFor(cdp, `!!document.querySelector('.app2-nav-btn')`, 20000, 'nav3');
  await clickSel(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(e=>e.textContent.includes('剧本选择'))`);
  await waitFor(cdp, CARD_CNT + ' > 0', 15000, 'list2');
  await sleep(400);
  check('D 刷新后生成剧本无残留（murder）', !(await cdp.eval(CARD_HAS('删除测试·民国旧宅'))));
  await clickSel(cdp, `[...document.querySelectorAll('.chip2')].find(e=>e.textContent.includes('一般模式'))`);
  await sleep(400);
  check('D 刷新后生成剧本无残留（general）', !(await cdp.eval(CARD_HAS('删除测试·雨夜书店'))));
  await cdp.shot('05-after-reload.png');

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
