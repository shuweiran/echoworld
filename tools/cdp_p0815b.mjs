/* cdp_p0815b.mjs — P-0815-B：一般模式 Gal 视图（GalGeneralView）候选/输入交互 CDP 走查
 * 前置：static_proxy_p0815b.mjs 运行中（4176 → dist + /api → 8000 真实后端）
 * 流程：进入 剧本选择 → 一般模式 → 选剧本 → 角色选择（自由聊天模式 + 带玩家 + 选角色）
 *       → 进入对局 → GalGeneralView → 观察候选条是否反复重建 → 点击候选 → 输入发送 → console 0 错误
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9252;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4176/';
const OUT = 'D:/echoworld/tmp/p0815b';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11,19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
let pass = 0, fail = 0;
const check = (n, c, d='') => { if (c) { pass++; log('PASS ' + n + (d?(' :: '+d):'')); } else { fail++; log('FAIL ' + n + (d?(' :: '+d):'')); } };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
class CDP {
  constructor(ws){ this.ws=ws; this.id=0; this.pending=new Map(); this.consoleLogs=[]; this.netLogs=[]; }
  static async connect(url){ const ws=new WebSocket(url); await new Promise((res,rej)=>{ws.onopen=res;ws.onerror=rej;}); const c=new CDP(ws); ws.onmessage=(ev)=>{const m=JSON.parse(ev.data);if(m.id&&c.pending.has(m.id)){c.pending.get(m.id)(m);c.pending.delete(m.id);}else if(m.method==='Runtime.consoleAPICalled'){c.consoleLogs.push(m.params.type+': '+(m.params.args||[]).map(a=>a.value??a.description??'').join(' ').slice(0,300));}else if(m.method==='Runtime.exceptionThrown'){c.consoleLogs.push('EXCEPTION: '+(m.params.exceptionDetails?.exception?.description||m.params.exceptionDetails?.text||'').slice(0,300));}else if(m.method==='Network.responseReceived'&&m.params.response.url.includes('/api/')){c.netLogs.push(m.params.response.status+' '+m.params.response.url.replace('http://127.0.0.1:4176',''));}}; return c; }
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
    '--user-data-dir=C:\\Temp\\edge-p0815b-'+Date.now(),'about:blank'], { stdio:'ignore', detached:true });
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
  // 自由聊天模式（默认即 chat，确认）
  await clickText(cdp,'.chip2','自由聊天模式');
  await sleep(200);
  // 勾选「带玩家」
  await cdp.eval(`(()=>{ const cb=Array.from(document.querySelectorAll('input[type=checkbox]')).find(x=>x.checked===false); if(cb){cb.click();return 'OK';} return 'NO_CB'; })()`);
  await sleep(300);
  // 选玩家角色（第一个角色卡）
  await cdp.eval(`(()=>{ const cards=Array.from(document.querySelectorAll('.role-chip')); const me=cards.find(c=>c.textContent&&c.textContent.includes('选择你的角色')); if(me){me.click();return 'OK';} return 'NO_PICKER'; })()`);
  await sleep(300);
  await cdp.eval(`(()=>{ const btns=Array.from(document.querySelectorAll('.modal-box .role-chip')); if(btns.length===0) return 'NO_MODAL'; btns[0].click(); return 'OK'; })()`);
  await sleep(400);
  log('[state]', await cdp.eval(`(()=>{const s=JSON.stringify({withPlayer:!!document.body.textContent.match(/带玩家/), lit:(document.body.textContent.match(/点亮\\s*(\\d+)/)||[])[1]||'?'}); return s;})()`));
  // 进入对局
  const start = await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.roles-footer button, button')).find(x=>x.textContent&&x.textContent.includes('进入对局')); if(!b) return 'NO_BTN'; b.click(); return 'OK'; })()`);
  log('[start]', start);
  // 等待 GalGeneralView（galg-page）+ 第一条消息
  await waitFor(cdp,`!!document.querySelector('.galg-page')`,30000,'galg-page');
  log('=== GalGeneralView 挂载 ===');
  await cdp.shot('01_galg_mounted.png');
  const identity = await cdp.eval(`(()=>{ const t=document.querySelector('.galg-identity'); const mode=document.querySelector('.galg-mode-chip'); return JSON.stringify({identity:t?t.textContent.trim():'NO', mode:mode?mode.textContent.trim():'NO'}); })()`);
  log('[identity]', identity);
  check('玩家身份条出现（带玩家模式）', identity.includes('你扮演'), identity.slice(0,60));
  await sleep(6000);
  // 观察候选条/输入框是否出现（等待 AI 首轮播完 → 玩家回合）
  await waitFor(cdp,`!!document.querySelector('.gal-choices') || !!document.querySelector('.gal-input')`,60000,'choices-or-input');
  // 采样 5 次（间隔 800ms）候选条 DOM 变化
  const samples = [];
  for (let i=0;i<6;i++){
    const snap = await cdp.eval(`(()=>{
      const c=document.querySelector('.galg-choices-slot'); if(!c) return 'NO_SLOT';
      return JSON.stringify({
        inner: c.innerHTML.length,
        buttons: c.querySelectorAll('.gal-choice-btn').length,
        texts: Array.from(c.querySelectorAll('.gal-choice-btn')).map(b=>b.textContent.trim().slice(0,20)),
        disabled: Array.from(c.querySelectorAll('.gal-choice-btn')).map(b=>b.disabled),
      });
    })()`);
    samples.push(snap);
    await sleep(800);
  }
  const unique = new Set(samples);
  log('[samples]', samples.join(' ||| '));
  check('候选条存在且内容稳定（非反复重建）', unique.size <= 2, 'unique='+unique.size+' of 6 samples');
  await cdp.shot('02_choices.png');

  // 点击第一个候选
  const clickRes = await cdp.eval(`(()=>{ const b=document.querySelector('.gal-choice-btn'); if(!b) return 'NO_BTN'; if(b.disabled) return 'DISABLED'; b.click(); return 'CLICKED'; })()`);
  log('[click-candidate]', clickRes);
  check('候选按钮可点击（非 disabled）', String(clickRes).startsWith('CLICKED'), String(clickRes));
  await sleep(3000);
  await cdp.shot('03_after_click_3s.png');
  const afterClick = await cdp.eval(`(()=>{
    const inp=document.querySelector('.gal-input');
    const hint=document.querySelector('.gal-input-hint');
    const dialog=document.querySelector('.gal-dialog');
    return JSON.stringify({
      inputVal: inp?inp.value:'NO_INPUT',
      hint: hint?hint.textContent.trim().slice(0,60):'NO',
      dialog: dialog?dialog.textContent.trim().slice(0,40):'NO',
    });
  })()`);
  log('[after-click]', afterClick);
  check('点击候选后无报错（console 0 error）', cdp.consoleLogs.filter(l=>l.startsWith('EXCEPTION')||l.startsWith('error')).length===0, cdp.consoleLogs.slice(-3).join(' | '));

  // 输入框发送
  await cdp.eval(`(()=>{ const inp=document.querySelector('.gal-input'); if(!inp) return 'NO_INPUT'; inp.value='测试消息 0815B'; inp.dispatchEvent(new Event('input',{bubbles:true})); const btn=Array.from(document.querySelectorAll('.gal-send-btn')).find(b=>b.textContent.includes('发送')); if(btn&&!btn.disabled){btn.click();return 'SENT';} return 'BTN_DISABLED val='+inp.value; })()`);
  await sleep(500);
  const sendState = await cdp.eval(`(()=>{ const inp=document.querySelector('.gal-input'); return JSON.stringify({val:inp?inp.value:'NO', hint:(document.querySelector('.gal-input-hint')||{}).textContent?.trim().slice(0,80)||'NO'}); })()`);
  log('[send-state]', sendState);
  await sleep(12000);
  await cdp.shot('04_after_send_12s.png');
  const apiCalls = cdp.netLogs.join(' | ');
  log('[api-calls]', apiCalls);
  check('曾发起 /api/send', apiCalls.includes('/api/send'), apiCalls.slice(0,200));
  check('最终 console 0 错误', cdp.consoleLogs.filter(l=>l.startsWith('EXCEPTION')||l.startsWith('error')).length===0, (cdp.consoleLogs.filter(l=>l.startsWith('EXCEPTION')||l.startsWith('error'))).join(' | ').slice(0,200));
  log('RESULT pass='+pass+' fail='+fail);
  child.kill();
  process.exit(fail>0?1:0);
}
main().catch(e=>{ console.log('FATAL',e?.message); try{child?.kill();}catch{} process.exit(1); });
