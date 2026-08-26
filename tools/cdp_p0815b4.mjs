/* cdp_p0815b4.mjs — P-0815-B(→C)：死态诊断
 * 起局后：探针 liveStatus/门控输入 → 直接调 store.advance() → 观察状态变化
 *        → DOM .gal-dialog click → 观察 → 探针候选区为什么不可见
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9255;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4176/';
const OUT = 'D:/echoworld/tmp/p0815b4';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11,19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
class CDP {
  constructor(ws){ this.ws=ws; this.id=0; this.pending=new Map(); }
  static async connect(url){ const ws=new WebSocket(url); await new Promise((res,rej)=>{ws.onopen=res;ws.onerror=rej;}); const c=new CDP(ws); ws.onmessage=(ev)=>{const m=JSON.parse(ev.data);if(m.id&&c.pending.has(m.id)){c.pending.get(m.id)(m);c.pending.delete(m.id);}}; return c; }
  send(method,params={}){ const id=++this.id; return new Promise((resolve,reject)=>{ const t=setTimeout(()=>{this.pending.delete(id);reject(new Error('timeout '+method));},60000); this.pending.set(id,(m)=>{clearTimeout(t);resolve(m);}); try{this.ws.send(JSON.stringify({id,method,params}));}catch(e){clearTimeout(t);reject(e);} }); }
  async eval(expr){ const r=await this.send('Runtime.evaluate',{expression:expr,returnByValue:true,awaitPromise:true}); if(r.result?.exceptionDetails) throw new Error('eval '+JSON.stringify(r.result.exceptionDetails.exception||'').slice(0,200)); return r.result?.result?.value; }
}
async function waitFor(cdp,expr,t=45000,label=''){ const t0=Date.now(); while(Date.now()-t0<t){ try{ const v=await cdp.eval(expr); if(v) return v; }catch{} await sleep(700); } throw new Error('timeout '+label); }
async function clickText(cdp,sel,text){ const r=await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`); if(String(r).startsWith('MISS')) throw new Error('click miss '+sel+' '+text); }

const FULL = `(()=>{ const st=window.__galGeneralStore.getState(); return JSON.stringify({
  liveMode:st.liveMode, liveStatus:st.liveStatus, liveGameType:st.liveGameType,
  liveSending:st.liveSending, queueLen:st.liveQueue.length,
  typing:st.typing?{d:st.typing.done,c:st.typing.chars,f:st.typing.full.length,s:st.typing.speakerId}:null,
  current:st.current?(st.current.speakerId+'|'+(st.current.text||'').slice(0,14)):null,
  log:st.log.length, sug:st.liveSuggestions, livePlayerName:st.livePlayerName,
  choicesBtns: (()=>{ const c=document.querySelector('.galg-choices-slot'); return c?c.querySelectorAll('.gal-choice-btn').length:'NO_SLOT'; })(),
  dialogCount: document.querySelectorAll('.gal-dialog').length,
  gate: (st.liveMode && st.liveGameType==='general' && st.liveStatus==='open' && !st.liveSending && !(st.typing&&!st.typing.done) && st.liveQueue.length<=1),
}); })()`;

async function main(){
  let child;
  child = spawn(EDGE, ['--headless=new','--no-proxy-server','--disable-gpu','--no-sandbox',
    `--remote-debugging-port=${PORT}`,'--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0815b4-'+Date.now(),'about:blank'], { stdio:'ignore', detached:true });
  for(let i=0;i<30;i++){ try{ const r=await fetch(`${BASE}/json/version`); if(r.ok) break; }catch{} await sleep(500); }
  const list=await (await fetch(`${BASE}/json/list`)).json();
  const cdp=await CDP.connect(list.find(t=>t.type==='page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
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
  await sleep(6000);
  log('[probe-6s]', await cdp.eval(FULL));
  await sleep(6000);
  log('[probe-12s]', await cdp.eval(FULL));

  // 直接调 store.advance()（绕过 DOM 事件）
  const r1 = await cdp.eval(`(()=>{ const st=window.__galGeneralStore.getState(); const before=JSON.stringify({q:st.liveQueue.length, cur:st.current?st.current.speakerId:null, typ:st.typing?st.typing.done:null, log:st.log.length}); st.advance(); const s2=window.__galGeneralStore.getState(); return JSON.stringify({before, after:{q:s2.liveQueue.length, cur:s2.current?s2.current.speakerId:null, typ:s2.typing?s2.typing.done:null, log:s2.log.length}}); })()`);
  log('[direct-advance]', r1);

  // DOM 点击 .gal-dialog
  const r2 = await cdp.eval(`(()=>{ const d=document.querySelector('.gal-dialog'); if(!d) return 'NO_DIALOG'; const cs=getComputedStyle(d); const rect=d.getBoundingClientRect();
    const catcher=document.elementFromPoint(rect.left+rect.width/2, rect.top+rect.height/2);
    d.click();
    const s=window.__galGeneralStore.getState();
    return JSON.stringify({dialogClass:d.className, pe:cs.pointerEvents, z:cs.zIndex, hit:catcher?catcher.className:'NONE', after:{q:s.liveQueue.length, cur:s.current?s.current.speakerId:null, log:s.log.length}}); })()`);
  log('[dom-click-dialog]', r2);

  await sleep(4000);
  log('[probe-16s]', await cdp.eval(FULL));
  await sleep(8000);
  log('[probe-24s]', await cdp.eval(FULL));
  // 多按几次 advance 直到队列空
  for(let i=0;i<6;i++){
    await cdp.eval(`(()=>{ window.__galGeneralStore.getState().advance(); return 'OK'; })()`);
    await sleep(900);
    log('[adv#'+(i+1)+']', (await cdp.eval(`(()=>{ const s=window.__galGeneralStore.getState(); return JSON.stringify({q:s.liveQueue.length, cur:s.current?s.current.speakerId:null, typ:s.typing?s.typing.done:null, log:s.log.length}); })()`)));
  }
  log('[final]', await cdp.eval(FULL));
  child.kill();
  process.exit(0);
}
main().catch(e=>{ console.log('FATAL',e?.message); try{child?.kill();}catch{} process.exit(1); });
