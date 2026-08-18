// make_furniture_block_demo.mjs — 生成「真正挡路的家具」交互演示（自包含 HTML）
// 输入：work/map_castle_96x64.json（模板原型城堡地图：家具写 collision=1 + tileProps.blocked）
// 输出：work/furniture-block-demo.html（零依赖，双击即玩）
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const map = JSON.parse(readFileSync(join(root, 'work', 'map_castle_96x64.json'), 'utf-8'));

// 家具色块（对齐 decorData.ts 色板，识别挡路家具）
const FURNITURE_COLORS = {
  chest: '#7c4a21', lamp: '#ffd166', note: '#f1f5f9', flower_bed: '#e63946',
  rock: '#808a93', table_rect: '#b07a4f', tea_table: '#6b4423', scroll: '#f5e6c8',
  incense: '#d4a017', dressing_table: '#b07a4f', desk: '#b07a4f', bookshelf: '#6b4423',
  plant: '#2e7d32', stove: '#374151', sink: '#cbd5e1', counter: '#b07a4f', fountain: '#38bdf8',
};
const GROUND_COLORS = { 1: '#8b5e3c', 2: '#64748b', 3: '#3f9e4d', 4: '#9c3d3d', 5: '#94a3b8' };

const html = `<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<title>真正挡路的家具 · 演示</title>
<style>
  body { margin:0; background:#0c1322; color:#e8eef9; font-family:'Microsoft YaHei',sans-serif; display:flex; flex-direction:column; align-items:center; min-height:100vh; }
  h1 { margin:14px 0 4px; font-size:19px; }
  .sub { color:#93a1bd; font-size:12.5px; margin-bottom:8px; }
  #wrap { position:relative; }
  canvas { border:2px solid #2b3854; border-radius:8px; background:#111a2e; display:block; }
  .hud { display:flex; gap:14px; align-items:center; margin-top:10px; font-size:13px; flex-wrap:wrap; justify-content:center; }
  .btn { background:#1b2944; color:#e8eef9; border:1px solid #2b3854; border-radius:7px; padding:6px 12px; cursor:pointer; }
  .btn.on { background:#7c4dff; border-color:#9f7cff; }
  .tip { color:#93a1bd; font-size:12px; margin-top:8px; max-width:760px; text-align:center; line-height:1.6; }
  .hit { color:#f87171; min-height:18px; margin-top:6px; font-size:13px; }
</style>
</head>
<body>
<h1>🏰 真正挡路的家具</h1>
<div class="sub">晨曦城堡 · 96×40 真实生成地图 · 桌子/柜子/床/灶台全部挡路，寻路自动绕开</div>
<div id="wrap"><canvas id="c"></canvas></div>
<div class="hud">
  <span>🎮 WASD / 方向键移动</span>
  <button id="ghost" class="btn">🚶 穿墙模式：关</button>
  <span id="pos">坐标 (7,6)</span>
  <span id="furn"></span>
</div>
<div class="hit" id="hit"></div>
<div class="tip">家具（棕/灰/蓝等色块）占用格已写入地图碰撞层——正常模式玩家会被桌子、柜子、床、灶台挡住；
打开「穿墙模式」对比：碰撞关闭后可以直接穿过家具。AI 巡逻同样只走可通行格，不会踩上家具。</div>
<script>
const MAP = ${JSON.stringify(map)};
const TS = 10, W = MAP.width * TS, H = MAP.height * TS;
const c = document.getElementById('c'); c.width = W; c.height = H;
const g = c.getContext('2d');
const FURN = ${JSON.stringify(FURNITURE_COLORS)};
const GROUND = ${JSON.stringify(GROUND_COLORS)};
const col = MAP.layers.collision, ground = MAP.layers.ground;
const props = MAP.tileProps || {};
let px = (MAP.spawn_points.find(s => s.type === 'player') || MAP.spawn_points[0]).x;
let py = (MAP.spawn_points.find(s => s.type === 'player') || MAP.spawn_points[0]).y;
let ghost = false;
const keys = {};

function blocked(x, y) {
  if (x < 0 || y < 0 || x >= MAP.width || y >= MAP.height) return true;
  if (col[y] && col[y][x] === 1) return true;
  return false;
}
function furnishAt(x, y) {
  for (const d of MAP.decor || []) {
    if (d.tile[0] === x && d.tile[1] === y) return d;
  }
  return null;
}
function draw() {
  for (let y = 0; y < MAP.height; y++) for (let x = 0; x < MAP.width; x++) {
    g.fillStyle = GROUND[ground[y][x]] || '#6b7280';
    g.fillRect(x * TS, y * TS, TS, TS);
  }
  for (const d of MAP.decor || []) {
    const [dx, dy] = d.tile;
    g.fillStyle = FURN[d.type] || '#6b7280';
    g.fillRect(dx * TS + 1, dy * TS + 1, TS - 2, TS - 2);
    if (d.type === 'note') { g.fillStyle = '#4a5568'; g.fillRect(dx * TS + 3.5, dy * TS + 3.5, 3, 3); }
  }
  // 家具名字（悬停展示交给 hit 条，这里给挡路家具描边）
  for (const d of MAP.decor || []) {
    if (blocked(d.tile[0], d.tile[1])) {
      g.strokeStyle = 'rgba(244,63,94,.35)'; g.lineWidth = 1;
      g.strokeRect(d.tile[0] * TS + 0.5, d.tile[1] * TS + 0.5, TS - 1, TS - 1);
    }
  }
  g.fillStyle = '#38bdf8'; g.beginPath(); g.arc((px + 0.5) * TS, (py + 0.5) * TS, 4, 0, Math.PI * 2); g.fill();
  g.fillStyle = '#fff'; g.font = '9px sans-serif'; g.textAlign = 'center';
  g.fillText('玩家', (px + 0.5) * TS, (py + 0.5) * TS - 7);
}
function move(dx, dy) {
  const nx = px + dx, ny = py + dy;
  const hit = blocked(nx, ny);
  if (!hit || ghost) { px = nx; py = ny; document.getElementById('hit').textContent = ''; }
  else {
    const f = furnishAt(nx, ny);
    document.getElementById('hit').textContent = '🚧 被挡住：' + (f ? ('「' + f.type + '」' + (f.tile ? ' (家具)' : '')) : '墙/边界');
  }
  const f2 = furnishAt(px, py);
  document.getElementById('pos').textContent = '坐标 (' + px + ',' + py + ')';
  document.getElementById('furn').textContent = f2 ? '站在家具「' + f2.type + '」上（穿墙模式）' : '';
  draw();
}
document.addEventListener('keydown', e => { keys[e.code] = true; });
document.addEventListener('keyup', e => { keys[e.code] = false; });
document.getElementById('ghost').addEventListener('click', e => {
  ghost = !ghost;
  e.target.textContent = '🚶 穿墙模式：' + (ghost ? '开' : '关');
  e.target.classList.toggle('on', ghost);
});
setInterval(() => {
  let dx = 0, dy = 0;
  if (keys['ArrowLeft'] || keys['KeyA']) dx -= 1;
  if (keys['ArrowRight'] || keys['KeyD']) dx += 1;
  if (keys['ArrowUp'] || keys['KeyW']) dy -= 1;
  if (keys['ArrowDown'] || keys['KeyS']) dy += 1;
  if (dx || dy) move(dx, dy);
}, 90);
draw();
// ?demo=block：自动向最近的挡路格走一步，展示「被挡住」提示（截图/快速演示用）
const demo = new URLSearchParams(location.search).get('demo');
if (demo === 'block') {
  let bx = -1, by = -1;
  for (let d = 1; d < 40 && bx < 0; d++) {
    for (let dy = -d; dy <= d && bx < 0; dy++) {
      for (let dx = -d; dx <= d; dx++) {
        if (Math.abs(dx) + Math.abs(dy) !== d) continue;
        const gx = px + dx, gy = py + dy;
        if (blocked(gx, gy) && furnishAt(gx, gy)) { bx = gx; by = gy; break; }
      }
    }
  }
  if (bx >= 0) {
    for (let i = 0; i < 300; i++) {
      const before = document.getElementById('hit').textContent;
      const cx = px, cy = py;
      if (cx === bx && cy === by) break;
      const dxs = bx > cx ? [1, -1] : [-1, 1];
      const dys = by > cy ? [1, -1] : [-1, 1];
      let stepped = false;
      const cands = [];
      for (const dx of dxs) cands.push([dx, 0]);
      for (const dy of dys) cands.push([0, dy]);
      for (const [dx, dy] of cands) {
        if (!blocked(cx + dx, cy + dy)
            && Math.abs(bx - (cx + dx)) + Math.abs(by - (cy + dy)) < Math.abs(bx - cx) + Math.abs(by - cy)) {
          move(dx, dy);
          stepped = true;
          break;
        }
      }
      if (!stepped) {
        const ordered = [...cands].sort((a, b) =>
          (Math.abs(bx - (cx + a[0])) + Math.abs(by - (cy + a[1])))
          - (Math.abs(bx - (cx + b[0])) + Math.abs(by - (cy + b[1]))));
        for (const [dx, dy] of ordered) {
          move(dx, dy);
          if (document.getElementById('hit').textContent !== before) break;
        }
      }
      if (document.getElementById('hit').textContent !== before) break;
    }
  }
}
document.title += ' · ok(blocked=' + MAP.decor.filter(d => blocked(d.tile[0], d.tile[1])).length + ')';
</script>
</body>
</html>
`;

const out = join(root, 'work', 'furniture-block-demo.html');
writeFileSync(out, html, 'utf-8');
console.log('written', out, html.length, 'bytes');
