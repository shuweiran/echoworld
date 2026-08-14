/* cdp_p0814e.mjs — P-0814-E 一般模式一问一答验证（CDP 端到端）
 * 前置：tools/static_proxy_p0814e.mjs 已在 4174 运行（新 dist + /api 透传 8000）。
 * 轮次完成权威信号 = 后端 /api/state：awaiting_playback=true && round>=N（一轮生成完等「播出完毕」信号）。
 * 场景 A（有玩家，一问一答）：round1 生成完播完 → playback_done 计数=0（播完停，不连说）→
 *   候选自动出现 → 玩家输入 → AI 回应 round2 → 播完再停（计数仍=0，round 停在 2）。
 * 场景 B（导演模式，无玩家）：round1 播完自动发 playback_done（计数>=1）→ round 自动>=2（无需输入）。
 * 证据：tmp/p0814e/*.png + progress.log；退出码 0=全 PASS。
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9247;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4174/';
const OUT = 'D:/roleplay-java/tmp/p0814e';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); } };
    return c;
  }
  send(method, params = {}) { const id = ++this.id; return new Promise((resolve, reject) => { const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 150000); this.pending.set(id, (m) => { clearTimeout(t); resolve(m); }); try { this.ws.send(JSON.stringify({ id, method, params })); } catch (e) { clearTimeout(t); reject(e); } }); }
  async eval(expr) { const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true }); if (r.result?.exceptionDetails) throw new Error('eval: ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 300)); return r.result?.result?.value; }
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); log('[shot]', f); } } catch (e) { } }
}
async function waitFor(cdp, expr, t = 60000, label = '') { const t0 = Date.now(); while (Date.now() - t0 < t) { try { const v = await cdp.eval(expr); if (v) return v; } catch { } await sleep(800); } throw new Error('timeout ' + label); }
async function clickText(cdp, sel, text) { const r = await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`); if (String(r).startsWith('MISS')) throw new Error('click miss ' + sel + ' ' + text); }

const stateExpr = `(()=>{ const s=window.__galGeneralStore?window.__galGeneralStore.getState():null; return s?JSON.stringify({q:s.liveQueue.length,cur:!!s.current,typ:!!s.typing,armed:s.livePlaybackArmed,liveName:s.livePlayerName,sid:s.liveSessionId,status:s.liveStatus}):'NOSTORE'; })()`;
const fetchCountExpr = `window.__pbCount||0`;
const backendRoundExpr = `(async()=>{ try{ const sid=window.__galGeneralStore?window.__galGeneralStore.getState().liveSessionId:''; const r=await fetch('/api/state'+(sid?'?session_id='+encodeURIComponent(sid):'')); const j=await r.json(); return JSON.stringify({round:j.round,awaiting:j.awaiting_playback,status:j.status}); }catch(e){ return 'ERR '+e.message; } })()`;
const choicesExpr = `(()=>{ const els=document.querySelectorAll('.gal-choices, .gal-live-choices'); return els.length>0 ? Array.from(els).map(e=>e.textContent.slice(0,40)).join(' | ') : ''; })()`;

/** 等后端轮次完成（round>=N 且 awaiting_playback=true，按 liveSessionId 定向） */
async function awaitRound(cdp, n, t = 300000) {
  await waitFor(cdp, `(async()=>{ try{ const sid=window.__galGeneralStore?window.__galGeneralStore.getState().liveSessionId:''; const r=await fetch('/api/state'+(sid?'?session_id='+encodeURIComponent(sid):'')); const j=await r.json(); return (j.round>=${n} && j.awaiting_playback===true)?1:0; }catch(e){ return 0; } })()`, t, 'round' + n + ' complete');
}

/** 等队列播完：模拟用户点击「▼ 点击继续」逐条读完（播完=用户读完最后一句话） */
async function clickThrough(cdp, maxMs = 150000) {
  const t0 = Date.now();
  while (Date.now() - t0 < maxMs) {
    const st = JSON.parse(await cdp.eval(stateExpr));
    if (!st.q && !st.cur && !st.typ) return true;
    await cdp.eval(`(()=>{ const d=document.querySelector('.gal-dialog'); if(d){d.click();return 'OK';} return 'NO'; })()`);
    await sleep(1200);
  }
  return false;
}

