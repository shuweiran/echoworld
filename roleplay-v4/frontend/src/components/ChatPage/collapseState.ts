/**
 * collapseState.ts — 兼容 re-export（阶段 D P-0817-E 已迁移至共享层 components/ui/collapseState.ts）
 *
 * 原实现已原样搬至 components/ui/collapseState.ts（只搬代码 + 改 import，零视觉变化）。
 * 本文件保留 re-export 兼容旧 import 路径；新消费点请直接引共享层路径。
 */
export {
  loadSidebarView,
  saveSidebarView,
  SIDEBAR_STORAGE_KEY,
  type SidebarView,
} from '../ui/collapseState';
