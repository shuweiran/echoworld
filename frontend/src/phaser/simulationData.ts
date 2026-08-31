/**
 * simulationData.ts — Phaser 渲染层数据适配（阶段 1）
 *
 * 职责：把后端 /api/simulation/*（REST + SSE world_snapshot）的原始载荷
 * 归一为渲染层对象。数据流不变——本文件只做形状适配，不改变任何后端契约。
 *
 * 对应：docs/Phaser迁移计划.md 阶段 1；D-020 结构性前提（后端权威模拟 + 前端纯渲染）。
 */

/** 后端 AgentState.toMap() 的子集（渲染需要的最小字段，宽容解析） */
export interface SimAgent {
  agentName: string;
  /** P-0824-L：轻量群演投影标识；点击后可按 roleId 请求晋升为完整 Agent。 */
  roleId?: string;
  ambient?: boolean;
  roleTier?: string;
  lifecycleStatus?: string;
  x: number;
  y: number;
  floorId?: string;
  surfaceId?: string;
  track?: string;
  emotion?: string;
  emotionEmoji?: string;
  hearRange?: number;
  moveSpeed?: number;
  currentMessage?: string;
  inConversation?: boolean;
  hasTarget?: boolean;
  targetX?: number;
  targetY?: number;
  targetFloorId?: string;
  targetSurfaceId?: string;
  navigationWaypoints?: Array<{
    x: number;
    y: number;
    floorId: string;
    surfaceId?: string;
    transition?: boolean;
    connectorId?: string;
  }>;
  navigationWaypointIndex?: number;
  stance?: string;
  attention?: number;
  playerControlled?: boolean;
  manualTarget?: boolean;
  vx?: number;
  vy?: number;
}

/** 后端 Obstacle.toMap() 子集 */
export interface SimObstacle {
  type: string;
  x: number;
  y: number;
  width: number;
  height: number;
  blocksSound?: boolean;
  label?: string;
  floorId?: string;
}

/** 后端 WorldSnapshot.toMap() / GET /api/simulation/state 的子集 */
export interface SimSnapshot {
  tick?: number;
  /** P-0827-B：由 MapContract 驱动的后端权威世界像素边界。 */
  worldWidth?: number;
  worldHeight?: number;
  floors?: Array<{ id: string; elevation?: number; width?: number; height?: number }>;
  connectors?: Array<Record<string, unknown>>;
  agents?: SimAgent[];
  obstacles?: SimObstacle[];
  timestamp?: number;
  worldNarration?: string;
  directorActive?: boolean;
  scene?: string;
  running?: boolean;
  agentCount?: number;
  recentConversations?: unknown[];
}

/** Pure renderer projection: hiding another floor never mutates authoritative state. */
export function projectSnapshotToFloor(snapshot: SimSnapshot, floorId: string): SimSnapshot {
  const floor = floorId || 'ground';
  return {
    ...snapshot,
    agents: (snapshot.agents || []).filter(agent => (agent.floorId || 'ground') === floor),
    obstacles: (snapshot.obstacles || []).filter(obstacle => (obstacle.floorId || 'ground') === floor),
  };
}

export function availableFloorIds(snapshot: SimSnapshot): string[] {
  const ids = new Set<string>();
  (snapshot.floors || []).forEach(floor => { if (floor?.id) ids.add(floor.id); });
  (snapshot.agents || []).forEach(agent => ids.add(agent.floorId || 'ground'));
  if (ids.size === 0) ids.add('ground');
  return [...ids].sort((a, b) => a.localeCompare(b));
}

/** 对话组（conversation-status 载荷子集） */
export interface SimGroup {
  /** P-0803-G：组 ID（后端 ConversationGroup.getGroupId，join/leave 端点路径参数；旧后端/缺省时不提供） */
  id?: string;
  mode?: string;
  participants?: string[];
  rounds?: number;
  turns?: number;
  /** P-0803-G：组空闲毫秒数（后端 30s idle 自动解散，前端展示/判断可参考） */
  idleMs?: number;
  topic?: { description?: string };
}

/* ── 视觉常量（与 static/simulation.html 自研 Canvas 渲染保持一致，无回归） ── */

export const WORLD_W = 1000;
export const WORLD_H = 600;

/**
 * P-0813-G：接近提示触发距离（px）——玩家角色与 NPC 距离 < 该阈值时，
 * 该 NPC 头顶显示「💬 对话」交互提示（点击进入对话；远离后消失）。
 */
export const APPROACH_DIST = 80;

// ── P-0813-K：群接近判定阈值（px）──────────────────────────────
/** 玩家与群任一成员的距离阈值：< 100px 视为靠近该对话群。 */
export const GROUP_APPROACH_MEMBER_DIST = 100;
/** 玩家与群中心（成员位置均值）的距离阈值：< 120px 视为靠近该对话群。 */
export const GROUP_APPROACH_CENTER_DIST = 120;

