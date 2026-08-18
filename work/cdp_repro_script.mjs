/* cdp_repro_script.mjs — 临时复现：剧本杀前端聊天问题（准备无聊天 / 搜证莫名消息 / 顺序）
 * 路径：首页 → 剧本选择 → 剧本杀模式 → 民国旧宅疑云 → 进入对局
 * 输出：work/repro_script/（截图 + dom 快照 + result.json）
 */
import { writeFileSync, mkdirSync, appendFileSync, existsSync } from 'node:fs';
import { spawn } from 'node:child_process';

const EDGE_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
];
const PORT = 9253;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:8000/';
const OUT = 'D:/roleplay-java/work/repro_script_deploy';
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

/** 采集 Gal 聊天区 DOM 快照 */
const galSnapshotExpr = `(() => {
  const q = s => [...document.querySelectorAll(s)];
  const txt = q('.gal-dialog-text').map(e => (e.textContent || '').trim()).filter(Boolean);
  const name = q('.gal-dialog-name').map(e => (e.textContent || '').trim()).filter(Boolean);
  const logLines = q('.gal-log-line').map(e => (e.textContent || '').trim()).filter(Boolean).slice(0, 12);
  const hint = q('.gal-dialog-hint').map(e => (e.textContent || '').trim()).filter(Boolean);
  const waitLabel = q('.gal-wait-label').map(e => (e.textContent || '').trim()).filter(Boolean);
  const waitHint = q('.gal-wait-hint').map(e => (e.textContent || '').trim()).filter(Boolean);
  const narratorText = q('.gal-narrator-text').map(e => (e.textContent || '').trim()).filter(Boolean);
  const inputPh = q('.gal-input').map(e => e.getAttribute('placeholder') || '').filter(Boolean);
  const inputLock = q('.script-gal-chat .gal-input-row, .script-gal-chat input').length;
  const actionBar = q('.script-gal-action-bar').map(e => (e.textContent || '').trim()).filter(Boolean);
  const chatRect = (() => { const el = document.querySelector('.script-gal-chat'); if (!el) return null; const r = el.getBoundingClientRect(); return { h: Math.round(r.height), y: Math.round(r.y), w: Math.round(r.width) }; })();
  const invest = document.querySelector('.proto-invest-title')?.textContent?.trim() || '';
  const setup = document.querySelector('.proto-setup-title')?.textContent?.trim() || '';
  const discuss = document.querySelector('.proto-discuss-title')?.textContent?.trim() || '';
  const vote = document.querySelector('.proto-vote-title')?.textContent?.trim() || '';
  const reveal = document.body.textContent.includes('揭晓') && document.querySelector('.proto-reveal') ? 'reveal' : '';
  const phaseBadge = q('.phase-banner').map(e => (e.textContent || '').trim()).filter(Boolean).slice(0, 3);
  return { invest, setup, discuss, vote, reveal, phaseBadge, chatRect, txt, name, logLines, hint, waitLabel, waitHint, narratorText, inputPh, inputLock, actionBar,
    bodySnippet: document.body.textContent.replace(/\\s+/g, ' ').slice(0, 300) };
})()`;

