/**
 * ScriptProtoRightPanel.tsx — 剧本杀对局页·右栏（P-0816-M 按原型重构，ui-proto-v2）
 *
 * 对齐原型三页右栏：4 Tab（线索 / 逻辑链 / 角色库 / 历史）+ 收起 → 📋 悬浮按钮抽屉：
 *   ① 线索库（Tab 线索）：Her Story 式证据检索框 + chips（全部/人物/地点/时间）+ 线索卡
 *      （id/地点/标签/内容/持有）+ 操作行「📎 出示（阶段三壳，API-9 未备）/ 🔁 转交（真实
 *      transfer_clue 端点，D-016）」——转交行选目标玩家后调用父级 onTransferClue。
 *   ② 逻辑链（Tab 逻辑链）：Obra Dinn 人物×线索×关系矩阵（U2 MVP 前端内容推导：
 *      content 提及角色名 → ★ 直接关联；阶段二切后端 API-8）+ 关系连线 + 图例 + 壳标注。
 *   ③ 角色库（Tab 角色库）：本局角色卡（角色色渐变头像 + 名字 + 心锁 🔒 标记 + 本人角标高）；
 *   ④ 历史（Tab 历史）：HistoryPanel 内嵌（既有历史会话加载组件，L11）。
 * 数据源全部为真实状态（status.clues/my_clues/roles/locations/players），无 mock。
 */
import { useEffect, useMemo, useState } from 'react';
import { HistoryPanel } from '../HistoryPanel/HistoryPanel';
import { deriveRelations, type ScriptClueLike } from './actionUtils';
// 阶段 D（P-0817-E）：证据检索/chips 已抽至共享层 utils/ui/evidenceFilter.ts
import { evidenceTags, filterEvidence } from '../../utils/ui/evidenceFilter';
import { deriveRoleLocks } from './actionUtils';
import { avatarGradientFor } from './chatUtils';
import { useAppStore } from '../../store/appStore';
import { api } from '../../api/client';

export interface ScriptProtoRightPanelProps {
  scriptState: any;
  currentPlayer: string;
  busy: boolean;
  transferTargets: Record<string, string>;
  setTransferTargets: (m: Record<string, string>) => void;
  /** 线索转交（真实 transfer_clue 端点，D-016；父级 ChatPage 已接线） */
  onTransferClue: (clueId: string, target: string) => void;
  /** P-0816-T（阶段三 API-9，决策 C8）：出示证据到对话流（真实 POST /api/script/present；
   *  仅 DISCUSSION 阶段 + 持有该线索/公开线索可出示；父级 ChatPage 已接线） */
  onPresentClue: (clueId: string) => void;
}

const TABS = [
  { id: 'clues', label: '线索' },
  { id: 'chain', label: '逻辑链' },
  { id: 'roles', label: '角色库' },
  { id: 'hist', label: '历史' },
] as const;
type TabId = typeof TABS[number]['id'];

const CHIPS = ['全部', '人物', '地点', '时间'];

