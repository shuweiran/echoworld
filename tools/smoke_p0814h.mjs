/* smoke_p0814h.mjs — P-0814-H 热点/搜证点交互系统冒烟（esbuild 打包真实代码）
 * 验证三个核心纯函数契约（与后端 MapInteractService 逐字对齐）：
 *   1. decorInRange 靠近判定 —— Chebyshev |dx|≤r 且 |dy|≤r，r=decor.radius||1（后端 DEFAULT_RADIUS）
 *   2. decorStateKey 实例状态键 —— "mapId|decorId"（对齐后端 decorStates 键）
 *   3. formatInteractResult 交互结果映射 —— 后端响应 → 前端展示结构（ok/text/dialog/clues/processed）
 * 运行：node tools/smoke_p0814h.mjs（0=ALL PASS）
 */
import { createRequire } from 'module';
import { mkdirSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/echoworld/frontend/node_modules/esbuild');

mkdirSync('tmp/p0814h', { recursive: true });

const code = `
const { decorInRange, decorStateKey, formatInteractResult } = require('./frontend/src/phaser/interactData.ts');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}

// ══ 1. 靠近判定 decorInRange（Chebyshev，与后端半径语义同源） ══
const chest = { id: 'chest_1', type: 'chest', tile: [4, 4] };
check('1 默认半径 1：轴向 1 格（3,4）可交互', decorInRange(chest, 3, 4) === true);
check('1 默认半径 1：斜角 1 格（5,5）可交互', decorInRange(chest, 5, 5) === true);
check('1 默认半径 1：轴向 2 格（6,4）不可交互', decorInRange(chest, 6, 4) === false);
check('1 默认半径 1：斜角 2,2（6,6）不可交互', decorInRange(chest, 6, 6) === false);
check('1 默认半径 1：同格（4,4）可交互', decorInRange(chest, 4, 4) === true);

const lamp = { id: 'lamp_1', type: 'lamp', tile: [6, 3], radius: 2 };
check('1 radius 覆盖：radius=2 时 2 格（8,3）可交互', decorInRange(lamp, 8, 3) === true);
check('1 radius 覆盖：radius=2 时 3 格（9,3）不可交互', decorInRange(lamp, 9, 3) === false);
check('1 radius 非法（0）→ 回退默认 1', decorInRange({ id: 'x', type: 'y', tile: [2, 2], radius: 0 }, 4, 2) === false);
check('1 tile 缺失 → 不可交互（防御）', decorInRange({ id: 'x', type: 'y', tile: undefined }, 2, 2) === false);
check('1 默认半径参数可覆盖（defaultRadius=2）', decorInRange(chest, 6, 4, 2) === true);

// ══ 2. 实例状态键 decorStateKey ══
check('2 状态键 = "mapId|decorId"', decorStateKey('map_1', 'chest_1') === 'map_1|chest_1');
check('2 多图注册表键隔离', decorStateKey('map_2', 'chest_1') !== decorStateKey('map_1', 'chest_1'));

// ══ 3. 交互结果映射 formatInteractResult（后端 POST /api/script/interact 响应 → 前端展示） ══
// 3a. happy path：dialog + items + processed（once 箱子）
const r1 = formatInteractResult({
  ok: true, handled: true, processed: true,
  dialog: ['箱子打开了，里面有一片碎玻璃！'],
  items: [{ id: 'c1', title: '碎玻璃' }],
  result: '交互成功：箱子打开了，里面有一片碎玻璃！；获得线索 1 条（一次性，已处理）',
});
check('3a happy path：ok=true', r1.ok === true);
check('3a dialog 文本透传', r1.dialog.length === 1 && r1.dialog[0].includes('碎玻璃'));
check('3a 线索 items 映射（id+title）', r1.clues.length === 1 && r1.clues[0].id === 'c1' && r1.clues[0].title === '碎玻璃');
check('3a processed=true（前端灰显数据源）', r1.processed === true);
check('3a 主文本取 result', r1.text.includes('一次性，已处理'));

// 3b. 已处理幂等：重复交互（无 dialog，仅 processed + result）
const r2 = formatInteractResult({ ok: true, handled: false, processed: true, result: '该处已处理过（chest_1）' });
check('3b 已处理幂等：ok=true 且 processed=true', r2.ok === true && r2.processed === true);
check('3b 无 dialog 不崩溃', r2.dialog.length === 0 && r2.clues.length === 0);
check('3b 主文本 = 已处理过', r2.text.includes('已处理过'));

// 3c. error 路径：超半径够不着
const r3 = formatInteractResult({ ok: false, handled: false, error: '够不着：距离超过交互半径 1 格（距目标 5 > 1）' });
check('3c 超半径：ok=false', r3.ok === false);
check('3c error 文本透传', r3.text.includes('够不着'));

// 3d. conditions 门：requireFlag 不满足 → failDialog（blocked）
const r4 = formatInteractResult({ ok: true, handled: false, blocked: true, require_flag: 'key_room', dialog: ['门锁着…'], result: '门锁着…' });
check('3d 条件拦截：ok=true（请求成功但被拦）', r4.ok === true);
check('3d failDialog 文本', r4.dialog.length === 1 && r4.dialog[0] === '门锁着…');
check('3d 未处理（不灰显）', r4.processed === false);

// 3e. 环境占位：无 decor 无 tileProps.action
const r5 = formatInteractResult({ ok: true, handled: false, result: '这里没有什么特别的。' });
check('3e 环境占位：ok=true 且文本=占位文案', r5.ok === true && r5.text === '这里没有什么特别的。');

// 3f. 空/畸形响应 → 零异常兜底
const r6 = formatInteractResult(null);
check('3f null 响应兜底：ok=false 有文本', r6.ok === false && r6.text.length > 0 && r6.clues.length === 0);
const r7 = formatInteractResult({ handled: true });
check('3f 最小响应兜底：ok=true 主文本=交互完成', r7.ok === true && r7.text === '交互完成');

// ══ 4. 与后端契约对齐：响应键兼容（decor 实体 / tileProps 分发） ══
const r8 = formatInteractResult({ ok: true, handled: true, dialog: ['墙上的画框很沉。'], result: '交互成功：墙上的画框很沉。' });
check('4 tileProps dialog 分发：文本映射', r8.dialog.length === 1 && r8.dialog[0] === '墙上的画框很沉。');
const r9 = formatInteractResult({ ok: true, handled: true, items: [{ id: 'c2', title: '密信' }], result: '交互成功；获得线索 1 条' });
check('4 tileProps addItem 分发：items 映射', r9.clues.length === 1 && r9.clues[0].id === 'c2');

console.log(failures === 0 ? 'SMOKE RESULT: ALL PASS' : 'SMOKE RESULT: ' + failures + ' FAIL');
process.exit(failures > 0 ? 1 : 0);
`;

buildSync({
  stdin: { contents: code, resolveDir: 'D:/echoworld', sourcefile: 'smoke_p0814h.ts' },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  outfile: 'tmp/p0814h/smoke.cjs',
  logLevel: 'silent',
});

require('D:/echoworld/tmp/p0814h/smoke.cjs');
