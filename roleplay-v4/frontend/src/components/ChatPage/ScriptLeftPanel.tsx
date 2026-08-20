/**
 * ScriptLeftPanel.tsx — 剧本杀三栏布局·左栏（ui-proto-v2，新增 UI 块）
 *
 * 对齐原型 investigation.html 左栏语义：
 *   - 展开态（240px）：阶段进度条（六态：完成✓ / 当前发光 / 未激活灰）+ 角色列表
 *     （players/roles 数据源 = GET /api/script/status；我的角色高亮；状态点按 U10
 *     前端本地推断——MVP：本人绿点在场，其余灰点；心跳接口 P2 不做）
 *   - 心锁 🔒（决策 U1 阶段二接通）：数据源切换为后端 API-3 GET /api/script/locks
 *     （规则推导过渡 + 终态 LLM 标注 clues[].unlock_role；roleLocks 随快照落库）；
 *     🔓 破锁按钮去禁用 → 点击选择证据（本人持有线索）→ POST /api/script/unlock（API-4）
 *     → 成功更新锁状态（SSE script_locks + 本地 applyUnlockToLocks）；失败 toast 后端 error。
 *     API 不可用时回退本地规则推导（deriveRoleLocks，阶段一过渡口径）。
 *   - 🎭 扮演（P-0816-R 内联化）：window.prompt 改内联表单（输入 roleKey 后恢复并绑定），
 *     绑定成功显示「已扮演：xxx」（bundle 文案资产）。
 *   - 收窄态（56px icon rail）：图标竖排 + tooltip（title），点击图标展开左栏
 *
 * 只读展示组件 + 心锁/扮演交互（破锁/扮演为本栏专属操作，其余面板零改动）。
 */
import { useEffect, useState } from 'react';
import { deriveRoleLocks, applyUnlockToLocks, type LockLike, type ScriptClueLike } from './actionUtils';
import { avatarGradientFor } from './chatUtils';
import { useAppStore } from '../../store/appStore';
import { api } from '../../api/client';
export interface ScriptStateLike {
  phase?: string;
  session_id?: string;
  players?: string[];
  roles?: string[];
  your_role?: string;
  /** P-0816-I（U1）：可见线索（公开 + 本人持有）——心锁本地规则推导数据源（API-3 不可用时回退） */
  clues?: any[];
  /** C2：本人持有的线索对象列表（破锁证据选择数据源） */
  my_clues?: any[];
}

export interface ScriptLeftPanelProps {
  scriptState: ScriptStateLike | null;
  collapsed: boolean;
  onToggle: () => void;
}

const PHASES = ['setup', 'investigation', 'discussion', 'vote', 'reveal', 'ended'] as const;
const PHASE_EMOJI: Record<string, string> = {
  setup: '🎭', investigation: '🔍', discussion: '🗣️', vote: '🗳️', reveal: '🎬', ended: '🏁',
};
const PHASE_LABEL: Record<string, string> = {
  setup: '准备', investigation: '搜证', discussion: '讨论', vote: '投票', reveal: '揭晓', ended: '终局',
};

