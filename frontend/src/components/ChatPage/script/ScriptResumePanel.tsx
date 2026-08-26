/**
 * ScriptResumePanel.tsx — 剧本杀恢复对局（重连）面板
 * 阶段① P-0809-A 拆分自 ChatPage.tsx（原「🔄 恢复对局（重连）」折叠区 + doScriptResume）。
 * 自包含：本地状态 + POST /api/script/resume（roleKey 认证；ENDED 终态结果卡）。
 */
import { useState } from 'react';
import { useAppStore } from '../../../store/appStore';
import { api } from '../../../api/client';

export function ScriptResumePanel() {
  const store = useAppStore();
  const [open, setOpen] = useState(false);
  const [gameId, setGameId] = useState('');
  const [roomCode, setRoomCode] = useState('');
  const [playerKey, setPlayerKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [info, setInfo] = useState<any>(null);

  const doResume = async () => {
    const body: { game_id?: string; room_code?: string; player_key?: string } = {};
    if (gameId.trim()) body.game_id = gameId.trim();
    else if (roomCode.trim()) body.room_code = roomCode.trim().toUpperCase();
    if (playerKey.trim()) body.player_key = playerKey.trim();
    if (!body.game_id && !body.room_code) { setInfo({ error: '请输入对局ID（session_id）或房间码' }); return; }
    if (!body.player_key) { setInfo({ error: '请输入玩家 roleKey（重连凭证）' }); return; }
    setBusy(true);
    setInfo(null);
    try {
      const res = await api.scriptResume(body);
      if (res.error) {
        setInfo({ error: res.error });
      } else {
        store.setScriptState(res);
        if (res.phase) store.setScriptPhase(res.phase);
        // P-0802-J：恢复后回写对局 session_id（SSE 会话定向连接。
        if (res.session_id) store.setScriptSessionId(res.session_id);
        setInfo({
          ok: true,
          player: res.player,
          phase: res.phase,
          restored: res.restored === true,
          terminal: res.terminal === true,
          murderer: res.murderer,
          correct: res.correct,
          truth: res.truth,
          votes: res.votes,
          winner: res.winner,
        });
      }
    } catch (e: any) {
      setInfo({ error: e.message || '恢复失败' });
    }
    setBusy(false);
  };

  return (
    <div className="ww-panel game-card" style={{ marginBottom: 8 }}>
      <div className="ww-panel-header" style={{ cursor: 'pointer' }} onClick={() => setOpen(!open)}>
        <span>🔄 恢复对局（重连）</span>
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
          <div style={{ display: 'flex', gap: 4, marginBottom: 6 }}>
            <input
              className="input"
              style={{ flex: 1, fontSize: 12 }}
              placeholder="玩家 roleKey（重连凭证，DM 面板可查）"
              value={playerKey}
              onChange={e => setPlayerKey(e.target.value)}
            />
            <button className="btn btn-smallall btn-primary" disabled={busy} onClick={doResume}>
              {busy ? '恢复中..' : '恢复'}
            </button>
          </div>
          {info?.error && <div style={{ color: '#ff5252', marginBottom: 4 }}>⚠️ {info.error}</div>}
          {info?.ok && !info.terminal && (
            <div style={{ color: 'var(--accent)', marginBottom: 4 }}>
              ✅ 已恢复玩家<strong>{info.player}</strong> 的视图（{info.restored ? '从快照重建' : '内存命中'}）｜ 阶段：{info.phase}
            </div>
          )}
          {info?.ok && info.terminal && (
            <div className="card" style={{ marginTop: 4, padding: 8, background: 'var(--bg-2)', lineHeight: 1.6, color: 'var(--text-2)' }}>
              <div style={{ marginBottom: 4 }}>🏁 <strong>对局已结束</strong>（终态结果）</div>
              <div>被定罪：{info.winner || '未知'}</div>
              <div>真凶：{info.murderer || '未识别'}</div>
              <div>判定：{info.correct === true ? '✅ 成功找到真凶' : info.correct === false ? '❌ 冤枉了好人' : '—'}</div>
              {info.truth && <div style={{ marginTop: 4 }}><strong>真相：</strong>{info.truth}</div>}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
