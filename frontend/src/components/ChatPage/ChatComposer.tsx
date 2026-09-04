/**
 * ChatComposer.tsx — 底部消息输入区（阶段① P-0809-A 拆分自 ChatPage.tsx）
 *
 * 职责：结束按钮 + 文本输入 + 语音输入 + 发送。
 * P0 Gal 收敛：本组件只服务狼人杀（剧本杀走 gal 输入区，一般模式走 GalGeneralView）。
 * 发言路由：
 *   - 狼人杀：乐观渲染（SSE 回显通道）
 *   - 剧本杀 DISCUSSION 阶段：POST /api/script/discussion_say（双通道合并 B1），失败降级 /api/send
 *   - 其他：主控通道 store.sendMessage
 * 自包含（读 useAppStore + api）。
 */
import { useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { api } from '../../api/client';
import { getPhaseGuide, startVoice } from './chatUtils';

export function ChatComposer() {
  const store = useAppStore();
  const [userInput, setUserInput] = useState('');

  const composerPlaceholder = () => {
    if (store.werewolfWaitHuman) return `你是 ${store.currentPlayer}，请发言...`;
    if (store.mode === 'script') return '输入旁白或 @角色名点名 AI（被点名者将强制发言）...';
    return '输入发言...';
  };

  const send = async () => {
    const text = userInput.trim();
    if (!text) return;
    if (store.isRunning) {
      // P0-2：stop 后再 send 不再永久停摆（后端 runRound 对非空会话自动恢复 running。
      await store.stop();
    }
    // P0 Gal 收敛：本组件只服务狼人杀 —— 白天讨论经对局讨论通道（显式 session 身份，
    // 与 galSseAdapter.liveSay 狼人杀分支同源）；一般模式走 Gal，剧本杀走 gal 输入区。
    // 如此 /api/send 不再有无 session 的前端调用者，后端可对其强制 session_id。
    const playerName = store.currentPlayer || '';
    if (!store.werewolfSessionId || !store.werewolfRoleKey) {
      store.addSystemMsg('缺少狼人杀身份凭证，请重新进入对局');
      return;
    }
    // 狼人杀走 SSE 回显通道，保留乐观显示（discussion_say 无 SSE 回显，本地回显即唯一展示）
    store.addAgentMsg(playerName, text);
    try {
      await api.werewolfDiscussionSay(store.werewolfSessionId, playerName, store.werewolfRoleKey, text);
    } catch (e: any) {
      store.addSystemMsg(`发言失败：${e?.message || '未知错误'}`);
    }
    setUserInput('');
  };

  return (
    <div className="composer game-composer">
      {store.werewolfWaitHuman && store.currentPlayer && (
        <div className="wait-human-banner" style={{ gridColumn: '1 / -1', borderRadius: 4, marginBottom: 6 }}>
          🎯 轮到你了！以 <strong>{store.currentPlayer}</strong> 的身份发言
          <div className="ww-sub">
            {getPhaseGuide(store.werewolfPhase, store.werewolfMyRole)}
          </div>
        </div>
      )}
      {/* P0 Gal 收敛：“三轮”按钮（经典一般模式）已删除；一般模式走 Gal 界面点击/输入驱动 */}
      <button className="btn btn-danger round-actions" disabled={!store.isRunning} onClick={() => { store.stop(); }}>结束</button>
      <input value={userInput} onChange={e => setUserInput(e.target.value)} onKeyDown={e => e.key === 'Enter' && send()} placeholder={composerPlaceholder()} />
      <button className="btn btn-icon mic-btn" onClick={() => startVoice(setUserInput)} title="语音输入">🎤</button>
      <button className="btn btn-primary" disabled={!userInput.trim()} onClick={send}>发送</button>
    </div>
  );
}
