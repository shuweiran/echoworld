/**
 * vision_sim_smoke.js — 场景级冒烟测试（Node）
 * 镜像 vision_demo.html 中的世界布局与 AI 行为逻辑（防漂移：与 vision_core.js 共享算法库），
 * 验证 demo 场景的关键验收点：
 *   A. 初始视角：玩家透过门缝可见「影卫·青」，墙后「守卫·铁」被遮挡淡出
 *   B. 草丛不对称：AI 看不见草丛中的玩家；玩家看得见草丛中的 AI；AI 贴近可识破
 *   C. 迷雾：观察者在雾内视野范围缩小（260→156）
 *   D. 行为闭环：玩家暴露 → AI 追击；躲入草丛 → AI 丢失目标
 *   E. 20s 连续模拟无 NaN / 实体不出界
 *
 * 运行：node vision_sim_smoke.js
 */
'use strict';
const assert = require('assert');
const VC = require('./vision_core.js');

const WORLD_W = 1000, WORLD_H = 600;

// ── 与 vision_demo.html 相同的世界 ──
const world = {
  obstacles: [
    { x: 330, y: 60,  w: 16, h: 200, type: 'WALL',   blocksVision: true },
    { x: 330, y: 330, w: 16, h: 200, type: 'WALL',   blocksVision: true },
    { x: 520, y: 120, w: 18, h: 18,  type: 'PILLAR', blocksVision: true },
    { x: 520, y: 462, w: 18, h: 18,  type: 'PILLAR', blocksVision: true },
    { x: 700, y: 110, w: 18, h: 18,  type: 'PILLAR', blocksVision: true },
    { x: 700, y: 470, w: 18, h: 18,  type: 'PILLAR', blocksVision: true },
    { x: 560, y: 60,  w: 90, h: 14,  type: 'ROCK',   blocksVision: true },
    { x: 150, y: 540, w: 120, h: 12, type: 'BENCH',  blocksVision: false },
  ],
  grassPatches: [{ x: 380, y: 285, w: 120, h: 90 }],
  fogZones: [{ x: 215, y: 110, r: 85 }, { x: 770, y: 150, r: 95 }, { x: 770, y: 455, r: 90 }],
};
const env = () => ({ obstacles: world.obstacles, grassPatches: world.grassPatches, fogZones: world.fogZones });
const GRASS = world.grassPatches[0];

function collidesAt(x, y, r) {
  for (const o of world.obstacles) if (VC.circleRectHit(x, y, r, o.x, o.y, o.w, o.h)) return true;
  return false;
}
function moveEntity(e, dx, dy) {
  if (!collidesAt(e.x + dx, e.y, e.r)) e.x += dx;
  if (!collidesAt(e.x, e.y + dy, e.r)) e.y += dy;
  e.x = VC.clamp(e.x, e.r + 2, WORLD_W - e.r - 2);
  e.y = VC.clamp(e.y, e.r + 2, WORLD_H - e.r - 2);
}

function makePlayer(x, y, facing) { return { kind: 'player', agentName: '玩家', x, y, r: 12, facing: facing || 0, fov: 100, viewRange: 300, moveSpeed: 130 }; }
function makeAI(name, x, y, anchorR) {
  return {
    kind: 'ai', agentName: name, x, y, r: 12, facing: Math.random() * Math.PI * 2,
    fov: 110, viewRange: 260, hearRange: 150, moveSpeed: 55, chaseSpeed: 92,
    anchorX: x, anchorY: y, anchorR: anchorR || 130, heading: Math.random() * Math.PI * 2,
    state: 'WANDER', sawPlayer: false, lastSeenPos: null, lastSeenT: -99, lastHearT: -99,
    turnT: 2 + Math.random() * 2,
  };
}

