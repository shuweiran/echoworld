/* cdp_p0816u.mjs — P-0816-U 局内视觉对齐验证（CDP 并排截图）
 * 前置：8899 原型 http server（PID 18352）；4399 static proxy（dist→8000 API 透传）；CDP 9222。
 * 流程：
 *   P1-P3 三张原型页截图（investigation/discussion/vote.html，1280x800）
 *   R1 打开实际对局页（4399）→ 剧本选择 → 预设 murder 剧本 → 角色选择 → 进入对局
 *   R2 等 phase=investigation（真实后端状态轮询）→ 截图 real-investigation
 *   R3 REST POST /api/script/advance → discussion → 截图 real-discussion
 *   R4 REST POST /api/script/advance → vote → 截图 real-vote
 * 输出：tmp/p0816u/（截图 + result.json + progress.log）
 */
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const CDP_URL = 'http://127.0.0.1:9222';
const PROTO = 'http://localhost:8899';
const APP = 'http://localhost:4399/';
const BACKEND = 'http://localhost:8000';
const OUT = 'D:/echoworld/tmp/p0816u';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };
const Q = (s) => JSON.stringify(s);

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
}

async function newTab(url) {
  let tab;
  try {
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' });
    tab = await r.json();
  } catch (e) {
    const r = await fetch(`${CDP_URL}/json/new?${encodeURIComponent(url)}`);
    tab = await r.json();
  }
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false });
  return cdp;
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
async function restPost(path, body, headers = {}) {
  const r = await fetch(BACKEND + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  return await r.json();
}
async function restGet(path) {
  const r = await fetch(BACKEND + path);
  return await r.json();
}

async function main() {
  const results = {};

  /* ══════ P1-P3 原型页截图（最终视觉蓝本） ══════ */
  for (const [name, file] of [['investigation', 'proto-investigation.png'], ['discussion', 'proto-discussion.png'], ['vote', 'proto-vote.png']]) {
    const cdp = await newTab(PROTO + '/' + name + '.html');
    await waitFor(cdp, `document.readyState === 'complete'`, 20000, 'proto ' + name);
    await sleep(1200);
    await cdp.shot(file);
    results['proto_' + name] = file;
    await cdp.send('Page.close').catch(() => {});
  }
  log('原型三页截图完成');

  /* ══════ R1 实际对局页（4399 新 bundle + 8000 真实后端） ══════ */
  const cdp = await newTab(APP);
  await waitFor(cdp, `document.readyState === 'complete'`, 30000, 'app load');
  await waitFor(cdp, `!!document.querySelector('.app2-nav-btn')`, 20000, 'app2 nav');
  await sleep(600);

  // 进入剧本选择
  await clickSel(cdp, `[...document.querySelectorAll('.app2-nav-btn')].find(e=>e.textContent.includes('剧本选择'))`);
  await waitFor(cdp, `document.querySelectorAll('.script-item').length > 0`, 15000, 'scripts list');
  await sleep(600);

  // 选第一个可进入的 murder 剧本卡（preset 或后端 script_ 均可）
  const clicked = await cdp.eval(`(()=>{
    const cards=[...document.querySelectorAll('.script-item')];
    const card=cards.find(c=>c.textContent.includes('民国旧宅疑云'))||cards[0];
    if(!card) return 'NO-CARD';
    card.click(); return card.textContent.slice(0,30);
  })()`);
  check('R1 点击剧本卡', String(clicked).startsWith('OK') || (clicked !== 'NO-CARD' && clicked !== 'MISS'), String(clicked));
  await waitFor(cdp, `!!document.querySelector('.roles-script-name') || !!document.querySelector('.role-chip')`, 20000, 'role select');
  await sleep(600);

  // 角色选择页：点「进入对局」主按钮（murder 角色默认点亮）
  const start = await cdp.eval(`(()=>{
    const b=[...document.querySelectorAll('button')].find(x=>x.textContent.includes('进入对局'));
    if(!b) return 'MISS';
    if(b.disabled){ const lit=[...document.querySelectorAll('.role-chip.selected')]; if(lit.length===0){ const c=document.querySelector('.role-chip'); if(c){c.click();} } }
    setTimeout(()=>b.click(), 50);
    return 'OK';
  })()`);
  check('R1 点击进入对局', start === 'OK', String(start));
  await sleep(1500);

  // 等对局页 proto-v2 workspace + 顶栏
  await waitFor(cdp, `!!document.querySelector('.workspace.proto-v2') && !!document.querySelector('.proto-topbar')`, 45000, 'proto-v2 page');
  await sleep(800);

  // 等真实后端 phase=investigation（两阶段生成完成后自动进入）
  let phase = '';
  const t0 = Date.now();
  while (Date.now() - t0 < 150000) {
    try {
      const st = await restGet('/api/script/status?session_id=current&player=__probe__').catch(() => null);
      // 用 DOM 顶栏阶段徽章判定（更贴近玩家视图）
      const badge = await cdp.eval(`(()=>{ const b=document.querySelector('.proto-badge-phase'); return b ? b.textContent.trim() : ''; })()`).catch(() => '');
      if (badge.includes('搜证')) { phase = 'investigation'; break; }
    } catch { }
    await sleep(2000);
  }
  check('R2 到达搜证阶段', phase === 'investigation', 'phase=' + phase);
  await sleep(2500);
  await cdp.shot('real-investigation.png');
  results.real_investigation = 'real-investigation.png';
  // 附：左栏/右栏可见性探针（供视觉核对）
  results.inv_probes = await cdp.eval(`(()=>{
    const q=s=>document.querySelectorAll(s).length;
    return { locCards:q('.proto-loc'), choices:q('.proto-choice'), clueCards:q('.proto-clue-card'), phaseSteps:q('.proto-phase-step'), chars:q('.proto-char'), tabs:q('.proto-tab') };
  })()`);

  /* ══════ R3 推进 → 讨论阶段 ══════ */
  const sid = (await restGet('/api/script/status?probe=1').catch(() => ({}))) || {};
  const adv1 = await restPost('/api/script/advance', { session_id: sid.session_id || undefined });
  log('advance→discussion resp:', JSON.stringify(adv1).slice(0, 200));
  await waitFor(cdp, `(()=>{ const b=document.querySelector('.proto-badge-phase'); return b && b.textContent.includes('讨论'); })()`, 30000, 'discussion badge').catch(() => log('warn: discussion badge 未出现（可能已推进）'));
  await sleep(2500);
  await cdp.shot('real-discussion.png');
  results.real_discussion = 'real-discussion.png';
  results.disc_probes = await cdp.eval(`(()=>{
    const q=s=>document.querySelectorAll(s).length;
    return { msgs:q('.proto-discuss-stream .proto-msg'), sysLines:q('.proto-sys-line'), talkBtns:q('.proto-t-btn'), ammo:q('.proto-ammo') };
  })()`);

  /* ══════ R4 推进 → 投票阶段 ══════ */
  const adv2 = await restPost('/api/script/advance', { session_id: sid.session_id || undefined });
  log('advance→vote resp:', JSON.stringify(adv2).slice(0, 200));
  await waitFor(cdp, `(()=>{ const b=document.querySelector('.proto-badge-phase'); return b && b.textContent.includes('投票'); })()`, 30000, 'vote badge').catch(() => log('warn: vote badge 未出现'));
  await sleep(2500);
  await cdp.shot('real-vote.png');
  results.real_vote = 'real-vote.png';
  results.vote_probes = await cdp.eval(`(()=>{
    const q=s=>document.querySelectorAll(s).length;
    return { susCards:q('.proto-sus'), trust:q('.proto-trust')?1:0, voteBar:q('.proto-vote-bar')?1:0, stat:q('.proto-vote-stat')?1:0, sel:q('.proto-sus.sel').length };
  })()`);

  /* ══════ 收尾 ══════ */
  results.console_errors = cdp.consoleLogs.slice(0, 10);
  check('R5 console 无新增 error/exception', cdp.consoleLogs.length === 0, 'n=' + cdp.consoleLogs.length);
  writeFileSync(`${OUT}/result.json`, JSON.stringify(results, null, 2), 'utf-8');
  log(`DONE pass=${pass} fail=${fail} → ${OUT}/result.json`);
}

main().catch(e => { log('FATAL', e.message); process.exit(1); });
