/**
 * 讨论引擎静默占位（SpeechGate SILENCE_MARKER）。
 *
 * <p>后端 SpeechGate 对「低意愿且无规则触发」的成员以占位符「……（沉默）」入发言记录，
 * 保证发言记录/轮次结构完整（冷场检测亦以该标记识别全员静默）。前端统一以本模块识别，
 * 将静默占位渲染为静默样式（灰色/斜体）而非普通发言，避免「AI 说了个寂寞」的误读。
 *
 * <p>对齐 D-022 已知限制修复（P-0803-P）。
 */
export const SILENCE_MARKER = '……（沉默）';

/** 判断文本是否为静默占位（精确匹配或包含均可——容忍前后缀噪声）。 */
export function isSilenceText(text: unknown): boolean {
  return typeof text === 'string' && text.trim() === SILENCE_MARKER;
}

/** 静默发言渲染（无发言人强调，灰色斜体）。 */
export function SilenceTurn() {
  return <span style={{ color: 'var(--text-3, #5b6b8c)', fontStyle: 'italic', opacity: 0.8 }}>{SILENCE_MARKER}</span>;
}
