#!/usr/bin/env node
/**
 * gen_assets.js — 生成 demo 占位素材（零第三方版权素材）
 *
 * 输出（写入 ../assets/）：
 *   tiles.png     瓦片集：5 个 32×32 瓦片（木地板/墙/草地/地毯/石板走廊）
 *   player.png    角色 1 精灵表：4 方向 × 4 帧 32×32（aseprite 管线用）
 *   player2.png   角色 2 精灵表：同上（spritesheet 管线用，换色）
 *   player.json   Aseprite 格式动画元数据（frameTags: walk-down/up/left/right）
 *   player2.json  同上（角色 2）
 *
 * 纯 Node 内置模块（zlib 压缩 + 自实现 CRC32 的极简 PNG 编码器），
 * 无 canvas / 无第三方依赖，可在任何有 Node 的机器复现。
 *
 * 用途标注：占位帧（程序生成，无版权问题）；正式素材管线验证的是
 * Phaser 的加载/动画路径而非美术质量，真实 Aseprite 素材只需替换同构 png+json。
 */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// ---------- 极简 PNG 编码器 ----------
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}
function encodePNG(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 6;  // color type RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------- 像素画布 ----------
class Canvas {
  constructor(w, h) {
    this.w = w; this.h = h;
    this.buf = Buffer.alloc(w * h * 4); // 全透明
  }
  setPx(x, y, r, g, b, a = 255) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const i = (y * this.w + x) * 4;
    this.buf[i] = r; this.buf[i + 1] = g; this.buf[i + 2] = b; this.buf[i + 3] = a;
  }
  fillRect(x, y, w, h, r, g, b, a = 255) {
    for (let yy = y; yy < y + h; yy++)
      for (let xx = x; xx < x + w; xx++) this.setPx(xx, yy, r, g, b, a);
  }
  rectBorder(x, y, w, h, r, g, b, a = 255) {
    this.fillRect(x, y, w, 1, r, g, b, a);
    this.fillRect(x, y + h - 1, w, 1, r, g, b, a);
    this.fillRect(x, y, 1, h, r, g, b, a);
    this.fillRect(x + w - 1, y, 1, h, r, g, b, a);
  }
  // 噪点撒布（草地纹理）
  sprinkle(n, r, g, b, a = 255) {
    for (let i = 0; i < n; i++) {
      this.setPx((Math.random() * this.w) | 0, (Math.random() * this.h) | 0, r, g, b, a);
    }
  }
}

// ---------- 瓦片集 160×32：5 个 32×32 ----------
function drawTiles() {
  const c = new Canvas(160, 32);
  const T = 32;
  // tile 1 木地板（室内）
  c.fillRect(0, 0, T, T, 185, 141, 95);
  for (let y = 4; y < T; y += 8) c.fillRect(0, y, T, 1, 158, 116, 74);       // 板缝横线
  for (let y = 0; y < T; y += 8) {
    const off = ((y / 8) % 2) * 8;
    for (let x = off; x < T; x += 16) c.fillRect(x, y, 1, 4, 158, 116, 74);   // 错缝竖线
  }
  c.sprinkle(30, 150, 110, 70, 70);
  // tile 2 墙（砖）
  c.fillRect(T, 0, T, T, 100, 104, 118);
  c.fillRect(T, 0, T, 2, 140, 144, 156); // 顶光
  for (let row = 0; row < 4; row++) {
    const off = row % 2 ? 8 : 0;
    for (let x = off; x < T; x += 16) c.fillRect(T + x, 2 + row * 8, 1, 6, 78, 82, 94);
    c.fillRect(T, 2 + row * 8, T, 1, 78, 82, 94);
  }
  // tile 3 草地（花园）
  c.fillRect(T * 2, 0, T, T, 124, 179, 86);
  c.sprinkle(60, 96, 148, 62, 140);
  c.sprinkle(40, 158, 205, 120, 120);
  // tile 4 地毯（红）
  c.fillRect(T * 3, 0, T, T, 163, 59, 59);
  c.rectBorder(T * 3 + 2, 2, T - 4, T - 4, 196, 96, 90);
  c.rectBorder(T * 3 + 5, 5, T - 10, T - 10, 128, 40, 40);
  for (let i = 0; i < 3; i++) c.setPx(T * 3 + 16, 8 + i * 8, 226, 178, 120, 200); // 中心装饰点
  // tile 5 石板走廊
  c.fillRect(T * 4, 0, T, T, 154, 149, 142);
  c.fillRect(T * 4 + 1, 1, 15, 14, 172, 168, 160);
  c.fillRect(T * 4 + 17, 1, 14, 14, 172, 168, 160);
  c.fillRect(T * 4 + 1, 16, 15, 15, 172, 168, 160);
  c.fillRect(T * 4 + 17, 16, 14, 15, 172, 168, 160);
  c.fillRect(T * 4 + 8, 8, 3, 3, 128, 122, 116);
  c.fillRect(T * 4 + 24, 8, 3, 3, 128, 122, 116);
  c.fillRect(T * 4 + 8, 24, 3, 3, 128, 122, 116);
  c.fillRect(T * 4 + 24, 24, 3, 3, 128, 122, 116);
  return c;
}

