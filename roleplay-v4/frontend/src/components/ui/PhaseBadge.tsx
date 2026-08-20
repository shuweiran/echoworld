/**
 * PhaseBadge.tsx — 剧本杀阶段彩色徽章（ui-proto-v2，共享组件）
 *
 * 阶段 D（P-0817-E）自 components/ChatPage/chatUtils.ts 抽取阶段色逻辑 + 新建徽章组件：
 *   - SCRIPT_PHASE_LABEL / SCRIPT_PHASE_EMOJI  阶段文案/图标（原 chatUtils 定义，原样搬移）
 *   - scriptPhaseThemeClass                    阶段 → 主题类名纯函数（原 chatUtils 定义，原样搬移）
 *   - PhaseBadge                               阶段彩色徽章组件（.proto-badge .proto-badge-phase）
 * 只搬代码 + 改 import，零视觉变化；chatUtils 保留 re-export 兼容旧消费点。
 * 消费点：ScriptProtoTopbar（剧本杀顶栏阶段徽章）/ scriptPhaseThemeClass（ChatPage 阶段色切换）。
 */
export const SCRIPT_PHASE_LABEL: Record<string, string> = {
  setup: '准备阶段', investigation: '搜证阶段', discussion: '讨论阶段',
  vote: '投票阶段', reveal: '揭晓阶段', ended: '已结束',
};
export const SCRIPT_PHASE_EMOJI: Record<string, string> = {
  setup: '🎭', investigation: '🔍', discussion: '🗣️', vote: '🗳️', reveal: '🎬', ended: '🏁',
};

/* ── 剧本杀阶段主题（P-0816-M：阶段色随阶段切换 青蓝/暖橙/红紫） ─── */
/**
 * 阶段 → 主题类名（挂到 .workspace.proto-v2.phase-<phase>，CSS 变量随阶段切换）：
 *   investigation 青蓝（原型 investigation.html --phase:#0ea5e9）
 *   discussion    暖橙（原型 discussion.html --phase:#f59e0b）
 *   vote          红紫（原型 vote.html --grad:#dc2626→#9333ea）
 *   setup/reveal/ended 及其他 → 默认紫（沿用现有 proto 主色）
 */
export function scriptPhaseThemeClass(phase?: string): string {
  const p = String(phase || '');
  if (p === 'investigation' || p === 'discussion' || p === 'vote') return `phase-${p}`;
  return 'phase-default';
}

export interface PhaseBadgeProps {
  /** 剧本杀阶段键（setup/investigation/discussion/vote/reveal/ended） */
  phase?: string;
}

/** 阶段彩色徽章（原型 badge-phase：渐变 + 发光；渲染 markup 与 P-0816-M 原顶栏完全一致） */
export function PhaseBadge({ phase }: PhaseBadgeProps) {
  const p = String(phase || '');
  return (
    <span className={`proto-badge proto-badge-phase phase-${p}`}>
      {SCRIPT_PHASE_EMOJI[p] || '🎭'} {SCRIPT_PHASE_LABEL[p] || p}
    </span>
  );
}
