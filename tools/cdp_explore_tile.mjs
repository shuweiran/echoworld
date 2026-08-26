/* CDP：采样 2D 探索 canvas 像素，确认瓦片背景（非纯深色）渲染 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9252;
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = 'D:/echoworld/tmp/explore_tile_check';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11,19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
let pass=0, fail=0;
const check=(n,c,d='')=>{ if(c){pass++;log('PASS '+n+(d?(' :: '+d):''));} else {fail++;log('FAIL '+n+(d?(' :: '+d):''));} };
const sleep=(ms)=>new Promise(r=>setTimeout(r,ms));
class CDP {
  constructor(ws){ this.ws=ws; this.id=0; this.pending=new Map(); }
  static async connect(url){ const ws=new WebSocket(url); await new Promise((res,rej)=>{ws.onopen=res;ws.onerror=rej;}); const c=new CDP(ws); ws.onmessage=(ev)=>{const m=JSON.parse(ev.data);if(m.id&&c.pending.has(m.id)){c.pending.get(m.id)(m);c.pending.delete(m.id);}}; return c; }
  send(method,params={}){ const id=++this.id; return new Promise((resolve,reject)=>{ const t=setTimeout(()=>{this.pending.delete(id);reject(new Error('timeout '+method));},45000); this.pending.set(id,(m)=>{clearTimeout(t);resolve(m);}); try{this.ws.send(JSON.stringify({id,method,params}));}catch(e){clearTimeout(t);reject(e);} }); }
  async eval(expr){ const r=await this.send('Runtime.evaluate',{expression:expr,returnByValue:true,awaitPromise:true}); if(r.result?.exceptionDetails) throw new Error('eval '+JSON.stringify(r.result.exceptionDetails.exception||'').slice(0,200)); return r.result?.result?.value; }
}
async function waitFor(cdp,expr,t=40000,label=''){ const t0=Date.now(); while(Date.now()-t0<t){ try{ const v=await cdp.eval(expr); if(v) return v; }catch{} await sleep(700); } throw new Error('timeout '+label); }
async function clickText(cdp,sel,text){ const r=await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`); if(String(r).startsWith('MISS')) throw new Error('click miss '+sel+' '+text); }
async function main(){
  const child=spawn(EDGE,['--headless=new','--no-proxy-server','--disable-gpu','--no-sandbox',`--remote-debugging-port=${PORT}`,'--window-size=1440,900','--user-data-dir=C:\\Temp\\edge-tile-'+Date.now(),'about:blank'],{stdio:'ignore',detached:true});
  for(let i=0;i<30;i++){ try{ const r=await fetch(`${BASE}/json/version`); if(r.ok) break; }catch{} await sleep(500); }
  const list=await (await fetch(`${BASE}/json/list`)).json();
  const cdp=await CDP.connect(list.find(t=>t.type==='page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride',{width:1440,height:900,deviceScaleFactor:1,mobile:false});
  await cdp.send('Emulation.setVisibleSize',{width:1440,height:900});
  if(await cdp.eval('1+1')!==2) throw new Error('sanity');
  await cdp.send('Page.navigate',{url:'http://127.0.0.1:8000/'});
  await waitFor(cdp,`document.querySelectorAll('.app2-nav-btn').length>=5`,20000,'nav');
  await clickText(cdp,'.app2-nav-btn','剧本选择');
  await waitFor(cdp,`!!document.querySelector('.chip2')`,15000,'script');
  await clickText(cdp,'.chip2','一般模式');
  await sleep(500);
  await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp,`document.body.textContent.includes('角色选择')`,15000,'roles');
  await sleep(400);
  await clickText(cdp,'.chip2','2D 探索模式');
  await sleep(300);
  await cdp.eval(`(()=>{ const me=Array.from(document.querySelectorAll('.role-chip')).find(c=>c.textContent&&c.textContent.includes('选择你的角色')); if(me){me.click();return 'OK';} return 'NO'; })()`);
  await sleep(300);
  await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.modal-box .role-chip')); if(b.length){b[0].click();return 'OK';} return 'NO'; })()`);
  await sleep(400);
  await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.roles-footer button')).find(x=>x.textContent.includes('进入 2D 探索')); if(b){b.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp,`!!document.querySelector('.phaser-sim-view canvas')`,120000,'canvas');
  await sleep(4000);
  // 采样 canvas 像素：取多个点，统计颜色多样性（瓦片背景应有多种颜色；纯深色背景几乎单色）
  const px = await cdp.eval(`(()=>{
    const c=document.querySelector('.phaser-sim-view canvas');
    if(!c) return 'NO_CANVAS';
    const ctx=c.getContext('2d');
    const colors=new Set();
    const pts=[[50,50],[150,150],[300,100],[500,300],[700,200],[900,500],[400,400],[800,100],[100,450],[650,400],[250,250],[550,550]];
    for(const [x,y] of pts){ try{ const d=ctx.getImageData(x,y,1,1).data; colors.add(d[0]+','+d[1]+','+d[2]); }catch(e){} }
    return JSON.stringify({colors:Array.from(colors), n:colors.size});
  })()`);
  log('[canvas-pixels]', px);
  const o=JSON.parse(px);
  check('瓦片背景多色渲染（非纯深色）', o.n>=4, `distinctColors=${o.n}`);
  log('RESULT pass='+pass+' fail='+fail);
  child.kill();
  process.exit(fail>0?1:0);
}
main().catch(e=>{ console.log('FATAL',e?.message); try{child?.kill();}catch{} process.exit(1); });
