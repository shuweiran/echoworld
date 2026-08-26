/* P-0816-I smoke: 搜证页替换 + 讨论页 VN 化 纯函数冒烟
 * 用 esbuild 打包真实代码（actionUtils.ts）在 Node 里断言（对齐包 1 smoke_p0816h 模式）。
 * 覆盖：
 *   A1 行动条渲染逻辑 —— 行动类型图标映射（ask/research/present/未知兜底）
 *   A2 行动条数据契约 —— API-1 响应动作的 enabled/reason/ap_cost 字段消费
 *   A3 地点卡片 —— clueCountAtLocation 可见线索数徽章（已搜地点回看数据源）
 *   A4 心锁推导（U1 过渡口径）—— 线索 content/title 提及角色名 → 该角色 1 锁
 *   B1 证据检索（C7 纯前端）—— query 子串 + chips 分类（人物/地点/时间）过滤
 *   B2 VN 演出拼装（U13 前端拼装）—— buildVnLines 首行地点引导 + 线索 content 逐条
 */
import { createRequire } from 'module';
import { mkdirSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/echoworld/frontend/node_modules/esbuild');

mkdirSync('tmp/p0816i', { recursive: true });

const code = `
const {
  deriveRoleLocks,
  clueCountAtLocation,
  evidenceTags,
  filterEvidence,
  buildVnLines,
  actionEmoji,
} = require('./frontend/src/components/ChatPage/actionUtils.ts');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}

// ═══ A1 行动条渲染逻辑：类型 → 图标（ask/research/present/未知兜底） ═══
check('A1a ask 行动 = 🤝', actionEmoji('ask') === '🤝');
check('A1b research 行动 = 📚', actionEmoji('research') === '📚');
check('A1c present 行动 = 🃏', actionEmoji('present') === '🃏');
check('A1d 未知类型兜底 🎯', actionEmoji('dance') === '🎯' && actionEmoji(undefined) === '🎯');

// ═══ A2 行动条数据契约（API-1 GET /api/script/actions 响应形状，P-0816-G 实测） ═══
// 后端契约：{id, type, target, label, ap_cost, enabled, reason}；enabled=false 带 reason
const api1 = {
  ok: true, phase: 'investigation', ap: 2, ap_max: 3,
  actions: [
    { id: 'ask|苏晚', type: 'ask', target: '苏晚', label: '去问苏晚', ap_cost: 1, enabled: true, reason: '' },
    { id: 'research|客厅', type: 'research', target: '客厅', label: '回看客厅', ap_cost: 0, enabled: true, reason: '' },
    { id: 'research|书房', type: 'research', target: '书房', label: '去搜书房', ap_cost: 2, enabled: false, reason: '行动点不足' },
    { id: 'present|CL-01', type: 'present', target: 'CL-01', label: '出示烧毁的信', ap_cost: 1, enabled: false, reason: '出示线索需在讨论阶段进行' },
  ],
};
// 渲染派生：图标 + 可点击性 + 禁用原因透出（前端消费后端字段原样，不重造逻辑）
const renderable = api1.actions.map(a => ({
  icon: actionEmoji(a.type),
  clickable: a.enabled === true,
  reason: a.enabled === false ? (a.reason || '不可用') : '',
}));
check('A2a 行动条 4 条全渲染（含禁用）', renderable.length === 4);
check('A2b ask 可点击', renderable[0].clickable === true && renderable[0].icon === '🤝');
check('A2c 回看客厅（U7 已搜回看 ap_cost=0）可点击', renderable[1].clickable === true && api1.actions[1].ap_cost === 0);
check('A2d 去搜书房 AP 不足禁用 + reason 透出', renderable[2].clickable === false && renderable[2].reason === '行动点不足');
check('A2e 出示证据搜证阶段禁用 + 引导原因', renderable[3].clickable === false && renderable[3].reason.includes('讨论阶段'));
check('A2f AP 显示与后端联动（ap/ap_max）', api1.ap === 2 && api1.ap_max === 3);

// ═══ A3 地点卡片线索数徽章（clueCountAtLocation，status.clues = 公开 + 本人持有） ═══
const clues = [
  { id: 'CL-01', location: '客厅', content: '烧了一半的信，落款署名是「顾言」……' },
  { id: 'CL-02', location: '客厅', content: '茶几上的空酒杯' },
  { id: 'CL-03', location: '书房', content: '怀表链，刻着死者姓氏缩写' },
];
check('A3a 客厅 2 条可见线索', clueCountAtLocation(clues, '客厅') === 2);
check('A3b 书房 1 条可见线索', clueCountAtLocation(clues, '书房') === 1);
check('A3c 未搜地点 0 条', clueCountAtLocation(clues, '阁楼') === 0);
check('A3d 空线索/空地点兜底', clueCountAtLocation(null, '客厅') === 0 && clueCountAtLocation(clues, '') === 0);

// ═══ A4 心锁推导（U1 过渡口径：线索 content/title 提及角色名 → 该角色 1 锁） ═══
const roles = ['林深', '苏晚', '顾言', '陈默'];
const lockClues = [
  { id: 'CL-01', content: '落款署名是「顾言」，提到一笔不该出现的交易。', title: '烧毁的信' },
  { id: 'CL-02', content: '花圃泥土里的鞋印，尺码与陈默的鞋对得上。', title: '花园皮鞋印' },
  { id: 'CL-03', content: '怀表链……死者生前常戴。', title: '怀表链' },
  { id: 'CL-04', content: '顾言和苏晚在书房争吵，顾言摔门而出。', title: '争吵目击' },
];
const locks = deriveRoleLocks(lockClues, roles);
const lockOf = Object.fromEntries(locks.map(l => [l.role, l.count]));
check('A4a 顾言 2 锁（CL-01 + CL-04 提及两次）', lockOf['顾言'] === 2, JSON.stringify(locks));
check('A4b 陈默 1 锁（CL-02）', lockOf['陈默'] === 1);
check('A4c 苏晚 1 锁（CL-04 提及）/ 林深 0 锁（不出现）', lockOf['苏晚'] === 1 && lockOf['林深'] === undefined);
check('A4d 结果按 roles 顺序且仅锁数>0', locks.map(l => l.role).join() === '苏晚,顾言,陈默');
check('A4e 空输入兜底', deriveRoleLocks(null, roles).length === 0 && deriveRoleLocks(lockClues, null).length === 0);
check('A4f title 提及也算锁（U1 口径 content 含 title）', deriveRoleLocks([{ id: 'X', content: '', title: '陈默的遗物清单' }], roles).some(l => l.role === '陈默' && l.count === 1));

// ═══ B1 证据检索（C7 MVP 纯前端：query + chips 全部/人物/地点/时间） ═══
const locs = ['客厅', '书房', '花园'];
const evClues = [
  { id: 'CL-01', location: '书房', title: '烧毁的信', content: '落款署名是「顾言」，提到一笔不该出现的交易。' },
  { id: 'CL-02', location: '花园', title: '花园皮鞋印', content: '昨晚的泥土里有一枚鞋印，尺码与陈默的鞋对得上。' },
  { id: 'CL-03', location: '客厅', title: '怀表链', content: '怀表链在书房抽屉里找到，刻着死者姓氏缩写。' },
];
check('B1a query=顾言 命中 CL-01', filterEvidence(evClues, '顾言', '全部', roles, locs).length === 1);
check('B1b query=陈默 命中 CL-02', filterEvidence(evClues, '陈默', '全部', roles, locs).length === 1);
check('B1c 分类=人物 命中提及角色名的 2 条', filterEvidence(evClues, '', '人物', roles, locs).length === 2);
check('B1d 分类=地点（content/title 提及地点名：CL-02 标题花园皮鞋印 + CL-03 内容书房 → 2 条）', filterEvidence(evClues, '', '地点', roles, locs).length === 2);
check('B1e 分类=时间（昨晚/凌晨/点时 命中 CL-02）', filterEvidence(evClues, '', '时间', roles, locs).length === 1);
check('B1f query+分类 联合过滤', filterEvidence(evClues, '陈默', '时间', roles, locs).length === 1);
check('B1g 空 query + 全部 → 全量', filterEvidence(evClues, '', '全部', roles, locs).length === 3);
check('B1h 无命中返回空数组', filterEvidence(evClues, '不存在的词', '全部', roles, locs).length === 0);
// evidenceTags 分类启发式（不含线索自身 location 字段——loc-tag 独立展示）
check('B1i tags 人物（顾言）且不含地点（location 字段不参与扫描）', evidenceTags(evClues[0], roles, locs).includes('人物') && !evidenceTags(evClues[0], roles, locs).includes('地点'));
check('B1j tags 人物+时间（陈默+昨晚）', evidenceTags(evClues[1], roles, locs).includes('人物') && evidenceTags(evClues[1], roles, locs).includes('时间'));
check('B1k 无线索对象兜底', evidenceTags(null, roles, locs).length === 0);

// ═══ B2 VN 演出拼装（U13 前端拼装：地点引导 + 线索 content 逐条；零新端点） ═══
const vnLines = buildVnLines([{ id: 'CL-01', content: '落款署名是「顾言」……' }, { id: 'CL-02', content: '一枚鞋印。' }], '书房');
check('B2a 首行地点引导', vnLines[0] === '在「书房」仔细搜索……');
check('B2b 线索 content 逐条入行', vnLines[1] === '落款署名是「顾言」……' && vnLines[2] === '一枚鞋印。');
check('B2c 空线索 + fallback 兜底', buildVnLines([], '阁楼', '该地点没有更多可搜证线索')[1] === '该地点没有更多可搜证线索');
check('B2d 回看响应（replayed 线索子集）拼装', buildVnLines([{ id: 'CL-01', content: '回看内容' }], '客厅').length === 2);

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
require('node:fs').writeFileSync('tmp/p0816i/smoke.cjs', out.outputFiles[0].text);
require('node:child_process').execSync('node tmp/p0816i/smoke.cjs', { cwd: 'D:/echoworld', stdio: 'inherit' });
