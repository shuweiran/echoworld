/**
 * MiniMap.tsx — 剧本杀地图小地图（调研项 2 方案 B：DOM canvas 覆盖层，P-0803-E）
 *
 * 数据源：契约 v1 map 的 rooms[]/zones[] 矩形 + 玩家格坐标（onPlayerMove 回调）；
 * 独立于 Phaser 生命周期，React 层直接画 canvas，零引擎负担；
 * 与全屏方案天然兼容（同一 React 树内渲染）。
 */
import { useEffect, useRef } from 'react';
import type { ScriptMap } from './mapData';

export interface MiniMapProps {
  /** 契约 v1 地图（rooms/zones 格坐标） */
  map: ScriptMap;
  /** 玩家格坐标 [gx, gy]（null=尚未上报） */
  player: { x: number; y: number } | null;
  /** 已搜证 zone id 列表（绿点；未搜证金点） */
  searched: string[];
  /** P-0817-G：当前房间 id（房间模式高亮；缺省不高亮） */
  currentRoom?: string;
}

/** 小地图画布宽（px）；高按地图宽高比自适应（60~110px） */
const MM_W = 140;

export function MiniMap({ map, player, searched, currentRoom }: MiniMapProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const W = Math.max(1, map.width);
    const H = Math.max(1, map.height);
    canvas.width = MM_W;
    canvas.height = Math.min(110, Math.max(60, Math.round((MM_W * H) / W)));
    const s = canvas.width / W; // 每格像素

    ctx.clearRect(0, 0, canvas.width, canvas.height);
    // 背景（半透明，覆盖在地图上不遮挡）
    ctx.fillStyle = 'rgba(15, 23, 42, 0.88)';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    // 房间（浅蓝矩形）
    for (const r of map.rooms) {
      if (r.w <= 0 || r.h <= 0) continue;
      const active = currentRoom === r.id;
      ctx.fillStyle = active ? 'rgba(255, 209, 102, 0.45)' : 'rgba(56, 189, 248, 0.22)';
      ctx.fillRect(r.x * s, r.y * s, r.w * s, r.h * s);
      ctx.strokeStyle = active ? '#ffd166' : 'rgba(56, 189, 248, 0.55)';
      ctx.lineWidth = active ? 2 : 1;
      ctx.strokeRect(r.x * s + 0.5, r.y * s + 0.5, r.w * s, r.h * s);
    }
    // 走廊（石板灰线，可选）
    for (const c of map.corridors) {
      if (c.points.length < 2) continue;
      ctx.strokeStyle = 'rgba(148, 163, 184, 0.5)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      c.points.forEach((p, i) => {
        const x = (p[0] + 0.5) * s;
        const y = (p[1] + 0.5) * s;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      });
      ctx.stroke();
    }
    // 搜证热点（未搜证金 / 已搜证绿）
    const searchedSet = new Set(searched);
    for (const z of map.zones) {
      ctx.beginPath();
      ctx.arc((z.x + 0.5) * s, (z.y + 0.5) * s, Math.max(2, s * 0.75), 0, Math.PI * 2);
      ctx.fillStyle = searchedSet.has(z.id) ? '#3ddc84' : '#ffd166';
      ctx.fill();
    }
    // 玩家（白点 + 蓝圈）
    if (player) {
      const px = (player.x + 0.5) * s;
      const py = (player.y + 0.5) * s;
      ctx.beginPath();
      ctx.arc(px, py, 3, 0, Math.PI * 2);
      ctx.fillStyle = '#ffffff';
      ctx.fill();
      ctx.strokeStyle = '#0ea5e9';
      ctx.lineWidth = 1.5;
      ctx.stroke();
    }
  }, [map, player, searched, currentRoom]);

  return (
    <canvas
      ref={canvasRef}
      className="mini-map-canvas"
      title="小地图：浅蓝=房间 · 金/绿点=搜证热点（已搜证变绿） · 白点=你"
    />
  );
}
