/**
 * decorData.ts — 地图装饰色块映射与渲染计划（P-0814-G，契约 v0.2）
 *
 * 主人拍板：装饰先用色块替代（不引入新图片素材）——用 Phaser Graphics/Rectangle
 * 程序化绘制（简单几何 + 颜色），后续有素材再替换。
 *
 * 数据来源（docs/地图JSON契约-v1.md §7，P-0814-F）：
 *   - layers.objects   （Front 层：类型名二维数组，可 null；与角色互相遮挡，y 排序）
 *   - layers.overlay   （AlwaysFront 前景遮罩：永远盖住角色，depth 最高）
 *   - decor            （显式装饰/交互物列表：{id,type,tile:[x,y]}）
 *   - spawnMarkers     （生成器指示 Map<类别, 坐标数组>：grass/debris 批量铺色块）
 *   - tileProps        （每格属性字典：water=true 叠加蓝色半透明）
 *
 * 本文件只含纯数据/纯函数（Node 冒烟可测，不依赖 Phaser）：
 *   色块映射表（objects / decor / spawnMarkers 三类，未知 type 灰色兜底）→
 *   buildDecorPlan(map) → 按 y 排序的渲染计划（depth = y 基准 + 层偏移）
 *   collectWaterTiles / buildOverlayPlan 拆分
 * ScriptMapScene 只负责把计划里的 draw 命令画到 Graphics（见 drawDecorCmds）。
 *
 * 深度范式（星露谷 standingY/10000，D13）：同一连续 y 基准 + 层偏移——
 *   ground(0) < water(0.5) < objects/decor/markers(1 + rowNorm) <
 *   角色(1 + charNorm + 0.01) < overlay(5)
 *   行内 y 排序：北侧（rowNorm 小）→ depth 小 → 被南侧遮挡。
 */
import type { ScriptMap } from './mapData';

/* ── 绘图命令（归一化坐标，tile 单位 0..1；渲染层乘 ts 像素化） ── */

export type DecorCmd =
  | { shape: 'rect'; x: number; y: number; w: number; h: number; color: number; alpha: number }
  | { shape: 'circle'; x: number; y: number; r: number; color: number; alpha: number }
  | { shape: 'triangle'; x: number; y: number; w: number; h: number; color: number; alpha: number }
  | { shape: 'dots'; x: number; y: number; pts: [number, number][]; colors: number[]; alpha: number };

/**
 * drawDecorCmds — 把渲染计划里的绘图命令画到 Phaser Graphics（P-0814-G 补全：
 * P-0814-F 批次 v0.2 前端适配时 ScriptMapScene 已改调本函数，但 decorData 未导出实现，
 * tsc 断链（drawDecorCmds 未定义）。坐标语义与 styleToCmds 对齐：rect=左上角 tile 单位
 * x/y + w/h；circle=圆心 x/y + 半径 r；triangle=顶角 (x,y) + 底宽 w/高 h（底边在 y+h）；
 * dots=小点 tile 单位偏移列表，按调色板循环取色。参数用结构化类型（fillStyle/fillRect/
 * fillCircle/fillTriangle）避免引入 Phaser 依赖，保持本文件「纯函数 Node 冒烟可测」性质。
 */
export function drawDecorCmds(
  g: {
    fillStyle(color: number, alpha?: number): unknown;
    fillRect(x: number, y: number, w: number, h: number): unknown;
    fillCircle(x: number, y: number, r: number): unknown;
    fillTriangle(x1: number, y1: number, x2: number, y2: number, x3: number, y3: number): unknown;
  },
  cmds: DecorCmd[],
  px: number,
  py: number,
  ts: number,
): void {
  for (const c of cmds) {
    switch (c.shape) {
      case 'rect':
        g.fillStyle(c.color, c.alpha);
        g.fillRect(px + c.x * ts, py + c.y * ts, Math.max(0.5, c.w * ts), Math.max(0.5, c.h * ts));
        break;
      case 'circle':
        g.fillStyle(c.color, c.alpha);
        g.fillCircle(px + c.x * ts, py + c.y * ts, Math.max(0.5, c.r * ts));
        break;
      case 'triangle':
        g.fillStyle(c.color, c.alpha);
        g.fillTriangle(
          px + (c.x - c.w / 2) * ts, py + (c.y + c.h) * ts,
          px + (c.x + c.w / 2) * ts, py + (c.y + c.h) * ts,
          px + c.x * ts, py + c.y * ts,
        );
        break;
      case 'dots':
        for (let i = 0; i < c.pts.length; i++) {
          g.fillStyle(c.colors[i % c.colors.length], c.alpha);
          g.fillCircle(px + c.pts[i][0] * ts, py + c.pts[i][1] * ts, Math.max(0.5, ts * 0.09));
        }
        break;
    }
  }
}

/** 色块（fill color 0xRRGGBB） */
export interface DecorStyle {
  fill: number;
  alpha: number;
  /** rect 矩形 / circle 圆 / triangle 三角 / dots 彩色小点 */
  kind: 'rect' | 'circle' | 'triangle' | 'dots';
  /** 尺寸（tile 单位 0..1） */
  size: { w?: number; h?: number; r?: number };
  /** dots 用：小点调色板 */
  sub?: number[];
}

