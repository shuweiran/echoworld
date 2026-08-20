/**
 * ScriptDiscussionPanel.tsx — 剧本杀对局页·讨论主区（P-0816-M 按原型重构，ui-proto-v2）
 *
 * 对齐原型 discussion.html 中栏：
 *   ① 头部：💬 讨论阶段 + ⏱ 倒计时（决策 U9：status.phase_elapsed_ms/phase_timeout_ms 本地计时）
 *   ② 快捷动作条（原型 talk-bar）：🔍 质询（引导）/ 📎 出示证据 / 📜 历史记录
 *   ③ P-0818-D：对话流与输入框改由下方 ScriptGalChatPanel（Gal 舞台 + 打字机 + 输入区）承担——
 *      讨论发言逐条在 Gal 舞台播放，「当前发言」操作条承接质询/引用 + 反驳弹药；
 *      本面板只保留讨论头部 + 快捷动作条（阶段色/倒计时/引导不丢，消息不再双处渲染）。
 */
import { useState } from 'react';
import { useLocalCountdown } from './useLocalCountdown';

export interface ScriptDiscussionPanelProps {
  /** GET /api/script/status 玩家视图（含 discussion/roles/my_clues/clues/locations） */
  scriptState: any;
  currentPlayer: string;
  onStartVoting?: () => void;
  busy?: boolean;
}

export function ScriptDiscussionPanel({ scriptState, currentPlayer, onStartVoting, busy = false }: ScriptDiscussionPanelProps) {
  const [showEvPop, setShowEvPop] = useState(false);

  // 倒计时（U9：本地计时，不走 SSE 事件）
  const { remainSec } = useLocalCountdown(
    scriptState?.phase_elapsed_ms,
    scriptState?.phase_timeout_ms,
  );

  return (
    <div className="proto-discuss-main">
      {/* 头部：标题 + 倒计时 */}
      <div className="proto-discuss-head">
        <span className="proto-discuss-title">💬 讨论阶段</span>
        {remainSec != null && (
          <span className={`proto-discuss-countdown${remainSec <= 10 ? ' urgent' : ''}`} title="阶段倒计时（本地计时 U9）">
            ⏱ 剩余 {remainSec}s
          </span>
        )}
        <span className="proto-discuss-sub">{scriptState?.mode === 'chat' ? '简单对话版 · 自由发言' : '自由发言 · 质询矛盾 · 出示证据'}</span>
      </div>

      {/* 快捷动作条（原型 talk-bar） */}
      <div className="proto-talk-bar">
        <span className="proto-talk-label">🎭 快捷动作</span>
        <button className="proto-t-btn" title="质询当前发言：Gal 舞台「当前发言」操作条 🔍 质询（API-5 POST /api/script/press，服务端 pressed 标记 + 目标角色辩解）">
          🔍 质询
        </button>
        {/* 📎 出示证据：P-0816-T（阶段三 API-9）已接通 —— 入口在右栏「线索」Tab 线索卡「📎 出示」按钮
            （点击 → POST /api/script/present → 「🃏 出示：CL-xx」system 行入对话流全员可见） */}
        <button className="proto-t-btn" title="出示证据：右栏「线索」Tab 线索卡上的「📎 出示」按钮（API-9 POST /api/script/present，已接通）" onClick={() => setShowEvPop(v => !v)}>
          📎 出示证据
        </button>
        <button className="proto-t-btn" title="历史记录在右栏「历史」Tab（VN Backlog 语义）">
          📜 历史记录
        </button>
      </div>

      {/* P-0818-D：对话已下移至 Gal 舞台（打字机 + 立绘 + 输入区）；此处仅引导不重复渲染 */}
      <div className="proto-discuss-note">
        💬 讨论对话在下方 Gal 舞台播放：发言请用舞台输入框；质询 / 引用请用「当前发言」操作条。
        {currentPlayer ? `（你的角色：${currentPlayer}）` : ''}
      </div>
      <div className="proto-phase-action">
        <span>讨论结束后进入投票；证据可在右侧「线索」页签出示。</span>
        <button className="btn btn-smallall proto-next-phase" disabled={busy || !onStartVoting} onClick={onStartVoting}>
          🗳️ 结束讨论，进入投票
        </button>
      </div>

      {/* 出示证据引导弹层（阶段三已接通：入口在右栏线索卡；此处引导不重复造交互） */}
      {showEvPop && (
        <div className="proto-ev-pop">
          <div className="proto-ev-pop-head">
            📎 出示证据
            <button className="proto-ev-close" onClick={() => setShowEvPop(false)}>✕</button>
          </div>
          <div className="proto-ev-empty">出示证据已接通（API-9 POST /api/script/present + SSE script_present）：请到<b>右栏「线索」Tab</b>，点击线索卡上的「📎 出示」按钮（仅讨论阶段、持有或公开线索可出示）——出示后以「🃏 出示：CL-xx 线索名」系统行插入对话流，全员可见。</div>
        </div>
      )}
    </div>
  );
}
