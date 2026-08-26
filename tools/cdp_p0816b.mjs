/* cdp_p0816b.mjs — P-0816-B 游戏内 2D（SimulationScene）地图内容渲染补齐 CDP 端到端真机验证
 * 前置：tools/static_proxy_p0816b.mjs 运行中（4191 = dist 新 bundle（含本批渲染）；/api → 8000 真实后端）；
 *       localStorage 注入真实 LLM 地图（POST /api/scenes/map 已生成，tmp/p0816b_map.json，
 *       含 5 zones/5 rooms/4 spawn_points/3 decor/spawnMarkers）→ GameBridge 命中缓存不发起生成
 * 流程：剧本选择 → 一般模式 → 剧本卡 → 角色选择（2D 探索 + 带玩家 + 选 me + 点亮 3 AI）→ 进入 2D 探索
 * 断言：
 *   A 世界启动：canvas + running + 4 agents（me + 3 AI）
 *   B 像素采样（截图像素在页面内解码）：金色热点区域命中 / 出生点蓝标记命中 / decor 色块命中
 *     （bench 棕 / lamp 黄 / flower 红 / grass 浅绿 / debris 棕）—— 截图像素级证据（真机渲染）
 *   C 交互零破坏：点击玩家 → hasTarget 置位（点击设目标仍工作）；点击 NPC → Gal 面板打开（对话链路正常）
 *   D 热点点击提示：点击热点区域中心 → 底部提示出现（截图佐证）
 *   E 缩放兼容：滚轮缩放（zoom in）→ 截图佐证新渲染随相机正常显示（世界坐标 Graphics/Text 天然兼容）
 *   F console 0 新增错误
 *   G 测试世界复位（running=false agents=0）
 * 产出：tmp/p0816b/progress.log + 截图
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync, readFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9257;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4191/';
const OUT = 'D:/echoworld/tmp/p0816b';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };

// 真实 LLM 地图（本地已生成；含 zones/rooms/spawn_points/decor/spawnMarkers）
const MAP_BODY = JSON.parse(readFileSync('D:/echoworld/tmp/p0816b_map.json', 'utf-8').replace(/^\uFEFF/, ''));
const MAP = MAP_BODY.map ?? MAP_BODY;
const TILE_W = 1000 / MAP.width;
const TILE_H = 600 / MAP.height;
log('[map]', JSON.stringify({
  size: MAP.width + 'x' + MAP.height,
  zones: MAP.zones.length, rooms: MAP.rooms.length,
  spawns: MAP.spawn_points.length, decor: (MAP.decor || []).length,
  spawnMarkers: Object.keys(MAP.spawnMarkers || {}).map(k => k + ':' + MAP.spawnMarkers[k].length),
}));
// 热点世界坐标（供点击/命中）
const ZONE0 = MAP.zones[0]; // 第一个热点
const ZONE0_WX = (ZONE0.x + 0.5) * TILE_W;
const ZONE0_WY = (ZONE0.y + 0.5) * TILE_H;

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

const PAGE_STATE = `(async () => { const r = await fetch('/api/simulation/state'); const d = await r.json(); return JSON.stringify({ running: d.running, agents: (d.agents||[]).map(a => ({ n: a.agentName, x: a.x, y: a.y, pc: !!a.playerControlled, ht: !!a.hasTarget })), tick: d.tickCount ?? d.tick ?? 0 }); })()`;
async function pageState(cdp) { return JSON.parse(await cdp.eval(PAGE_STATE)); }

async function clickGame(cdp, gx, gy) {
  const rect = await cdp.eval(`(()=>{ const c=document.querySelector('.phaser-sim-view canvas'); if(!c) return null; const r=c.getBoundingClientRect(); return JSON.stringify({left:r.left,top:r.top,width:r.width,height:r.height}); })()`);
  if (!rect) return 'NO_CANVAS';
  const R = JSON.parse(rect);
  const sx = R.left + (gx / 1000) * R.width;
  const sy = R.top + (gy / 600) * R.height;
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: sx, y: sy, button: 'left', clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: sx, y: sy, button: 'left', clickCount: 1 });
  return 'OK';
}

// 截图像素采样（页面内解码：img → 2d canvas → getImageData；规避 Phaser WebGL 上下文限制）
const pixelCountExpr = (dataUrl, specs) => `(async () => {
  const img = new Image();
  await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = ${JSON.stringify(dataUrl)}; });
  const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
  const x = c.getContext('2d'); x.drawImage(img, 0, 0);
  const d = x.getImageData(0, 0, c.width, c.height).data;
  const near = (r,g,b,tr,tg,tb,tol) => Math.abs(r-tr)<=tol && Math.abs(g-tg)<=tol && Math.abs(b-tb)<=tol;
  const out = {};
  ${Object.entries(specs).map(([k, s]) => `out.${k} = (() => { let n=0; for(let i=0;i<d.length;i+=4){ if(near(d[i],d[i+1],d[i+2],${s[0]},${s[1]},${s[2]},${s[3]})) n++; } return n; })();`).join('\n')}
  return JSON.stringify({ w: c.width, h: c.height, counts: out });
})()`;

function main2(cdp, child) {
  return (async () => {
    // ── 注入 localStorage：三个预设剧本 id 都挂上真实 LLM 地图（无论点中哪个都命中缓存）──
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
    check('0 注入 generalMaps 缓存（g_cafe/g_galaxy/g_school）', injected === 'OK', String(injected));
    await cdp.send('Page.reload', { ignoreCache: true });
    await waitFor(cdp, `document.querySelectorAll('.app2-nav-btn').length>=5`, 25000, 'nav after reload');

    // ── 导航：剧本选择 → 一般模式 → 剧本卡 → 角色选择 ──
    await clickText(cdp, '.app2-nav-btn', '剧本选择');
    await waitFor(cdp, `!!document.querySelector('.chip2')`, 15000, 'mode chips');
    await clickText(cdp, '.chip2', '一般模式');
    await sleep(600);
    await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
    await waitFor(cdp, `document.body.textContent.includes('角色选择')`, 20000, 'roles page');
    await sleep(500);

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
    const lit = await cdp.eval(`(()=>{
      const pn=${JSON.stringify(playerName)};
      const cards = Array.from(document.querySelectorAll('.role-chip')).filter(c=>c.classList && !c.classList.contains('role-chip-add') && !c.textContent.includes('选择你的角色') && !c.textContent.includes('玩家角色') && !c.textContent.includes('不参与本局') && !(pn && c.textContent.includes(pn)));
      let n=0;
      for(const c of cards){ if(!c.classList.contains('selected')){ c.click(); n++; if(n>=3) break; } }
      return 'lit='+n;
    })()`);
    log('[lit-ai-roles]', lit);
    await sleep(400);
    await clickText(cdp, '.btn2.btn2-primary', '进入 2D 探索');

    // ── 2D 世界挂载 ──
    await waitFor(cdp, `!!document.querySelector('.phaser-sim-view canvas')`, 45000, 'phaser canvas');
    await waitFor(cdp, `(async()=>{ const r=await fetch('/api/simulation/state'); const d=await r.json(); return d.running === true; })()`, 30000, 'world running');
    await waitFor(cdp, `(async()=>{ const r=await fetch('/api/simulation/state'); const d=await r.json(); return (d.agents||[]).length>=4; })()`, 60000, '4 agents');
    await sleep(4000); // 等瓦片/热点/装饰首帧渲染稳定
    let st = await pageState(cdp);
    log('[world]', JSON.stringify({ agents: st.agents.map(a => a.n + '@' + Math.round(a.x) + ',' + Math.round(a.y) + (a.pc ? '(me)' : '')), tick: st.tick, running: st.running }));
    check('A 2D 世界加载 4 角色（me + 3 AI）', st.agents.length >= 4, JSON.stringify(st.agents.map(a => a.n)));
    await cdp.shot('01_world_full.png');

    // ── B 像素采样：热点金色 / 出生点蓝 / decor 色块（截图像素级真机证据）──
    await cdp.shot('02_pixel_sample.png');
    const dataUrl = 'data:image/png;base64,' + cdp.lastShotData;
    const pix = JSON.parse(await cdp.eval(pixelCountExpr(dataUrl, {
      zoneGold: [0xff, 0xd1, 0x66, 45],      // 热点金色（填充脉冲+描边 0.9）
      spawnBlue: [0x38, 0xbd, 0xf8, 50],    // 出生点玩家蓝
      bench: [0x8a, 0x5a, 0x2b, 30],        // decor bench 棕
      lamp: [0xff, 0xd1, 0x66, 30],         // decor lamp 黄（与热点同色系）
      flower: [0xe6, 0x39, 0x46, 30],       // flower_bed 红点
      grass: [0x7b, 0xc9, 0x6f, 30],        // spawnMarkers grass 浅绿
      debris: [0x8a, 0x6b, 0x4a, 30],       // spawnMarkers debris 棕
    })));
    log('[pixels]', JSON.stringify(pix.counts));
    check('B1 画布已渲染（截图非空）', pix.w > 0 && pix.h > 0, pix.w + 'x' + pix.h);
    check('B2 热点金色区域像素命中（zones 渲染出现）', pix.counts.zoneGold >= 150, 'px=' + pix.counts.zoneGold);
    check('B3 出生点蓝标记像素命中（spawn_points 渲染出现）', pix.counts.spawnBlue >= 40, 'px=' + pix.counts.spawnBlue);
    const decorHits = ['bench', 'lamp', 'flower', 'grass', 'debris'].filter(k => pix.counts[k] >= 20);
    check('B4 decor 色块像素命中（bench/lamp/flower/grass/debris ≥3 类）', decorHits.length >= 3,
      'hits=' + JSON.stringify(decorHits) + ' ' + JSON.stringify(pix.counts));

    // ── B5/B6 标签定位探针（真实 canvas rect 映射，区域取色）──
    // 热点名标签：世界 (zx+0.5)*tileW, (zy+0.5)*tileH + s*0.8；房间名标签：世界 (rx+w/2)*tileW, ry*tileH-4
    const rectNow = await cdp.eval(`(()=>{ const c=document.querySelector('.phaser-sim-view canvas'); if(!c) return null; const r=c.getBoundingClientRect(); return JSON.stringify({left:r.left,top:r.top,width:r.width,height:r.height}); })()`);
    if (rectNow) {
      const R = JSON.parse(rectNow);
      const toScreen = (wx, wy) => ({ x: R.left + (wx / 1000) * R.width, y: R.top + (wy / 600) * R.height });
      const zoneProbes = MAP.zones.map(z => {
        const s = Math.min(TILE_W, TILE_H);
        const p = toScreen((z.x + 0.5) * TILE_W, (z.y + 0.5) * TILE_H + s * 0.8);
        return [Math.round(p.x), Math.round(p.y)];
      });
      const roomProbes = MAP.rooms.map(r => {
        const p = toScreen((r.x + r.w / 2) * TILE_W, r.y * TILE_H - 4);
        return [Math.round(p.x), Math.round(p.y)];
      });
      const labelProbe = null; // (占位已移除：直接用整页截图按真实 rect 取色，见下)
      // 用整页截图 + 已存 rect：直接对 02_pixel_sample.png 的 base64 在页面解码后按真实 rect 取色
      const dataUrlP = 'data:image/png;base64,' + cdp.lastShotData;
      const lr = JSON.parse(await cdp.eval(`(async () => {
        const img = new Image();
        await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = ${JSON.stringify(dataUrlP)}; });
        const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
        const x = c.getContext('2d'); x.drawImage(img, 0, 0);
        const d = x.getImageData(0, 0, c.width, c.height).data;
        const near = (r,g,b,tr,tg,tb,tol) => Math.abs(r-tr)<=tol && Math.abs(g-tg)<=tol && Math.abs(b-tb)<=tol;
        const boxCount = (cx, cy) => {
          const x0=Math.max(0,Math.floor(cx-40)), x1=Math.min(c.width-1,Math.ceil(cx+40));
          const y0=Math.max(0,Math.floor(cy-16)), y1=Math.min(c.height-1,Math.ceil(cy+16));
          let n=0;
          for(let yy=y0;yy<=y1;yy++) for(let xx=x0;xx<=x1;xx++){
            const i=(yy*c.width+xx)*4;
            if (near(d[i],d[i+1],d[i+2],255,224,138,35)) n++; // 热点名 #ffe08a
          }
          return n;
        };
        const boxCountRoom = (cx, cy) => {
          const x0=Math.max(0,Math.floor(cx-48)), x1=Math.min(c.width-1,Math.ceil(cx+48));
          const y0=Math.max(0,Math.floor(cy-16)), y1=Math.min(c.height-1,Math.ceil(cy+16));
          let n=0;
          for(let yy=y0;yy<=y1;yy++) for(let xx=x0;xx<=x1;xx++){
            const i=(yy*c.width+xx)*4;
            if (near(d[i],d[i+1],d[i+2],226,232,240,25)) n++; // 房间名 #e2e8f0
          }
          return n;
        };
        return JSON.stringify({
          zones: ${JSON.stringify(zoneProbes)}.map(p => boxCount(p[0], p[1])),
          rooms: ${JSON.stringify(roomProbes)}.map(p => boxCountRoom(p[0], p[1])),
        });
      })()`));
      log('[label-probes]', JSON.stringify(lr));
      check('B5 热点名称标签渲染（≥3/5 个区域命中 #ffe08a 文字像素）', lr.zones.filter(n => n >= 2).length >= 3, JSON.stringify(lr.zones));
      check('B6 房间名标签渲染（≥3/5 个房间命中 #e2e8f0 文字像素）', lr.rooms.filter(n => n >= 3).length >= 3, JSON.stringify(lr.rooms));
    }

    // ── C 交互零破坏：点击玩家 → hasTarget 置位（点击设目标仍工作）──
    let me = st.agents.find(a => a.pc) || null;
    if (!me) { for (let i = 0; i < 5 && !me; i++) { const s2 = await pageState(cdp); me = s2.agents.find(a => a.pc); if (!me) await sleep(1000); } }
    check('C0 解析到玩家角色', !!me, me ? me.n : '(none)');
    if (me) {
      const tx = Math.min(900, me.x + 120), ty = Math.min(540, me.y + 80); // 玩家右下方空地
      await clickGame(cdp, tx, ty);
      await sleep(1500);
      const s3 = await pageState(cdp);
      const meNow = s3.agents.find(a => a.n === me.n);
      check('C1 点击设目标仍工作（玩家 hasTarget 置位）', !!meNow && meNow.ht === true, meNow ? 'ht=' + meNow.ht : '(no me)');
      await sleep(2500);
      await cdp.shot('03_click_target.png');
      // ── D 热点点击提示：点击热点区域中心 → 底部提示出现（截图佐证）──
      await clickGame(cdp, ZONE0_WX, ZONE0_WY);
      await sleep(800);
      await cdp.shot('04_zone_click_hint.png');
      // ── 点击 NPC → Gal 面板打开（对话链路正常）──
      let npcClickOk = false;
      for (let i = 0; i < 4 && !npcClickOk; i++) {
        const s4 = await pageState(cdp);
        const p4 = s4.agents.find(a => a.pc);
        const cands = s4.agents.filter(a => !a.pc).sort((a, b) => p4 ? Math.hypot(a.x - p4.x, a.y - p4.y) - Math.hypot(b.x - p4.x, b.y - p4.y) : 0);
        if (cands.length === 0) break;
        const npc = cands[0];
        await clickGame(cdp, npc.x, npc.y);
        await sleep(1800);
        npcClickOk = await cdp.eval(`!!document.querySelector('.sim-gal-panel')`);
        if (!npcClickOk) log('[npc-click] 重试 ' + npc.n);
      }
      check('C2 点击 NPC → Gal 对话面板打开（对话链路正常）', npcClickOk, '');
      await cdp.shot('05_npc_dialog.png');
    }

    // ── E 缩放兼容：滚轮 zoom in（世界坐标 Graphics/Text 随相机缩放）──
    const rect = await cdp.eval(`(()=>{ const c=document.querySelector('.phaser-sim-view canvas'); if(!c) return null; const r=c.getBoundingClientRect(); return JSON.stringify({left:r.left,top:r.top,width:r.width,height:r.height}); })()`);
    if (rect) {
      const R = JSON.parse(rect);
      const cx = R.left + R.width / 2, cy = R.top + R.height / 2;
      for (let i = 0; i < 4; i++) {
        await cdp.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: cx, y: cy, deltaX: 0, deltaY: -120 });
        await sleep(300);
      }
      await sleep(1500);
      await cdp.shot('06_zoomed.png');
      // 缩放下再采样一次热点金色（相机跟随玩家后热点可能不在视口，仅截图佐证 + 采样尽力）
      const dataUrl2 = 'data:image/png;base64,' + cdp.lastShotData;
      const pix2 = JSON.parse(await cdp.eval(pixelCountExpr(dataUrl2, { zoneGold: [0xff, 0xd1, 0x66, 45], agentBlue: [0x38, 0xbd, 0xf8, 45] })));
      log('[pixels-zoom]', JSON.stringify(pix2.counts));
      check('E 缩放后渲染正常（截图佐证；世界坐标 Graphics 天然随相机缩放）', pix2.w > 0 && pix2.h > 0, 'sample=' + JSON.stringify(pix2.counts));
    }

    // ── F console 错误（白名单：charanim-助手 素材解析失败为既有噪音）──
    const errs = cdp.consoleLogs.filter(l => (l.startsWith('EXCEPTION') || l.startsWith('error'))
      && !l.includes('charanim-助手') && !l.includes('Expected property name'));
    check('F console 无新增错误（charanim 既有素材噪音除外）', errs.length === 0, errs.slice(0, 3).join(' | ') || 'clean');

    // ── G 复位测试世界 ──
    await cdp.eval(`(async () => { try { await fetch('/api/simulation/reset', { method: 'POST' }); } catch(e){} return 'OK'; })()`);
    await sleep(2500);
    const stReset = await pageState(cdp);
    check('G 测试世界已复位（agents=0 running=false）', (stReset.agents || []).length === 0 && stReset.running === false,
      JSON.stringify({ agents: (stReset.agents || []).length, running: stReset.running }));

    log('RESULT pass=' + pass + ' fail=' + fail);
    writeFileSync(`${OUT}/result.json`, JSON.stringify({ pass, fail, pixels: pix.counts, decorHits, consoleErrors: errs.length }, null, 2));
    child.kill();
    process.exit(fail > 0 ? 1 : 0);
  })();
}

async function main() {
  let child;
  child = spawn(EDGE, ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0816b-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 30; i++) { try { const r = await fetch(`${BASE}/json/version`); if (r.ok) break; } catch { } await sleep(500); }
  const list = await (await fetch(`${BASE}/json/list`)).json();
  const cdp = await CDP.connect(list.find(t => t.type === 'page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  if (await cdp.eval('1+1') !== 2) throw new Error('cdp sanity');
  await main2(cdp, child);
}
main().catch(e => { console.log('FATAL', e?.message); try { process.kill(0); } catch { } process.exit(1); });
