var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __getOwnPropNames = Object.getOwnPropertyNames;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};
var __copyProps = (to, from, except, desc) => {
  if (from && typeof from === "object" || typeof from === "function") {
    for (let key of __getOwnPropNames(from))
      if (!__hasOwnProp.call(to, key) && key !== except)
        __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
  }
  return to;
};
var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

// src/phaser/decorData.ts
var decorData_exports = {};
__export(decorData_exports, {
  CHAR_LAYER_OFFSET: () => CHAR_LAYER_OFFSET,
  C_BED_BLUE: () => C_BED_BLUE,
  C_BED_SOIL: () => C_BED_SOIL,
  C_BED_WHITE: () => C_BED_WHITE,
  C_BENCH_BROWN: () => C_BENCH_BROWN,
  C_CARPET_RED: () => C_CARPET_RED,
  C_CHEST_BROWN: () => C_CHEST_BROWN,
  C_CHEST_DARK: () => C_CHEST_DARK,
  C_DARK_GRAY: () => C_DARK_GRAY,
  C_DEBRIS_BROWN: () => C_DEBRIS_BROWN,
  C_DEBRIS_DARK: () => C_DEBRIS_DARK,
  C_FENCE_BROWN: () => C_FENCE_BROWN,
  C_GOLD_YELLOW: () => C_GOLD_YELLOW,
  C_GRASS_LIGHT: () => C_GRASS_LIGHT,
  C_GRASS_MID: () => C_GRASS_MID,
  C_HAY_YELLOW: () => C_HAY_YELLOW,
  C_LAMP_POLE: () => C_LAMP_POLE,
  C_LAMP_YELLOW: () => C_LAMP_YELLOW,
  C_NOTE_WHITE: () => C_NOTE_WHITE,
  C_PILLAR_GRAY: () => C_PILLAR_GRAY,
  C_POT_GREEN: () => C_POT_GREEN,
  C_ROCK_GRAY: () => C_ROCK_GRAY,
  C_SCREEN_BROWN: () => C_SCREEN_BROWN,
  C_SCROLL_TAN: () => C_SCROLL_TAN,
  C_SINK_LIGHT: () => C_SINK_LIGHT,
  C_SOFA_RED: () => C_SOFA_RED,
  C_TREE_GREEN: () => C_TREE_GREEN,
  C_TRUNK_BROWN: () => C_TRUNK_BROWN,
  C_UNKNOWN: () => C_UNKNOWN,
  C_WATER_BLUE: () => C_WATER_BLUE,
  C_WOOD_DARK: () => C_WOOD_DARK,
  C_WOOD_LIGHT: () => C_WOOD_LIGHT,
  DEPTH_OVERLAY: () => DEPTH_OVERLAY,
  DEPTH_WATER: () => DEPTH_WATER,
  DOT_OFFSETS: () => DOT_OFFSETS,
  FLOWER_COLORS: () => FLOWER_COLORS,
  buildDecorPlan: () => buildDecorPlan,
  charDepth: () => charDepth,
  decorDepth: () => decorDepth,
  decorStyle: () => decorStyle,
  drawDecorCmds: () => drawDecorCmds,
  markerStyle: () => markerStyle,
  objectStyle: () => objectStyle,
  overlayStyle: () => overlayStyle
});
module.exports = __toCommonJS(decorData_exports);
function drawDecorCmds(g, cmds, px, py, ts) {
  for (const c of cmds) {
    switch (c.shape) {
      case "rect":
        g.fillStyle(c.color, c.alpha);
        g.fillRect(px + c.x * ts, py + c.y * ts, Math.max(0.5, c.w * ts), Math.max(0.5, c.h * ts));
        break;
      case "circle":
        g.fillStyle(c.color, c.alpha);
        g.fillCircle(px + c.x * ts, py + c.y * ts, Math.max(0.5, c.r * ts));
        break;
      case "triangle":
        g.fillStyle(c.color, c.alpha);
        g.fillTriangle(
          px + (c.x - c.w / 2) * ts,
          py + (c.y + c.h) * ts,
          px + (c.x + c.w / 2) * ts,
          py + (c.y + c.h) * ts,
          px + c.x * ts,
          py + c.y * ts
        );
        break;
      case "dots":
        for (let i = 0; i < c.pts.length; i++) {
          g.fillStyle(c.colors[i % c.colors.length], c.alpha);
          g.fillCircle(px + c.pts[i][0] * ts, py + c.pts[i][1] * ts, Math.max(0.5, ts * 0.09));
        }
        break;
    }
  }
}
var C_TREE_GREEN = 1988145;
var C_TRUNK_BROWN = 7029795;
var C_FENCE_BROWN = 8149556;
var C_PILLAR_GRAY = 10134961;
var C_BENCH_BROWN = 9067051;
var C_LAMP_YELLOW = 16765286;
var C_LAMP_POLE = 4871528;
var C_CHEST_BROWN = 8145441;
var C_CHEST_DARK = 6107922;
var C_NOTE_WHITE = 15857145;
var C_GRASS_LIGHT = 8112495;
var C_GRASS_MID = 6271834;
var C_DEBRIS_BROWN = 9071434;
var C_DEBRIS_DARK = 7692358;
var C_UNKNOWN = 7041664;
var C_WATER_BLUE = 3718648;
var C_BED_SOIL = 8149556;
var C_WOOD_LIGHT = 11565647;
var C_WOOD_DARK = 7029795;
var C_SOFA_RED = 11753039;
var C_BED_WHITE = 15857145;
var C_BED_BLUE = 4881318;
var C_DARK_GRAY = 3621201;
var C_SINK_LIGHT = 13358561;
var C_POT_GREEN = 3046706;
var C_ROCK_GRAY = 8424083;
var C_HAY_YELLOW = 14729320;
var C_SCREEN_BROWN = 10506797;
var C_SCROLL_TAN = 16115400;
var C_GOLD_YELLOW = 13934615;
var C_CARPET_RED = 10239293;
var FLOWER_COLORS = [15087942, 16032353, 15320170, 13936037, 15893634];
var DOT_OFFSETS = [
  [0.22, 0.28],
  [0.45, 0.22],
  [0.68, 0.32],
  [0.3, 0.5],
  [0.56, 0.5],
  [0.78, 0.46],
  [0.24, 0.6],
  [0.5, 0.62],
  [0.72, 0.6]
];
var DEPTH_WATER = 0.5;
var DEPTH_OVERLAY = 5;
var CHAR_LAYER_OFFSET = 0.01;
function decorDepth(y, H) {
  return 1 + (y + 0.5) / Math.max(1, H);
}
function charDepth(py, mapPxH) {
  const norm = Math.min(0.9999, Math.max(0, py / Math.max(1, mapPxH)));
  return 1 + norm + CHAR_LAYER_OFFSET;
}
var OBJECT_STYLES = {
  tree_oak: { fill: C_TREE_GREEN, alpha: 1, kind: "circle", size: { r: 0.36 } },
  fence: { fill: C_FENCE_BROWN, alpha: 1, kind: "rect", size: { w: 0.84, h: 0.1 } },
  flower_bed: { fill: C_BED_SOIL, alpha: 1, kind: "dots", size: { w: 0.9, h: 0.16 }, sub: FLOWER_COLORS }
};
var OBJECT_UNKNOWN = { fill: C_UNKNOWN, alpha: 0.6, kind: "rect", size: { w: 0.9, h: 0.9 } };
function objectStyle(type) {
  if (type === null || type === void 0) return null;
  const s = OBJECT_STYLES[type] ?? OBJECT_UNKNOWN;
  return styleToCmds(s, type);
}
var DECOR_STYLES = {
  pillar: { fill: C_PILLAR_GRAY, alpha: 1, kind: "rect", size: { w: 0.44, h: 0.84 } },
  flower_bed: { fill: C_BED_SOIL, alpha: 1, kind: "dots", size: { w: 0.9, h: 0.16 }, sub: FLOWER_COLORS },
  bench: { fill: C_BENCH_BROWN, alpha: 1, kind: "rect", size: { w: 0.88, h: 0.16 } },
  lamp: { fill: C_LAMP_YELLOW, alpha: 1, kind: "circle", size: { r: 0.16 } },
  chest: { fill: C_CHEST_BROWN, alpha: 1, kind: "rect", size: { w: 0.44, h: 0.4 } },
  note: { fill: C_NOTE_WHITE, alpha: 0.9, kind: "rect", size: { w: 0.28, h: 0.2 } },
  // P-0817-N（L2 房间家具）：模板配方产出的家具类型色块（单格图标，锚定格渲染）
  counter: { fill: C_WOOD_LIGHT, alpha: 1, kind: "rect", size: { w: 0.94, h: 0.5 } },
  counter_4: { fill: C_WOOD_LIGHT, alpha: 1, kind: "rect", size: { w: 0.94, h: 0.5 } },
  stool: { fill: C_WOOD_LIGHT, alpha: 1, kind: "circle", size: { r: 0.24 } },
  table_round: { fill: C_WOOD_LIGHT, alpha: 1, kind: "circle", size: { r: 0.3 } },
  table_rect: { fill: C_WOOD_LIGHT, alpha: 1, kind: "rect", size: { w: 0.9, h: 0.42 } },
  chair: { fill: C_WOOD_DARK, alpha: 1, kind: "rect", size: { w: 0.5, h: 0.42 } },
  bookshelf: { fill: C_WOOD_DARK, alpha: 1, kind: "rect", size: { w: 0.88, h: 0.78 } },
  sofa: { fill: C_SOFA_RED, alpha: 1, kind: "rect", size: { w: 0.9, h: 0.54 } },
  bed: { fill: C_BED_WHITE, alpha: 1, kind: "rect", size: { w: 0.9, h: 0.54 } },
  desk: { fill: C_WOOD_LIGHT, alpha: 1, kind: "rect", size: { w: 0.9, h: 0.4 } },
  stove: { fill: C_DARK_GRAY, alpha: 1, kind: "rect", size: { w: 0.9, h: 0.68 } },
  sink: { fill: C_SINK_LIGHT, alpha: 1, kind: "rect", size: { w: 0.9, h: 0.58 } },
  cabinet: { fill: C_WOOD_DARK, alpha: 1, kind: "rect", size: { w: 0.88, h: 0.86 } },
  shelf: { fill: C_WOOD_DARK, alpha: 1, kind: "rect", size: { w: 0.9, h: 0.14 } },
  plant: { fill: C_POT_GREEN, alpha: 1, kind: "circle", size: { r: 0.26 } },
  tree: { fill: C_TREE_GREEN, alpha: 1, kind: "circle", size: { r: 0.36 } },
  fountain: { fill: C_WATER_BLUE, alpha: 0.9, kind: "circle", size: { r: 0.7 } },
  rock: { fill: C_ROCK_GRAY, alpha: 1, kind: "circle", size: { r: 0.28 } },
  wood_stack: { fill: C_WOOD_LIGHT, alpha: 1, kind: "rect", size: { w: 0.56, h: 0.42 } },
  rug: { fill: C_CARPET_RED, alpha: 0.85, kind: "rect", size: { w: 0.94, h: 0.9 } },
  window: { fill: C_WATER_BLUE, alpha: 0.5, kind: "rect", size: { w: 0.8, h: 0.8 } },
  screen: { fill: C_SCREEN_BROWN, alpha: 1, kind: "rect", size: { w: 0.9, h: 0.78 } },
  tea_table: { fill: C_WOOD_DARK, alpha: 1, kind: "rect", size: { w: 0.9, h: 0.4 } },
  wardrobe: { fill: C_WOOD_DARK, alpha: 1, kind: "rect", size: { w: 0.88, h: 0.86 } },
  dressing_table: { fill: C_WOOD_LIGHT, alpha: 1, kind: "rect", size: { w: 0.84, h: 0.46 } },
  incense: { fill: C_GOLD_YELLOW, alpha: 1, kind: "rect", size: { w: 0.3, h: 0.28 } },
  scroll: { fill: C_SCROLL_TAN, alpha: 1, kind: "rect", size: { w: 0.4, h: 0.6 } },
  hay: { fill: C_HAY_YELLOW, alpha: 1, kind: "circle", size: { r: 0.32 } },
  cart: { fill: C_WOOD_DARK, alpha: 1, kind: "rect", size: { w: 0.84, h: 0.5 } }
};
var DECOR_UNKNOWN = { fill: C_UNKNOWN, alpha: 0.6, kind: "rect", size: { w: 0.9, h: 0.9 } };
var R = (x, y, w, h, color, alpha = 1) => ({ shape: "rect", x, y, w, h, color, alpha });
var C = (x, y, r, color, alpha = 1) => ({ shape: "circle", x, y, r, color, alpha });
var DOTS = (pts, colors) => ({ shape: "dots", x: 0.5, y: 0.5, pts, colors, alpha: 1 });
var FLOWER_PTS = [
  [0.15, 0.4],
  [0.45, 0.3],
  [0.75, 0.42],
  [1.05, 0.32],
  [1.35, 0.42],
  [1.65, 0.34],
  [0.3, 0.55],
  [1.2, 0.55]
];
var FURNITURE_DRAW = {
  counter: [
    R(0.02, 0.15, 0.96, 0.55, C_WOOD_LIGHT),
    R(0.02, 0.7, 0.96, 0.26, C_WOOD_DARK),
    R(0.1, 0.22, 0.08, 0.2, C_NOTE_WHITE)
  ],
  counter_4: [
    R(0.02, 0.15, 3.96, 0.55, C_WOOD_LIGHT),
    R(0.02, 0.7, 3.96, 0.26, C_WOOD_DARK),
    R(0.3, 0.25, 0.3, 0.18, C_NOTE_WHITE),
    R(2.4, 0.25, 0.3, 0.18, C_NOTE_WHITE)
  ],
  stool: [C(0.5, 0.35, 0.28, C_WOOD_LIGHT), R(0.46, 0.5, 0.08, 0.35, C_WOOD_DARK), C(0.5, 0.9, 0.12, C_DARK_GRAY)],
  table_round: [C(0.5, 0.4, 0.34, C_WOOD_LIGHT), R(0.46, 0.55, 0.08, 0.35, C_WOOD_DARK)],
  table_rect: [
    R(0.03, 0.18, 1.94, 0.5, C_WOOD_LIGHT),
    R(0.1, 0.68, 0.1, 0.28, C_WOOD_DARK),
    R(0.8, 0.68, 0.1, 0.28, C_WOOD_DARK),
    R(1.6, 0.68, 0.1, 0.28, C_WOOD_DARK)
  ],
  chair: [
    R(0.15, 0.25, 0.7, 0.45, C_WOOD_DARK),
    R(0.15, 0.15, 0.7, 0.12, C_WOOD_LIGHT),
    R(0.2, 0.7, 0.1, 0.24, C_WOOD_DARK),
    R(0.7, 0.7, 0.1, 0.24, C_WOOD_DARK)
  ],
  bookshelf: [
    R(0.02, 0.05, 1.96, 0.9, C_WOOD_DARK),
    R(0.08, 0.12, 0.84, 0.3, C_BED_WHITE),
    R(0.08, 0.52, 0.84, 0.34, C_GRASS_LIGHT),
    R(1, 0.12, 0.92, 0.3, C_SCROLL_TAN),
    R(1, 0.52, 0.92, 0.34, C_SOFA_RED)
  ],
  sofa: [
    R(0.04, 0.22, 1.92, 0.56, C_SOFA_RED),
    R(0.04, 0.12, 1.92, 0.18, C_SOFA_RED),
    R(0.18, 0.78, 0.12, 0.18, C_DARK_GRAY),
    R(1.7, 0.78, 0.12, 0.18, C_DARK_GRAY)
  ],
  bed: [
    R(0.03, 0.3, 1.94, 0.62, C_BED_WHITE),
    R(0.03, 0.16, 0.72, 0.76, C_BED_BLUE),
    R(0.05, 0.3, 0.7, 0.6, C_BED_WHITE),
    R(0.78, 0.28, 1.16, 0.6, C_GRASS_LIGHT),
    R(0.2, 0.3, 0.12, 0.12, C_NOTE_WHITE)
  ],
  desk: [
    R(0.03, 0.3, 1.94, 0.42, C_WOOD_LIGHT),
    R(0.08, 0.72, 0.1, 0.24, C_WOOD_DARK),
    R(0.9, 0.72, 0.1, 0.24, C_WOOD_DARK),
    R(1.7, 0.72, 0.1, 0.24, C_WOOD_DARK),
    R(1.35, 0.1, 0.45, 0.22, C_SCROLL_TAN)
  ],
  stove: [
    R(0.05, 0.15, 0.9, 0.7, C_DARK_GRAY),
    C(0.3, 0.38, 0.14, C_WOOD_DARK),
    C(0.7, 0.38, 0.14, C_WOOD_DARK),
    C(0.3, 0.7, 0.14, C_DARK_GRAY),
    C(0.7, 0.7, 0.14, C_DARK_GRAY),
    R(0.42, 0.06, 0.16, 0.12, C_PILLAR_GRAY)
  ],
  sink: [R(0.05, 0.2, 0.9, 0.6, C_SINK_LIGHT), R(0.16, 0.3, 0.68, 0.34, C_PILLAR_GRAY), R(0.42, 0.05, 0.16, 0.18, C_PILLAR_GRAY)],
  cabinet: [
    R(0.04, 0.06, 0.92, 0.9, C_WOOD_DARK),
    R(0.16, 0.18, 0.68, 0.3, C_WOOD_LIGHT),
    R(0.16, 0.54, 0.68, 0.3, C_WOOD_LIGHT),
    C(0.5, 0.33, 0.05, C_GOLD_YELLOW),
    C(0.5, 0.69, 0.05, C_GOLD_YELLOW)
  ],
  shelf: [
    R(0.02, 0.3, 1.96, 0.12, C_WOOD_DARK),
    R(0.02, 0.66, 1.96, 0.12, C_WOOD_DARK),
    R(0.1, 0.36, 0.3, 0.26, C_CHEST_BROWN),
    R(0.8, 0.36, 0.3, 0.26, C_SCROLL_TAN),
    R(1.5, 0.36, 0.3, 0.26, C_POT_GREEN),
    R(0.45, 0.72, 0.3, 0.2, C_NOTE_WHITE)
  ],
  chest: [R(0.16, 0.22, 0.68, 0.62, C_CHEST_BROWN), R(0.22, 0.12, 0.56, 0.18, C_CHEST_DARK), R(0.44, 0.3, 0.12, 0.12, C_LAMP_YELLOW)],
  note: [R(0.32, 0.38, 0.36, 0.24, C_NOTE_WHITE), C(0.5, 0.38, 0.06, C_LAMP_POLE)],
  lamp: [R(0.46, 0.4, 0.08, 0.48, C_LAMP_POLE), C(0.5, 0.3, 0.16, C_LAMP_YELLOW)],
  plant: [C(0.5, 0.38, 0.3, C_POT_GREEN), R(0.38, 0.62, 0.24, 0.24, C_BED_SOIL)],
  pillar: [R(0.28, 0.08, 0.44, 0.84, C_PILLAR_GRAY), R(0.36, 0.02, 0.28, 0.1, C_PILLAR_GRAY)],
  tree: [C(0.5, 0.42, 0.38, C_TREE_GREEN), R(0.44, 0.62, 0.12, 0.32, C_TRUNK_BROWN)],
  flower_bed: [R(0.02, 0.6, 1.96, 0.24, C_BED_SOIL), DOTS(FLOWER_PTS, FLOWER_COLORS)],
  bench: [
    R(0.04, 0.24, 1.92, 0.18, C_BENCH_BROWN),
    R(0.04, 0.44, 1.92, 0.12, C_BENCH_BROWN),
    R(0.14, 0.58, 0.1, 0.3, C_WOOD_DARK),
    R(1.76, 0.58, 0.1, 0.3, C_WOOD_DARK)
  ],
  fountain: [
    C(1, 1, 0.78, C_PILLAR_GRAY),
    C(1, 0.92, 0.62, C_WATER_BLUE),
    C(1, 0.72, 0.24, C_PILLAR_GRAY),
    C(1, 0.55, 0.1, C_WATER_BLUE)
  ],
  rock: [C(0.5, 0.65, 0.38, C_ROCK_GRAY), C(0.35, 0.5, 0.2, C_ROCK_GRAY), C(0.62, 0.5, 0.16, C_ROCK_GRAY)],
  wood_stack: [R(0.18, 0.28, 0.64, 0.44, C_WOOD_LIGHT), R(0.28, 0.16, 0.44, 0.2, C_WOOD_DARK), C(0.5, 0.12, 0.08, C_WOOD_LIGHT)],
  rug: [R(0.03, 0.03, 2.94, 1.94, C_CARPET_RED, 0.85), R(0.12, 0.12, 2.76, 1.76, C_WOOD_DARK, 0.4)],
  window: [R(0.1, 0.1, 0.8, 0.8, C_WATER_BLUE, 0.5), R(0.44, 0.1, 0.12, 0.8, C_LAMP_POLE), R(0.1, 0.44, 0.8, 0.12, C_LAMP_POLE)],
  screen: [
    R(0.04, 0.1, 1.92, 0.8, C_SCREEN_BROWN),
    R(0.12, 0.18, 0.52, 0.64, C_SCROLL_TAN),
    R(0.7, 0.18, 0.52, 0.64, C_GRASS_LIGHT),
    R(1.28, 0.18, 0.56, 0.64, C_SCROLL_TAN)
  ],
  tea_table: [
    R(0.03, 0.3, 1.94, 0.42, C_WOOD_DARK),
    R(0.1, 0.72, 0.1, 0.24, C_WOOD_DARK),
    R(0.9, 0.72, 0.1, 0.24, C_WOOD_DARK),
    R(1.7, 0.72, 0.1, 0.24, C_WOOD_DARK),
    C(0.5, 0.4, 0.12, C_GOLD_YELLOW),
    C(1.2, 0.42, 0.1, C_SCROLL_TAN)
  ],
  wardrobe: [
    R(0.04, 0.05, 1.92, 0.9, C_WOOD_DARK),
    R(0.1, 0.12, 0.84, 0.76, C_WOOD_LIGHT),
    R(1.02, 0.12, 0.84, 0.76, C_WOOD_LIGHT),
    C(0.5, 0.5, 0.05, C_GOLD_YELLOW),
    C(1.44, 0.5, 0.05, C_GOLD_YELLOW)
  ],
  dressing_table: [
    R(0.08, 0.4, 0.84, 0.46, C_WOOD_LIGHT),
    R(0.16, 0.86, 0.1, 0.1, C_WOOD_DARK),
    R(0.74, 0.86, 0.1, 0.1, C_WOOD_DARK),
    C(0.5, 0.26, 0.18, C_NOTE_WHITE),
    R(0.44, 0.06, 0.12, 0.14, C_LAMP_POLE)
  ],
  incense: [R(0.34, 0.5, 0.32, 0.3, C_GOLD_YELLOW), R(0.47, 0.26, 0.06, 0.26, C_LAMP_POLE), C(0.5, 0.22, 0.05, C_LAMP_YELLOW)],
  scroll: [R(0.3, 0.18, 0.4, 0.64, C_SCROLL_TAN), R(0.28, 0.14, 0.44, 0.08, C_WOOD_DARK), R(0.28, 0.78, 0.44, 0.08, C_WOOD_DARK)],
  hay: [C(0.5, 0.55, 0.36, C_HAY_YELLOW), C(0.35, 0.4, 0.2, C_HAY_YELLOW), C(0.65, 0.42, 0.18, C_HAY_YELLOW)],
  cart: [
    R(0.05, 0.25, 1.7, 0.5, C_WOOD_DARK),
    C(0.35, 0.82, 0.14, C_DARK_GRAY),
    C(1.45, 0.82, 0.14, C_DARK_GRAY),
    R(1.72, 0.18, 0.2, 0.62, C_WOOD_LIGHT)
  ]
};
function decorStyle(type) {
  const f = FURNITURE_DRAW[type];
  if (f) return f;
  const s = DECOR_STYLES[type] ?? DECOR_UNKNOWN;
  return styleToCmds(s, type);
}
var MARKER_STYLES = {
  grass: { fill: C_GRASS_LIGHT, alpha: 0.9, kind: "triangle", size: { w: 0.26, h: 0.4 } },
  debris: { fill: C_DEBRIS_BROWN, alpha: 0.95, kind: "rect", size: { w: 0.2, h: 0.18 } }
};
var MARKER_UNKNOWN = { fill: C_UNKNOWN, alpha: 0.8, kind: "circle", size: { r: 0.12 } };
function markerStyle(category) {
  const s = MARKER_STYLES[category] ?? MARKER_UNKNOWN;
  return styleToCmds(s, category);
}
function overlayStyle(type) {
  if (type === "canopy") return { fill: C_TREE_GREEN, alpha: 0.35 };
  return { fill: C_UNKNOWN, alpha: 0.3 };
}
function styleToCmds(s, type) {
  const cmds = [];
  switch (s.kind) {
    case "rect": {
      const w = s.size.w ?? 0.5, h = s.size.h ?? 0.5;
      cmds.push({ shape: "rect", x: (1 - w) / 2, y: (1 - h) / 2, w, h, color: s.fill, alpha: s.alpha });
      if (type === "fence") {
        cmds.push({ shape: "rect", x: 0.12, y: 0.34, w: 0.08, h: 0.24, color: s.fill, alpha: 1 });
        cmds.push({ shape: "rect", x: 0.8, y: 0.34, w: 0.08, h: 0.24, color: s.fill, alpha: 1 });
      } else if (type === "bench") {
        cmds.push({ shape: "rect", x: 0.06, y: 0.36, w: 0.88, h: 0.08, color: s.fill, alpha: 1 });
        cmds.push({ shape: "rect", x: 0.14, y: 0.68, w: 0.08, h: 0.16, color: C_LAMP_POLE, alpha: 1 });
        cmds.push({ shape: "rect", x: 0.78, y: 0.68, w: 0.08, h: 0.16, color: C_LAMP_POLE, alpha: 1 });
      } else if (type === "chest") {
        cmds.push({ shape: "rect", x: 0.28, y: 0.26, w: 0.44, h: 0.1, color: C_CHEST_DARK, alpha: 1 });
        cmds.push({ shape: "rect", x: 0.46, y: 0.44, w: 0.08, h: 0.1, color: C_LAMP_YELLOW, alpha: 0.9 });
      } else if (type === "note") {
        cmds.push({ shape: "rect", x: 0.42, y: 0.52, w: 0.16, h: 0.08, color: C_LAMP_POLE, alpha: 0.8 });
      } else if (type === "pillar") {
        cmds.push({ shape: "rect", x: 0.22, y: 0.06, w: 0.56, h: 0.1, color: C_PILLAR_GRAY, alpha: 1 });
      } else if (type === "bed") {
        cmds.push({ shape: "rect", x: 0.08, y: 0.1, w: 0.34, h: 0.5, color: C_BED_BLUE, alpha: 1 });
        cmds.push({ shape: "rect", x: 0.42, y: 0.12, w: 0.5, h: 0.42, color: C_POT_GREEN, alpha: 0.9 });
      } else if (type === "table_rect") {
        cmds.push({ shape: "rect", x: 0.12, y: 0.64, w: 0.08, h: 0.24, color: C_WOOD_DARK, alpha: 1 });
        cmds.push({ shape: "rect", x: 0.8, y: 0.64, w: 0.08, h: 0.24, color: C_WOOD_DARK, alpha: 1 });
      } else if (type === "bookshelf") {
        cmds.push({ shape: "rect", x: 0.08, y: 0.2, w: 0.84, h: 0.12, color: C_WOOD_LIGHT, alpha: 1 });
        cmds.push({ shape: "rect", x: 0.08, y: 0.52, w: 0.84, h: 0.12, color: C_WOOD_LIGHT, alpha: 1 });
      } else if (type === "stove") {
        cmds.push({ shape: "circle", x: 0.32, y: 0.3, r: 0.12, color: C_WOOD_DARK, alpha: 1 });
        cmds.push({ shape: "circle", x: 0.68, y: 0.3, r: 0.12, color: C_WOOD_DARK, alpha: 1 });
      } else if (type === "window") {
        cmds.push({ shape: "rect", x: 0.44, y: 0.1, w: 0.12, h: 0.8, color: C_LAMP_POLE, alpha: 0.8 });
        cmds.push({ shape: "rect", x: 0.1, y: 0.44, w: 0.8, h: 0.12, color: C_LAMP_POLE, alpha: 0.8 });
      } else if (type === "cart") {
        cmds.push({ shape: "circle", x: 0.3, y: 0.78, r: 0.12, color: C_DARK_GRAY, alpha: 1 });
        cmds.push({ shape: "circle", x: 0.7, y: 0.78, r: 0.12, color: C_DARK_GRAY, alpha: 1 });
      }
      break;
    }
    case "circle": {
      const r = s.size.r ?? 0.2;
      cmds.push({ shape: "circle", x: 0.5, y: type === "tree_oak" ? 0.42 : 0.45, r, color: s.fill, alpha: s.alpha });
      if (type === "tree_oak") {
        cmds.push({ shape: "rect", x: 0.44, y: 0.62, w: 0.12, h: 0.32, color: C_TRUNK_BROWN, alpha: 1 });
      } else if (type === "lamp") {
        cmds.push({ shape: "rect", x: 0.47, y: 0.4, w: 0.06, h: 0.44, color: C_LAMP_POLE, alpha: 1 });
      } else if (type === "tree") {
        cmds.push({ shape: "rect", x: 0.44, y: 0.62, w: 0.12, h: 0.32, color: C_TRUNK_BROWN, alpha: 1 });
      } else if (type === "fountain") {
        cmds.push({ shape: "circle", x: 0.5, y: 0.5, r: 0.28, color: C_PILLAR_GRAY, alpha: 1 });
      } else if (type === "rock") {
        cmds.push({ shape: "circle", x: 0.4, y: 0.55, r: 0.16, color: C_ROCK_GRAY, alpha: 1 });
      }
      break;
    }
    case "triangle": {
      const w = s.size.w ?? 0.3, h = s.size.h ?? 0.4;
      cmds.push({ shape: "triangle", x: 0.5, y: 0.74, w, h, color: s.fill, alpha: s.alpha });
      cmds.push({ shape: "triangle", x: 0.3, y: 0.78, w: w * 0.7, h: h * 0.75, color: C_GRASS_MID, alpha: 0.85 });
      break;
    }
    case "dots": {
      const count = Math.min(DOT_OFFSETS.length, 6);
      cmds.push({ shape: "dots", x: 0.5, y: 0.5, pts: DOT_OFFSETS.slice(0, count), colors: s.sub ?? FLOWER_COLORS, alpha: 1 });
      cmds.push({ shape: "rect", x: 0.05, y: 0.6, w: 0.9, h: 0.16, color: s.fill, alpha: 1 });
      break;
    }
  }
  return cmds;
}
function buildDecorPlan(map) {
  const H = Math.max(1, map.height || map.layers.ground.length || 1);
  const items = [];
  const objects = map.layers?.objects;
  if (objects) {
    for (let y = 0; y < objects.length; y++) {
      const row = objects[y];
      if (!row) continue;
      for (let x = 0; x < row.length; x++) {
        const cmds = objectStyle(row[x]);
        if (!cmds) continue;
        items.push({ layer: "objects", type: row[x], x, y, depth: decorDepth(y, H), cmds });
      }
    }
  }
  const markers = map.spawnMarkers;
  if (markers) {
    for (const [cat, pts] of Object.entries(markers)) {
      for (const p of pts || []) {
        items.push({ layer: "markers", type: cat, x: p[0], y: p[1], depth: decorDepth(p[1], H), cmds: markerStyle(cat) });
      }
    }
  }
  const decor = map.decor;
  if (decor) {
    for (const d of decor) {
      items.push({ layer: "decor", type: d.type, x: d.tile[0], y: d.tile[1], depth: decorDepth(d.tile[1], H), cmds: decorStyle(d.type) });
    }
  }
  items.sort((a, b) => a.depth - b.depth || a.x - b.x);
  const overlay = [];
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
  const water = [];
  const tp = map.tileProps;
  if (tp) {
    for (const [k, v] of Object.entries(tp)) {
      if (v && v.water === true) {
        const comma = k.indexOf(",");
        const x = Number(k.slice(0, comma)), y = Number(k.slice(comma + 1));
        if (comma > 0 && Number.isFinite(x) && Number.isFinite(y)) water.push({ x, y });
      }
    }
  }
  return { items, overlay, water };
}
