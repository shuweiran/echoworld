import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';

/**
 * C4: 剧本杀主持人（DM）面板 —— 对齐通用剧本杀范式 Chronos 的 state:dm_dashboard + dm:advance。
 *
 * <p>能力：
 * <ul>
 *   <li>对局概览：phase / round / 剧本名 / 审批门状态（approval_status）</li>
 *   <li>玩家表（DM 全量不脱敏）：角色 / 秘密 / AP / 线索数 / 投票状态 / roleKey（可复制分发）</li>
 *   <li>手动推进（dm:advance）：INVESTIGATION→DISCUSSION→VOTE→REVEAL→ENDED；
 *       VOTE 步经 D7 审批门（阻塞等待）——面板同时提供「批准 / 驳回」按钮，DM 推进后立即审批</li>
 *   <li>DM key 越权保护：roleplay.game.dm.key 配置非空时需填 DM key（X-DM-Key 请求头）</li>
 * </ul>
 */
const SCRIPT_PHASE_LABEL: Record<string, string> = {
  setup: '准备阶段', investigation: '搜证阶段', discussion: '讨论阶段',
  vote: '投票阶段', reveal: '揭晓阶段', ended: '已结束',
};
const SCRIPT_PHASE_EMOJI: Record<string, string> = {
  setup: '🎭', investigation: '🔍', discussion: '🗣️', vote: '🗳️', reveal: '🎬', ended: '🏁',
};