/**
 * P-0813-K：计算玩家角色当前可加入（接近）的对话群名单（纯函数，供 Scene 渲染与冒烟测试共用）。
 * - 玩家不在世界中 → 空数组；
 * - 只统计「含 2+ 成员的活跃群」且玩家尚未加入的群（已在组内 → 是离开入口，不是加入提示）；
 * - 距离判定：玩家与群任一成员距离 < {@link GROUP_APPROACH_MEMBER_DIST}
 *   或 玩家与群中心（在场成员位置均值）距离 < {@link GROUP_APPROACH_CENTER_DIST}；
 * - DYAD 对偶组（mode=DYAD，后端上限 2 必满）不提供加入提示（1v1 语义，P-0803-G 同规则）。
 *
 * @returns 可加入的群 id 数组（按 conversation-status 顺序）
 */
export function findApproachableGroups(
  playerName: string,
  agents: SimAgent[],
  groups: SimGroup[],
  memberDist: number = GROUP_APPROACH_MEMBER_DIST,
  centerDist: number = GROUP_APPROACH_CENTER_DIST,
): string[] {
  if (!playerName) return [];
  const p = agents.find(a => a.agentName === playerName);
  if (!p) return [];
  const pos = new Map(agents.filter(a => a && a.agentName).map(a => [a.agentName, a] as const));
  const out: string[] = [];
  for (const g of groups || []) {
    if (!g || !g.id) continue;
    if (g.mode === 'DYAD') continue;                    // 1v1 对偶组不提供加入入口
    const members = (g.participants || []).filter(n => n && n !== playerName);
    if (members.length < 2) continue;                   // 需 2+ 成员（正在对话的 AI 群）
    if ((g.participants || []).includes(playerName)) continue; // 玩家已在组内 → 退出入口而非加入提示
    // 群中心 = 在场成员位置均值
    let sx = 0, sy = 0, cnt = 0;
    let nearMember = false;
    for (const name of members) {
      const a = pos.get(name);
      if (!a) continue;
      sx += a.x; sy += a.y; cnt++;
      if (Math.hypot(a.x - p.x, a.y - p.y) < memberDist) nearMember = true;
    }
    if (nearMember) { out.push(g.id); continue; }        // 任一成员 < 100px → 靠近
    if (cnt > 0) {
      const cx = sx / cnt, cy = sy / cnt;
      if (Math.hypot(cx - p.x, cy - p.y) < centerDist) out.push(g.id); // 群中心 < 120px → 靠近
    }
  }
  return out;
}

/**
 * P-0813-G：计算玩家角色当前可交互（接近）的 NPC 名单（纯函数，供 Scene 渲染与冒烟测试共用）。
 * - 玩家不在世界中（导演模式 / 快照未含玩家）→ 空数组（无提示）；
 * - 只统计与玩家距离 < dist 的 agent，玩家自身排除。
 */
export function findApproachable(playerName: string, agents: SimAgent[], dist: number = APPROACH_DIST): string[] {
  if (!playerName) return [];
  const p = agents.find(a => a.agentName === playerName);
  if (!p) return [];
  const out: string[] = [];
  for (const a of agents) {
    if (!a || !a.agentName || a.agentName === playerName) continue;
    if (Math.hypot(a.x - p.x, a.y - p.y) < dist) out.push(a.agentName);
  }
  return out;
}

export const AGENT_COLORS = [
  '#38bdf8', '#f472b6', '#a78bfa', '#34d399', '#fb923c', '#f87171', '#e879f9', '#2dd4bf',
];

/** 与 simulation.html agentColor() 相同的哈希取色（保证视觉不变） */
export function agentColor(name: string): string {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = ((h << 5) - h) + name.charCodeAt(i);
  return AGENT_COLORS[Math.abs(h) % AGENT_COLORS.length];
}

/** 障碍类型 → 填充色（与 simulation.html draw() 的映射一致） */
export function obstacleColor(type: string): string {
  switch (type) {
    case 'BUILDING': case 'WALL': return '#334155';
    case 'WATER': return '#1e3a5f';
    case 'TABLE': return '#4a3728';
    case 'TREE': return '#064e3b';
    case 'ROCK': return '#57534e';
    case 'BUSH': return '#14532d';
    case 'BENCH': return '#78350f';
    case 'FOUNTAIN': return '#1e40af';
    default: return '#3f3f46';
  }
}

/** 对话模式 → 群组框颜色（与 simulation.html 一致） */
export function groupModeColor(mode: string): string {
  switch (mode) {
    case 'GROUP_DISCUSSION': return '#a78bfa';
    case 'PUBLIC_SPEAKING': return '#fbbf24';
    case 'DEBATE': return '#ef4444';
    default: return '#38bdf8';
  }
}

/** 可用场景列表（与后端 Obstacle.availableScenes() 一致，仅渲染侧展示用） */
export const AVAILABLE_SCENES = ['park', 'city', 'cafe', 'forest', 'classroom', 'beach'];

/* ── 宽容解析辅助（对齐 D-014 纪律：缺省兜底，不崩） ── */