// ---------- 角色帧 32×32 ----------
// dir: 0=down 1=up 2=left 3=right; frame: 0..3（0=站立,1..3=走路循环）
function drawCharacter(shirt, pants) {
  return (dir, frame) => {
    const c = new Canvas(32, 32);
    const S = 32;
    const face = dir === 0 ? 1 : dir === 2 ? -1 : dir === 3 ? 1 : 0; // 眼睛朝向 x 偏移
    // 腿（走路交替）
    const legLift = [0, -1, 0, 1][frame];        // 0: 并立  1: 左腿前  2: 并立  3: 右腿前
    const legOffL = frame === 1 ? -2 : 0;
    const legOffR = frame === 3 ? 2 : 0;
    const legY = 20 + (frame === 0 || frame === 2 ? 0 : legLift * 0);
    c.fillRect(11 + legOffL, 21, 4, 7, pants[0], pants[1], pants[2]); // 左腿
    c.fillRect(17 + legOffR, 21, 4, 7, pants[0], pants[1], pants[2]); // 右腿
    // 鞋
    c.fillRect(10 + legOffL, 27, 6, 2, 40, 40, 44);
    c.fillRect(16 + legOffR, 27, 6, 2, 40, 40, 44);
    // 身体（上衣）
    c.fillRect(10, 12, 12, 10, shirt[0], shirt[1], shirt[2]);
    c.fillRect(9, 13, 2, 6, shirt[0], shirt[1], shirt[2]); // 左臂
    c.fillRect(21, 13, 2, 6, shirt[0], shirt[1], shirt[2]); // 右臂
    c.fillRect(9, 19, 2, 3, 40, 40, 44); // 手
    c.fillRect(21, 19, 2, 3, 40, 40, 44);
    // 头
    c.fillRect(10, 3, 12, 10, 240, 196, 160); // 皮肤
    // 头发（up=后脑全盖）
    if (dir === 1) {
      c.fillRect(9, 2, 14, 6, 60, 44, 32);
      c.fillRect(10, 8, 12, 2, 60, 44, 32);
    } else {
      c.fillRect(10, 2, 12, 3, 60, 44, 32); // 刘海
    }
    // 眼睛
    if (dir === 0) { // down：双眼
      c.fillRect(13 + face * 0, 8, 2, 2, 30, 30, 34);
      c.fillRect(18, 8, 2, 2, 30, 30, 34);
    } else if (dir === 2 || dir === 3) { // 侧脸：单眼
      const ex = dir === 3 ? 19 : 11;
      c.fillRect(ex, 8, 2, 2, 30, 30, 34);
    }
    // 描边（轮廓）
    c.rectBorder(9, 3, 14, 14, 40, 40, 44); // 头+身外框
    return c;
  };
}