/** 进入 demo2 一般模式对局（scenario: 'player' | 'director'）→ 返回 true 到达 GalGeneralView */
async function enterGeneralGame(cdp, scenario) {
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `document.querySelectorAll('.app2-nav-btn').length>=5`, 20000, 'nav');
  await clickText(cdp, '.app2-nav-btn', '剧本选择');
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 15000, 'script page');
  await clickText(cdp, '.chip2', '一般模式');
  await sleep(500);
  await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp, `document.body.textContent.includes('角色选择')`, 15000, 'roles');
  await sleep(400);
  if (scenario === 'player') {
    // 选玩家角色：点「选择你的角色」→ 在弹窗内（.modal-mask .role-chip）选第一个
    await cdp.eval(`(()=>{ const e=document.querySelector('.role-chip'); if(e){e.click();return 'OK';} return 'NO'; })()`);
    await sleep(400);
    const picked = await cdp.eval(`(()=>{ const b=document.querySelector('.modal-mask .role-chip'); if(b){b.click();return 'OK';} return 'NO'; })()`);
    if (picked !== 'OK') throw new Error('player pick fail');
    await sleep(400);
  } else {
    // 导演模式：不选玩家角色 + 取消 withPlayer 勾选（仅一般模式的「带玩家」开关）
    await cdp.eval(`(()=>{ const cb=document.querySelector('input[type="checkbox"]'); if(cb){ const setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'checked').set; setter.call(cb,false); cb.dispatchEvent(new Event('change',{bubbles:true})); return 'OK'; } return 'NO_CB'; })()`);
    await sleep(300);
  }
  await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.roles-footer button')).find(x=>x.textContent.includes('进入对局')); if(b){b.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp, `!!document.querySelector('.galg-page')`, 150000, 'galg');
  await waitFor(cdp, `window.__galGeneralStore&&window.__galGeneralStore.getState().liveMode`, 30000, 'live');
  return true;
}