// 与 HTML updateAI 相同的状态机（视野 → 行为）
function updateAI(ai, player, dt, simT) {
  const vis = VC.visibilityOf(ai, player, env(), { losPad: 6, grassHideDist: 46 });
  ai.sawPlayer = vis.visible;
  const d = VC.dist(ai.x, ai.y, player.x, player.y);
  ai.heardPlayer = d <= ai.hearRange;
  if (vis.visible) { ai.lastSeenPos = { x: player.x, y: player.y }; ai.lastSeenT = simT; }
  if (ai.heardPlayer) ai.lastHearT = simT;

  if (ai.sawPlayer) ai.state = 'CHASE';
  else if (simT - ai.lastSeenT < 3 || simT - ai.lastHearT < 1.5) ai.state = 'SEARCH';
  else ai.state = 'WANDER';

  let speed = ai.moveSpeed, tx = null, ty = null;
  if (ai.state === 'CHASE') {
    speed = ai.chaseSpeed;
    if (d >= 40) { tx = player.x; ty = player.y; }
  } else if (ai.state === 'SEARCH') {
    speed = ai.moveSpeed * 0.85;
    const t = ai.lastSeenPos || { x: player.x, y: player.y };
    tx = t.x; ty = t.y;
    if (VC.dist(ai.x, ai.y, tx, ty) < 50) ai.state = 'WANDER';
  } else {
    ai.turnT -= dt;
    if (ai.turnT <= 0) { ai.heading = Math.random() * Math.PI * 2; ai.turnT = 2 + Math.random() * 2.5; }
    if (VC.dist(ai.x, ai.y, ai.anchorX, ai.anchorY) > ai.anchorR) ai.heading = VC.angleTo(ai.x, ai.y, ai.anchorX, ai.anchorY);
    tx = ai.x + Math.cos(ai.heading) * 50; ty = ai.y + Math.sin(ai.heading) * 50;
  }
  if (tx != null) {
    const ang = Math.atan2(ty - ai.y, tx - ai.x);
    ai.facing = ang;
    moveEntity(ai, Math.cos(ang) * speed * dt, Math.sin(ang) * speed * dt);
  } else ai.facing += dt * 0.8;
  return vis;
}

let passed = 0, failed = 0;
function T(name, fn) {
  try { fn(); passed++; console.log('  ✓ ' + name); }
  catch (e) { failed++; console.error('  ✗ ' + name + ' → ' + e.message); }
}

// ── A. 初始视角分布（玩家出生点 200,300 面朝右）──────────────
T('A1 初始：玩家透过门缝可见草丛中的「影卫·青」', () => {
  const player = makePlayer(200, 300, 0);
  const yingwei = { x: 400, y: 310, r: 12 };
  const r = VC.visibilityOf(player, yingwei, env(), { losPad: 6 });
  assert.strictEqual(r.visible, true);
  assert.strictEqual(r.reason, VC.REASON.VISIBLE);
});
T('A2 初始：墙后的「守卫·铁」被遮挡 → BLOCKED（淡出）', () => {
  const player = makePlayer(200, 300, 0);
  const shouwei = { x: 430, y: 150, r: 12 };
  const r = VC.visibilityOf(player, shouwei, env(), { losPad: 6 });
  assert.strictEqual(r.visible, false);
  assert.strictEqual(r.reason, VC.REASON.BLOCKED);
});
T('A3 初始：右侧「猎手·风」太远 → OUT_OF_RANGE', () => {
  const player = makePlayer(200, 300, 0);
  const r = VC.visibilityOf(player, { x: 600, y: 330, r: 12 }, env(), { losPad: 6 });
  assert.strictEqual(r.reason, VC.REASON.OUT_OF_RANGE);
});
T('A4 初始：左上「巡游·金」在视野角外（且雾中）→ OUT_OF_FOV', () => {
  const player = makePlayer(200, 300, 0);
  const r = VC.visibilityOf(player, { x: 180, y: 150, r: 12 }, env(), { losPad: 6 });
  assert.strictEqual(r.reason, VC.REASON.OUT_OF_FOV);
});

// ── B. 草丛不对称视觉 ───────────────────────────────────────
T('B1 AI 看不见草丛中的玩家（IN_GRASS）', () => {
  const ai = { x: 600, y: 320, facing: Math.PI, fov: 110, viewRange: 260, kind: 'ai', grassHideDist: 46 };
  const player = { x: 420, y: 310, r: 12 }; // 草丛 (380..500, 285..375) 内
  const r = VC.visibilityOf(ai, player, env(), { losPad: 6 });
  assert.strictEqual(r.visible, false);
  assert.strictEqual(r.reason, VC.REASON.IN_GRASS);
});
T('B2 玩家看得见草丛中的 AI（不对称）', () => {
  const player = makePlayer(200, 300, 0);
  const r = VC.visibilityOf(player, { x: 420, y: 310, r: 12 }, env(), { losPad: 6 });
  assert.strictEqual(r.visible, true);
});
T('B3 AI 贴近草丛(≤46px)可识破', () => {
  const ai = { x: 450, y: 300, facing: Math.PI, fov: 110, viewRange: 260, kind: 'ai', grassHideDist: 46 };
  const player = { x: 420, y: 310, r: 12 };
  const r = VC.visibilityOf(ai, player, env(), { losPad: 6 });
  assert.strictEqual(r.visible, true);
});

