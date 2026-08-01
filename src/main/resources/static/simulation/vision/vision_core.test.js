/**
 * vision_core.test.js — 视觉系统核心算法单元测试（Node，无浏览器依赖）
 * 运行：node vision_core.test.js
 * 覆盖：线段-矩形求交 / 视线通畅 / 可见性判定全分支（遮挡·草丛·迷雾·范围·视野角）/
 *       视野多边形（墙体截断·雾内缩圈）/ 移动碰撞
 */
'use strict';
const assert = require('assert');
const VC = require('./vision_core.js');

// ── 测试世界构造 ────────────────────────────────────────────
const openWorld  = { obstacles: [], grassPatches: [], fogZones: [] };
const wallWorld  = { obstacles: [{ x: 100, y: 0, w: 10, h: 200, blocksVision: true }], grassPatches: [], fogZones: [] };
const grassWorld = { obstacles: [], grassPatches: [{ x: 200, y: 200, w: 80, h: 80 }], fogZones: [] };
const fogWorld   = { obstacles: [], grassPatches: [], fogZones: [{ x: 300, y: 300, r: 60 }] };

const obsFwd = { x: 0, y: 0, facing: 0, fov: 90, viewRange: 200, kind: 'ai' }; // 面朝 +X，左右各 45°

let passed = 0, failed = 0;
function T(name, fn) {
  try { fn(); passed++; console.log('  ✓ ' + name); }
  catch (e) { failed++; console.error('  ✗ ' + name + ' → ' + e.message); }
}

// ── 1. 线段与 AABB 求交（Liang-Barsky）──────────────────────
T('segRectHit: 水平线段穿过矩形', () => {
  assert.ok(VC.segRectHit(0, 50, 200, 50, 90, 40, 20, 20));
});
T('segRectHit: 对角线段未触及矩形', () => {
  assert.ok(!VC.segRectHit(0, 0, 200, 200, 90, 40, 20, 20));
});
T('segRectHit: 垂直线段贴边进入', () => {
  assert.ok(VC.segRectHit(95, 10, 95, 100, 90, 40, 20, 20));
});
T('segRectHit: 平行且不相交', () => {
  assert.ok(!VC.segRectHit(0, 10, 200, 10, 90, 40, 20, 20));
});
T('segRectHit: 线段在矩形前方终止', () => {
  assert.ok(!VC.segRectHit(0, 50, 85, 50, 90, 40, 20, 20));
});
T('segRectHit: 起点在矩形内视为命中', () => {
  assert.ok(VC.segRectHit(95, 50, 95, 60, 90, 40, 20, 20));
});

// ── 2. 视线通畅 ─────────────────────────────────────────────
T('hasLOS: 开阔地视线通畅', () => {
  assert.ok(VC.hasLOS(0, 0, 300, 0, openWorld.obstacles));
});
T('hasLOS: 墙体截断视线', () => {
  assert.ok(!VC.hasLOS(50, 50, 200, 50, wallWorld.obstacles));
});
T('hasLOS: 绕过墙体上/下沿可见', () => {
  // 起点在墙的 y 跨度之外，线段不穿越墙体的外扩矩形 → 视线通畅
  assert.ok(VC.hasLOS(50, 250, 200, 300, wallWorld.obstacles));   // 墙下方绕行
  assert.ok(VC.hasLOS(50, -50, 200, 0, wallWorld.obstacles));     // 墙上方绕行
  // 对照：起点在墙的 y 跨度内，必然被挡
  assert.ok(!VC.hasLOS(50, 100, 200, 300, wallWorld.obstacles));
});
T('hasLOS: blocksVision=false 的障碍不挡视线', () => {
  const w = { obstacles: [{ x: 100, y: 0, w: 10, h: 200, blocksVision: false }], grassPatches: [], fogZones: [] };
  assert.ok(VC.hasLOS(50, 50, 200, 50, w.obstacles));
});
T('hasLOS: 擦墙角（pad=6 外扩）视为遮挡', () => {
  // 从墙左上角外 3px 出发的视线，外扩 6px 后应被判定遮挡
  assert.ok(!VC.hasLOS(93, 90, 200, 90, wallWorld.obstacles, 6));
});

