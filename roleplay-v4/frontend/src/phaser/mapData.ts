/**
 * mapData.ts — 地图 JSON 契约 v1 数据适配（阶段 2）
 *
 * 职责：把后端 POST /api/script/map 返回的契约 v1 地图 JSON 归一为渲染层对象。
 * 契约文档：docs/地图JSON契约-v1.md（阶段 0 定稿；宽容解析规则 §3 对齐 D-014 纪律）。
 */

/** 契约 v1 地图（宽容解析后的规范结构） */
export interface ScriptMap {
  map_version: number;
  map_id: string;
  name: string;
  theme: string;
  tile_size: number;
  width: number;
  height: number;
  tileset?: { src?: string; first_gid?: number; tile_count?: number };
  layers: {
    ground: number[][];
    collision: number[][];
    /** v0.2（P-0814-F）：Front 层静态装饰类型名二维数组（可 null 元素，与 ground 同尺寸） */
    objects?: (string | null)[][];
    /** v0.2：AlwaysFront 前景遮罩二维数组（可 null 元素，永远盖住角色） */
    overlay?: (string | null)[][];
  };
  rooms: MapRoom[];
  corridors: MapCorridor[];
  zones: MapZone[];
  spawn_points: MapSpawnPoint[];
  /** v0.2：每格属性字典（键 "x,y"，值任意属性字典；water/blocked 等不做白名单） */
  tileProps?: Record<string, Record<string, unknown>>;
  /** v0.2：显式装饰/交互物（id 全局唯一、type 简单英文标识符、tile=[x,y]） */
  decor?: MapDecorItem[];
  /** v0.2：生成器指示（键=类别名，值=坐标数组，如 {"grass":[[2,2]]}） */
  spawnMarkers?: Record<string, number[][]>;
  /** v0.2：传送点（from=[x,y]，to=[mapId字符串,x,y]；本批不渲染，契约透传） */
  warps?: MapWarp[];
  /** P-0817-G：房间出口表（走门切换数据源；缺失/空 = 非房间模式） */
  exits?: MapExit[];
  generator?: { kind?: string; seed?: number; model?: string; note?: string };
}

/** v0.2 decor 条目 */
export interface MapDecorItem {
  id: string;
  type: string;
  tile: [number, number];
  state?: Record<string, unknown>;
  onInteract?: Record<string, unknown>;
  once?: boolean;
  radius?: number;
}

/** v0.2 warps 条目（场景切换数据表；渲染层透传） */
export interface MapWarp {
  from: [number, number];
  to: [string, number, number];
}

export interface MapRoom {
  id: string;
  name: string;
  x: number;
  y: number;
  w: number;
  h: number;
  tags?: string[];
}

export interface MapCorridor {
  id: string;
  from: string;
  to: string;
  points: number[][];
}

export interface MapZone {
  id: string;
  name: string;
  type: string; // search / door / broadcast
  x: number;
  y: number;
  radius: number;
  clue_location?: string;
  prompt?: string;
}

export interface MapSpawnPoint {
  id: string;
  type: string; // player / npc
  x: number;
  y: number;
}

/** P-0817-G（房间模式）：房间出口表（契约 v0.2 扩展键 exits[]，MapExits 确定性推导） */
export interface MapExit {
  id: string;
  from: string;
  to: string;
  side?: string; // top / bottom / left / right
  door: [number, number];
}

/* ── 宽容解析（对齐契约 §3：缺省兜底、不崩） ── */

function num(v: unknown, def: number): number {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : def;
}

function str(v: unknown, def: string): string {
  return v === undefined || v === null ? def : String(v);
}

function listOf(v: unknown): unknown[] {
  return Array.isArray(v) ? v : [];
}

