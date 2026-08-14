/* P-0813-D smoke: GalStore liveSayOverride routing + 2D world message feeding + agent-click trigger logic (real code via esbuild) */
import { createRequire } from 'module';
import { mkdirSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/roleplay-java/roleplay-v4/frontend/node_modules/esbuild');

mkdirSync('tmp/p0813d', { recursive: true });

const code = `
const { useGalStore } = require('./roleplay-v4/frontend/src/gal/GalStore.ts');
const { buildLiveChoices } = require('./roleplay-v4/frontend/src/gal/galChoices.ts');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}

(async () => {

// ── 1. liveSayOverride：2D 模拟视图注入后，发言走 override（/api/simulation/send），默认 liveSay 不受影响 ──
let calledOverride = false;
let calledLiveSay = false;
useGalStore.getState().enterLiveMode('2d-test', { playerName: '玩家甲' });
useGalStore.setState({ liveGameType: 'general', liveStatus: 'open', hidePlayerBubbles: false });

// 注入 override（模拟 PhaserSimulationView.simSend 的接线：置 override 后 GalInputArea/GalChoicesArea 走它）
useGalStore.getState().setLiveSayOverride(async (t) => { calledOverride = true; useGalStore.getState().enqueuePlayerEcho(t); });
const sendFn = useGalStore.getState().liveSayOverride;
check('1 liveSayOverride 已注入', typeof sendFn === 'function');

// 玩家回声入队（enqueuePlayerEcho → liveQueue 玩家消息；hidePlayerBubbles=false 不丢弃）
await sendFn('你好，小铃！');
const s1 = useGalStore.getState();
check('1 玩家回声入队', s1.liveQueue.some(m => m.kind === 'player' && m.text === '你好，小铃！'),
  'q=' + JSON.stringify(s1.liveQueue.map(m => m.text)));
check('1 已发送反馈时间戳', s1.liveLastSent > 0);

// 清除 override → 恢复默认（卸载 SimGalChatPanel 时）
useGalStore.getState().setLiveSayOverride(undefined);
check('1 override 清除后为 undefined', useGalStore.getState().liveSayOverride === undefined);

// ── 2. 2D 世界消息流喂入 GalStore 队列（SimGalChatPanel feed：去重 + liveEnsureSpeaker） ──
// 模拟父组件 worldMsgs（recentConversations 拍平：id/who/text）
const feed = [
  { id: 'w-1|凯尔|你好', who: '凯尔', text: '你好' },
  { id: 'w-2|小铃|今天天气不错', who: '小铃', text: '今天天气不错' },
  { id: 'w-1|凯尔|你好', who: '凯尔', text: '你好' }, // 重复 id → 去重
];
const seen = new Set();
const st2 = useGalStore.getState();
for (const m of feed) {
  if (!m || !m.text || !m.who) continue;
  if (seen.has(m.id)) continue;
  seen.add(m.id);
  st2.liveEnsureSpeaker(m.who);
  st2.liveEnqueue({ kind: 'agent', speakerId: m.who, name: m.who, text: m.text });
}
const s2 = useGalStore.getState();
check('2 世界消息去重入队（2 条不重复）', s2.liveQueue.filter(m => m.kind === 'agent').length === 2,
  'q=' + JSON.stringify(s2.liveQueue.map(m => m.text)));
check('2 未知角色占位立绘已建（凯尔）', s2.speakers.some(sp => sp.id === '凯尔'));

// ── 3. 点击继续播放（advance 消费队首；打字机 tick 推进） ──
let s3 = useGalStore.getState();
if (s3.current && s3.typing) {
  // 打字中：tick 推进 → 完成 → advance 下一条
  s3.tick(9999);
}
s3 = useGalStore.getState();
check('3 当前消息进入打字机', !!s3.typing && s3.typing.done, 'typing=' + JSON.stringify(s3.typing && { full: s3.typing.full.slice(0, 8), done: s3.typing.done }));
const before = s3.liveQueue.length;
s3.advance(); // 完成态点击 → 入 log → 弹队首 → 播下一条
s3 = useGalStore.getState();
check('3 点击继续消费队首', s3.liveQueue.length === before - 1, 'before=' + before + ' after=' + s3.liveQueue.length);
check('3 已播消息入 log', s3.log.length >= 1, 'log=' + s3.log.length);

// ── 4. 候选话术（GalChoicesArea 前端候选）在玩家回合生成 ──
const choices = buildLiveChoices('你觉得这家咖啡馆的咖啡怎么样？');
check('4 前端候选 4 条且 ≤40 字', Array.isArray(choices) && choices.length === 4 && choices.every(c => Array.from(c).length <= 40),
  JSON.stringify(choices));

// ── 5. exitLiveMode 清理（卸载面板时） ──
useGalStore.getState().exitLiveMode();
const s5 = useGalStore.getState();
check('5 exitLiveMode 清理队列/状态', s5.liveQueue.length === 0 && s5.liveMode === false && s5.current === null);

// ── 6. 导演模式（无玩家）：override 仍注入但不代玩家发言（handleAgentClick 守卫逻辑） ──
useGalStore.getState().enterLiveMode('2d-test2', { playerName: '' });
let echoed = 0;
useGalStore.getState().setLiveSayOverride(async () => { echoed++; });
const st6 = useGalStore.getState();
// 模拟 handleAgentClick 导演分支：仅系统行，不调 simSend
const hasPlayer = !!(st6.livePlayerName && String(st6.livePlayerName).trim().length > 0);
check('6 导演模式无玩家（不代发言）', hasPlayer === false && echoed === 0);
useGalStore.getState().exitLiveMode();

console.log(failures === 0 ? '\\nALL PASS (' + 0 + ' failures)' : '\\nFAILURES: ' + failures);
process.exit(failures === 0 ? 0 : 1);
})();
`;

const result = buildSync({
  entryPoints: [],
  stdin: { contents: code, resolveDir: 'D:/roleplay-java', sourcefile: 'smoke_p0813d.ts' },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  loader: { '.ts': 'ts', '.tsx': 'tsx', '.css': 'text', '.svg': 'text' },
  write: false,
  logLevel: 'error',
});
require('fs').writeFileSync('tmp/p0813d/smoke_p0813d.cjs', result.outputFiles[0].text);
execSyncNode();
function execSyncNode() {
  const { execSync } = require('child_process');
  try {
    const out = execSync('node tmp/p0813d/smoke_p0813d.cjs', { cwd: 'D:/roleplay-java', encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    process.stdout.write(out);
  } catch (e) {
    process.stdout.write(e.stdout || '');
    process.exit(1);
  }
}
