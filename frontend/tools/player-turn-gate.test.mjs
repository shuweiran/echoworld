import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { isAutonomousAdvanceGate, isPlayerTurnGate } from '../src/gal/playerTurnGate.ts';

const ready = {
  liveMode: true,
  liveGameType: 'general',
  liveStatus: 'open',
  queueLen: 1,
  typing: { done: true },
  roundComplete: false,
  inputPending: false,
};

test('round_complete 后且输入已终态才显示玩家选项', () => {
  assert.equal(isPlayerTurnGate(ready), false);
  assert.equal(isPlayerTurnGate({ ...ready, roundComplete: true, inputPending: true }), false);
  assert.equal(isPlayerTurnGate({ ...ready, roundComplete: true }), true);
  assert.equal(isPlayerTurnGate({ ...ready, roundComplete: true, queueLen: 2 }), false);
  assert.equal(isPlayerTurnGate({ ...ready, roundComplete: true, typing: { done: false } }), false);
});

test('SSE 成功与失败终态都已接线', () => {
  const store = readFileSync(new URL('../src/gal/GalStore.ts', import.meta.url), 'utf8');
  const sse = readFileSync(new URL('../src/api/useSSE.ts', import.meta.url), 'utf8');
  assert.match(store, /case 'round_complete':[\s\S]{0,240}livePlaybackArmed: true/);
  assert.match(store, /case 'agent_error':[\s\S]{0,320}liveFailAgent/);
  assert.match(sse, /'agent_error'/);
});

test('有玩家时点击空对话框不得生成无输入的新一轮', () => {
  const autonomous = {
    hasSession: true,
    hasPlayer: false,
    hasOverride: false,
    gameType: 'general',
    drained: true,
    busy: false,
  };
  assert.equal(isAutonomousAdvanceGate(autonomous), true);
  assert.equal(isAutonomousAdvanceGate({ ...autonomous, hasPlayer: true }), false);

  const store = readFileSync(new URL('../src/gal/GalStore.ts', import.meta.url), 'utf8');
  assert.match(store, /requestNextRound:[\s\S]{0,360}livePlayerName[\s\S]{0,80}return/);
});
