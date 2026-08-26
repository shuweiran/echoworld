/* cdp_p0814g_decor.mjs — P-0814-G（Phaser 多层渲染批次）装饰色块视觉验证（CDP 端到端）
 * ⚠️ 本批次标记 P-0814-G 与并行「前端消息显示顺序修复」批次撞标：tools/cdp_p0814g.mjs 已被该批次占用，
 *    本文件改名 cdp_p0814g_decor.mjs、证据目录改 tmp/p0814g_decor/ 避让（源文件零重叠）。
 * 前置：tools/static_proxy_p0814g.mjs 已在 4175 运行（新 dist + /api、/assets 透传 8000）。
 * 流程：demo2 剧本选择 → 一般模式 → 角色选择页「🗺️ 生成并预览地图」按钮 →
 *       CDP Fetch 拦截 POST /api/scenes/map，注入契约 v0.2 地图（objects/overlay/decor/
 *       spawnMarkers/tileProps 全键）→ PhaserScriptMapView 真实渲染 → 截图像素采样断言
 *       装饰色块颜色命中（树冠深绿/灯黄/长椅棕/石柱灰/栅栏棕/小草浅绿/杂物棕/花彩色/水蓝）。
 * 证据：tmp/p0814g_decor/*.png + progress.log；退出码 0=全 PASS。
 */
