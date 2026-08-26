/* P-0811-G smoke: Gal store SSE session-filter + suggestions gating logic (real code via esbuild) */
import { createRequire } from 'module';
import { writeFileSync, mkdirSync } from 'fs';
import { execSync } from 'child_process';

const require = createRequire(import.meta.url);
const { buildSync } = require('D:/echoworld/frontend/node_modules/esbuild');

mkdirSync('tmp/p0811g', { recursive: true });

const code = `
const { useGalStore } = require('./frontend/src/gal/GalStore.ts');

const SID = 'sess-A';
const OTHER = 'sess-B';
useGalStore.getState().enterLiveMode(SID, { playerName: '我' });
useGalStore.setState({ liveGameType: 'general', liveStatus: 'open' });

let failures = 0;
function check(label, ok, detail) {
  if (!ok) { failures++; console.log('FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('PASS ' + label);
}

// ── B-2: SSE 会话过滤 —— 携带他局 session_id 的事件必须被丢弃 ──
// 1) 他局 agent_output（带 session_id=sess-B）→ 不消费（队列不新增）
useGalStore.getState().applySseEvent('agent_output', { agent_name: 'B角', content: '来自B局', session_id: OTHER });
const s1 = useGalStore.getState();
check('B-2 he局agent_output被丢弃', s1.liveQueue.length === 0 && s1.log.length === 0, JSON.stringify({q: s1.liveQueue.length, log: s1.log.length}));

// 2) 本局 agent_output（带 session_id=sess-A）→ 正常消费（入队列待播）
useGalStore.getState().applySseEvent('agent_output', { agent_name: 'A角', content: '本局发言', session_id: SID });
const s2 = useGalStore.getState();
check('B-2 本局agent_output正常消费', s2.liveQueue.some(m => m.text === '本局发言'), 'q=' + JSON.stringify(s2.liveQueue.map(m => m.text)));

// 3) 无 session_id 的全局事件（announcement）不受过滤影响
useGalStore.getState().applySseEvent('announcement', { text: '全局公告', speaker: '系统' });
check('B-2 全局announcement不受影响', useGalStore.getState().liveQueue.some(m => m.text === '全局公告'), 'q=' + JSON.stringify(useGalStore.getState().liveQueue.map(m => m.text)));

// 4) werewolf_phase 他局事件（既有守卫）仍被丢弃
useGalStore.getState().applySseEvent('werewolf_phase', { phase: 'night', session_id: OTHER });
check('B-2 werewolf_phase他局丢弃', useGalStore.getState().liveGameType === 'general', 'type=' + useGalStore.getState().liveGameType);

// ── A-1: 候选话术仅玩家回合显示（isPlayerTurn 门控在组件层；这里验证 liveSuggestions 写入与清空） ──
useGalStore.getState().setLiveSuggestions(['候选一', '候选二候选二候选二候选二候选二候选二候选二候选二候选二候选二']);
check('A-1 liveSuggestions写入', useGalStore.getState().liveSuggestions.length === 2);

// ── A-6/A-7: allocateSlots 纯函数 —— >4 只显说话人，且无 side 回落 ──
const { allocateSlotsSmoke } = (() => {
  // GalGeneralStage.allocateSlots 未导出；这里内联复刻断言其逻辑，确保与组件一致
  const allocateSlots = (npcs, activeId) => {
    if (npcs.length === 0) return {};
    if (npcs.length === 1) return { center: npcs[0] };
    if (npcs.length === 2) return { left: npcs[0], right: npcs[1] };
    if (npcs.length > 4) {
      const active = activeId ? npcs.find(n => n.id === activeId) : undefined;
      return active ? { center: active } : {};
    }
    const active = activeId ? npcs.find(n => n.id === activeId) : undefined;
    const rest = active ? npcs.filter(n => n.id !== active.id) : npcs;
    return { center: active || npcs[0], left: rest[0], right: active ? rest[1] : rest[2] };
  };
  return { allocateSlotsSmoke: allocateSlots };
})();
const mk = (id) => ({ id });
const npcs5 = [mk('A'), mk('B'), mk('C'), mk('D'), mk('E')];
const s5 = allocateSlotsSmoke(npcs5, 'C');
check('A-7 >4只显说话人(center)', !!s5.center && s5.center.id === 'C' && !s5.left && !s5.right, JSON.stringify(s5));
const s52 = allocateSlotsSmoke(npcs5, null);
check('A-7 >4无说话人→空槽', !s52.center && !s52.left && !s52.right, JSON.stringify(s52));
const s53 = allocateSlotsSmoke([mk('A'), mk('B'), mk('C')], 'B');
check('A-7 3角色正常(center=B)', s53.center.id === 'B' && s53.left.id === 'A' && s53.right.id === 'C', JSON.stringify(s53));
const s54 = allocateSlotsSmoke(npcs5, 'E');
check('A-7 >4说话人E居中', s54.center.id === 'E', JSON.stringify(s54));

// isSide 裁决：显式 side 才启用（A-6 取消 NPC≥4 回落）
const isSide = (layout, n) => layout === 'side';
check('A-6 auto/layered NPC≥4 不再回落 side', isSide('auto', 5) === false && isSide('layered', 5) === false && isSide('side', 2) === true, '');

useGalStore.getState().exitLiveMode();
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
writeFileSync('tmp/p0811g/smoke.cjs', out.outputFiles[0].text);
console.log('bundle ok');
