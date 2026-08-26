/* P-0815-H smoke: 群聊面板按群过滤（方案 A）—— shouldShowWorldMsg 纯函数 + 入队过滤场景
 * 用 esbuild 打包真实代码（simGroupFilter.ts）在 Node 里断言。
 * 覆盖：自由对话模式全量放行 / 群聊模式仅当前群消息 / 无群归属消息在群聊模式不放行 /
 * 群切换后仅新群消息放行 / 精确匹配（非子串）/ 空串兜底。
 */
import { createRequire } from 'module';
import { mkdirSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/echoworld/frontend/node_modules/esbuild');

mkdirSync('tmp/p0815h', { recursive: true });

const code = `
const { shouldShowWorldMsg } = require('./frontend/src/phaser/simGroupFilter.ts');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}

// ── 1. 自由对话模式（currentGroupId 空）：全量世界消息放行（2D 氛围保留，行为不变） ──
check('1a 自由模式：带群消息放行', shouldShowWorldMsg('沈墨+苏浅浅', undefined) === true);
check('1b 自由模式：无群归属消息放行', shouldShowWorldMsg(undefined, undefined) === true);
check('1c 自由模式：空串兜底视为未入群', shouldShowWorldMsg('白露+苏浅浅', '') === true);

// ── 2. 群聊模式（currentGroupId = 玩家当前群）：只放行当前群消息 ──
const G = 'me+沈墨';
check('2a 群聊：当前群消息放行', shouldShowWorldMsg(G, G) === true);
check('2b 群聊：其他群消息过滤', shouldShowWorldMsg('沈墨+苏浅浅', G) === false);
check('2c 群聊：其他群消息过滤', shouldShowWorldMsg('白露+苏浅浅', G) === false);
check('2d 群聊：无群归属消息不放行（防混入）', shouldShowWorldMsg(undefined, G) === false);

// ── 3. 精确匹配（非子串包含）：群 id 必须完全相等 ──
check('3a 前缀相似但不同群：不放行', shouldShowWorldMsg('me+沈墨的邻居', 'me+沈墨') === false);
check('3b 同名前缀不同群：不放行', shouldShowWorldMsg('沈墨+苏浅浅', '苏浅浅+沈墨') === false);

// ── 4. 入队过滤场景复刻（SimGalChatPanel 入队 effect 的过滤判定循环） ──
// 世界 recentConversations 拍平后可能包含 3 个组 4 个角色（主人贴文场景）：
//   沈墨+苏浅浅（苏浅浅/沈墨）、白露+苏浅浅（苏浅浅/白露）、me+沈墨（沈墨/me）
const worldMsgs = [
  { id: 'w-1', who: '苏浅浅', text: '你倒是来得巧…', group: '沈墨+苏浅浅' },
  { id: 'w-2', who: '沈墨', text: '浅浅，好久不见…', group: '沈墨+苏浅浅' },
  { id: 'w-3', who: '苏浅浅', text: '她倒是沉得住气…', group: '白露+苏浅浅' },
  { id: 'w-4', who: '白露', text: '天色不早了…', group: '白露+苏浅浅' },
  { id: 'w-5', who: '沈墨', text: '往东走三百米…', group: 'me+沈墨' },
  { id: 'w-6', who: 'me', text: '好，我记住了。', group: 'me+沈墨' },
];
function filterFor(wms, groupId) {
  return wms.filter(m => shouldShowWorldMsg(m.group, groupId));
}
// 4a 玩家在 me+沈墨 群：只留该群 2 条（沈墨/me），其他组 4 条全部过滤
let onlyMyGroup = filterFor(worldMsgs, 'me+沈墨');
check('4a 群聊（me+沈墨）：仅当前群 2 条入队', onlyMyGroup.length === 2 && onlyMyGroup.every(m => m.group === 'me+沈墨'),
  JSON.stringify(onlyMyGroup.map(m => m.who)));
// 4b 群头成员一致性：入队消息角色 ⊆ 群成员（所见即所得：群头 2 人 = 消息 2 人）
const members = new Set(['me', '沈墨']);
check('4b 群聊：入队角色全部是群成员', onlyMyGroup.every(m => members.has(m.who)),
  JSON.stringify(onlyMyGroup.map(m => m.who)));
// 4c 自由对话模式：3 组 6 条全量（世界氛围保留）
check('4c 自由模式：全量 6 条入队', filterFor(worldMsgs, undefined).length === 6);
// 4d 群切换（加入 白露+苏浅浅 群）：只留该群消息
let afterSwitch = filterFor(worldMsgs, '白露+苏浅浅');
check('4d 群切换：仅新群 2 条（苏浅浅/白露）', afterSwitch.length === 2 && afterSwitch.every(m => m.group === '白露+苏浅浅'),
  JSON.stringify(afterSwitch.map(m => m.who)));
// 4e 无群归属消息在群聊模式被过滤、自由模式放行（后端全条目带 group，此例为防御性断言）
check('4e 无归属消息：群聊不放行/自由放行',
  filterFor([{ id: 'w-x', who: '路人', text: 'hi', group: undefined }], 'me+沈墨').length === 0 &&
  filterFor([{ id: 'w-x', who: '路人', text: 'hi', group: undefined }], undefined).length === 1);

console.log(failures === 0 ? '\\nALL PASS' : '\\nFAILURES=' + failures);
process.exit(failures === 0 ? 0 : 1);
`;

const out = buildSync({
  stdin: { contents: code, resolveDir: 'D:/echoworld', loader: 'js' },
  bundle: true,
  write: false,
  format: 'cjs',
  platform: 'node',
});
require('node:fs').writeFileSync('tmp/p0815h/smoke.cjs', out.outputFiles[0].text);
require('node:child_process').execSync('node tmp/p0815h/smoke.cjs', { cwd: 'D:/echoworld', stdio: 'inherit' });
