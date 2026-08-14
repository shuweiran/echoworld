/* CDP 验证：一般模式角色选择页「生成并预览地图」端到端 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9249;
const BASE = `http://127.0.0.1:${PORT}`;
const OUT = 'D:/roleplay-java/tmp/map_preview_verify';
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
    '--user-data-dir=C:\\Temp\\edge-map-'+Date.now(),'about:blank'], { stdio:'ignore', detached:true });
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
  // 点「生成并预览地图」
  const btn = await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.chip2,button')).find(x=>x.textContent&&x.textContent.includes('生成并预览地图')); if(!b) return 'NO_BTN'; b.click(); return 'OK'; })()`);
  log('[preview-click]', btn);
  // 等待地图生成（弹窗出现 → 生成中 → canvas）
  let gotCanvas = false;
  const t0=Date.now();
  while(Date.now()-t0<90000){
    await sleep(1500);
    const hasCanvas = await cdp.eval(`!!document.querySelector('.modal-box .script-map-host canvas')`);
    const busy = await cdp.eval(`document.body.textContent.includes('正在生成地图')`);
    if(hasCanvas){ gotCanvas=true; break; }
    if(!busy && Date.now()-t0>30000) break; // 无生成中且超时 → 可能失败
  }
  await cdp.shot('map_preview.png');
  check('地图预览 canvas 渲染', gotCanvas===true);
  // 检查是否有错误提示
  const err = await cdp.eval(`(()=>{ const m=document.querySelector('.modal-box'); return m?m.textContent.slice(0,120):'NO_MODAL'; })()`);
  log('[modal]', err.replace(/\n/g,' '));
  check('无「生成失败」错误', !err.includes('失败'), err.slice(0,60));
  // 检查标题含地图名
  const hasTitle = await cdp.eval(`document.body.textContent.includes('地图预览')||document.body.textContent.includes('LLM 生成')`);
  check('弹窗标题/LLM 标记', !!hasTitle);
  log('RESULT pass='+pass+' fail='+fail);
  child.kill();
  process.exit(fail>0?1:0);
}
main().catch(e=>{ console.log('FATAL',e?.message); try{child?.kill();}catch{} process.exit(1); });
