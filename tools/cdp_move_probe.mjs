/* cdp_move_probe.mjs — 玩家地图运动控制实测探针（2026-08-15 聚焦调研）
 * 前置：8000 后端运行中（PID 3268）；本工具内置静态代理（4182 = dist 新 bundle，/api 透传 8000，
 *       POST /api/scenes/map 被拦截 500 → 前端兜底 script.map（BSP 确定性地图，零 LLM 成本））
 * 流程：剧本选择 → 一般模式 → g_cafe → 选玩家角色 + 带玩家 + 2D 探索 → 进入 2D
 *       → 测试 A 远点点击延迟 / B 连续多点 / C 撞墙 / D WASD / E 外推 A/B（页面内注入 vx/vy）
 * 产出：tmp/move_probe/report.json + 截图；结束复位世界（running=false agents=0）
 */
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { request as httpRequest } from 'node:http';
import { readFileSync, writeFileSync, mkdirSync, appendFileSync, existsSync, statSync } from 'node:fs';
import { join, extname, normalize } from 'node:path';
import zlib from 'node:zlib';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const CDP_PORT = 9261;
const APP_PORT = 4182;
const APP = `http://127.0.0.1:${APP_PORT}/`;
const BACKEND = 'http://localhost:8000';
const OUT = 'D:/echoworld/tmp/move_probe';
mkdirSync(OUT, { recursive: true });
const PROG = `${OUT}/progress.log`;
appendFileSync(PROG, '\n==== ' + new Date().toISOString() + ' ====\n');
const log = (...a) => { const l = '[' + new Date().toISOString().slice(11, 19) + '] ' + a.join(' '); appendFileSync(PROG, l + '\n'); console.log(l); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

/* ── 静态代理（dist + API 透传） ─────────────────────────────── */
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };
const DIST = 'D:/echoworld/frontend/dist';
createServer((req, res) => {
  const url = req.url || '/';
  if (url.startsWith('/api/')) {
    if (url.startsWith('/api/scenes/map')) { res.writeHead(500, { 'access-control-allow-origin': '*' }); res.end('intercepted'); return; }
    const headers = { ...req.headers }; delete headers.origin; headers.host = 'localhost:8000';
    headers['content-type'] = headers['content-type'] || 'application/json';
    const preq = httpRequest(BACKEND + url, { method: req.method, headers }, (pres) => {
      res.writeHead(pres.statusCode || 200, { ...pres.headers, 'access-control-allow-origin': '*' });
      pres.pipe(res);
    });
    preq.on('error', () => { res.writeHead(502); res.end('proxy err'); });
    req.pipe(preq); return;
  }
  const clean = normalize(url.split('?')[0]).replace(/^([/\\])+/, '');
  const file = join(DIST, clean || 'index.html');
  if (existsSync(file) && statSync(file).isFile()) { res.writeHead(200, { 'content-type': MIME[extname(file).toLowerCase()] || 'application/octet-stream' }); res.end(readFileSync(file)); return; }
  res.writeHead(404); res.end('nf: ' + url);
}).listen(APP_PORT, () => log('[proxy] dist on', APP_PORT));

/* ── PNG 解码（8-bit RGB/RGBA 非隔行） ───────────────────────── */
function decodePNG(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not png');
  let pos = 8, w = 0, h = 0, bitDepth = 0, colorType = 0, interlace = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos); const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); bitDepth = data[8]; colorType = data[9]; interlace = data[12]; }
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  if (interlace !== 0 || bitDepth !== 8) throw new Error('unsupported png: depth=' + bitDepth + ' interlace=' + interlace);
  const ch = colorType === 6 ? 4 : colorType === 2 ? 3 : colorType === 0 ? 1 : 0;
  if (!ch) throw new Error('unsupported colorType ' + colorType);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * ch; const out = Buffer.alloc(w * h * 4);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)];
    const line = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let x = 0; x < stride; x++) {
      const a = x >= ch ? line[x - ch] : 0, b = prev[x], c = x >= ch ? prev[x - ch] : 0;
      let v = line[x];
      if (f === 1) v = (v + a) & 0xff;
      else if (f === 2) v = (v + b) & 0xff;
      else if (f === 3) v = (v + ((a + b) >> 1)) & 0xff;
      else if (f === 4) { const p = a + b - c; const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 0xff; }
      line[x] = v;
    }
    for (let x = 0; x < w; x++) { const i = x * ch; out[(y * w + x) * 4] = line[i]; out[(y * w + x) * 4 + 1] = ch > 1 ? line[i + 1] : line[i]; out[(y * w + x) * 4 + 2] = ch > 2 ? line[i + 2] : line[i]; out[(y * w + x) * 4 + 3] = ch > 3 ? line[i + 3] : 255; }
    prev = line;
  }
  return { w, h, data: out };
}

