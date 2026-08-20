/**
 * ScriptSelectPage.tsx — 剧本选择（主页面 2，页 A）
 *
 * - 右上角：剧本杀模式 / 一般模式 切换
 * - 列表第一位固定「自由角色」入口（管理用户角色，也可作为角色来源）
 * - 剧本卡片保留部分信息（标题/背景/人数/标签）
 * - 点击剧本 → 进入角色选择页（B，按模式进入对应变体）
 * - P-0816-J：剧本卡新增删除按钮（右上角 ✕，hover 显现）——
 *     ① 预设剧本（mockData 代码常量，source='preset'）→ 不可删，点击提示「预设剧本不可删除」；
 *     ② AI 生成/导入剧本（localStorage roleplay_demo2_generated_v1 的 murder/general 槽位，
 *        source='ai'/'import'）→ confirm 后置空对应槽位（setGeneratedMurder/General(null)），
 *        列表经 useMemo 自动重算即时刷新；
 *     ③ 后端场景剧本（source='backend'，P-0816-L 转正式）→ confirm 后调 api.deleteScene(id)，
 *        成功后 removeBackendScript 从 store 移除（后端为数据源，刷新后亦已删）。
 */
import { useEffect, useMemo } from 'react';
import { useDemoStore } from '../store';
import { getGeneralScripts, getMurderScripts } from '../mockData';
import type { GameMode } from '../store';
import type { GeneralScript, MurderScript } from '../types';
import { api } from '../../api/client';
// P-0816-L：后端场景 → 剧本卡映射（GET /api/scenes）
import { backendSceneToGeneral, backendSceneToMurder, isMurderBackendScene } from '../backendScenes';

type ScriptLike = MurderScript | GeneralScript;

/** 是否预设剧本（mockData 代码常量，不可删除） */
function isPreset(s: ScriptLike): boolean {
  return !s.source || s.source === 'preset';
}

/** 来源 chip 文案：AI 生成 / 导入 / 后端场景 */
function sourceLabel(s: ScriptLike): string {
  if (s.source === 'ai') return '✨ AI 生成';
  if (s.source === 'import') return '导入';
  if (s.source === 'backend') return '☁️ 后端场景';
  return '';
}

/**
 * P-0816-J：删除剧本入口（点击卡片 ✕ 触发）。
 * 预设 → 提示不可删（拦截）；生成/导入 → confirm 后置空 localStorage 槽位；
 * backend → 调 DELETE /api/scenes/{id}（预留分支）。
 * P-0816-L：backend 分支转正式 —— 列表已接入 GET /api/scenes；confirm 确认后调用
 * api.deleteScene(id)，成功则 removeBackendScript 从 store 移除（列表即时刷新，刷新页面后
 * 后端亦已删除）；失败 alert 提示。
 */
async function handleDelete(e: React.MouseEvent, kind: 'murder' | 'general', s: ScriptLike): Promise<void> {
  e.stopPropagation(); // 不触发卡片「进入角色选择」
  if (isPreset(s)) {
    window.alert('预设剧本不可删除（内置剧本为代码常量）');
    return;
  }
  if ((s.source as string) === 'backend') {
    if (!window.confirm(`确定删除后端场景「${s.title}」吗？删除后将同步从服务器移除。`)) return;
    try {
      await api.deleteScene(s.id);
      useDemoStore.getState().removeBackendScript(s.id);
    } catch (err: any) {
      window.alert(`删除失败：${String(err?.message || '未知错误')}`);
    }
    return;
  }
  if (!window.confirm(`确定删除剧本「${s.title}」吗？删除后将从列表中移除。`)) return;
  const store = useDemoStore.getState();
  if (kind === 'murder') store.setGeneratedMurder(null);
  else store.setGeneratedGeneral(null);
}

