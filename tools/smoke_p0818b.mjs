/* smoke_p0818b.mjs — P-0818-B extractSpeechText 纯函数冒烟（Node 24 直接 import TS 源码）
 * 运行：node tools/smoke_p0818b.mjs（工作目录 D:\echoworld）
 */
import { extractSpeechText } from '../frontend/src/services/ttsText.ts';

let pass = 0, fail = 0;
const eq = (n, actual, expected) => {
  const ok = actual === expected;
  if (ok) { pass++; console.log('PASS', n, JSON.stringify(actual)); }
  else { fail++; console.log('FAIL', n, 'expected', JSON.stringify(expected), 'actual', JSON.stringify(actual)); }
};

const cases = [
  ['前括号动作', '（微微侧头看向沈墨，嘴角带着若有若无的笑意）沈公子找我，想必不只是为了赏花吧？', '沈公子找我，想必不只是为了赏花吧？'],
  ['后括号动作', '嗯，你说得对。有些事，过去了就让它过去吧。', '嗯，你说得对。有些事，过去了就让它过去吧。'],
  ['情绪标注', '我没事的，谢谢关心。【情绪：平静】', '我没事的，谢谢关心。'],
  ['前后括号+情绪', '（抬眼看向对方）你说得对。（轻轻点头）【情绪：平静】', '你说得对。'],
  ['嵌套全角括号', '（她低下头（似乎在想什么）然后开口）你好呀。', '你好呀。'],
  ['嵌套半角括号', '(she paused (thinking) then) Hello there.', 'Hello there.'],
  ['半角括号', 'I think (maybe) it is fine.', 'I think it is fine.'],
  ['半角方括号', 'ready [system note] now', 'ready now'],
  ['无括号原样', '今天天气真好，我们去散步吧。', '今天天气真好，我们去散步吧。'],
  ['纯括号消息返回空', '（只是站在原地，沉默不语）', ''],
  ['换行折叠', '第一句。\n\n第二句。', '第一句。 第二句。'],
  ['多余空白收尾', '  你好 ，  世界。  ', '你好 ， 世界。'],
  ['null/undefined 兜底', null, ''],
  ['孤立左括号残段', '（', ''],
  ['孤立右括号残段', '）', ''],
  ['未闭合动作括号', '（她轻轻叹了口气', '她轻轻叹了口气'],
  ['多个孤立括号', '（（（', ''],
];

for (const [n, input, expected] of cases) {
  eq(n, extractSpeechText(input), expected);
}

console.log(`==== RESULT: ${pass} pass / ${fail} fail ====`);
process.exit(fail > 0 ? 1 : 0);