export function ScriptProtoRightPanel({
  scriptState, currentPlayer, busy, transferTargets, setTransferTargets, onTransferClue, onPresentClue,
}: ScriptProtoRightPanelProps) {
  const [tab, setTab] = useState<TabId>('clues');
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('全部');

  const roles: string[] = Array.isArray(scriptState?.roles) ? scriptState.roles : [];
  const locations: string[] = Array.isArray(scriptState?.locations) ? scriptState.locations : [];
  const players: string[] = Array.isArray(scriptState?.players) ? scriptState.players : [];
  const myClues: ScriptClueLike[] = Array.isArray(scriptState?.my_clues) ? scriptState.my_clues : [];
  const visibleClues: ScriptClueLike[] = Array.isArray(scriptState?.clues) ? scriptState.clues : [];
  const otherPlayers = players.filter(p => p !== currentPlayer);

  // 证据列表 = status.clues（公开+持有）∪ my_clues（持有全量），按 id 去重
  const evidence: ScriptClueLike[] = useMemo(() => {
    const byId = new Map<string, ScriptClueLike>();
    for (const c of visibleClues) if (c?.id) byId.set(String(c.id), c);
    for (const c of myClues) if (c?.id) byId.set(String(c.id), c);
    return [...byId.values()];
  }, [visibleClues, myClues]);

  const filtered = useMemo(
    () => filterEvidence(evidence, query, category, roles, locations),
    [evidence, query, category, roles, locations],
  );

  const locks = useMemo(() => deriveRoleLocks(evidence, roles), [evidence, roles]);
  const relations = useMemo(() => deriveRelations(evidence, roles), [evidence, roles]);
  // P-0816-R（API-8，决策 U2）：关系矩阵服务端推导 —— GET /api/script/relations（内容提及★ / 持有者◯ / 其余–）；
  // 请求失败/后端未上线 → 回退前端推导（deriveRelations），图例标注数据源
  const store = useAppStore();
  const [serverRelations, setServerRelations] = useState<any>(null);
  const [relationsSource, setRelationsSource] = useState<'server' | 'local'>('local');
  useEffect(() => {
    let alive = true;
    api.scriptRelations(currentPlayer, store.scriptRoleKey || undefined)
      .then(res => {
        if (!alive) return;
        if (res?.ok && Array.isArray(res.roles) && res.matrix) {
          setServerRelations(res);
          setRelationsSource('server');
        } else {
          setRelationsSource('local');
        }
      })
      .catch(() => { if (alive) setRelationsSource('local'); });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPlayer, scriptState?.session_id, evidence.length]);
  const matrixRoles: string[] = serverRelations ? (serverRelations.roles as string[]) : roles;
  const matrixClueIds: string[] = serverRelations
    ? ((serverRelations.clues as string[]) || []).slice(0, 3)
    : evidence.slice(0, 3).map(c => String(c.id));
  const markOf = (role: string, clueId: string): string => {
    if (serverRelations?.matrix?.[role]) {
      const m = String(serverRelations.matrix[role][clueId] || '–');
      return m === '★' || m === '◯' ? m : '–';
    }
    return relations.matrix.find(m => m.role === role && m.clue === clueId)?.mark === '★' ? '★' : '–';
  };
  const trusteeSet = new Set<string>(Array.isArray(scriptState?.trustees) ? scriptState.trustees : []);
  const myRole = scriptState?.your_role || currentPlayer;
  // P-0816-T（阶段三 API-9）：出示仅 DISCUSSION 阶段可用（后端阶段守卫；前端禁用引导）
  const presentPhaseOk = scriptState?.phase === 'discussion';
  const myClueIds = new Set<string>(myClues.map((c: ScriptClueLike) => String(c?.id)));

  return (
    <div className="proto-right-body">
      {/* 4 Tab（对齐原型 .tabs/.tab.on 相位色） */}
      <div className="proto-tabs">
        {TABS.map(t => (
          <button
            key={t.id}
            className={`proto-tab${tab === t.id ? ' on' : ''}`}
            onClick={() => setTab(t.id)}
          >{t.label}</button>
        ))}
      </div>

      {/* ═══ Tab 线索库：证据检索（Her Story）+ chips + 线索卡 + 出示/转交 ═══ */}
      {tab === 'clues' && (
        <div className="proto-pane">
          <div className="proto-hs-search">
            <input
              className="proto-hs-input"
              type="text"
              placeholder="🔍 搜索证据…（人名/地点/时间）"
              value={query}
              onChange={e => setQuery(e.target.value)}
            />
            <div className="proto-hs-chips">
              {CHIPS.map(c => (
                <button
                  key={c}
                  className={`proto-hs-chip${category === c ? ' on' : ''}`}
                  onClick={() => setCategory(c)}
                >{c}</button>
              ))}
            </div>
          </div>
          <div className="proto-pane-label">线索库 · {filtered.length} 条可见</div>
          {filtered.length === 0 && (
            <div className="proto-pane-hint">无匹配证据（检索词/分类过滤）<br />搜证阶段点击地点卡片获取线索</div>
          )}
          {filtered.map(c => {
            const tags = evidenceTags(c, roles, locations);
            const transferable = c.transferable === true;
            return (
              <div key={c.id} className="proto-clue-card">
                <div className="proto-clue-head">
                  <span className="proto-clue-id">{c.id}</span>
                  {c.location ? <span className="proto-clue-loc">📍 {c.location}</span> : null}
                  {tags.map(t => <span key={t} className="proto-clue-tag">{t}</span>)}
                </div>
                <div className="proto-clue-body">{(c.title ? `${c.title}：` : '') + String(c.content || '')}</div>
                <div className="proto-clue-ops">
                  {/* P-0816-T（阶段三 API-9，决策 C8）：出示证据接通 —— 去禁用，点击 → POST /api/script/present
                      （「🃏 出示：CL-xx 线索名」system 行入讨论转录全员可见 + SSE script_present 同步；
                      成功 toast 由父级 ChatPage 处理；失败 toast 后端 error）。
                      可出示条件：DISCUSSION 阶段 + 本人持有或公开线索；其余禁用并给出原因提示 */}
                  {(() => {
                    const held = myClueIds.has(String(c.id)) || c.public === true;
                    const reason = !presentPhaseOk
                      ? '仅讨论阶段可出示证据'
                      : (!held ? '未持有该线索（搜证获得后可出示）' : '');
                    return (
                      <button
                        className="btn btn-smallall"
                        disabled={busy || !held || !presentPhaseOk}
                        onClick={() => onPresentClue(String(c.id))}
                        title={reason || '出示到对话流：全员可见「🃏 出示」系统行（真实端点 API-9）'}
                      >📎 出示{!presentPhaseOk ? ' 阶段三' : ''}</button>
                    );
                  })()}
                  {/* 🔁 转交：真实 transfer_clue 端点（D-016；仅可转交线索 + 有目标玩家时） */}
                  {transferable && otherPlayers.length > 0 ? (
                    <span className="proto-transfer-row">
                      <select
                        value={transferTargets[String(c.id)] || ''}
                        onChange={e => setTransferTargets({ ...transferTargets, [String(c.id)]: e.target.value })}
                      >
                        <option value="">转交→</option>
                        {otherPlayers.map(p => <option key={p} value={p}>{p}</option>)}
                      </select>
                      <button
                        className="btn btn-smallall"
                        disabled={busy || !transferTargets[String(c.id)]}
                        onClick={() => onTransferClue(String(c.id), transferTargets[String(c.id)])}
                        title="转交线索：ownership 变更，接收方立即可见（真实端点）"
                      >🔁 转交</button>
                    </span>
                  ) : (
                    <button className="btn btn-smallall" disabled title={transferable ? '本局无其他玩家可转交' : '该线索不可转交（transferable=false）'}>
                      🔁 转交
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* ═══ Tab 逻辑链：Obra Dinn 人物×线索×关系矩阵（P-0816-R 接通 API-8 服务端推导） ═══ */}
      {tab === 'chain' && (
        <div className="proto-pane">
          <div className="proto-pane-label">人物 × 线索 × 关系（Obra Dinn 整理簿）</div>
          <div className="proto-matrix">
            {matrixClueIds.length === 0 ? (
              <div className="proto-pane-hint">暂无可见线索（搜证后矩阵自动填充）</div>
            ) : (
              <table>
                <thead>
                  <tr>
                    <th />
                    {matrixClueIds.map(id => <th key={id}>{id}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {matrixRoles.map(r => (
                    <tr key={r}>
                      <td className="proto-matrix-name">{r}{r === myRole ? '（你）' : ''}</td>
                      {matrixClueIds.map(id => {
                        const mark = markOf(r, id);
                        return (
                          <td key={id}>
                            <span className={`proto-mk${mark !== '–' ? ' direct' : ''}`} title={mark === '★' ? '直接关联：线索内容提及该角色' : mark === '◯' ? '持有：该线索由本角色持有' : '无关联'}>{mark}</span>
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <div className="proto-matrix-legend">★ 直接关联（内容提及） · ◯ 持有 · – 无关联 —— {relationsSource === 'server' ? '服务端推导（API-8 GET /api/script/relations，决策 U2 MVP 内容推导）' : '前端内容推导（API-8 不可用时回退，阶段二接通后以服务端为准）'}</div>
            {(relationsSource === 'local' ? relations.lines : ((serverRelations?.relations) || [])).length > 0 && (
              <div className="proto-rel-lines">
                {(relationsSource === 'local' ? relations.lines : ((serverRelations?.relations) || [])).map((l: any, i: number) => (
                  <div key={i} className="proto-rel-line">🔗 <b>{l.from || l.role}</b> —— {l.clue || l.clueId}：{String(l.reason || l.text || '').slice(0, 40)}{l.reason ? '' : '…'}</div>
                ))}
              </div>
            )}
          </div>
          <div className="proto-pane-note">🔧 API 请求链路追踪（TraceDrawer）在 ⚙️ 设置菜单「逻辑链（API 追踪）」</div>
        </div>
      )}

      {/* ═══ Tab 角色库：本局角色卡（渐变头像 + 心锁标记） ═══ */}
      {tab === 'roles' && (
        <div className="proto-pane">
          <div className="proto-pane-label">角色库 · 本局 {roles.length} 人</div>
          {roles.map(r => {
            // P-0816-R：心锁标记优先服务端（store.scriptLocks，API-3），不可用时回退本地推导
            const serverLock = Array.isArray((store.scriptLocks as any)?.locks)
              ? (store.scriptLocks as any).locks.find((l: any) => l.role === r) : null;
            const localLock = locks.find(l => l.role === r);
            const lock = serverLock
              ? { count: serverLock.unlocked ? 0 : Number(serverLock.lock_count ?? 0), unlocked: !!serverLock.unlocked }
              : { count: localLock ? localLock.count : 0, unlocked: false };
            return (
              <div key={r} className="proto-role-card">
                <span className="proto-role-avatar" style={{ background: avatarGradientFor(r) }}>{r[0] || '?'}</span>
                <div className="proto-role-info">
                  <div className="proto-role-name">
                    {r}
                    {r === myRole && <span className="proto-role-tag">你</span>}
                    {lock.unlocked
                      ? <span className="proto-lock-unlocked" title="该角色心锁已解开">🔓</span>
                      : lock.count > 0 && <span className="proto-lock-badges" title={`心锁 ${lock.count} 把（API-3 服务端推导）`}>{'🔒'.repeat(lock.count)}</span>}
                  </div>
                  <div className="proto-role-sub">
                    {trusteeSet.has(r) ? '🤖 托管（AI 代管）' : r === myRole ? '我的角色' : '玩家'}
                  </div>
                </div>
              </div>
            );
          })}
          {roles.length === 0 && <div className="proto-pane-hint">角色尚未分配（剧本生成中）</div>}
        </div>
      )}

      {/* ═══ Tab 历史：HistoryPanel 内嵌（既有历史会话组件，L11） ═══ */}
      {tab === 'hist' && (
        <div className="proto-pane">
          <div className="proto-pane-label">历史记录（VN Backlog · 已存会话）</div>
          <HistoryPanel onClose={() => { /* Tab 内嵌：关闭无需动作 */ }} />
        </div>
      )}

      {/* 视图记忆小字（对齐原型 view-note） */}
      <div className="proto-view-note">💾 视图状态已记忆（localStorage）</div>
    </div>
  );
}
