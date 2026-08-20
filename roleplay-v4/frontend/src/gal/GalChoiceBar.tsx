/**
 * GalChoiceBar.tsx — 选项条 + 常驻自选输入框（P-0810-02 + P-0810-06 + P-0810-21-D + P-0811-G）
 *
 * P-0811-G（用户 UI 反馈 A-1/A-3/A-5）拆分重构：
 *  - GalChoicesArea：候选/选项区（demo choiceNode + 后端候选 + 前端兜底），
 *    一般模式 live 下统一加「玩家回合」门控（isPlayerTurn）——候选只在轮到玩家时显示
 *    （此前后端候选仅判断 liveMode+非空，AI 说话时也一直显示，用户反馈①）；
 *    独立导出供 GalGeneralStage 放到对话框上方（用户反馈③：选项条在对话框上方）。
 *  - GalInputArea：常驻自选输入框 + 提示行 + 「✅ 已发送」反馈（A-5 感知优化：
 *    增大字号 + 动画 + 发送按钮绿色高亮，不依赖 hidePlayerBubbles 回显）。
 *  - GalChoiceBar：两者组合（向后兼容 demo GalStage / side 布局）。
 *
 * P-0815-D（主人 2026-08-15 12:11 实测「AI+用户双人模式候选/输入无反应 + 选项条反复刷新」）：
 *  ① 候选条闪烁根因 —— isPlayerTurnGate 旧条件 queueLen<=1 在「round_complete 到达前、
 *     本轮第一句 AI 消息停驻（q=1）」时即误判为玩家回合 → 候选条出现；随后同轮其余 AI
 *     消息陆续入队（串行生成逐条广播，q>1）→ 候选条消失。多 AI 轮下反复出现/消失=闪烁。
 *     修复：玩家回合 = 队列完全读空（q===0，任何视图稳定成立）或（本轮播完 roundComplete
 *     已到 + 队列只剩当前句 q===1——round_complete 后同轮不再有新消息顶替，候选条稳定）；
 *     新消息入队（队列尾变化）即清除 roundComplete 标志（防跨轮残留误判）。
 *  ② 发送无反应根因 —— liveSending 在途（LLM 10-40s）时：候选按钮 disabled（点击无效）、
 *     输入框发送按钮 disabled 但 Enter 仍触发 send() → liveSay 守卫静默返回 + 输入框清空
 *     （丢字无反馈=“发送无响应”）。修复：send() 内 guard liveSending/liveSessionId（不吞字），
 *     提示行显示「⏳ 发送中…」；liveSending 不再参与门控（发送中候选保持可见 disabled，
 *     条目不消失，闪烁观感进一步消除）。
 *
 * 选项条：仅当 choiceNode 存在时显示（3-4 个预设选项，demo 模式）。
 * 自选输入框：常驻，随时可打字发言（Enter / 发送按钮）。
 */
import { useState, useEffect, useMemo, useRef } from 'react';
import { useGalStore } from './GalStore';
import { liveSay } from './galSseAdapter';
import { buildLiveChoices } from './galChoices';

/**
 * P-0813-D：实际发言发送器——2D 模拟视图注入 liveSayOverride（走 /api/simulation/send）时优先使用，
 * 否则默认 liveSay（一般模式原行为零变化）。
 */
function useSend() {
  const override = useGalStore(s => s.liveSayOverride);
  return (t: string) => { if (override) return override(t); return liveSay(t); };
}

/**
 * P-0810-23-D1 + P-0811-G：一般模式玩家回合判定（liveMode 等待玩家输入时）。
 * 与前端候选兜底 / 后端候选统一共用——候选仅在轮到玩家时出现（用户反馈①③）。
 *
 * P-0814-G（主会话更正）：**AI 播完即视为轮到玩家** —— 候选直接显示在消息框上方，无需先点击。
 * GalStore 播放状态机：AI 消息经「打字机完成（typing.done=true）→ 用户点击 advance 弹队」两步推进，
 * 旧条件要求队列排空 + current 清空 = 点击弹队后才满足 → 真机「要点击后才出现」（主人 19:44 需求未生效，
 * P-0814-C/E 的 CDP 用 clickThrough 模拟点击验证的是点击后状态）。
 * 新条件：无正在打字机播放的消息（typing 不存在或 done）+ 无排队待播消息（队列至多剩队首当前条）。
 *
 * P-0815-D：玩家回合追加「本轮播完」约束（roundComplete=livePlaybackArmed，round_complete 置位）：
 *  - 旧条件 queueLen<=1 在「本轮第一句停驻、其余 AI 消息仍在串行生成入队」时误判为玩家回合
 *    → 候选条出现后随即被后续消息顶掉（q>1）= 反复刷新/闪烁（主人 12:11 实测）。
 *  - round_complete 在全部 agent_output 广播之后到达 → 到点即代表同轮消息已全部入队，
 *    候选条在「最后一句停驻」时出现后不再有新消息顶替 = 稳定。
 *  - liveSending 不再参与门控：发送中候选保持可见（disabled），条目不消失（闪烁观感消除）。
 */
