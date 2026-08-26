/**
 * WerewolfResumePanel.tsx — 狼人杀恢复对局（重连）面板
 * 阶段① P-0809-A 拆分自 ChatPage.tsx（原「🔄 恢复狼人杀对局」折叠区 + doWerewolfResume）。
 * 自包含：本地状态 + POST /api/werewolf/resume（roleKey 必填防冒充）。
 */
import { useState } from 'react';
import { useAppStore } from '../../../store/appStore';
import { api } from '../../../api/client';
import type { WerewolfPhase } from '../../../types';
import { normalizePhase } from '../chatUtils';

export function WerewolfResumePanel() {
  const store = useAppStore();
  const [open, setOpen] = useState(false);
  const [gameId, setGameId] = useState('');
  const [roomCode, setRoomCode] = useState('');
  const [playerKey, setPlayerKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [info, setInfo] = useState<any>(null);

  const doResume = async () => {
    if (!gameId.trim() && !roomCode.trim()) {
      setInfo({ error: '请输入对局ID（session_id）或房间码' });
      return;
    }
    if (!playerKey.trim()) {
      setInfo({ error: '请输入玩家 roleKey（重连凭证，见下方「我的 roleKey」）' });
      return;
    }
    setBusy(true);
    setInfo(null);
    try {
      const res = await api.werewolfResume({
        session_id: gameId.trim(),
        room_code: roomCode.trim(),
        player: store.currentPlayer,
        player_key: playerKey.trim(),
      });
      if (res?.error) {
        setInfo({ error: res.error });
      } else {
        if (res.session_id) store.setWerewolfSessionId(res.session_id);
        if (res.role_key) store.setWerewolfRoleKey(res.role_key);
        if (res.phase) store.setWerewolfPhase(normalizePhase(res.phase) as WerewolfPhase, res.round);
        if (res.your_role) {
          const wwRoleCn: Record<string, string> = { werewolf: '狼人', wolf: '狼人', seer: '预言家', witch: '女巫', hunter: '猎人', villager: '平民' };
          store.setWerewolfMyRole(wwRoleCn[res.your_role] || res.your_role);
        }
        if (Array.isArray(res.alive)) store.setWerewolfAlive(res.alive);
        if (res.visible && typeof res.visible === 'object') store.setWerewolfVisible(res.visible);
        if (Array.isArray(res.discussion)) store.setWerewolfDiscussion(res.discussion);
        if (res.witch_victim) store.setWerewolfWitchVictim(String(res.witch_victim));
        if (res.winner) store.setWerewolfWinner(res.winner);
        if (res.phase === 'ended' || res.terminal) store.setWerewolfPhase('game_over');
        setInfo({ ok: true, restored: res.restored, phase: res.phase, terminal: res.terminal, winner: res.winner });
      }
    } catch (e: any) {
      setInfo({ error: e.message || '恢复失败' });
    }
    setBusy(false);
  };

  return (
    <div className="ww-panel game-card" style={{ marginTop: 8 }}>
      <div className="ww-panel-header" style={{ cursor: 'pointer' }} onClick={() => setOpen(!open)}>
        <span>🔄 恢复狼人杀对局（重连）</span>
        <span>{open ? '▾' : '▸'}</span>
      </div>
      {open && (
        <div style={{ padding: '8px 10px', fontSize: 12 }}>
          <div style={{ display: 'flex', gap: 4, marginBottom: 4 }}>
            <input
              className="input"
              style={{ flex: 1, fontSize: 12 }}
              placeholder="对局ID（session_id）"
              value={gameId}
              onChange={e => setGameId(e.target.value)}
            />
            <input
              className="input"
              style={{ flex: 1, fontSize: 12 }}
              placeholder="请输入房间码"
              value={roomCode}
              onChange={e => setRoomCode(e.target.value.toUpperCase())}
            />
          </div>
          <input
            className="input"
            style={{ width: '100%', fontSize: 12, marginBottom: 4 }}
            placeholder="玩家 roleKey（必填，重连凭证）"
            value={playerKey}
            onChange={e => setPlayerKey(e.target.value)}
          />
          <div style={{ marginBottom: 4, color: 'var(--text-dim)', fontSize: 11 }}>
            我的 roleKey：<code style={{ wordBreak: 'break-all' }}>{store.werewolfRoleKey || '（未获取，开局后自动发放）'}</code>
          </div>
          <button className="btn btn-small btn-primary" disabled={busy} onClick={doResume}>
            {busy ? '恢复中..' : '恢复'}
          </button>
          {info?.error && <div style={{ color: 'var(--color-danger)', marginTop: 4 }}>⚠️ {info.error}</div>}
          {info?.ok && !info.terminal && (
            <div style={{ color: 'var(--accent)', marginTop: 4 }}>
              ✅ 已恢复（{info.restored ? '从快照重建' : '内存命中'}）｜ 阶段：{info.phase}
            </div>
          )}
          {info?.ok && info.terminal && (
            <div style={{ marginTop: 4 }}>🏁 对局已结束（胜方：{info.winner || '未知'}）</div>
          )}
        </div>
      )}
    </div>
  );
}
