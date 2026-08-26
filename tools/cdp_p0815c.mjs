/* cdp_p0815c.mjs — P-0815-C 修复验证：候选条稳定（不再提前出现/闪烁）+ 候选/输入可正常发送
 * 断言：
 *  A) 本轮第一句停驻、round_complete 未到（armed=false）→ 候选条必须隐藏（旧代码此处显示=闪烁源）
 *  B) 同轮消息全部入队（round_complete 到）且读到最后一句话停驻 → 候选条出现
 *  C) 候选条 6 采样稳定（不闪）
 *  D) 点击候选 → POST /api/send
 *  E) 输入框 React 原生 setter 输入 → 发送 → POST /api/send；发送中候选保持可见且 disabled
 *  F) console 0 错误
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9256;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4176/';
const OUT = 'D:/echoworld/tmp/p0815c';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11,19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
let pass = 0, fail = 0;
const check = (n, c, d='') => { if (c) { pass++; log('PASS ' + n + (d?(' :: '+d):'')); } else { fail++; log('FAIL ' + n + (d?(' :: '+d):'')); } };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
class CDP {
  constructor(ws){ this.ws=ws; this.id=0; this.pending=new Map(); this.consoleLogs=[]; this.netLogs=[]; }
  static async connect(url){ const ws=new WebSocket(url); await new Promise((res,rej)=>{ws.onopen=res;ws.onerror=rej;}); const c=new CDP(ws); ws.onmessage=(ev)=>{const m=JSON.parse(ev.data);if(m.id&&c.pending.has(m.id)){c.pending.get(m.id)(m);c.pending.delete(m.id);}}; return c; }
  send(method,params={}){ const id=++this.id; return new Promise((resolve,reject)=>{ const t=setTimeout(()=>{this.pending.delete(id);reject(new Error('timeout '+method));},60000); this.pending.set(id,(m)=>{clearTimeout(t);resolve(m);}); try{this.ws.send(JSON.stringify({id,method,params}));}catch(e){clearTimeout(t);reject(e);} }); }
  async eval(expr){ const r=await this.send('Runtime.evaluate',{expression:expr,returnByValue:true,awaitPromise:true}); if(r.result?.exceptionDetails) throw new Error('eval '+JSON.stringify(r.result.exceptionDetails.exception||'').slice(0,200)); return r.result?.result?.value; }
  async shot(f){ try{ const r=await this.send('Page.captureScreenshot',{format:'png'}); if(r.result?.data){ writeFileSync(`${OUT}/${f}`,Buffer.from(r.result.data,'base64')); log('[shot]',f);} }catch(e){} }
}
async function waitFor(cdp,expr,t=45000,label=''){ const t0=Date.now(); while(Date.now()-t0<t){ try{ const v=await cdp.eval(expr); if(v) return v; }catch{} await sleep(700); } throw new Error('timeout '+label); }
async function clickText(cdp,sel,text){ const r=await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`); if(String(r).startsWith('MISS')) throw new Error('click miss '+sel+' '+text); }

const PROBE = `(()=>{ const st=window.__galGeneralStore.getState(); return JSON.stringify({
  q:st.liveQueue.length, armed:st.livePlaybackArmed, sending:st.liveSending,
  cur:st.current?st.current.speakerId:null,
  typ:st.typing?('T'+(st.typing.done?'D':'P')+'/'+st.typing.chars+'/'+st.typing.full.length):null,
  btns:(()=>{ const c=document.querySelector('.galg-choices-slot'); return c?c.querySelectorAll('.gal-choice-btn').length:'NO_SLOT'; })(),
  btnDisabled:(()=>{ const c=document.querySelector('.galg-choices-slot'); const b=c?c.querySelector('.gal-choice-btn'):null; return b?b.disabled:null; })(),
  hint:(document.querySelector('.gal-input-hint')||{}).textContent?.trim().slice(0,30)||'',
}); })()`;

async function main(){
  let child;
  child = spawn(EDGE, ['--headless=new','--no-proxy-server','--disable-gpu','--no-sandbox',
    `--remote-debugging-port=${PORT}`,'--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0815c-'+Date.now(),'about:blank'], { stdio:'ignore', detached:true });
  for(let i=0;i<30;i++){ try{ const r=await fetch(`${BASE}/json/version`); if(r.ok) break; }catch{} await sleep(500); }
  const list=await (await fetch(`${BASE}/json/list`)).json();
  const cdp=await CDP.connect(list.find(t=>t.type==='page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable'); await cdp.send('Network.enable');
  cdp.ws.onmessage = (ev)=>{const m=JSON.parse(ev.data); if(m.id&&cdp.pending.has(m.id)){cdp.pending.get(m.id)(m);cdp.pending.delete(m.id);} else if(m.method==='Runtime.consoleAPICalled'){cdp.consoleLogs.push(m.params.type+': '+(m.params.args||[]).map(a=>a.value??a.description??'').join(' ').slice(0,200));} else if(m.method==='Runtime.exceptionThrown'){cdp.consoleLogs.push('EXCEPTION: '+(m.params.exceptionDetails?.exception?.description||m.params.exceptionDetails?.text||'').slice(0,200));} else if(m.method==='Network.responseReceived'&&m.params.response.url.includes('/api/')){cdp.netLogs.push(m.params.response.status+' '+m.params.response.url.replace('http://127.0.0.1:4176',''));}};
  await cdp.send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
  if(await cdp.eval('1+1')!==2) throw new Error('cdp sanity');

  await cdp.send('Page.navigate',{url:APP});
  await waitFor(cdp,`document.querySelectorAll('.app2-nav-btn').length>=5`,20000,'nav');
  await clickText(cdp,'.app2-nav-btn','剧本选择');
  await waitFor(cdp,`!!document.querySelector('.chip2')`,15000,'script page');
  await clickText(cdp,'.chip2','一般模式');
  await sleep(500);
  await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp,`document.body.textContent.includes('角色选择')`,20000,'roles');
  await sleep(400);
  await clickText(cdp,'.chip2','自由聊天模式');
  await sleep(200);
  await cdp.eval(`(()=>{ const cb=Array.from(document.querySelectorAll('input[type=checkbox]')).find(x=>x.checked===false); if(cb){cb.click();return 'OK';} return 'NO_CB'; })()`);
  await sleep(300);
  await cdp.eval(`(()=>{ const cards=Array.from(document.querySelectorAll('.role-chip')); const me=cards.find(c=>c.textContent&&c.textContent.includes('选择你的角色')); if(me){me.click();return 'OK';} return 'NO_PICKER'; })()`);
  await sleep(300);
  await cdp.eval(`(()=>{ const btns=Array.from(document.querySelectorAll('.modal-box .role-chip')); if(btns.length===0) return 'NO_MODAL'; btns[0].click(); return 'OK'; })()`);
  await sleep(400);
  await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('button')).find(x=>x.textContent&&x.textContent.includes('进入对局')); if(!b) return 'NO_BTN'; b.click(); return 'OK'; })()`);
  await waitFor(cdp,`!!document.querySelector('.galg-page')`,30000,'galg-page');
  await sleep(4000);

  // ── 阶段 A：第一句停驻且 armed=false（round_complete 未到）→ 候选必须隐藏 ──
  const tA0=Date.now(); let caughtParked=false;
  while(Date.now()-tA0<60000){
    const p = JSON.parse(await cdp.eval(PROBE));
    if (p.typ==='TD' && p.q>=1 && p.armed===false) { caughtParked=true; log('[A] 第一句停驻 armed=false', JSON.stringify(p)); break; }
    await sleep(800);
  }
  check('A1 捕获「首句停驻+未播完」状态', caughtParked);
  if (caughtParked) {
    const p = JSON.parse(await cdp.eval(PROBE));
    check('A2 首句停驻时候选条隐藏（修复闪烁源）', p.btns===0, 'btns='+p.btns+' q='+p.q+' armed='+p.armed);
  }
  await cdp.shot('A_parked_first.png');

  // ── 阶段 B：推进全部消息直到队列空或最后一句停驻（armed=true）→ 候选出现 ──
  const tB0=Date.now(); let shownState=null;
  while(Date.now()-tB0<120000){
    const p = JSON.parse(await cdp.eval(PROBE));
    if (p.btns>0) { shownState=p; break; }
    // 点击推进（直接调 store.advance —— b4 已证状态机正常；DOM 点击在 D 阶段单独验证）
    if (p.typ===null || String(p.typ).startsWith('TD')) {
      await cdp.eval(`(()=>{ window.__galGeneralStore.getState().advance(); return 'OK'; })()`);
    }
    await sleep(900);
  }
  log('[B] 候选出现状态', shownState?JSON.stringify(shownState):'NOT_SHOWN');
  check('B1 候选条最终出现', !!shownState, shownState?('q='+shownState.q+' armed='+shownState.armed+' btns='+shownState.btns):'');
  if (shownState) {
    check('B2 候选出现时本轮已播完（armed=true 或队列已空）', shownState.armed===true || shownState.q===0, 'armed='+shownState.armed+' q='+shownState.q);
  }
  await cdp.shot('B_choices_shown.png');

  // ── 阶段 C：6 采样稳定性 ──
  const samples=[];
  for(let i=0;i<6;i++){ samples.push(await cdp.eval(`(()=>{ const c=document.querySelector('.galg-choices-slot'); return c?JSON.stringify(Array.from(c.querySelectorAll('.gal-choice-btn')).map(b=>b.textContent.trim().slice(0,12))):'NO'; })()`)); await sleep(900); }
  log('[C] samples', samples.join(' ||| '));
  check('C1 候选条 6 采样完全稳定', new Set(samples).size===1, 'unique='+new Set(samples).size);

  // ── 阶段 D：点击候选 → /api/send ──
  const preSend = cdp.netLogs.filter(l=>l.includes('/api/send')).length;
  // 先单独验证 DOM 点击对话框可推进（修复前的疑似死区）
  const domClickTest = await cdp.eval(`(()=>{ const st=window.__galGeneralStore.getState(); const before=st.liveQueue.length;
    const d=document.querySelector('.gal-dialog'); if(!d) return 'NO_DIALOG';
    d.click(); const s2=window.__galGeneralStore.getState();
    return JSON.stringify({before, after:s2.liveQueue.length, cur:s2.current?s2.current.speakerId:null, log:s2.log.length}); })()`);
  log('[D0] dom-click-dialog', domClickTest);
  const dct = JSON.parse(domClickTest);
  check('D0 对话框 DOM 点击可推进消息', String(domClickTest)!=='NO_DIALOG' && dct.after < dct.before, domClickTest);
  // 推进到候选出现
  const tD0=Date.now();
  while(Date.now()-tD0<60000){
    const p = JSON.parse(await cdp.eval(PROBE));
    if (p.btns>0) break;
    if (p.typ===null || String(p.typ).startsWith('TD')) await cdp.eval(`(()=>{ window.__galGeneralStore.getState().advance(); return 'OK'; })()`);
    await sleep(900);
  }
  const clickR = await cdp.eval(`(()=>{ const b=document.querySelector('.galg-choices-slot .gal-choice-btn'); if(!b) return 'NO'; if(b.disabled) return 'DISABLED'; b.click(); return 'OK'; })()`);
  log('[D] click-candidate', clickR);
  check('D1 候选可点击', String(clickR)==='OK', String(clickR));
  await sleep(2500);
  const sendCount = cdp.netLogs.filter(l=>l.includes('/api/send')).length;
  check('D2 点击候选 → POST /api/send', sendCount>preSend, 'before='+preSend+' after='+sendCount);
  const inFlight = JSON.parse(await cdp.eval(PROBE));
  log('[D] in-flight', JSON.stringify(inFlight));
  check('D3 发送中候选保持可见且 disabled', inFlight.sending===true ? (inFlight.btns>0 && inFlight.btnDisabled===true) : true, 'sending='+inFlight.sending+' btns='+inFlight.btns+' disabled='+inFlight.btnDisabled);
  await cdp.shot('D_inflight.png');

  // ── 阶段 E：等本轮结束（发送完成+回复播完）→ 输入框发送 ──
  const tE0=Date.now();
  while(Date.now()-tE0<150000){
    const p = JSON.parse(await cdp.eval(PROBE));
    if (p.sending===false && (p.btns>0 || p.q===0)) { log('[E] 轮次落定', JSON.stringify(p)); break; }
    await sleep(3000);
  }
  // 推进到候选出现（如有停驻消息）
  const tE2=Date.now();
  while(Date.now()-tE2<60000){
    const p = JSON.parse(await cdp.eval(PROBE));
    if (p.btns>0) break;
    if (p.typ===null || String(p.typ).startsWith('TD')) await cdp.eval(`(()=>{ window.__galGeneralStore.getState().advance(); return 'OK'; })()`);
    await sleep(900);
  }
  // 输入框发送（React 原生 setter）
  await cdp.eval(`(()=>{ const inp=document.querySelector('.gal-input'); if(!inp) return 'NO_INPUT';
    const setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
    setter.call(inp,'修复验证消息 0815C'); inp.dispatchEvent(new Event('input',{bubbles:true})); return 'SET_OK'; })()`);
  await sleep(400);
  const btnState = await cdp.eval(`(()=>{ const btn=Array.from(document.querySelectorAll('.gal-send-btn')).find(b=>b.textContent.includes('发送')); return btn?JSON.stringify({disabled:btn.disabled}):'NO_BTN'; })()`);
  log('[E] send-btn', btnState);
  const preSend2 = cdp.netLogs.filter(l=>l.includes('/api/send')).length;
  const clickE = await cdp.eval(`(()=>{ const btn=Array.from(document.querySelectorAll('.gal-send-btn')).find(b=>b.textContent.includes('发送')); if(!btn) return 'NO'; if(btn.disabled) return 'DISABLED'; btn.click(); return 'OK'; })()`);
  log('[E] send-click', clickE);
  check('E1 输入发送按钮可点', String(clickE)==='OK', String(clickE));
  await sleep(2500);
  const sendCount2 = cdp.netLogs.filter(l=>l.includes('/api/send')).length;
  check('E2 输入发送 → POST /api/send', sendCount2>preSend2, 'before='+preSend2+' after='+sendCount2);
  const inputAfter = await cdp.eval(`(()=>{ const st=window.__galGeneralStore.getState(); return JSON.stringify({sending:st.liveSending, hint:(document.querySelector('.gal-input-hint')||{}).textContent?.trim().slice(0,30)||''}); })()`);
  log('[E] after-input-send', inputAfter);
  const ia = JSON.parse(inputAfter);
  check('E3 输入发送中提示「发送中」', ia.sending===true && ia.hint.includes('发送中'), ia.hint);

  // ── 阶段 F：console ──
  const errs = cdp.consoleLogs.filter(l=>l.startsWith('EXCEPTION')||l.startsWith('error'));
  check('F1 全程 console 0 错误', errs.length===0, errs.join(' | ').slice(0,200));
  log('RESULT pass='+pass+' fail='+fail);
  child.kill();
  process.exit(fail>0?1:0);
}
main().catch(e=>{ console.log('FATAL',e?.message); try{child?.kill();}catch{} process.exit(1); });