export function isPlayerTurnGate(opts: {
  liveMode: boolean;
  liveGameType: string;
  liveStatus: string;
  liveSending: boolean;
  queueLen: number;
  typing: { done: boolean } | null;
  /** P-0815-D：本轮「播出完毕待推进」标志（round_complete 到达 = 本轮全部 AI 输出已入队） */
  roundComplete: boolean;
}): boolean {
  const playing = !!opts.typing && !opts.typing.done;
  return opts.liveMode
    && opts.liveGameType === 'general'
    && opts.liveStatus === 'open'
    && !playing
    && (opts.queueLen === 0 || (opts.roundComplete && opts.queueLen <= 1));
}

function usePlayerTurn(): boolean {
  const liveMode = useGalStore(s => s.liveMode);
  const liveGameType = useGalStore(s => s.liveGameType);
  const liveStatus = useGalStore(s => s.liveStatus);
  const liveSending = useGalStore(s => s.liveSending);
  const liveQueue = useGalStore(s => s.liveQueue);
  const typing = useGalStore(s => s.typing);
  const roundComplete = useGalStore(s => s.livePlaybackArmed);
  // P-0815-D：队列尾签名（新消息入队 → 尾 id 变化）——新消息到达即清除「本轮播完」标志。
  // 防跨轮残留：round_complete 置位后若无清除，下一轮首条消息停驻（q=1）会被误判为玩家回合
  // （候选条提前出现 → 下一条消息到达时消失 = 闪烁）。
  const queueTailSig = useGalStore(s => {
    const q = s.liveQueue;
    return q.length > 0 ? String(q[q.length - 1].id) : '';
  });
  const setLivePlaybackArmed = useGalStore(s => s.setLivePlaybackArmed);
  const prevQueueTailRef = useRef(queueTailSig);
  useEffect(() => {
    if (prevQueueTailRef.current !== queueTailSig) {
      prevQueueTailRef.current = queueTailSig;
      setLivePlaybackArmed(false);
    }
  }, [queueTailSig, setLivePlaybackArmed]);
  // P-0815-D：2D 常流式视图（SimGalChatPanel 伪会话 '2d-' 前缀）无 round_complete 概念——
  // 保持旧行为（q<=1 即玩家回合），不误伤 2D DYAD/群聊。
  const liveSessionId = useGalStore(s => s.liveSessionId);
  const is2dStreaming = String(liveSessionId || '').startsWith('2d-');
  return isPlayerTurnGate({
    liveMode,
    liveGameType,
    liveStatus,
    liveSending,
    queueLen: liveQueue.length,
    typing,
    roundComplete: roundComplete || is2dStreaming,
  });
}

/** 最近一条 AI（非系统/非玩家）发言文本 → 提取追问话题 */
function useLastAiText(): string {
  const log = useGalStore(s => s.log);
  const current = useGalStore(s => s.current);
  const typing = useGalStore(s => s.typing);
  return useMemo(() => {
    // P-0814-G：AI 播完停驻（候选出现时机）——当前停驻的 AI 消息尚未入 log（点击才入），
    // 直接取它，否则前端兜底候选话题会滞后一条（后端候选 liveSuggestions 不受影响）。
    if (current && (current as any).kind === 'agent' && typing && typing.done) {
      const t = (current as any).text || '';
      if (t && t.trim()) return t;
    }
    for (let i = log.length - 1; i >= 0; i--) {
      const l = log[i];
      if (l.isPlayer) continue;
      if (l.speakerId === 'system') continue;
      if (l.text && l.text.trim()) return l.text;
    }
    return '';
  }, [log, current, typing]);
}

