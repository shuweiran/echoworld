/**
 * RoleLibPage.tsx — 角色库（主页面：角色卡管理）
 *
 * 设计：左「剧本名称」→ 右「该剧本默认角色小卡（只显示名字）」。
 * - 右键角色卡 → 进入详情页（左键点击同样进入）
 * - 选定剧本后，「添加角色」到该剧本（手动表单 / AI 聊天框）
 * - 顶部「自由角色」入口：查看/创建自由角色（作为各剧本角色来源）
 * - 剧本杀与一般模式角色卡互通
 */
import { useMemo, useState } from 'react';
import { useDemoStore } from '../store';
import { getGeneralScripts, getMurderScripts, mockGenerateRole } from '../mockData';
import { api } from '../../api/client';
import { v1RoleToRoleCard } from '../mappers';
import { AiGenBox, type AiGenResult } from '../components/AiGenBox';
import { RoleForm, formToRole, roleToForm, type RoleFormValues } from '../components/RoleForm';
import type { RoleCard } from '../types';

/** 左侧选中的剧本：null=自由角色 / {kind,id} */
type LibTarget = { kind: 'murder' | 'general'; id: string } | null;

export function RoleLibPage() {
  const freeRoles = useDemoStore(s => s.freeRoles);
  const extraRoles = useDemoStore(s => s.extraRoles);
  const addNewRoleToScript = useDemoStore(s => s.addNewRoleToScript);
  const upsertRole = useDemoStore(s => s.upsertRole);
  const removeRole = useDemoStore(s => s.removeRole);
  const removeExtraRole = useDemoStore(s => s.removeExtraRole);
  const removeScriptRole = useDemoStore(s => s.removeScriptRole);
  const effectiveScriptRoles = useDemoStore(s => s.effectiveScriptRoles);
  const removedScriptRoles = useDemoStore(s => s.removedScriptRoles);
  const openRoleDetail = useDemoStore(s => s.openRoleDetail);
  const selectRole = useDemoStore(s => s.selectRole);
  // P-0811-E：生成的剧本/场景合并进角色库剧本分组（刷新后仍可见，不再消失）
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

  const [target, setTarget] = useState<LibTarget>({ kind: 'murder', id: getMurderScripts()[0]?.id ?? '' });
  const [addOpen, setAddOpen] = useState(false);
  const [genMode, setGenMode] = useState<'manual' | 'ai'>('manual');
  const [form, setForm] = useState<RoleFormValues>(() => ({
    ...roleToForm({ id: '', name: '', avatar: '🧝', intro: '', personality: '', talkStyle: '', hasSecret: false, source: 'free', homeScripts: [] }),
    ttsEnabled: false,
  }));

  // 当前选中剧本/自由角色的角色列表
  const { header, roles } = useMemo(() => {
    if (!target) {
      return { header: '自由角色', roles: freeRoles };
    }
    const script = target.kind === 'murder'
      ? murders.find(s => s.id === target.id)
      : generals.find(s => s.id === target.id);
    const added = extraRoles[target.id] || [];
    // 有效默认角色 = 剧本默认去掉已移除的，加上本剧本新增角色
    return { header: script?.title ?? '剧本', roles: [...effectiveScriptRoles(target.id, script?.roles ?? []), ...added] };
  }, [target, freeRoles, extraRoles, murders, generals, effectiveScriptRoles, removedScriptRoles]);

  const openRole = (r: RoleCard) => {
    selectRole(r.id);
    if (!target) {
      openRoleDetail('general', null, r.id);
    } else {
      openRoleDetail(target.kind, target.id, r.id);
    }
  };

  /** 删除角色：新增角色=真删除；一般模式剧本默认角色=从该剧本移除；剧本杀默认角色=只读不可删 */
  const deleteRole = (r: RoleCard) => {
    if (!target) { removeRole(r.id); return; }
    if (extraRoles[target.id]?.some(x => x.id === r.id)) {
      removeExtraRole(target.id, r.id);
    } else if (target.kind === 'general') {
      removeScriptRole(target.id, r.id);
    }
    // 剧本杀默认角色：只读，不删除
  };

  /** 是否只读（剧本杀模式默认角色设定不可改） */
  const isReadonly = (r: RoleCard) =>
    !!target && target.kind === 'murder' && !extraRoles[target.id]?.some(x => x.id === r.id);

  const submitAdd = () => {
    if (!form.name.trim()) return;
    const r = formToRole(form, { id: `role_${Date.now()}`, source: 'free', hasSecret: false, homeScripts: [] });
    if (!target) {
      upsertRole(r);
    } else {
      addNewRoleToScript(r, target.id);
    }
    setAddOpen(false);
    setForm({ ...roleToForm({ id: '', name: '', avatar: '🧝', intro: '', personality: '', talkStyle: '', hasSecret: false, source: 'free', homeScripts: [] }), ttsEnabled: false });
  };

  const aiGen = async (prompt: string): Promise<AiGenResult> => {
    // P-0811-E：AI 生成角色接真实 LLM（后端已自动落库），失败兜底 mock
    try {
      const r = await api.generateCharacter(prompt);
      const name = String(r?.name || mockGenerateRole(prompt).name);
      const role = v1RoleToRoleCard(
        { id: `ai_${Date.now().toString(36)}`, name, intro: r?.summary || r?.appearance || '' },
        0,
        target ? [target.id] : [],
      );
      if (!target) {
        upsertRole(role);
      } else {
        addNewRoleToScript(role, target.id);
      }
      return { text: `已创建角色「${role.name}」并加入${target ? `「${header}」` : '自由角色库'}。` };
    } catch (e: any) {
      const msg = String(e?.message || '生成失败');
      // 撞名 409：提示换描述（不兜底 mock），弹窗保持打开
      if (/已存在/.test(msg)) {
        throw new Error(`${msg}。请换个描述（LLM 会生成不同的角色名），或直接使用现有角色。`);
      }
      // 其他失败：回退本地 mock + 可见提示
      const role = mockGenerateRole(prompt);
      if (!target) {
        upsertRole(role);
      } else {
        addNewRoleToScript(role, target.id);
      }
      return { text: `LLM 生成失败，已用本地模板兜底：已创建角色「${role.name}」并加入${target ? `「${header}」` : '自由角色库'}。（原因：${msg}）` };
    }
  };

  return (
    <div>
      <div className="page-head">
        <h2>🎭 角色卡管理</h2>
        <span className="page-sub">左选剧本 → 右看角色卡 · 右键/点角色卡看详情 · 添加角色归属当前剧本</span>
      </div>

      <div className="scripts-wrap">
        {/* 左：剧本名称 */}
        <div className="panel-col">
          <div className="panel-col-head">
            <span className="panel-col-num">①</span>
            <span className="panel-col-title">剧本名称</span>
            <span className="panel-col-sub">选择剧本查看默认角色</span>
          </div>
          <div style={{ padding: 10 }}>
            <div className="lib-mode-label">自由角色（各剧本角色来源）</div>
            <button
              className={`script-chip ${!target ? 'selected' : ''}`}
              onClick={() => setTarget(null)}
            >🧩 自由角色<span className="sc-sub">{freeRoles.length} 个角色</span></button>

            <div className="lib-mode-label">剧本杀模式</div>
            {murders.map(s => (
              <button
                key={s.id}
                className={`script-chip ${target?.kind === 'murder' && target.id === s.id ? 'selected' : ''}`}
                onClick={() => setTarget({ kind: 'murder', id: s.id })}
              >🕵️ {s.title}<span className="sc-sub">{s.roles.length} 角色 · {s.playerMin}-{s.playerMax} 人</span></button>
            ))}

            <div className="lib-mode-label">一般模式</div>
            {generals.map(s => (
              <button
                key={s.id}
                className={`script-chip ${target?.kind === 'general' && target.id === s.id ? 'selected' : ''}`}
                onClick={() => setTarget({ kind: 'general', id: s.id })}
              >{s.emoji} {s.title}<span className="sc-sub">{s.roles.length} 角色</span></button>
            ))}
          </div>
        </div>

        {/* 右：该剧本默认角色卡（小卡只显示名字） */}
        <div className="panel-col roles-col">
          <div className="panel-col-head">
            <span className="panel-col-num">②</span>
            <span className="panel-col-title">{header}</span>
            <span className="panel-col-sub">{roles.length} 角色 · 点卡看详情</span>
          </div>
          <div style={{ padding: '12px 14px' }}>
            <div className="role-chips">
              {roles.length === 0 && <div className="empty-note" style={{ padding: 14 }}>暂无角色，点击「＋ 添加角色」创建。</div>}
              {roles.map(r => (
                <div
                  key={r.id}
                  className="role-chip"
                  title={isReadonly(r) ? '剧本杀角色设定只读 · 右键查看详情' : '左键或右键查看详情 · ✕ 删除'}
                  onClick={() => openRole(r)}
                  onContextMenu={e => { e.preventDefault(); openRole(r); }}
                >
                  {r.name}
                  {r.hasSecret && <span className="rc-secret">🔒</span>}
                  {!isReadonly(r) && (
                    <span
                      className="rc-x"
                      title="删除此角色"
                      onClick={e => { e.stopPropagation(); deleteRole(r); }}
                    >✕</span>
                  )}
                </div>
              ))}
              <button className="role-chip role-chip-add" onClick={() => setAddOpen(true)}>＋ 添加角色</button>
            </div>
            <div className="hint" style={{ marginTop: 10 }}>
              点击/右键角色卡进详情页；✕ 删除（剧本杀默认角色只读）；「＋ 添加角色」加入「{header}」。
            </div>
          </div>
        </div>
      </div>

      {/* 添加角色弹窗 */}
      {addOpen && (
        <div className="modal-mask" onClick={() => setAddOpen(false)}>
          <div className="modal-box" onClick={e => e.stopPropagation()} style={{ maxWidth: 720 }}>
            <div className="modal-head">
              <div className="modal-title">＋ 添加角色 → {header}</div>
              <div className="chip-row" style={{ marginBottom: 0 }}>
                <button className={`chip2 ${genMode === 'manual' ? 'active' : ''}`} onClick={() => setGenMode('manual')}>✍️ 手动</button>
                <button className={`chip2 ${genMode === 'ai' ? 'active' : ''}`} onClick={() => setGenMode('ai')}>✨ AI 生成</button>
              </div>
              <button className="modal-close" onClick={() => setAddOpen(false)}>✕</button>
            </div>

            {genMode === 'manual' ? (
              <div>
                <RoleForm values={form} onChange={setForm} showSecret={target?.kind === 'murder'} />
                <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end', marginTop: 6 }}>
                  <button className="btn2 btn2-ghost" onClick={() => setAddOpen(false)}>取消</button>
                  <button className="btn2 btn2-primary" onClick={submitAdd}>添加</button>
                </div>
              </div>
            ) : (
              <AiGenBox
                placeholder="描述你想要的角色…"
                stages={['AI 正在构思角色设定…', '正在补充背景故事…']}
                generate={aiGen}
                onResult={() => { setAddOpen(false); }}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}
