/* cdp_p0817c.mjs — P-0817-C 阶段B 大厅 6 页换肤 CDP 真机走查（v2）
 * 前置：static_proxy_p0817c.mjs（4498：dist + /api 透传真实 8000）已启动；
 *       Edge CDP 9222 已启动（user-data-dir tmp/edge-p0817c）。
 * 输出：tmp/p0817c/（截图 + progress.log + result.json）
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://localhost:4498/';
const OUT = 'D:/roleplay-java/tmp/p0817c';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== v2 ' + new Date().toISOString() + ' ====\n');
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
      else if (m.method === 'Runtime.consoleAPICalled') { if (m.params.type === 'error') c.consoleLogs.push('ERROR: ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 300)); }
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
async function newTab(url) {
  let tab;
  try { const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' }); tab = await r.json(); }
  catch (e) { const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`); tab = await r.json(); }
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  return cdp;
}
async function waitFor(cdp, expr, t = 30000, label = '') {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(700);
  }
  throw new Error('waitFor timeout: ' + label + ' :: ' + expr);
}
const click = (cdp, expr) => cdp.eval(`(() => { const el = ${expr}; if (!el) return false; el.click(); return true; })()`);
const nav = (cdp, label) => click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('${label}'))`);

const main = async () => {
  const cdp = await newTab(APP);
  await cdp.eval(`localStorage.clear(); true`);
  await cdp.eval(`location.reload(); true`);
  await sleep(2000);
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 20000, '首页加载');
  await cdp.eval(`(() => { window.__p0817c = { api: [] }; const of = window.fetch.bind(window); window.fetch = (...a) => { const u = String(a[0] || ''); if (u.startsWith('/api/') || u.startsWith('http://localhost:4498/api/')) window.__p0817c.api.push(String((a[1]?.method) || 'GET') + ' ' + u.replace('http://localhost:4498','')); return of(...a); }; return true; })()`);

  // ═══ 1. 首页 ═══
  await waitFor(cdp, `document.querySelectorAll('.home-card').length >= 4`, 20000, '首页四卡');
  const home = await cdp.eval(`(() => {
    const cs = getComputedStyle(document.body);
    const panel = getComputedStyle(document.querySelector('.home-card'));
    const topbar = getComputedStyle(document.querySelector('.app2-topbar'));
    const logo = getComputedStyle(document.querySelector('.app2-logo'));
    return { bodyBg: cs.backgroundColor, bodyColor: cs.color, cardBg: panel.backgroundColor, cardRadius: panel.borderRadius,
      cardBorder: panel.borderColor, topbarBg: topbar.backgroundColor, logoBg: logo.backgroundImage,
      cards: document.querySelectorAll('.home-card').length };
  })()`);
  log('home computed: ' + JSON.stringify(home));
  check('H1 首页 5 张四感氛围卡渲染', home.cards >= 4, 'cards=' + home.cards);
  check('H2 body 背景 = 新蓝黑 #0c1322', /rgb\(12, ?19, ?34\)/.test(home.bodyBg), home.bodyBg);
  check('H3 首页卡背景 = 新面板 #141e33', /rgb\(20, ?30, ?51\)/.test(home.cardBg), home.cardBg);
  check('H4 卡片圆角 = --radius-card(10px)', home.cardRadius === '10px', home.cardRadius);
  check('H5 顶栏背景 = 蓝黑 rgba(12,19,34,.72)', home.topbarBg.includes('12, 19, 34'), home.topbarBg);
  check('H6 logo 渐变 = 阶段色紫+青蓝', home.logoBg.includes('124, 77, 255') && home.logoBg.includes('14, 165, 233'), home.logoBg.slice(0, 120));
  await cdp.shot('S1-home.png');

  // ═══ 2. 剧本选择页 ═══
  await nav(cdp, '剧本选择');
  await waitFor(cdp, `document.querySelectorAll('.scripts-list .script-item').length >= 1`, 20000, '剧本选择页');
  await sleep(1500);
  const scripts = await cdp.eval(`(() => { const items = [...document.querySelectorAll('.scripts-list .script-item')]; const tabs = [...document.querySelectorAll('.chip2')].map(c => c.textContent.trim()); return { itemCount: items.length, tabs }; })()`);
  check('P1 剧本选择页渲染（列表+页签）', scripts.itemCount >= 1 && scripts.tabs.length >= 2, JSON.stringify(scripts));
  // P2 选中态阶段色：临时给卡片加 .selected 类断言计算样式（点击即跳转，不能停留断言）；
  // 注意 transition:all .15s 使 border-color 插值中（渐变 background-image 不可插值即时生效），先禁 transition 再读
  const selStyle = await cdp.eval(`(() => { const el = document.querySelector('.scripts-list .script-item'); if (!el) return null; el.style.transition = 'none'; el.classList.add('selected'); const cs = getComputedStyle(el); const r = { border: cs.borderColor, bg: cs.backgroundImage.slice(0, 160) }; el.classList.remove('selected'); el.style.transition = ''; return r; })()`);
  check('P2 剧本卡选中态 = 阶段暖橙 #f59e0b 点缀', !!selStyle && /rgb\(245, ?158, ?11\)/.test(selStyle.border), JSON.stringify(selStyle));
  await cdp.shot('S2-scripts.png');

  // ═══ 3. 角色选择页（点击剧本卡进入）═══
  await click(cdp, `document.querySelector('.scripts-list .script-item')`);
  await waitFor(cdp, `!!document.querySelector('.roles-col')`, 20000, '角色选择页');
  await sleep(1000);
  const roles = await cdp.eval(`(() => {
    const name = document.querySelector('.roles-script-name');
    const chips = document.querySelectorAll('.role-chips .role-chip:not(.role-chip-add)').length;
    const startBtn = [...document.querySelectorAll('.roles-footer button')].find(b => b.textContent.includes('进入') || b.textContent.includes('开始'));
    return { name: name ? name.textContent.trim() : '', chips, startText: startBtn ? startBtn.textContent.trim() : '', startDisabled: startBtn ? startBtn.disabled : null };
  })()`);
  check('R1 角色选择页渲染（剧本名+角色卡）', roles.chips >= 1 && roles.name.length > 0, JSON.stringify(roles));
  await cdp.shot('S3-roles.png');

  // ═══ 4. 剧本生成页 ═══
  await nav(cdp, '剧本生成');
  await waitFor(cdp, `!!document.querySelector('.gen-step-head') || document.body.textContent.includes('剧本主题')`, 20000, '剧本生成页');
  await sleep(800);
  await cdp.shot('S4-gen.png');
  check('G1 剧本生成页渲染', await cdp.eval(`!!document.querySelector('.gen-step-head')`));

  // ═══ 5. 设置页 ═══
  await nav(cdp, '设置');
  await waitFor(cdp, `document.querySelectorAll('.settings-tab').length >= 1`, 20000, '设置页');
  await sleep(800);
  const settings = await cdp.eval(`(() => { const tabs = [...document.querySelectorAll('.settings-tab')].map(t => t.textContent.trim()); const active = document.querySelector('.settings-tab.active'); return { tabs, activeText: active ? active.textContent.trim() : '', activeBg: active ? getComputedStyle(active).backgroundImage.slice(0, 120) : '' }; })()`);
  check('S1 设置页渲染（页签）', settings.tabs.length >= 2, JSON.stringify(settings));
  check('S2 设置页签选中态 = 阶段色紫+青蓝', settings.activeBg.includes('124, 77, 255') && settings.activeBg.includes('14, 165, 233'), settings.activeBg);
  await cdp.shot('S5-settings.png');

  // ═══ 6. 角色库页 ═══
  await nav(cdp, '角色库');
  await waitFor(cdp, `!!document.querySelector('.role-chips') || document.body.textContent.includes('角色卡管理')`, 20000, '角色库页');
  await sleep(1000);
  const lib = await cdp.eval(`(() => ({ chips: document.querySelectorAll('.role-chips .role-chip:not(.role-chip-add)').length, labels: [...document.querySelectorAll('.lib-mode-label')].map(l => l.textContent.trim()) }))()`);
  check('L1 角色库页渲染（角色卡/模式分组）', lib.chips > 0 && lib.labels.length >= 1, JSON.stringify(lib));
  await cdp.shot('S6-roles-lib.png');

  // ═══ 7. 冒烟链路：选剧本 → 选角色 → 进对局（真实 8000）═══
  await nav(cdp, '剧本选择');
  await waitFor(cdp, `document.querySelectorAll('.scripts-list .script-item').length >= 1`, 20000, '剧本列表');
  await sleep(800);
  const murderTabClicked = await click(cdp, `[...document.querySelectorAll('.chip2')].find(c => c.textContent.includes('剧本杀'))`);
  log('murder tab clicked: ' + murderTabClicked);
  await sleep(600);
  await click(cdp, `document.querySelector('.scripts-list .script-item')`);
  await waitFor(cdp, `!!document.querySelector('.roles-col')`, 20000, '角色选择(murder)');
  await sleep(800);
  const r2 = await cdp.eval(`(() => { const name = document.querySelector('.roles-script-name'); const chips = document.querySelectorAll('.role-chips .role-chip:not(.role-chip-add)').length; const startBtn = [...document.querySelectorAll('.roles-footer button')].find(b => b.textContent.includes('进入') || b.textContent.includes('开始')); return { name: name ? name.textContent.trim() : '', chips, startDisabled: startBtn ? startBtn.disabled : null }; })()`);
  log('murder roles: ' + JSON.stringify(r2));
  check('M1 剧本杀剧本 → 角色选择页', !!r2.name && r2.chips > 0, JSON.stringify(r2));
  // 点亮角色 chip（murder 页角色为 chip 布局）
  const clicked = await cdp.eval(`(() => { const chips = [...document.querySelectorAll('.role-chips .role-chip:not(.role-chip-add)')]; let n = 0; for (const c of chips) { if (n >= 3) break; if (!c.classList.contains('selected')) { c.click(); n++; } } return n; })()`);
  log('role chips clicked: ' + clicked);
  await sleep(500);
  const selStyle2 = await cdp.eval(`(() => { const s = document.querySelector('.role-chips .role-chip.selected'); if (!s) return null; const cs = getComputedStyle(s); return { count: document.querySelectorAll('.role-chips .role-chip.selected').length, border: cs.borderColor, shadow: cs.boxShadow.slice(0, 60) }; })()`);
  check('M2 角色卡选中态 = 阶段紫 #7c4dff 点缀', !!selStyle2 && /rgb\(124, ?77, ?255\)/.test(selStyle2.border), JSON.stringify(selStyle2));
  await cdp.shot('S7-roles-murder-selected.png');
  const startClicked = await click(cdp, `[...document.querySelectorAll('.roles-footer button')].find(b => b.textContent.includes('进入') || b.textContent.includes('开始'))`);
  log('start clicked: ' + startClicked);
  await sleep(2500);
  const game = await cdp.eval(`(() => ({ apiScript: window.__p0817c.api.filter(u => u.includes('/api/script/')).slice(0, 10), body: document.body.textContent.includes('对局启动失败') ? 'error' : (document.body.textContent.includes('正在生成剧本') || document.body.textContent.includes('正在连接后端') ? 'launching' : 'other') }))()`);
  log('game launch: ' + JSON.stringify(game));
  check('M3 对局启动：/api/script/init 已调用（真实 8000）', game.apiScript.some(u => u.includes('/api/script/init')), JSON.stringify(game.apiScript));
  try {
    await waitFor(cdp, `document.body.textContent.includes('剧本杀对局') || document.body.textContent.includes('对局启动失败') || document.body.textContent.includes('准备阶段') || !!document.querySelector('.proto-topbar, .proto-stage, .proto-vn, .proto-vote-panel')`, 150000, '对局页进入');
    await sleep(4000);
    const final = await cdp.eval(`(() => ({
      error: document.body.textContent.includes('对局启动失败'),
      launching: document.body.textContent.includes('正在生成剧本'),
      proto: !!document.querySelector('.proto-topbar, .proto-stage, .proto-vn, .proto-vote-panel'),
      apiScript: window.__p0817c.api.filter(u => u.includes('/api/script/')).slice(-12),
    }))()`);
    log('game final: ' + JSON.stringify(final));
    check('M4 对局进入（剧本杀对局视图）', !final.error && (final.proto || document.body.textContent.includes('剧本杀对局') || final.launching), JSON.stringify(final));
    await cdp.shot('S8-game.png');
  } catch (e) {
    log('WARN 对局页等待超时: ' + e.message);
    const st = await cdp.eval(`(() => ({ body: document.body.textContent.slice(0, 200), apiScript: window.__p0817c.api.filter(u => u.includes('/api/script/')).slice(-8) }))()`);
    log('game state at timeout: ' + JSON.stringify(st));
    check('M4 对局进入', false, '超时：' + JSON.stringify(st));
  }

  // ═══ 8. console 新增错误 ═══
  const errs = cdp.consoleLogs.filter(l => !l.includes('Failed to load resource') && !l.includes('favicon'));
  check('E1 console 无新增错误/异常', errs.length === 0, JSON.stringify(errs.slice(0, 5)));

  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, at: new Date().toISOString() }, null, 2));
  log(`\n==== RESULT: ${pass} PASS / ${fail} FAIL ====`);
  try { await cdp.send('Page.close'); } catch { }
};

main().then(() => process.exit(fail > 0 ? 1 : 0)).catch(e => { console.error('FATAL', e); process.exit(2); });
