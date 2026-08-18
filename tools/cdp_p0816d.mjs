/* cdp_p0816d.mjs — P-0816-D 2D 地图「显示太小」取证 + 复测 CDP 脚本
 * 前置：tools/static_proxy_p0816d.mjs 运行中（4192 = dist 当前部署 bundle；/api → 8000 真实后端）；
 *       tmp/p0816b_map.json（P-0816-B 已生成的真实 LLM 地图，含 zones/rooms/spawn/decor）
 * 目标：实测 canvas 显示尺寸 vs 内部分辨率（FIT 缩放比例）、宿主容器链路尺寸、
 *       游戏内 2D vs 预览弹窗（PhaserScriptMapView）同屏对比、滚轮缩放后尺寸变化
 * 流程：剧本选择 → 一般模式 → 剧本卡 → 角色选择 → [预览弹窗测量] → 2D 探索 [游戏内测量]
 * 产出：tmp/p0816d/progress.log + 截图 + result.json
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync, readFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9258;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4192/';
const OUT = 'D:/roleplay-java/tmp/p0816d';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };

const MAP_BODY = JSON.parse(readFileSync('D:/roleplay-java/tmp/p0816b_map.json', 'utf-8').replace(/^\uFEFF/, ''));
const MAP = MAP_BODY.map ?? MAP_BODY;

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); this.consoleLogs = []; this.lastShotData = ''; }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); }
      else if (m.method === 'Runtime.consoleAPICalled') c.consoleLogs.push(m.params.type + ': ' + (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ').slice(0, 200));
      else if (m.method === 'Runtime.exceptionThrown') c.consoleLogs.push('EXCEPTION: ' + (m.params.exceptionDetails?.exception?.description || m.params.exceptionDetails?.text || '').slice(0, 200));
    };
    return c;
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 60000);
      this.pending.set(id, (m) => { clearTimeout(t); resolve(m); });
      try { this.ws.send(JSON.stringify({ id, method, params })); } catch (e) { clearTimeout(t); reject(e); }
    });
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.result?.exceptionDetails) throw new Error('eval ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 300));
    return r.result?.result?.value;
  }
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); this.lastShotData = r.result.data; log('[shot]', f); return r.result.data; } } catch (e) { } return ''; }
}

async function waitFor(cdp, expr, t = 45000, label = '') {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(700);
  }
  throw new Error('timeout ' + label);
}
async function clickText(cdp, sel, text) {
  const r = await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`);
  if (String(r).startsWith('MISS')) throw new Error('click miss ' + sel + ' ' + text);
}

const PAGE_STATE = `(async () => { const r = await fetch('/api/simulation/state'); const d = await r.json(); return JSON.stringify({ running: d.running, agents: (d.agents||[]).map(a => ({ n: a.agentName, x: a.x, y: a.y, pc: !!a.playerControlled })), tick: d.tickCount ?? d.tick ?? 0 }); })()`;
async function pageState(cdp) { return JSON.parse(await cdp.eval(PAGE_STATE)); }

// ── 画布/容器尺寸探针：canvas 显示尺寸（CSS rect） vs 内部分辨率（canvas.width/height）──
const SIZE_PROBE = `(() => {
  const canvas = document.querySelector('.phaser-sim-view canvas') || document.querySelector('.script-map-host canvas') || document.querySelector('canvas');
  if (!canvas) return JSON.stringify({ found: false });
  const r = canvas.getBoundingClientRect();
  const host = canvas.parentElement;
  const hr = host ? host.getBoundingClientRect() : null;
  // 宿主容器链路（向上到 body）
  const chain = [];
  let el = host;
  while (el && el !== document.body) {
    const b = el.getBoundingClientRect();
    chain.push({ tag: el.tagName, cls: (el.className && typeof el.className === 'string') ? el.className.slice(0, 80) : '', w: Math.round(b.width), h: Math.round(b.height) });
    el = el.parentElement;
  }
  const canvasStyle = getComputedStyle(canvas);
  return JSON.stringify({
    found: true,
    canvas: { cssW: Math.round(r.width), cssH: Math.round(r.height), left: Math.round(r.left), top: Math.round(r.top) },
    internal: { w: canvas.width, h: canvas.height },
    scaleFactor: canvas.width > 0 ? +(r.width / canvas.width).toFixed(4) : 0,
    host: hr ? { w: Math.round(hr.width), h: Math.round(hr.height) } : null,
    viewport: { w: window.innerWidth, h: window.innerHeight },
    dpr: window.devicePixelRatio,
    canvasStyle: { w: canvasStyle.width, h: canvasStyle.height, position: canvasStyle.position, display: canvasStyle.display },
    chain,
  });
})()`;

async function measure(cdp, label) {
  const s = JSON.parse(await cdp.eval(SIZE_PROBE));
  log('[size:' + label + ']', JSON.stringify(s));
  return s;
}

function main2(cdp, child) {
  return (async () => {
    // ── 注入 localStorage：三个预设剧本 id 都挂上真实 LLM 地图（命中缓存不发起生成）──
    await cdp.send('Page.navigate', { url: APP });
    await waitFor(cdp, `document.readyState==='complete'`, 25000, 'app first load');
    await sleep(1500);
    const injected = await cdp.eval(`(() => {
      const key = 'roleplay_demo2_general_maps_v1';
      const cur = (() => { try { return JSON.parse(localStorage.getItem(key) || '{}'); } catch { return {}; } })();
      cur['g_cafe'] = ${JSON.stringify(MAP)};
      cur['g_galaxy'] = ${JSON.stringify(MAP)};
      cur['g_school'] = ${JSON.stringify(MAP)};
      try { localStorage.setItem(key, JSON.stringify(cur)); return 'OK'; } catch (e) { return 'ERR ' + e.message; }
    })()`);
    check('0 注入 generalMaps 缓存', injected === 'OK', String(injected));
    await cdp.send('Page.reload', { ignoreCache: true });
    await waitFor(cdp, `document.querySelectorAll('.app2-nav-btn').length>=5`, 25000, 'nav after reload');

    // ── 导航：剧本选择 → 一般模式 → 剧本卡 → 角色选择 ──
    await clickText(cdp, '.app2-nav-btn', '剧本选择');
    await waitFor(cdp, `!!document.querySelector('.chip2')`, 15000, 'mode chips');
    await clickText(cdp, '.chip2', '一般模式');
    await sleep(600);
    await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
    await waitFor(cdp, `document.body.textContent.includes('角色选择')`, 20000, 'roles page');
    await sleep(800);

    // ══ A. 预览弹窗（PhaserScriptMapView）尺寸取证 ══
    const previewBtn = await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.chip2')).find(c=>c.textContent&&(c.textContent.includes('预览地图')||c.textContent.includes('生成并预览'))); if(!b) return 'MISS'; b.click(); return b.textContent.trim(); })()`);
    log('[preview-btn]', String(previewBtn));
    await waitFor(cdp, `!!document.querySelector('.modal-mask canvas') || !!document.querySelector('.modal-mask')`, 25000, 'preview modal');
    await waitFor(cdp, `(()=>{ const c=document.querySelector('.modal-mask canvas'); if(!c) return false; const r=c.getBoundingClientRect(); return r.width>50; })()`, 25000, 'preview canvas rendered');
    await sleep(1500);
    const pv = await measure(cdp, 'PREVIEW');
    await cdp.shot('01_preview_modal.png');
    check('A1 预览画布已渲染（cssW>300）', pv.found && pv.canvas.cssW > 300, JSON.stringify({ css: pv.canvas, internal: pv.internal }));
    check('A2 预览 FIT 缩放比例记录', pv.found, 'scaleFactor=' + (pv.found ? pv.scaleFactor : 'n/a'));
    // 关弹窗
    await cdp.eval(`(()=>{ const m=document.querySelector('.modal-mask'); if(m){ m.click(); return 'OK'; } return 'NO'; })()`);
    await sleep(800);

    // ── 角色选择：2D 探索 + 带玩家 + 选 me + 点亮 3 AI ──
    await clickText(cdp, '.chip2', '2D 探索');
    await sleep(300);
    await cdp.eval(`(()=>{ const cb=Array.from(document.querySelectorAll('.roles-footer input[type=checkbox]')).find(x=>x.checked===false); if(cb){cb.click();return 'OK';} return 'NO_CB'; })()`);
    await sleep(300);
    await cdp.eval(`(()=>{ const b=Array.from(document.querySelectorAll('.role-chip')).find(c=>c.textContent&&c.textContent.includes('选择你的角色')); if(!b) return 'NO_PICKER'; b.click(); return 'OK'; })()`);
    await sleep(400);
    await cdp.eval(`(()=>{ const btns=Array.from(document.querySelectorAll('.modal-box .role-chip')); if(btns.length===0) return 'NO_MODAL'; btns[0].click(); return 'OK'; })()`);
    await sleep(400);
    const playerName = await cdp.eval(`(()=>{ const c=Array.from(document.querySelectorAll('.role-chip')).find(x=>x.textContent&&x.textContent.includes('玩家角色')&&!x.textContent.includes('不参与本局')); if(!c) return ''; const t=c.textContent.trim(); const m=t.match(/^(\\S+)\\s+([^·]+?)\\s*·/); return m?m[2].trim():''; })()`);
    log('[player-role]', playerName);
    await cdp.eval(`(()=>{
      const pn=${JSON.stringify(playerName)};
      const cards = Array.from(document.querySelectorAll('.role-chip')).filter(c=>c.classList && !c.classList.contains('role-chip-add') && !c.textContent.includes('选择你的角色') && !c.textContent.includes('玩家角色') && !c.textContent.includes('不参与本局') && !(pn && c.textContent.includes(pn)));
      let n=0;
      for(const c of cards){ if(!c.classList.contains('selected')){ c.click(); n++; if(n>=3) break; } }
      return 'lit='+n;
    })()`);
    await sleep(400);
    await clickText(cdp, '.btn2.btn2-primary', '进入 2D 探索');

    // ══ B. 游戏内 2D（PhaserSimulationView）尺寸取证 ══
    await waitFor(cdp, `!!document.querySelector('.phaser-sim-view canvas')`, 45000, 'phaser canvas');
    await waitFor(cdp, `(async()=>{ const r=await fetch('/api/simulation/state'); const d=await r.json(); return d.running === true; })()`, 30000, 'world running');
    await waitFor(cdp, `(async()=>{ const r=await fetch('/api/simulation/state'); const d=await r.json(); return (d.agents||[]).length>=4; })()`, 60000, '4 agents');
    await sleep(4000); // 首帧稳定
    const st = await pageState(cdp);
    log('[world]', JSON.stringify({ agents: st.agents.map(a => a.n + '@' + Math.round(a.x) + ',' + Math.round(a.y) + (a.pc ? '(me)' : '')), tick: st.tick, running: st.running }));
    check('B0 2D 世界加载 4 角色', st.agents.length >= 4, JSON.stringify(st.agents.map(a => a.n)));

    // 1440×900 视口下测量
    const g1 = await measure(cdp, 'GAME@1440x900');
    check('B1 游戏内画布已渲染（cssW>300）', g1.found && g1.canvas.cssW > 300, JSON.stringify({ css: g1.canvas, internal: g1.internal, host: g1.host }));
    // 世界内容是否铺满画布：地图铺满 1000×600 → 若 FIT 生效，画布显示整张地图
    check('B2 游戏内 FIT 缩放生效（canvas 显示尺寸 ≠ 内部 1000×600 且按比例）',
      g1.found && g1.canvas.cssW !== g1.internal.w && g1.scaleFactor > 0.5 && g1.scaleFactor < 1.5,
      'scaleFactor=' + (g1.found ? g1.scaleFactor : 'n/a'));
    await cdp.shot('02_game_1440x900.png');

    // 全屏按钮可点吗？先测滚轮缩放对显示的影响（zoom=1 全景 vs zoom>1）
    // 先记录画布内容对比：滚轮缩放只动相机，画布尺寸不变（验证显示尺寸与相机解耦）
    const rect = JSON.parse(await cdp.eval(`(()=>{ const c=document.querySelector('.phaser-sim-view canvas'); if(!c) return 'null'; const r=c.getBoundingClientRect(); return JSON.stringify({left:r.left,top:r.top,width:r.width,height:r.height}); })()`));
    if (rect) {
      const cx = rect.left + rect.width / 2, cy = rect.top + rect.height / 2;
      for (let i = 0; i < 4; i++) {
        await cdp.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: cx, y: cy, deltaX: 0, deltaY: -120 });
        await sleep(300);
      }
      await sleep(1500);
      await cdp.shot('03_game_zoomed.png');
      const g2 = await measure(cdp, 'GAME_ZOOMED');
      check('B3 滚轮缩放后画布显示尺寸不变（缩放走相机不走 canvas）',
        g2.found && Math.abs(g2.canvas.cssW - g1.canvas.cssW) <= 2 && Math.abs(g2.canvas.cssH - g1.canvas.cssH) <= 2,
        'cssW ' + g1.canvas.cssW + '→' + g2.canvas.cssW + ' cssH ' + g1.canvas.cssH + '→' + g2.canvas.cssH);
    }

    // 1920×1080 视口（放大视口 → FIT 应随容器放大画布显示尺寸；高度 560 是瓶颈）
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1920, height: 1080, deviceScaleFactor: 1, mobile: false });
    await sleep(2500);
    const g3 = await measure(cdp, 'GAME@1920x1080');
    check('B4 视口 1440→1920 画布显示宽度变化记录（若仍 933≈受限高度则 FIT 高度瓶颈成立）',
      g3.found, JSON.stringify({ w1440: g1.canvas.cssW, w1920: g3.canvas.cssW, h1920: g3.canvas.cssH, host: g3.host }));
    await cdp.shot('04_game_1920x1080.png');

    // ── C console 错误（白名单：charanim-助手 素材解析失败为既有噪音）──
    const errs = cdp.consoleLogs.filter(l => (l.startsWith('EXCEPTION') || l.startsWith('error'))
      && !l.includes('charanim-助手') && !l.includes('Expected property name')
      && !l.includes('map-tileset-asset')); // 素材图集经代理缺失为 CDP 环境噪音（真实后端 200 image/png）
    check('C console 无新增错误', errs.length === 0, errs.slice(0, 3).join(' | ') || 'clean');

    // ── D 复位测试世界 ──
    await cdp.eval(`(async () => { try { await fetch('/api/simulation/reset', { method: 'POST' }); } catch(e){} return 'OK'; })()`);
    await sleep(2500);
    const stReset = await pageState(cdp);
    check('D 测试世界已复位（agents=0 running=false）', (stReset.agents || []).length === 0 && stReset.running === false,
      JSON.stringify({ agents: (stReset.agents || []).length, running: stReset.running }));

    log('RESULT pass=' + pass + ' fail=' + fail);
    writeFileSync(`${OUT}/result.json`, JSON.stringify({
      pass, fail,
      preview: pv,
      game1440: g1,
      gameZoomed: undefined,
      game1920: g3,
      consoleErrors: errs.length,
    }, null, 2));
    child.kill();
    process.exit(fail > 0 ? 1 : 0);
  })();
}

async function main() {
  let child;
  child = spawn(EDGE, ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0816d-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 30; i++) { try { const r = await fetch(`${BASE}/json/version`); if (r.ok) break; } catch { } await sleep(500); }
  const list = await (await fetch(`${BASE}/json/list`)).json();
  const cdp = await CDP.connect(list.find(t => t.type === 'page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  if (await cdp.eval('1+1') !== 2) throw new Error('cdp sanity');
  await main2(cdp, child);
}
main().catch(e => { console.log('FATAL', e?.message); try { process.kill(0); } catch { } process.exit(1); });