/* ── 页面注入（导航前） ───────────────────────────────────────── */
const INSTR = `(()=>{
  window.__mv = { snaps: [], fetches: [], rAF: [], t0: performance.now() };
  // fetch 记录（target / move-dir）
  const of = window.fetch;
  window.fetch = function(...args){
    const u = String(args[0] || '');
    if (u.includes('/api/simulation/target') || u.includes('/api/simulation/move-dir')) {
      window.__mv.fetches.push({ t: Date.now(), url: u.split('?')[0] });
    }
    return of.apply(this, args);
  };
  // SSE world_snapshot 记录 + 可选 vx/vy 注入（A/B 外推测试用）
  window.__mvExtrap = { enabled: false, prev: null };
  const origAE = EventSource.prototype.addEventListener;
  EventSource.prototype.addEventListener = function(type, cb, ...rest){
    if (type === 'world_snapshot') {
      const wrap = (e) => {
        try {
          let d = JSON.parse(e.data);
          if (window.__mvExtrap.enabled && Array.isArray(d.agents)) {
            const now = Date.now();
            const prev = window.__mvExtrap.prev;
            for (const a of d.agents) {
              if (prev && prev[a.agentName] && prev[a.agentName].x !== undefined) {
                const dt = Math.max(0.05, (now - prev[a.agentName].t) / 1000);
                a.vx = (a.x - prev[a.agentName].x) / dt;
                a.vy = (a.y - prev[a.agentName].y) / dt;
              } else { a.vx = 0; a.vy = 0; }
            }
            const np = {}; for (const a of d.agents) np[a.agentName] = { x: a.x, y: a.y, t: now };
            window.__mvExtrap.prev = np;
          }
          const agents = (d.agents || []).map(a => ({ n: a.agentName, x: a.x, y: a.y, vx: a.vx, vy: a.vy, ht: a.hasTarget, pc: a.playerControlled, sp: a.moveSpeed }));
          const obs = (d.obstacles || []).map(o => ({ type: o.type, x: o.x, y: o.y, width: o.width, height: o.height }));
          window.__mv.snaps.push({ t: Date.now(), tick: d.tick, agents, obs });
          if (window.__mv.snaps.length > 5000) window.__mv.snaps.splice(0, 500);
        } catch (err) {}
        try { cb.call(this, e); } catch (err) {}
      };
      return origAE.call(this, type, wrap, ...rest);
    }
    return origAE.apply(this, arguments);
  };
  // rAF 帧间隔
  let last = performance.now();
  function loop(t){ window.__mv.rAF.push(t - last); last = t; if (window.__mv.rAF.length > 30000) window.__mv.rAF.splice(0, 5000); requestAnimationFrame(loop); }
  requestAnimationFrame(loop);
  // 工具函数（页面内）：玩家最新状态 / 无障碍直线目标 / 画布 rect
  window.__mvUtil = {
    lastSnap: () => window.__mv.snaps[window.__mv.snaps.length - 1],
    player: () => {
      for (let i = window.__mv.snaps.length - 1; i >= 0; i--) {
        const a = (window.__mv.snaps[i].agents || []).find(x => x.pc);
        if (a) return { ...a, t: window.__mv.snaps[i].t };
      }
      return null;
    },
    obstacles: () => { const s = window.__mv.lastSnap(); return s && s.obs ? s.obs : []; },
    canvasRect: () => { const c = document.querySelector('.phaser-sim-view canvas'); if (!c) return null; const r = c.getBoundingClientRect(); return { left: r.left, top: r.top, width: r.width, height: r.height }; },
  };
})();`;

/* ── CDP 封装 ────────────────────────────────────────────────── */
class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); this.consoleLogs = []; }
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
    if (r.result?.exceptionDetails) throw new Error('eval: ' + JSON.stringify(r.result.exceptionDetails.exception || r.result.exceptionDetails.text || '').slice(0, 300));
    return r.result?.result?.value;
  }
  async shot(f) { try { const r = await this.send('Page.captureScreenshot', { format: 'png' }); if (r.result?.data) { writeFileSync(`${OUT}/${f}`, Buffer.from(r.result.data, 'base64')); return Buffer.from(r.result.data, 'base64'); } } catch (e) { log('[shot fail]', f, e.message); } return null; }
}

async function waitFor(cdp, expr, t = 40000, label = '') {
  const t0 = Date.now();
  while (Date.now() - t0 < t) {
    try { const v = await cdp.eval(expr); if (v) return v; } catch { }
    await sleep(600);
  }
  throw new Error('timeout ' + label);
}
async function clickText(cdp, sel, text) {
  const r = await cdp.eval(`(()=>{ const els=Array.from(document.querySelectorAll(${JSON.stringify(sel)})); const h=els.filter(e=>e.textContent&&e.textContent.includes(${JSON.stringify(text)})); if(!h[0]) return 'MISS'; h[0].click(); return 'OK'; })()`);
  if (String(r).startsWith('MISS')) throw new Error('click miss ' + sel + ' ' + text);
  return r;
}

/* ── 像素跟踪（玩家圆点） ─────────────────────────────────────── */
const AGENT_COLORS = ['#38bdf8', '#f472b6', '#a78bfa', '#34d399', '#fb923c', '#f87171', '#e879f9', '#2dd4bf'];
function agentColor(name) { let h = 0; for (let i = 0; i < name.length; i++) h = ((h << 5) - h) + name.charCodeAt(i); return AGENT_COLORS[Math.abs(h) % AGENT_COLORS.length]; }
function hexToRgb(hex) { return [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)]; }

