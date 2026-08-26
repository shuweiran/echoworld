/* cdp_p0815b2.mjs — P-0815-B：GalGeneralView store 状态探针 + 真实输入发送测试
 * 复用 UI 流程起局（一般模式 自由聊天 + 带玩家），挂载后：
 *  A) 探针 __galGeneralStore 关键字段（找 isPlayerTurn 阻塞点）
 *  B) 用 React 原生 setter 正确输入 → 点击发送 → 观察 /api/send 是否发出、AI 是否回复
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9253;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4176/';
const OUT = 'D:/echoworld/tmp/p0815b2';
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

async function main(){
  let child;
  child = spawn(EDGE, ['--headless=new','--no-proxy-server','--disable-gpu','--no-sandbox',
    `--remote-debugging-port=${PORT}`,'--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0815b2-'+Date.now(),'about:blank'], { stdio:'ignore', detached:true });
  for(let i=0;i<30;i++){ try{ const r=await fetch(`${BASE}/json/version`); if(r.ok) break; }catch{} await sleep(500); }
  const list=await (await fetch(`${BASE}/json/list`)).json();
  const cdp=await CDP.connect(list.find(t=>t.type==='page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable'); await cdp.send('Network.enable');
  cdp.ws.onmessage = (ev)=>{const m=JSON.parse(ev.data); if(m.id&&cdp.pending.has(m.id)){cdp.pending.get(m.id)(m);cdp.pending.delete(m.id);} else if(m.method==='Runtime.consoleAPICalled'){cdp.consoleLogs.push(m.params.type+': '+(m.params.args||[]).map(a=>a.value??a.description??'').join(' ').slice(0,300));} else if(m.method==='Runtime.exceptionThrown'){cdp.consoleLogs.push('EXCEPTION: '+(m.params.exceptionDetails?.exception?.description||m.params.exceptionDetails?.text||'').slice(0,300));} else if(m.method==='Network.responseReceived'&&m.params.response.url.includes('/api/')){cdp.netLogs.push(m.params.response.status+' '+m.params.response.url.replace('http://127.0.0.1:4176',''));}};
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
  const start = await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('button')).find(x=>x.textContent&&x.textContent.includes('进入对局')); if(!b) return 'NO_BTN'; b.click(); return 'OK'; })()`);
  log('[start]', start);
  await waitFor(cdp,`!!document.querySelector('.galg-page')`,30000,'galg-page');
  await sleep(8000);
  log('=== 挂载 8s 后 store 探针 ===');
  const probe1 = await cdp.eval(`(()=>{ const st=window.__galGeneralStore.getState(); return JSON.stringify({
    liveMode:st.liveMode, liveSessionId:st.liveSessionId, liveStatus:st.liveStatus,
    liveGameType:st.liveGameType, livePhase:st.livePhase, liveGeneralMode:st.liveGeneralMode,
    livePlayerName:st.livePlayerName, hidePlayerBubbles:st.hidePlayerBubbles,
    liveSending:st.liveSending, liveSendError:st.liveSendError,
    queueLen:st.liveQueue.length, current:(st.current&&st.current.speakerId+':'+(st.current.text||'').slice(0,16))||null,
    typing:st.typing?JSON.stringify({speakerId:st.typing.speakerId,chars:st.typing.chars,fullLen:st.typing.full.length,done:st.typing.done}):null,
    liveSuggestions:st.liveSuggestions, logLen:st.log.length,
    liveSayOverride:!!st.liveSayOverride, choiceNode:!!st.choiceNode,
  }); })()`);
  log('[probe1]', probe1);
  const p1 = JSON.parse(probe1);
  check('liveMode=true', p1.liveMode===true);
  check('liveSessionId 非空', !!p1.liveSessionId, p1.liveSessionId);
  check('liveGameType=general', p1.liveGameType==='general', p1.liveGameType);
  check('liveStatus=open', p1.liveStatus==='open', p1.liveStatus);
  check('liveSending=false（无卡死）', p1.liveSending===false);
  check('候选区出现条件 isPlayerTurn 应为 true（此时无打字/队列空）', (p1.typing===null||JSON.parse(p1.typing).done===true) && p1.queueLen<=1, 'typing='+p1.typing+' queueLen='+p1.queueLen);

  // 采样候选条 6 次
  const samples = [];
  for (let i=0;i<6;i++){
    const snap = await cdp.eval(`(()=>{ const c=document.querySelector('.galg-choices-slot'); if(!c) return 'NO_SLOT'; return JSON.stringify({inner:c.innerHTML.length, buttons:c.querySelectorAll('.gal-choice-btn').length, texts:Array.from(c.querySelectorAll('.gal-choice-btn')).map(b=>b.textContent.trim().slice(0,16))}); })()`);
    samples.push(snap);
    await sleep(800);
  }
  log('[samples]', samples.join(' ||| '));
  check('候选条内容稳定', new Set(samples).size<=2, 'unique='+new Set(samples).size);

  // 用 React 原生 setter 输入 → 点发送
  const setRes = await cdp.eval(`(()=>{ const inp=document.querySelector('.gal-input'); if(!inp) return 'NO_INPUT';
    const setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;
    setter.call(inp,'测试消息 0815B2'); inp.dispatchEvent(new Event('input',{bubbles:true}));
    return 'SET_OK'; })()`);
  log('[input-set]', setRes);
  await sleep(400);
  const btnState = await cdp.eval(`(()=>{ const btn=Array.from(document.querySelectorAll('.gal-send-btn')).find(b=>b.textContent.includes('发送')); return btn?JSON.stringify({disabled:btn.disabled, text:btn.textContent.trim()}):'NO_BTN'; })()`);
  log('[send-btn]', btnState);
  const clickRes = await cdp.eval(`(()=>{ const btn=Array.from(document.querySelectorAll('.gal-send-btn')).find(b=>b.textContent.includes('发送')); if(!btn) return 'NO_BTN'; if(btn.disabled) return 'DISABLED'; btn.click(); return 'CLICKED'; })()`);
  log('[send-click]', clickRes);
  check('发送按钮可点（非 disabled）', String(clickRes)==='CLICKED', String(clickRes));
  await sleep(4000);
  const afterSend = await cdp.eval(`(()=>{ const st=window.__galGeneralStore.getState(); const inp=document.querySelector('.gal-input'); return JSON.stringify({inputVal:inp?inp.value:'NO', liveSending:st.liveSending, liveSendError:st.liveSendError, queueLen:st.liveQueue.length, lastSent:st.liveLastSent>0}); })()`);
  log('[after-send-4s]', afterSend);
  await cdp.shot('05_after_send.png');
  await sleep(20000);
  const finalState = await cdp.eval(`(()=>{ const st=window.__galGeneralStore.getState(); return JSON.stringify({liveSending:st.liveSending, liveSendError:st.liveSendError, queueLen:st.liveQueue.length, logLen:st.log.length, current:st.current?st.current.speakerId:null, liveSuggestions:st.liveSuggestions}); })()`);
  log('[final-state]', finalState);
  const apiCalls = cdp.netLogs.filter(l=>l.includes('/api/send')).join(' | ');
  log('[api-send-calls]', apiCalls || 'NONE');
  check('曾发起 POST /api/send', apiCalls.includes('/api/send'), apiCalls.slice(0,120));
  check('无 liveSendError', !JSON.parse(finalState).liveSendError, JSON.parse(finalState).liveSendError||'');
  check('最终 console 0 错误', cdp.consoleLogs.filter(l=>l.startsWith('EXCEPTION')||l.startsWith('error')).length===0, (cdp.consoleLogs.filter(l=>l.startsWith('EXCEPTION')||l.startsWith('error'))).join(' | ').slice(0,200));
  log('RESULT pass='+pass+' fail='+fail);
  child.kill();
  process.exit(fail>0?1:0);
}
main().catch(e=>{ console.log('FATAL',e?.message); try{child?.kill();}catch{} process.exit(1); });
