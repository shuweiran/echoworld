/* cdp_p0814g.mjs — P-0814-G 消息显示顺序修复 CDP 端到端（真实后端 8000 + 真实 LLM）
 * 前置：static_proxy 已在 4174 运行（serve 新 dist + /api 透传 8000）。
 * 场景 A（有玩家主控视图）：R1 AI 播完停 → 玩家输入 → R2 AI 回应播完停。
 * 断言（修复核心）：
 *  ① 玩家输入后 log/队列中【无】玩家消息以 AI 样式（me）出现在 AI 回复之后（修复前必现）；
 *  ② AI 消息不重复（每轮恰 1 条，无双路径双播）；
 *  ③ 历史抽屉默认正序：玩家消息标注「玩家」且位于同轮 AI 回复之前；
 *  ④ P-0814-E 门控不回退：有玩家 R2 播完 pbCount 仍 =0（不自动连说）、轮次停 2。
 * 证据：tmp/p0814g/*.png + progress.log；退出码 0=全 PASS。
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9249;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4174/';
const OUT = 'D:/roleplay-java/tmp/p0814g';
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

const stateExpr = `(()=>{ const s=window.__galGeneralStore?window.__galGeneralStore.getState():null; return s?JSON.stringify({q:s.liveQueue.length,cur:!!s.current,typ:!!s.typing,armed:s.livePlaybackArmed,liveName:s.livePlayerName,sid:s.liveSessionId}):'NOSTORE'; })()`;
const backendRoundExpr = `(async()=>{ try{ const sid=window.__galGeneralStore?window.__galGeneralStore.getState().liveSessionId:''; const r=await fetch('/api/state'+(sid?'?session_id='+encodeURIComponent(sid):'')); const j=await r.json(); return JSON.stringify({round:j.round,awaiting:j.awaiting_playback,status:j.status}); }catch(e){ return 'ERR '+e.message; } })()`;
const storeDumpExpr = `(()=>{ const s=window.__galGeneralStore?window.__galGeneralStore.getState():null; if(!s) return 'NOSTORE'; return JSON.stringify({q:s.liveQueue.map(m=>({kind:m.kind,sid:m.speakerId,text:m.text,ts:m.ts})), log:s.log.map(l=>({isP:!!l.isPlayer,sid:l.speakerId,text:l.text}))}); })()`;

async function awaitRound(cdp, n, t = 360000) {
  await waitFor(cdp, `(async()=>{ try{ const sid=window.__galGeneralStore?window.__galGeneralStore.getState().liveSessionId:''; const r=await fetch('/api/state'+(sid?'?session_id='+encodeURIComponent(sid):'')); const j=await r.json(); return (j.round>=${n} && j.awaiting_playback===true)?1:0; }catch(e){ return 0; } })()`, t, 'round' + n + ' complete');
}
async function clickThrough(cdp, maxMs = 180000) {
  const t0 = Date.now();
  while (Date.now() - t0 < maxMs) {
    const st = JSON.parse(await cdp.eval(stateExpr));
    if (!st.q && !st.cur && !st.typ) return true;
    await cdp.eval(`(()=>{ const d=document.querySelector('.gal-dialog'); if(d){d.click();return 'OK';} return 'NO'; })()`);
    await sleep(1200);
  }
  return false;
}

async function enterGeneralGame(cdp) {
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `document.querySelectorAll('.app2-nav-btn').length>=5`, 20000, 'nav');
  await clickText(cdp, '.app2-nav-btn', '剧本选择');
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 15000, 'script page');
  await clickText(cdp, '.chip2', '一般模式');
  await sleep(500);
  await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp, `document.body.textContent.includes('角色选择')`, 15000, 'roles');
  await sleep(400);
  // 选玩家角色（弹窗内第一个）
  await cdp.eval(`(()=>{ const e=document.querySelector('.role-chip'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await sleep(400);
  const picked = await cdp.eval(`(()=>{ const b=document.querySelector('.modal-mask .role-chip'); if(b){b.click();return 'OK';} return 'NO'; })()`);
  if (picked !== 'OK') throw new Error('player pick fail');
  await sleep(400);
  await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.roles-footer button')).find(x=>x.textContent.includes('进入对局')); if(b){b.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp, `!!document.querySelector('.galg-page')`, 150000, 'galg');
  await waitFor(cdp, `window.__galGeneralStore&&window.__galGeneralStore.getState().liveMode`, 30000, 'live');
  return true;
}

async function main() {
  const child = spawn(EDGE, ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0814g-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 30; i++) { try { const r = await fetch(`${BASE}/json/version`); if (r.ok) break; } catch { } await sleep(500); }
  const list = await (await fetch(`${BASE}/json/list`)).json();
  let page = list.find(t => t.type === 'page');
  const cdp = await CDP.connect(page.webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Emulation.setVisibleSize', { width: 1440, height: 900 });
  if (await cdp.eval('1+1') !== 2) throw new Error('cdp sanity');
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: `
    window.__pbCount = 0;
    const __origFetch = window.fetch.bind(window);
    window.fetch = (...args) => {
      const u = String(args[0]||'');
      if (u.includes('playback_done')) { window.__pbCount++; }
      return __origFetch(...args);
    };
  ` });

  log('=== 场景 A（有玩家 · 一问一答 · 顺序修复）开始 ===');
  await enterGeneralGame(cdp);
  await waitFor(cdp, `window.__galGeneralStore.getState().livePlayerName.trim().length>0`, 15000, 'playerName set');
  await cdp.shot('A01_mounted.png');
  // R1：AI 播完停
  await awaitRound(cdp, 1, 420000);
  { const ok = await clickThrough(cdp, 180000); if (!ok) throw new Error('A round1 drained timeout'); }
  await sleep(6000);
  const cA1 = Number(await cdp.eval(`window.__pbCount`));
  check('A1 R1 播完停（有玩家不自动连说，P-0814-E 门控未回退）', cA1 === 0, 'pbCount=' + cA1);
  const dump1 = JSON.parse(await cdp.eval(storeDumpExpr));
  check('A2 R1 log 仅 AI 消息（无玩家消息混入）', dump1.log.length >= 1 && dump1.log.every(l => !l.isP), JSON.stringify(dump1.log.slice(-3)));
  check('A3 R1 AI 消息不重复（无双播）', new Set(dump1.log.map(l => l.sid + '\u0000' + l.text)).size === dump1.log.length, 'log=' + dump1.log.length);
  // 玩家输入 → R2
  await cdp.eval(`(()=>{ const inp=document.querySelector('.gal-input'); if(!inp) return 'NO_INPUT'; const setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set; setter.call(inp,'你好，我想听听你的看法。'); inp.dispatchEvent(new Event('input',{bubbles:true})); return 'OK'; })()`);
  await sleep(400);
  await cdp.eval(`(()=>{ const b=document.querySelector('.gal-send-btn'); if(b){b.click();return 'OK';} return 'NO'; })()`);
  await awaitRound(cdp, 2, 420000);
  { const ok = await clickThrough(cdp, 180000); if (!ok) throw new Error('A round2 drained timeout'); }
  await sleep(6000);
  const dump2 = JSON.parse(await cdp.eval(storeDumpExpr));
  const backA2 = JSON.parse(await cdp.eval(backendRoundExpr));
  const cA2 = Number(await cdp.eval(`window.__pbCount`));
  // 核心断言：玩家输入文本绝不以 AI 样式出现在 log/队列（修复前 me 会排在 AI 回复后）
  const playerTextLeak = dump2.log.some(l => !l.isP && l.text.includes('我想听听你的看法'));
  const playerInQueue = dump2.q.some(m => m.sid === 'me' && m.text.includes('我想听听你的看法'));
  check('A4 玩家输入不泄漏为 AI 消息（log 无 me 样式）', !playerTextLeak, JSON.stringify(dump2.log.slice(-3)));
  check('A5 队列无 me 残留（玩家消息绝不在 AI 回复之后）', !playerInQueue, JSON.stringify(dump2.q));
  check('A6 R2 AI 消息不重复（双路径去重生效）', new Set(dump2.log.map(l => l.sid + '\u0000' + l.text)).size === dump2.log.length, 'log=' + dump2.log.length);
  check('A7 R2 播完停（轮次停 2 不连说）', Number(backA2.round) === 2 && cA2 === 0, 'round=' + backA2.round + ' pbCount=' + cA2);
  await cdp.shot('A02_after_r2.png');

  // 历史抽屉：默认正序 + 玩家消息标注「玩家」且在同轮 AI 回复之前
  await clickText(cdp, '.galg-top-btn', '历史记录');
  await waitFor(cdp, `document.querySelectorAll('.galg-msg-row').length>=2`, 20000, 'drawer rows');
  await sleep(1500);
  const drawerExpr = `(()=>{ const rows=Array.from(document.querySelectorAll('.galg-msg-row')); return JSON.stringify(rows.map(r=>({type:r.querySelector('.galg-msg-type')?.textContent||'', name:r.querySelector('.galg-msg-name')?.textContent||'', round:(r.querySelector('.galg-msg-round')?.textContent||'').replace('R',''), text:(r.querySelector('.galg-msg-content')?.textContent||'').slice(0,20)}))); })()`;
  const rows = JSON.parse(await cdp.eval(drawerExpr));
  log('[drawer rows]', JSON.stringify(rows.slice(0, 8)));
  const firstRound = rows.length ? Number(rows[0].round || 0) : 0;
  const lastRound = rows.length ? Number(rows[rows.length - 1].round || 0) : 0;
  check('A8 历史抽屉默认正序（R' + firstRound + ' → R' + lastRound + '）', rows.length >= 2 && firstRound <= lastRound, 'firstR=' + firstRound + ' lastR=' + lastRound);
  // 玩家消息（输入文本）标注「玩家」且在 AI 回复之前
  const playerRowIdx = rows.findIndex(r => r.text.includes('我想听听你的看法'));
  check('A9 玩家消息标注「玩家」', playerRowIdx >= 0 && rows[playerRowIdx].type === '玩家', playerRowIdx >= 0 ? rows[playerRowIdx].type : 'not found');
  if (playerRowIdx >= 0) {
    // 同轮内：玩家消息必须在其所在轮 AI 回复之前（正序下 index 更小）
    const sameRoundAi = rows.findIndex(r => r.round === rows[playerRowIdx].round && r.type === 'AI' && r.name !== 'me');
    check('A10 玩家消息位于同轮 AI 回复之前（玩家在前）', sameRoundAi === -1 || sameRoundAi > playerRowIdx, 'playerIdx=' + playerRowIdx + ' aiIdx=' + sameRoundAi);
  }
  await cdp.shot('A03_drawer.png');
  log('=== 场景 A 结束 ===');

  log('RESULT pass=' + pass + ' fail=' + fail);
  child.kill();
  process.exit(fail > 0 ? 1 : 0);
}
main().catch(e => { console.log('FATAL', e?.message); try { child?.kill(); } catch { } process.exit(1); });