// ── 3. 可见性判定全分支 ─────────────────────────────────────
T('visible: 视线畅通 → VISIBLE', () => {
  const r = VC.visibilityOf(obsFwd, { x: 100, y: 0 }, openWorld);
  assert.strictEqual(r.visible, true);
  assert.strictEqual(r.reason, VC.REASON.VISIBLE);
  assert.strictEqual(r.factor, 1);
});
T('visible: 超出视野范围 → OUT_OF_RANGE', () => {
  const r = VC.visibilityOf(obsFwd, { x: 250, y: 0 }, openWorld);
  assert.strictEqual(r.reason, VC.REASON.OUT_OF_RANGE);
});
T('visible: 偏离视野角 → OUT_OF_FOV', () => {
  const r = VC.visibilityOf(obsFwd, { x: 0, y: 150 }, openWorld);
  assert.strictEqual(r.reason, VC.REASON.OUT_OF_FOV);
});
T('visible: 墙后 → BLOCKED（玩家视角看不到墙后 AI）', () => {
  const r = VC.visibilityOf({ x: 50, y: 50, facing: 0, fov: 90, viewRange: 200, kind: 'player' },
                            { x: 150, y: 50 }, wallWorld);
  assert.strictEqual(r.reason, VC.REASON.BLOCKED);
  assert.strictEqual(r.visible, false);
});
T('visible: 从墙下方绕行处可见墙后实体', () => {
  // 观察点与目标都在墙底(y=200)之下，视线不穿越墙体 → 可见
  const r = VC.visibilityOf({ x: 50, y: 300, facing: 0, fov: 90, viewRange: 200, kind: 'player' },
                            { x: 150, y: 300 }, wallWorld);
  assert.strictEqual(r.visible, true);
});
T('grass: AI 看不见草丛中的实体 → IN_GRASS', () => {
  const r = VC.visibilityOf({ x: 100, y: 100, facing: 0, fov: 120, viewRange: 300, kind: 'ai' },
                            { x: 240, y: 240 }, grassWorld);
  assert.strictEqual(r.reason, VC.REASON.IN_GRASS);
});
T('grass: 玩家能看见草丛中的实体（不对称视觉）', () => {
  const r = VC.visibilityOf({ x: 100, y: 100, facing: 0, fov: 120, viewRange: 300, kind: 'player' },
                            { x: 240, y: 240 }, grassWorld);
  assert.strictEqual(r.visible, true);
});
T('grass: AI 贴近(≤grassHideDist)可识破草丛', () => {
  const r = VC.visibilityOf({ x: 220, y: 220, facing: 0, fov: 120, viewRange: 300, kind: 'ai', grassHideDist: 46 },
                            { x: 240, y: 240 }, grassWorld);
  assert.strictEqual(r.visible, true);
});
T('grass: AI 同在草丛中则互相可见', () => {
  const r = VC.visibilityOf({ x: 230, y: 230, facing: 0, fov: 120, viewRange: 300, kind: 'ai' },
                            { x: 250, y: 250 }, grassWorld);
  assert.strictEqual(r.visible, true);
});
T('fog: 雾中实体仍可见但被削弱(factor<1)', () => {
  const r = VC.visibilityOf({ x: 100, y: 100, facing: 0, fov: 120, viewRange: 300, kind: 'ai' },
                            { x: 300, y: 300 }, fogWorld);
  assert.strictEqual(r.visible, true);
  assert.ok(r.factor < 1);
  assert.strictEqual(r.reason, VC.REASON.FOG_DIM);
});
T('fog: 观察者在雾内视野范围缩小(300→180)', () => {
  const r = VC.visibilityOf({ x: 300, y: 300, facing: 0, fov: 120, viewRange: 300, kind: 'ai' },
                            { x: 550, y: 300 }, fogWorld);
  assert.strictEqual(r.reason, VC.REASON.OUT_OF_RANGE); // 250 > 180
});
T('fog: 观察者在雾内、目标近距仍可见', () => {
  const r = VC.visibilityOf({ x: 300, y: 300, facing: 0, fov: 120, viewRange: 300, kind: 'ai' },
                            { x: 330, y: 300 }, fogWorld);
  assert.strictEqual(r.visible, true);
});

// ── 4. 视野多边形 ───────────────────────────────────────────
T('polygon: 开阔地边界点全部落在视野范围内', () => {
  const pts = VC.buildVisionPolygon(obsFwd, openWorld);
  assert.ok(pts.length >= 40); // 90° / 2° = 45 段
  for (const p of pts) assert.ok(VC.dist(0, 0, p.x, p.y) <= 200.001);
});
T('polygon: 墙体将前方射线截断在墙面上', () => {
  const pts = VC.buildVisionPolygon({ x: 50, y: 50, facing: 0, fov: 90, viewRange: 200, kind: 'ai' }, wallWorld);
  const mid = pts[Math.floor(pts.length / 2)];
  assert.ok(VC.dist(50, 50, mid.x, mid.y) < 120); // 墙在 x=94（含 pad）
});
T('polygon: 观察者在雾内 → 多边形半径整体缩小', () => {
  const pts = VC.buildVisionPolygon({ x: 300, y: 300, facing: 0, fov: 90, viewRange: 300, kind: 'ai' }, fogWorld);
  for (const p of pts) assert.ok(VC.dist(300, 300, p.x, p.y) <= 180.001); // 300×0.6
});

// ── 5. 移动碰撞 ─────────────────────────────────────────────
T('circleRectHit: 圆与矩形相交', () => {
  assert.ok(VC.circleRectHit(100, 50, 12, 90, 40, 20, 20));
  assert.ok(VC.circleRectHit(95, 45, 12, 90, 40, 20, 20)); // 角点附近
});
T('circleRectHit: 圆与矩形分离', () => {
  assert.ok(!VC.circleRectHit(200, 50, 12, 90, 40, 20, 20));
  assert.ok(!VC.circleRectHit(120, 80, 12, 90, 40, 20, 20));
});

// ── 汇总 ────────────────────────────────────────────────────
console.log('\n' + (failed === 0 ? '✅' : '❌') + ` vision_core tests: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
