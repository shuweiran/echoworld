/**
 * evidenceFilter.ts — 证据检索/chips 纯函数（ui-proto-v2，共享工具）
 *
 * 阶段 D（P-0817-E）自 components/ChatPage/actionUtils.ts 原样抽取（只搬代码 + 改 import，
 * 零行为变化；actionUtils 保留 re-export 兼容旧消费点）。
 * 消费点：ScriptProtoRightPanel（证据库检索框 + chips）/ actionUtils re-export。
 *
 * 纯函数（零 React 依赖，可 esbuild 打包 Node 冒烟）：
 *   - evidenceTags       证据检索分类（Her Story chips：人物/地点/时间，决策 C7 MVP 纯前端优先）
 *   - filterEvidence     证据检索：query 子串命中 + 分类 chip 过滤（纯前端过滤 status.clues）
 */
export interface ScriptClueLike {
  id?: string;
  title?: string;
  location?: string;
  content?: string;
  ap_cost?: number;
  /** P-0816-M：是否可转交（D-016 transfer_clue 契约；右栏线索库转交操作行判断） */
  transferable?: boolean;
  /** P-0816-T（阶段三 API-9）：公开线索标记（public=true 全员可见，可直接出示；后端契约） */
  public?: boolean;
}

/** 时间类关键词（证据检索「时间」分类启发式；宽松匹配，不追求全量） */
const TIME_KEYWORDS = [
  '凌晨', '早晨', '早上', '中午', '下午', '傍晚', '晚上', '深夜', '午夜',
  '昨晚', '前天', '昨天', '今天', '明天', '后天', '星期', '月', '夜',
];

/** 时间模式：数字+点时/分/秒、第N天/夜/轮等 */
const TIME_RE = /[0-9０-９一二三四五六七八九十]+\s*[点时分钟秒]/;

/**
 * 证据检索分类标签（Her Story chips：人物/地点/时间）——纯前端启发式：
 *  - 人物：content/title 提及任一角色名
 *  - 地点：content/title 提及任一地点名（不含线索自身 location 字段——
 *    那是卡片上的 loc-tag 独立展示，纳入会让「地点」分类恒全量命中）
 *  - 时间：命中时间关键词/模式
 * 标签可多选命中；无命中返回空数组（仅「全部」可见）。
 */
export function evidenceTags(
  clue: ScriptClueLike | null | undefined,
  roles: string[] | null | undefined,
  locations: string[] | null | undefined,
): string[] {
  if (!clue) return [];
  const text = `${String(clue.content ?? '')} ${String(clue.title ?? '')}`;
  const tags: string[] = [];
  const roleList = Array.isArray(roles) ? roles : [];
  const locList = Array.isArray(locations) ? locations : [];
  if (roleList.some(r => r && text.includes(r))) tags.push('人物');
  if (locList.some(l => l && text.includes(l))) tags.push('地点');
  if (TIME_KEYWORDS.some(k => text.includes(k)) || TIME_RE.test(text)) tags.push('时间');
  return tags;
}

/**
 * 证据检索（决策 C7 MVP 纯前端优先）：query 子串命中 content/title/id + category chip 过滤。
 * category：'全部' | '人物' | '地点' | '时间'（'全部' 不做分类过滤）。
 */
export function filterEvidence(
  clues: ScriptClueLike[] | null | undefined,
  query: string,
  category: string,
  roles: string[] | null | undefined,
  locations: string[] | null | undefined,
): ScriptClueLike[] {
  const clueList = Array.isArray(clues) ? clues : [];
  const q = (query || '').trim().toLowerCase();
  return clueList.filter(c => {
    if (category && category !== '全部') {
      const tags = evidenceTags(c, roles, locations);
      if (!tags.includes(category)) return false;
    }
    if (!q) return true;
    const hay = `${String(c?.content ?? '')} ${String(c?.title ?? '')} ${String(c?.id ?? '')}`.toLowerCase();
    return hay.includes(q);
  });
}
