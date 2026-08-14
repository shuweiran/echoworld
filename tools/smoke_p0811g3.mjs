/* P-0811-G smoke3: generalMaps localStorage 持久化（真实代码 esbuild） */
import { createRequire } from 'module';
import { writeFileSync, mkdirSync } from 'fs';
const require = createRequire(import.meta.url);
const { buildSync } = require('D:/roleplay-java/roleplay-v4/frontend/node_modules/esbuild');

mkdirSync('tmp/p0811g', { recursive: true });

const code = `
const store = {};
global.localStorage = {
  getItem: k => (k in store ? store[k] : null),
  setItem: (k, v) => { store[k] = String(v); },
  removeItem: k => { delete store[k]; },
};
let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}
const { useDemoStore } = require('./roleplay-v4/frontend/src/demo2/store.ts');
const MAP = { map_version: 1, map_id: 'm1', name: '测试地图', theme: 't', tile_size: 32, width: 4, height: 4, layers: { ground: [[]], collision: [[]] }, rooms: [], corridors: [], zones: [], spawn_points: [] } as any;

// 写入 → localStorage 持久化
useDemoStore.getState().setGeneralMap('g_cafe', MAP);
const persisted = JSON.parse(store['roleplay_demo2_general_maps_v1'] || '{}');
check('setGeneralMap 持久化到 localStorage', !!persisted['g_cafe']);

// 新实例（模拟刷新）→ 从 localStorage 恢复
const { useDemoStore: S2 } = require('./roleplay-v4/frontend/src/demo2/store.ts');
check('刷新后 generalMaps 恢复', !!S2.getState().generalMaps['g_cafe']);

// 清除
S2.getState().setGeneralMap('g_cafe', null);
check('清除后 localStorage 同步删除', !(JSON.parse(store['roleplay_demo2_general_maps_v1'] || '{}')['g_cafe']));
check('清除后 state 同步', !S2.getState().generalMaps['g_cafe']);

console.log(failures === 0 ? 'SMOKE3_ALL_PASS' : 'SMOKE3_FAILURES=' + failures);
process.exit(failures === 0 ? 0 : 1);
`;

const out = buildSync({
  stdin: { contents: code, resolveDir: process.cwd(), loader: 'ts' },
  bundle: true,
  platform: 'node',
  format: 'cjs',
  write: false,
  define: { 'process.env.NODE_ENV': '"test"' },
});
writeFileSync('tmp/p0811g/smoke3.cjs', out.outputFiles[0].text);
console.log('bundle ok');
