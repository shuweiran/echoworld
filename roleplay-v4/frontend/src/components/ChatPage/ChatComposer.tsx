/**
 * ChatComposer.tsx — 底部消息输入区（阶段① P-0809-A 拆分自 ChatPage.tsx）
 *
 * 职责：三轮/结束按钮 + 文本输入 + 语音输入 + 发送。
 * 发言路由（保持原逻辑）：
 *   - 狼人杀：乐观渲染（SSE 回显通道）
 *   - 剧本杀 DISCUSSION 阶段：POST /api/script/discussion_say（双通道合并 B1），失败降级 /api/send
 *   - 其他：主控通道 store.sendMessage
 * 自包含（读 useAppStore + api），autoPlay 行为与拆分前逐字一致。
 */
import { useEffect, useRef, useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { api } from '../../api/client';
import { getPhaseGuide, startVoice } from './chatUtils';

export function ChatComposer() {
  const store = useAppStore();
  const [userInput, setUserInput] = useState('');
  const [autoPlay, setAutoPlay] = useState(false);
  const autoTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Auto-play: when round completes, continue if no human wait needed（保持拆分前行为）
  useEffect(() => {
    if (!autoPlay) return;
    if (store.isRunning) return;
    setAutoPlay(false);
  }, [autoPlay, store.isRunning, store.werewolfWaitHuman, store.werewolfPhase, store.mode]);

  useEffect(() => {
    return () => { if (autoTimerRef.current) clearTimeout(autoTimerRef.current); };
  }, []);

  const startAuto = () => store.startRound(3);

  const effectivePlayer = () => {
    if (store.directorCharacter && store.directorCharacter !== '系统') return store.directorCharacter;
    return '';
  };

  const composerPlaceholder = () => {
    const player = effectivePlayer();
    if (store.werewolfWaitHuman && player) return `你是 ${player}，请发言...`;
    if (store.directorCharacter && store.directorCharacter !== '系统') return `以 ${store.directorCharacter} 的身份发言...`;
    if (store.agents.includes(store.currentPlayer)) return `以 ${store.currentPlayer} 的身份发言...`;
    if (store.mode === 'script') return '输入旁白或 @角色名点名 AI（被点名者将强制发言）...';
    return '输入主控旁白，例如：让苏哲先检查门锁，林诗保持警惕';
  };

  const send = async () => {
    const text = userInput.trim();
    if (!text) return;
    if (store.isRunning) {
      // P0-2：stop 后再 send 不再永久停摆（后端 runRound 对非空会话自动恢复 running。
      await store.stop();
    }
    // Determine player name（P0-1：不再硬编码 'me'，用当前玩家名）
    const isWW = store.mode === 'werewolf';
    const playerName = isWW
      ? store.currentPlayer
      : (store.directorCharacter && store.directorCharacter !== '系统')
        ? store.directorCharacter
        : (store.agents.includes(store.currentPlayer) ? store.currentPlayer : '');
    // P0-2/E6：非狼人杀不再乐观渲染（后端 speaker 命中 agent → SSE user_input → character 回显。
    if (isWW) {
      // 狼人杀走 SSE 回显通道，保留乐观显示
      store.addAgentMsg(playerName, text);
    }
    // P-0805-A（B1）：剧本杀讨论阶段双通道合并 —— composer 发言路由到 discussion_say
    const scriptDiscussion = store.mode === 'script' && store.scriptState?.phase === 'discussion';
    if (scriptDiscussion) {
      const key = (store as any).scriptRoleKey || '';
      try {
        await api.scriptDiscussionSay(playerName || store.currentPlayer || 'me', text, key || undefined);
      } catch (e: any) {
        console.error('discussion_say error:', e);
        await store.sendMessage(text, playerName);
        setUserInput('');
        return;
      }
      setUserInput('');
      return;
    }
    store.sendMessage(text, playerName);
    setUserInput('');
  };

  return (
    <div className="composer game-composer">
      {store.werewolfWaitHuman && effectivePlayer() && (
        <div className="wait-human-banner" style={{ gridColumn: '1 / -1', borderRadius: 4, marginBottom: 6 }}>
          🎯 轮到你了！以 <strong>{effectivePlayer()}</strong> 的身份发言
          <div className="ww-sub">
            {getPhaseGuide(store.werewolfPhase, store.werewolfMyRole)}
          </div>
        </div>
      )}
      {store.mode !== 'werewolf' && <button className="btn round-actions" disabled={store.isRunning} onClick={startAuto}>三轮</button>}
      <button className="btn btn-danger round-actions" disabled={!store.isRunning && !autoPlay} onClick={() => { setAutoPlay(false); store.stop(); }}>结束</button>
      <input value={userInput} onChange={e => setUserInput(e.target.value)} onKeyDown={e => e.key === 'Enter' && send()} placeholder={composerPlaceholder()} />
      <button className="btn btn-icon mic-btn" onClick={() => startVoice(setUserInput)} title="语音输入">🎤</button>
      <button className="btn btn-primary" disabled={!userInput.trim()} onClick={send}>发送</button>
    </div>
  );
}
