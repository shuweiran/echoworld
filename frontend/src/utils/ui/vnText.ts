/**
 * vnText.ts — VN 演出文本拼装纯函数（ui-proto-v2，共享工具）
 *
 * 阶段 D（P-0817-E）自 components/ChatPage/actionUtils.ts 原样抽取（只搬代码 + 改 import，
 * 零行为变化；actionUtils 保留 re-export 兼容旧消费点）。
 * 消费点：ScriptInvestigationPanel（搜证 VN 弹层数据源）/ actionUtils re-export。
 *
 * 决策 U13（MVP 前端拼装，零新端点）：VN 面板文本由本函数前端拼装（线索 content）；
 * 阶段三 API-14（action_playback 后端模板）为可选升级点，接通时替换 lines 数据源即可。
 */
import type { ScriptClueLike } from './evidenceFilter';

/**
 * VN 演出文本前端拼装（决策 U13 MVP）：首行地点引导 + 逐条线索 content。
 * 后端阶段三 API-14（action_playback 模板）可选升级，代码注释标注接通点。
 */
export function buildVnLines(
  clues: ScriptClueLike[] | null | undefined,
  location?: string,
  fallback?: string,
): string[] {
  const clueList = Array.isArray(clues) ? clues : [];
  const lines: string[] = [];
  if (location) lines.push(`在「${location}」仔细搜索……`);
  let clueLines = 0;
  for (const c of clueList) {
    const text = String(c?.content ?? '').trim();
    if (text) {
      lines.push(text);
      clueLines++;
    }
  }
  // 无任何线索文本时（搜证落空/回看空态）追加 fallback 行（如后端 result 文案）
  if (clueLines === 0 && fallback) lines.push(fallback);
  return lines;
}