/**
 * P-0811-G：候选/选项区（demo choiceNode + 后端候选 + 前端兜底）。
 * 一般模式 live 下统一 isPlayerTurn 门控（A-1）——后端候选不再抢占显示。
 */
export function GalChoicesArea() {
  const choiceNode = useGalStore(s => s.choiceNode);
  const choose = useGalStore(s => s.choose);
  const liveMode = useGalStore(s => s.liveMode);
  const liveSending = useGalStore(s => s.liveSending);
  // P-0811-G：导演模式（无玩家）不渲染候选区（防御：即使外部未门控也不显示）
  const livePlayerName = useGalStore(s => s.livePlayerName);
  const hasPlayer = liveMode ? !!livePlayerName && String(livePlayerName).trim().length > 0 : true;
  // P-0810-21-D：玩家发言候选话术（一般模式玩家回合可选项；点击=直接发言）
  const liveSuggestions = useGalStore(s => s.liveSuggestions);

  const isPlayerTurn = usePlayerTurn();
  const lastAiText = useLastAiText();
  // P-0813-D：发送器（override 优先：2D 模拟视图注入时走 simulation send）
  const send = useSend();

  const liveChoices = useMemo(
    () => (isPlayerTurn ? buildLiveChoices(lastAiText) : []),
    [isPlayerTurn, lastAiText],
  );

  // P-0810-23-D1 融合：后端候选（P-0810-21-D /api/round/suggest）优先，前端候选兜底；
  // 用户最终规则「每条 ≤40 字」为硬性约束 → 后端 LLM 候选也按 ≤40 过滤，全过滤则回退前端。
  const safeSuggestions = liveSuggestions.filter(s => Array.from(s).length <= 40);

  return (
    <>
      {/* demo 模式：choiceNode 选项 */}
      {!liveMode && choiceNode && choiceNode.choices && choiceNode.choices.length > 0 && (
        <div className="gal-choices">
          {choiceNode.choices.map((c, i) => (
            <button key={i} className="gal-choice-btn" onClick={() => choose(c)}>
              <span className="gal-choice-arrow">▶</span>
              <span>{c.text}</span>
            </button>
          ))}
        </div>
      )}
      {/* P-0810-21-D：后端 /api/round/suggest 候选（LLM 生成 + 规则兜底）——
          P-0811-G：仅在轮到玩家时显示（用户反馈①：AI 说话时也一直显示）；无玩家不显示 */}
      {liveMode && hasPlayer && isPlayerTurn && safeSuggestions.length > 0 && (
        <div className="gal-choices">
          {safeSuggestions.map((sug, i) => (
            <button key={i} className="gal-choice-btn" disabled={liveSending} onClick={() => {
              // P-0814-G：候选出现时 AI 消息可能仍停驻（播完未点击）——先弹队再发言，
              // 避免发言后旧消息还挡在队列头（新回复需玩家再点一次才播）。
              useGalStore.getState().advance();
              void send(sug);
            }}>
              <span className="gal-choice-arrow">▶</span>
              <span>{sug}</span>
            </button>
          ))}
        </div>
      )}
      {/* P-0810-23-D1：前端候选（类 demo 选择支）——后端候选不可用时的零成本兜底；与后端候选互斥显示防重复 */}
      {liveMode && hasPlayer && isPlayerTurn && safeSuggestions.length === 0 && liveChoices.length > 0 && (
        <div className="gal-choices gal-live-choices">
          <div className="gal-live-choices-label">💬 你可以说：</div>
          {liveChoices.map((c, i) => (
            <button key={i} className="gal-choice-btn" disabled={liveSending} onClick={() => {
              // P-0814-G：同后端候选——先弹队再发言
              useGalStore.getState().advance();
              void send(c);
            }}>
              <span className="gal-choice-arrow">▶</span>
              <span>{c}</span>
            </button>
          ))}
        </div>
      )}
    </>
  );
}

/**
 * P-0811-G：常驻自选输入框 + 提示行 + 「✅ 已发送」反馈（A-5 感知优化）。
 * 独立导出供 GalGeneralStage 放在对话框下方；与 GalChoicesArea 分离。
 */