function buildCharacterSheet(makeFrame) {
  const c = new Canvas(128, 128);
  const dirs = [0, 1, 2, 3];
  dirs.forEach((dir) => {
    for (let f = 0; f < 4; f++) {
      const frame = makeFrame(dir, f);
      for (let y = 0; y < 32; y++)
        for (let x = 0; x < 32; x++) {
          const i = (y * 32 + x) * 4;
          if (frame.buf[i + 3] > 0) {
            const o = ((dir * 4 + f) * 32 + y) * 128 * 4 + x * 4;
            c.buf[o] = frame.buf[i]; c.buf[o + 1] = frame.buf[i + 1];
            c.buf[o + 2] = frame.buf[i + 2]; c.buf[o + 3] = frame.buf[i + 3];
          }
        }
    }
  });
  return c;
}

// Phaser 3.90 的 createFromAseprite 按「数字索引字符串」解析帧（源码 AnimationManager
// createFromAseprite：frames[i.toString()] → Animation.getFrames → texture.get(frame)），
// 因此 frames 键必须为 "0".."15"（hash 格式数值键），纹理帧名才会与动画帧索引一致。
function asepriteJson(sheetName, framesCount) {
  const frames = {};
  for (let dir = 0; dir < 4; dir++) {
    for (let f = 0; f < 4; f++) {
      const idx = dir * 4 + f;
      const key = String(idx);
      frames[key] = {
        frame: { x: (idx % 4) * 32, y: Math.floor(idx / 4) * 32, w: 32, h: 32 },
        duration: 120,
      };
    }
  }
  return {
    frames,
    meta: {
      app: 'roleplay-java phaser_validate gen_assets.js（程序生成占位，非 Aseprite 导出）',
      version: '1.0',
      image: sheetName + '.png',
      format: 'RGBA8888',
      size: { w: 128, h: 128 },
      scale: '1',
      frameTags: [
        { name: 'walk-down', from: 0, to: 3, direction: 'forward' },
        { name: 'walk-up', from: 4, to: 7, direction: 'forward' },
        { name: 'walk-left', from: 8, to: 11, direction: 'forward' },
        { name: 'walk-right', from: 12, to: 15, direction: 'forward' },
      ],
      layers: [{ name: 'Layer 1', opacity: 255, blendMode: 'normal' }],
      slices: [],
    },
  };
}

// ---------- 主流程 ----------
const outDir = path.join(__dirname, '..', 'assets');
fs.mkdirSync(outDir, { recursive: true });

const tiles = drawTiles();
fs.writeFileSync(path.join(outDir, 'tiles.png'), encodePNG(tiles.w, tiles.h, tiles.buf));

const makeChar1 = drawCharacter([58, 110, 165], [52, 64, 92]);   // 蓝衣（aseprite 管线）
const char1 = buildCharacterSheet(makeChar1);
fs.writeFileSync(path.join(outDir, 'player.png'), encodePNG(char1.w, char1.h, char1.buf));
fs.writeFileSync(path.join(outDir, 'player.json'), JSON.stringify(asepriteJson('player'), null, 2));

const makeChar2 = drawCharacter([176, 57, 47], [70, 60, 52]);    // 红衣（spritesheet 管线）
const char2 = buildCharacterSheet(makeChar2);
fs.writeFileSync(path.join(outDir, 'player2.png'), encodePNG(char2.w, char2.h, char2.buf));
fs.writeFileSync(path.join(outDir, 'player2.json'), JSON.stringify(asepriteJson('player2'), null, 2));

console.log('assets generated ->', outDir);
for (const f of fs.readdirSync(outDir)) {
  const st = fs.statSync(path.join(outDir, f));
  console.log('  ', f, st.size, 'bytes');
}
