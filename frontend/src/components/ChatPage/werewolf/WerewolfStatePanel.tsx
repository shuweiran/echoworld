/**
 * WerewolfStatePanel.tsx — 狼人杀状态面板（阶段/轮次/我的身份/存活/出局）
 * 阶段① P-0809-A 拆分自 ChatPage.tsx（原 WerewolfStatePanel 函数组件）。
 */
import { PHASE_EMOJI, PHASE_LABEL, ROLE_EMOJI, normalizePhase } from '../chatUtils';

export function WerewolfStatePanel({ phase, round, players, myRole }: {
  phase: string;
  round: number;
  players: { name: string; role: string; alive: boolean; roleRevealed: boolean }[];
  myRole: string;
}) {
  const alive = players.filter(p => p.alive);
  const dead = players.filter(p => !p.alive);
  const p = normalizePhase(phase);
  const phaseEmoji = PHASE_EMOJI[p] || '🎮';

  return (
    <div className="ww-panel game-card">
      {/* Header — current phase */}
      <div className="ww-panel-header">
        <span>{phaseEmoji}</span>
        <span>第 {round} {PHASE_LABEL[p] || '阶段'}</span>
      </div>

      {/* My role */}
      {myRole && (
        <div className="ww-my-role-box">
          <span>{ROLE_EMOJI[myRole] || '🎭'}</span>
          <span>{myRole}</span>
        </div>
      )}

      {/* Alive players */}
      <div className="ww-panel-section">
        <div className="ww-panel-section-title">🟢 存活 ({alive.length})</div>
        <div className="ww-player-list">
          {alive.length === 0 && <div className="muted" style={{ fontSize: 11, padding: '0 10px' }}>暂无</div>}
          {alive.map(p => (
            <div className="ww-player-item" key={p.name}>
              <span className="ww-dot alive" />
              <span className="ww-name">{p.name}</span>
              <span className="ww-role">{p.roleRevealed ? p.role : ''}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Dead players */}
      <div className="ww-panel-section">
        <div className="ww-panel-section-title">❌ 已出局 ({dead.length})</div>
        <div className="ww-player-list">
          {dead.length === 0 && <div className="muted" style={{ fontSize: 11, padding: '0 10px' }}>暂无</div>}
          {dead.map(p => (
            <div className="ww-player-item" key={p.name}>
              <span className="ww-dot dead" />
              <span className="ww-name dead">{p.name}</span>
              <span className={`ww-role ${p.roleRevealed ? 'revealed' : ''}`}>
                {p.roleRevealed ? p.role : '???'}
              </span>
            </div>
          ))}
        </div>
      </div>

      {/* Round counter */}
      <div style={{ padding: '4px 10px', fontSize: 11, color: 'var(--text-3)' }}>
        轮次 #{round}
      </div>
    </div>
  );
}
