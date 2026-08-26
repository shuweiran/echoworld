/**
 * backendScenes.ts — P-0816-L：后端场景（GET /api/scenes）→ 前端剧本卡（MurderScript/GeneralScript）映射
 *
 * 后端 GET /api/scenes 返回结构（SceneController.list + DatabaseService.entityToMap）：
 *   scene_id / name / description / keywords / category(general|werewolf) /
 *   default_roles(List<string>) / default_map(契约 v1 对象或 null) / goals(对象或 null) /
 *   initial_agent_names(List<string>) / createdAt
 *
 * ── 归属决策（murder tab vs general tab）──
 *   ① scene_id 前缀 'script_' → 剧本杀模式（murder）列表：
 *     这些是剧本杀对局链路保存的谋杀剧本（SceneController.create 支持客户端指定 scene_id，
 *     旧前端剧本杀链路固定 script_xxx 前缀；实测库中 陆宅迷局/夜来香血案/迷雾别墅 等均为此类）；
 *   ② category === 'werewolf' → 一般模式列表（带 🐺 chip）：
 *     demo2 无狼人杀剧本卡页签，对齐整机版 D-033「一般模式页签 = 一般+狼人杀剧本卡（带 chip）」先例；
 *   ③ 其余（category==='general' 且非 script_ 前缀）→ 一般模式列表。
 *
 * ── 字段映射 ──
 *   通用：scene_id→id / name→title / description→background(+desc)
 *   角色：initial_agent_names（后端真实角色名，滤除 'me' 玩家占位）→ 占位 RoleCard
 *     （id=backend_<scene_id>_<name>，intro 标注「后端场景角色」；personality 等留空——
 *     后端场景不携带角色卡五层数据，姓名来自真实数据，不虚构人格，避免造假）；
 *   人数（murder 卡）：playerMin=playerMax=角色名数量（后端场景无 min/max 字段，诚实取实际人数）；
 *   地图（general 卡）：default_map 为合法契约 v1 对象则直用；否则 buildMap BSP 占位
 *     （与 P-0811-G 兜底同源，保证角色选择页/2D 探索不因缺 map 崩溃）。
 */
import type { GeneralScript, MurderScript, RoleCard, RoleSource } from './types';
import type { ScriptMap } from '../phaser/mapData';
import { buildMap } from './mockData';

/** 后端场景原始记录（GET /api/scenes 元素，宽容字段） */
export interface BackendSceneRecord {
  scene_id: string;
  name: string;
  description?: string | null;
  keywords?: string | null;
  category?: string | null;
  default_roles?: unknown;
  default_map?: unknown;
  goals?: unknown;
  initial_agent_names?: string[] | null;
  createdAt?: string | null;
}

/** 后端场景归属判定：script_ 前缀 → 剧本杀（murder）剧本 */
export function isMurderBackendScene(s: BackendSceneRecord): boolean {
  return String(s.scene_id ?? '').startsWith('script_');
}

/** 后端场景归属判定：werewolf 分类（展示在一般模式列表，带 🐺 chip） */
export function isWerewolfBackendScene(s: BackendSceneRecord): boolean {
  return String(s.category ?? '').trim() === 'werewolf';
}

/** 后端真实角色名（滤除 'me' 玩家占位，去重） */
function roleNamesOf(s: BackendSceneRecord): string[] {
  const names: string[] = [];
  for (const raw of s.initial_agent_names ?? []) {
    const n = String(raw ?? '').trim();
    if (!n || n === 'me' || n === '我') continue;
    if (!names.includes(n)) names.push(n);
  }
  return names;
}

/** 角色名 → 占位 RoleCard（姓名真实、人格留空不虚构；source='backend'） */
function rolesOf(s: BackendSceneRecord): RoleCard[] {
  const sceneId = String(s.scene_id ?? '');
  return roleNamesOf(s).map(name => ({
    id: `backend_${sceneId}_${name}`,
    name,
    avatar: '🧑',
    intro: `${name}（后端场景角色）`,
    personality: '',
    talkStyle: '',
    background: '',
    hasSecret: false,
    source: 'backend' as RoleSource,
    homeScripts: [sceneId],
  }));
}

/** 后端 default_map → ScriptMap（合法契约 v1 对象直用；字符串尝试 JSON.parse；否则 null） */
function defaultMapOf(s: BackendSceneRecord): ScriptMap | null {
  const dm = s.default_map;
  if (!dm) return null;
  let obj: any = dm;
  if (typeof dm === 'string') {
    try { obj = JSON.parse(dm); } catch { return null; }
  }
  if (obj && typeof obj === 'object' && Array.isArray(obj.layers?.ground) && typeof obj.width === 'number' && typeof obj.height === 'number') {
    return obj as ScriptMap;
  }
  return null;
}

/** 后端场景 → 剧本杀（murder）剧本卡 */
export function backendSceneToMurder(s: BackendSceneRecord): MurderScript {
  const sceneId = String(s.scene_id ?? '');
  const name = String(s.name ?? '未命名剧本');
  const desc = String(s.description ?? '').trim() || name;
  const roles = rolesOf(s);
  const n = Math.max(1, roles.length);
  return {
    id: sceneId,
    title: name,
    tags: [isWerewolfBackendScene(s) ? '狼人杀' : '后端'],
    background: desc,
    playerMin: n,
    playerMax: n,
    plot: '',
    relations: [],
    roles,
    clues: [],
    locations: [],
    truth: '',
    killerId: '',
    source: 'backend',
  };
}

/** 后端场景 → 一般模式剧本卡（category=werewolf 时带 🐺 chip） */
export function backendSceneToGeneral(s: BackendSceneRecord): GeneralScript {
  const sceneId = String(s.scene_id ?? '');
  const name = String(s.name ?? '未命名场景');
  const desc = String(s.description ?? '').trim() || name;
  const ww = isWerewolfBackendScene(s);
  // seed 取 scene_id 哈希，保证同场景同 seed（BSP 确定性，刷新不换图）
  let seed = 20260816;
  for (let i = 0; i < sceneId.length; i++) seed = (seed * 31 + sceneId.charCodeAt(i)) >>> 0;
  return {
    id: sceneId,
    title: name,
    emoji: ww ? '🐺' : '🏞️',
    theme: ww ? '狼人杀' : '一般模式',
    tags: ww ? ['🐺 狼人杀', '后端'] : ['后端场景'],
    desc,
    background: desc,
    relations: [],
    roles: rolesOf(s),
    map: defaultMapOf(s) ?? buildMap(name, seed),
    opening: '',
    source: 'backend',
  };
}
