/* smoke_p0814g_choices.mjs — P-0814-G（更正）候选自动显示冒烟（esbuild 打包真实代码）
 * 主人 19:44 需求：AI 对话出现后上方自动显示选项，不要再点一次出现。
 * 根因：usePlayerTurn 旧条件要求队列排空+current 清空 = 点击弹队后才满足 → 真机「要点击才出现」。
 * 断言：
 *  ① isPlayerTurnGate 纯函数状态矩阵：S1 播放中=false / S2 播完未点击=true（修复目标）/
 *     S3 已弹队=true / S4 等待=true / 多消息排队播第1条=false / liveSending=true=false；
 *  ② 真实 store 驱动：liveEnqueue AI 消息 → tick 播完（typing.done=true，未点击）→
 *     isPlayerTurnGate=true（候选可直接显示）；advance 弹队后仍 true。
 * 运行：node tools/smoke_p0814g_choices.mjs（0=ALL PASS）
 */
import { createRequire } from 'module';
import { mkdirSync, writeFileSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/roleplay-java/roleplay-v4/frontend/node_modules/esbuild');

mkdirSync('tmp/p0814g/choices', { recursive: true });

globalThis.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
globalThis.EventSource = class { constructor() {} close() {} addEventListener() {} };
globalThis.fetch = async () => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } });

buildSync({
  entryPoints: ['D:/roleplay-java/tmp/p0814g/choices/drive.ts'],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  outfile: 'tmp/p0814g/choices/drive.cjs',
  nodePaths: ['D:/roleplay-java/roleplay-v4/frontend/node_modules'],
  logLevel: 'silent',
});
writeFileSync('D:/roleplay-java/tmp/p0814g/choices/drive.ts', `
export { isPlayerTurnGate } from '../../../../roleplay-v4/frontend/src/gal/GalChoiceBar';
export { useGalStore } from '../../../../roleplay-v4/frontend/src/gal/GalStore';
`);

const { isPlayerTurnGate, useGalStore } = require('D:/roleplay-java/tmp/p0814g/choices/drive.cjs');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}
const st = () => useGalStore.getState();
const gate = () => {
  const s = st();
  return isPlayerTurnGate({
    liveMode: s.liveMode,
    liveGameType: s.liveGameType,
    liveStatus: s.liveStatus,
    liveSending: s.liveSending,
    queueLen: s.liveQueue.length,
    typing: s.typing,
  });
};

(async () => {
  // ════ ① 纯函数状态矩阵 ════
  const base = { liveMode: true, liveGameType: 'general', liveStatus: 'open', liveSending: false };
  check('① S1 播放中（typing 未 done）→ 候选不显示',
    isPlayerTurnGate({ ...base, queueLen: 1, typing: { done: false } }) === false);
  check('① S2 播完未点击（typing.done=true，队列=当前条）→ 候选显示【修复目标】',
    isPlayerTurnGate({ ...base, queueLen: 1, typing: { done: true } }) === true);
  check('① S3 已弹队（无 typing，队列空）→ 候选显示',
    isPlayerTurnGate({ ...base, queueLen: 0, typing: null }) === true);
  check('① S4 等待态（无消息）→ 候选显示',
    isPlayerTurnGate({ ...base, queueLen: 0, typing: null }) === true);
  check('① 多消息排队播第 1 条（queueLen=3）→ 不显示',
    isPlayerTurnGate({ ...base, queueLen: 3, typing: { done: false } }) === false);
  check('① 多条排队最后一条播完（queueLen=2 剩 2 条）→ 不显示',
    isPlayerTurnGate({ ...base, queueLen: 2, typing: { done: true } }) === false);
  check('① liveSending=true → 不显示',
    isPlayerTurnGate({ ...base, liveSending: true, queueLen: 1, typing: { done: true } }) === false);
  check('① 非 general 类型 → 不显示',
    isPlayerTurnGate({ ...base, liveGameType: 'werewolf', queueLen: 0, typing: null }) === false);

  // ════ ② 真实 store 驱动 S2 ════
  st().exitLiveMode();
  st().enterLiveMode('s1', { playerName: 'me' });
  st().setLiveGameType('general');
  st().setLiveStatus('open');
  st().setHidePlayerBubbles(true);
  st().liveEnqueue({ kind: 'agent', speakerId: '苏瑶', name: '苏瑶', text: '你好呀，欢迎光临。' });
  // 播放中：typing 建立未 done
  check('② 入队后播放中 → 候选不显示', gate() === false, 'q=' + st().liveQueue.length + ' typing=' + JSON.stringify(st().typing && { done: st().typing.done }));
  // 模拟 tick 播完（打字机逐字推进）
  while (st().typing && !st().typing.done) st().tick(1000);
  check('② 播完未点击（typing.done=true，队列剩当前条）→ 候选直接显示【核心】',
    gate() === true, 'q=' + st().liveQueue.length + ' typingDone=' + st().typing?.done + ' cur=' + !!st().current);
  // advance 弹队（旧交互仍可用）→ 候选保持
  st().advance();
  check('② 点击弹队后 → 候选仍显示', gate() === true, 'q=' + st().liveQueue.length);
  // 新消息到达（下一轮）→ 候选消失
  st().liveEnqueue({ kind: 'agent', speakerId: '苏瑶', name: '苏瑶', text: '新的一轮回复。' });
  check('② 新轮消息入队播放中 → 候选隐藏', gate() === false, 'q=' + st().liveQueue.length);

  console.log(failures === 0 ? 'SMOKE RESULT: ALL PASS' : 'SMOKE RESULT: ' + failures + ' FAIL');
  process.exit(failures > 0 ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
