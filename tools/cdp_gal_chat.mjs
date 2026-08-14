/* CDP 验证：一般模式起局带玩家 → Gal 界面应显示 AI 消息 + 玩家身份 + 输入框（非"等待对局消息"） */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9246;
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = 'D:/roleplay-java/tmp/gal_chat_verify';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11,19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
let pass = 0, fail = 0;
const check = (n, c, d='') => { if (c) { pass++; log('PASS ' + n + (d?(' :: '+d):'')); } else { fail++; log('FAIL ' + n + (d?(' :: '+d):'')); } };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

class CDP {
  constructor(ws){ this.ws=ws; this.id=0; this.pending=new Map(); }
  static async connect(url){
    const ws = new WebSocket(url);
    await new Promise((res,rej)=>{ ws.onopen=res; ws.onerror=rej; });
    const c = new CDP(ws);
    ws.onmessage=(ev)=>{ const m=JSON.parse(ev.data); if(m.id&&c.pending.has(m.id)){ c.pending.get(m.id)(m); c.pending.delete(m.id); } };
    return c;
  }
  send(method,params={}){ const id=++this.id; return new Promise((resolve,reject)=>{ const t=setTimeout(()=>{this.pending.delete(id);reject(new Error('timeout '+method));},45000); this.pending.set(id,(m)=>{clearTimeout(t);resolve(m);}); try{ this.ws.send(JSON.stringify({id,method,params})); }catch(e){clearTimeout(t);reject(e);} }); }
  async eval(expr){ const r=await this.send('Runtime.evaluate',{expression:expr,returnByValue:true,awaitPromise:true}); if(r.result?.exceptionDetails) throw new Error('eval: '+JSON.stringify(r.result.exceptionDetails.exception||'').slice(0,200)); return r.result?.result?.value; }
  async shot(f){ try{ const r=await this.send('Page.captureScreenshot',{format:'png'}); if(r.result?.data){ writeFileSync(`${OUT}/${f}`,Buffer.from(r.result.data,'base64')); log('[shot]',f);} }catch(e){} }
}
async function waitFor(cdp,expr,t=30000,label=''){ const t0=Date.now(); while(Date.now()-t0<t){ try{ const v=await cdp.eval(expr); if(v) return v; }catch{} await sleep(700); } throw new Error('timeout '+label); }
async function clickText(cdp,sel,text){ const r=await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`); if(String(r).startsWith('MISS')) throw new Error('click miss '+sel+' '+text); }

async function main(){
  const child = spawn(EDGE, ['--headless=new','--no-proxy-server','--disable-gpu','--no-sandbox',
    `--remote-debugging-port=${PORT}`,'--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-gal-'+Date.now(),'about:blank'], { stdio:'ignore', detached:true });
  for(let i=0;i<30;i++){ try{ const r=await fetch(`${BASE}/json/version`); if(r.ok) break; }catch{} await sleep(500); }
  const list=await (await fetch(`${BASE}/json/list`)).json();
  let page=list.find(t=>t.type==='page');
  const cdp=await CDP.connect(page.webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
  await cdp.send('Emulation.setVisibleSize',{width:1440,height:900});
  if(await cdp.eval('1+1')!==2) throw new Error('cdp sanity');

  await cdp.send('Page.navigate',{url:'http://127.0.0.1:8000/'});
  await waitFor(cdp,`document.querySelectorAll('.app2-nav-btn').length>=5`,20000,'nav');
  await clickText(cdp,'.app2-nav-btn','剧本选择');
  await waitFor(cdp,`!!document.querySelector('.chip2')`,15000,'script page');
  await clickText(cdp,'.chip2','一般模式');
  await sleep(500);
  await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp,`document.body.textContent.includes('角色选择')`,15000,'roles');
  await sleep(400);
  // 选玩家角色：点第一张"选择你的角色"
  await cdp.eval(`(()=>{ const e=document.querySelector('.role-chip'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await sleep(300);
  await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.role-chip')).find(x=>x.textContent&&!x.textContent.includes('选择你的角色')); if(b){b.click();return 'OK';} return 'NO'; })()`);
  await sleep(400);
  // 开始对局（默认带玩家 chat）
  await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.roles-footer button')).find(x=>x.textContent.includes('进入对局')); if(b){b.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp,`!!document.querySelector('.galg-page')`,120000,'galg');
  await waitFor(cdp,`window.__galGeneralStore&&window.__galGeneralStore.getState().liveMode`,30000,'live');
  log('=== GalGeneralView 挂载 ===');
  await cdp.shot('01_mounted.png');
  // 等 AI 消息入队
  const t0=Date.now(); let got=false;
  while(Date.now()-t0<15000){
    const st=await cdp.eval(`(()=>{const s=window.__galGeneralStore.getState(); return JSON.stringify({q:s.liveQueue.length,cur:!!s.current,typ:!!s.typing,liveName:s.livePlayerName,sid:s.liveSessionId,wait:document.querySelector('.gal-dialog-wait')?document.querySelector('.gal-dialog-wait').textContent.slice(0,20):''});})()`);
    const o=JSON.parse(st);
    if(o.q>0||o.cur||o.typ){ got=true; break; }
    await sleep(800);
  }
  const st=JSON.parse(await cdp.eval(`(()=>{const s=window.__galGeneralStore.getState(); return JSON.stringify({q:s.liveQueue.length,cur:!!s.current,typ:!!s.typing,liveName:s.livePlayerName,sid:s.liveSessionId,hasInput:!!document.querySelector('.gal-input'),hasWait:!!document.querySelector('.gal-dialog-wait'),waitText:document.querySelector('.gal-dialog-wait')?document.querySelector('.gal-dialog-wait').textContent.slice(0,30):''});})()`));
  log('[state]',JSON.stringify(st));
  check('AI 消息已入队/播放', st.q>0||st.cur||st.typ, `q=${st.q}`);
  check('玩家身份已设置', !!st.liveName, `liveName=${st.liveName}`);
  check('有输入框（带玩家）', st.hasInput===true, 'hasInput='+st.hasInput);
  check('非「等待对局消息」卡住', st.hasWait!==true || st.q>0 || st.cur, `wait=${st.waitText}`);
  await cdp.shot('02_state.png');
  log('RESULT pass='+pass+' fail='+fail);
  child.kill();
  process.exit(fail>0?1:0);
}
main().catch(e=>{ console.log('FATAL',e?.message); try{child?.kill();}catch{} process.exit(1); });