/* ── 颜色常量（集中一处定义） ── */

export const C_TREE_GREEN = 0x1e5631;   // 深绿（树冠）
export const C_TRUNK_BROWN = 0x6b4423;  // 树干棕
export const C_FENCE_BROWN = 0x7c5a34;  // 栅栏棕
export const C_PILLAR_GRAY = 0x9aa5b1;  // 石柱灰
export const C_BENCH_BROWN = 0x8a5a2b;  // 长椅棕
export const C_LAMP_YELLOW = 0xffd166;  // 灯黄亮
export const C_LAMP_POLE = 0x4a5568;    // 灯柱深灰
export const C_CHEST_BROWN = 0x7c4a21;  // 箱子棕
export const C_CHEST_DARK = 0x5d3312;   // 箱盖深棕
export const C_NOTE_WHITE = 0xf1f5f9;   // 便条白
export const C_GRASS_LIGHT = 0x7bc96f;  // 小草浅绿
export const C_GRASS_MID = 0x5fb35a;    // 小草中绿
export const C_DEBRIS_BROWN = 0x8a6b4a; // 杂物棕
export const C_DEBRIS_DARK = 0x756046;  // 杂物深棕
export const C_UNKNOWN = 0x6b7280;      // 未知兜底灰
export const C_WATER_BLUE = 0x38bdf8;   // 水蓝
export const C_BED_SOIL = 0x7c5a34;     // 花坛土槽棕
// P-0817-N（L2 房间家具色块）：家具配色（对齐模板原型 template_proto.py 家具库）
export const C_WOOD_LIGHT = 0xb07a4f;   // 浅木（台面/桌面）
export const C_WOOD_DARK = 0x6b4423;    // 深木（柜体/框架）
export const C_SOFA_RED = 0xb3564f;     // 沙发红
export const C_BED_WHITE = 0xf1f5f9;    // 床单白
export const C_BED_BLUE = 0x4a7ba6;     // 枕头蓝
export const C_DARK_GRAY = 0x374151;    // 灶台深灰
export const C_SINK_LIGHT = 0xcbd5e1;   // 水槽浅灰
export const C_POT_GREEN = 0x2e7d32;    // 盆栽绿
export const C_ROCK_GRAY = 0x808a93;    // 岩石灰
export const C_HAY_YELLOW = 0xe0c068;   // 干草黄
export const C_SCREEN_BROWN = 0xa0522d; // 屏风棕
export const C_SCROLL_TAN = 0xf5e6c8;   // 卷轴米
export const C_GOLD_YELLOW = 0xd4a017;  // 鎏金
export const C_CARPET_RED = 0x9c3d3d;   // 地毯红
export const FLOWER_COLORS = [0xe63946, 0xf4a261, 0xe9c46a, 0xd4a5a5, 0xf28482]; // 花彩色调色板

/** 花坛彩色小点的确定性位置（tile 单位） */
export const DOT_OFFSETS: [number, number][] = [
  [0.22, 0.28], [0.45, 0.22], [0.68, 0.32], [0.3, 0.5], [0.56, 0.5], [0.78, 0.46],
  [0.24, 0.6], [0.5, 0.62], [0.72, 0.6],
];

/* ── 深度常量（y 基准 + 层偏移，星露谷 standingY/10000 范式） ── */

/** 水格叠加深度（ground tilemap 之上、装饰之下） */
export const DEPTH_WATER = 0.5;
/** 前景遮罩深度（永远盖住角色；低于热点/出生点等 UI 标记层 7/8） */
export const DEPTH_OVERLAY = 5;
/** 角色层偏移（同一连续 y 基准上略高于装饰，同格同 y 时角色在前） */
export const CHAR_LAYER_OFFSET = 0.01;

/** 装饰行深度：depth = 1 + (y + 0.5) / H（北侧小 → 被南侧遮挡） */
export function decorDepth(y: number, H: number): number {
  return 1 + (y + 0.5) / Math.max(1, H);
}

/** 角色深度：depth = 1 + clamp(py / mapPxH) + 层偏移（连续 y，与装饰同一基准互遮挡） */
export function charDepth(py: number, mapPxH: number): number {
  const norm = Math.min(0.9999, Math.max(0, py / Math.max(1, mapPxH)));
  return 1 + norm + CHAR_LAYER_OFFSET;
}

/* ── ① objects 层映射（LLM 可能输出） ── */

const OBJECT_STYLES: Record<string, DecorStyle> = {
  tree_oak: { fill: C_TREE_GREEN, alpha: 1, kind: 'circle', size: { r: 0.36 } },
  fence: { fill: C_FENCE_BROWN, alpha: 1, kind: 'rect', size: { w: 0.84, h: 0.1 } },
  flower_bed: { fill: C_BED_SOIL, alpha: 1, kind: 'dots', size: { w: 0.9, h: 0.16 }, sub: FLOWER_COLORS },
};

/** 未知 objects 类型 → 灰色 60% 透明方块（兜底） */
const OBJECT_UNKNOWN: DecorStyle = { fill: C_UNKNOWN, alpha: 0.6, kind: 'rect', size: { w: 0.9, h: 0.9 } };

