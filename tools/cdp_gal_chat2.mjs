/* CDP 复验：精确走「选玩家角色 → 进入对局」流程，检查 livePlayerName/输入框 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9247;
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = 'D:/echoworld/tmp/gal_chat_verify2';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11,19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
let pass = 0, fail = 0;
const check = (n, c, d='') => { if (c) { pass++; log('PASS ' + n + (d?(' :: '+d):'')); } else { fail++; log('FAIL ' + n + (d?(' :: '+d):'')); } };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
class CDP {
  constructor(ws){ this.ws=ws; this.id=0; this.pending=new Map(); }
  static async connect(url){ const ws=new WebSocket(url); await new Promise((res,rej)=>{ws.onopen=res;ws.onerror=rej;}); const c=new CDP(ws); ws.onmessage=(ev)=>{const m=JSON.parse(ev.data);if(m.id&&c.pending.has(m.id)){c.pending.get(m.id)(m);c.pending.delete(m.id);}}; return c; }
  send(method,params={}){ const id=++this.id; return new Promise((resolve,reject)=>{ const t=setTimeout(()=>{this.pending.delete(id);reject(new Error('timeout '+method));},45000); this.pending.set(id,(m)=>{clearTimeout(t);resolve(m);}); try{this.ws.send(JSON.stringify({id,method,params}));}catch(e){clearTimeout(t);reject(e);} }); }
  async eval(expr){ const r=await this.send('Runtime.evaluate',{expression:expr,returnByValue:true,awaitPromise:true}); if(r.result?.exceptionDetails) throw new Error('eval '+JSON.stringify(r.result.exceptionDetails.exception||'').slice(0,200)); return r.result?.result?.value; }
  async shot(f){ try{ const r=await this.send('Page.captureScreenshot',{format:'png'}); if(r.result?.data){ writeFileSync(`${OUT}/${f}`,Buffer.from(r.result.data,'base64')); log('[shot]',f);} }catch(e){} }
}
async function waitFor(cdp,expr,t=40000,label=''){ const t0=Date.now(); while(Date.now()-t0<t){ try{ const v=await cdp.eval(expr); if(v) return v; }catch{} await sleep(700); } throw new Error('timeout '+label); }
async function clickText(cdp,sel,text){ const r=await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`); if(String(r).startsWith('MISS')) throw new Error('click miss '+sel+' '+text); }

async function main(){
  const child = spawn(EDGE, ['--headless=new','--no-proxy-server','--disable-gpu','--no-sandbox',
    `--remote-debugging-port=${PORT}`,'--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-gal2-'+Date.now(),'about:blank'], { stdio:'ignore', detached:true });
  for(let i=0;i<30;i++){ try{ const r=await fetch(`${BASE}/json/version`); if(r.ok) break; }catch{} await sleep(500); }
  const list=await (await fetch(`${BASE}/json/list`)).json();
  const cdp=await CDP.connect(list.find(t=>t.type==='page').webSocketDebuggerUrl);
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
  await sleep(500);
  await cdp.shot('a_roles.png');
  // 通过 store 直接设置玩家角色（最可靠：绕过弹窗点击不确定性）
  await cdp.eval(`(()=>{ const st=window.__demoStore||null; return 'no-demo-store'; })()`);
  // 尝试获取 demo store（window 上可能没暴露）——改用 DOM：点「选择你的角色」卡
  const pick = await cdp.eval(`(()=>{ const cards=Array.from(document.querySelectorAll('.role-chip')); const me=cards.find(c=>c.textContent&&c.textContent.includes('选择你的角色')); if(!me) return 'NO_PICKER'; me.click(); return 'OK'; })()`);
  log('[pick-picker]', pick);
  await sleep(400);
  // 弹窗里点第一个角色
  const pickRole = await cdp.eval(`(()=>{ const btns=Array.from(document.querySelectorAll('.modal-box .role-chip')); if(btns.length===0) return 'NO_MODAL'; btns[0].click(); return 'OK:'+btns[0].textContent.slice(0,10); })()`);
  log('[pick-role]', pickRole);
  await sleep(500);
  await cdp.shot('b_player_selected.png');
  // 确认 withPlayer checkbox 已勾
  const wpc = await cdp.eval(`(()=>{ const c=document.querySelector('.roles-footer input[type=checkbox]'); return c?c.checked:'NO'; })()`);
  log('[withPlayer-checkbox]', wpc);
  // 进入对局
  const start = await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.roles-footer button')).find(x=>x.textContent.includes('进入对局')); if(!b) return 'NO_BTN'; b.click(); return 'OK'; })()`);
  log('[start]', start);
  await waitFor(cdp,`!!document.querySelector('.galg-page')`,120000,'galg');
  await waitFor(cdp,`window.__galGeneralStore&&window.__galGeneralStore.getState().liveMode`,30000,'live');
  log('=== Gal 挂载 ===');
  // 等 SSE 探测到 general + 玩家名
  const t0=Date.now();
  while(Date.now()-t0<60000){
    const st=JSON.parse(await cdp.eval(`(()=>{const s=window.__galGeneralStore.getState(); return JSON.stringify({name:s.livePlayerName,sid:s.liveSessionId,type:s.liveGameType,q:s.liveQueue.length,cur:!!s.current,hasInput:!!document.querySelector('.gal-input'),hasWait:!!document.querySelector('.gal-dialog-wait'),identity:document.querySelector('.galg-identity')?document.querySelector('.galg-identity').textContent.slice(0,40):''});})()`));
    if(st.name) break;
    await sleep(800);
  }
  // 等 AI 消息真正入队/播放（自动轮 LLM 可能耗时 10-40s）
  const tQ=Date.now();
  while(Date.now()-tQ<60000){
    const sQ=JSON.parse(await cdp.eval(`(()=>{const s=window.__galGeneralStore.getState(); return JSON.stringify({q:s.liveQueue.length,cur:!!s.current,typ:!!s.typing});})()`));
    if(sQ.q>0||sQ.cur||sQ.typ) break;
    await sleep(1000);
  }
  const st=JSON.parse(await cdp.eval(`(()=>{const s=window.__galGeneralStore.getState(); return JSON.stringify({name:s.livePlayerName,sid:s.liveSessionId,type:s.liveGameType,q:s.liveQueue.length,cur:!!s.current,typ:!!s.typing,hasInput:!!document.querySelector('.gal-input'),hasWait:!!document.querySelector('.gal-dialog-wait'),waitText:document.querySelector('.gal-dialog-wait')?document.querySelector('.gal-dialog-wait').textContent.slice(0,30):'',identity:document.querySelector('.galg-identity')?document.querySelector('.galg-identity').textContent.slice(0,50):''});})()`));
  log('[final]', JSON.stringify(st));
  check('玩家身份 livePlayerName', !!st.name, `name=${st.name}`);
  check('SSE 探测 general', st.type==='general', `type=${st.type}`);
  check('有输入框', st.hasInput===true, 'hasInput='+st.hasInput);
  check('顶部玩家身份 UI', st.identity.includes('你扮演'), `identity=${st.identity}`);
  check('AI 消息播放', st.q>0||st.cur||st.typ, `q=${st.q}`);
  await cdp.shot('c_final.png');
  log('RESULT pass='+pass+' fail='+fail);
  child.kill();
  process.exit(fail>0?1:0);
}
main().catch(e=>{ console.log('FATAL',e?.message); process.exit(1); });
