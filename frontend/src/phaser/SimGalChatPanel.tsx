/**
 * SimGalChatPanel.tsx — 2D 模拟视图的 Gal 式对话区（P-0813-D）
 *
 * 需求：把一般模式（GalGeneralView）的 Gal 式文字聊天（逐条消息、点击继续播放）
 * 迁移进 2D 模式（PhaserSimulationView 右侧面板）——2D 场景与对话区同屏联动。
 *
 * 设计：
 *  - 复用 GalStore live 消息流（liveQueue 队列 + typing 打字机 + advance「▼ 点击继续」）；
 *  - 消息源 = 2D 世界对话流（recentConversations 3s 轮询拍平后的 worldMsgs，由父组件传入），
 *    逐条 liveEnqueue 入队 → GalDialogBox 打字机逐字播放 → 点击继续；
 *  - 玩家发言：注入 liveSayOverride → 父组件 sendText（POST /api/simulation/send/{playerName}，
 *    不走 RouterService 的 api.send）；发送成功后 enqueuePlayerEcho 本地回显（hidePlayerBubbles=false，
 *    玩家消息在 Gal 区可见——玩家角色参与对话）；
 *  - 候选话术（GalChoicesArea）：liveGameType 置 'general' + liveStatus 置 'open' → isPlayerTurn 门控
 *    生效，前端候选（buildLiveChoices）在轮到玩家时显示，点击即发言；
 *  - 对话触发：父组件 SimulationScene.onAgentClick（点击 NPC / 玩家角色自身）→ 展开面板 + 系统提示行
 *    + 自动打招呼（见 PhaserSimulationView.handleAgentClick）。
 */
import { useEffect, useRef, useState } from 'react';
import { useGalStore } from '../gal/GalStore';
import { GalDialogBox } from '../gal/GalDialogBox';
import { GalChoicesArea, GalInputArea } from '../gal/GalChoiceBar';
import { api } from '../api/client';
import { shouldShowWorldMsg } from './simGroupFilter';
import { simChatPlaybackTiming } from './simChatConfig';
import '../gal/gal.css';
import '../gal/galGeneral.css';

export interface SimGalChatPanelProps {
  /** 玩家名（参与对话的角色；空=导演模式仅观看） */
  playerName?: string;
  /** 世界对话消息（已拍平清洗：SimChatMsg[]）——喂入 GalStore 队列（去重入队）。
   *  P-0815-H：每条消息带所属群 id（group 可选字段），群聊模式按当前群过滤入队。 */
  worldMsgs: Array<{ id: string; who: string; text: string; group?: string }>;
  /** 玩家发言发送器（父组件实现：POST /api/simulation/send/{playerName}） */
  sendText: (text: string) => Promise<void>;
  /**
   * P-0813-G：待消费对话行（面板未挂载时点击 NPC/自己产生的系统提示与问候语）。
   * 挂载（enterLiveMode 就绪）后逐条消费：system → liveEnqueue 系统行；send → sendText 代玩家发言。
   * 解决「未对话时面板不渲染 → liveMode=false → 点击 NPC 的问候/提示会丢」的时序问题。
   */
  pendingLines?: Array<{ kind: 'system' | 'send'; text: string }>;
  /** P-0813-G：消费完成回调（父组件从待处理列表移除，防止重渲染重复消费） */
  onConsumedLine?: (line: { kind: 'system' | 'send'; text: string }) => void;
  /**
   * P-0813-K：群聊模式数据（玩家加入的对话群）。非空 → 面板顶部显示群头
   * （群主题/模式 + 成员列表，玩家高亮「你」）；空 → 自由对话模式（旧行为零变化）。
   */
  groupInfo?: { id: string; mode?: string; participants?: string[]; topic?: { description?: string } };
}

/** 伪 session_id 前缀（本面板不依赖后端会话，仅用于 GalStore live 状态机） */
let panelSeq = 0;

