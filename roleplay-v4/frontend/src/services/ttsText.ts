/**
 * ttsText.ts — TTS 朗读文本提取（P-0818-B）
 *
 * 局内 AI 消息常混有「动作/表情描述（…）」「情绪标注【…】」等非语句内容，
 * 直接朗读会把这些括号内容也念出来。本模块提供纯函数，把可朗读内容收敛为「语句」：
 *  ① 删除全角括号（…）、半角括号 (...)、全角方括号【…】、半角方括号 [...] 内的内容（含嵌套）；
 *  ② 连续空白/换行折叠为单个空格；
 *  ③ 收尾 trim。
 *
 * 纯函数、零依赖，便于 node 冒烟测试直接 import（Node 24 原生 TS）。
 */

const GROUP_PATTERNS: RegExp[] = [
  /（[^（）]*）/g,   // 全角圆括号
  /\([^()]*\)/g,     // 半角圆括号
  /【[^【】]*】/g,   // 全角方括号
  /\[[^\[\]]*\]/g,   // 半角方括号
];

/**
 * 提取可朗读语句：去掉括号/方括号内的动作、表情、情绪等非语句内容（含嵌套），
 * 折叠空白并 trim。整条消息只有括号内容时返回空串（调用方不合成，避免念动作描述）。
 */
export function extractSpeechText(text: string): string {
  let s = String(text ?? '');
  // 循环删除最内层括号组直至稳定（处理嵌套；上限 12 轮防病态输入）
  for (let i = 0; i < 12; i++) {
    let changed = false;
    for (const re of GROUP_PATTERNS) {
      const next = s.replace(re, '');
      if (next !== s) { s = next; changed = true; }
    }
    if (!changed) break;
  }
  // P-0818-B 兜底：清掉残余孤立括号字符（如 LLM 流式残段单发「（」），
  // 括号字符本身不是语句；成对内容已被上一循环剥离
  s = s.replace(/[（）()【】[\]]/g, '');
  return s.replace(/\s+/g, ' ').trim();
}