/**
 * objects 类型 → 绘图命令序列；null/空 → null（跳过该格）。
 * tree_oak 深绿圆冠+棕干 / fence 棕色细条 / flower_bed 彩色小点 / 未知 → 灰色 60% 方块。
 */
export function objectStyle(type: string | null | undefined): DecorCmd[] | null {
  if (type === null || type === undefined) return null;
  const s = OBJECT_STYLES[type] ?? OBJECT_UNKNOWN;
  return styleToCmds(s, type);
}

/* ── ② decor 类型映射（BSP 已产出 + LLM 可能） ── */

const DECOR_STYLES: Record<string, DecorStyle> = {
  pillar: { fill: C_PILLAR_GRAY, alpha: 1, kind: 'rect', size: { w: 0.44, h: 0.84 } },
  flower_bed: { fill: C_BED_SOIL, alpha: 1, kind: 'dots', size: { w: 0.9, h: 0.16 }, sub: FLOWER_COLORS },
  bench: { fill: C_BENCH_BROWN, alpha: 1, kind: 'rect', size: { w: 0.88, h: 0.16 } },
  lamp: { fill: C_LAMP_YELLOW, alpha: 1, kind: 'circle', size: { r: 0.16 } },
  chest: { fill: C_CHEST_BROWN, alpha: 1, kind: 'rect', size: { w: 0.44, h: 0.4 } },
  note: { fill: C_NOTE_WHITE, alpha: 0.9, kind: 'rect', size: { w: 0.28, h: 0.2 } },
  // P-0817-N（L2 房间家具）：模板配方产出的家具类型色块（单格图标，锚定格渲染）
  counter: { fill: C_WOOD_LIGHT, alpha: 1, kind: 'rect', size: { w: 0.94, h: 0.5 } },
  counter_4: { fill: C_WOOD_LIGHT, alpha: 1, kind: 'rect', size: { w: 0.94, h: 0.5 } },
  stool: { fill: C_WOOD_LIGHT, alpha: 1, kind: 'circle', size: { r: 0.24 } },
  table_round: { fill: C_WOOD_LIGHT, alpha: 1, kind: 'circle', size: { r: 0.3 } },
  table_rect: { fill: C_WOOD_LIGHT, alpha: 1, kind: 'rect', size: { w: 0.9, h: 0.42 } },
  chair: { fill: C_WOOD_DARK, alpha: 1, kind: 'rect', size: { w: 0.5, h: 0.42 } },
  bookshelf: { fill: C_WOOD_DARK, alpha: 1, kind: 'rect', size: { w: 0.88, h: 0.78 } },
  sofa: { fill: C_SOFA_RED, alpha: 1, kind: 'rect', size: { w: 0.9, h: 0.54 } },
  bed: { fill: C_BED_WHITE, alpha: 1, kind: 'rect', size: { w: 0.9, h: 0.54 } },
  desk: { fill: C_WOOD_LIGHT, alpha: 1, kind: 'rect', size: { w: 0.9, h: 0.4 } },
  stove: { fill: C_DARK_GRAY, alpha: 1, kind: 'rect', size: { w: 0.9, h: 0.68 } },
  sink: { fill: C_SINK_LIGHT, alpha: 1, kind: 'rect', size: { w: 0.9, h: 0.58 } },
  cabinet: { fill: C_WOOD_DARK, alpha: 1, kind: 'rect', size: { w: 0.88, h: 0.86 } },
  shelf: { fill: C_WOOD_DARK, alpha: 1, kind: 'rect', size: { w: 0.9, h: 0.14 } },
  plant: { fill: C_POT_GREEN, alpha: 1, kind: 'circle', size: { r: 0.26 } },
  tree: { fill: C_TREE_GREEN, alpha: 1, kind: 'circle', size: { r: 0.36 } },
  fountain: { fill: C_WATER_BLUE, alpha: 0.9, kind: 'circle', size: { r: 0.7 } },
  rock: { fill: C_ROCK_GRAY, alpha: 1, kind: 'circle', size: { r: 0.28 } },
  wood_stack: { fill: C_WOOD_LIGHT, alpha: 1, kind: 'rect', size: { w: 0.56, h: 0.42 } },
  rug: { fill: C_CARPET_RED, alpha: 0.85, kind: 'rect', size: { w: 0.94, h: 0.9 } },
  window: { fill: C_WATER_BLUE, alpha: 0.5, kind: 'rect', size: { w: 0.8, h: 0.8 } },
  screen: { fill: C_SCREEN_BROWN, alpha: 1, kind: 'rect', size: { w: 0.9, h: 0.78 } },
  tea_table: { fill: C_WOOD_DARK, alpha: 1, kind: 'rect', size: { w: 0.9, h: 0.4 } },
  wardrobe: { fill: C_WOOD_DARK, alpha: 1, kind: 'rect', size: { w: 0.88, h: 0.86 } },
  dressing_table: { fill: C_WOOD_LIGHT, alpha: 1, kind: 'rect', size: { w: 0.84, h: 0.46 } },
  incense: { fill: C_GOLD_YELLOW, alpha: 1, kind: 'rect', size: { w: 0.3, h: 0.28 } },
  scroll: { fill: C_SCROLL_TAN, alpha: 1, kind: 'rect', size: { w: 0.4, h: 0.6 } },
  hay: { fill: C_HAY_YELLOW, alpha: 1, kind: 'circle', size: { r: 0.32 } },
  cart: { fill: C_WOOD_DARK, alpha: 1, kind: 'rect', size: { w: 0.84, h: 0.5 } },
};