export function SimGalChatPanel({ playerName, worldMsgs, sendText, pendingLines, onConsumedLine, groupInfo }: SimGalChatPanelProps) {
  const started = useGalStore(s => s.started);
  const finished = useGalStore(s => s.finished);
  const tick = useGalStore(s => s.tick);
  const liveQueue = useGalStore(s => s.liveQueue);
  const typing = useGalStore(s => s.typing);
  const current = useGalStore(s => s.current);
  /** 已入队消息签名（防 worldMsgs 重渲染重复入队） */
  const seenRef = useRef<Set<string>>(new Set());
  /** sendText 镜像（liveSayOverride 闭包内读最新值） */
  const sendRef = useRef(sendText);
  sendRef.current = sendText;
  const [playbackAdvancing, setPlaybackAdvancing] = useState(false);
  /** 状态更新生效前也必须拒绝双击，后端每个播放信号都可能代表一轮。 */
  const playbackAdvancingRef = useRef(false);
  const playbackTiming = simChatPlaybackTiming(!playerName?.trim());

  // ── 挂载：进入 GalStore live 模式（2D 世界对话流驱动；卸载退出） ──
  useEffect(() => {
    const sid = '2d-' + (++panelSeq) + '-' + Date.now();
    const st = useGalStore.getState();
    st.enterLiveMode(sid, { playerName });
    // 2D 世界无 RouterService SSE：手动置类型/状态使候选与输入门控生效（isPlayerTurn 需 open+general）
    st.setLiveGameType('general');
    st.setLiveStatus('open');
    // 玩家消息可见（玩家角色参与对话；GalGeneralView 的 hidePlayerBubbles=true 语义不适用）
    st.setHidePlayerBubbles(false);
    // 玩家发言路由 → /api/simulation/send（覆盖默认 liveSay 的 RouterService 路径）
    // 玩家输入本身会唤醒后端并生成回复；下一轮仍由 Gal 等待态的点击触发，避免额外自动续轮。
    st.setLiveSayOverride((t: string) => {
      return sendRef.current(t);
    });
    return () => {
      const s2 = useGalStore.getState();
      s2.setLiveSayOverride(undefined);
      s2.setHidePlayerBubbles(false);
      s2.exitLiveMode();
      seenRef.current.clear();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── 打字机定时器：只读取 PlaybackTiming 单一事实源（地图气泡不走此路径） ──
  useEffect(() => {
    if (!started || finished) return;
    const t = setInterval(() => tick(1), playbackTiming.tickMs);
    return () => clearInterval(t);
  }, [started, finished, tick, playbackTiming.tickMs]);

  // ── P-0813-G：消费待处理对话行（挂载 effect 先于本 effect 执行 → liveMode 已就绪） ──
  useEffect(() => {
    if (!pendingLines || pendingLines.length === 0) return;
    const st = useGalStore.getState();
    if (!st.liveMode) return; // 面板尚未完成 live 模式初始化（理论不发生，声明顺序保证）
    for (const line of pendingLines) {
      if (line.kind === 'send') {
        void sendRef.current(line.text); // simSend：POST /api/simulation/send + 本地回显
      } else {
        st.liveEnqueue({ kind: 'system', speakerId: 'system', name: '💬 你', text: line.text });
      }
      onConsumedLine?.(line);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingLines]);

  // ── 世界对话流 → GalStore 队列（去重入队；liveEnsureSpeaker 为未知角色补占位立绘） ──
  useEffect(() => {
    const st = useGalStore.getState();
    if (!st.liveMode) return;
    // 群聊按群过滤：当前面板只阅读已加入的群。自由探索不能旁听全世界的完整台词。
    const currentGroupId = groupInfo?.id;
    for (const m of worldMsgs) {
      if (!m || !m.text || !m.who) continue;
      if (!shouldShowWorldMsg(m.group, currentGroupId)) continue;
      if (seenRef.current.has(m.id)) continue;
      seenRef.current.add(m.id);
      st.liveEnsureSpeaker(m.who);
      st.liveEnqueue({ kind: 'agent', speakerId: m.who, name: m.who, text: m.text });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [worldMsgs, groupInfo?.id]);

  const playbackDrained = !liveQueue.length && !typing && !current;
  // 等待态始终可点击：空轮/被过滤的轮次同样需要玩家显式放行；后端只接受真正 await 中的一次信号。
  const canClickContinue = !!groupInfo && playbackDrained && !playbackAdvancing;
  const continueConversation = () => {
    if (!groupInfo?.id || !canClickContinue || playbackAdvancingRef.current) return;
    playbackAdvancingRef.current = true;
    setPlaybackAdvancing(true);
    api.simPlaybackDone({ group_id: groupInfo.id })
      .then((result: { advanced?: boolean }) => {
        if (!result?.advanced) console.warn('点击推进未就绪，保留对话框供再次点击');
      })
      .catch((e) => {
        console.warn('点击推进 playback_done 失败，请重试', e);
      })
      .finally(() => {
        playbackAdvancingRef.current = false;
        setPlaybackAdvancing(false);
      });
  };

  return (
    <div className="sim-gal-chat" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1 }}>
      {/* P-0813-K：群聊模式头部——群主题/模式 + 成员列表（玩家高亮「你」）；自由对话不显示 */}
      {groupInfo && (
        <div className="sim-gal-group-head">
          <div className="sim-gal-group-title">
            👥 群聊 · {groupInfo.topic?.description || groupInfo.mode || '对话群'}
          </div>
          <div className="sim-gal-group-members">
            {((groupInfo.participants || []) as string[]).map((n, i) => (
              <span key={n} className={`sim-gal-group-member${n === playerName ? ' is-me' : ''}`}>
                {n === playerName ? `${n}（你）` : n}
                {i < (groupInfo.participants?.length ?? 0) - 1 ? '、' : ''}
              </span>
            ))}
          </div>
        </div>
      )}
      <div className="sim-gal-chat-tip" style={{ fontSize: 11, color: 'var(--text-3)', padding: '2px 4px 4px' }}>
        🎮 Gal 式对话 · 每句播放完后点击对话框继续（再次点击 NPC 可发起新对话 · 「🚪 退出对话」回到探索）
      </div>
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
        <GalDialogBox
          onLiveWaitClick={continueConversation}
          liveWaitClickable={canClickContinue}
          liveWaitBusy={playbackAdvancing}
        />
      </div>
      <div style={{ flexShrink: 0, paddingTop: 6 }}>
        <GalChoicesArea />
        <GalInputArea />
      </div>
    </div>
  );
}
