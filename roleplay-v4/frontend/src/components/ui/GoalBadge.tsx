/**
 * GoalBadge.tsx — 顶栏 🎯 当前目标徽章（ui-proto-v2，共享组件）
 *
 * 阶段 D（P-0817-E）自 components/ChatPage/GoalBadge.tsx 原样抽取至共享层
 * （只搬代码 + 改 import，零视觉变化；原位置保留 re-export 兼容）。
 * 消费点：ChatTopbar（一般/狼人杀对局顶栏）/ ScriptProtoTopbar（剧本杀顶栏）。
 *
 * 接入（决策 U4/U14 / D1）：
 *   - GET /api/script/goal → {ok, phase, goal{title, progress, detail}}
 *     （规则模板：搜证 x/y、质询计数、投票 x/y；零新状态）
 *   - SSE script_goal 实时 + 3s 轮询兜底（数据由 ChatPage 统一写入 store.scriptGoal）
 *   - 倒计时由 status 字段本地计时（U9），不在徽章内重复实现
 */
export interface GoalData {
  ok?: boolean;
  phase?: string;
  goal?: {
    title?: string;
    progress?: Record<string, number>;
    detail?: string;
  };
}

export interface GoalBadgeProps {
  /** GET /api/script/goal 响应（store.scriptGoal） */
  goal: GoalData | null;
}

/** 进度展示文本（progress 键值对 → "已搜证 2/6" 类短句；无则空） */
export function progressText(g: GoalData | null): string {
  const p = g?.goal?.progress;
  if (!p) return '';
  if (typeof p.searched === 'number' && typeof p.total === 'number') return `${p.searched}/${p.total}`;
  if (typeof p.voted === 'number' && typeof p.total === 'number') return `${p.voted}/${p.total}`;
  if (typeof p.pressed === 'number') return `${p.pressed}`;
  return '';
}

export function GoalBadge({ goal }: GoalBadgeProps) {
  const title = goal?.goal?.title || '';
  const detail = goal?.goal?.detail || '';
  const prog = progressText(goal);
  if (!title) return null;
  return (
    <span className="proto-goal-badge" title={detail || '当前目标'}>
      🎯 {title}
      {prog ? <span className="proto-goal-prog">{prog}</span> : null}
    </span>
  );
}