/** 未知 decor 类型 → 灰色 60% 透明方块 */
const DECOR_UNKNOWN: DecorStyle = { fill: C_UNKNOWN, alpha: 0.6, kind: 'rect', size: { w: 0.9, h: 0.9 } };

/* ── 家具专用绘制（P-0817-N 模板配方产出；左上对齐、可跨格，对齐模板原型 FURNITURE.draw） ──
 * 坐标单位 = tile（相对家具左上角；w/h 可 >1 跨到邻居格）。
 * 解决「家具像像素点」：每件家具画成完整可辨识图形（桌面+腿 / 床+枕被 / 柜+层板书…）。 */

const R = (x: number, y: number, w: number, h: number, color: number, alpha = 1): DecorCmd =>
  ({ shape: 'rect', x, y, w, h, color, alpha });
const C = (x: number, y: number, r: number, color: number, alpha = 1): DecorCmd =>
  ({ shape: 'circle', x, y, r, color, alpha });
const DOTS = (pts: [number, number][], colors: number[]): DecorCmd =>
  ({ shape: 'dots', x: 0.5, y: 0.5, pts, colors, alpha: 1 });

const FLOWER_PTS: [number, number][] = [
  [0.15, 0.4], [0.45, 0.3], [0.75, 0.42], [1.05, 0.32], [1.35, 0.42], [1.65, 0.34], [0.3, 0.55], [1.2, 0.55],
];

