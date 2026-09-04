/**
 * backendScenes.ts — 后端场景（GET /api/scenes）→ 一般模式剧本卡映射
 *
 * 重要边界：
 * - /api/scenes 是通用「场景」数据源，不等于剧本杀 MurderScript 数据源。
 * - 剧本杀必须保留 plot / clues / truth / killerId 等完整剧本字段，不能把普通 scene
 *   仅凭 scene_id 前缀强转成 MurderScript。
 * - scene_id 以 script_ 开头的旧记录视为历史/运行态遗留数据，不再注入任何剧本选择列表。
 * - category=werewolf 由狼人杀入口管理，不再混入一般模式列表。
 *
 * 后端 GET /api/scenes 当前常见结构：
 *   scene_id / name / description / category(general|werewolf) /
 *   default_map / initial_agent_names 等。
 */
import type { GeneralScript, RoleCard, RoleSource } from './types';
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

/** 狼人杀场景由狼人杀入口管理，不进入一般模式剧本列表。 */
export function isWerewolfBackendScene(s: BackendSceneRecord): boolean {
  return String(s.category ?? '').trim().toLowerCase() === 'werewolf';
}

/**
 * 旧版/运行态 script_* scene 不是完整 MurderScript。
 * 它们可能来自旧链路或会话运行数据，因此从剧本选择页直接排除。
 */
export function isLegacyScriptBackendScene(s: BackendSceneRecord): boolean {
  return String(s.scene_id ?? '').trim().startsWith('script_');
}

/**
 * 后端通用 scene 是否属于「一般模式」。
 * - category=general：明确一般模式；
 * - category 为空：兼容旧的一般场景数据；
 * - werewolf / murder / 未知分类：不注入一般模式；
 * - script_*：无论 category 如何都视为旧运行态记录，避免再次串栏。
 */
export function isGeneralBackendScene(s: BackendSceneRecord): boolean {
  if (isLegacyScriptBackendScene(s)) return false;
  const category = String(s.category ?? '').trim().toLowerCase();
  return category === '' || category === 'general';
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

/** 后端场景 → 一般模式剧本卡。仅应对 isGeneralBackendScene(s)===true 的记录调用。 */
export function backendSceneToGeneral(s: BackendSceneRecord): GeneralScript {
  const sceneId = String(s.scene_id ?? '');
  const name = String(s.name ?? '未命名场景');
  const desc = String(s.description ?? '').trim() || name;
  // seed 取 scene_id 哈希，保证同场景同 seed（BSP 确定性，刷新不换图）
  let seed = 20260816;
  for (let i = 0; i < sceneId.length; i++) seed = (seed * 31 + sceneId.charCodeAt(i)) >>> 0;
  return {
    id: sceneId,
    title: name,
    emoji: '🏞️',
    theme: '一般模式',
    tags: ['后端场景'],
    desc,
    background: desc,
    relations: [],
    roles: rolesOf(s),
    map: defaultMapOf(s) ?? buildMap(name, seed),
    opening: '',
    source: 'backend',
  };
}
