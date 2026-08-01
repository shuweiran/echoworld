/**
 * vision_core.js — 2D 视觉系统核心算法（纯函数，无 DOM 依赖）
 *
 * 供 vision_demo.html 引用（<script src="vision_core.js">，file:// 双击可用），
 * 也可在 Node 中直接 require 做单元测试（vision_core.test.js）。
 *
 * 坐标系约定（与 src/main/resources/static/simulation.html 一致）：
 *   - 世界坐标 1000×600，左上角原点，向右为 +X，向下为 +Y
 *   - 障碍物 = AABB {x, y, w, h}，可带 blocksVision:false 表示不挡视线
 *   - 草丛 = AABB {x, y, w, h}（掩体：对 AI 观察者隐藏其中的实体）
 *   - 迷雾 = 圆 {x, y, r}（降低观察者视野范围 / 使雾中实体变淡）
 *
 * 可见性判定流程 visibilityOf()：
 *   ① 距离 > 观察者视野范围(viewRange，迷雾内打 6 折)          → OUT_OF_RANGE
 *   ② 目标方位角超出视野角(fov/2)                              → OUT_OF_FOV
 *   ③ 目标在草丛中 且 观察者为 AI 且 距离 > grassHideDist     → IN_GRASS
 *   ④ 观察点到目标点线段被挡视线障碍物截断（Liang-Barsky）    → BLOCKED
 *   ⑤ 目标处于迷雾区 → 可见但 factor 降低（FOG_DIM）
 */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) module.exports = factory();
  else root.VisionCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const TAU = Math.PI * 2;

  function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }

  function dist2(x1, y1, x2, y2) { const dx = x2 - x1, dy = y2 - y1; return dx * dx + dy * dy; }
  function dist(x1, y1, x2, y2) { return Math.sqrt(dist2(x1, y1, x2, y2)); }

  /** 角度归一化到 [-PI, PI] */
  function normAngle(a) { while (a > Math.PI) a -= TAU; while (a < -Math.PI) a += TAU; return a; }
  /** 两角之差（带符号，归一化） */
  function angleDiff(a, b) { return normAngle(a - b); }
  /** 从 (ax,ay) 指向 (bx,by) 的方位角 */
  function angleTo(ax, ay, bx, by) { return Math.atan2(by - ay, bx - ax); }

  /**
   * Liang-Barsky：线段 (x1,y1)→(x2,y2) 与 AABB [rx,ry,rw,rh] 求交
   * @returns 进入参数 t∈[0,1]；不相交返回 -1
   */
  function segRectT(x1, y1, x2, y2, rx, ry, rw, rh) {
    let t0 = 0, t1 = 1;
    const dx = x2 - x1, dy = y2 - y1;
    const p = [-dx, dx, -dy, dy];
    const q = [x1 - rx, rx + rw - x1, y1 - ry, ry + rh - y1];
    for (let i = 0; i < 4; i++) {
      if (p[i] === 0) {
        if (q[i] < 0) return -1;
      } else {
        const t = q[i] / p[i];
        if (p[i] < 0) { if (t > t1) return -1; if (t > t0) t0 = t; }
        else { if (t < t0) return -1; if (t < t1) t1 = t; }
      }
    }
    return (t0 >= 0 && t0 <= 1) ? t0 : -1;
  }

  function segRectHit(x1, y1, x2, y2, rx, ry, rw, rh) {
    return segRectT(x1, y1, x2, y2, rx, ry, rw, rh) >= 0;
  }

  /**
   * 视线通畅检测：观察点到目标点之间是否被「挡视线障碍物」截断
   * @param obstacles 障碍物数组；pad 为障碍物外扩像素（防贴墙/擦角偷看）
   */
  function hasLOS(x1, y1, x2, y2, obstacles, pad) {
    pad = pad || 6;
    for (let i = 0; i < obstacles.length; i++) {
      const o = obstacles[i];
      if (o.blocksVision === false) continue;
      if (segRectHit(x1, y1, x2, y2, o.x - pad, o.y - pad, o.w + pad * 2, o.h + pad * 2)) return false;
    }
    return true;
  }

  /**
   * 射线（原点 + 方向）到最近挡视线障碍物的距离；无命中返回 Infinity
   */
  function rayObstacleDist(ox, oy, dirx, diry, obstacles, pad, maxDist) {
    pad = pad || 6;
    let best = Infinity;
    const ex = ox + dirx * maxDist, ey = oy + diry * maxDist;
    for (let i = 0; i < obstacles.length; i++) {
      const o = obstacles[i];
      if (o.blocksVision === false) continue;
      const t = segRectT(ox, oy, ex, ey, o.x - pad, o.y - pad, o.w + pad * 2, o.h + pad * 2);
      if (t >= 0) { const d = t * maxDist; if (d < best) best = d; }
    }
    return best;
  }

  function pointInRect(px, py, rx, ry, rw, rh) { return px >= rx && px <= rx + rw && py >= ry && py <= ry + rh; }
  function pointInCircle(px, py, cx, cy, cr) { return dist2(px, py, cx, cy) <= cr * cr; }

  /** 实体所在草丛（无则 null） */
  function entityInGrass(ent, grassPatches) {
    for (let i = 0; i < grassPatches.length; i++) {
      const g = grassPatches[i];
      if (pointInRect(ent.x, ent.y, g.x, g.y, g.w, g.h)) return g;
    }
    return null;
  }

  /** 观察者所处迷雾（无则 null） */
  function observerInFog(obs, fogZones) {
    for (let i = 0; i < fogZones.length; i++) {
      const f = fogZones[i];
      if (pointInCircle(obs.x, obs.y, f.x, f.y, f.r)) return f;
    }
    return null;
  }

  const REASON = {
    VISIBLE: 'VISIBLE',       // 可见（视线畅通）
    OUT_OF_RANGE: 'OUT_OF_RANGE', // 超出视野范围
    OUT_OF_FOV: 'OUT_OF_FOV', // 偏离视野角
    BLOCKED: 'BLOCKED',       // 被障碍物遮挡
    IN_GRASS: 'IN_GRASS',     // 藏在草丛/掩体中
    FOG_DIM: 'FOG_DIM',       // 迷雾削弱（仍可见）
  };

  /**
   * 观察者 obs 对实体 ent 的可见性判定
   * @param obs  {x, y, facing(弧度), viewRange, fov(度), kind:'player'|'ai', grassHideDist?}
   * @param ent  {x, y}
   * @param world {obstacles, grassPatches, fogZones}
   * @param opts {losPad?, fogDimFactor?, fogRangeFactor?}
   * @returns {visible, reason, dist, factor}
   */
  function visibilityOf(obs, ent, world, opts) {
    opts = opts || {};
    const pad = opts.losPad != null ? opts.losPad : 6;
    const grassHideDist = obs.grassHideDist != null ? obs.grassHideDist : 46;
    const fogDimFactor = opts.fogDimFactor != null ? opts.fogDimFactor : 0.55;
    const fogRangeFactor = opts.fogRangeFactor != null ? opts.fogRangeFactor : 0.6;

    const obstacles = world.obstacles || [];
    const grassPatches = world.grassPatches || [];
    const fogZones = world.fogZones || [];

    const d = dist(obs.x, obs.y, ent.x, ent.y);
    const ang = Math.abs(angleDiff(obs.facing, angleTo(obs.x, obs.y, ent.x, ent.y)));

    let viewRange = obs.viewRange;
    let factor = 1;

    // 迷雾：观察者在雾内 → 视野范围缩小；目标在雾内 → 可见度降低
    if (fogZones.length) {
      if (observerInFog(obs, fogZones)) { viewRange *= fogRangeFactor; factor *= 0.85; }
      for (let i = 0; i < fogZones.length; i++) {
        if (pointInCircle(ent.x, ent.y, fogZones[i].x, fogZones[i].y, fogZones[i].r)) { factor *= fogDimFactor; break; }
      }
    }

    if (d > viewRange) return { visible: false, reason: REASON.OUT_OF_RANGE, dist: d, factor: 0 };
    if (ang > (obs.fov / 2) * Math.PI / 180) return { visible: false, reason: REASON.OUT_OF_FOV, dist: d, factor: 0 };

    // 草丛掩体：对 AI 观察者隐藏其中的实体（同草丛或贴近可识破）
    if (obs.kind !== 'player' && grassPatches.length) {
      const g = entityInGrass(ent, grassPatches);
      if (g) {
        const obsInSame = pointInRect(obs.x, obs.y, g.x, g.y, g.w, g.h);
        if (!obsInSame && d > grassHideDist) {
          return { visible: false, reason: REASON.IN_GRASS, dist: d, factor: 0 };
        }
      }
    }

    if (!hasLOS(obs.x, obs.y, ent.x, ent.y, obstacles, pad)) {
      return { visible: false, reason: REASON.BLOCKED, dist: d, factor: 0 };
    }

    return { visible: true, reason: factor < 1 ? REASON.FOG_DIM : REASON.VISIBLE, dist: d, factor: factor };
  }

  /**
   * 视野多边形（可见区域）：在视野角范围内按步长发射射线，
   * 每根射线取「最近挡视线障碍物」与「视野范围」的较小者作为终点。
   * @returns [{x,y}, ...]（含观察者起点闭合的扇形边界点序列）
   */
  function buildVisionPolygon(obs, world, opts) {
    opts = opts || {};
    const stepDeg = opts.stepDeg != null ? opts.stepDeg : 2;
    const pad = opts.losPad != null ? opts.losPad : 6;
    const fogRangeFactor = opts.fogRangeFactor != null ? opts.fogRangeFactor : 0.6;

    const fogZones = world.fogZones || [];
    let viewRange = obs.viewRange;
    if (fogZones.length && observerInFog(obs, fogZones)) viewRange *= fogRangeFactor;

    const obstacles = world.obstacles || [];
    const fov = obs.fov * Math.PI / 180;
    const steps = Math.max(2, Math.ceil(fov / (stepDeg * Math.PI / 180)));
    const pts = [];
    for (let i = 0; i <= steps; i++) {
      const a = obs.facing - fov / 2 + (fov * i / steps);
      const dx = Math.cos(a), dy = Math.sin(a);
      const hit = rayObstacleDist(obs.x, obs.y, dx, dy, obstacles, pad, viewRange);
      const r = Math.min(hit, viewRange);
      pts.push({ x: obs.x + dx * r, y: obs.y + dy * r });
    }
    return pts;
  }

  /** 圆 vs AABB 碰撞（移动用） */
  function circleRectHit(cx, cy, cr, rx, ry, rw, rh) {
    const nx = clamp(cx, rx, rx + rw), ny = clamp(cy, ry, ry + rh);
    return dist2(cx, cy, nx, ny) <= cr * cr;
  }

  return {
    clamp, normAngle, angleDiff, angleTo, dist, dist2,
    segRectT, segRectHit, hasLOS, rayObstacleDist,
    pointInRect, pointInCircle, entityInGrass, observerInFog,
    visibilityOf, buildVisionPolygon, circleRectHit, REASON,
  };
});