const FURNITURE_DRAW: Record<string, DecorCmd[]> = {
  counter: [
    R(0.02, 0.15, 0.96, 0.55, C_WOOD_LIGHT), R(0.02, 0.7, 0.96, 0.26, C_WOOD_DARK),
    R(0.1, 0.22, 0.08, 0.2, C_NOTE_WHITE),
  ],
  counter_4: [
    R(0.02, 0.15, 3.96, 0.55, C_WOOD_LIGHT), R(0.02, 0.7, 3.96, 0.26, C_WOOD_DARK),
    R(0.3, 0.25, 0.3, 0.18, C_NOTE_WHITE), R(2.4, 0.25, 0.3, 0.18, C_NOTE_WHITE),
  ],
  stool: [C(0.5, 0.35, 0.28, C_WOOD_LIGHT), R(0.46, 0.5, 0.08, 0.35, C_WOOD_DARK), C(0.5, 0.9, 0.12, C_DARK_GRAY)],
  table_round: [C(0.5, 0.4, 0.34, C_WOOD_LIGHT), R(0.46, 0.55, 0.08, 0.35, C_WOOD_DARK)],
  table_rect: [
    R(0.03, 0.18, 1.94, 0.5, C_WOOD_LIGHT),
    R(0.1, 0.68, 0.1, 0.28, C_WOOD_DARK), R(0.8, 0.68, 0.1, 0.28, C_WOOD_DARK), R(1.6, 0.68, 0.1, 0.28, C_WOOD_DARK),
  ],
  chair: [
    R(0.15, 0.25, 0.7, 0.45, C_WOOD_DARK), R(0.15, 0.15, 0.7, 0.12, C_WOOD_LIGHT),
    R(0.2, 0.7, 0.1, 0.24, C_WOOD_DARK), R(0.7, 0.7, 0.1, 0.24, C_WOOD_DARK),
  ],
  bookshelf: [
    R(0.02, 0.05, 1.96, 0.9, C_WOOD_DARK),
    R(0.08, 0.12, 0.84, 0.3, C_BED_WHITE), R(0.08, 0.52, 0.84, 0.34, C_GRASS_LIGHT),
    R(1.0, 0.12, 0.92, 0.3, C_SCROLL_TAN), R(1.0, 0.52, 0.92, 0.34, C_SOFA_RED),
  ],
  sofa: [
    R(0.04, 0.22, 1.92, 0.56, C_SOFA_RED), R(0.04, 0.12, 1.92, 0.18, C_SOFA_RED),
    R(0.18, 0.78, 0.12, 0.18, C_DARK_GRAY), R(1.7, 0.78, 0.12, 0.18, C_DARK_GRAY),
  ],
  bed: [
    R(0.03, 0.3, 1.94, 0.62, C_BED_WHITE),
    R(0.03, 0.16, 0.72, 0.76, C_BED_BLUE), R(0.05, 0.3, 0.7, 0.6, C_BED_WHITE),
    R(0.78, 0.28, 1.16, 0.6, C_GRASS_LIGHT), R(0.2, 0.3, 0.12, 0.12, C_NOTE_WHITE),
  ],
  desk: [
    R(0.03, 0.3, 1.94, 0.42, C_WOOD_LIGHT),
    R(0.08, 0.72, 0.1, 0.24, C_WOOD_DARK), R(0.9, 0.72, 0.1, 0.24, C_WOOD_DARK), R(1.7, 0.72, 0.1, 0.24, C_WOOD_DARK),
    R(1.35, 0.1, 0.45, 0.22, C_SCROLL_TAN),
  ],
  stove: [
    R(0.05, 0.15, 0.9, 0.7, C_DARK_GRAY),
    C(0.3, 0.38, 0.14, C_WOOD_DARK), C(0.7, 0.38, 0.14, C_WOOD_DARK),
    C(0.3, 0.7, 0.14, C_DARK_GRAY), C(0.7, 0.7, 0.14, C_DARK_GRAY),
    R(0.42, 0.06, 0.16, 0.12, C_PILLAR_GRAY),
  ],
  sink: [R(0.05, 0.2, 0.9, 0.6, C_SINK_LIGHT), R(0.16, 0.3, 0.68, 0.34, C_PILLAR_GRAY), R(0.42, 0.05, 0.16, 0.18, C_PILLAR_GRAY)],
  cabinet: [
    R(0.04, 0.06, 0.92, 0.9, C_WOOD_DARK),
    R(0.16, 0.18, 0.68, 0.3, C_WOOD_LIGHT), R(0.16, 0.54, 0.68, 0.3, C_WOOD_LIGHT),
    C(0.5, 0.33, 0.05, C_GOLD_YELLOW), C(0.5, 0.69, 0.05, C_GOLD_YELLOW),
  ],
  shelf: [
    R(0.02, 0.3, 1.96, 0.12, C_WOOD_DARK), R(0.02, 0.66, 1.96, 0.12, C_WOOD_DARK),
    R(0.1, 0.36, 0.3, 0.26, C_CHEST_BROWN), R(0.8, 0.36, 0.3, 0.26, C_SCROLL_TAN),
    R(1.5, 0.36, 0.3, 0.26, C_POT_GREEN), R(0.45, 0.72, 0.3, 0.2, C_NOTE_WHITE),
  ],
  chest: [R(0.16, 0.22, 0.68, 0.62, C_CHEST_BROWN), R(0.22, 0.12, 0.56, 0.18, C_CHEST_DARK), R(0.44, 0.3, 0.12, 0.12, C_LAMP_YELLOW)],
  note: [R(0.32, 0.38, 0.36, 0.24, C_NOTE_WHITE), C(0.5, 0.38, 0.06, C_LAMP_POLE)],
  lamp: [R(0.46, 0.4, 0.08, 0.48, C_LAMP_POLE), C(0.5, 0.3, 0.16, C_LAMP_YELLOW)],
  plant: [C(0.5, 0.38, 0.3, C_POT_GREEN), R(0.38, 0.62, 0.24, 0.24, C_BED_SOIL)],
  pillar: [R(0.28, 0.08, 0.44, 0.84, C_PILLAR_GRAY), R(0.36, 0.02, 0.28, 0.1, C_PILLAR_GRAY)],
  tree: [C(0.5, 0.42, 0.38, C_TREE_GREEN), R(0.44, 0.62, 0.12, 0.32, C_TRUNK_BROWN)],
  flower_bed: [R(0.02, 0.6, 1.96, 0.24, C_BED_SOIL), DOTS(FLOWER_PTS, FLOWER_COLORS)],
  bench: [
    R(0.04, 0.24, 1.92, 0.18, C_BENCH_BROWN), R(0.04, 0.44, 1.92, 0.12, C_BENCH_BROWN),
    R(0.14, 0.58, 0.1, 0.3, C_WOOD_DARK), R(1.76, 0.58, 0.1, 0.3, C_WOOD_DARK),
  ],
  fountain: [
    C(1.0, 1.0, 0.78, C_PILLAR_GRAY), C(1.0, 0.92, 0.62, C_WATER_BLUE),
    C(1.0, 0.72, 0.24, C_PILLAR_GRAY), C(1.0, 0.55, 0.1, C_WATER_BLUE),
  ],
  rock: [C(0.5, 0.65, 0.38, C_ROCK_GRAY), C(0.35, 0.5, 0.2, C_ROCK_GRAY), C(0.62, 0.5, 0.16, C_ROCK_GRAY)],
  wood_stack: [R(0.18, 0.28, 0.64, 0.44, C_WOOD_LIGHT), R(0.28, 0.16, 0.44, 0.2, C_WOOD_DARK), C(0.5, 0.12, 0.08, C_WOOD_LIGHT)],
  rug: [R(0.03, 0.03, 2.94, 1.94, C_CARPET_RED, 0.85), R(0.12, 0.12, 2.76, 1.76, C_WOOD_DARK, 0.4)],
  window: [R(0.1, 0.1, 0.8, 0.8, C_WATER_BLUE, 0.5), R(0.44, 0.1, 0.12, 0.8, C_LAMP_POLE), R(0.1, 0.44, 0.8, 0.12, C_LAMP_POLE)],
  screen: [
    R(0.04, 0.1, 1.92, 0.8, C_SCREEN_BROWN),
    R(0.12, 0.18, 0.52, 0.64, C_SCROLL_TAN), R(0.7, 0.18, 0.52, 0.64, C_GRASS_LIGHT), R(1.28, 0.18, 0.56, 0.64, C_SCROLL_TAN),
  ],
  tea_table: [
    R(0.03, 0.3, 1.94, 0.42, C_WOOD_DARK),
    R(0.1, 0.72, 0.1, 0.24, C_WOOD_DARK), R(0.9, 0.72, 0.1, 0.24, C_WOOD_DARK), R(1.7, 0.72, 0.1, 0.24, C_WOOD_DARK),
    C(0.5, 0.4, 0.12, C_GOLD_YELLOW), C(1.2, 0.42, 0.1, C_SCROLL_TAN),
  ],
  wardrobe: [
    R(0.04, 0.05, 1.92, 0.9, C_WOOD_DARK),
    R(0.1, 0.12, 0.84, 0.76, C_WOOD_LIGHT), R(1.02, 0.12, 0.84, 0.76, C_WOOD_LIGHT),
    C(0.5, 0.5, 0.05, C_GOLD_YELLOW), C(1.44, 0.5, 0.05, C_GOLD_YELLOW),
  ],
  dressing_table: [
    R(0.08, 0.4, 0.84, 0.46, C_WOOD_LIGHT),
    R(0.16, 0.86, 0.1, 0.1, C_WOOD_DARK), R(0.74, 0.86, 0.1, 0.1, C_WOOD_DARK),
    C(0.5, 0.26, 0.18, C_NOTE_WHITE), R(0.44, 0.06, 0.12, 0.14, C_LAMP_POLE),
  ],
  incense: [R(0.34, 0.5, 0.32, 0.3, C_GOLD_YELLOW), R(0.47, 0.26, 0.06, 0.26, C_LAMP_POLE), C(0.5, 0.22, 0.05, C_LAMP_YELLOW)],
  scroll: [R(0.3, 0.18, 0.4, 0.64, C_SCROLL_TAN), R(0.28, 0.14, 0.44, 0.08, C_WOOD_DARK), R(0.28, 0.78, 0.44, 0.08, C_WOOD_DARK)],
  hay: [C(0.5, 0.55, 0.36, C_HAY_YELLOW), C(0.35, 0.4, 0.2, C_HAY_YELLOW), C(0.65, 0.42, 0.18, C_HAY_YELLOW)],
  cart: [
    R(0.05, 0.25, 1.7, 0.5, C_WOOD_DARK),
    C(0.35, 0.82, 0.14, C_DARK_GRAY), C(1.45, 0.82, 0.14, C_DARK_GRAY),
    R(1.72, 0.18, 0.2, 0.62, C_WOOD_LIGHT),
  ],
  // P-0817-Q（外部/内部分离）：建筑外观屋顶 + 门（外部地图 decor 类型）
  roof: [
    R(0.02, 0.02, 0.96, 0.96, C_CARPET_RED),
    R(0.02, 0.02, 0.96, 0.16, C_WOOD_DARK),          // 檐
    R(0.02, 0.5, 0.96, 0.06, C_WOOD_DARK),           // 屋脊线
  ],
  door: [
    R(0.1, 0.1, 0.8, 0.8, C_WOOD_DARK),
    R(0.18, 0.18, 0.64, 0.68, C_WOOD_LIGHT),
    C(0.68, 0.5, 0.06, C_GOLD_YELLOW),               // 门把手
  ],
};

