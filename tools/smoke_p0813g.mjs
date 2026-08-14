/* P-0813-G smoke: 接近提示（findApproachable）+ 对话框显隐（未对话不渲染 → 点击进入 → 退出 → 再次进入）
 * 用 esbuild 打包真码（simulationData.ts / GalStore.ts / galChoices.ts）在 Node 里断言。
 * 复用 smoke_p0813d 的 liveSayOverride 检查 + 新增：接近阈值判定 / pendingLines 消费 / 重进流程。 */
import { createRequire } from 'module';
import { mkdirSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/roleplay-java/roleplay-v4/frontend/node_modules/esbuild');

mkdirSync('tmp/p0813g', { recursive: true });

const code = `
const { useGalStore } = require('./roleplay-v4/frontend/src/gal/GalStore.ts');
const { findApproachable, APPROACH_DIST } = require('./roleplay-v4/frontend/src/phaser/simulationData.ts');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}

(async () => {

// ── 1. 接近提示纯函数 findApproachable（SimulationScene.updateApproachHints 共用） ──
const agents = [
  { agentName: '我', x: 100, y: 100 },
  { agentName: '小铃', x: 140, y: 130 },  // dist = 50 < 80 → 可交互
  { agentName: '凯尔', x: 240, y: 110 },  // dist = 140.4 ≥ 80 → 不可交互
  { agentName: '露娜', x: 179.9, y: 100 }, // dist = 79.9 < 80 → 边界内可交互
];
let near = findApproachable('我', agents, APPROACH_DIST);
check('1 接近名单 = 距离<80 的 NPC（排除玩家自己）', near.length === 2 && near.includes('小铃') && near.includes('露娜') && !near.includes('我'),
  JSON.stringify(near));
check('1 边界：恰在 80px 外不可交互', !findApproachable('我', [{ agentName: '我', x: 0, y: 0 }, { agentName: '凯尔', x: 80, y: 0 }], APPROACH_DIST).includes('凯尔'));
check('1 玩家不在世界 → 空名单（导演模式无提示）', findApproachable('我', [{ agentName: '凯尔', x: 10, y: 10 }], APPROACH_DIST).length === 0);
check('1 无玩家名 → 空名单', findApproachable('', agents, APPROACH_DIST).length === 0);

// ── 2. 对话框显隐核心：未对话（面板卸载 → exitLiveMode）→ 无 live 状态、override 清除 ──
useGalStore.getState().enterLiveMode('2d-g', { playerName: '我' });
useGalStore.setState({ liveGameType: 'general', liveStatus: 'open', hidePlayerBubbles: false });
useGalStore.getState().setLiveSayOverride(async (t) => { useGalStore.getState().enqueuePlayerEcho(t); });
check('2 对话中：liveMode=true + override 已注入', useGalStore.getState().liveMode === true && typeof useGalStore.getState().liveSayOverride === 'function');

// 退出对话（面板卸载）
useGalStore.getState().exitLiveMode();
const sExit = useGalStore.getState();
check('2 退出对话：liveMode=false + override 清除 + 队列清空',
  sExit.liveMode === false && sExit.liveSayOverride === undefined && sExit.liveQueue.length === 0 && sExit.current === null);

// ── 3. 再次进入（点击 NPC）：pendingLines 消费流程（panel 挂载后 system→enqueue / send→sendText+echo） ──
let sent = 0;
let sendTextCalls = [];
useGalStore.getState().enterLiveMode('2d-g2', { playerName: '我' });
useGalStore.setState({ liveGameType: 'general', liveStatus: 'open', hidePlayerBubbles: false });
useGalStore.getState().setLiveSayOverride(async (t) => { sent++; sendTextCalls.push(t); useGalStore.getState().enqueuePlayerEcho(t); });

// 模拟 SimGalChatPanel 挂载后消费 pendingLines（handleAgentClick 生成：system 提示 + send 问候）
const pendingLines = [
  { kind: 'system', text: '你走近了 小铃，主动打了个招呼…' },
  { kind: 'send', text: '你好，小铃！我是我，想和你聊聊。' },
];
const st3 = useGalStore.getState();
for (const line of pendingLines) {
  if (line.kind === 'send') { await useGalStore.getState().liveSayOverride(line.text); }
  else st3.liveEnqueue({ kind: 'system', speakerId: 'system', name: '💬 你', text: line.text });
}
const s3 = useGalStore.getState();
check('3 重新进入：问候走 override（send 计数 1）', sent === 1 && sendTextCalls[0] === '你好，小铃！我是我，想和你聊聊。', JSON.stringify(sendTextCalls));
check('3 重新进入：系统提示行入队', s3.liveQueue.some(m => m.kind === 'system' && m.text.includes('主动打了个招呼')));
check('3 重新进入：玩家回声入队（hidePlayerBubbles=false 可见）', s3.liveQueue.some(m => m.kind === 'player' && m.text === '你好，小铃！我是我，想和你聊聊。'));

// ── 4. 世界消息流重喂（退出后重新进入 → seenRef 清空 → recentConversations 再次入队播放） ──
const feed = [
  { id: 'w-1|凯尔|你好', who: '凯尔', text: '你好' },
  { id: 'w-2|小铃|今天天气不错', who: '小铃', text: '今天天气不错' },
];
const seen = new Set();
const st4 = useGalStore.getState();
for (const m of feed) {
  if (seen.has(m.id)) continue;
  seen.add(m.id);
  st4.liveEnsureSpeaker(m.who);
  st4.liveEnqueue({ kind: 'agent', speakerId: m.who, name: m.who, text: m.text });
}
const s4 = useGalStore.getState();
check('4 重进后世界消息入队（2 条 agent）', s4.liveQueue.filter(m => m.kind === 'agent').length === 2, JSON.stringify(s4.liveQueue.map(m => m.text)));
check('4 重进后占位立绘重建（凯尔）', s4.speakers.some(sp => sp.id === '凯尔'));

// ── 5. 点击继续播放（advance 消费队首；打字机 tick 推进） ──
let s5 = useGalStore.getState();
if (s5.current && s5.typing) s5.tick(9999);
s5 = useGalStore.getState();
check('5 当前消息进入打字机', !!s5.typing && s5.typing.done);
const before = s5.liveQueue.length;
s5.advance();
s5 = useGalStore.getState();
check('5 点击继续消费队首', s5.liveQueue.length === before - 1, 'before=' + before + ' after=' + s5.liveQueue.length);

// ── 6. 导演模式（无玩家）：点击 NPC 仅系统提示不代发言 ──
useGalStore.getState().exitLiveMode();
useGalStore.getState().enterLiveMode('2d-g3', { playerName: '' });
let echoed = 0;
useGalStore.getState().setLiveSayOverride(async () => { echoed++; });
const st6 = useGalStore.getState();
const hasPlayer = !!(st6.livePlayerName && String(st6.livePlayerName).trim().length > 0);
check('6 导演模式无玩家（不代发言）', hasPlayer === false && echoed === 0);

useGalStore.getState().exitLiveMode();
console.log(failures === 0 ? '\\nALL PASS (' + 0 + ' failures)' : '\\nFAILURES: ' + failures);
process.exit(failures === 0 ? 0 : 1);
})();
`;

const result = buildSync({
  entryPoints: [],
  stdin: { contents: code, resolveDir: 'D:/roleplay-java', sourcefile: 'smoke_p0813g.ts' },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  loader: { '.ts': 'ts', '.tsx': 'tsx', '.css': 'text', '.svg': 'text' },
  write: false,
  logLevel: 'error',
});
require('fs').writeFileSync('tmp/p0813g/smoke_p0813g.cjs', result.outputFiles[0].text);
execSyncNode();
function execSyncNode() {
  const { execSync } = require('child_process');
  try {
    const out = execSync('node tmp/p0813g/smoke_p0813g.cjs', { cwd: 'D:/roleplay-java', encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
    process.stdout.write(out);
  } catch (e) {
    process.stdout.write(e.stdout || '');
    process.exit(1);
  }
}
