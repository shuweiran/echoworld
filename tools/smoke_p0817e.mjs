/* P-0817-E smoke: 阶段D 共享组件抽取 —— 纯函数复用冒烟（utils/ui/evidenceFilter + vnText 新路径）
 * 用 esbuild 打包共享层真实代码（新路径），在 Node 中断言（对齐 smoke_p0816h/i 模式）。
 * 覆盖：
 *   A1 evidenceTags 分类 chips（人物/地点/时间）——新路径 utils/ui/evidenceFilter.ts
 *   A2 filterEvidence query 子串 + 分类过滤——新路径
 *   A3 buildVnLines 首行地点引导 + 线索 content 逐条 + fallback——新路径 utils/ui/vnText.ts
 *   A4 actionUtils re-export 兼容（旧消费点路径仍可取到同一实现）
 *   A5 GoalBadge.progressText 进度文案（共享组件纯函数）
 */
import { createRequire } from 'module';
import { mkdirSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/echoworld/frontend/node_modules/esbuild');

mkdirSync('tmp/p0817e', { recursive: true });

const code = `
const { evidenceTags, filterEvidence } = require('./frontend/src/utils/ui/evidenceFilter.ts');
const { buildVnLines } = require('./frontend/src/utils/ui/vnText.ts');
const { evidenceTags: et2, filterEvidence: fe2, buildVnLines: bv2 } = require('./frontend/src/components/ChatPage/actionUtils.ts');
const { progressText } = require('./frontend/src/components/ui/GoalBadge.tsx');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}

const roles = ['林深', '苏晚'];
const locations = ['书房', '客厅'];

// A1 证据检索分类 chips（新路径）
const cluePerson = { id: 'CL-01', title: '烧毁的信', content: '林深昨晚去过书房' }; // 人物+地点+时间（昨晚=时间词）
const clueLoc = { id: 'CL-04', title: '台灯', content: '书房里有一盏台灯' }; // 仅地点
const clueTime = { id: 'CL-02', title: '怀表', content: '凌晨三点钟声响起' };
const cluePlain = { id: 'CL-03', title: '钥匙', content: '一把铜钥匙' };
check('A1a 人物+地点+时间三命中', JSON.stringify(evidenceTags(cluePerson, roles, locations)) === JSON.stringify(['人物', '地点', '时间']));
check('A1b 时间 chip 命中', JSON.stringify(evidenceTags(clueTime, roles, locations)) === JSON.stringify(['时间']));
check('A1c 无命中空数组', JSON.stringify(evidenceTags(cluePlain, roles, locations)) === JSON.stringify([]));
check('A1d null 线索安全', JSON.stringify(evidenceTags(null, roles, locations)) === JSON.stringify([]));
check('A1e 仅地点命中', JSON.stringify(evidenceTags(clueLoc, roles, locations)) === JSON.stringify(['地点']));

// A2 filterEvidence（新路径）
const clues = [cluePerson, clueTime, clueLoc, cluePlain];
check('A2a query 子串命中', filterEvidence(clues, '怀表', '全部', roles, locations).length === 1);
check('A2b 人物分类过滤', filterEvidence(clues, '', '人物', roles, locations).length === 1);
check('A2c 时间分类过滤', filterEvidence(clues, '', '时间', roles, locations).length === 2); // 林深昨晚（时间词）+ 怀表凌晨
check('A2d 空 query + 全部', filterEvidence(clues, '', '全部', roles, locations).length === 4);
check('A2e 地点分类过滤', filterEvidence(clues, '', '地点', roles, locations).length === 2);

// A3 buildVnLines（新路径）
const vn = buildVnLines([cluePerson], '书房', '此处无线索');
check('A3a 首行地点引导', vn[0] === '在「书房」仔细搜索……');
check('A3b 线索逐条', vn[1] === '林深昨晚去过书房');
check('A3c 空态 fallback', buildVnLines([], '客厅', '此处无线索')[1] === '此处无线索');
check('A3d 无 location 无首行', buildVnLines([cluePerson])[0] === '林深昨晚去过书房');

// A4 actionUtils re-export 兼容（旧消费点路径同一实现）
check('A4a evidenceTags 经 actionUtils 同实现', et2(cluePerson, roles, locations)[0] === '人物');
check('A4b filterEvidence 经 actionUtils 同实现', fe2(clues, '钥匙', '全部', roles, locations).length === 1);
check('A4c buildVnLines 经 actionUtils 同实现', bv2([clueTime], '客厅')[0] === '在「客厅」仔细搜索……');

// A5 GoalBadge.progressText（共享组件纯函数）
check('A5a 搜证进度 2/6', progressText({ goal: { progress: { searched: 2, total: 6 } } }) === '2/6');
check('A5b 投票进度 3/5', progressText({ goal: { progress: { voted: 3, total: 5 } } }) === '3/5');
check('A5c 无 progress 空串', progressText({ goal: { title: 'x' } }) === '');

if (failures > 0) { console.log('SMOKE FAILED: ' + failures); process.exit(1); }
console.log('SMOKE ALL PASS');
`;

const out = 'tmp/p0817e/smoke_bundle.mjs';
buildSync({
  stdin: { contents: code, resolveDir: 'D:/echoworld', loader: 'js' },
  bundle: true,
  format: 'esm',
  outfile: out,
  platform: 'node',
  logLevel: 'error',
});
console.log('built -> ' + out);