export function ScriptSelectPage() {
  const mode = useDemoStore(s => s.mode);
  const setMode = useDemoStore(s => s.setMode);
  const enterRoles = useDemoStore(s => s.enterRoles);
  const setBackendScripts = useDemoStore(s => s.setBackendScripts);
  // P-0811-E：生成的剧本/场景合并进列表（刷新后仍可见，不再消失）
  const generatedMurder = useDemoStore(s => s.generatedMurder);
  const generatedGeneral = useDemoStore(s => s.generatedGeneral);
  // P-0816-L：后端场景剧本（GET /api/scenes 映射，source='backend'）
  const backendMurder = useDemoStore(s => s.backendMurder);
  const backendGeneral = useDemoStore(s => s.backendGeneral);

  // P-0811-E：生成剧本置顶展示（与预设按 id 去重，防同 id 重复卡片）
  // P-0816-L：合并后端场景 —— 顺序：生成 → 后端 → 预设；去重规则 = 按 id 先到先得
  //   （实际 id 空间互不重叠：生成/预设为本地 id，后端为 scene_id script_*/hex；冲突时早出现者优先）
  const murders = useMemo(() => {
    const out: MurderScript[] = [];
    const seen = new Set<string>();
    const push = (s?: MurderScript | null) => { if (s && !seen.has(s.id)) { seen.add(s.id); out.push(s); } };
    push(generatedMurder);
    backendMurder.forEach(push);
    getMurderScripts().forEach(push);
    return out;
  }, [generatedMurder, backendMurder]);
  const generals = useMemo(() => {
    const out: GeneralScript[] = [];
    const seen = new Set<string>();
    const push = (s?: GeneralScript | null) => { if (s && !seen.has(s.id)) { seen.add(s.id); out.push(s); } };
    push(generatedGeneral);
    backendGeneral.forEach(push);
    getGeneralScripts().forEach(push);
    return out;
  }, [generatedGeneral, backendGeneral]);

  const switchMode = (m: GameMode) => setMode(m);

  // P-0816-L：挂载时拉取后端场景（GET /api/scenes）→ 映射为剧本卡 → 合并进列表。
  // 降级策略：加载失败仅 console.warn（轻提示），预设与本地生成剧本不受影响；单条坏数据跳过。
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const list = await api.listScenes();
        if (!alive || !Array.isArray(list)) return;
        const murder: MurderScript[] = [];
        const general: GeneralScript[] = [];
        for (const raw of list) {
          try {
            if (isMurderBackendScene(raw)) murder.push(backendSceneToMurder(raw));
            else general.push(backendSceneToGeneral(raw));
          } catch { /* 单条坏数据跳过，不拖垮整列表 */ }
        }
        setBackendScripts(murder, general);
      } catch (err) {
        console.warn('[ScriptSelectPage] 后端场景加载失败（不影响预设与本地剧本）：', err);
      }
    })();
    return () => { alive = false; };
  }, [setBackendScripts]);

  return (
    <div>
      <div className="page-head">
        <h2>📜 剧本选择</h2>
        <span className="page-sub">挑选一个剧本，进入角色选择。</span>
        <div className="chip-row" style={{ marginLeft: 'auto', marginBottom: 0 }}>
          <button
            className={`chip2 ${mode === 'murder' ? 'active' : ''}`}
            onClick={() => switchMode('murder')}
          >🕵️ 剧本杀模式</button>
          <button
            className={`chip2 ${mode === 'general' ? 'active' : ''}`}
            onClick={() => switchMode('general')}
          >🌄 一般模式</button>
        </div>
      </div>

      <div className="card2">
        {/* 剧本列表 */}
        <div className="scripts-list">
          {mode === 'murder'
            ? murders.map(s => (
                <div
                  key={s.id}
                  className="script-item"
                  role="button"
                  tabIndex={0}
                  onClick={() => enterRoles({ kind: 'murder', scriptId: s.id })}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      enterRoles({ kind: 'murder', scriptId: s.id });
                    }
                  }}
                >
                  <div className="si-top">
                    <span className="si-title">📜 {s.title}</span>
                    <span className="tag2 tag2-gold" style={{ marginLeft: 'auto' }}>{s.tags[0]}</span>
                    <button
                      type="button"
                      className={`si-del${isPreset(s) ? ' si-del-disabled' : ''}`}
                      title={isPreset(s) ? '预设剧本不可删除' : '删除剧本'}
                      onClick={(e) => { void handleDelete(e, 'murder', s); }}
                    >✕</button>
                  </div>
                  <div className="si-meta">
                    <span>👥 {s.playerMin}-{s.playerMax} 人</span>
                    <span>🎭 {s.roles.length} 角色</span>
                    <span>🔒 {s.roles.filter(r => r.hasSecret).length} 秘密</span>
                    {s.source && s.source !== 'preset' && <span className="tag2 tag2-cyan">{sourceLabel(s)}</span>}
                  </div>
                  <div className="si-desc">{s.background}</div>
                </div>
              ))
            : generals.map(s => (
                <div
                  key={s.id}
                  className="script-item"
                  role="button"
                  tabIndex={0}
                  onClick={() => enterRoles({ kind: 'general', scriptId: s.id })}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      enterRoles({ kind: 'general', scriptId: s.id });
                    }
                  }}
                >
                  <div className="si-top">
                    <span className="si-title">{s.emoji} {s.title}</span>
                    <span className="tag2 tag2-cyan" style={{ marginLeft: 'auto' }}>{s.theme}</span>
                    <button
                      type="button"
                      className={`si-del${isPreset(s) ? ' si-del-disabled' : ''}`}
                      title={isPreset(s) ? '预设剧本不可删除' : '删除剧本'}
                      onClick={(e) => { void handleDelete(e, 'general', s); }}
                    >✕</button>
                  </div>
                  <div className="si-meta">
                    <span>👤 {s.roles.length} 角色</span>
                    <span>🗺️ {s.map.width}×{s.map.height} 地图</span>
                    <span>🏷️ {s.tags.join(' · ')}</span>
                    {s.source && s.source !== 'preset' && <span className="tag2 tag2-gold">{sourceLabel(s)}</span>}
                  </div>
                  <div className="si-desc">{s.desc}</div>
                </div>
              ))}
        </div>
      </div>
    </div>
  );
}