export function normalizeMap(raw: unknown): ScriptMap | null {
  if (!raw || typeof raw !== 'object') return null;
  const m = raw as Record<string, unknown>;
  const layers = (m.layers && typeof m.layers === 'object' ? m.layers : {}) as Record<string, unknown>;
  const ground = Array.isArray(layers.ground) ? layers.ground as number[][] : [];
  const collision = Array.isArray(layers.collision) ? layers.collision as number[][] : [];
  if (ground.length === 0 || collision.length === 0) return null;

  const zones = listOf(m.zones).map(z => {
    const zz = z as Record<string, unknown>;
    return {
      id: str(zz.id, 'zone'),
      name: str(zz.name, '热点'),
      type: str(zz.type, 'search'),
      x: num(zz.x, 0),
      y: num(zz.y, 0),
      radius: Number(zz.radius) >= 0 ? Number(zz.radius) : 1,
      clue_location: str(zz.clue_location, ''),
      prompt: str(zz.prompt, ''),
    } as MapZone;
  });

  const spawns = listOf(m.spawn_points).map(s => {
    const ss = s as Record<string, unknown>;
    return {
      id: str(ss.id, 'spawn'),
      type: str(ss.type, 'npc'),
      x: num(ss.x, 0),
      y: num(ss.y, 0),
    } as MapSpawnPoint;
  });

  const rooms = listOf(m.rooms).map(r => {
    const rr = r as Record<string, unknown>;
    return {
      id: str(rr.id, 'room'),
      name: str(rr.name, ''),
      x: num(rr.x, 0),
      y: num(rr.y, 0),
      w: num(rr.w, 0),
      h: num(rr.h, 0),
      tags: Array.isArray(rr.tags) ? rr.tags.map(String) : [],
    } as MapRoom;
  });

  const corridors = listOf(m.corridors).map(c => {
    const cc = c as Record<string, unknown>;
    const pts = Array.isArray(cc.points)
      ? (cc.points as unknown[][]).filter(p => Array.isArray(p) && p.length >= 2).map(p => [Number(p[0]), Number(p[1])])
      : [];
    return { id: str(cc.id, 'cor'), from: str(cc.from, ''), to: str(cc.to, ''), points: pts } as MapCorridor;
  });

  /* ── v0.2 可选键宽容解析（对齐契约 §7：缺失一律兜底为空，v1 数据零破坏） ── */

  // layers.objects / layers.overlay：字符串二维数组（元素可 null），非数组 → undefined（渲染层跳过）
  const strGrid = (v: unknown): (string | null)[][] | undefined => {
    if (!Array.isArray(v)) return undefined;
    const out: (string | null)[][] = [];
    for (const row of v) {
      if (!Array.isArray(row)) return undefined;
      out.push(row.map(cell => (cell === undefined || cell === null ? null : String(cell))));
    }
    return out;
  };

  // tileProps：键 "x,y" → 值对象字典（宽容透传，不做白名单）；非对象 → 丢弃该键
  const tileProps: Record<string, Record<string, unknown>> = {};
  if (m.tileProps && typeof m.tileProps === 'object') {
    for (const [k, v] of Object.entries(m.tileProps as Record<string, unknown>)) {
      if (v && typeof v === 'object') tileProps[k] = v as Record<string, unknown>;
    }
  }

  // decor：{id,type,tile:[x,y],state?,onInteract?,once?,radius?} 宽容解析
  const decor = listOf(m.decor).map(d => {
    const dd = d as Record<string, unknown>;
    const tile = Array.isArray(dd.tile) && dd.tile.length >= 2 ? [Number(dd.tile[0]), Number(dd.tile[1])] : [0, 0];
    return {
      id: str(dd.id, 'decor'),
      type: str(dd.type, 'unknown'),
      tile: tile as [number, number],
      state: dd.state && typeof dd.state === 'object' ? dd.state : undefined,
      onInteract: dd.onInteract && typeof dd.onInteract === 'object' ? dd.onInteract : undefined,
      once: dd.once === undefined ? undefined : Boolean(dd.once),
      radius: dd.radius !== undefined ? Number(dd.radius) : undefined,
    } as MapDecorItem;
  });

  // spawnMarkers：键=类别名，值=[[x,y],...] 坐标数组（非数对丢弃）
  const spawnMarkers: Record<string, number[][]> = {};
  if (m.spawnMarkers && typeof m.spawnMarkers === 'object') {
    for (const [cat, pts] of Object.entries(m.spawnMarkers as Record<string, unknown>)) {
      if (!Array.isArray(pts)) continue;
      const valid = (pts as unknown[]).filter(p => Array.isArray(p) && p.length >= 2).map(p => [Number((p as unknown[])[0]), Number((p as unknown[])[1])]);
      if (valid.length > 0) spawnMarkers[cat] = valid;
    }
  }

  // warps：{from:[x,y], to:[mapId,x,y]} 宽容解析
  const warps = listOf(m.warps).map(w => {
    const ww = w as Record<string, unknown>;
    const from = Array.isArray(ww.from) && ww.from.length >= 2 ? [Number((ww.from as unknown[])[0]), Number((ww.from as unknown[])[1])] : [0, 0];
    const to = Array.isArray(ww.to) && ww.to.length >= 3 ? [String((ww.to as unknown[])[0]), Number((ww.to as unknown[])[1]), Number((ww.to as unknown[])[2])] : ['', 0, 0];
    return { from: from as [number, number], to: to as [string, number, number] } as MapWarp;
  });

  // P-0817-G：exits 宽容解析（{id, from, to, side?, door:[x,y]}；door 非法或 from/to 缺失 → 跳过）
  const exits = listOf(m.exits).map(e => {
    const ee = e as Record<string, unknown>;
    const door = Array.isArray(ee.door) && ee.door.length >= 2
      ? [Number((ee.door as unknown[])[0]), Number((ee.door as unknown[])[1])]
      : null;
    if (!door) return null;
    return {
      id: str(ee.id, 'exit'),
      from: str(ee.from, ''),
      to: str(ee.to, ''),
      side: ee.side === undefined ? undefined : String(ee.side),
      door: door as [number, number],
    } as MapExit;
  }).filter((e): e is MapExit => !!e && !!e.from && !!e.to);

  return {
    map_version: Number(m.map_version) || 1,
    map_id: str(m.map_id, 'map'),
    name: str(m.name, '未命名地图'),
    theme: str(m.theme, ''),
    tile_size: num(m.tile_size, 32),
    width: num(m.width, ground[0]?.length ?? 0),
    height: num(m.height, ground.length),
    tileset: (m.tileset && typeof m.tileset === 'object' ? m.tileset : undefined) as ScriptMap['tileset'],
    layers: {
      ground,
      collision,
      objects: strGrid(layers.objects),
      overlay: strGrid(layers.overlay),
    },
    rooms,
    corridors,
    zones,
    spawn_points: spawns,
    tileProps: Object.keys(tileProps).length > 0 ? tileProps : undefined,
    decor: decor.length > 0 ? decor : undefined,
    spawnMarkers: Object.keys(spawnMarkers).length > 0 ? spawnMarkers : undefined,
    warps: warps.length > 0 ? warps : undefined,
    exits: exits.length > 0 ? exits : undefined,
    generator: (m.generator && typeof m.generator === 'object' ? m.generator : undefined) as ScriptMap['generator'],
  };
}

/** 瓦片 id → 运行时生成色块纹理的颜色（对齐契约 tiles.png 语义：1木地板 2墙 3草地 4地毯 5石板） */
export function tileColor(id: number): number {
  switch (id) {
    case 1: return 0x8b5e3c; // 木地板
    case 2: return 0x64748b; // 墙
    case 3: return 0x3f9e4d; // 草地
    case 4: return 0x9c3d3d; // 地毯
    case 5: return 0x94a3b8; // 石板
    default: return 0x2a2a35; // 未知/装饰
  }
}
