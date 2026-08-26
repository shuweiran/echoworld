/* cdp_p0817d.mjs — P-0817-D 阶段C 玩法页对齐 CDP 真机走查
 * 前置：static_proxy_p0817d.mjs（4497：dist + /api 透传真实 8000）已启动；
 *       Edge CDP 9222 已启动（user-data-dir tmp/edge-p0817d）。
 * 覆盖：① 狼人杀对局页（旧布局换肤 + 阶段色点缀）② 一般模式 Gal 对局页（底色层次）
 *       ③ 一般模式经典视图（ChatPage 新皮肤）④ 2D Phaser 外围壳（canvas 零改动）
 * 输出：tmp/p0817d/（截图 + progress.log + result.json）
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const APP = 'http://localhost:4497/';
const OUT = 'D:/echoworld/tmp/p0817d';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== CDP ' + new Date().toISOString() + ' ====\n');
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
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 60000);
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
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
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
// 对局页内无 app2 nav：先点 GameBridge「🏠 模式选择」返回 home，再导航
const backToHome = async (cdp) => {
  const hasGame = await cdp.eval(`!!document.querySelector('.btn2') && !!document.body.textContent.includes('模式选择') && !document.querySelector('.app2-topbar')`);
  if (hasGame) {
    await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('模式选择'))`);
    await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 20000, '返回 home');
    await sleep(800);
  }
};

const main = async () => {
  // ═══════════ 0. 首页（基线 + fetch 追踪） ═══════════
  const cdp = await newTab(APP);
  await cdp.eval(`localStorage.clear(); true`);
  await cdp.eval(`location.reload(); true`);
  await sleep(2000);
  await waitFor(cdp, `!!document.querySelector('.app2-topbar')`, 20000, '首页加载');
  await cdp.eval(`(() => { window.__d = { api: [] }; const of = window.fetch.bind(window); window.fetch = (...a) => { const u = String(a[0] || ''); if (u.startsWith('/api/') || u.startsWith('http://localhost:4497/api/')) window.__d.api.push(String((a[1]?.method) || 'GET') + ' ' + u.replace('http://localhost:4497','')); return of(...a); }; return true; })()`);
  const home = await cdp.eval(`(() => { const cs = getComputedStyle(document.body); const card = getComputedStyle(document.querySelector('.home-card')); return { bodyBg: cs.backgroundColor, cardBg: card.backgroundColor, cardRadius: card.borderRadius, cards: document.querySelectorAll('.home-card').length }; })()`);
  log('home computed: ' + JSON.stringify(home));
  check('H1 body 背景 = 新蓝黑 #0c1322', /rgb\(12, ?19, ?34\)/.test(home.bodyBg), home.bodyBg);
  check('H2 首页卡背景 = 新面板 #141e33', /rgb\(20, ?30, ?51\)/.test(home.cardBg), home.cardBg);

  // ═══════════ 1. 狼人杀对局页（C1） ═══════════
  const wwNav = await nav(cdp, '狼人杀');
  log('nav werewolf: ' + wwNav);
  await waitFor(cdp, `!!document.querySelector('.roles-col')`, 20000, '狼人杀角色选择页');
  await sleep(800);
  const wwRoles = await cdp.eval(`(() => { const chips = [...document.querySelectorAll('.role-chips .role-chip')].map(c => c.textContent.trim()).filter(Boolean); const startBtn = [...document.querySelectorAll('.roles-footer button')].find(b => b.textContent.includes('开始狼人杀')); const bg = getComputedStyle(document.querySelector('.roles-col') || document.body).backgroundColor; return { chipCount: chips.length, startDisabled: startBtn ? startBtn.disabled : null, bg }; })()`);
  log('ww roles: ' + JSON.stringify(wwRoles));
  check('W1 狼人杀角色选择页渲染（职业卡+开始按钮可用）', wwRoles.chipCount >= 4 && wwRoles.startDisabled === false, JSON.stringify(wwRoles));
  const wwStart = await click(cdp, `[...document.querySelectorAll('.roles-footer button')].find(b => b.textContent.includes('开始狼人杀'))`);
  log('ww start clicked: ' + wwStart);
  await waitFor(cdp, `!!document.querySelector('.workspace.werewolf-mode, .werewolf-panel, .ww-panel') || document.body.textContent.includes('狼人杀') && document.body.textContent.includes('夜间') || document.body.textContent.includes('存活')`, 90000, '狼人杀对局页');
  await sleep(5000);
  const ww = await cdp.eval(`(() => {
    const shell = document.querySelector('.app-shell') || document.body;
    const panel = document.querySelector('.panel, .ww-panel, .werewolf-panel');
    const btn = document.querySelector('.btn');
    const cs = getComputedStyle(shell);
    const panelCs = panel ? getComputedStyle(panel) : null;
    const bcs = btn ? getComputedStyle(btn) : null;
    const apiInit = window.__d.api.filter(u => u.includes('/api/werewolf/init'));
    // 注入 phase-banner 断言狼人杀阶段色点缀（夜间/投票渐变）
    const probe = document.createElement('div');
    probe.className = 'phase-banner phase-vote';
    probe.style.display = 'inline-block'; probe.style.position = 'fixed'; probe.style.left = '-9999px';
    document.body.appendChild(probe);
    const voteBg = getComputedStyle(probe).backgroundImage;
    const voteBorder = getComputedStyle(probe).borderBottomColor;
    probe.className = 'phase-banner phase-night';
    const nightBg = getComputedStyle(probe).backgroundImage;
    probe.remove();
    return { wsBg: cs.backgroundColor, btnRadius: bcs ? bcs.borderRadius : null, panelBg: panelCs ? panelCs.backgroundColor : null,
      panelRadius: panelCs ? panelCs.borderRadius : null, voteBg, voteBorder, nightBg,
      hasWwMode: !!document.querySelector('.workspace.werewolf-mode'), apiInit: apiInit.length, bodyHead: document.body.textContent.slice(0, 120) };
  })()`);
  log('ww game computed: ' + JSON.stringify(ww));
  check('W2 狼人杀对局启动：/api/werewolf/init 已调用（真实 8000）', ww.apiInit >= 1, 'init=' + ww.apiInit);
  check('W3 对局背景 = 新蓝黑 #0c1322', /rgb\(12, ?19, ?34\)/.test(ww.wsBg), ww.wsBg);
  check('W4 面板背景 = 新面板 #141e33', !!ww.panelBg && /rgb\(20, ?30, ?51\)/.test(ww.panelBg), ww.panelBg);
  check('W5 对局按钮圆角 = 10px（--radius 统一上浮）', ww.btnRadius === '10px', ww.btnRadius);
  check('W6 投票阶段色点缀 = 红紫渐变', ww.voteBg.includes('160, 32, 64') && ww.voteBg.includes('110, 45, 160'), ww.voteBg.slice(0, 120));
  check('W7 夜间阶段色点缀 = 深紫渐变', ww.nightBg.includes('76, 45, 150'), ww.nightBg.slice(0, 120));
  await cdp.shot('S1-werewolf.png');

  // ═══════════ 2. 一般模式 Gal 对局页（C2） ═══════════
  await backToHome(cdp);
  await nav(cdp, '剧本选择');
  await waitFor(cdp, `document.querySelectorAll('.scripts-list .script-item').length >= 1`, 30000, '剧本选择页');
  await sleep(1000);
  const genTab = await click(cdp, `[...document.querySelectorAll('.chip2')].find(c => c.textContent.includes('一般'))`);
  log('general tab: ' + genTab);
  await sleep(600);
  await click(cdp, `document.querySelector('.scripts-list .script-item')`);
  await waitFor(cdp, `!!document.querySelector('.roles-col')`, 30000, '角色选择(general)');
  await sleep(1000);
  // 点亮 2 个角色卡（一般模式需要 litCount>0）
  const lit = await cdp.eval(`(() => { const chips = [...document.querySelectorAll('.role-chips .role-chip:not(.role-chip-add)')]; let n = 0; for (const c of chips) { if (n >= 2) break; if (!c.classList.contains('selected')) { c.click(); n++; } } return n; })()`);
  log('general chips lit: ' + lit);
  await sleep(600);
  const galStart = await click(cdp, `[...document.querySelectorAll('.roles-footer button')].find(b => b.textContent.includes('进入对局'))`);
  log('gal start clicked: ' + galStart);
  await waitFor(cdp, `!!document.querySelector('.galg-stage, .gal-stage, .gal-dialog-box, .galg-foreground, .gal-root')`, 90000, 'Gal 对局页');
  await sleep(6000);
  const gal = await cdp.eval(`(() => {
    const stage = document.querySelector('.galg-stage, .gal-stage, .galg-foreground, .gal-root');
    const cs = getComputedStyle(document.body);
    const stageCs = stage ? getComputedStyle(stage) : null;
    // --gal-* 定义在 .galg-page/.gal-page 作用域（自定义属性继承，读子元素亦可）
    const galScope = document.querySelector('.galg-page, .gal-page');
    const gcs = galScope ? getComputedStyle(galScope) : null;
    const galBg = gcs ? gcs.getPropertyValue('--gal-bg').trim() : '';
    const galAccent = gcs ? gcs.getPropertyValue('--gal-accent').trim() : '';
    const galFont = gcs ? gcs.getPropertyValue('--gal-font').trim() : '';
    const baseAccentGal = getComputedStyle(document.documentElement).getPropertyValue('--color-accent-gal').trim();
    const classicBtn = [...document.querySelectorAll('button')].find(b => b.textContent.includes('经典视图'));
    const apiStart = window.__d.api.filter(u => u.includes('/api/scenes/start') || u.includes('/api/startScene'));
    return { bodyBg: cs.backgroundColor, stageBg: stageCs ? stageCs.backgroundColor : null, galBg, galAccent, baseAccentGal, galFont, hasClassic: !!classicBtn, apiStart: apiStart.length };
  })()`);
  log('gal computed: ' + JSON.stringify(gal));
  check('G1 Gal 对局页渲染（stage + 经典视图按钮）', gal.hasClassic, JSON.stringify(gal));
  check('G2 --gal-bg = 新蓝黑 #0c1322', gal.galBg === '#0c1322', gal.galBg);
  check('G3 --gal-accent 霓虹粉保留（专属变体）', gal.galAccent === '#ff6ad5' || gal.galAccent.includes('ff6ad5') || gal.galAccent.includes('255, 106, 213'), gal.galAccent + ' (base: ' + gal.baseAccentGal + ')');
  check('G4 像素风字体保留', gal.galFont.toLowerCase().includes('monospace') || gal.galFont.toLowerCase().includes('courier') || gal.galFont.toLowerCase().includes('nsimsun'), gal.galFont);
  await cdp.shot('S2-gal.png');

  // ═══════════ 3. 一般模式经典视图（C2） ═══════════
  const classicClicked = await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('经典视图'))`);
  log('classic clicked: ' + classicClicked);
  await waitFor(cdp, `!!document.querySelector('.workspace') && !document.querySelector('.galg-foreground, .gal-stage, .gal-root')`, 30000, '经典视图');
  await sleep(3000);
  const cls = await cdp.eval(`(() => {
    const shell = document.querySelector('.app-shell') || document.body;
    const panel = document.querySelector('.panel');
    const btn = document.querySelector('.btn');
    const cs = getComputedStyle(shell);
    const pcs = panel ? getComputedStyle(panel) : null;
    const bcs = btn ? getComputedStyle(btn) : null;
    return { wsBg: cs.backgroundColor, panelBg: pcs ? pcs.backgroundColor : null,
      panelRadius: pcs ? pcs.borderRadius : null, btnBg: bcs ? bcs.backgroundColor : null,
      btnRadius: bcs ? bcs.borderRadius : null, hasTopbar: !!document.querySelector('.topbar, .chat-topbar') };
  })()`);
  log('classic computed: ' + JSON.stringify(cls));
  check('C1 经典视图渲染（workspace+顶栏）', cls.hasTopbar && !!cls.wsBg, JSON.stringify(cls));
  check('C2 经典视图背景 = 新蓝黑 #0c1322', /rgb\(12, ?19, ?34\)/.test(cls.wsBg), cls.wsBg);
  check('C3 经典视图面板 = 新面板 #141e33', /rgb\(20, ?30, ?51\)/.test(cls.panelBg), cls.panelBg);
  check('C4 经典视图按钮 = 面板次级 #1b2944 + 圆角', /rgb\(27, ?41, ?68\)/.test(cls.btnBg), cls.btnBg + ' / ' + cls.btnRadius);
  await cdp.shot('S3-classic.png');

  // ═══════════ 4. 2D Phaser 外围壳（C3） ═══════════
  await backToHome(cdp);
  await nav(cdp, '剧本选择');
  await waitFor(cdp, `document.querySelectorAll('.scripts-list .script-item').length >= 1`, 30000, '剧本选择页');
  await sleep(1000);
  await click(cdp, `[...document.querySelectorAll('.chip2')].find(c => c.textContent.includes('一般'))`);
  await sleep(600);
  await click(cdp, `document.querySelector('.scripts-list .script-item')`);
  await waitFor(cdp, `!!document.querySelector('.roles-col')`, 30000, '角色选择(general)');
  await sleep(1000);
  const exploreMode = await click(cdp, `[...document.querySelectorAll('.chip2')].find(c => c.textContent.includes('2D 探索'))`);
  log('explore mode: ' + exploreMode);
  await sleep(500);
  const lit2 = await cdp.eval(`(() => { const chips = [...document.querySelectorAll('.role-chips .role-chip:not(.role-chip-add)')]; let n = 0; for (const c of chips) { if (n >= 2) break; if (!c.classList.contains('selected')) { c.click(); n++; } } return n; })()`);
  log('explore chips lit: ' + lit2);
  await sleep(600);
  const exploreStart = await click(cdp, `[...document.querySelectorAll('.roles-footer button')].find(b => b.textContent.includes('进入 2D 探索'))`);
  log('explore start: ' + exploreStart);
  let canvasOk = false;
  try {
    await waitFor(cdp, `!!document.querySelector('canvas')`, 150000, '2D 探索视图 canvas');
    canvasOk = true;
  } catch (e) {
    const st = await cdp.eval(`(() => ({
      body: document.body.textContent.slice(0, 300),
      hasError: document.body.textContent.includes('对局启动失败'),
      loading: document.body.textContent.includes('正在加载 2D'),
      api: window.__d.api.filter(u => u.includes('/map')).slice(-5),
      canvases: document.querySelectorAll('canvas').length,
      errs: window.__d.errs || []
    }))()`);
    log('2D timeout state: ' + JSON.stringify(st));
    check('D1 2D 视图 canvas 渲染（尺寸 > 0）', false, '超时：' + JSON.stringify(st).slice(0, 220));
    await cdp.shot('S4-2d-timeout.png');
  }
  if (canvasOk) {
    await sleep(8000);
  const td = await cdp.eval(`(() => {
    const canvas = document.querySelector('canvas');
    // 外围壳断言：2D 视图右侧聊天面板（.sim-chat-panel）消费全局 token --panel-2/--border
    const chat = document.querySelector('.sim-chat-panel');
    const chatCs = chat ? getComputedStyle(chat) : null;
    const apiMap = window.__d.api.filter(u => u.includes('/api/scenes/map') || u.includes('/api/script/map') || u.includes('/map'));
    return { canvasW: canvas ? canvas.width : 0, canvasH: canvas ? canvas.height : 0,
      chatBg: chatCs ? chatCs.backgroundColor : null, chatBorderL: chatCs ? chatCs.borderLeftColor : null,
      apiMap: apiMap.length };
  })()`);
  log('2d computed: ' + JSON.stringify(td));
  check('D1 2D 视图 canvas 渲染（尺寸 > 0）', td.canvasW > 0 && td.canvasH > 0, td.canvasW + 'x' + td.canvasH);
  check('D2 2D 外围聊天面板 = token 化（--panel-2 次级面板 #1b2944）', !!td.chatBg && /rgb\(27, ?41, ?68\)/.test(td.chatBg), td.chatBg);
  check('D2b 2D 外围面板描边 = 新边框 #2b3854', !!td.chatBorderL && /rgb\(43, ?56, ?84\)/.test(td.chatBorderL), td.chatBorderL);
  check('D3 2D 地图 API 已调用（LLM 生成或 park 回退）', td.apiMap >= 1, 'map calls=' + td.apiMap);
  await cdp.shot('S4-2d.png');
  }

  // E1 console 新增错误：过滤既有 benign 噪音（favicon / Failed to load resource / charanim 素材 JSON 解析）
  const errs = cdp.consoleLogs.filter(l => !l.includes('Failed to load resource') && !l.includes('favicon') && !l.includes('charanim') && !l.includes('Expected property name'));
  check('E1 console 无新增错误/异常', errs.length === 0, JSON.stringify(errs.slice(0, 5)));

  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, at: new Date().toISOString() }, null, 2));
  log(`\n==== RESULT: ${pass} PASS / ${fail} FAIL ====`);
  try { await cdp.send('Page.close'); } catch { }
};

main().then(() => process.exit(fail > 0 ? 1 : 0)).catch(e => { console.error('FATAL', e); process.exit(2); });
