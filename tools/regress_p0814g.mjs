// regress_p0814g.mjs — 对照回归：把 pullGeneralHistory 玩家归类改回旧行为（role=agent 一律 AI），
// 同一套冒烟断言应 FAIL（证明修复真实有效）。不改源码（镜像复制到 tmp 后替换）。
import { createRequire } from 'module';
import { mkdirSync, writeFileSync, readFileSync, copyFileSync, existsSync } from 'fs';
import { dirname } from 'path';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/echoworld/frontend/node_modules/esbuild');

const SRC = 'D:/echoworld/frontend/src';
const REG = 'D:/echoworld/tmp/p0814g/regress';
mkdirSync(REG + '/api', { recursive: true });
mkdirSync(REG + '/utils', { recursive: true });

// 镜像复制依赖链（保持相对 import 结构）
const files = [
  'gal/GalStore.ts', 'gal/galSseAdapter.ts', 'gal/galDemoData.ts', 'gal/galChoices.ts',
  'api/client.ts', 'api/aiImage.ts', 'utils/silenceMarker.tsx', 'types/index.ts',
  'types.ts',
];
for (const f of files) {
  const from = SRC + '/' + f;
  if (!existsSync(from)) continue;
  const to = REG + '/' + f;
  mkdirSync(dirname(to), { recursive: true });
  copyFileSync(from, to);
}
// galChoices 可能 import galDemoData —— 已覆盖。aiImage import client —— 已覆盖。

// 改 galSseAdapter：归类改回旧行为
const adapterPath = REG + '/gal/galSseAdapter.ts';
let code = readFileSync(adapterPath, 'utf-8');
const old = `      const isPlayerMsg = role === 'user'
        || (role === 'agent' && !!name && (name === 'me' || (playerName && name === playerName)));`;
if (!code.includes(old)) throw new Error('pattern not found in adapter');
code = code.replace(old, `      const isPlayerMsg = false; // 对照：旧行为（role=agent 一律 AI）`);
writeFileSync(adapterPath, code);

writeFileSync(REG + '/drive.ts', `
export { useGalStore } from './gal/GalStore';
export { pullGeneralHistory } from './gal/galSseAdapter';
`);

globalThis.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
globalThis.EventSource = class { constructor() {} close() {} addEventListener() {} };
const historyDb = [];
globalThis.fetch = async (url) => {
  const u = String(url);
  let payload = { ok: true };
  if (u.includes('/api/history')) payload = { messages: historyDb };
  else if (u.includes('/api/round/suggest')) payload = { suggestions: [] };
  else if (u.includes('/api/mode')) payload = { mode: 'protagonist' };
  else payload = { ok: true };
  return new Response(JSON.stringify(payload), { status: 200, headers: { 'Content-Type': 'application/json' } });
};

buildSync({
  entryPoints: [REG + '/drive.ts'],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  outfile: 'tmp/p0814g/regress/drive.cjs',
  nodePaths: ['D:/echoworld/frontend/node_modules'],
  logLevel: 'silent',
});

const { useGalStore, pullGeneralHistory } = require('D:/echoworld/tmp/p0814g/regress/drive.cjs');
const st = () => useGalStore.getState();
const queueTexts = () => st().liveQueue.map(m => `${m.kind}:${m.speakerId}:${m.text}`);

(async () => {
  st().enterLiveMode('s1', { playerName: 'me' });
  st().setLiveGameType('general');
  st().setHidePlayerBubbles(true);
  historyDb.push({ role: 'agent', name: '苏瑶', content: '苏瑶R1回复', round_number: 1 });
  historyDb.push({ role: 'agent', name: 'me', content: '玩家R2发言', round_number: 2 });
  historyDb.push({ role: 'agent', name: '苏瑶', content: '苏瑶R2回复', round_number: 2 });
  st().applySseEvent('agent_output', { agent_name: '苏瑶', content: '苏瑶R1回复', session_id: 's1' });
  st().skipTyping(); st().advance();
  st().applySseEvent('agent_output', { agent_name: '苏瑶', content: '苏瑶R2回复', session_id: 's1' });
  st().skipTyping(); st().advance();
  await pullGeneralHistory('s1');
  const q = queueTexts();
  const hasMeAfterAi = q.some(m => m.includes('me:玩家R2发言'));
  console.log('[regress] queue =', JSON.stringify(q));
  console.log(hasMeAfterAi
    ? '[regress] 旧行为复现：me 以 AI 样式入队且排在 AI 回复之后（乱序）→ 修复有效'
    : '[regress] 意外：me 未出现');
  process.exit(hasMeAfterAi ? 0 : 1);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