async function launchEdge() {
  const edge = EDGE_CANDIDATES.find(p => { try { return existsSync(p); } catch { return false; } });
  if (!edge) throw new Error('Edge not found');
  const child = spawn(edge, [
    '--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--autoplay-policy=no-user-gesture-required',
    '--user-data-dir=C:\\Temp\\edge-repro-script-' + Date.now(), 'about:blank',
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
  await cdp.shot('00-role-select.png');
  await click(cdp, `[...document.querySelectorAll('button')].find(b => b.textContent.includes('进入对局'))`);
  await waitFor(cdp, `!!document.querySelector('.app-shell')`, 120000, '对局页挂载');
  check('T2 进入对局 → ChatPage 挂载', true);

  // ── 准备阶段观察 ──
  await sleep(2500);
  const setupSnap = await cdp.eval(galSnapshotExpr);
  writeFileSync(`${OUT}/setup-snapshot.json`, JSON.stringify(setupSnap, null, 2));
  await cdp.shot('01-setup.png');
  log('SETUP snapshot:', JSON.stringify(setupSnap));
  check('T3 准备阶段存在 Gal 聊天区（.script-gal-chat）', await cdp.eval(`!!document.querySelector('.script-gal-chat')`));
  check('T4 准备阶段聊天区有可见文字（等待态/旁白）', (setupSnap.txt.length + setupSnap.logLines.length + setupSnap.narratorText.length + setupSnap.waitLabel.length) > 0, JSON.stringify({ txt: setupSnap.txt, log: setupSnap.logLines, narr: setupSnap.narratorText, wait: setupSnap.waitLabel }));

  // ── 等完整剧本生成 → 搜证阶段 ──
  log('等待搜证阶段（LLM 生成可能 30-120s）…');
  const investAt = await waitFor(cdp, `!!document.querySelector('.proto-invest-title')`, 240000, '搜证阶段', 1500);
  check('T5 进入搜证阶段', investAt);
  await sleep(4000);
  const investSnap1 = await cdp.eval(galSnapshotExpr);
  writeFileSync(`${OUT}/invest-snapshot-1.json`, JSON.stringify(investSnap1, null, 2));
  await cdp.shot('02-invest.png');
  log('INVEST snapshot#1:', JSON.stringify(investSnap1));

  // 观察 12s，看是否有“莫名”消息自动冒出
  const t0 = Date.now();
  const seen = [];
  while (Date.now() - t0 < 12000) {
    await sleep(2000);
    const snap = await cdp.eval(galSnapshotExpr);
    seen.push({ t: Date.now() - t0, txt: snap.txt, log: snap.logLines });
  }
  writeFileSync(`${OUT}/invest-observe.json`, JSON.stringify(seen, null, 2));
  const investSnap2 = await cdp.eval(galSnapshotExpr);
  writeFileSync(`${OUT}/invest-snapshot-2.json`, JSON.stringify(investSnap2, null, 2));
  await cdp.shot('03-invest-12s.png');
  log('INVEST snapshot#2:', JSON.stringify(investSnap2));
  check('T6 搜证阶段聊天区有内容（旁白/角色消息）', (investSnap2.txt.length + investSnap2.logLines.length + investSnap2.narratorText.length) > 0, JSON.stringify({ txt: investSnap2.txt, log: investSnap2.logLines, narr: investSnap2.narratorText }));

  // ── 布局修复验证：Gal 聊天区可见高度 ──
  check('T7 Gal 聊天区可见高度 ≥ 200px（此前 0 高度不可见）', (investSnap2.chatRect?.h || 0) >= 200, JSON.stringify(investSnap2.chatRect));
  check('T8 搜证阶段无「阶段切换：」系统消息', !JSON.stringify(investSnap2).includes('阶段切换：INVESTIGATION'), JSON.stringify(investSnap2.txt));

  // ── 推进到讨论：验证 Gal 舞台消息逐字播放 + 当前发言操作条 ──
  await cdp.eval(`fetch('/api/script/start_discussion', { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' }).then(r => r.json()).then(d => window.__disc = d).catch(e => window.__disc = { error: String(e) }); true`);
  // 讨论窗口高频采样：每 800ms 探测阶段 + Gal 舞台（自动点击推进，让队列逐条播放）
  const discT0 = Date.now();
  let maxChars = 0;
  let sawAgentName = false;
  let sawActionBar = false;
  let sawDiscussTitle = false;
  while (Date.now() - discT0 < 60000) {
    await sleep(800);
    const snap = await cdp.eval(galSnapshotExpr);
    if (snap.discuss) sawDiscussTitle = true;
    const chars = (snap.txt.join('') + snap.narratorText.join('')).length;
    if (chars > maxChars) maxChars = chars;
    if (snap.name.length > 0 && !snap.name.some(n => n.includes('剧本杀') || n.includes('系统'))) sawAgentName = true;
    if (snap.actionBar.length > 0) sawActionBar = true;
    // 点击推进（打字中=跳过/完成=下一条）
    await cdp.eval(`(() => { const d = document.querySelector('.gal-dialog'); if (d) d.click(); return true; })()`);
    // 阶段离开讨论 → 结束采样
    if (sawDiscussTitle && !snap.discuss && snap.vote) break;
    if (sawActionBar && sawAgentName) break;
  }
  const discussSnap2 = await cdp.eval(galSnapshotExpr);
  writeFileSync(`${OUT}/discuss-snapshot-2.json`, JSON.stringify(discussSnap2, null, 2));
  await cdp.shot('05-discuss.png');
  log('DISCUSS snapshot:', JSON.stringify(discussSnap2));
  check('T9 讨论阶段 Gal 聊天区可见高度 ≥ 200px', (discussSnap2.chatRect?.h || 0) >= 200, JSON.stringify(discussSnap2.chatRect));
  check('T10 讨论消息在 Gal 舞台逐字显示（打字机推进）', maxChars >= 4, 'maxChars=' + maxChars);
  check('T11 讨论出现角色发言（非系统名）', sawAgentName, 'sawAgentName=' + sawAgentName);
  check('T12 讨论「当前发言」操作条出现（质询/引用）', sawActionBar, 'sawActionBar=' + sawActionBar);

  // console 错误汇总
  log('CONSOLE ERRORS:', JSON.stringify(cdp.consoleLogs.slice(0, 10)));
  writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, consoleErrors: cdp.consoleLogs.slice(0, 10) }, null, 2));
  log(`RESULT ${pass}/${pass + fail}`);
  process.exit(0);
};

main().catch(e => { log('FATAL', e.message); process.exit(1); });