export function ScriptLeftPanel({ scriptState, collapsed, onToggle }: ScriptLeftPanelProps) {
  const store = useAppStore();
  const phase = scriptState?.phase || 'setup';
  const phaseIdx = PHASES.indexOf(phase as (typeof PHASES)[number]);
  const myRole = scriptState?.your_role || '';
  const boundName = store.boundCharacterName || '';
  const currentPlayer = boundName || myRole || '';
  const players: string[] = Array.isArray(scriptState?.players) ? (scriptState.players as string[]) : [];
  const roles: string[] = Array.isArray(scriptState?.roles) ? (scriptState.roles as string[]) : [];
  const myClues: ScriptClueLike[] = Array.isArray(scriptState?.my_clues) ? (scriptState.my_clues as ScriptClueLike[]) : [];

  // ── 心锁状态：后端 API-3 为准（SSE script_locks + 轮询刷新），API 不可用回退本地规则推导 ──
  const serverLocks: LockLike[] | null = Array.isArray((store.scriptLocks as any)?.locks)
    ? (store.scriptLocks as any).locks as LockLike[]
    : null;
  const locks: LockLike[] = serverLocks ?? deriveRoleLocks(scriptState?.clues, roles).map(l => ({
    role: l.role, lock_count: l.count, unlocked: false,
  }));

  /** P-0816-R：拉取心锁（API-3；阶段/对局变化时刷新；SSE script_locks 增量更新兜底） */
  useEffect(() => {
    if (!currentPlayer) return;
    let alive = true;
    api.scriptLocks(currentPlayer, store.scriptRoleKey || undefined)
      .then(res => { if (alive && res?.ok) store.setScriptLocks(res); })
      .catch(() => { /* 后端未上线/不可用 → 保持本地推导回退，不打断 UI */ });
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPlayer, scriptState?.session_id, scriptState?.phase]);

  // ── 破锁交互（API-4）：unlockRole 打开证据选择 → 选线索 POST /api/script/unlock ──
  const [unlockRole, setUnlockRole] = useState<string | null>(null);
  const [unlockMsg, setUnlockMsg] = useState<string>('');
  const [unlocking, setUnlocking] = useState(false);

  const eligibleIds = new Set<string>(
    (locks.find(l => l.role === unlockRole)?.unlock_clue_ids) || [],
  );

  const doUnlock = async (clueId: string) => {
    if (!unlockRole || unlocking) return;
    setUnlocking(true);
    setUnlockMsg('');
    try {
      const res = await api.scriptUnlock(currentPlayer, unlockRole, clueId, store.scriptRoleKey || undefined);
      if (res?.ok) {
        // 成功：本地合并锁状态（SSE script_locks 也会同步，双通道一致）
        const next = applyUnlockToLocks(locks, unlockRole);
        store.setScriptLocks({ ok: true, locks: next });
        setUnlockMsg(String(res.message || `${unlockRole} 的心锁解开了！`));
        setUnlockRole(null);
      } else {
        window.alert(`破锁失败：${res?.error || '未知错误'}`);
      }
    } catch (e: any) {
      window.alert(`破锁失败：${e?.message || '未知错误'}`);
    } finally {
      setUnlocking(false);
    }
  };

  // ── 🎭 扮演内联表单（P-0816-R：替代 window.prompt，输入 roleKey 可留空 + 确认/取消） ──
  const [playRoleOpen, setPlayRoleOpen] = useState<string | null>(null);
  const [playKeyInput, setPlayKeyInput] = useState('');

  const confirmPlay = async (roleName: string) => {
    const key = playKeyInput.trim();
    if (!key) {
      window.alert('请输入该角色的 roleKey；角色令牌是身份校验凭证，不能再留空绑定。');
      return;
    }
    try {
      const res = await api.scriptResume({ game_id: String(scriptState?.session_id || ''), player_key: key });
      if (res?.error) throw new Error(String(res.error));
      const actual = String(res?.player || res?.your_role || '');
      if (actual !== roleName) throw new Error('角色令牌与所选角色不匹配');
      store.setScriptRoleKey(key);
      store.setBoundCharacterName(actual);
      store.setCurrentPlayer(actual);
      store.setScriptState(res);
      if (res?.session_id) store.setScriptSessionId(String(res.session_id));
      setPlayRoleOpen(null);
      setPlayKeyInput('');
    } catch (e: any) {
      window.alert(`绑定角色失败：${e?.message || '未知错误'}`);
    }
  };
  const cancelPlay = () => { setPlayRoleOpen(null); setPlayKeyInput(''); };

  /** rail 图标集（收窄态竖排 + tooltip；点击展开左栏）——对齐原型 rail：阶段图标 + 角色渐变头像 + 证据袋 */
  const railIcons: Array<{ icon: string; tip: string; grad?: string }> = [
    ...PHASES.map((p, i) => ({ icon: phaseIdx > i ? '✓' : PHASE_EMOJI[p], tip: `${PHASE_LABEL[p]}（${phaseIdx > i ? '已完成' : phaseIdx === i ? '当前' : '待开启'}）` })),
    ...players.slice(0, 8).map(n => ({ icon: n[0] || '?', tip: n, grad: avatarGradientFor(n) })),
    { icon: '🔎', tip: '证据袋（右栏线索 Tab）' },
  ];

  const lockCountOf = (name: string) => locks.find(l => l.role === name)?.lock_count ?? 0;
  const isUnlocked = (name: string) => !!locks.find(l => l.role === name)?.unlocked;

  return (
    <aside className={`proto-left proto-left-script${collapsed ? ' rail' : ''}`}>
      <div className="proto-left-toggle">
        <button
          className="btn btn-smallall proto-side-toggle"
          onClick={onToggle}
          title={collapsed ? '▶ 展开左栏' : '◀ 收窄为图标条（56px）'}
        >
          {collapsed ? '▶' : '◀'}
        </button>
      </div>

      {collapsed ? (
        <div className="proto-left-rail">
          {railIcons.map((it, idx) => (
            <button key={`${it.tip}-${idx}`} className="rail-ico" title={it.tip} onClick={onToggle} style={it.grad ? { background: it.grad } : undefined}>
              {it.icon}
            </button>
          ))}
        </div>
      ) : (
        <>
          {/* 阶段进度：完成✓ / 当前发光 / 未激活灰 */}
          <div className="proto-section">
            <div className="proto-section-label">📌 阶段进度</div>
            <div className="proto-phase-steps">
              {PHASES.map((p, i) => {
                const done = phaseIdx > i;
                const current = phaseIdx === i;
                return (
                  <div key={p} className={`proto-phase-step${current ? ' current' : ''}${done ? ' done' : ''}`}>
                    <span className="proto-phase-dot">{done ? '✓' : PHASE_EMOJI[p]}</span>
                    <span className="proto-phase-name">{PHASE_LABEL[p]}</span>
                  </div>
                );
              })}
            </div>
          </div>

          {/* 角色列表：players ∪ roles 去重展示；本人角色高亮 + 绿点（U10 本地推断）；角色渐变头像（原型 av-*） */}
          <div className="proto-section">
            <div className="proto-section-label">👥 角色（{players.length}）</div>
            <div className="proto-char-list">
              {players.map(name => {
                const isMe = name === myRole;
                const isBound = name === boundName;
                return (
                  <div key={name} className={`proto-char${isMe ? ' me' : ''}`} title={isMe ? `${name}（你）` : name}>
                    <span className="proto-char-avatar" style={{ background: avatarGradientFor(name) }}>{name[0] || '?'}</span>
                    <span className="proto-char-name">{name}</span>
                    {/* P-0816-R：心锁 🔒 标记 —— 后端 API-3 数据源（未解锁角色显示锁数；已解锁置灰） */}
                    {lockCountOf(name) > 0 && (
                      <span className="proto-lock-badges" title={`心锁 ${lockCountOf(name)} 把（API-3 服务端推导）`}>
                        {('🔒').repeat(lockCountOf(name))}
                      </span>
                    )}
                    {isUnlocked(name) && (
                      <span className="proto-lock-unlocked" title="该角色心锁已解开">🔓</span>
                    )}
                    {/* 角色绑定入口（🎭 扮演）——内联表单（输入角色令牌可留空 + 确认/取消） */}
                    <span className={`dot ${isMe ? 'active' : ''}`} />
                    <button
                      className={`proto-play-btn${isBound ? ' bound' : ''}`}
                      title={isBound ? `${name}（已扮演）` : `扮演此角色（输入角色令牌，可留空直接绑定）`}
                      onClick={() => { setPlayRoleOpen(playRoleOpen === name ? null : name); setPlayKeyInput(''); }}
                    >
                      {isBound ? '✓ 已扮演' : '🎭 扮演'}
                    </button>
                    {playRoleOpen === name && (
                      <div className="proto-play-form">
                        <input
                          className="proto-play-input"
                          type="text"
                          placeholder={`角色令牌（可留空）· ${name}`}
                          value={playKeyInput}
                          onChange={e => setPlayKeyInput(e.target.value)}
                          onKeyDown={e => { if (e.key === 'Enter') confirmPlay(name); }}
                          autoFocus
                        />
                        <div className="proto-play-actions">
                          <button className="btn btn-smallall proto-play-confirm" onClick={() => confirmPlay(name)}>确认</button>
                          <button className="btn btn-smallall proto-play-cancel" onClick={cancelPlay}>取消</button>
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
              {/* P-0818-D：只展示「真正的多余角色」（角色数 > 玩家数时的超出部分）。
                  角色数 ≤ 玩家数时不再把整套角色表并列展示——此前 LLM 自造角色名（站长·林远 等）
                  与玩家名（林晚秋 等）按名去重永远失效，5 玩家 + 5 角色显示成 10 个「身份」。 */}
              {(() => {
                const surplus = roles.length - players.length;
                if (surplus <= 0) return null;
                const extras = roles.filter(r => !players.includes(r)).slice(0, surplus);
                if (extras.length === 0) return null;
                return (
                  <>
                    <div className="proto-section-label proto-extra-label">🎭 未分配角色（{extras.length}）</div>
                    {extras.map(r => (
                      <div key={r} className="proto-char proto-char-extra" title={`${r}（本局暂无玩家扮演）`}>
                        <span className="proto-char-avatar" style={{ background: avatarGradientFor(r) }}>{r[0] || '?'}</span>
                        <span className="proto-char-name">{r}</span>
                        {lockCountOf(r) > 0 && (
                          <span className="proto-lock-badges" title={`心锁 ${lockCountOf(r)} 把（API-3 服务端推导）`}>
                            {('🔒').repeat(lockCountOf(r))}
                          </span>
                        )}
                        {isUnlocked(r) && <span className="proto-lock-unlocked" title="该角色心锁已解开">🔓</span>}
                        <button
                          className={`proto-play-btn${r === boundName ? ' bound' : ''}`}
                          title={r === boundName ? `${r}（已扮演）` : '扮演此角色（需输入角色令牌）'}
                          onClick={() => { setPlayRoleOpen(playRoleOpen === r ? null : r); setPlayKeyInput(''); }}
                        >
                          {r === boundName ? '✓ 已扮演' : '🎭 扮演'}
                        </button>
                        {playRoleOpen === r && (
                          <div className="proto-play-form">
                            <input
                              className="proto-play-input"
                              type="text"
                              placeholder={`角色令牌（必填）· ${r}`}
                              value={playKeyInput}
                              onChange={e => setPlayKeyInput(e.target.value)}
                              onKeyDown={e => { if (e.key === 'Enter') confirmPlay(r); }}
                              autoFocus
                            />
                            <div className="proto-play-actions">
                              <button className="btn btn-smallall proto-play-confirm" onClick={() => confirmPlay(r)}>确认</button>
                              <button className="btn btn-smallall proto-play-cancel" onClick={cancelPlay}>取消</button>
                            </div>
                          </div>
                        )}
                      </div>
                    ))}
                  </>
                );
              })()}
            </div>
            {boundName && <div className="proto-play-bound-tip">已扮演：{boundName}</div>}
          </div>

          {/* P-0816-R（阶段二接通）：心锁区 —— 后端 API-3 数据源 + 🔓 破锁（API-4 出示证据） */}
          {locks.length > 0 && (
            <div className="proto-section">
              <div className="proto-section-label">🔒 心锁{serverLocks ? '（API-3）' : '（本地推导回退）'}</div>
              <div className="proto-lock-list">
                {locks.map(l => (
                  <div key={l.role} className="proto-lock-row">
                    <span className="proto-lock-role">{l.role}</span>
                    <span className="proto-lock-badges">
                      {l.unlocked ? '🔓' : ('🔒').repeat(Math.max(0, l.lock_count))}
                    </span>
                    {/* P-0816-R：破锁按钮接通 —— 去禁用；点击打开证据选择（本人持有线索）→ API-4 */}
                    {!l.unlocked ? (
                      <button
                        className="btn btn-smallall proto-lock-unlock"
                        disabled={unlockRole !== null && unlockRole !== l.role}
                        onClick={() => { setUnlockRole(unlockRole === l.role ? null : l.role); setUnlockMsg(''); }}
                        title={unlockRole === l.role ? '收起证据选择' : `出示对应证据破锁（需要本人持有解锁线索）`}
                      >
                        🔓 破锁
                      </button>
                    ) : (
                      <span className="proto-lock-done">已解开</span>
                    )}
                    {unlockRole === l.role && (
                      <div className="proto-lock-evidence">
                        <div className="proto-lock-evidence-head">出示证据（本人持有线索）</div>
                        {myClues.length === 0 ? (
                          <div className="proto-lock-evidence-empty">你还没有任何线索 —— 先去搜证吧</div>
                        ) : (
                          myClues.map(c => {
                            const cid = String(c.id || '');
                            const eligible = eligibleIds.has(cid);
                            return (
                              <button
                                key={cid}
                                className={`proto-lock-evidence-item${eligible ? ' ok' : ''}`}
                                disabled={!eligible || unlocking}
                                onClick={() => doUnlock(cid)}
                                title={eligible
                                  ? `出示「${c.title || cid}」破锁`
                                  : '该线索不是解锁线索（无法解开 TA 的心锁）'}
                              >
                                <span className="proto-lock-evidence-id">{cid}</span>
                                <span className="proto-lock-evidence-title">{c.title || String(c.content || '').slice(0, 18)}</span>
                                {eligible ? <span className="proto-lock-evidence-tag">🔑 可破锁</span> : <span className="proto-lock-evidence-tag no">✕</span>}
                              </button>
                            );
                          })
                        )}
                        {unlockMsg && <div className="proto-lock-unlock-msg">{unlockMsg}</div>}
                      </div>
                    )}
                  </div>
                ))}
              </div>
              <div className="proto-lock-note">
                {serverLocks
                  ? '服务端推导：线索提及/LLM 标注 unlock_role → 该角色 1 锁（出示对应证据破锁，状态随快照落库）'
                  : '本地规则推导（API-3 不可用时回退）：线索内容提及角色名 → 该角色 1 锁'}
              </div>
            </div>
          )}
        </>
      )}
    </aside>
  );
}