// ── C. 迷雾 ─────────────────────────────────────────────────
T('C1 雾内观察者视野范围缩小（260×0.6=156）', () => {
  const ai = { x: 215, y: 110, facing: 0, fov: 110, viewRange: 260, kind: 'ai' }; // 在 F1 雾内
  const target = { x: 215 + 200, y: 110, r: 12 };
  const r = VC.visibilityOf(ai, target, env(), { losPad: 6 });
  assert.strictEqual(r.reason, VC.REASON.OUT_OF_RANGE); // 200 > 156
});
T('C2 雾中实体对观察者可见但变淡（FOG_DIM）', () => {
  const ai = { x: 100, y: 110, facing: 0, fov: 110, viewRange: 260, kind: 'ai' };
  const r = VC.visibilityOf(ai, { x: 215, y: 110, r: 12 }, env(), { losPad: 6 });
  assert.strictEqual(r.visible, true);
  assert.strictEqual(r.reason, VC.REASON.FOG_DIM);
  assert.ok(r.factor < 1);
});

// ── D. 行为闭环（模拟状态机）────────────────────────────────
T('D1 玩家暴露 → AI 进入追击；躲入草丛 → AI 丢失目标', () => {
  const ai = makeAI('猎手·风', 600, 330, 100);
  ai.facing = Math.PI; // 面朝左（玩家方向）
  let simT = 0;
  // 玩家在草丛外（暴露）
  let player = makePlayer(360, 330, 0);
  updateAI(ai, player, 0.05, simT);
  assert.strictEqual(ai.sawPlayer, true, '暴露时应看到玩家');
  assert.strictEqual(ai.state, 'CHASE');
  // 玩家躲进草丛（距 AI >150px，避免听觉触发搜寻，验证纯视觉丢失）
  player = makePlayer(420, 310, 0);
  simT += 4;
  updateAI(ai, player, 0.05, simT);
  assert.strictEqual(ai.sawPlayer, false, '草丛中应看不到玩家');
  assert.notStrictEqual(ai.state, 'CHASE', '丢失目标后退出追击');
  assert.strictEqual(ai.state, 'WANDER', '超过 3s 未见 → 恢复闲逛');
});
T('D2 听到动静（听觉带内）→ 搜寻', () => {
  const ai = makeAI('守卫·铁', 430, 150, 120);
  ai.facing = Math.PI; // 面朝左（玩家方向）
  const player = makePlayer(300, 150, 0); // 距离 130 < hearRange 150，但视线被墙挡
  const vis = updateAI(ai, player, 0.05, 0);
  assert.strictEqual(vis.reason, VC.REASON.BLOCKED);
  assert.strictEqual(ai.sawPlayer, false);
  assert.strictEqual(ai.state, 'SEARCH', '看不到但听得到 → 搜寻');
});

// ── E. 20 秒连续模拟稳定性 ──────────────────────────────────
T('E1 20s 模拟：无 NaN、实体始终在界内、状态机不抛错', () => {
  const player = makePlayer(200, 300, 0);
  const ais = [
    makeAI('守卫·铁', 430, 150, 120),
    makeAI('影卫·青', 400, 310, 60),
    makeAI('猎手·风', 600, 330, 100),
    makeAI('巡游·金', 180, 150, 90),
  ];
  // 玩家随机游走脚本（含穿门缝/进草丛/进迷雾路径）
  const waypoints = [
    [200, 300], [330, 310], [420, 310], [500, 330], [600, 330], [700, 330],
    [600, 320], [420, 310], [300, 300], [215, 110], [180, 150], [200, 300],
  ];
  let simT = 0;
  const dt = 0.05;
  let seenCount = 0;
  for (let i = 0; i < 400; i++) {
    simT += dt;
    // 玩家沿航点匀速移动
    const wp = waypoints[Math.floor(i / 28) % waypoints.length];
    const ang = Math.atan2(wp[1] - player.y, wp[0] - player.x);
    const d = VC.dist(player.x, player.y, wp[0], wp[1]);
    if (d > 6) {
      player.facing = ang;
      moveEntity(player, Math.cos(ang) * player.moveSpeed * dt, Math.sin(ang) * player.moveSpeed * dt);
    }
    for (const ai of ais) updateAI(ai, player, dt, simT);
    // 完整性断言
    for (const e of [player, ...ais]) {
      assert.ok(Number.isFinite(e.x) && Number.isFinite(e.y), e.agentName + ' 出现 NaN');
      assert.ok(e.x >= e.r - 1 && e.x <= WORLD_W - e.r + 1 && e.y >= e.r - 1 && e.y <= WORLD_H - e.r + 1,
        e.agentName + ' 出界 (' + e.x.toFixed(1) + ',' + e.y.toFixed(1) + ')');
    }
    if (ais.some(a => a.sawPlayer)) seenCount++;
  }
  assert.ok(seenCount > 0, '20s 内至少应出现 AI 看到玩家的帧（行为闭环生效）');
});

console.log('\n' + (failed === 0 ? '✅' : '❌') + ` vision_sim_smoke tests: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