import { spawn } from 'node:child_process';
import { writeFileSync, mkdirSync, appendFileSync } from 'node:fs';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9253;
const BASE = `http://127.0.0.1:${PORT}`;
const APP = 'http://127.0.0.1:4175/';
const OUT = 'D:/echoworld/tmp/p0814g_decor';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
let pass = 0, fail = 0;
const check = (n, c, d = '') => { if (c) { pass++; log('PASS ' + n + (d ? ' :: ' + d : '')); } else { fail++; log('FAIL ' + n + (d ? ' :: ' + d : '')); } };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && c.pending.has(m.id)) { c.pending.get(m.id)(m); c.pending.delete(m.id); } };
    return c;
  }
  send(method, params = {}) { const id = ++this.id; return new Promise((resolve, reject) => { const t = setTimeout(() => { this.pending.delete(id); reject(new Error('timeout ' + method)); }, 60000); this.pending.set(id, (m) => { clearTimeout(t); resolve(m); }); try { this.ws.send(JSON.stringify({ id, method, params })); } catch (e) { clearTimeout(t); reject(e); } }); }
  async eval(expr) { const r = await this.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true }); if (r.result?.exceptionDetails) throw new Error('eval: ' + JSON.stringify(r.result.exceptionDetails.exception || '').slice(0, 300)); return r.result?.result?.value; }
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); log('[shot]', f); } } catch (e) { } }
}
async function waitFor(cdp, expr, t = 60000, label = '') { const t0 = Date.now(); while (Date.now() - t0 < t) { try { const v = await cdp.eval(expr); if (v) return v; } catch { } await sleep(800); } throw new Error('timeout ' + label); }
async function clickText(cdp, sel, text) { const r = await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`); if (String(r).startsWith('MISS')) throw new Error('click miss ' + sel + ' ' + text); }

/* ── 构造契约 v0.2 地图（16×10：两房间 + 树/栅栏/花坛 objects + canopy 遮罩 + decor 四类 + 标记 + 水池） ── */
function buildV02Map() {
  const W = 16, H = 10;
  const ground = [], collision = [];
  for (let y = 0; y < H; y++) { ground.push(new Array(W).fill(3)); collision.push(new Array(W).fill(0)); }
  // 房间 A（客厅）：x1..6 y1..4，房间 B（书房）：x9..14 y5..8；墙圈 + 地板
  const carve = (x0, y0, w, h) => {
    for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) {
      const wall = x === x0 || y === y0 || x === x0 + w - 1 || y === y0 + h - 1;
      ground[y][x] = wall ? 2 : 1;
      collision[y][x] = wall ? 1 : 0;
    }
  };
  carve(1, 1, 6, 4); carve(9, 5, 6, 4);
  const obj = (y) => new Array(W).fill(null);
  const objects = Array.from({ length: H }, (_, y) => obj(y));
  objects[2][3] = 'tree_oak';   // 树（房间 A 内）
  objects[3][13] = 'tree_oak';  // 树（房间 B 旁）
  objects[5][5] = 'fence'; objects[5][6] = 'fence'; objects[5][7] = 'fence'; // 栅栏行
  objects[4][2] = 'flower_bed';
  const overlay = Array.from({ length: H }, (_, y) => obj(y));
  overlay[2][3] = 'canopy';     // 树冠遮罩永远盖住角色
  return {
    map_version: 1, map_id: 'p0814g_v02', name: 'P-0814-G 装饰验证', theme: '色块方案视觉验证',
    tile_size: 16, width: W, height: H,
    layers: { ground, collision, objects, overlay },
    rooms: [
      { id: 'living', name: '客厅', x: 1, y: 1, w: 6, h: 4 },
      { id: 'study', name: '书房', x: 9, y: 5, w: 6, h: 4 },
    ],
    corridors: [],
    zones: [
      { id: 'z1', name: '茶几', type: 'search', x: 2, y: 2, radius: 1, clue_location: '客厅' },
      { id: 'z2', name: '书桌', type: 'search', x: 11, y: 6, radius: 1, clue_location: '书房' },
    ],
    spawn_points: [
      { id: 'sp_p', type: 'player', x: 8, y: 5 },
      { id: 'sp_n1', type: 'npc', x: 4, y: 3 },
      { id: 'sp_n2', type: 'npc', x: 11, y: 7 },
    ],
    decor: [
      { id: 'd1', type: 'pillar', tile: [10, 6] },
      { id: 'd2', type: 'bench', tile: [12, 6] },
      { id: 'd3', type: 'lamp', tile: [13, 6] },
      { id: 'd4', type: 'flower_bed', tile: [10, 8] },
    ],
    spawnMarkers: {
      grass: [[1, 1], [1, 2], [14, 1], [2, 8], [13, 4], [0, 0]],
      debris: [[15, 9], [1, 9]],
    },
    tileProps: { '7,2': { water: true }, '8,2': { water: true }, '7,3': { water: true }, '8,3': { water: true }, '0,0': { blocked: true } },
    warps: [],
    generator: { kind: 'mock', note: 'P-0814-G CDP 注入' },
  };
}
const V02_MAP = JSON.stringify(buildV02Map());

let child;
async function main() {
  child = spawn(EDGE, ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${PORT}`, '--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-p0814g-decor-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 30; i++) { try { const r = await fetch(`${BASE}/json/version`); if (r.ok) break; } catch { } await sleep(500); }
  const list = await (await fetch(`${BASE}/json/list`)).json();
  let page = list.find(t => t.type === 'page');
  const cdp = await CDP.connect(page.webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  const consoleErrors = [];
  cdp.ws.addEventListener('message', (ev) => {
    try {
      const m = JSON.parse(ev.data);
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
        const args = (m.params.args || []).map(a => a.value !== undefined ? String(a.value) : (a.description || a.type || '')).slice(0, 6);
        consoleErrors.push(args.join(' | ').slice(0, 200));
      }
    } catch { }
  });

  // ── Fetch 拦截：POST /api/scenes/map → 注入 v0.2 地图 ──
  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*api/scenes/map*', requestStage: 'Request' }] });
  let intercepted = 0;
  cdp.ws.addEventListener('message', async (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method !== 'Fetch.requestPaused') return;
    const req = m.params;
    intercepted++;
    await cdp.send('Fetch.fulfillRequest', {
      requestId: req.requestId,
      responseCode: 200,
      responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
      body: Buffer.from(JSON.stringify({ map: JSON.parse(V02_MAP), generator: { kind: 'mock' }, validation: { ok: true }, fallback: [] })).toString('base64'),
    });
    log('[fetch-intercepted]', req.request.url);
  });

  // ── 进入 demo2 → 一般模式 → 角色选择 → 打开地图预览 ──
  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `document.querySelectorAll('.app2-nav-btn').length>=5`, 20000, 'nav');
  await clickText(cdp, '.app2-nav-btn', '剧本选择');
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 15000, 'script page');
  await clickText(cdp, '.chip2', '一般模式');
  await sleep(500);
  await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp, `document.body.textContent.includes('角色选择')`, 15000, 'roles');
  await sleep(400);
  await clickText(cdp, '.chip2', '地图');
  await waitFor(cdp, `!!document.querySelector('.modal-mask')`, 10000, 'map modal');
  await waitFor(cdp, `!!document.querySelector('.script-map-host canvas')`, 30000, 'phaser canvas');
  await sleep(2500); // 等瓦片/装饰首帧渲染 + 相机稳定

  check('A 拦截到 /api/scenes/map 请求并注入 v0.2 地图', intercepted >= 1, 'count=' + intercepted);
  await cdp.shot('map_modal.png');

  // ── 像素采样：装饰色块颜色命中 ──
  const pix = await cdp.eval(`(()=>{
    const c=document.querySelector('.script-map-host canvas'); if(!c) return 'NOCANVAS';
    const ctx=c.getContext('2d'); if(!ctx) return 'NOCTX';
    const {width:w,height:h}=c; const img=ctx.getImageData(0,0,w,h).data;
    const near=(r,g,b,tr,tg,tb,tol)=>{ return Math.abs(r-tr)<=tol&&Math.abs(g-tg)<=tol&&Math.abs(b-tb)<=tol; };
    const count=(tr,tg,tb,tol)=>{ let n=0; for(let i=0;i<img.length;i+=4){ if(near(img[i],img[i+1],img[i+2],tr,tg,tb,tol)) n++; } return n; };
    return JSON.stringify({
      w,h,
      counts: {
        tree:count(0x1e,0x56,0x31,28), lamp:count(0xff,0xd1,0x66,26), bench:count(0x8a,0x5a,0x2b,26),
        pillar:count(0x9a,0xa5,0xb1,26), fence:count(0x7c,0x5a,0x34,26), grass:count(0x7b,0xc9,0x6f,26),
        debris:count(0x8a,0x6b,0x4a,24), flower1:count(0xe6,0x39,0x46,24), flower2:count(0xf4,0xa2,0x61,24),
        blueTint:(()=>{ let n=0; for(let i=0;i<img.length;i+=4){ const r=img[i],g=img[i+1],b=img[i+2]; if(b>r+45&&b>g+45) n++; } return n; })()
      }
    });
  })()`);
  log('pixel sample:', String(pix).slice(0, 600));
  const P = JSON.parse(pix);
  check('B 画布已渲染（非空）', P.w > 0 && P.h > 0, P.w + 'x' + P.h);
  check('C 树冠深绿色块命中（tree_oak）', P.counts.tree >= 80, 'px=' + P.counts.tree);
  check('D 灯黄色块命中（lamp）', P.counts.lamp >= 40, 'px=' + P.counts.lamp);
  check('E 长椅棕色块命中（bench）', P.counts.bench >= 60, 'px=' + P.counts.bench);
  check('F 石柱灰色块命中（pillar）', P.counts.pillar >= 60, 'px=' + P.counts.pillar);
  check('G 栅栏棕色块命中（fence 3 格）', P.counts.fence >= 80, 'px=' + P.counts.fence);
  check('H 小草浅绿色块命中（grass 标记）', P.counts.grass >= 40, 'px=' + P.counts.grass);
  check('I 杂物棕色块命中（debris 标记）', P.counts.debris >= 30, 'px=' + P.counts.debris);
  check('J 花坛彩色点命中（flower_bed 红/橙）', P.counts.flower1 + P.counts.flower2 >= 20, 'px=' + (P.counts.flower1 + P.counts.flower2));
  check('K 水池蓝色半透明叠加命中（tileProps water）', P.counts.blueTint >= 60, 'px=' + P.counts.blueTint);
  check('L 控制台 0 错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));

  await cdp.shot('map_decor_close.png');
  await cdp.send('Fetch.disable');
  await sleep(300);
  child.kill();
  log('RESULT pass=' + pass + ' fail=' + fail);
  process.exit(fail === 0 ? 0 : 1);
}
main().catch(e => { log('FATAL', e.message); try { child.kill(); } catch { } process.exit(1); });
