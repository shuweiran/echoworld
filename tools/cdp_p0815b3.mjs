/* cdp_p0815b3.mjs — P-0815-B(→C)：多轮实测：推进消息 → 候选出现 → 点击候选发言 → 回复播放 → 下一轮
 * 目标：复现「候选/输入无响应 + 选项条刷新」；监测 liveSending/queue/typing 状态演化
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9254;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4176/';
const OUT = 'D:/echoworld/tmp/p0815b3';
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

const probeExpr = `(()=>{ const st=window.__galGeneralStore.getState(); return JSON.stringify({
  sending:st.liveSending, err:st.liveSendError, q:st.liveQueue.length,
  cur:st.current?st.current.speakerId+'|'+(st.current.text||'').slice(0,12):null,
  typ:st.typing?('T'+(st.typing.done?'D':'P')+'/'+st.typing.chars+'/'+st.typing.full.length):null,
  sug:st.liveSuggestions.length, log:st.log.length, armed:st.livePlaybackArmed,
}); })()`;

async function main(){
  let child;
  child = spawn(EDGE, ['--headless=new','--no-proxy-server','--disable-gpu','--no-sandbox',
    `--remote-debugging-port=${PORT}`,'--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0815b3-'+Date.now(),'about:blank'], { stdio:'ignore', detached:true });
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
  await sleep(5000);
  log('[t0]', await cdp.eval(probeExpr));

  // ── 阶段 A：推进队列直到候选出现（点击对话框推进；最多 20 次） ──
  let advanced = 0;
  const tA0 = Date.now();
  while (Date.now() - tA0 < 90000) {
    const vis = await cdp.eval(`(()=>{ const c=document.querySelector('.galg-choices-slot'); const b=c?c.querySelectorAll('.gal-choice-btn').length:0; return b>0; })()`);
    if (vis) break;
    const prog = JSON.parse(await cdp.eval(probeExpr));
    if (prog.q===0 && prog.typ===null && prog.cur===null) { log('[drain] 队列已空但候选未见', JSON.stringify(prog)); break; }
    // 点击对话框推进（当前消息完成态 → 下一条）
    await cdp.eval(`(()=>{ const d=document.querySelector('.gal-dialog'); if(d){d.click();return 'OK';} return 'NO'; })()`);
    advanced++;
    await sleep(1200);
  }
  log('[phaseA] advanced='+advanced+' probes=', await cdp.eval(probeExpr));
  await cdp.shot('A_after_advance.png');
  const choicesA = await cdp.eval(`(()=>{ const c=document.querySelector('.galg-choices-slot'); if(!c) return 'NO_SLOT'; return JSON.stringify({btns:c.querySelectorAll('.gal-choice-btn').length, texts:Array.from(c.querySelectorAll('.gal-choice-btn')).map(b=>b.textContent.trim().slice(0,14))}); })()`);
  log('[choicesA]', choicesA);
  check('候选条出现', !String(choicesA).startsWith('NO_SLOT') && JSON.parse(choicesA).btns>0, choicesA.slice(0,120));
  await cdp.shot('A_choices.png');
  // 采样 5 次看是否闪
  const samples=[];
  for(let i=0;i<5;i++){ samples.push(await cdp.eval(`(()=>{ const c=document.querySelector('.galg-choices-slot'); return c?String(c.querySelectorAll('.gal-choice-btn').length):'NO'; })()`)); await sleep(900); }
  log('[samples]', samples.join(','));
  check('候选条稳定（5 采样一致）', new Set(samples).size===1, samples.join(','));

  // ── 阶段 B：点击候选 #1 → 应发 /api/send → AI 回复 → round_complete ──
  const preSend = cdp.netLogs.filter(l=>l.includes('/api/send')).length;
  const clickR = await cdp.eval(`(()=>{ const b=document.querySelector('.galg-choices-slot .gal-choice-btn'); if(!b) return 'NO'; if(b.disabled) return 'DISABLED'; b.click(); return 'OK'; })()`);
  log('[click-candidate]', clickR);
  check('候选可点击', String(clickR)==='OK', String(clickR));
  await sleep(2500);
  log('[t+2.5s]', await cdp.eval(probeExpr));
  await sleep(2500);
  log('[t+5s]', await cdp.eval(probeExpr));
  const sendCount = cdp.netLogs.filter(l=>l.includes('/api/send')).length;
  check('点击候选 → 发起 /api/send', sendCount>preSend, 'before='+preSend+' after='+sendCount);
  // 等 AI 回复入队并播完（最多 90s）
  let replied=false;
  const tB0=Date.now();
  while(Date.now()-tB0<90000){
    const p = JSON.parse(await cdp.eval(probeExpr));
    if (p.log>0 && p.cur===null && p.q===0 && p.sending===false) { replied=true; log('[round-done]', JSON.stringify(p)); break; }
    await sleep(2000);
  }
  check('AI 回复轮完成（log 增长/队列清空）', replied);
  await cdp.shot('B_after_round.png');

  // ── 阶段 C：下一轮玩家回合 → 候选再次出现 → 点击候选 #2 ──
  const vis2 = await cdp.eval(`(()=>{ const c=document.querySelector('.galg-choices-slot'); const b=c?c.querySelectorAll('.gal-choice-btn').length:0; return b>0; })()`);
  log('[phaseC] 候选再次可见=', vis2);
  if (vis2) {
    const preSend2 = cdp.netLogs.filter(l=>l.includes('/api/send')).length;
    const clickR2 = await cdp.eval(`(()=>{ const b=document.querySelector('.galg-choices-slot .gal-choice-btn'); if(!b) return 'NO'; if(b.disabled) return 'DISABLED'; b.click(); return 'OK'; })()`);
    log('[click-candidate2]', clickR2);
    await sleep(2000);
    const sendCount2 = cdp.netLogs.filter(l=>l.includes('/api/send')).length;
    check('第二轮点击候选 → /api/send', sendCount2>preSend2, 'before='+preSend2+' after='+sendCount2);
    // ── 阶段 D：in-flight 竞态 —— liveSending=true 时再点一次候选（应被 disabled 挡住） ──
    await sleep(1500);
    const race = await cdp.eval(`(()=>{ const st=window.__galGeneralStore.getState(); const b=document.querySelector('.galg-choices-slot .gal-choice-btn'); return JSON.stringify({sending:st.liveSending, btnDisabled:b?b.disabled:'NO_BTN', btnVisible:!!b}); })()`);
    log('[race-probe]', race);
    const raceObj = JSON.parse(race);
    check('in-flight 期间候选按钮 disabled（防重发）', raceObj.sending===true ? raceObj.btnDisabled===true : true, race);
  } else {
    log('[phaseC] 候选未再次出现（跳过 C/D）');
  }
  // 等 2 轮全部落定
  const tE0=Date.now();
  while(Date.now()-tE0<120000){
    const p = JSON.parse(await cdp.eval(probeExpr));
    if (p.q===0 && p.sending===false && p.typ===null) { log('[settle]', JSON.stringify(p)); break; }
    await sleep(3000);
  }
  log('[final]', await cdp.eval(probeExpr));
  await cdp.shot('Z_final.png');
  check('全程 console 0 错误', cdp.consoleLogs.filter(l=>l.startsWith('EXCEPTION')||l.startsWith('error')).length===0, (cdp.consoleLogs.filter(l=>l.startsWith('EXCEPTION')||l.startsWith('error'))).join(' | ').slice(0,200));
  log('RESULT pass='+pass+' fail='+fail);
  child.kill();
  process.exit(fail>0?1:0);
}
main().catch(e=>{ console.log('FATAL',e?.message); try{child?.kill();}catch{} process.exit(1); });
