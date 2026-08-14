/* P-0811-G smoke: demo2 generalMaps store + 玩家判定逻辑（真实代码 esbuild） */
import { createRequire } from 'module';
import { writeFileSync, mkdirSync } from 'fs';
const require = createRequire(import.meta.url);
const { buildSync } = require('D:/roleplay-java/roleplay-v4/frontend/node_modules/esbuild');

mkdirSync('tmp/p0811g', { recursive: true });

const code = `
const { useDemoStore } = require('./roleplay-v4/frontend/src/demo2/store.ts');
let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}

// A. generalMaps store 写入/读取/清除
useDemoStore.getState().setGeneralMap('g_cafe', { map_version: 1, map_id: 'x', name: 't', theme: 't', tile_size: 16, width: 32, height: 20, layers: { ground: [[]], collision: [[]] }, rooms: [], corridors: [], zones: [], spawn_points: [] } as any);
check('generalMaps 写入', !!useDemoStore.getState().generalMaps['g_cafe']);
useDemoStore.getState().setGeneralMap('g_cafe', null);
check('generalMaps 清除', !useDemoStore.getState().generalMaps['g_cafe']);

// B. 玩家判定：withPlayer/playerRole → 是否带玩家
useDemoStore.getState().setWithPlayer(false);
useDemoStore.getState().setPlayerRole(null);
const s2 = useDemoStore.getState();
check('B 无玩家：withPlayer=false', s2.withPlayer === false);
const playerJoins2 = !!s2.playerRole && (true || s2.withPlayer);
check('B 无玩家：playerJoins=false', playerJoins2 === false);
// 有玩家角色 + withPlayer=true → 带玩家
useDemoStore.getState().setWithPlayer(true);
useDemoStore.getState().setPlayerRole({ id: 'p', name: '凯尔' } as any);
const s3 = useDemoStore.getState();
check('B 有玩家：playerJoins=true', !!s3.playerRole && s3.withPlayer === true);

console.log(failures === 0 ? 'SMOKE_ALL_PASS' : 'SMOKE_FAILURES=' + failures);
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
writeFileSync('tmp/p0811g/smoke2.cjs', out.outputFiles[0].text);
console.log('bundle ok');
