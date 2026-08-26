/**
 * ScriptGameInfoBar.tsx — 剧本杀「对局信息条」（P-0815-F 批3 方向5）
 *
 * 把散落的信息统一为一种「对局信息条」：
 *  - 连接状态（原旁路条 ScriptGalChatPanel 内的连接 chip）
 *  - 阶段 + 倒计时（原 ChatMessageFlow 剧本杀阶段横幅）
 *  - 我的角色 / 秘密 / 线索数（原旁路条 chips）
 *
 * 放置：ScriptGalChatPanel 顶部（GalChatStage header 位），ChatMessageFlow 阶段横幅
 * 删除（信息收敛进本信息条，避免「阶段信息 4 处重复」——批2 已收敛为 2 处，本批并成 1 处）。
 */
import { useGalStore } from './GalStore';
import { SCRIPT_PHASE_EMOJI, SCRIPT_PHASE_LABEL } from '../components/ChatPage/chatUtils';

export interface ScriptGameInfoBarProps {
  /** 剧本杀对局状态（阶段/倒计时/角色/秘密/线索；ScriptGalChatPanel scriptState prop 透传） */
  scriptState?: any;
}

export function ScriptGameInfoBar({ scriptState }: ScriptGameInfoBarProps) {
  const liveStatus = useGalStore(s => s.liveStatus);
  const phase = scriptState?.phase || '';
  const yourSecret = scriptState?.your_secret ? String(scriptState.your_secret) : '';
  const myClues = Array.isArray(scriptState?.my_clues) ? scriptState.my_clues : [];
  const roleName = scriptState?.your_role || '';

  // 阶段倒计时（原 ChatMessageFlow 阶段横幅逻辑）
  const hasCountdown = scriptState?.phase_timeout_ms > 0 && !['ended', 'reveal'].includes(phase);
  const countdownSecs = hasCountdown && scriptState?.phase_elapsed_ms != null
    ? Math.max(0, Math.ceil((scriptState.phase_timeout_ms - scriptState.phase_elapsed_ms) / 1000))
    : null;

  const show = !!(phase || yourSecret || myClues.length > 0 || roleName || (liveStatus && liveStatus !== 'open'));
  if (!show) return null;

  return (
    <div className="script-gal-info">
      {liveStatus && liveStatus !== 'open' && (
        <span className={`script-gal-chip${liveStatus === 'reconnecting' ? ' script-gal-chip-warn' : ''}`}>
          {liveStatus === 'connecting' ? '🟡 连接中…' : liveStatus === 'reconnecting' ? '🔴 连接中断，重连中…' : '⚪ 未连接'}
        </span>
      )}
      {phase && (
        <span className="script-gal-chip script-gal-chip-phase" title={SCRIPT_PHASE_LABEL[phase] || phase}>
          {SCRIPT_PHASE_EMOJI[phase] || '🎮'} {SCRIPT_PHASE_LABEL[phase] || phase}
          {countdownSecs != null && (
            <span style={{ marginLeft: 4, color: 'var(--phase-discussion)', fontSize: 11 }}>⏱ {countdownSecs}s 后推进</span>
          )}
        </span>
      )}
      {roleName && <span className="script-gal-chip">🧑‍🤝‍🧑 {roleName}</span>}
      {yourSecret && (
        <span className="script-gal-chip script-gal-chip-secret" title={yourSecret}>
          🕵️ 秘密：{yourSecret.length > 24 ? yourSecret.slice(0, 24) + '…' : yourSecret}
        </span>
      )}
      {myClues.length > 0 && <span className="script-gal-chip">🔍 线索 ×{myClues.length}</span>}
    </div>
  );
}
