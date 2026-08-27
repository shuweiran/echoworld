/**
 * simChatConfig.ts — C-2 批次：2D 世界对话输出机制（打字机流式播放）集中配置
 *
 * 主人拍板方案（docs/前端问题调研-20260802.md 背景 + C-2 任务书）：
 *   1. 流式 = 前端打字机模拟：LLM 整句生成 → SSE/轮询推送 → 前端打字机播出；
 *      上一段播完（含句间停顿）才播下一段（播放队列严格串行）。
 *   2. Gal 面板只从本文件读取播放节奏；地图气泡直接出现，不参与打字机；
 *      每句话字数上限（前端渲染硬截断 + LLM prompt 轻提示双保险）。
 *
 * 所有可调参数集中于此，改这里即可全局调整（对齐 D-004「阈值勿 hardcode」纪律）。
 */

export const simChatConfig = {
  /** 玩家当前对话的 UI 打字速度；只影响显示，不阻塞世界模拟。 */
  typingCharsPerSec: 12,
  /** 打字机逐字间隔 ms。 */
  typingTickMs: Math.round(1000 / 12),
  /** 句间停顿 ms：上一段播完 → 停顿 → 下一段。 */
  interSentencePauseMs: 650,
  /** 暂停超时 ms：输入框有字 → 暂停播放；超时无操作 → 跳过当前句（主人拍板 60s） */
  pauseTimeoutMs: 60000,
  /** 句长上限（字）：渲染层硬截断（超长省略号）。建议 40-60 区间，取 60。
   *  与后端 TrackStrategy.MAX_SENTENCE_CHARS（角色发言 prompt 轻提示）对齐。 */
  maxSentenceChars: 60,
  /** 2D 世界角色气泡只作「谁正在说」提示，不承担完整阅读。 */
  maxBubbleChars: 20,
  /** 同一画面最多展示的气泡数；完整文本进入当前对话面板。 */
  maxVisibleBubbles: 3,
  /** 导演旁听仅加速本地回放，不加快后端 AI 思考/发言，方便观察连续对话。 */
  observerTypingCharsPerSec: 20,
  /** 旁听时两句之间的短停顿。 */
  observerInterSentencePauseMs: 400,
} as const;

/** 打字机参数的单行摘要（调试/演示用） */
export function simChatConfigSummary(): string {
  return `打字机 ${simChatConfig.typingCharsPerSec}字/秒｜句间停顿 ${simChatConfig.interSentencePauseMs / 1000}s｜暂停超时 ${simChatConfig.pauseTimeoutMs / 1000}s｜句长上限 ${simChatConfig.maxSentenceChars}字`;
}

/** 按当前观察身份选择本地回放节奏；不改变后端模拟时钟。 */
export function simChatPlaybackTiming(observing: boolean) {
  return observing
    ? { charsPerSec: simChatConfig.observerTypingCharsPerSec, tickMs: Math.round(1000 / simChatConfig.observerTypingCharsPerSec), pauseMs: simChatConfig.observerInterSentencePauseMs }
    : { charsPerSec: simChatConfig.typingCharsPerSec, tickMs: simChatConfig.typingTickMs, pauseMs: simChatConfig.interSentencePauseMs };
}

/**
 * 仅输出语言文字：过滤非语言噪音（emoji / 特殊符号 / 变体选择符 / 零宽字符 /
 * 替换符 U+FFFD），保留中文标点（，。！？；：、""''《》…——）与普通 ASCII 标点。
 * 另做双保险：剥离【情绪：xxx】残留（后端 TrackStrategy.processResults 已剥离，
 * 前端过滤兜底，防止 tag 上屏）。
 */
export function cleanWorldText(raw: unknown): string {
  if (raw == null) return '';
  return String(raw)
    .replace(
      // emoji 与杂项符号区段（保留中英文与常规标点）
      /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{2190}-\u{21FF}\u{FE0F}\u{FFFD}\u{200B}\u{200C}\u{200D}]/gu,
      '',
    )
    // 双保险：剥离【情绪：xxx】/ [情绪: xxx] 残留
    .replace(/[【\[]\s*情绪[^】\]]*[】\]]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 渲染层硬截断：超长文本截到 max-1 字 + 省略号（保证对话框装得下） */
export function truncateText(text: string, max: number): string {
  if (text.length <= max) return text;
  return text.slice(0, Math.max(0, max - 1)) + '…';
}
