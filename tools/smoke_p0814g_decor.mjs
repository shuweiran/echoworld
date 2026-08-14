/* smoke_p0814g_decor.mjs — P-0814-G（Phaser 多层渲染批次）装饰色块纯函数冒烟
 * ⚠️ 本批次标记 P-0814-G 与并行「前端消息显示顺序修复」批次撞标：tools/smoke_p0814g.mjs 已被该批次占用
 *    （其任务登记亦为 tools/smoke_p0814g.mjs），本文件改名 smoke_p0814g_decor.mjs 避让。
 * 用 esbuild 打包真码（mapData.ts normalizeMap + decorData.ts 色块映射/渲染计划）在 Node 断言：
 *   ① 色块映射表完整性（BSP 四 decor 类型 + grass/debris 类别 + objects 三类型 + 未知兜底）
 *   ② 多层渲染对缺失键返回空（v1 地图逐像素零回归）
 *   ③ y 排序（北侧装饰 depth < 南侧；ground < objects < characters < overlay 层序）
 *   ④ normalizeMap 透传 v0.2 新键（objects/overlay/decor/spawnMarkers/tileProps/warps）
 * 参照 tools/smoke_p0813g.mjs 风格。 */
import { createRequire } from 'module';
import { mkdirSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/roleplay-java/roleplay-v4/frontend/node_modules/esbuild');

mkdirSync('tmp/p0814g_decor', { recursive: true });

const code = `
const { normalizeMap } = require('./roleplay-v4/frontend/src/phaser/mapData.ts');
const {
  buildDecorPlan, objectStyle, decorStyle, markerStyle, overlayStyle,
  decorDepth, charDepth, DEPTH_WATER, DEPTH_OVERLAY,
  C_TREE_GREEN, C_TRUNK_BROWN, C_FENCE_BROWN, C_PILLAR_GRAY, C_BENCH_BROWN,
  C_LAMP_YELLOW, C_CHEST_BROWN, C_NOTE_WHITE, C_GRASS_LIGHT, C_DEBRIS_BROWN,
  C_UNKNOWN, C_WATER_BLUE, FLOWER_COLORS,
} = require('./roleplay-v4/frontend/src/phaser/decorData.ts');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}
const cmdsInfo = (cmds) => cmds.map(c => c.shape + ':' + (c.color === undefined ? '' : c.color.toString(16))).join(',');

// ── ① 色块映射表完整性 ──
// objects 层（LLM 可能输出）：tree_oak 深绿圆冠+棕干 / fence 棕色细条 / flower_bed 彩色小点
const oak = objectStyle('tree_oak');
check('1 objects.tree_oak = 圆冠(深绿) + 树干(棕)', oak && oak.length === 2
  && oak[0].shape === 'circle' && oak[0].color === C_TREE_GREEN
  && oak[1].shape === 'rect' && oak[1].color === C_TRUNK_BROWN, cmdsInfo(oak || []));
const fence = objectStyle('fence');
check('1 objects.fence = 棕色细条(横条+立柱)', fence && fence.length === 3
  && fence[0].shape === 'rect' && fence[0].color === C_FENCE_BROWN, cmdsInfo(fence || []));
const ob = objectStyle('flower_bed');
check('1 objects.flower_bed = 彩色小点(dots 用 FLOWER_COLORS 调色板)', ob && ob.some(c => c.shape === 'dots' && c.colors.join() === FLOWER_COLORS.join()), cmdsInfo(ob || []));
check('1 objects null 元素跳过（不渲染）', objectStyle(null) === null && objectStyle(undefined) === null);
const oakU = objectStyle('mystery_object');
check('1 objects 未知类型 → 灰色 60% 透明方块兜底', oakU && oakU.length === 1 && oakU[0].shape === 'rect' && oakU[0].color === C_UNKNOWN && oakU[0].alpha === 0.6, cmdsInfo(oakU || []));

// decor（BSP 四类型 + LLM 扩展 chest/note）：pillar 灰矩形 / flower_bed 彩色 / bench 棕色长条 / lamp 黄色亮块
check('2 decor.pillar = 灰矩形', decorStyle('pillar')[0].shape === 'rect' && decorStyle('pillar')[0].color === C_PILLAR_GRAY);
check('2 decor.flower_bed = 彩色（dots）', decorStyle('flower_bed').some(c => c.shape === 'dots'));
check('2 decor.bench = 棕色长条', decorStyle('bench')[0].shape === 'rect' && decorStyle('bench')[0].color === C_BENCH_BROWN);
check('2 decor.lamp = 黄色亮块', decorStyle('lamp').some(c => c.shape === 'circle' && c.color === C_LAMP_YELLOW));
check('2 decor.chest = 棕色方块（LLM 扩展）', decorStyle('chest').some(c => c.shape === 'rect' && c.color === C_CHEST_BROWN));
check('2 decor.note = 白色小条（LLM 扩展）', decorStyle('note').some(c => c.shape === 'rect' && c.color === C_NOTE_WHITE));
const dU = decorStyle('mystery_decor');
check('2 decor 未知类型 → 灰色 60% 方块兜底', dU[0].shape === 'rect' && dU[0].color === C_UNKNOWN && dU[0].alpha === 0.6, cmdsInfo(dU));

// spawnMarkers 类别：grass 浅绿小草（三角）/ debris 棕色小方块 / 未知 → 灰色小点
check('3 markers.grass = 浅绿小三角', markerStyle('grass').some(c => c.shape === 'triangle' && c.color === C_GRASS_LIGHT));
check('3 markers.debris = 棕色小方块', markerStyle('debris').some(c => c.shape === 'rect' && c.color === C_DEBRIS_BROWN));
const mU = markerStyle('mystery_marker');
check('3 markers 未知类别 → 灰色小点兜底', mU[0].shape === 'circle' && mU[0].color === C_UNKNOWN, cmdsInfo(mU));

// overlay：canopy → 深绿 alpha 0.35；未知 → 灰色 alpha 0.3
const ovCan = overlayStyle('canopy');
check('4 overlay.canopy = 深绿 alpha 0.35（永远盖住角色）', ovCan.fill === C_TREE_GREEN && ovCan.alpha === 0.35);
check('4 overlay 未知类型 = 灰色 alpha 0.3', overlayStyle('mystery').fill === C_UNKNOWN && overlayStyle('mystery').alpha === 0.3);

// ── ② 多层渲染对缺失键返回空（v1 地图逐像素零回归） ──
const v1 = normalizeMap({
  map_version: 1, map_id: 'v1', name: 'v1', width: 10, height: 6, tile_size: 32,
  layers: { ground: Array.from({ length: 6 }, () => Array(10).fill(1)), collision: Array.from({ length: 6 }, () => Array(10).fill(0)) },
});
const p1 = buildDecorPlan(v1);
check('5 v1 地图（无新键）→ 装饰/遮罩/水全空（零渲染）', p1.items.length === 0 && p1.overlay.length === 0 && p1.water.length === 0,
  'items=' + p1.items.length + ' overlay=' + p1.overlay.length + ' water=' + p1.water.length);
check('5 v1 地图 normalize 后新键为 undefined（不注入空数组）',
  v1.layers.objects === undefined && v1.decor === undefined && v1.spawnMarkers === undefined && v1.tileProps === undefined && v1.warps === undefined);

// ── ③ y 排序（北侧装饰 depth < 南侧；层序 ground < objects < characters < overlay） ──
const v02 = normalizeMap({
  map_version: 1, map_id: 'v02', name: 'v02', width: 10, height: 6, tile_size: 32,
  layers: {
    ground: Array.from({ length: 6 }, () => Array(10).fill(3)),
    collision: Array.from({ length: 6 }, () => Array(10).fill(0)),
    objects: [
      [null, null, null, null, null, null, null, null, null, null],
      [null, 'tree_oak', null, null, null, null, null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null],
      [null, null, null, null, null, 'fence', null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null],
    ],
    overlay: [
      [null, null, null, null, null, null, null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null],
      ['canopy', null, null, null, null, null, null, null, null, null],
    ],
  },
  decor: [
    { id: 'd1', type: 'pillar', tile: [3, 2] },
    { id: 'd2', type: 'lamp', tile: [6, 4] },
  ],
  spawnMarkers: { grass: [[1, 1], [2, 2]], debris: [[8, 4]] },
  tileProps: { '4,2': { water: true }, '0,0': { blocked: true }, '1,1': { water: false } },
  warps: [{ from: [9, 5], to: ['town', 10, 30] }],
});
const p2 = buildDecorPlan(v02);
check('6 完整 v0.2 计划：items=7（objects 2 + markers 3 + decor 2）', p2.items.length === 7,
  'items=' + p2.items.length + ' ' + p2.items.map(i => i.layer + '@' + i.x + ',' + i.y).join(' '));
check('6 遮罩=1（canopy @5,0）', p2.overlay.length === 1 && p2.overlay[0].type === 'canopy' && p2.overlay[0].x === 0 && p2.overlay[0].y === 5);
check('6 water 仅 water=true 格（blocked 不做视觉 / water:false 排除）', p2.water.length === 1 && p2.water[0].x === 4 && p2.water[0].y === 2,
  JSON.stringify(p2.water));
// y 排序：北侧（y 小）depth 小 → 排在前
const sortedOk = p2.items.every((it, i) => i === 0 || p2.items[i - 1].depth <= it.depth);
check('6 计划按 depth 升序（北→南）', sortedOk, p2.items.map(i => i.depth.toFixed(3)).join(','));
const y1 = p2.items.find(i => i.layer === 'objects' && i.x === 1 && i.y === 1);
const y3 = p2.items.find(i => i.layer === 'objects' && i.x === 5 && i.y === 3);
check('7 北侧装饰 depth < 南侧装饰（y=1 在 y=3 前）', y1 && y3 && y1.depth < y3.depth && p2.items.indexOf(y1) < p2.items.indexOf(y3),
  'y1=' + (y1 && y1.depth) + ' y3=' + (y3 && y3.depth));
check('7 decorDepth 公式 = 1 + (y+0.5)/H', decorDepth(0, 6) === 1 + 0.5 / 6 && decorDepth(5, 6) === 1 + 5.5 / 6);
// 层序：ground(0) < water(0.5) < objects(1.x) < characters(1.x+0.01) < overlay(5) < UI 标记(7+)
const H = 6, ts = 64, mapPxH = H * ts;
const charSameRow = charDepth((0.5) * ts, mapPxH); // 与 y=0 行中心同一 y
check('8 层序 ground < water < objects < characters < overlay',
  DEPTH_WATER > 0 && decorDepth(0, H) > DEPTH_WATER && charSameRow > decorDepth(0, H) && DEPTH_OVERLAY > charDepth(mapPxH, mapPxH) && DEPTH_OVERLAY < 7,
  'water=' + DEPTH_WATER + ' obj0=' + decorDepth(0, H).toFixed(4) + ' char0=' + charSameRow.toFixed(4) + ' charMax=' + charDepth(mapPxH, mapPxH).toFixed(4) + ' overlay=' + DEPTH_OVERLAY);
check('8 角色 y 连续深度（北侧角色在装饰下、南侧在上）',
  charDepth(0, mapPxH) < decorDepth(1, H) && charDepth((5.5) * ts, mapPxH) > decorDepth(4, H),
  'charTop=' + charDepth(0, mapPxH).toFixed(4) + ' objRow1=' + decorDepth(1, H).toFixed(4));
check('8 水色常量 = 半透明蓝', C_WATER_BLUE === 0x38bdf8);

// ── ④ normalizeMap 透传 v0.2 新键（宽容解析） ──
check('9 v0.2 地图 normalize 透传 layers.objects（可 null 元素）', Array.isArray(v02.layers.objects) && v02.layers.objects[1][1] === 'tree_oak' && v02.layers.objects[0][0] === null);
check('9 v0.2 地图 normalize 透传 layers.overlay', Array.isArray(v02.layers.overlay) && v02.layers.overlay[5][0] === 'canopy');
check('9 v0.2 地图 normalize 透传 decor（tile 数对）', v02.decor.length === 2 && v02.decor[0].type === 'pillar' && v02.decor[0].tile[0] === 3 && v02.decor[0].tile[1] === 2);
check('9 v0.2 地图 normalize 透传 spawnMarkers', v02.spawnMarkers.grass.length === 2 && v02.spawnMarkers.debris[0][0] === 8);
check('9 v0.2 地图 normalize 透传 tileProps（water 保留）', v02.tileProps['4,2'].water === true && v02.tileProps['1,1'].water === false);
check('9 v0.2 地图 normalize 透传 warps', v02.warps.length === 1 && v02.warps[0].to[0] === 'town' && v02.warps[0].to[1] === 10);
// 半残缺新键宽容解析不崩
const half = normalizeMap({
  map_version: 1, map_id: 'half', width: 4, height: 3, tile_size: 32,
  layers: { ground: [[1, 1, 1, 1], [1, 1, 1, 1], [1, 1, 1, 1]], collision: [[0, 0, 0, 0], [0, 0, 0, 0], [0, 0, 0, 0]], objects: 'bad' },
  decor: [{ id: 'x' }], spawnMarkers: { grass: 'bad' }, tileProps: { 'a,b': 'not-object' },
});
const ph = buildDecorPlan(half);
check('10 半残缺 v0.2 键宽容解析不崩（objects 非数组→忽略，坏 spawnMarkers/tileProps 丢弃，缺 type 的 decor → 未知灰色兜底）',
  ph.items.length === 1 && ph.items[0].type === 'unknown' && ph.items[0].cmds[0].color === C_UNKNOWN
  && ph.water.length === 0 && ph.overlay.length === 0, 'items=' + ph.items.length + ' ' + ph.items.map(i => i.type + '@' + i.x + ',' + i.y).join(' '));

console.log(failures === 0 ? '\\nALL PASS (0 failures)' : '\\nFAILURES: ' + failures);
process.exit(failures === 0 ? 0 : 1);
`;

const result = buildSync({
  entryPoints: [],
  stdin: { contents: code, resolveDir: 'D:/roleplay-java', sourcefile: 'smoke_p0814g_decor.ts' },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  loader: { '.ts': 'ts', '.tsx': 'tsx', '.css': 'text', '.svg': 'text' },
  write: false,
  logLevel: 'error',
});
require('fs').writeFileSync('tmp/p0814g_decor/smoke_p0814g_decor.cjs', result.outputFiles[0].text);
execSyncNode();
function execSyncNode() {
  const { execSync } = require('child_process');
  try {
    const out = execSync('node tmp/p0814g_decor/smoke_p0814g_decor.cjs', { cwd: 'D:/roleplay-java', encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    process.stdout.write(out);
  } catch (e) {
    process.stdout.write(e.stdout || '');
    process.exit(1);
  }
}
