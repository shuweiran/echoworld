/**
 * ScriptSelectPage.tsx — 剧本选择（主页面 2，页 A）
 *
 * 数据边界（合入 roleplay-v4 PR#1「isolate murder and general script lists」）：
 * - 剧本杀：只展示完整 MurderScript（预设 / AI 生成 / 导入）。
 * - 一般模式：展示 GeneralScript（预设 / AI 生成 / 后端 /api/scenes）。
 * - 后端通用 scene 不再通过 scene_id 前缀强转成 MurderScript；狼人杀和旧 script_* 记录也不混入一般模式。
 * - 删除入口：预设不可删；生成/导入置空 localStorage 槽位；后端场景走 DELETE /api/scenes/{id}。
 */
import { useEffect, useMemo } from 'react';
import { useDemoStore } from '../store';
import { getGeneralScripts, getMurderScripts } from '../mockData';
import type { GameMode } from '../store';
import type { GeneralScript, MurderScript } from '../types';
import { api } from '../../api/client';
// 后端场景 → 一般模式剧本卡映射（GET /api/scenes；不再产出 MurderScript）
import { backendSceneToGeneral, isGeneralBackendScene, type BackendSceneRecord } from '../backendScenes';

type ScriptLike = MurderScript | GeneralScript;
const MOBILE_BUILD = import.meta.env.VITE_MOBILE_BUILD === 'true';

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
 * 删除剧本入口（点击卡片 ✕ 触发）。
 * 预设 → 提示不可删（拦截）；生成/导入 → confirm 后置空 localStorage 槽位；
 * backend → confirm 后调 api.deleteScene(id)，成功则 removeBackendScript 从 store 移除。
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

/**
 * 兼容 /api/scenes 的两种历史响应形态：
 * - 旧前端预期：Scene[]
 * - 当前包装响应：{ scenes: Scene[] }
 */
function unwrapSceneList(payload: unknown): BackendSceneRecord[] {
  if (Array.isArray(payload)) return payload as BackendSceneRecord[];
  if (payload && typeof payload === 'object' && Array.isArray((payload as any).scenes)) {
    return (payload as any).scenes as BackendSceneRecord[];
  }
  return [];
}

export function ScriptSelectPage() {
  const mode = useDemoStore(s => s.mode);
  const setMode = useDemoStore(s => s.setMode);
  const enterRoles = useDemoStore(s => s.enterRoles);
  const setBackendScripts = useDemoStore(s => s.setBackendScripts);
  const generatedMurder = useDemoStore(s => s.generatedMurder);
  const generatedGeneral = useDemoStore(s => s.generatedGeneral);
  const backendGeneral = useDemoStore(s => s.backendGeneral);

  // 剧本杀只接受真正的 MurderScript 数据源（生成 → 预设），不接 /api/scenes。
  const murders = useMemo(() => {
    const out: MurderScript[] = [];
    const seen = new Set<string>();
    const push = (s?: MurderScript | null) => { if (s && !seen.has(s.id)) { seen.add(s.id); out.push(s); } };
    push(generatedMurder);
    getMurderScripts().forEach(push);
    return out;
  }, [generatedMurder]);
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

  // /api/scenes 只装载一般模式场景；狼人杀、旧 script_*、未知分类全部跳过。
  // 降级策略：加载失败仅 console.warn（轻提示），预设与本地生成剧本不受影响；单条坏数据跳过。
  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const payload = await api.listScenes();
        if (!alive) return;
        const general: GeneralScript[] = [];
        for (const raw of unwrapSceneList(payload)) {
          try {
            if (isGeneralBackendScene(raw)) general.push(backendSceneToGeneral(raw));
          } catch { /* 单条坏数据跳过，不拖垮整列表 */ }
        }
        setBackendScripts(general);
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
          {!MOBILE_BUILD && <button
            className={`chip2 ${mode === 'murder' ? 'active' : ''}`}
            onClick={() => switchMode('murder')}
          >🕵️ 剧本杀模式</button>}
          <button
            className={`chip2 ${mode === 'general' ? 'active' : ''}`}
            onClick={() => switchMode('general')}
          >🌄 一般模式</button>
        </div>
      </div>

      <div className="card2">
        {/* 剧本列表 */}
        <div className="scripts-list">
          {MOBILE_BUILD || mode === 'general'
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