/**
 * decor type → 绘图命令序列（恒非 null——未知也有灰色兜底）。
 * 家具类型（模板配方产出）走 FURNITURE_DRAW 跨格完整图形；其余走 DECOR_STYLES 色块兜底。
 */
export function decorStyle(type: string): DecorCmd[] {
  const f = FURNITURE_DRAW[type];
  if (f) return f;
  const s = DECOR_STYLES[type] ?? DECOR_UNKNOWN;
  return styleToCmds(s, type);
}

/* ── ③ spawnMarkers 类别映射 ── */

const MARKER_STYLES: Record<string, DecorStyle> = {
  grass: { fill: C_GRASS_LIGHT, alpha: 0.9, kind: 'triangle', size: { w: 0.26, h: 0.4 } },
  debris: { fill: C_DEBRIS_BROWN, alpha: 0.95, kind: 'rect', size: { w: 0.2, h: 0.18 } },
};

/** 未知类别 → 灰色小点 */
const MARKER_UNKNOWN: DecorStyle = { fill: C_UNKNOWN, alpha: 0.8, kind: 'circle', size: { r: 0.12 } };

/** spawnMarkers 类别 → 绘图命令序列（grass 浅绿小三角 / debris 棕色小方块 / 未知 → 灰色小点） */
export function markerStyle(category: string): DecorCmd[] {
  const s = MARKER_STYLES[category] ?? MARKER_UNKNOWN;
  return styleToCmds(s, category);
}

/* ── ④ overlay 前景遮罩映射 ── */

export interface OverlayStyle {
  fill: number;
  alpha: number;
}

/** overlay 类型 → 遮罩样式（canopy → 深绿 alpha 0.35；未知 → 灰色 alpha 0.3） */
export function overlayStyle(type: string): OverlayStyle {
  if (type === 'canopy') return { fill: C_TREE_GREEN, alpha: 0.35 };
  return { fill: C_UNKNOWN, alpha: 0.3 };
}

/* ── 样式 → 命令序列（集中一处，纯函数） ── */