export function normalizeAgent(raw: unknown): SimAgent | null {
  if (!raw || typeof raw !== 'object') return null;
  const a = raw as Record<string, unknown>;
  const name = String(a.agentName ?? '');
  if (!name) return null;
  return {
    agentName: name,
    roleId: typeof a.roleId === 'string' ? a.roleId : undefined,
    ambient: Boolean(a.ambient),
    roleTier: typeof a.roleTier === 'string' ? a.roleTier : undefined,
    lifecycleStatus: typeof a.lifecycleStatus === 'string' ? a.lifecycleStatus : undefined,
    x: Number(a.x ?? 0),
    y: Number(a.y ?? 0),
    floorId: typeof a.floorId === 'string' && a.floorId.trim() ? a.floorId : 'ground',
    surfaceId: typeof a.surfaceId === 'string' && a.surfaceId.trim() ? a.surfaceId : 'ground',
    track: typeof a.track === 'string' ? a.track : undefined,
    emotion: String(a.emotion ?? ''),
    emotionEmoji: String(a.emotionEmoji ?? '😐'),
    hearRange: Number(a.hearRange ?? 200),
    moveSpeed: Number(a.moveSpeed ?? 80),
    currentMessage: typeof a.currentMessage === 'string' ? a.currentMessage : '',
    inConversation: Boolean(a.inConversation),
    hasTarget: Boolean(a.hasTarget),
    targetX: a.targetX !== undefined ? Number(a.targetX) : undefined,
    targetY: a.targetY !== undefined ? Number(a.targetY) : undefined,
    targetFloorId: typeof a.targetFloorId === 'string' ? a.targetFloorId : undefined,
    targetSurfaceId: typeof a.targetSurfaceId === 'string' ? a.targetSurfaceId : undefined,
    navigationWaypoints: Array.isArray(a.navigationWaypoints) ? a.navigationWaypoints
      .filter(value => !!value && typeof value === 'object')
      .map(value => {
        const waypoint = value as Record<string, unknown>;
        return {
          x: Number(waypoint.x ?? 0),
          y: Number(waypoint.y ?? 0),
          floorId: typeof waypoint.floorId === 'string' ? waypoint.floorId : 'ground',
          surfaceId: typeof waypoint.surfaceId === 'string' ? waypoint.surfaceId : undefined,
          transition: Boolean(waypoint.transition),
          connectorId: typeof waypoint.connectorId === 'string' ? waypoint.connectorId : undefined,
        };
      }) : [],
    navigationWaypointIndex: a.navigationWaypointIndex !== undefined ? Number(a.navigationWaypointIndex) : 0,
    stance: String(a.stance ?? 'neutral'),
    attention: Number(a.attention ?? 0),
    playerControlled: Boolean(a.playerControlled),
    manualTarget: Boolean(a.manualTarget),
    vx: a.vx !== undefined ? Number(a.vx) : 0,
    vy: a.vy !== undefined ? Number(a.vy) : 0,
  };
}

export function normalizeObstacle(raw: unknown): SimObstacle | null {
  if (!raw || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  if (o.x === undefined || o.y === undefined) return null;
  return {
    type: String(o.type ?? 'UNKNOWN'),
    x: Number(o.x),
    y: Number(o.y),
    width: Number(o.width ?? 0),
    height: Number(o.height ?? 0),
    blocksSound: Boolean(o.blocksSound),
    label: String(o.label ?? ''),
    floorId: typeof o.floorId === 'string' && o.floorId.trim() ? o.floorId : 'ground',
  };
}

/** 归一整个快照（缺字段给默认，坏条目丢弃） */
export function normalizeSnapshot(raw: unknown): SimSnapshot {
  if (!raw || typeof raw !== 'object') return {};
  const s = raw as Record<string, unknown>;
  const agents = Array.isArray(s.agents) ? s.agents.map(normalizeAgent).filter(Boolean) as SimAgent[] : [];
  const obstacles = Array.isArray(s.obstacles) ? s.obstacles.map(normalizeObstacle).filter(Boolean) as SimObstacle[] : [];
  return {
    tick: s.tick !== undefined ? Number(s.tick) : 0,
    worldWidth: s.worldWidth !== undefined ? Number(s.worldWidth) : undefined,
    worldHeight: s.worldHeight !== undefined ? Number(s.worldHeight) : undefined,
    agents,
    obstacles,
    floors: Array.isArray(s.floors) ? s.floors.filter(v => !!v && typeof v === 'object') as SimSnapshot['floors'] : undefined,
    connectors: Array.isArray(s.connectors) ? s.connectors.filter(v => !!v && typeof v === 'object') as Array<Record<string, unknown>> : undefined,
    timestamp: s.timestamp !== undefined ? Number(s.timestamp) : undefined,
    worldNarration: typeof s.worldNarration === 'string' ? s.worldNarration : '',
    directorActive: Boolean(s.directorActive),
    scene: typeof s.scene === 'string' ? s.scene : undefined,
    running: typeof s.running === 'boolean' ? s.running : undefined,
    agentCount: s.agentCount !== undefined ? Number(s.agentCount) : agents.length,
    recentConversations: Array.isArray(s.recentConversations) ? s.recentConversations : [],
  };
}
