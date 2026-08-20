/**
 * ImportRolesModal.tsx — 从其他剧本导入角色（共享组件）
 *
 * 左右结构：左 = 来源剧本列表（分组，排除当前剧本）；右 = 该剧本角色小卡。
 * 点击角色即导入到当前剧本并标记「已导入」（✓ / 置灰，不可重复导入）。
 * 支持「一键导入全部」。
 */
import { useMemo, useState } from 'react';
import { useDemoStore } from '../store';
import { getGeneralScripts, getMurderScripts } from '../mockData';
import type { RoleCard } from '../types';

interface ImportRolesModalProps {
  /** 当前剧本 id（导入目标，需排除） */
  currentScriptId: string;
  /** 当前剧本模式 */
  currentKind: 'murder' | 'general';
  /** 已导入到当前剧本的角色 id（显示已导入态） */
  importedIds: string[];
  onImport: (r: RoleCard) => void;
  onClose: () => void;
}

export function ImportRolesModal({ currentScriptId, currentKind, importedIds, onImport, onClose }: ImportRolesModalProps) {
  // P-0811-E：生成的剧本/场景合并进可导入来源（刷新后仍可见）
  const generatedMurder = useDemoStore(s => s.generatedMurder);
  const generatedGeneral = useDemoStore(s => s.generatedGeneral);

  const murders = useMemo(() => {
    const presets = getMurderScripts();
    return generatedMurder && !presets.some(s => s.id === generatedMurder.id)
      ? [generatedMurder, ...presets]
      : presets;
  }, [generatedMurder]);
  const generals = useMemo(() => {
    const presets = getGeneralScripts();
    return generatedGeneral && !presets.some(s => s.id === generatedGeneral.id)
      ? [generatedGeneral, ...presets]
      : presets;
  }, [generatedGeneral]);

  const otherScripts = useMemo(() => {
    const m = murders.filter(s => !(currentKind === 'murder' && s.id === currentScriptId))
      .map(s => ({ kind: 'murder' as const, id: s.id, title: s.title, emoji: '🕵️', roles: s.roles }));
    const g = generals.filter(s => !(currentKind === 'general' && s.id === currentScriptId))
      .map(s => ({ kind: 'general' as const, id: s.id, title: s.title, emoji: s.emoji, roles: s.roles }));
    return { murder: m, general: g };
  }, [murders, generals, currentScriptId, currentKind]);

  const all = [...otherScripts.murder, ...otherScripts.general];
  const [sourceId, setSourceId] = useState<string | null>(all[0]?.id ?? null);
  const source = all.find(s => s.id === sourceId);

  const imported = new Set(importedIds);

  return (
    <div className="modal-mask" onClick={onClose}>
      <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 860 }}>
        <div className="modal-head">
          <div className="modal-title">📥 从其他剧本导入角色</div>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>

        {all.length === 0 ? (
          <div className="empty-note">没有其他剧本可导入。</div>
        ) : (
          <div className="scripts-wrap">
            {/* 左：来源剧本 */}
            <div className="panel-col">
              <div className="panel-col-head">
                <span className="panel-col-num">①</span>
                <span className="panel-col-title">来源剧本</span>
                <span className="panel-col-sub">选择后查看角色</span>
              </div>
              <div style={{ padding: 10, maxHeight: 400, overflowY: 'auto' }}>
                {otherScripts.murder.length > 0 && <div className="lib-mode-label">剧本杀模式</div>}
                {otherScripts.murder.map(s => (
                  <button key={s.id} className={`script-chip ${sourceId === s.id ? 'selected' : ''}`} onClick={() => setSourceId(s.id)}>
                    🕵️ {s.title}<span className="sc-sub">{s.roles.length} 个角色</span>
                  </button>
                ))}
                {otherScripts.general.length > 0 && <div className="lib-mode-label">一般模式</div>}
                {otherScripts.general.map(s => (
                  <button key={s.id} className={`script-chip ${sourceId === s.id ? 'selected' : ''}`} onClick={() => setSourceId(s.id)}>
                    {s.emoji} {s.title}<span className="sc-sub">{s.roles.length} 个角色</span>
                  </button>
                ))}
              </div>
            </div>

            {/* 右：该剧本角色 */}
            <div className="panel-col roles-col">
              <div className="panel-col-head">
                <span className="panel-col-num">②</span>
                <span className="panel-col-title">{source?.title ?? '剧本'}</span>
                <span className="panel-col-sub">点击角色卡导入</span>
              </div>
              <div style={{ padding: '12px 14px' }}>
                <div className="role-chips">
                  {source?.roles.map(r => {
                    const done = imported.has(r.id);
                    return (
                      <div
                        key={r.id}
                        className={`role-chip ${done ? '' : 'selected'}`}
                        style={done ? { opacity: 0.5, cursor: 'default' } : undefined}
                        title={done ? '已导入当前剧本' : '点击导入到当前剧本'}
                        onClick={() => { if (!done) onImport(r); }}
                      >
                        {r.avatar} {r.name}
                        {r.hasSecret && <span className="rc-secret">🔒</span>}
                        {done && <span style={{ color: 'var(--color-success)', fontSize: 12 }}>✓</span>}
                      </div>
                    );
                  })}
                </div>
                <div style={{ marginTop: 14, display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                  <button className="btn2 btn2-primary btn2-sm" disabled={!source || source.roles.every(r => imported.has(r.id))} onClick={() => source?.roles.forEach(r => { if (!imported.has(r.id)) onImport(r); })}>
                    ⚡ 一键导入全部（{source?.roles.filter(r => !imported.has(r.id)).length ?? 0}）
                  </button>
                  <button className="btn2 btn2-ghost btn2-sm" onClick={onClose}>完成</button>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
