/* smoke_p0814e.mjs — P-0814-E 一问一答门控逻辑冒烟（esbuild 打包真实代码）
 * ①useAutoPlaybackDone：enabled=false（有玩家在场）→ 队列排空**不发** playback_done（AI 播完停）；
 * ②enabled=true（无玩家/导演模式）→ 队列排空**自动发**（自动推进）；③播放中（drained=false）不发；
 * ④组件门控谓词镜像（GalGeneralView livePlayerName 非空 / ChatMessageFlow currentPlayer∈agents）。
 * React 桩语义对齐真实 React：useRef 按调用位持久化（同实例跨渲染同对象）、useEffect 依赖变更才执行。
 * 运行：node tools/smoke_p0814e.mjs
 */
import { createRequire } from 'module';
import { mkdirSync, writeFileSync } from 'fs';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/roleplay-java/roleplay-v4/frontend/node_modules/esbuild');

mkdirSync('tmp/p0814e/node_modules/react', { recursive: true });

// ── React 最小桩（放 fake node_modules/react，与 hook bundle 共用同一模块实例） ──
writeFileSync('tmp/p0814e/node_modules/react/index.js', `
const refs = [];
let idx = 0;
/** 每次渲染前调用：useRef 按调用位持久化（同实例跨渲染同对象，对齐真实 React） */
function __beginRender() { idx = 0; }
function useRef(init) {
  if (refs[idx] === undefined) refs[idx] = { current: init };
  const r = refs[idx]; idx++;
  return r;
}
let lastDeps = null;
function useEffect(fn, deps) {
  const same = lastDeps !== null && !!deps && deps.length === lastDeps.length && deps.every((d, i) => Object.is(d, lastDeps[i]));
  lastDeps = deps ? [...deps] : null;
  if (!same) fn();
}
module.exports = { __beginRender, useRef, useEffect };
`);

buildSync({
  entryPoints: ['D:/roleplay-java/roleplay-v4/frontend/src/gal/useAutoPlaybackDone.ts'],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  external: ['react'],
  outfile: 'tmp/p0814e/hook.cjs',
  logLevel: 'silent',
});

const { useAutoPlaybackDone } = require('D:/roleplay-java/tmp/p0814e/hook.cjs');
const { __beginRender } = require('D:/roleplay-java/tmp/p0814e/node_modules/react/index.js');

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

(async () => {
  let calls = [];
  globalThis.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init: init || {} });
    return new Response(JSON.stringify({ ok: true }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };

  /** 单实例渲染（refs 跨渲染持久，同真实 React）：外部状态 + onAdvancing 同步清 armed 并重渲染 */
  const inst = { armed: true, drained: true, enabled: true };
  let advancing = 0;
  const render = () => {
    __beginRender(); // 复位 useRef 调用位（同实例跨渲染同对象）
    useAutoPlaybackDone({
      enabled: inst.enabled, armed: inst.armed, drained: inst.drained, sessionId: 's1',
      onAdvancing: () => { advancing++; inst.armed = false; render(); },
    });
  };

  // ── ① 有玩家（enabled=false）：队列排空不自动发信号（AI 播完停等输入） ──
  calls = []; advancing = 0;
  inst.enabled = false; inst.armed = true; inst.drained = true;
  render();
  await sleep(1800);
  check('① 有玩家：队列排空不自动发 playback_done（一问一答停等输入）', calls.length === 0 && advancing === 0,
    'calls=' + calls.length + ' advancing=' + advancing);

  // ── ② 无玩家（enabled=true）：队列排空自动发恰一次（导演模式自动推进） ──
  calls = []; advancing = 0;
  inst.enabled = true; inst.armed = true; inst.drained = true;
  render();
  await sleep(1800);
  check('② 无玩家：队列排空自动发 playback_done 恰一次', calls.length === 1 && advancing === 1,
    'calls=' + calls.length + ' advancing=' + advancing);
  check('② 请求路径 playback_done', calls.length === 1 && String(calls[0].url).includes('playback_done'),
    'url=' + (calls[0] ? calls[0].url : 'none'));
  const body1 = calls.length === 1 ? (calls[0].init.body || '') : '';
  check('② body={session_id:"s1"}（一般模式无 group_id）', body1.includes('"session_id":"s1"'), 'body=' + body1);

  // ── ③ 播放中（drained=false）不发；播完（drained=true）才发 ──
  calls = []; advancing = 0;
  inst.enabled = true; inst.armed = true; inst.drained = false;
  render();
  await sleep(1600);
  check('③ 播放中（队列非空）不发信号', calls.length === 0, 'calls=' + calls.length);
  inst.drained = true;
  render();
  await sleep(1800);
  check('③ 播完（队列排空）才发信号恰一次', calls.length === 1 && advancing === 1, 'calls=' + calls.length + ' advancing=' + advancing);

  // ── ④ 组件门控谓词镜像 ──
  const hasPlayer = (n) => !!n && String(n).trim().length > 0;
  check('④ Gal 有玩家判定：空串/空白=false，名字=true',
    !hasPlayer('') && !hasPlayer('   ') && hasPlayer('凯尔'),
    `''=${hasPlayer('')} '   '=${hasPlayer('   ')} 凯尔=${hasPlayer('凯尔')}`);
  const classicStop = (agents, cp) => agents.includes(cp);
  check('④ 经典视图：currentPlayer∈agents → 停等输入；不在 → 自动推进',
    classicStop(['凯尔', '小铃'], '凯尔') === true && classicStop(['小铃'], 'me') === false,
    '凯尔∈agents=true, me∉agents=false');

  console.log(failures === 0 ? 'SMOKE RESULT: ALL PASS' : 'SMOKE RESULT: ' + failures + ' FAIL');
  process.exit(failures > 0 ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
