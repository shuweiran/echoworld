/**
 * voteUtils.ts — 投票进度聚合纯函数（决策 U8/C13 口径）
 *
 * 后端口径（P-0816-G / 决策 U8）：`voted` 不含弃票（弃票写独立 abstainedVoters 集合）——
 * 所以「已投票 x/y」= voted + abstained（前端展示口径），与 goal HUD 的 vote 进度一致。
 * 纯函数可单测（无 React 依赖）。
 */
export interface VoteProgressData {
  ok?: boolean;
  phase?: string;
  total?: number;
  voted?: number;
  abstained?: number;
  pending?: string[];
  candidates?: Array<{ name: string; votes: number; point?: string }>;
  trustees?: string[];
}

/** 「已投票」加和口径：voted + abstained（弃票计入已表态，不参与票型统计）。 */
export function countVoted(p: VoteProgressData | null | undefined): number {
  if (!p) return 0;
  return (typeof p.voted === 'number' ? p.voted : 0) + (typeof p.abstained === 'number' ? p.abstained : 0);
}

/** 已投票百分比（0-100，total<=0 → 0）。 */
export function votedPercent(p: VoteProgressData | null | undefined): number {
  if (!p || typeof p.total !== 'number' || p.total <= 0) return 0;
  const done = countVoted(p);
  return Math.min(100, Math.round((done / p.total) * 100));
}

/**
 * 是否 VOTE 阶段 —— 非 VOTE 阶段后端只返回 {phase}（API-10 契约），
 * 此时无统计字段，前端隐藏统计区。
 */
export function isVotePhaseData(p: VoteProgressData | null | undefined): boolean {
  return !!p && p.phase === 'vote' && typeof p.total === 'number';
}