export function GalInputArea() {
  const liveMode = useGalStore(s => s.liveMode);
  const liveSessionId = useGalStore(s => s.liveSessionId);
  const liveSending = useGalStore(s => s.liveSending);
  const liveSendError = useGalStore(s => s.liveSendError);
  // P-0810-21：最近成功发送时间戳 → 「✅ 已发送」反馈（不依赖 hidePlayerBubbles 回显）
  const liveLastSent = useGalStore(s => s.liveLastSent);
  const liveGameType = useGalStore(s => s.liveGameType);
  const livePhase = useGalStore(s => s.livePhase);
  const submitText = useGalStore(s => s.submitText);
  const choiceNode = useGalStore(s => s.choiceNode);
  // P-0813-D：发送器（override 优先：2D 模拟视图注入时走 simulation send）
  const sendText = useSend();
  // P-0811-G：导演模式（无玩家）不渲染输入框（防御）
  const livePlayerName = useGalStore(s => s.livePlayerName);
  const hasPlayer = liveMode ? !!livePlayerName && String(livePlayerName).trim().length > 0 : true;

  const [text, setText] = useState('');
  const [sentFlash, setSentFlash] = useState(false);

  // 发送成功后闪烁「已发送」4s（时间戳变化即触发；A-5：3s→4s + 动画/高亮）
  useEffect(() => {
    if (!liveLastSent) { setSentFlash(false); return; }
    setSentFlash(true);
    const t = setTimeout(() => setSentFlash(false), 4000);
    return () => clearTimeout(t);
  }, [liveLastSent]);

  const canSend = liveMode ? (!!liveSessionId && !liveSending && !!text.trim()) : !!text.trim();
  const send = () => {
    const t = text.trim();
    if (!t) return;
    if (liveMode) {
      // P-0815-D：发送中（LLM 10-40s 在途）或未连接 → 保留输入文本不吞字、不静默清空
      //（旧行为 Enter 触发 liveSay 守卫静默 return + 输入框清空 = 用户以为发送成功实际丢字）。
      if (liveSending) return;
      if (!liveSessionId) return;
      // P-0814-G：同候选点击——先弹队（AI 播完停驻时），发言后新回复直接入队播放，
      // 旧消息不再挡队头等第二次点击。
      useGalStore.getState().advance();
      void sendText(t);
    } else {
      submitText(t);
    }
    setText('');
  };

  return (
    <>
      {!hasPlayer ? null : (
        <>
      <div className={`gal-input-row${sentFlash ? ' gal-input-row-sent' : ''}`}>
        <span className="gal-input-mark">&gt;</span>
        <input
          className="gal-input"
          value={text}
          onChange={e => setText(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') send(); }}
          placeholder={liveMode
            ? (liveSessionId ? `向对局发言…（${liveGameType === 'script' ? '剧本杀讨论' : liveGameType === 'werewolf' ? '狼人杀讨论' : '一般对话'}${livePhase ? ' · ' + livePhase : ''}）` : '请先在上方连接对局')
            : (choiceNode ? '自由输入你的回答…（与选项并存）' : '随时可以插话…')}
          maxLength={120}
          disabled={liveMode && !liveSessionId}
        />
        <button className={`gal-send-btn${sentFlash ? ' gal-send-btn-sent' : ''}`} onClick={send} disabled={!canSend}>
          {liveSending ? '发送中…' : '发送'}
        </button>
      </div>
      <div className="gal-input-hint">
        {liveMode
          ? (liveSendError
              ? <span className="gal-live-error">✕ 发言失败：{liveSendError}</span>
              : liveSending
                ? <span className="gal-live-sending">⏳ 发送中…（AI 正在生成回复，约 10-40 秒）</span>
                : sentFlash
                  ? <span className="gal-live-sent">✅ 已发送（AI 正听见你说话…）</span>
                  : (liveSessionId ? '发言按对局类型路由（讨论阶段入讨论流 / 其他走一般对话）' : '连接真实对局后可发言'))
          : '选项决定剧情走向 · 输入可自由发言'}
      </div>
        </>
      )}
    </>
  );
}

/** 组合（向后兼容 demo GalStage / side 布局）：候选区 + 输入区 */
export function GalChoiceBar() {
  return (
    <div className="gal-choice-bar">
      <GalChoicesArea />
      <GalInputArea />
    </div>
  );
}