function styleToCmds(s: DecorStyle, type: string): DecorCmd[] {
  const cmds: DecorCmd[] = [];
  switch (s.kind) {
    case 'rect': {
      const w = s.size.w ?? 0.5, h = s.size.h ?? 0.5;
      cmds.push({ shape: 'rect', x: (1 - w) / 2, y: (1 - h) / 2, w, h, color: s.fill, alpha: s.alpha });
      // 类型化附加细节（程序化简单几何，让色块可辨识）
      if (type === 'fence') {
        cmds.push({ shape: 'rect', x: 0.12, y: 0.34, w: 0.08, h: 0.24, color: s.fill, alpha: 1 });
        cmds.push({ shape: 'rect', x: 0.8, y: 0.34, w: 0.08, h: 0.24, color: s.fill, alpha: 1 });
      } else if (type === 'bench') {
        cmds.push({ shape: 'rect', x: 0.06, y: 0.36, w: 0.88, h: 0.08, color: s.fill, alpha: 1 }); // 靠背
        cmds.push({ shape: 'rect', x: 0.14, y: 0.68, w: 0.08, h: 0.16, color: C_LAMP_POLE, alpha: 1 }); // 腿
        cmds.push({ shape: 'rect', x: 0.78, y: 0.68, w: 0.08, h: 0.16, color: C_LAMP_POLE, alpha: 1 });
      } else if (type === 'chest') {
        cmds.push({ shape: 'rect', x: 0.28, y: 0.26, w: 0.44, h: 0.1, color: C_CHEST_DARK, alpha: 1 }); // 箱盖
        cmds.push({ shape: 'rect', x: 0.46, y: 0.44, w: 0.08, h: 0.1, color: C_LAMP_YELLOW, alpha: 0.9 }); // 锁
      } else if (type === 'note') {
        cmds.push({ shape: 'rect', x: 0.42, y: 0.52, w: 0.16, h: 0.08, color: C_LAMP_POLE, alpha: 0.8 }); // 图钉
      } else if (type === 'pillar') {
        cmds.push({ shape: 'rect', x: 0.22, y: 0.06, w: 0.56, h: 0.1, color: C_PILLAR_GRAY, alpha: 1 }); // 柱顶
      } else if (type === 'bed') {
        cmds.push({ shape: 'rect', x: 0.08, y: 0.1, w: 0.34, h: 0.5, color: C_BED_BLUE, alpha: 1 }); // 枕头
        cmds.push({ shape: 'rect', x: 0.42, y: 0.12, w: 0.5, h: 0.42, color: C_POT_GREEN, alpha: 0.9 }); // 被子
      } else if (type === 'table_rect') {
        cmds.push({ shape: 'rect', x: 0.12, y: 0.64, w: 0.08, h: 0.24, color: C_WOOD_DARK, alpha: 1 }); // 桌腿
        cmds.push({ shape: 'rect', x: 0.8, y: 0.64, w: 0.08, h: 0.24, color: C_WOOD_DARK, alpha: 1 });
      } else if (type === 'bookshelf') {
        cmds.push({ shape: 'rect', x: 0.08, y: 0.2, w: 0.84, h: 0.12, color: C_WOOD_LIGHT, alpha: 1 }); // 层板
        cmds.push({ shape: 'rect', x: 0.08, y: 0.52, w: 0.84, h: 0.12, color: C_WOOD_LIGHT, alpha: 1 });
      } else if (type === 'stove') {
        cmds.push({ shape: 'circle', x: 0.32, y: 0.3, r: 0.12, color: C_WOOD_DARK, alpha: 1 }); // 灶眼
        cmds.push({ shape: 'circle', x: 0.68, y: 0.3, r: 0.12, color: C_WOOD_DARK, alpha: 1 });
      } else if (type === 'window') {
        cmds.push({ shape: 'rect', x: 0.44, y: 0.1, w: 0.12, h: 0.8, color: C_LAMP_POLE, alpha: 0.8 }); // 窗框
        cmds.push({ shape: 'rect', x: 0.1, y: 0.44, w: 0.8, h: 0.12, color: C_LAMP_POLE, alpha: 0.8 });
      } else if (type === 'cart') {
        cmds.push({ shape: 'circle', x: 0.3, y: 0.78, r: 0.12, color: C_DARK_GRAY, alpha: 1 }); // 车轮
        cmds.push({ shape: 'circle', x: 0.7, y: 0.78, r: 0.12, color: C_DARK_GRAY, alpha: 1 });
      }
      break;
    }
    case 'circle': {
      const r = s.size.r ?? 0.2;
      cmds.push({ shape: 'circle', x: 0.5, y: type === 'tree_oak' ? 0.42 : 0.45, r, color: s.fill, alpha: s.alpha });
      if (type === 'tree_oak') {
        cmds.push({ shape: 'rect', x: 0.44, y: 0.62, w: 0.12, h: 0.32, color: C_TRUNK_BROWN, alpha: 1 }); // 树干
      } else if (type === 'lamp') {
        cmds.push({ shape: 'rect', x: 0.47, y: 0.4, w: 0.06, h: 0.44, color: C_LAMP_POLE, alpha: 1 }); // 灯柱
      } else if (type === 'tree') {
        cmds.push({ shape: 'rect', x: 0.44, y: 0.62, w: 0.12, h: 0.32, color: C_TRUNK_BROWN, alpha: 1 }); // 树干
      } else if (type === 'fountain') {
        cmds.push({ shape: 'circle', x: 0.5, y: 0.5, r: 0.28, color: C_PILLAR_GRAY, alpha: 1 }); // 池沿
      } else if (type === 'rock') {
        cmds.push({ shape: 'circle', x: 0.4, y: 0.55, r: 0.16, color: C_ROCK_GRAY, alpha: 1 }); // 副石
      }
      break;
    }
    case 'triangle': {
      const w = s.size.w ?? 0.3, h = s.size.h ?? 0.4;
      cmds.push({ shape: 'triangle', x: 0.5, y: 0.74, w, h, color: s.fill, alpha: s.alpha });
      cmds.push({ shape: 'triangle', x: 0.3, y: 0.78, w: w * 0.7, h: h * 0.75, color: C_GRASS_MID, alpha: 0.85 });
      break;
    }
    case 'dots': {
      const count = Math.min(DOT_OFFSETS.length, 6);
      cmds.push({ shape: 'dots', x: 0.5, y: 0.5, pts: DOT_OFFSETS.slice(0, count), colors: s.sub ?? FLOWER_COLORS, alpha: 1 });
      // 花坛底槽（bed 轮廓）
      cmds.push({ shape: 'rect', x: 0.05, y: 0.6, w: 0.9, h: 0.16, color: s.fill, alpha: 1 });
      break;
    }
  }
  return cmds;
}

