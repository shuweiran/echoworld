/**
 * navHistory.ts — 页面导航 ↔ 浏览器历史（P-0817-R）
 *
 * 目标：浏览器前进/后退按钮可回到上一个/下一个局内页面；URL 用 hash 路由
 * （`#/game`、`#/settings`…），兼容静态托管与深链直达。
 * 本文件只含纯函数（Node 冒烟可测，不依赖 window）。
 */

/** 导航视图白名单（与 demo2/store.ts View 对齐；werewolf 由 roles 变体承载） */
export const NAV_VIEWS = [
  'home', 'scripts', 'roles', 'gen', 'manual', 'settings',
  'roles-lib', 'free-chars', 'role-detail', 'game',
] as const;

export type NavView = typeof NAV_VIEWS[number];

/** 视图 → URL hash（如 game → "#/game"） */
export function viewToHash(v: string): string {
  return '#/' + v;
}

/** URL hash → 视图（非法/未知 → null；支持 "#/game" 与 "#game" 宽容） */
export function hashToView(hash: string): NavView | null {
  if (!hash) return null;
  const h = hash.trim();
  const raw = h.startsWith('#/') ? h.slice(2) : h.startsWith('#') ? h.slice(1) : h;
  const view = raw.split('?')[0].split('/')[0].trim();
  if (!view) return null;
  return (NAV_VIEWS as readonly string[]).includes(view) ? view as NavView : null;
}
