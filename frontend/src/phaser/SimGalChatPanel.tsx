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
import { useAutoPlaybackDone } from '../gal/useAutoPlaybackDone';
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
  /** P-0814-B：groupInfo 镜像（liveSayOverride 闭包内读最新群 id —— 输入后自动 playback_done 用） */
  const groupInfoRef = useRef(groupInfo);
  groupInfoRef.current = groupInfo;
  /** P-0814-B：首次 worldMsgs 批（挂载回放旧历史）不武装推进——仅新到达的消息武装（收紧武装条件） */
  const firstBatchRef = useRef(true);
  /** P-0814-A/B：自动推进 —— 本轮「播出完毕待推进」（新消息入队置位；自动推进 hook 消费清除） */
  const [playbackArmed, setPlaybackArmed] = useState(false);
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
    // P-0814-B：2D 输入=点击 —— 发送成功后自动发 playback_done（group_id 路径）：
    // 组在等待态时后端 sendUserMessage 已唤醒生成回复轮（输入即推进）；此信号再推进一轮
    // （AI 续接）；后端信号计数幂等（每信号至多一轮），组不存在/不在等待则 no-op。
    st.setLiveSayOverride((t: string) => {
      return sendRef.current(t).then(() => {
        const gid = groupInfoRef.current?.id;
        if (gid) {
          api.simPlaybackDone({ group_id: gid }).catch((e) =>
            console.warn('simSend 后自动推进失败（2D 组）', e));
        }
      });
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
    let enqueued = false;
    for (const m of worldMsgs) {
      if (!m || !m.text || !m.who) continue;
      if (!shouldShowWorldMsg(m.group, currentGroupId)) continue;
      if (seenRef.current.has(m.id)) continue;
      seenRef.current.add(m.id);
      st.liveEnsureSpeaker(m.who);
      st.liveEnqueue({ kind: 'agent', speakerId: m.who, name: m.who, text: m.text });
      enqueued = true;
    }
    // P-0814-A/B：新消息入队即武装「自动推进」（一轮播完即停；后端幂等防重复推进）。
    // 收紧武装条件：首次批（挂载回放旧历史）不武装——仅新到达的世界消息武装（防重挂载误推进）。
    if (enqueued && !firstBatchRef.current) setPlaybackArmed(true);
    firstBatchRef.current = false;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [worldMsgs, groupInfo?.id]);

  // P-0814-C：加入/离开群（groupInfo.id 变化）时清除武装——仅武装当前群新消息，
  // 防「入组前残留的旧武装」在入组瞬间误触发一轮
  useEffect(() => {
    setPlaybackArmed(false);
  }, [groupInfo?.id]);

  // P-0814-C：自动推进（删「▶ 推进下一轮」按钮）——组内播放完毕（Gal 队列排空）自动
  // POST /api/simulation/playback_done（group_id 路径）→ 后端生成下一轮。触发点是队列排空
  // 事件，不是定时器；节奏由播放速度天然控制。仅玩家所在组（groupInfo）启用：未入组的
  // 世界消息仅展示不推进（组无玩家时由后端等待超时解散兜底）。
  useAutoPlaybackDone({
    enabled: !!groupInfo,
    armed: playbackArmed,
    drained: !liveQueue.length && !typing && !current,
    groupId: groupInfo?.id,
    onAdvancing: () => setPlaybackArmed(false),
    onAdvanceFailed: () => {
      // 失败延迟重新武装（2s 后重试）——2D 组无轮询兜底，重试即恢复机制
      setTimeout(() => setPlaybackArmed(true), 2000);
    },
  });

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
        🎮 Gal 式对话 · 点击对话框继续（再次点击 NPC 可发起新对话 · 「🚪 退出对话」回到探索）
      </div>
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
        <GalDialogBox />
      </div>
      <div style={{ flexShrink: 0, paddingTop: 6 }}>
        <GalChoicesArea />
        <GalInputArea />
      </div>
    </div>
  );
}