export function ScriptDmPanel({ sessionId, onClose }: { sessionId: string; onClose: () => void }) {
  const [dmKey, setDmKey] = useState(localStorage.getItem('roleplay_dm_key') || '');
  const [dm, setDm] = useState<any>(null);
  const [playerKeys, setPlayerKeys] = useState<Record<string, string>>({});
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState('');
  const [showSecrets, setShowSecrets] = useState(false);
  const aliveRef = useRef(true);

  const refresh = async () => {
    if (!sessionId) { setErr('缺少 session_id（请先创建/恢复剧本杀对局）'); return; }
    try {
      const [status, keys] = await Promise.all([
        api.scriptDmStatus(sessionId, dmKey || undefined),
        api.scriptKeys(sessionId, dmKey || undefined),
      ]);
      if (aliveRef.current) {
        setDm(status);
        setPlayerKeys(keys.player_keys || {});
        setErr(status.error ? formatDmError(status.error) : '');
      }
    } catch (e: any) {
      if (aliveRef.current) setErr(formatDmError(e.message || 'DM 状态获取失败'));
    }
  };

  // 挂载时刷新 + 3s 轻轮询（捕捉讨论结束自动进 VOTE / 审批后阶段流转）
  useEffect(() => {
    aliveRef.current = true;
    refresh();
    const t = setInterval(refresh, 3000);
    return () => { aliveRef.current = false; clearInterval(t); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  const saveDmKey = (v: string) => {
    setDmKey(v);
    if (v) localStorage.setItem('roleplay_dm_key', v);
    else localStorage.removeItem('roleplay_dm_key');
  };

  const doAdvance = async () => {
    if (!sessionId) return;
    setBusy(true);
    setErr('');
    setMsg('');
    try {
      const r = await api.scriptAdvance(sessionId, dmKey || undefined);
      if (r.error) setErr(`⚠️ ${r.error}`);
      else setMsg(`✅ 已推进 → ${SCRIPT_PHASE_LABEL[r.phase] || r.phase}${r.message ? `（${r.message}）` : ''}`);
      await refresh();
    } catch (e: any) {
      setErr(formatDmError(e.message || '推进失败'));
    }
    setBusy(false);
  };

  const doApprove = async () => {
    setBusy(true); setErr(''); setMsg('');
    try {
      await api.approvalApprove(sessionId);
      setMsg('✅ 已批准揭晓');
      await refresh();
    } catch (e: any) { setErr(formatDmError(e.message || '批准失败')); }
    setBusy(false);
  };

  const doReject = async () => {
    setBusy(true); setErr(''); setMsg('');
    try {
      await api.approvalReject(sessionId);
      setMsg('已驳回揭晓（回滚至投票阶段）');
      await refresh();
    } catch (e: any) { setErr(formatDmError(e.message || '驳回失败')); }
    setBusy(false);
  };

  const copyKey = async (player: string, key: string) => {
    try {
      await navigator.clipboard.writeText(key);
      setCopied(player);
      setTimeout(() => setCopied(''), 1500);
    } catch { /* clipboard 不可用时忽略 */ }
  };

  const phase = dm?.phase || '';
  const isVoteAdvancing = phase === 'vote';
  const approvalPending = dm?.approval_status === 'pending';

  return (
    <div className="ww-panel" style={{ minWidth: 420 }}>
      <div className="ww-panel-header">
        <span>🎛 主持人面板（DM）</span>
        <button className="btn btn-small" onClick={onClose}>✕</button>
      </div>

      {/* 连接：session_id + DM key（可选越权保护） */}
      <div style={{ padding: '8px 10px', borderBottom: '1px solid var(--border)' }}>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginBottom: 6 }}>
          <input
            className="input"
            style={{ flex: 1, fontSize: 12 }}
            placeholder="session_id（默认当前对局）"
            defaultValue={sessionId}
            disabled
          />
          <button className="btn btn-small" disabled={busy} onClick={refresh}>刷新</button>
        </div>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <input
            className="input"
            style={{ flex: 1, fontSize: 12 }}
            type="password"
            placeholder="DM key（后端配置 roleplay.game.dm.key 时必填；未配置留空）"
            value={dmKey}
            onChange={e => saveDmKey(e.target.value)}
          />
          <button className="btn btn-small" disabled={busy} onClick={refresh}>连接</button>
        </div>
        {/* P-0815-F 批2（方向3）：DM key 首屏说明 —— 与「🔑 角色令牌」区分概念（报告 §4.3 缺口） */}
        <div style={{ marginTop: 6, fontSize: 11, color: 'var(--text-3)', lineHeight: 1.6 }}>
          🎛 <strong>DM key</strong>：后端配置 <code>roleplay.game.dm.key</code> 时的主持人口令（<strong>留空 = 未启用</strong>，直接连接即可）；
          与下方「🔑 角色令牌」（玩家重连凭证，每个玩家一个）是两个不同概念。
        </div>
      </div>

      {/* 对局概览 */}
      {dm && !dm.error && (
        <div style={{ padding: '8px 10px' }}>
          <div className="section-row" style={{ justifyContent: 'space-between', marginBottom: 4 }}>
            <strong>《{dm.name}》</strong>
            <span className="status-pill">{SCRIPT_PHASE_EMOJI[phase] || '🎮'} {SCRIPT_PHASE_LABEL[phase] || phase} · 第 {dm.round ?? 1} 轮</span>
          </div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', fontSize: 12, color: 'var(--text-2)', marginBottom: 4 }}>
            <span>角色 {dm.roles?.length ?? 0}</span>
            <span>地点 {dm.locations?.length ?? 0}</span>
            <span>线索 {dm.clues?.length ?? 0}</span>
            <span>玩家 {dm.players?.length ?? 0}</span>
            <span>凶手 {dm.killer_id || '未识别'}</span>
            {approvalPending && <span className="status-pill warn">⏳ 揭晓待审批</span>}
          </div>
          {dm.truth && (
            <div className="card" style={{ marginTop: 4, padding: 8, fontSize: 12, background: 'var(--bg-2)', color: 'var(--text-2)', lineHeight: 1.5 }}>
              🕵️ <strong>真相</strong>（DM 可见）：{dm.truth}
            </div>
          )}
        </div>
      )}

      {/* 玩家表（DM 全量） */}
      {dm && !dm.error && dm.players && (
        <div className="ww-panel-section">
          <div className="ww-panel-section-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span>👥 玩家表（{dm.players.length}）</span>
            <button className="btn btn-small" onClick={() => setShowSecrets(!showSecrets)}>
              {showSecrets ? '隐藏秘密' : '显示秘密'}
            </button>
          </div>
          <div style={{ padding: '0 10px 8px', fontSize: 12 }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ color: 'var(--text-3)', textAlign: 'left' }}>
                  <th style={{ padding: '2px 4px' }}>玩家</th>
                  <th style={{ padding: '2px 4px' }}>角色</th>
                  <th style={{ padding: '2px 4px' }}>AP</th>
                  <th style={{ padding: '2px 4px' }}>线索</th>
                  <th style={{ padding: '2px 4px' }}>投票</th>
                </tr>
              </thead>
              <tbody>
                {dm.players.map((p: any) => (
                  <tr key={p.name} style={{ borderTop: '1px solid var(--border)' }}>
                    <td style={{ padding: '4px' }}>
                      <strong>{p.name}</strong>
                      <div style={{ fontSize: 11, color: 'var(--text-3)', display: 'flex', gap: 4, alignItems: 'center' }}>
                        <code style={{ maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{p.role_key || '—'}</code>
                        {p.role_key && (
                          <button className="btn btn-small" style={{ padding: '0 4px', fontSize: 10 }} onClick={() => copyKey(p.name, p.role_key)}>
                            {copied === p.name ? '✅' : '复制'}
                          </button>
                        )}
                      </div>
                    </td>
                    <td style={{ padding: '4px' }}>{p.role}</td>
                    <td style={{ padding: '4px' }}>⚡{p.ap}/{p.ap_max}</td>
                    <td style={{ padding: '4px' }}>{p.clue_count}</td>
                    <td style={{ padding: '4px' }}>{p.voted ? `🗳 ${p.vote}` : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {showSecrets && (
              <div style={{ marginTop: 6, fontSize: 12, lineHeight: 1.6, color: 'var(--text-2)' }}>
                {dm.players.map((p: any) => (
                  <div key={p.name}>🔒 <strong>{p.name}</strong>（{p.role}）：{p.secret || '无秘密'}</div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* roleKey 分发列表（GET /api/script/keys 数据源） */}
      {!dm?.error && Object.keys(playerKeys).length > 0 && (
        <div className="ww-panel-section">
          <div className="ww-panel-section-title">🔑 角色令牌（roleKey 分发 · 重连凭证）</div>
          <div style={{ padding: '0 10px 8px', fontSize: 12 }}>
            {Object.entries(playerKeys).map(([player, key]) => (
              <div key={player} style={{ display: 'flex', gap: 6, alignItems: 'center', marginTop: 4 }}>
                <span style={{ width: 70, flexShrink: 0 }}><strong>{player}</strong></span>
                <code style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 11 }}>{key}</code>
                <button className="btn btn-small" style={{ padding: '0 6px', fontSize: 11 }} onClick={() => copyKey(player, key)}>
                  {copied === player ? '✅ 已复制' : '复制'}
                </button>
              </div>
            ))}
            <div style={{ marginTop: 6, fontSize: 11, color: 'var(--text-3)' }}>
              玩家断线后凭「对局ID + 自己的 roleKey」在「恢复对局」入口重连；错误 key 将被 403 拒绝（防冒充）。
            </div>
          </div>
        </div>
      )}

      {/* 推进 + 审批 */}
      {dm && !dm.error && phase !== 'ended' && (
        <div className="ww-panel-section" style={{ padding: '0 10px 10px' }}>
          <div className="ww-panel-section-title">⏩ 手动推进（dm:advance）</div>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 4 }}>
            <button className="btn btn-small btn-primary" disabled={busy} onClick={doAdvance}>
              {busy ? '⏳ 处理中...' : `推进 → ${SCRIPT_PHASE_LABEL[nextPhase(phase)] || '下一步'}`}
            </button>
            {approvalPending && (
              <>
                <button className="btn btn-small" disabled={busy} onClick={doApprove}>✅ 批准揭晓</button>
                <button className="btn btn-small btn-danger" disabled={busy} onClick={doReject}>驳回重投</button>
              </>
            )}
          </div>
          {isVoteAdvancing && !approvalPending && (
            <div style={{ marginTop: 6, fontSize: 11, color: 'var(--text-3)' }}>
              投票阶段推进 = 揭晓判定，将进入 D7 审批门（面板出现「✅ 批准揭晓 / 驳回重投」后由主持人裁决；超时自动驳回回滚）。
            </div>
          )}
        </div>
      )}

      {/* ENDED 终态 */}
      {dm && phase === 'ended' && (
        <div className="ww-panel-section" style={{ padding: '0 10px 10px' }}>
          <div className="ww-panel-section-title">🏁 终局（DM 视图）</div>
          <div style={{ fontSize: 12, lineHeight: 1.6, color: 'var(--text-2)' }}>
            <div>被定罪：{dm.winner || '无'}</div>
            <div>真凶：{dm.murderer || '未识别'}</div>
            <div>判定：{dm.correct === true ? '✅ 成功找到真凶' : dm.correct === false ? '❌ 冤枉了好人' : '—'}</div>
          </div>
        </div>
      )}

      {msg && <div style={{ padding: '0 10px 8px', fontSize: 12, color: 'var(--accent)' }}>{msg}</div>}
      {err && <div style={{ padding: '0 10px 8px', fontSize: 12, color: '#ff5252' }}>{err}</div>}
    </div>
  );
}

/** DM 未连接是常态，不把安全拒绝渲染成看似系统故障的裸 HTTP 403。 */
function formatDmError(raw: string): string {
  const message = String(raw || '');
  if (/403|forbidden|未授权/i.test(message)) {
    return 'ℹ️ 主持人面板未连接：当前部署启用了 DM 鉴权，请输入正确的 DM key；普通玩家不受影响。';
  }
  return '❌ ' + (message || 'DM 请求失败');
}

/** 下一阶段（仅用于按钮文案；实际推进由后端状态机决定） */
function nextPhase(phase: string): string {
  const map: Record<string, string> = {
    setup: 'investigation', investigation: 'discussion', discussion: 'vote',
    vote: 'reveal', reveal: 'ended',
  };
  return map[phase] || 'ended';
}
