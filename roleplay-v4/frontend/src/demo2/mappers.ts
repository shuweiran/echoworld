/**
 * mappers.ts — 后端 Schema v1 → demo2 结构映射（P-0811-E）
 *
 * 职责：把后端真实 LLM 接口的返回（角色生成表层响应 / 剧本 Schema v1 JSON /
 * 场景生成 {name, description}）映射为 demo2 消费的 RoleCard / MurderScript / GeneralScript。
 * 仅在真实接口成功路径使用；失败路径仍走 mockData 兜底（见各页面）。
 */
import type { GeneralScript, MurderScript, RoleCard } from './types';
import { AVATARS, buildMap, uid } from './mockData';

/** 由字符串确定性派生头像（同名字同头像，跨页面稳定） */
function pickAvatar(seedStr: string, i = 0): string {
  let h = 0;
  for (let k = 0; k < seedStr.length; k++) h = (h * 31 + seedStr.charCodeAt(k)) >>> 0;
  return AVATARS[Math.abs(h + i) % AVATARS.length];
}

/** 兜底简介（角色生成表层响应只回 name/appearance/summary，其余字段缺失时用占位） */
function fallbackIntro(name: string): string {
  return `${name}，由 AI 为你生成的角色。`;
}

/**
 * 后端角色 → demo2 RoleCard。
 * 兼容两种来源：① POST /api/characters/generate 表层响应（{name, appearance?, summary?}）；
 * ② 剧本 Schema v1 roles[]（{id,name,intro,is_hidden,secret,...}）。
 * 五层 persona 层数据后端不透出（P-0810-10 硬性），前端只消费表层。
 */
export function v1RoleToRoleCard(role: any, i = 0, homeScripts: string[] = []): RoleCard {
  const name = String(role?.name ?? `角色${i + 1}`);
  const intro = String(role?.intro ?? role?.summary ?? role?.appearance ?? fallbackIntro(name));
  return {
    id: String(role?.id ?? `ai_${Date.now().toString(36)}${i}`),
    name,
    avatar: pickAvatar(name, i),
    intro,
    personality: String(role?.personality ?? '由 AI 生成'),
    talkStyle: String(role?.talkStyle ?? '自然交流'),
    background: role?.background ? String(role.background) : (role?.appearance ? String(role.appearance) : undefined),
    secret: role?.secret ? String(role.secret) : undefined,
    hasSecret: !!role?.secret || !!role?.is_hidden,
    source: 'ai',
    homeScripts,
  };
}

/**
 * 后端剧本 Schema v1 JSON → demo2 MurderScript。
 * v1 结构：metadata{title,player_min,player_max,tags} / background / roles[] /
 * clues[]{id,title,location,content} / locations[] / killer_id / truth / secrets。
 */
export function v1ScriptToMurder(
  v1: any,
  extra?: { background?: string; direction?: string; genre?: string },
): MurderScript {
  const meta = (v1 && typeof v1 === 'object' ? v1.metadata : undefined) || {};
  const rolesRaw: any[] = Array.isArray(v1?.roles) ? v1.roles : [];
  const roles = rolesRaw.map((r, i) => v1RoleToRoleCard(r, i, []));
  const clues = (Array.isArray(v1?.clues) ? v1.clues : []).map((c: any, i: number) => ({
    id: String(c?.id ?? `clue_${i + 1}`),
    title: String(c?.title ?? `线索${i + 1}`),
    location: String(c?.location ?? '现场'),
    content: String(c?.content ?? ''),
  }));
  const locations = Array.isArray(v1?.locations) && v1.locations.length
    ? v1.locations.map(String)
    : [...new Set(clues.map((c: any) => c.location))];
  const title = String(meta.title ?? v1?.name ?? '未命名剧本');
  const direction = extra?.direction || '查明真相';
  return {
    id: `gen_${uid('m')}`,
    title,
    tags: Array.isArray(meta.tags) && meta.tags.length
      ? meta.tags.map(String)
      : [extra?.genre || '悬疑'],
    background: String(v1?.background ?? extra?.background ?? `${title}：一座看似平静的地方，一夜之间掀起命案。每个人都有自己的秘密。`),
    playerMin: Number(meta.player_min ?? (roles.length || 4)),
    playerMax: Number(meta.player_max ?? (roles.length || 6)),
    plot: `围绕“${direction}”展开：开场 → 搜证 → 对质 → 投票揭晓。`,
    relations: roles.map(r => `${r.name}·本局角色`),
    roles,
    clues,
    locations,
    truth: String(v1?.truth ?? ''),
    killerId: String(v1?.killer_id ?? roles[0]?.id ?? ''),
    source: 'ai',
  };
}

/**
 * 一般模式场景结构装配：LLM 场景名+描述 → GeneralScript。
 * P-0811-E（追加）：llmRoles 可选——后端场景生成附带的配套角色（已自动落库，表层映射为 RoleCard）
 * 作为新场景的角色列表（替代预置占位角色）；缺省/空时回退本地 3 个占位角色（角色/地图为本地结构非 AI mock）。
 */
export function assembleGeneralScript(name: string, desc: string, llmRoles?: RoleCard[]): GeneralScript {
  const t = name.trim() || '自定义世界';
  const text = (desc && desc.trim()) ? desc.trim() : `一段关于「${t}」的旅程从这里开始。`;
  const id = `gen_${uid('g')}`;
  const mk = (i: number, intro: string): RoleCard => ({
    id: `gen_${t}_${i}`,
    name: i === 0 ? '引路人' : i === 1 ? '同行者' : '守望者',
    avatar: pickAvatar(t, i + 2),
    intro,
    personality: '由 AI 生成',
    talkStyle: '自然交流',
    background: `在「${t}」中生活。`,
    hasSecret: false,
    source: 'ai',
    homeScripts: [],
  });
  const roles = (llmRoles && llmRoles.length > 0)
    ? llmRoles.map((r, i) => ({ ...r, id: r.id || `gen_role_${id}_${i}`, homeScripts: [id] }))
    : [
        mk(0, `在这片「${t}」世界中生活的角色。`),
        mk(1, `与你有交集的同伴。`),
        mk(2, `守望着这片世界的故人。`),
      ].map(r => ({ ...r, homeScripts: [id] }));
  return {
    id,
    title: t,
    emoji: '🌍',
    theme: t,
    tags: [t],
    desc: text,
    background: text,
    relations: roles.map((r, i) => `${r.name}·${i === 0 ? '引领者' : i === 1 ? '同行者' : '守望者'}`),
    roles,
    map: buildMap(t, Math.floor(Math.random() * 1e6)),
    opening: `你睁开眼，发现自己正身处「${t}」的世界……`,
    source: 'ai',
  };
}