async function main() {
  const child = spawn(EDGE, ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0814e-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 30; i++) { try { const r = await fetch(`${BASE}/json/version`); if (r.ok) break; } catch { } await sleep(500); }
  const list = await (await fetch(`${BASE}/json/list`)).json();
  let page = list.find(t => t.type === 'page');
  const cdp = await CDP.connect(page.webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Emulation.setVisibleSize', { width: 1440, height: 900 });
  if (await cdp.eval('1+1') !== 2) throw new Error('cdp sanity');
  // 页面加载前注入 playback_done 计数（证明「有玩家不自动发信号」）
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: `
    window.__pbCount = 0;
    window.__pbTimes = [];
    const __origFetch = window.fetch.bind(window);
    window.fetch = (...args) => {
      const u = String(args[0]||'');
      if (u.includes('playback_done')) { window.__pbCount++; window.__pbTimes.push(Date.now()); }
      return __origFetch(...args);
    };
  ` });

  // ════ 场景 A：有玩家 —— 一问一答 ════
  log('=== 场景 A（有玩家）开始 ===');
  if (!process.env.SCENARIO || process.env.SCENARIO === 'A') {
  await enterGeneralGame(cdp, 'player');
  await waitFor(cdp, `window.__galGeneralStore.getState().livePlayerName.trim().length>0`, 15000, 'playerName set');
  const sidA = await cdp.eval(`window.__galGeneralStore.getState().liveSessionId`);
  log('[A session]', sidA, 'pbCount=', await cdp.eval(fetchCountExpr));
  await cdp.shot('A01_mounted.png');
  // round1 生成完（后端 awaiting=true）→ 等队列播完（带状态日志定位卡点）
  await awaitRound(cdp, 1, 360000);
  {
    const ok = await clickThrough(cdp, 150000);
    if (!ok) throw new Error('A round1 drained timeout');
  }
  const stA1 = JSON.parse(await cdp.eval(stateExpr));
  check('A 玩家身份已设置', !!stA1.liveName, 'liveName=' + stA1.liveName);
  log('[A round1 drained]', JSON.stringify(stA1));
  // 停 6s（> settle 300ms + minGap 800ms）：有玩家 → 不应自动发 playback_done
  await sleep(6000);
  const cA1 = Number(await cdp.eval(fetchCountExpr));
  check('A 播完停：未自动发 playback_done（AI 不连说）', cA1 === 0, 'pbCount=' + cA1);
  const backA1 = JSON.parse(await cdp.eval(backendRoundExpr));
  check('A 播完停：后端轮次停在 1（等待玩家输入）', Number(backA1.round) === 1, 'round=' + backA1.round + ' awaiting=' + backA1.awaiting);
  // 候选选项在 AI 播完时自动出现（无需点击）
  const choicesA = await cdp.eval(choicesExpr);
  check('A 候选选项自动出现', choicesA.length > 0, choicesA.slice(0, 60));
  await cdp.shot('A02_stopped_choices.png');
  // 玩家输入 → AI 回应一轮
  const typed = await cdp.eval(`(()=>{ const inp=document.querySelector('.gal-input'); if(!inp) return 'NO_INPUT'; const setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set; setter.call(inp,'你好，我想听听大家对这个话题的看法。'); inp.dispatchEvent(new Event('input',{bubbles:true})); return 'OK'; })()`);
  check('A 输入框存在（有玩家）', typed === 'OK', typed);
  await sleep(400);
  await cdp.eval(`(()=>{ const b=document.querySelector('.gal-send-btn'); if(b){b.click();return 'OK';} return 'NO'; })()`);
  // round2 生成完（输入驱动）→ 播完 → 再停
  await awaitRound(cdp, 2, 360000);
  {
    const ok = await clickThrough(cdp, 150000);
    if (!ok) throw new Error('A round2 drained timeout');
  }
  await sleep(6000);
  const cA2 = Number(await cdp.eval(fetchCountExpr));
  check('A 输入后回应一轮即停（仍未自动发信号）', cA2 === 0, 'pbCount=' + cA2);
  const backA2 = JSON.parse(await cdp.eval(backendRoundExpr));
  check('A 回应一轮后停（轮次=2 不连说）', Number(backA2.round) === 2, 'round=' + backA2.round);
  await cdp.shot('A03_after_input.png');
  log('=== 场景 A 结束 ===');
  }

  // ════ 场景 B：导演模式（无玩家）—— 播完自动推进 ════
  log('=== 场景 B（导演模式）开始 ===');
  if (process.env.SCENARIO && process.env.SCENARIO !== 'B') throw new Error('SCENARIO=' + process.env.SCENARIO + ' 不支持');
  if (!process.env.SCENARIO || process.env.SCENARIO === 'B') {
    await cdp.send('Page.navigate', { url: 'about:blank' });
  await sleep(800);
  await enterGeneralGame(cdp, 'director');
  await waitFor(cdp, `window.__galGeneralStore.getState().livePlayerName.trim().length===0`, 15000, 'no player');
  const sidB = await cdp.eval(`window.__galGeneralStore.getState().liveSessionId`);
  log('[B session]', sidB, 'pbCount=', await cdp.eval(fetchCountExpr));
  await cdp.shot('B01_mounted.png');
  await awaitRound(cdp, 1, 360000);
  {
    const ok = await clickThrough(cdp, 150000);
    if (!ok) throw new Error('B round1 drained timeout');
  }
  const stB1 = JSON.parse(await cdp.eval(stateExpr));
  check('B 无玩家（导演模式）', !stB1.liveName, 'liveName=' + JSON.stringify(stB1.liveName));
  log('[B round1 drained]', JSON.stringify(stB1));
  // 播完自动推进：playback_done 自动发出 → 后端轮次自动推进到 >=2（无需输入）；
  // 期间持续模拟读者点击（播完=读完，读完后自动发下一轮信号）
  await waitFor(cdp, `window.__pbCount>=1`, 60000, 'B auto playback_done');
  const cB1 = Number(await cdp.eval(fetchCountExpr));
  check('B 播完自动发 playback_done', cB1 >= 1, 'pbCount=' + cB1);
  {
    const t0 = Date.now();
    let advanced = false;
    while (Date.now() - t0 < 300000) {
      await clickThrough(cdp, 20000); // 读完当前轮（含自动发信号后的新轮）
      const bb = JSON.parse(await cdp.eval(backendRoundExpr));
      if (Number(bb.round) >= 2) { advanced = true; break; }
      await sleep(1500);
    }
    if (!advanced) throw new Error('B round>=2 timeout');
  }
  const backB = JSON.parse(await cdp.eval(backendRoundExpr));
  check('B 导演模式自动推进（轮次>=2 无需输入）', Number(backB.round) >= 2, 'round=' + backB.round + ' awaiting=' + backB.awaiting);
    await cdp.shot('B02_auto_advanced.png');
    log('=== 场景 B 结束 ===');
  }

  log('RESULT pass=' + pass + ' fail=' + fail);
  child.kill();
  process.exit(fail > 0 ? 1 : 0);
}
main().catch(e => { console.log('FATAL', e?.message); try { child?.kill(); } catch { } process.exit(1); });
