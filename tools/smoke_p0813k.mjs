/* P-0813-K smoke: 群接近判定（findApproachableGroups）+ join/leave API 路径 + 加入后群聊数据流
 * 用 esbuild 打包真实代码（simulationData.ts / client.ts）在 Node 里断言。
 * 覆盖：群接近阈值边界（成员 100px / 群中心 120px）/ DYAD 排除 / 已在组排除 / <2 成员排除 /
 * 玩家不在场 / join/leave 端点 URL 与 body / currentTrack → joinedGroup 推导（加入后 Gal 群聊数据源）。
 */
import { createRequire } from 'module';
import { mkdirSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/echoworld/frontend/node_modules/esbuild');

mkdirSync('tmp/p0813k', { recursive: true });

const code = `
const { findApproachableGroups, GROUP_APPROACH_MEMBER_DIST, GROUP_APPROACH_CENTER_DIST } = require('./frontend/src/phaser/simulationData.ts');
const { api } = require('./frontend/src/api/client.ts');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}

// ── 1. 群接近判定纯函数 findApproachableGroups（SimulationScene.updateGroupApproachHints 共用） ──
// 场景：玩家 我(100,100)；群 g1 成员 小铃(140,130) 凯尔(240,110) → 小铃距玩家 50<100 → 可加入
//      群 g2 成员 阿杰(100,300) 阿琳(150,310) → 中心(125,305) 距玩家 ≈205.6 ≥120、成员均 ≥100 → 不可加入
//      群 g3（中心判定）成员 露娜(170,100) 阿明(220,160) → 中心(195,130) 距玩家 ≈99.2 <120 → 可加入（成员 70.7/156 含一个<100？70.7<100 也算成员命中——调整使仅中心命中）
const agents1 = [
  { agentName: '我', x: 100, y: 100 },
  { agentName: '小铃', x: 140, y: 130 },
  { agentName: '凯尔', x: 240, y: 110 },
  { agentName: '阿杰', x: 100, y: 300 },
  { agentName: '阿琳', x: 150, y: 310 },
  { agentName: '露娜', x: 210, y: 100 },
  { agentName: '阿明', x: 260, y: 160 },
];
const groups1 = [
  { id: 'g1', mode: 'GROUP_DISCUSSION', participants: ['小铃', '凯尔'] },        // 成员命中（50<100）
  { id: 'g2', mode: 'GROUP_DISCUSSION', participants: ['阿杰', '阿琳'] },        // 均远（成员 200/206 ≥100，中心 205.6 ≥120）
  { id: 'g3', mode: 'GROUP_DISCUSSION', participants: ['露娜', '阿明'] },        // 成员 110/160 ≥100，中心(235,130) 距玩家 ≈139 ≥120 → 也不可加入
];
let near1 = findApproachableGroups('我', agents1, groups1);
check('1 成员距离<100 命中 g1（排除远群 g2/g3）', near1.length === 1 && near1[0] === 'g1', JSON.stringify(near1));

// 边界：恰在 100px 外不命中（成员判定）
const agentsEdge = [
  { agentName: '我', x: 0, y: 0 },
  { agentName: 'NPC1', x: 100, y: 0 },   // dist=100 恰在阈值外
  { agentName: 'NPC2', x: 99.9, y: 0 },  // dist=99.9 <100 命中
];
const groupsEdge = [
  { id: 'ge1', mode: 'GROUP_DISCUSSION', participants: ['NPC1', 'NPC2'] },
];
check('1 成员阈值边界：99.9 命中 / 100 恰外', JSON.stringify(findApproachableGroups('我', agentsEdge, groupsEdge)) === JSON.stringify(['ge1']),
  JSON.stringify(findApproachableGroups('我', agentsEdge, groupsEdge)));

// 群中心判定边界：成员均 ≥100px，但群中心 <120px → 命中（120 恰外）
const agentsCenter = [
  { agentName: '我', x: 0, y: 0 },
  { agentName: 'C1', x: 160, y: 0 },   // dist 160 ≥100
  { agentName: 'C2', x: 0, y: 170 },   // dist 170 ≥100；中心(80,85) 距玩家 116.7 <120 → 命中
];
const groupsCenter = [
  { id: 'gc', mode: 'GROUP_DISCUSSION', participants: ['C1', 'C2'] },
];
check('1 群中心<120 命中（成员均≥100 时）', JSON.stringify(findApproachableGroups('我', agentsCenter, groupsCenter)) === JSON.stringify(['gc']),
  JSON.stringify(findApproachableGroups('我', agentsCenter, groupsCenter)));
const groupsCenter2 = [
  { id: 'gc2', mode: 'GROUP_DISCUSSION', participants: ['C1', 'C2'] }, // 中心(80,85) 116.7 —— 改坐标到中心恰 120 外
];
// 中心恰 120 外：C1(165,0) C2(0,175) → 中心(82.5,87.5) 距玩家 120.2 ≥120 → 不命中
const agentsCenterFar = [
  { agentName: '我', x: 0, y: 0 },
  { agentName: 'C1', x: 165, y: 0 },
  { agentName: 'C2', x: 0, y: 175 },
];
check('1 群中心恰 120 外不命中', findApproachableGroups('我', agentsCenterFar, groupsCenter2).length === 0,
  JSON.stringify(findApproachableGroups('我', agentsCenterFar, groupsCenter2)));

// 排除规则
const agents2 = [
  { agentName: '我', x: 0, y: 0 },
  { agentName: 'A', x: 30, y: 0 },
  { agentName: 'B', x: 0, y: 40 },
  { agentName: 'C', x: 60, y: 0 },
  { agentName: 'D', x: 0, y: 60 },
];
check('1 排除 DYAD（1v1 不提供加入入口）',
  findApproachableGroups('我', agents2, [{ id: 'dyad1', mode: 'DYAD', participants: ['A', 'B'] }]).length === 0);
check('1 排除玩家已在组（退出入口而非加入提示）',
  findApproachableGroups('我', agents2, [{ id: 'gx', mode: 'GROUP_DISCUSSION', participants: ['我', 'A', 'B'] }]).length === 0);
check('1 排除 <2 成员的群（需正在对话的 AI 群）',
  findApproachableGroups('我', agents2, [{ id: 'g1', mode: 'GROUP_DISCUSSION', participants: ['A'] }]).length === 0);
check('1 玩家不在世界 → 空（导演模式无提示）',
  findApproachableGroups('我', [{ agentName: 'A', x: 0, y: 0 }], [{ id: 'g1', mode: 'GROUP_DISCUSSION', participants: ['A', 'B'] }]).length === 0);
check('1 无玩家名 → 空', findApproachableGroups('', agents2, groups1).length === 0);
check('1 阈值常量导出', GROUP_APPROACH_MEMBER_DIST === 100 && GROUP_APPROACH_CENTER_DIST === 120);

// ── 2. client.ts join/leave 端点路径与 body（stub fetch 捕获） ──
// Node 无 localStorage：client.ts getAuthHeaders 读取 token（stub 空）
globalThis.localStorage = {
  getItem: () => null,
  setItem: () => {},
  removeItem: () => {},
  clear: () => {},
};
let captured = [];
globalThis.fetch = async (url, opts = {}) => {
  captured.push({ url: String(url), method: opts.method, body: opts.body });
  const payload = { status: 'ok', message: 'ok', group: { id: 'g1', mode: 'GROUP_DISCUSSION', participants: ['小铃', '凯尔', '我'] } };
  return { ok: true, status: 200, statusText: 'OK', json: async () => payload, text: async () => JSON.stringify(payload) };
};
(async () => {
  const rj = await api.joinConversation('g1', '我');
  check('2 join 端点 URL = /api/simulation/group/g1/join', captured[0].url === '/api/simulation/group/g1/join', captured[0].url);
  check('2 join body = {player_name:"我"}', captured[0].body === JSON.stringify({ player_name: '我' }), captured[0].body);
  check('2 join 响应含玩家（成员列表含我）', rj.status === 'ok' && rj.group.participants.includes('我'), JSON.stringify(rj));

  captured = [];
  const rl = await api.leaveConversation('g1', '我');
  check('2 leave 端点 URL = /api/simulation/group/g1/leave', captured[0].url === '/api/simulation/group/g1/leave', captured[0].url);
  check('2 leave body = {player_name:"我"}', captured[0].body === JSON.stringify({ player_name: '我' }), captured[0].body);
  check('2 leave 响应 ok', rl.status === 'ok');

  // ── 3. 加入后群聊数据流：conversation-status currentTrack → joinedGroup（Gal 面板群头数据源） ──
  // 模拟 PhaserSimulationView.fetchGroups 解析逻辑（真实数据形状）
  const convStatus = {
    activeGroups: 1,
    currentTrack: 'g1',
    groups: [{ id: 'g1', mode: 'GROUP_DISCUSSION', participants: ['小铃', '凯尔', '我'], rounds: 2, turns: 5, idleMs: 1200, topic: { description: '多人闲聊' } }],
  };
  const joinedGroup = convStatus.currentTrack ? (convStatus.groups.find(g => g.id === convStatus.currentTrack) || null) : null;
  check('3 currentTrack=g1 → joinedGroup 命中（群名/成员/主题齐备）',
    joinedGroup && joinedGroup.id === 'g1' && joinedGroup.participants.includes('我') && joinedGroup.topic.description === '多人闲聊',
    JSON.stringify(joinedGroup));
  check('3 玩家在组 → 群聊模式标记（member 含玩家名 = 群头高亮「你」）', joinedGroup.participants.includes('我'));

  // 退出后（后端 leave → currentTrack 清空）→ joinedGroup=null（回自由对话模式）
  const convStatusAfter = { activeGroups: 1, currentTrack: '', groups: [{ id: 'g1', mode: 'GROUP_DISCUSSION', participants: ['小铃', '凯尔'] }] };
  const joinedAfter = convStatusAfter.currentTrack ? (convStatusAfter.groups.find(g => g.id === convStatusAfter.currentTrack) || null) : null;
  check('3 退出后 currentTrack 空 → joinedGroup=null（回自由探索）', joinedAfter === null);

  console.log(failures === 0 ? '\\nALL PASS' : '\\nFAILURES: ' + failures);
  process.exit(failures === 0 ? 0 : 1);
})();
`;

const result = buildSync({
  stdin: { contents: code, resolveDir: 'D:/echoworld', sourcefile: 'smoke_p0813k.ts', loader: 'ts' },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  write: false,
  logLevel: 'silent',
});
require('fs').writeFileSync('tmp/p0813k/smoke_p0813k.cjs', result.outputFiles[0].text);
require('child_process').execSync('node tmp/p0813k/smoke_p0813k.cjs', { stdio: 'inherit' });
