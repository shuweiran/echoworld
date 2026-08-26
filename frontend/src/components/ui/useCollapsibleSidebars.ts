/**
 * useCollapsibleSidebars.ts — 三栏布局折叠 hook（决策 D7 执行须知 #7，复用原型 V2 交互模式）
 *
 * 阶段 D（P-0817-E）自 components/ChatPage/useCollapsibleSidebars.ts 原样抽取至共享层
 * （只搬代码 + 改 import，零视觉变化；原位置保留 re-export 兼容）。
 * 消费点：ChatPage（三栏布局，唯一挂载点，按页面分存折叠状态）。
 *
 * 交互对齐 docs/ui-prototype/investigation.html：
 *   - 左栏 240px ⇄ 56px icon rail（◀ 收起 / ▶ 展开，收窄后图标竖排 + tooltip）
 *   - 右栏 280px 常态显示；» 收起 → 隐藏 + 右下角悬浮按钮；点悬浮按钮 → 抽屉覆盖式弹出
 *     （r-drawer，对齐原型 floatBtn）；抽屉内 ✕ 收起为悬浮按钮、» 彻底收起
 *   - 折叠状态 localStorage 按页面分存（collapseState.ts），刷新保持
 *
 * 纯状态管理，布局 class 由调用方挂在 workspace 上（l-collapsed / r-collapsed / r-drawer）。
 */
import { useCallback, useState } from 'react';
import {
  loadSidebarView,
  saveSidebarView,
  type SidebarView,
} from './collapseState';

export interface CollapsibleSidebars {
  /** 左栏是否收窄（icon rail） */
  leftCollapsed: boolean;
  /** 右栏是否收起（隐藏） */
  rightCollapsed: boolean;
  /** 右栏抽屉是否打开（覆盖式） */
  drawerOpen: boolean;
  toggleLeft: () => void;
  toggleRight: () => void;
  openDrawer: () => void;
  closeDrawer: () => void;
}

export function useCollapsibleSidebars(pageKey: string): CollapsibleSidebars {
  const [view, setView] = useState<SidebarView>(() => loadSidebarView(pageKey));
  const [drawerOpen, setDrawerOpen] = useState(false);

  const toggleLeft = useCallback(() => {
    setView(v => {
      const next: SidebarView = { ...v, left: !v.left };
      saveSidebarView(pageKey, next);
      return next;
    });
  }, [pageKey]);

  /** » 收起/展开右栏：收起时同步关闭抽屉（对齐原型 setRight：toggle 时移除 r-drawer）。 */
  const toggleRight = useCallback(() => {
    setDrawerOpen(false);
    setView(v => {
      const next: SidebarView = { ...v, right: !v.right };
      saveSidebarView(pageKey, next);
      return next;
    });
  }, [pageKey]);

  const openDrawer = useCallback(() => setDrawerOpen(true), []);
  const closeDrawer = useCallback(() => setDrawerOpen(false), []);

  return {
    leftCollapsed: view.left,
    rightCollapsed: view.right,
    drawerOpen,
    toggleLeft,
    toggleRight,
    openDrawer,
    closeDrawer,
  };
}