function findClusters(img, w, h, r, g, b, tol = 3) {
  const visited = new Uint8Array(w * h); const clusters = [];
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const i = (y * w + x) * 4;
    if (visited[y * w + x]) continue;
    if (Math.abs(img[i] - r) > tol || Math.abs(img[i + 1] - g) > tol || Math.abs(img[i + 2] - b) > tol) continue;
    const q = [[x, y]]; visited[y * w + x] = 1; let sx = 0, sy = 0, cnt = 0;
    while (q.length) { const [cx, cy] = q.pop(); sx += cx; sy += cy; cnt++;
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const nx = cx + dx, ny = cy + dy;
        if (nx >= 0 && ny >= 0 && nx < w && ny < h && !visited[ny * w + nx]) { const ni = (ny * w + nx) * 4;
          if (Math.abs(img[ni] - r) <= tol && Math.abs(img[ni + 1] - g) <= tol && Math.abs(img[ni + 2] - b) <= tol) { visited[ny * w + nx] = 1; q.push([nx, ny]); } } } }
    if (cnt >= 8) clusters.push({ cx: sx / cnt, cy: sy / cnt, count: cnt });
  }
  return clusters;
}

/* ── 工具：定位玩家圆点屏幕位置 ───────────────────────────────── */
let lastDot = null;
function locatePlayerDot(pngBuf, playerColor, canvasRect) {
  const { w, h, data } = decodePNG(pngBuf);
  const [r, g, b] = hexToRgb(playerColor);
  const clusters = findClusters(data, w, h, r, g, b);
  if (clusters.length === 0) return null;
  // 选择：尺寸接近圆点（直径~22px 面积 300-1500px²）且离上次位置最近；无上次 → 最大簇
  const cx = canvasRect.left + canvasRect.width / 2, cy = canvasRect.top + canvasRect.height / 2;
  let best = null, bestScore = Infinity;
  for (const c of clusters) {
    if (c.count < 30 || c.count > 4000) continue;
    const px = lastDot ? lastDot.cx : cx, py = lastDot ? lastDot.cy : cy;
    const dist = Math.hypot(c.cx - px, c.cy - py);
    const score = dist + Math.abs(c.count - 350) * 0.05;
    if (score < bestScore) { bestScore = score; best = c; }
  }
  if (!best) return null;
  lastDot = best;
  // 屏幕 → 世界坐标
  const wx = (best.cx - canvasRect.left) / canvasRect.width * 1000;
  const wy = (best.cy - canvasRect.top) / canvasRect.height * 600;
  return { sx: best.cx, sy: best.cy, wx, wy, count: best.count };
}

