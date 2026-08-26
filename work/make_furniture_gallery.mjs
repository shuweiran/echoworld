// make_furniture_gallery.mjs — 家具图鉴：把 decorData.ts 的 FURNITURE_DRAW 渲染成 SVG 图鉴
// 验证「家具不是像素点」：每件家具画成跨格可辨识图形（桌面+腿 / 床+枕被…）
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const frontend = join(root, 'frontend');
// 用 esbuild 把 decorData.ts 打成 CJS（type-only import 自动擦除）
const out = join(root, 'work', 'decordata.cjs');
execFileSync('node', ['node_modules/esbuild/bin/esbuild', 'src/phaser/decorData.ts',
  '--bundle', '--format=cjs', '--outfile=' + out], {
  cwd: frontend, stdio: 'pipe',
});
const decorMod = await import(pathToFileURL(out).href);
const decorStyle = decorMod.decorStyle ?? decorMod.default.decorStyle;

const TS = 18; // SVG 每格像素
const PAD = 26;
const types = [
  'table_rect', 'chair', 'sofa', 'bed', 'desk', 'bookshelf', 'cabinet', 'wardrobe',
  'shelf', 'chest', 'tea_table', 'dressing_table', 'stove', 'sink', 'counter', 'counter_4',
  'stool', 'table_round', 'lamp', 'plant', 'pillar', 'screen', 'cart', 'wood_stack',
  'fountain', 'flower_bed', 'bench', 'tree', 'rock', 'hay', 'rug', 'window', 'incense', 'scroll', 'note',
];

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const color = (c) => '#' + c.toString(16).padStart(6, '0');

function cmdsToSvg(cmds, ox, oy) {
  let s = '';
  for (const c of cmds) {
    if (c.shape === 'rect') {
      s += `<rect x="${ox + c.x * TS}" y="${oy + c.y * TS}" width="${c.w * TS}" height="${c.h * TS}" fill="${color(c.color)}" fill-opacity="${c.alpha}" />`;
    } else if (c.shape === 'circle') {
      s += `<circle cx="${ox + c.x * TS}" cy="${oy + c.y * TS}" r="${c.r * TS}" fill="${color(c.color)}" fill-opacity="${c.alpha}" />`;
    } else if (c.shape === 'dots') {
      for (const [px, py] of c.pts) {
        s += `<circle cx="${ox + px * TS}" cy="${oy + py * TS}" r="${TS * 0.09}" fill="${color(c.colors[0])}" />`;
      }
    }
  }
  return s;
}

// 每格 4×3 tile 网格（跨格可见），家具相对左上角
const cards = types.map((t) => {
  const cmds = decorStyle(t);
  const w = 4 * TS, h = 3 * TS;
  const ox = PAD, oy = PAD;
  return `<div class="card"><div class="name">${t}</div>
  <svg width="${w + PAD * 2}" height="${h + PAD * 2}">
    <rect x="0" y="0" width="100%" height="100%" fill="#0c1322"/>
    ${cmdsToSvg(cmds, ox, oy)}
  </svg></div>`;
}).join('');

const html = `<!DOCTYPE html><html lang="zh"><head><meta charset="UTF-8">
<title>家具图鉴 · 不是像素点</title><style>
body{margin:0;background:#0c1322;color:#e8eef9;font-family:'Microsoft YaHei',sans-serif;padding:16px}
h1{font-size:18px;margin:0 0 4px}h2{font-size:12.5px;color:#93a1bd;font-weight:normal;margin:0 0 14px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:12px}
.card{background:#141e33;border:1px solid #2b3854;border-radius:8px;padding:6px}
.name{font-size:11.5px;color:#93a1bd;text-align:center;margin-bottom:4px}
svg{display:block;margin:0 auto}
</style></head><body>
<h1>🏠 家具图鉴（decorData FURNITURE_DRAW 跨格绘制）</h1>
<h2>每格 4×3 tile：桌面+桌腿 / 床+枕被 / 书柜+层板书 / 灶台+灶眼 —— 每件家具都是完整图形，不再是像素点</h2>
<div class="grid">${cards}</div>
</body></html>`;

writeFileSync(join(root, 'work', 'furniture-gallery.html'), html, 'utf-8');
console.log('written furniture-gallery.html', html.length, 'bytes');
