/**
 * galChoices.ts — P-0810-23-D1：主控说话候选话术（类 demo 选择支）
 *
 * 方案②（评估后采用）：前端基于场景/上下文生成通用候选——零 LLM 成本、即时显示、
 * 点击即发言（走 liveSay 同一链路）。后端 LLM 生成候选（方案①）成本高且延迟大，
 * 且一般模式会话本身由玩家自由主导，通用候选已能满足「给玩家抓手」的诉求；
 * 后端方案留待以后（见汇报理由）。
 *
 * 用户明确要求：候选**必须短**（每条 ≤40 字）、口语化。
 * 触发时机：一般模式玩家回合（liveMode 等待玩家输入时）由 GalChoiceBar 调用。
 */
const MAX_TOPIC = 10;

/** 从最近一条 AI 发言提取短话题（去句尾标点后取前 MAX_TOPIC 字；空则返回 ''）。 */
function extractTopic(lastAiText: string): string {
  const t = (lastAiText || '').replace(/\s+/g, '').trim();
  if (!t) return '';
  const cleaned = t.replace(/[。！？!?…~～\s]+$/, '');
  return cleaned.slice(0, MAX_TOPIC);
}

/**
 * 生成 4 条通用候选话术（每条 ≤40 字，口语化）：
 *   - 固定通用项：继续聊聊 / 换个话题吧 / 说说你自己
 *   - 上下文项：有最近 AI 发言 → 「追问刚才提到的「话题」…」；无 → 「刚才说的再展开讲讲」
 * @param lastAiText 最近一条 AI（非系统/非玩家）发言全文，用于提取追问话题
 */
export function buildLiveChoices(lastAiText?: string): string[] {
  const topic = lastAiText ? extractTopic(lastAiText) : '';
  const followUp = topic
    ? `追问刚才提到的「${topic}」…`
    : '刚才说的再展开讲讲';
  return ['继续聊聊', '换个话题吧', followUp, '说说你自己'];
}
