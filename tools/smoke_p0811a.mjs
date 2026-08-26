/* P-0811-A smoke: demo2 store scene-history isolation (real code via esbuild) */
import { buildSync } from 'esbuild';
import { writeFileSync, mkdirSync } from 'fs';

mkdirSync('tmp/p0811a', { recursive: true });

// Bundle the real store with a localStorage stub
const code = `
const store = {};
global.localStorage = {
  getItem: k => (k in store ? store[k] : null),
  setItem: (k, v) => { store[k] = String(v); },
  removeItem: k => { delete store[k]; },
};
const { useDemoStore } = require('./frontend/src/demo2/store.ts');
const s = useDemoStore.getState();

// 1) add three records: murder script A, murder script B, werewolf
s.addHistory({ title: '民国宅邸凶案', kind: 'murder', scriptId: 'm_manor', roleName: '我', result: '开始对局（4 名角色）' });
s.addHistory({ title: '星光列车', kind: 'murder', scriptId: 'm_train', roleName: '我', result: '开始对局（3 名角色）' });
s.addHistory({ title: '狼人杀', kind: 'werewolf', scriptId: null, roleName: '我', result: '开始对局（1 名角色）' });

const list = useDemoStore.getState().historyList;
console.log('TOTAL_RECORDS=' + list.length);
console.log('REC0_scriptId=' + list[0].scriptId + ' kind=' + list[0].kind);
console.log('REC1_scriptId=' + list[1].scriptId + ' kind=' + list[1].kind);
console.log('REC2_scriptId=' + list[2].scriptId + ' kind=' + list[2].kind);

// 2) filter expression mirroring RoleSelectPage scopedHistory
const filter = (ctxKind, ctxScriptId) => list.filter(h => h.kind === ctxKind && (h.scriptId ?? '') === (ctxScriptId ?? ''));
console.log('MURDER_A_page=' + filter('murder', 'm_manor').map(h => h.title).join('|'));
console.log('MURDER_B_page=' + filter('murder', 'm_train').map(h => h.title).join('|'));
console.log('WEREWOLF_page=' + filter('werewolf', null).map(h => h.title).join('|'));
console.log('GENERAL_A_page(should be empty)=' + filter('general', 'g_cafe').length);

// 3) persistence round-trip: new store instance reloads from localStorage
const { useDemoStore: useDemoStore2 } = require('./frontend/src/demo2/store.ts');
const reloaded = useDemoStore2.getState().historyList;
console.log('RELOAD_COUNT=' + reloaded.length + ' first_scriptId=' + reloaded[0].scriptId);

// 4) legacy entry (no scriptId) — simulate old localStorage record
store['roleplay_demo2_history_v1'] = JSON.stringify([
  { id: 'h_old', title: '旧剧本记录', kind: 'murder', roleName: '我', time: '08-01 10:00', result: '开始对局（2 名角色）' },
  { id: 'h_oldww', title: '旧狼人杀', kind: 'werewolf', roleName: '我', time: '08-01 10:00', result: '开始对局（1 名角色）' },
]);
const { useDemoStore: useDemoStore3 } = require('./frontend/src/demo2/store.ts');
const legacy = useDemoStore3.getState().historyList;
const legacyFilter = (k, sId) => legacy.filter(h => h.kind === k && (h.scriptId ?? '') === (sId ?? ''));
console.log('LEGACY_murder_page(hidden)=' + legacyFilter('murder', 'm_manor').length);
console.log('LEGACY_werewolf_page(visible)=' + legacyFilter('werewolf', null).map(h => h.title).join('|'));
`;

const out = buildSync({
  stdin: { contents: code, resolveDir: process.cwd(), loader: 'ts' },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  write: false,
});
writeFileSync('tmp/p0811a/smoke.cjs', out.outputFiles[0].text);
console.log('bundle ok');