/* ── 测试执行 ─────────────────────────────────────────────────── */
let child;
async function main() {
  // 前置复位（防上次失败残留世界）
  try { await fetch(BACKEND + '/api/simulation/reset', { method: 'POST' }); } catch { }
  child = spawn(EDGE, ['--headless=new', '--no-proxy-server', '--disable-gpu', '--no-sandbox',
    `--remote-debugging-port=${CDP_PORT}`, '--window-size=1440,900',
    '--user-data-dir=C:\\Temp\\edge-move-' + Date.now(), 'about:blank'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 30; i++) { try { const r = await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`); if (r.ok) break; } catch { } await sleep(500); }
  const list = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json();
  const cdp = await CDP.connect(list.find(t => t.type === 'page').webSocketDebuggerUrl);
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: INSTR });
  if (await cdp.eval('1+1') !== 2) throw new Error('cdp sanity');

  await cdp.send('Page.navigate', { url: APP });
  await waitFor(cdp, `document.querySelectorAll('.app2-nav-btn').length>=5`, 25000, 'nav');
  await clickText(cdp, '.app2-nav-btn', '剧本选择');
  await waitFor(cdp, `!!document.querySelector('.chip2')`, 15000, 'mode chips');
  await clickText(cdp, '.chip2', '一般模式');
  await sleep(500);
  await cdp.eval(`(()=>{ const e=document.querySelector('.script-item'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await waitFor(cdp, `document.body.textContent.includes('角色选择')`, 20000, 'roles page');
  await sleep(400);

  // 选玩家角色（打开选择器 → 点第一个角色）
  await clickText(cdp, '.role-chip', '选择你的角色');
  await waitFor(cdp, `!!document.querySelector('.modal-mask .role-chip')`, 8000, 'player picker');
  await cdp.eval(`(()=>{ const e=document.querySelector('.modal-mask .role-chip'); if(e){e.click();return 'OK';} return 'NO'; })()`);
  await sleep(400);
  // 带玩家开关（store 默认已是 true；仅在未勾选时点击）
  const cb = await cdp.eval(`(()=>{ const c=document.querySelector('input[type=checkbox]'); if(!c) return 'NO'; if(!c.checked){ c.click(); return 'CHECKED'; } return 'ALREADY'; })()`);
  log('[withPlayer]', cb);
  await sleep(300);
  // 2D 探索模式
  await clickText(cdp, '.chip2', '2D 探索');
  await sleep(300);
  await clickText(cdp, '.roles-footer button', '进入 2D 探索');
  await waitFor(cdp, `!!document.querySelector('.phaser-sim-view canvas')`, 60000, 'phaser canvas');
  await waitFor(cdp, `document.body.textContent.includes('SSE 已连接') || document.body.textContent.includes('角色已加载')`, 30000, 'sse');
  await waitFor(cdp, `window.__mv && window.__mv.snaps.length > 3`, 30000, 'snapshots');
  await cdp.eval(`(()=>{ const e=document.querySelector('.phaser-sim-view'); if(e) e.scrollIntoView(); return 'OK'; })()`);
  await sleep(1500);
  await cdp.shot('00_world_loaded.png');
  const player = await cdp.eval(`JSON.stringify(window.__mvUtil.player())`);
  const playerInfo = JSON.parse(player);
  if (!playerInfo || !playerInfo.n) throw new Error('no playerControlled agent found — world has no player');
  log('[player]', playerInfo.n, 'pos=(' + Math.round(playerInfo.x) + ',' + Math.round(playerInfo.y) + ') speed=' + playerInfo.sp, 'color=' + agentColor(playerInfo.n));
  const color = agentColor(playerInfo.n);

  // ── 测试 A：远点点击延迟 ──
  log('=== A: far click latency ===');
  // 页面内计算无障碍直线目标（+250px 距离）
  const target = await cdp.eval(`(()=>{
    const p = window.__mvUtil.player(); if(!p) return null;
    const obs = window.__mvUtil.lastSnap().obs || [];
    const cands = [ [250,0],[-250,0],[0,250],[0,-250],[250,250],[-250,250],[250,-250],[-250,-250] ];
    const segHit = (x1,y1,x2,y2,o) => {
      const ox=o.x, oy=o.y, ow=o.width, oh=o.height;
      const denom = (x2-x1)||1e-9, num = (y2-y1)||1e-9;
      for (let f=0; f<=1; f+=0.02) { const px=x1+(x2-x1)*f, py=y1+(y2-y1)*f;
        if (px>=ox-14 && px<=ox+ow+14 && py>=oy-14 && py<=oy+oh+14) return true; }
      return false;
    };
    for (const [dx,dy] of cands) {
      const tx = Math.max(20, Math.min(980, p.x+dx)), ty = Math.max(20, Math.min(580, p.y+dy));
      let hit = false;
      for (const o of obs) if (segHit(p.x,p.y,tx,ty,o)) { hit = true; break; }
      if (!hit) return { x: tx, y: ty };
    }
    return { x: Math.max(20, Math.min(980, p.x+250)), y: Math.max(20, Math.min(580, p.y+0)) };
  })()`);
  log('[A target]', JSON.stringify(target));
  const rect = JSON.parse(await cdp.eval(`JSON.stringify(window.__mvUtil.canvasRect())`));
  const sx = rect.left + target.x / 1000 * rect.width, sy = rect.top + target.y / 600 * rect.height;
  // 基线：点击前玩家圆点
  await sleep(300);
  const baseShot = await cdp.shot('a0_base.png');
  const baseDot = locatePlayerDot(baseShot, color, rect);
  log('[A base dot]', baseDot ? Math.round(baseDot.wx) + ',' + Math.round(baseDot.wy) : 'NOT FOUND');
  if (!baseDot) throw new Error('player dot not found in screenshot');
  lastDot = { cx: baseDot.sx, cy: baseDot.sy };
  // 清空快照日志，开始采样
  await cdp.eval(`window.__mv.snaps = []; window.__mv.fetches = [];`);
  const tClick = Date.now();
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: Math.round(sx), y: Math.round(sy), button: 'left', buttons: 1, clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: Math.round(sx), y: Math.round(sy), button: 'left', buttons: 0, clickCount: 1 });
  const tDispatched = Date.now();
  log('[A click dispatched] target=(' + Math.round(target.x) + ',' + Math.round(target.y) + ') screen=(' + Math.round(sx) + ',' + Math.round(sy) + ')');
  // 采样：截图每 ~100ms × 3s + 快照
  const samples = [];
  const tEnd = Date.now() + 3200;
  let prevDot = baseDot, prevSample = null, n = 0;
  while (Date.now() < tEnd) {
    const shotBuf = await cdp.shot('a_f_' + String(n++).padStart(2, '0') + '.png');
    if (shotBuf) {
      const dot = locatePlayerDot(shotBuf, color, rect);
      const now = Date.now();
      if (dot) {
        if (prevDot) {
          const dWorld = Math.hypot(dot.wx - prevDot.wx, dot.wy - prevDot.wy);
          samples.push({ t: now, wx: dot.wx, wy: dot.wy, dWorld, moved: dWorld > 1.5 });
        }
        prevDot = dot;
      }
      if (!prevSample) prevSample = { t: now, wx: dot ? dot.wx : null };
    }
    await sleep(100);
  }
  await sleep(400);
  const snapData = await cdp.eval(`JSON.stringify(window.__mv.snaps.map(s => ({ t: s.t, agents: s.agents.filter(a => a.n === ${JSON.stringify(playerInfo.n)}).map(a => ({ x: a.x, y: a.y, ht: a.ht })) })))`);
  const snaps = JSON.parse(snapData);
  const preClick = playerInfo; // 快照在清空前的最后状态
  // 分析：服务器端移动起点 / 页到达 / 视觉
  let tServerMove = null, tArriveMove = null, tServer10 = null;
  for (const s of snaps) {
    const a = s.agents[0]; if (!a) continue;
    if (tServerMove === null && Math.hypot(a.x - preClick.x, a.y - preClick.y) >= 2) tServerMove = s.t;
    if (tServer10 === null && Math.hypot(a.x - preClick.x, a.y - preClick.y) >= 10) tServer10 = s.t;
  }
  // 视觉：第一个 moved 采样
  let tVisual = null;
  for (const s of samples) { if (s.moved) { tVisual = s.t; break; } }
  const fetches = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.fetches)`));
  log('[A metrics] tClick=' + tClick,
    'fetchTarget=' + (fetches[0] ? fetches[0].t - tClick : 'NONE') + 'ms',
    'tServerMove=' + (tServerMove ? tServerMove - tClick : 'NONE') + 'ms',
    'tServer10=' + (tServer10 ? tServer10 - tClick : 'NONE') + 'ms',
    'tVisual=' + (tVisual ? tVisual - tClick : 'NONE') + 'ms',
    'snapCount=' + snaps.length);
  const resA = { click: tClick, dispatched: tDispatched, fetchTarget: fetches[0] ? fetches[0].t - tClick : null, tServerMove: tServerMove ? tServerMove - tClick : null, tServer10: tServer10 ? tServer10 - tClick : null, tVisual: tVisual ? tVisual - tClick : null, snapCount: snaps.length };

  // ── 测试 B：连续多点点击 ──
  log('=== B: multi-click tracking ===');
  await sleep(1500);
  const p2 = JSON.parse(await cdp.eval(`JSON.stringify(window.__mvUtil.player())`));
  await cdp.eval(`window.__mv.snaps = []; window.__mv.fetches = [];`);
  const clicks = [];
  const targets = [];
  for (const [dx, dy] of [[180, -120], [-160, 140], [140, 120]]) {
    targets.push({ x: Math.max(20, Math.min(980, p2.x + dx)), y: Math.max(20, Math.min(580, p2.y + dy)) });
  }
  const rectB = JSON.parse(await cdp.eval(`JSON.stringify(window.__mvUtil.canvasRect())`));
  const tB0 = Date.now();
  for (const tg of targets) {
    const cx = rectB.left + tg.x / 1000 * rectB.width, cy = rectB.top + tg.y / 600 * rectB.height;
    await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: Math.round(cx), y: Math.round(cy), button: 'left', buttons: 1, clickCount: 1 });
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: Math.round(cx), y: Math.round(cy), button: 'left', buttons: 0, clickCount: 1 });
    clicks.push(Date.now() - tB0);
    await sleep(450);
  }
  await sleep(2000);
  const bSnaps = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.snaps.map(s => ({ t: s.t, a: (s.agents.find(x => x.n === ${JSON.stringify(playerInfo.n)})) })).filter(s => s.a))`));
  const bFetches = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.fetches)`));
  // 检查：玩家目标是否跟随最后点击（服务器 hasTarget/最终位置）
  const lastSnapB = bSnaps[bSnaps.length - 1];
  const targetB = JSON.parse(await cdp.eval(`JSON.stringify(window.__mvUtil.player())`));
  log('[B] clickOffsets=' + JSON.stringify(clicks), 'fetches=' + bFetches.length, 'finalPos=(' + Math.round(lastSnapB.a.x) + ',' + Math.round(lastSnapB.a.y) + ') hasTarget=' + lastSnapB.a.ht, 'lastTarget=(' + Math.round(targets[2].x) + ',' + Math.round(targets[2].y) + ')');
  await cdp.shot('b_multi.png');

  // ── 测试 C：撞墙 ──
  log('=== C: wall collision ===');
  await sleep(1500);
  const cState = JSON.parse(await cdp.eval(`JSON.stringify({ player: window.__mvUtil.player(), obs: (window.__mvUtil.lastSnap().obs || []) })`));
  // 找最近的实心障碍，目标 = 障碍中心（墙内不可达）
  const obs = cState.obs.filter(o => o.type === 'WALL' || o.type === 'TREE' || o.type === 'BUILDING');
  let wallTarget = null, wallOb = null;
  let bestD = Infinity;
  for (const o of obs) {
    const ocx = o.x + o.width / 2, ocy = o.y + o.height / 2;
    const d = Math.hypot(ocx - cState.player.x, ocy - cState.player.y);
    if (d > 150 && d < bestD) { bestD = d; wallOb = o; wallTarget = { x: ocx, y: ocy }; }
  }
  if (!wallTarget) { wallOb = obs[0]; wallTarget = { x: wallOb.x + wallOb.width / 2, y: wallOb.y + wallOb.height / 2 }; }
  log('[C wall] obstacle=' + JSON.stringify({ type: wallOb.type, x: wallOb.x, y: wallOb.y, w: wallOb.width, h: wallOb.height }) + ' target=(' + Math.round(wallTarget.x) + ',' + Math.round(wallTarget.y) + ')');
  const rectC = JSON.parse(await cdp.eval(`JSON.stringify(window.__mvUtil.canvasRect())`));
  const cxs = rectC.left + wallTarget.x / 1000 * rectC.width, cys = rectC.top + wallTarget.y / 600 * rectC.height;
  await cdp.eval(`window.__mv.snaps = [];`);
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: Math.round(cxs), y: Math.round(cys), button: 'left', buttons: 1, clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: Math.round(cxs), y: Math.round(cys), button: 'left', buttons: 0, clickCount: 1 });
  await sleep(3000);
  const cSnaps = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.snaps.map(s => ({ t: s.t, a: (s.agents.find(x => x.n === ${JSON.stringify(playerInfo.n)})) })).filter(s => s.a))`));
  // 抖动分析：位置往返次数
  let reversals = 0, prevDir = null;
  const posSeq = [];
  for (let i = 1; i < cSnaps.length; i++) {
    const dx = cSnaps[i].a.x - cSnaps[i - 1].a.x, dy = cSnaps[i].a.y - cSnaps[i - 1].a.y;
    const d = Math.hypot(dx, dy);
    posSeq.push({ x: cSnaps[i].a.x, y: cSnaps[i].a.y, d: +d.toFixed(1) });
    if (d > 1) { const dir = Math.atan2(dy, dx); if (prevDir !== null && Math.abs(dir - prevDir) > 2.4) reversals++; prevDir = dir; }
  }
  const posSpread = posSeq.length ? Math.max(...posSeq.map(p => p.d)) : 0;
  log('[C] snaps=' + cSnaps.length, 'maxStep=' + posSpread + 'px', 'reversals=' + reversals, 'lastPos=(' + Math.round(cSnaps[cSnaps.length - 1].a.x) + ',' + Math.round(cSnaps[cSnaps.length - 1].a.y) + ') hasTarget=' + cSnaps[cSnaps.length - 1].a.ht);
  await cdp.shot('c_wall.png');

  // ── 测试 D：WASD 持续移动 ──
  log('=== D: WASD ===');
  await sleep(1500);
  await cdp.eval(`window.__mv.snaps = []; window.__mv.fetches = [];`);
  const tD0 = Date.now();
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'w', code: 'KeyW', windowsVirtualKeyCode: 87, nativeVirtualKeyCode: 87 });
  await sleep(1400);
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'w', code: 'KeyW', windowsVirtualKeyCode: 87, nativeVirtualKeyCode: 87 });
  await sleep(600);
  const dSnaps = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.snaps.map(s => ({ t: s.t, a: (s.agents.find(x => x.n === ${JSON.stringify(playerInfo.n)})) })).filter(s => s.a))`));
  const dFetches = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.fetches)`));
  const p0d = dSnaps[0].a, p1d = dSnaps[dSnaps.length - 1].a;
  const distD = Math.hypot(p1d.x - p0d.x, p1d.y - p0d.y);
  const durD = (dSnaps[dSnaps.length - 1].t - dSnaps[0].t) / 1000;
  log('[D] moveDirFetches=' + dFetches.length, 'dist=' + distD.toFixed(1) + 'px', 'dur=' + durD.toFixed(2) + 's', 'speed=' + (distD / durD).toFixed(1) + 'px/s', 'p0=(' + Math.round(p0d.x) + ',' + Math.round(p0d.y) + ') p1=(' + Math.round(p1d.x) + ',' + Math.round(p1d.y) + ')');
  await cdp.shot('d_wasd.png');

  // ── 测试 E：外推 A/B（注入 vx/vy → 视觉连续性对比） ──
  log('=== E: extrapolation A/B ===');
  // E-A（无外推）：点击 → 采样截图，统计「服务器在动但画面停顿」的比例
  await sleep(1500);
  const pE0 = JSON.parse(await cdp.eval(`JSON.stringify(window.__mvUtil.player())`));
  const targetE = await cdp.eval(`(()=>{
    const p = window.__mvUtil.player(); if(!p) return null;
    const obs = window.__mvUtil.lastSnap().obs || [];
    const segHit = (x1,y1,x2,y2,o) => { const ox=o.x, oy=o.y, ow=o.width, oh=o.height;
      for (let f=0; f<=1; f+=0.02) { const px=x1+(x2-x1)*f, py=y1+(y2-y1)*f;
        if (px>=ox-14 && px<=ox+ow+14 && py>=oy-14 && py<=oy+oh+14) return true; } return false; };
    for (const [dx,dy] of [[-200,0],[200,0],[0,-200],[0,200],[-200,-200],[200,200],[-200,200],[200,-200]]) {
      const tx = Math.max(20, Math.min(980, p.x+dx)), ty = Math.max(20, Math.min(580, p.y+dy));
      let hit = false; for (const o of obs) if (segHit(p.x,p.y,tx,ty,o)) { hit = true; break; }
      if (!hit) return { x: tx, y: ty };
    }
    return { x: Math.max(20, Math.min(980, p.x-200)), y: p.y };
  })()`);
  const rectE = JSON.parse(await cdp.eval(`JSON.stringify(window.__mvUtil.canvasRect())`));
  const ex = rectE.left + targetE.x / 1000 * rectE.width, ey = rectE.top + targetE.y / 600 * rectE.height;
  await sleep(300);
  const eBaseShot = await cdp.shot('e0_base.png');
  let ePrev = locatePlayerDot(eBaseShot, color, rectE);
  if (ePrev) lastDot = { cx: ePrev.sx, cy: ePrev.sy };
  const collectMotion = async (durMs) => {
    const samples2 = []; let cnt = 0;
    const tEnd2 = Date.now() + durMs;
    while (Date.now() < tEnd2) {
      const shotBuf = await cdp.shot('e_f_' + cnt++ + '.png');
      if (shotBuf) {
        const dot = locatePlayerDot(shotBuf, color, rectE);
        if (dot && ePrev) { const d = Math.hypot(dot.wx - ePrev.wx, dot.wy - ePrev.wy); samples2.push({ t: Date.now(), wx: dot.wx, wy: dot.wy, d, moved: d > 1.5 }); }
        if (dot) ePrev = dot;
      }
      await sleep(100);
    }
    return samples2;
  };
  // 视觉样本 vs 服务器位置滞后分析（用快照线性插值估计样本时刻的服务器真值）
  const lagStats = (samples2, snapsArr) => {
    const lags = [];
    for (const s of samples2) {
      if (snapsArr.length < 2) continue;
      let i = snapsArr.length - 2;
      for (let k = 0; k < snapsArr.length - 1; k++) { if (snapsArr[k].t <= s.t && s.t <= snapsArr[k + 1].t) { i = k; break; } }
      const p = snapsArr[i], q = snapsArr[i + 1];
      if (!p.a || !q.a) continue;
      const span = Math.max(1, q.t - p.t);
      const f = Math.max(0, Math.min(1, (s.t - p.t) / span));
      const sx = p.a.x + (q.a.x - p.a.x) * f, sy = p.a.y + (q.a.y - p.a.y) * f;
      lags.push(Math.hypot(s.wx - sx, s.wy - sy));
    }
    if (lags.length === 0) return null;
    const ls = [...lags].sort((x, y) => x - y);
    const lp = (p) => ls[Math.min(ls.length - 1, Math.floor(ls.length * p))];
    return { n: lags.length, p50: +lp(0.5).toFixed(1), p90: +lp(0.9).toFixed(1), max: +ls[ls.length - 1].toFixed(1), mean: +(lags.reduce((a, b) => a + b, 0) / lags.length).toFixed(1) };
  };
  await cdp.eval(`window.__mv.snaps = [];`);
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: Math.round(ex), y: Math.round(ey), button: 'left', buttons: 1, clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: Math.round(ex), y: Math.round(ey), button: 'left', buttons: 0, clickCount: 1 });
  const sA = await collectMotion(2600);
  const eSnapsA = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.snaps.map(s => ({ t: s.t, a: (s.agents.find(x => x.n === ${JSON.stringify(playerInfo.n)})) })).filter(s => s.a))`));
  // 服务器真实位移（E-A 窗口内玩家移动了多少）
  const eAMove = eSnapsA.length > 1 ? Math.hypot(eSnapsA[eSnapsA.length - 1].a.x - eSnapsA[0].a.x, eSnapsA[eSnapsA.length - 1].a.y - eSnapsA[0].a.y) : 0;
  const movedA = sA.filter(s => s.moved).length, stillA = sA.filter(s => !s.moved).length;
  const lagA = lagStats(sA, eSnapsA);
  // E-B：注入 vx/vy，重复同样点击
  await cdp.eval(`window.__mvExtrap.enabled = true;`);
  await sleep(1200);
  const pE1 = JSON.parse(await cdp.eval(`JSON.stringify(window.__mvUtil.player())`));
  const targetE2 = await cdp.eval(`(()=>{
    const p = window.__mvUtil.player(); if(!p) return null;
    const obs = window.__mvUtil.lastSnap().obs || [];
    const segHit = (x1,y1,x2,y2,o) => { const ox=o.x, oy=o.y, ow=o.width, oh=o.height;
      for (let f=0; f<=1; f+=0.02) { const px=x1+(x2-x1)*f, py=y1+(y2-y1)*f;
        if (px>=ox-14 && px<=ox+ow+14 && py>=oy-14 && py<=oy+oh+14) return true; } return false; };
    for (const [dx,dy] of [[-200,0],[200,0],[0,-200],[0,200],[-200,-200],[200,200],[-200,200],[200,-200]]) {
      const tx = Math.max(20, Math.min(980, p.x+dx)), ty = Math.max(20, Math.min(580, p.y+dy));
      let hit = false; for (const o of obs) if (segHit(p.x,p.y,tx,ty,o)) { hit = true; break; }
      if (!hit) return { x: tx, y: ty };
    }
    return { x: Math.max(20, Math.min(980, p.x-200)), y: p.y };
  })()`);
  const ex2 = rectE.left + targetE2.x / 1000 * rectE.width, ey2 = rectE.top + targetE2.y / 600 * rectE.height;
  await sleep(300);
  const eBase2 = await cdp.shot('e1_base.png');
  ePrev = locatePlayerDot(eBase2, color, rectE);
  if (ePrev) lastDot = { cx: ePrev.sx, cy: ePrev.sy };
  await cdp.eval(`window.__mv.snaps = [];`);
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: Math.round(ex2), y: Math.round(ey2), button: 'left', buttons: 1, clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: Math.round(ex2), y: Math.round(ey2), button: 'left', buttons: 0, clickCount: 1 });
  const sB = await collectMotion(2600);
  const eSnapsB = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.snaps.map(s => ({ t: s.t, a: (s.agents.find(x => x.n === ${JSON.stringify(playerInfo.n)})) })).filter(s => s.a))`));
  const eBMove = eSnapsB.length > 1 ? Math.hypot(eSnapsB[eSnapsB.length - 1].a.x - eSnapsB[0].a.x, eSnapsB[eSnapsB.length - 1].a.y - eSnapsB[0].a.y) : 0;
  const movedB = sB.filter(s => s.moved).length, stillB = sB.filter(s => !s.moved).length;
  const lagB = lagStats(sB, eSnapsB);
  await cdp.eval(`window.__mvExtrap.enabled = false;`);
  log('[E] A(无外推) moved=' + movedA + '/' + (movedA + stillA) + ' still=' + stillA + ' serverMove=' + eAMove.toFixed(0) + 'px lag=' + JSON.stringify(lagA) + ' | B(注入vx/vy) moved=' + movedB + '/' + (movedB + stillB) + ' still=' + stillB + ' serverMove=' + eBMove.toFixed(0) + 'px lag=' + JSON.stringify(lagB));

  // ── 帧间隔（全会话） ──
  const rAF = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.rAF.slice(-20000))`));
  const sorted = [...rAF].sort((x, y) => x - y);
  const pct = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
  const rAFSum = rAF.reduce((a, b) => a + b, 0);
  const frameStats = { frames: rAF.length, fps: +(1000 / (rAFSum / rAF.length)).toFixed(1), p50: +pct(0.5).toFixed(1), p90: +pct(0.9).toFixed(1), p99: +pct(0.99).toFixed(1), max: +sorted[sorted.length - 1].toFixed(1), jankGt50: rAF.filter(f => f > 50).length };
  log('[frames]', JSON.stringify(frameStats));

  // ── SSE 节奏 ──
  const allSnaps = JSON.parse(await cdp.eval(`JSON.stringify(window.__mv.snaps.map(s => s.t))`));
  const gaps = [];
  for (let i = 1; i < allSnaps.length; i++) gaps.push(allSnaps[i] - allSnaps[i - 1]);
  const gs = [...gaps].sort((x, y) => x - y);
  const gp = (p) => gs[Math.min(gs.length - 1, Math.floor(gs.length * p))];
  const gapStats = { n: gaps.length, p50: gp(0.5), p90: gp(0.9), p99: gp(0.99), max: Math.max(...gaps) };

  const result = { player: playerInfo.n, playerColor: color, A: resA, B: { clickOffsets: clicks, fetches: bFetches.length, finalPos: { x: lastSnapB.a.x, y: lastSnapB.a.y }, hasTarget: lastSnapB.a.ht }, C: { obstacle: { type: wallOb.type, x: wallOb.x, y: wallOb.y, w: wallOb.width, h: wallOb.height }, maxStep: posSpread, reversals, lastPos: { x: cSnaps[cSnaps.length - 1].a.x, y: cSnaps[cSnaps.length - 1].a.y }, hasTarget: cSnaps[cSnaps.length - 1].a.ht }, D: { fetches: dFetches.length, dist: +distD.toFixed(1), dur: +durD.toFixed(2), speed: +(distD / durD).toFixed(1) }, E: { A_noExtrap: { moved: movedA, still: stillA, serverMove: +eAMove.toFixed(0), lag: lagA }, B_withExtrap: { moved: movedB, still: stillB, serverMove: +eBMove.toFixed(0), lag: lagB } }, frames: frameStats, sseGaps: gapStats, consoleErrors: cdp.consoleLogs.filter(l => l.startsWith('EXCEPTION') || l.startsWith('error')).length };
  log('RESULT ' + JSON.stringify(result, null, 2));
  writeFileSync(`${OUT}/report.json`, JSON.stringify(result, null, 2));

  // ── 复位世界 ──
  try {
    await fetch(BACKEND + '/api/simulation/reset', { method: 'POST' });
    const st = await (await fetch(BACKEND + '/api/simulation/state')).json();
    log('[reset] running=' + st.running + ' agents=' + (st.agents ? st.agents.length : 0));
  } catch (e) { log('[reset fail]', e.message); }
  child.kill();
  process.exit(0);
}
main().catch(e => { console.log('FATAL', e?.message); try { child?.kill(); } catch { } process.exit(1); });