/* ── 渲染计划（纯函数，冒烟可测） ── */

export interface DecorPlanItem {
  /** 来源：objects 层 / decor 列表 / spawnMarkers */
  layer: 'objects' | 'decor' | 'markers';
  /** 类型名（objects/decor 的 type 或 markers 类别名） */
  type: string;
  /** Stable authored id when this item came from map.decor. */
  id?: string;
  x: number;
  y: number;
  /** 行深度（1 + (y+0.5)/H；北侧小 → 被南侧遮挡） */
  depth: number;
  cmds: DecorCmd[];
}

export interface OverlayPlanItem {
  type: string;
  x: number;
  y: number;
  style: OverlayStyle;
}

export interface DecorPlan {
  /** 全部 y 排序装饰（objects + decor + spawnMarkers，按 depth 升序 = 北→南） */
  items: DecorPlanItem[];
  /** 前景遮罩（永远盖住角色，depth 最高） */
  overlay: OverlayPlanItem[];
  /** water=true 格（蓝色半透明叠加） */
  water: { x: number; y: number }[];
}

/**
 * 构建装饰渲染计划（契约 v0.2 全部可选键缺失 → 空计划，v1 地图逐像素零回归）。
 */
export function buildDecorPlan(map: ScriptMap): DecorPlan {
  const H = Math.max(1, map.height || map.layers.ground.length || 1);
  const items: DecorPlanItem[] = [];

  // ① layers.objects（Front 层）：null 元素跳过
  const objects = map.layers?.objects;
  if (objects) {
    for (let y = 0; y < objects.length; y++) {
      const row = objects[y];
      if (!row) continue;
      for (let x = 0; x < row.length; x++) {
        const cmds = objectStyle(row[x]);
        if (!cmds) continue;
        items.push({ layer: 'objects', type: row[x] as string, x, y, depth: decorDepth(y, H), cmds });
      }
    }
  }

  // ② spawnMarkers：每类坐标批量铺色块
  const markers = map.spawnMarkers;
  if (markers) {
    for (const [cat, pts] of Object.entries(markers)) {
      for (const p of pts || []) {
        items.push({ layer: 'markers', type: cat, x: p[0], y: p[1], depth: decorDepth(p[1], H), cmds: markerStyle(cat) });
      }
    }
  }

  // ③ decor：显式装饰物（y 排序位）
  const decor = map.decor;
  if (decor) {
    for (const d of decor) {
      items.push({ layer: 'decor', id: d.id, type: d.type, x: d.tile[0], y: d.tile[1], depth: decorDepth(d.tile[1], H), cmds: decorStyle(d.type) });
    }
  }

  // y 排序：北侧（depth 小）在前 → 深度小 → 被南侧遮挡（星露谷 standingY 范式）；同深按 x
  items.sort((a, b) => (a.depth - b.depth) || (a.x - b.x));

  // ④ layers.overlay：前景遮罩（永远盖住角色，不做 y 排序）
  const overlay: OverlayPlanItem[] = [];
  const ovl = map.layers?.overlay;
  if (ovl) {
    for (let y = 0; y < ovl.length; y++) {
      const row = ovl[y];
      if (!row) continue;
      for (let x = 0; x < row.length; x++) {
        const t = row[x];
        if (t) overlay.push({ type: t, x, y, style: overlayStyle(t) });
      }
    }
  }

  // ⑤ tileProps：water=true → 蓝色半透明叠加（成本低；blocked 不做视觉）
  const water: { x: number; y: number }[] = [];
  const tp = map.tileProps;
  if (tp) {
    for (const [k, v] of Object.entries(tp)) {
      if (v && v.water === true) {
        const comma = k.indexOf(',');
        const x = Number(k.slice(0, comma)), y = Number(k.slice(comma + 1));
        if (comma > 0 && Number.isFinite(x) && Number.isFinite(y)) water.push({ x, y });
      }
    }
  }

  return { items, overlay, water };
}
