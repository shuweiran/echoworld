/* cdp_full_play.mjs — 完整跑通一次剧本杀（SETUP→INVESTIGATION→DISCUSSION→VOTE→REVEAL→ENDED）
 * 路径：首页 → 剧本选择 → 剧本杀 → 民国旧宅疑云 → 选择玩家角色 沈老爷（熄灭默认同名卡）
 *   → 进入对局 → 搜证（左栏扮演绑定后点地点卡）→ 讨论（Gal 输入框发言）→ 投票（点嫌疑人卡）
 *   → resolve 揭晓 → finish 终局；全程 console 0 错误；输出 work/full_play/。
 */
import { writeFileSync, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';

const EDGE_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];
const PORT = 9254;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:8000/';
const OUT = 'D:/roleplay-java/work/full_play';
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
const apiPost = (cdp, path, body) => cdp.eval(`fetch('${path}', { method: 'POST', headers: { 'content-type': 'application/json' }, body: ${JSON.stringify(JSON.stringify(body || {}))} }).then(r => r.json()).catch(e => ({ error: String(e) }))`);
const apiGet = (cdp, path) => cdp.eval(`fetch('${path}').then(r => r.json()).catch(e => ({ error: String(e) }))`);

async function launchEdge() {
  const edge = EDGE_CANDIDATES.find(p => { try { return existsSync(p); } catch { return false; } });
  if (!edge) throw new Error('Edge not found');
  const child = spawn(edge, [
    '--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    '--user-data-dir=C:\\Temp\\edge-fullplay-' + Date.now(), 'about:blank',
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

  // ── 导航进剧本杀角色选择 ──
  await click(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(b => b.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 30000, '剧本选择页');
  await click(cdp, `[...document.querySelectorAll('.chip2')].find(b => b.textContent.includes('剧本杀'))`);
  await waitFor(cdp, `[...document.querySelectorAll('.script-item')].some(c => c.textContent.includes('民国旧宅疑云'))`, 30000, '剧本杀列表');
  await click(cdp, `[...document.querySelectorAll('.script-item')].find(c => c.textContent.includes('民国旧宅疑云'))`);
  await waitFor(cdp, `document.body.textContent.includes('进入对局')`, 30000, '角色选择页');

  // ── 选择玩家角色 沈墨（本剧本角色 chips = 角色库角色名：林晚秋/沈墨/顾云舟/苏浅浅/陈一鸣）──
  await click(cdp, `[...document.querySelectorAll('.role-chip')].find(b => b.textContent.includes('选择你的角色'))`);
  await waitFor(cdp, `!!document.querySelector('.modal-box')`, 10000, '玩家角色弹窗');
  await click(cdp, `[...document.querySelectorAll('.modal-box .role-chip')].find(b => b.textContent.includes('沈墨'))`);
  await sleep(600);
  check('T2 已选择玩家角色 沈墨', await cdp.eval(`document.body.textContent.includes('沈墨') && document.body.textContent.includes('玩家角色')`));
  // 熄灭默认同名卡（避免玩家名单重复）
  await click(cdp, `[...document.querySelectorAll('.role-chip')].find(b => b.textContent.includes('沈墨') && b.textContent.includes('🔒') && !b.textContent.includes('玩家角色'))`);
  await sleep(400);
  await cdp.shot('01-role-select.png');
  const playersBefore = await cdp.eval(`(() => {
    const chips = [...document.querySelectorAll('.role-chip')].map(c => (c.textContent || '').replace(/\\s+/g, ' ').trim());
    return chips.filter(t => t.includes('玩家角色') || t.includes('🔒') || t.includes('选择你的角色')).slice(0, 12);
  })()`);
  log('ROLE CHIPS:', JSON.stringify(playersBefore));

  // ── 进入对局 ──
  await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入对局'))`);
  await waitFor(cdp, `!!document.querySelector('.app-shell')`, 150000, '对局页挂载');
  check('T3 进入对局 → ChatPage 挂载', true);

  // ── 等搜证阶段（LLM 生成完整剧本）──
  log('等待完整剧本生成 → 搜证阶段…');
  await waitFor(cdp, `!!document.querySelector('.proto-invest-title')`, 240000, '搜证阶段', 1500);
  check('T4 进入搜证阶段', true);
  await cdp.shot('02-invest.png');

  // ── 左栏扮演绑定 沈墨（否则搜证按钮被前端守卫拦截）──
  const bound = await cdp.eval(`(() => {
    const card = [...document.querySelectorAll('.proto-char')].find(c => (c.textContent || '').includes('沈墨'));
    if (!card) return 'no-card';
    const btn = [...card.querySelectorAll('button')].find(b => (b.textContent || '').includes('扮演'));
    if (!btn) return 'no-btn';
    btn.click();
    return 'opened';
  })()`);
  await sleep(400);
  const confirmed = await cdp.eval(`(() => {
    const form = document.querySelector('.proto-play-form');
    if (!form) return 'no-form';
    const confirmBtn = [...form.querySelectorAll('button')].find(b => (b.textContent || '').includes('确认'));
    if (!confirmBtn) return 'no-confirm';
    confirmBtn.click();
    return 'bound';
  })()`);
  check('T5 扮演绑定沈墨', confirmed === 'bound', JSON.stringify({ bound, confirmed }));

  // ── 搜证：点所有未搜地点卡（AP 不足时后端返回错误 toast，正常行为）──
  await sleep(1000);
  const searchResult = await cdp.eval(`(async () => {
    const out = [];
    for (let round = 0; round < 8; round++) {
      const cards = [...document.querySelectorAll('.proto-loc.unsearched')];
      if (cards.length === 0) break;
      cards[0].click();
      out.push('click:' + (cards[0].textContent || '').trim().slice(0, 20));
      await new Promise(r => setTimeout(r, 1800));
      const searched = document.querySelectorAll('.proto-loc.searched').length;
      out.push('searched=' + searched);
      if (document.querySelector('.proto-toast')) out.push('toast:' + (document.querySelector('.proto-toast').textContent || '').trim());
    }
    return out;
  })()`);
  log('SEARCH:', JSON.stringify(searchResult));
  const searchedCount = await cdp.eval(`document.querySelectorAll('.proto-loc.searched').length`);
  check('T6 完成搜证（已搜 ≥1 地点）', searchedCount >= 1, 'searched=' + searchedCount);
  await cdp.shot('03-searched.png');

  // ── 进入讨论阶段 ──
  const disc = await apiPost(cdp, '/api/script/start_discussion', {});
  check('T7 开始讨论', disc?.phase === 'discussion', JSON.stringify(disc).slice(0, 120));
  await waitFor(cdp, `!!document.querySelector('.proto-discuss-title') || !!document.querySelector('.proto-vote-title')`, 60000, '讨论/投票阶段', 1000);
  await sleep(2500);
  // 讨论窗口：等输入框出现，发一条玩家发言
  const sent = await cdp.eval(`(async () => {
    const t0 = Date.now();
    while (Date.now() - t0 < 40000) {
      const input = document.querySelector('.gal-input');
      if (input) {
        const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
        setter.call(input, '我觉得书房里的线索很可疑，大家怎么看？');
        input.dispatchEvent(new Event('input', { bubbles: true }));
        const sendBtn = [...document.querySelectorAll('button')].find(b => (b.textContent || '').includes('发送'));
        if (sendBtn) { sendBtn.click(); return 'sent'; }
      }
      await new Promise(r => setTimeout(r, 800));
    }
    return 'no-input';
  })()`);
  check('T8 讨论发言（Gal 输入框）', sent === 'sent', 'sent=' + sent);
  await sleep(2000);
  await cdp.shot('04-discuss.png');

  // ── 等投票阶段 ──
  await waitFor(cdp, `!!document.querySelector('.proto-vote-title')`, 120000, '投票阶段', 1500);
  check('T9 进入投票阶段', true);
  await cdp.shot('05-vote.png');
  // 点嫌疑人卡（选林晚秋）→ 确认投票
  const voteClicked = await cdp.eval(`(() => {
    const cand = [...document.querySelectorAll('.proto-sus')].find(b => (b.textContent || '').includes('林晚秋'));
    if (!cand) return 'no-cand';
    cand.click();
    return 'selected';
  })()`);
  await sleep(500);
  const voteConfirmed = await cdp.eval(`(() => {
    const btn = document.querySelector('.proto-vote-btn');
    if (!btn || btn.disabled) return 'no-btn';
    btn.click();
    return 'confirmed';
  })()`);
  check('T10 投票（选中林晚秋 → 确认）', voteConfirmed === 'confirmed', JSON.stringify({ voteClicked, voteConfirmed }));
  await sleep(1500);
  await cdp.shot('06-voted.png');

  // ── 揭晓 → 终局 ──
  const reveal = await apiPost(cdp, '/api/script/resolve', {});
  check('T11 揭晓（resolve）', !reveal?.error, JSON.stringify(reveal).slice(0, 160));
  await sleep(2500);
  await cdp.shot('07-reveal.png');
  const finish = await apiPost(cdp, '/api/script/finish', {});
  check('T12 终局（finish）', !finish?.error, JSON.stringify(finish).slice(0, 160));
  await sleep(2500);
  await cdp.shot('08-ended.png');

  // ── 终局状态核验（status + winner + truth + 落库日志）──
  const finalStatus = await apiGet(cdp, `/api/script/status?player=${encodeURIComponent('沈墨')}`);
  finalStatus.rolesCount = (finalStatus.roles || []).length;
  finalStatus.playersCount = (finalStatus.players || []).length;
  finalStatus.truthBrief = (finalStatus.truth || '').slice(0, 80);
  log('FINAL:', JSON.stringify(finalStatus));
  check('T13 终局 phase=ended', finalStatus.phase === 'ended', JSON.stringify(finalStatus));
  check('T14 有胜者/真相', !!finalStatus.winner || !!finalStatus.truth, JSON.stringify(finalStatus));
  writeFileSync(`${OUT}/final-status.json`, JSON.stringify(finalStatus, null, 2));

  log('CONSOLE ERRORS:', JSON.stringify(cdp.consoleLogs.slice(0, 10)));
  check('T15 console 0 错误', cdp.consoleLogs.length === 0, JSON.stringify(cdp.consoleLogs.slice(0, 3)));
  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, consoleErrors: cdp.consoleLogs.slice(0, 10), finalStatus }, null, 2));
  log(`RESULT ${pass}/${pass + fail}`);
  process.exit(0);
};

main().catch(e => { log('FATAL', e.message); process.exit(1); });
