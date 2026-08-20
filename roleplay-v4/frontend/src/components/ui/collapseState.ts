/**
 * collapseState.ts — 侧边栏折叠状态纯逻辑（localStorage 按页面分存）
 *
 * 阶段 D（P-0817-E）自 components/ChatPage/collapseState.ts 原样抽取至共享层
 * （只搬代码 + 改 import，零视觉变化；原位置保留 re-export 兼容）。
 * 消费点：useCollapsibleSidebars（ChatPage 三栏布局折叠 hook）。
 *
 * 对齐原型 investigation.html（docs/ui-prototype/）的交互模式：
 *   - localStorage 键独立（原型静态页用 `ui-proto-view-v2`，React 侧用 `roleplay_ui_proto_v2` 避免串扰）
 *   - 结构 `{ [pageKey]: { left, right } }`：按页面分存，同一对局页不同入口互不干扰
 *   - 纯函数可单测（无 React 依赖），useCollapsibleSidebars hook 只做状态包装
 */
export interface SidebarView {
  /** 左栏是否处于收窄（icon rail）模式 */
  left: boolean;
  /** 右栏是否处于收起（隐藏 + FAB）模式 */
  right: boolean;
}

export const SIDEBAR_STORAGE_KEY = 'roleplay_ui_proto_v2';

const EMPTY_VIEW: SidebarView = { left: false, right: false };

/** 读取指定页面的折叠状态（无记录/损坏 → 默认展开）。 */
export function loadSidebarView(pageKey: string): SidebarView {
  if (!pageKey) return { ...EMPTY_VIEW };
  try {
    const all = JSON.parse(localStorage.getItem(SIDEBAR_STORAGE_KEY) || '{}');
    const v = all?.[pageKey];
    if (v && typeof v === 'object') {
      return {
        left: v.left === true,
        right: v.right === true,
      };
    }
  } catch { /* 损坏数据静默回退默认 */ }
  return { ...EMPTY_VIEW };
}

/** 保存指定页面的折叠状态（按页面分存，不覆盖其他页面）。 */
export function saveSidebarView(pageKey: string, view: SidebarView): void {
  if (!pageKey) return;
  try {
    const all = JSON.parse(localStorage.getItem(SIDEBAR_STORAGE_KEY) || '{}');
    all[pageKey] = { left: view.left === true, right: view.right === true };
    localStorage.setItem(SIDEBAR_STORAGE_KEY, JSON.stringify(all));
  } catch { /* 存储不可用（隐私模式等）静默 */ }
}
