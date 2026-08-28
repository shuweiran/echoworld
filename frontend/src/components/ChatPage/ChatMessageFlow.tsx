/**
 * ChatMessageFlow.tsx — 中间消息流（阶段① P-0809-A 拆分自 ChatPage.tsx）
 *
 * 职责：内嵌 2D 面板 + 公告横幅（SSE announcement 驱动）+ 加载进度条 + 阶段横幅 +
 * 在场状态条 + 对话消息流（流式打字机渲染）+ 任务分配 + TTS 指示 + 底部输入区。
 */
import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { api } from '../../api/client';
import { AnnouncementBanner } from '../AnnouncementBanner';
import { PhaserSimulationView } from '../../phaser/PhaserSimulationView';
import { PhaserScriptMapView } from '../../phaser/PhaserScriptMapView';
const BabylonSimulationView = lazy(() => import('../../babylon/BabylonSimulationView').then(m => ({ default: m.BabylonSimulationView })));
import { ChatComposer } from './ChatComposer';
import { MessageView } from './MessageView';
import { GameAtmosphereBanner } from './GameAtmosphereBanner';
import { ScriptGalChatPanel } from '../../gal/ScriptGalChatPanel';
// P-0816-I（UI 重设计阶段一）：搜证页替换（主区顶部行动条 + 地点卡片网格 + VN 演出）
import { UI_PROTO_V2_ENABLED } from '../../uiProtoV2';
import { ScriptInvestigationPanel } from './ScriptInvestigationPanel';
// P-0816-M（对局页按原型重构）：主区阶段驱动切换（setup 准备区 / discussion 讨论主区 / vote 投票主区 / reveal·ended 揭晓终局）
import { ScriptSetupPanel } from './ScriptSetupPanel';
import { ScriptDiscussionPanel } from './ScriptDiscussionPanel';
import { ScriptVotePanel } from './ScriptVotePanel';
import { ScriptRevealPanel } from './ScriptRevealPanel';
import type { ScriptPanelHandlers } from './ChatRightPanel';
import {
  SCRIPT_PHASE_EMOJI, SCRIPT_PHASE_LABEL, getLoadingText, getPhaseGuide,
  normalizePhase, phaseClassName, PHASE_EMOJI, PHASE_LABEL, colorFor,
} from './chatUtils';

export interface MessageFlowProps {
  showSimPanel: boolean;
  toggleSimPanel: () => void;
  scriptState: any;
  simChars: { name: string; persona: string; voice: string; background: string }[];
  onRollback: (round: number) => void;
  /** P-0819-O：地图搜证/交互完成后刷新剧本状态，联动 Gal、线索与 AP */
  onScriptRefresh?: () => void | Promise<void>;
  /** P-0816-M：剧本杀动作处理器（主区阶段面板：setup 生成 / vote 投票弃票 / reveal 结束·重开·回剧本选择） */
  script?: ScriptPanelHandlers;
}

export function ChatMessageFlow({ showSimPanel, toggleSimPanel, scriptState, simChars, onRollback, onScriptRefresh, script }: MessageFlowProps) {
  const store = useAppStore();
  const [simulation3d, setSimulation3d] = useState(false);
  // P-0819-O 稳定性修复：scriptStatus 每 3s 轮询会重渲染父组件；角色名单必须保持引用稳定，
  // 否则 PhaserScriptMapView 会把每次轮询误判为角色配置变化并重建 Phaser.Game。
  const scriptAiCharacters = useMemo(
    () => simChars.filter(c => c.name !== store.currentPlayer).map(c => c.name),
    [simChars, store.currentPlayer],
  );
  // P-0816-M：proto 模式主区阶段驱动（阶段色由 workspace.phase-* 类驱动，见 ChatPage）
  const protoMain = store.mode === 'script' && UI_PROTO_V2_ENABLED && !!scriptState;
  const scriptPhase = String(scriptState?.phase || store.scriptPhase || '');
  const convRef = useRef<HTMLDivElement>(null);
  /** P-0814-C：轮询兜底武装 —— 已发过 playback_done 信号的轮次（后端 roundCount=最近完成轮）；
   *  后端空闲（status=idle）且 round 推进到未发信号的轮次 → 武装，由自动推进 effect 发信号。
   *  自愈覆盖：SSE round_complete 错过 / 断线重连 / 挂载前轮次已播完（原 mountRound 基准轮 diff 会漏） */
  const lastFiredRoundRef = useRef(0);

  // P-0814-B/C：经典视图武装轮询兜底 —— round_complete SSE 错过（重连/连接前广播完）时，
  // 5s 轮询发现后端「等待播出完毕」（awaiting_playback=true，P-0814-C 后端 /api/state 暴露）且
  // 轮次推进到未发信号轮次即重新武装；武装后由自动推进 effect 发信号（仅一般模式）。
  // P-0814-E：有玩家（currentPlayer 在 agents 中=玩家角色在场）时不武装不推进——一问一答：
  // AI 播完即停等玩家输入（ChatComposer 输入 → api.send → 后端输入即推进）；导演/无玩家仍自动推进。
  useEffect(() => {
    const sid = store.sessionId;
    if (!sid) return;
    let alive = true;
    const check = async () => {
      try {
        const st: any = await api.getState(sid);
        if (!alive) return;
        const round = Number(st?.round ?? 0);
        const awaiting = st?.awaiting_playback === true;
        if (round > 0 && awaiting && round > lastFiredRoundRef.current) {
          const s = useAppStore.getState();
          if (s.mode !== 'script' && s.mode !== 'werewolf') {
            // P-0814-E：有玩家（玩家角色在场）不武装——AI 播完即停等玩家输入，不自动推进
            if (!s.agents.includes(s.currentPlayer)) {
              if (round > s.currentRound) useAppStore.setState({ currentRound: round });
              useAppStore.setState({ playbackArmed: true });
            }
          }
        }
      } catch { /* 后端不可达：忽略 */ }
    };
    void check();
    const t = setInterval(check, 5000);
    return () => { alive = false; clearInterval(t); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [store.sessionId]);

  // P-0802-M：流式增量只改 content 不改 length —— 依赖末尾消息内容才能逐字跟滚
  useEffect(() => {
    if (convRef.current) convRef.current.scrollTop = convRef.current.scrollHeight;
  }, [store.messages.length, store.messages[store.messages.length - 1]?.content]);

  const visibleMessages = useMemo(() => {
    // Werewolf mode: filter messages visible to current human player
    const filterName = store.historyFilter ||
      (store.mode === 'werewolf'
        ? store.currentPlayer
        : (store.directorCharacter && store.directorCharacter !== '系统'
          ? store.directorCharacter
          : null));
    if (!filterName) return store.messages;
    return store.messages.filter(m => {
      if (m.visible_to && m.visible_to.length > 0) {
        return m.visible_to.includes(filterName);
      }
      return true;  // No visibility restriction = public
    });
  }, [store.historyFilter, store.messages, store.mode, store.directorCharacter]);

  // P-0814-C：自动推进（删「▶ 推进下一轮」按钮）——经典视图的「播放完毕」检测点 = round_complete
  //（SSE 已按序送达本轮回 agent_output/agent_token，消息全部渲染完成）；武装后自动 POST
  // /api/simulation/playback_done 驱动下一轮（一般模式无 group_id）。触发点是轮次完成事件，
  // 不是定时器；发信号前同步清除武装并记录已发信号轮次（每轮一次防重复）。
  // ⚠️ 不可在此 effect 内 setTimeout 后同步清 armed：清 armed 触发重渲染 → effect cleanup 会
  // 取消未执行的 timer（P-0814-C 实测：fire 永远不发出、lastFired 提前置位导致永久停摆）——
  // 故直接即时发信号（后端为阻塞式端点，同步生成下一轮期间 UI 有充足时间渲染落定）。
  // 失败 3s 后重新武装重试（轮询兜底 5s 也会重新武装）。
  // P-0814-E：有玩家（currentPlayer 在 agents 中=玩家角色在场）时不自动推进——一问一答：
  // AI 轮播完即停，等玩家输入（ChatComposer 输入 → api.send → 后端 runRound 玩家分支输入即
  // 推进，无需 playback_done）；导演/无玩家模式维持播完自动推进（防卡死）。
  useEffect(() => {
    const sid = store.sessionId;
    if (!store.playbackArmed || !sid) return;
    if (store.mode === 'script' || store.mode === 'werewolf') return;
    if (store.agents.includes(store.currentPlayer)) return; // 有玩家：不自动发信号，停等输入
    useAppStore.setState({ playbackArmed: false });
    const firedRound = useAppStore.getState().currentRound;
    if (firedRound > lastFiredRoundRef.current) lastFiredRoundRef.current = firedRound;
    api
      .simPlaybackDone({ session_id: sid })
      .catch((e) => {
        console.warn('自动推进 playback_done 失败（经典视图，将重试）', e);
        setTimeout(() => useAppStore.setState({ playbackArmed: true }), 3000);
      });
  }, [store.playbackArmed, store.mode, store.sessionId]);

  return (
    <main className="chat-main">
      {/* C-1：内嵌 2D 模拟面板（左地图 + 右聊天，可折叠；剧本杀「查看 2D 模拟」按钮联动开关）
          P-0803-K：简单对话版（scriptState.mode==='chat'）无取证无地图，隐藏整个 2D 讨论面板
          P-0803-M：简单对话版开启「配置地图」后 —— 有地图时放行面板（氛围展示 · 只读），无地图仍隐藏 */}
      {showSimPanel && !(store.mode === 'script' && scriptState?.mode === 'chat' && !scriptState?.map) && (
        <div style={{ marginBottom: 12 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 13, color: 'var(--text-2)' }}>
              {store.mode === 'script' && scriptState?.mode === 'chat' ? '🗺️ 对局地图（氛围展示 · 只读）' : '🗺️ 2D 模拟（左地图 · 右聊天）'}
            </span>
            {store.mode !== 'script' && (
              <button className="btn btn-small" onClick={() => setSimulation3d(v => !v)} title="在同一权威模拟状态上切换 2D/3D 表现层">
                {simulation3d ? '🎮 切回 2D' : '🌐 打开 3D'}
              </button>
            )}
            <button className="btn btn-smallall btn-danger" onClick={toggleSimPanel}>✕ 关闭</button>
          </div>
          {/* P-0803-H2：剧本杀对局显示 2D 模拟显示对局地图（PhaserScriptMapView），而非通用 park 场景 */}
          {/* P-0804-H 续：一般模式进入游戏后也显示当前场景绑定的瓦片地图（有 default_map 用瓦片图，
              无则回退 park 2D 世界）——「登进去地图是没绑定的样子」修复 */}
          {store.mode === 'script' && scriptState?.map ? (
            <PhaserScriptMapView
              map={scriptState.map}
              playerName={store.currentPlayer}
              height={420}
              searchedLocations={Array.isArray(scriptState.searched_locations) ? scriptState.searched_locations : []}
              readOnly={store.mode === 'script' && scriptState?.mode === 'chat'}
              aiCharacters={scriptAiCharacters}
              onActionComplete={onScriptRefresh}
              decorStates={(scriptState.decor_states && typeof scriptState.decor_states === 'object') ? scriptState.decor_states : undefined}
            />
          ) : simulation3d ? (
            <Suspense fallback={<div style={{ padding: 24, color: 'var(--text-2)' }}>正在加载 3D 世界…</div>}>
              <BabylonSimulationView
                map={store.currentSceneMap || undefined}
                playerName={store.currentPlayer}
                height={420}
              />
            </Suspense>
          ) : store.currentSceneMap ? (
            // P-0813-E：一般模式聊天页 2D 视图 —— 只读瓦片图 → 交互式模拟视图
            // （瓦片背景 + 后端障碍注入 + SSE 位置同步 + 点击移动，复用 GameBridge 2D 探索同款链路）
            <PhaserSimulationView
              characters={simChars}
              scene="custom"
              map={store.currentSceneMap}
              playerName={store.currentPlayer}
              height={420}
            />
          ) : (
            <PhaserSimulationView
              characters={simChars}
              scene="park"
              playerName={store.currentPlayer}
              height={420}
            />
          )}
        </div>
      )}

      {/* 公告横幅（SSE announcement 事件驱动；阶段① 激活 useSSE 后横幅队列恢复数据源） */}
      <AnnouncementBanner inline />

      {/* #122：对局信息横幅/氛围区（主题/阶段/轮次/玩家 + 场景氛围 + 操作引导；数据源 useAppStore，空态友好占位） */}
      <GameAtmosphereBanner scriptState={scriptState} />

      {/* Loading progress bar */}
      {store.isRunning && (
        <>
          <div className="loading-bar-wrap">
            <div className="loading-bar-track">
              <div className="loading-bar-fill" />
            </div>
          </div>
          <div className="loading-status-text">
            <span className="spinner" />
            <span>{store.mode === 'script' ? '🎭 剧本杀进行中..' : store.mode === 'werewolf' ? getLoadingText(store.werewolfPhase, store.werewolfMyRole) : '⏳ 运行中...'}</span>
          </div>
        </>
      )}

      {/* Phase guide banner */}
      {store.mode === 'werewolf' && (() => {
        const p = normalizePhase(store.werewolfPhase);
        return (
          <div className={`phase-banner ${phaseClassName(store.werewolfPhase)}`}>
            {PHASE_EMOJI[p] || '🎮'} {store.werewolfRound} {PHASE_LABEL[p] || '阶段'}
            {' · '}{getPhaseGuide(store.werewolfPhase, store.werewolfMyRole)}
          </div>
        );
      })()}

      {/* Script phase banner —— P-0815-F 批2（方向3）：阶段信息单点化，只保留 阶段 + 倒计时；
          操作引导统一由上方 GameAtmosphereBanner guide 承担（SCRIPT_GUIDES 单点源），
          不再与氛围横幅/右侧面板/旁路条重复堆叠。 */}
      {store.mode === 'script' && scriptState && (
        <div className="phase-banner phase-day">
          {SCRIPT_PHASE_EMOJI[scriptState.phase] || '🎮'} {SCRIPT_PHASE_LABEL[scriptState.phase] || scriptState.phase}
          {scriptState.phase_timeout_ms > 0 && !['ended', 'reveal'].includes(scriptState.phase) && (
            <span style={{ marginLeft: 8, color: 'var(--phase-discussion)', fontSize: 12 }}>
              ⏱ 本阶段 {scriptState.phase_elapsed_ms != null
                ? Math.max(0, Math.ceil((scriptState.phase_timeout_ms - scriptState.phase_elapsed_ms) / 1000))
                : '…'}s 后自动推进
            </span>
          )}
        </div>
      )}

      {/* P-0816-I（UI 重设计阶段一）：搜证页替换 —— 主区顶部行动条 + 地点卡片网格 + VN 演出
          仅真剧本杀（full）搜证阶段挂载（chat 模式无搜证）；行动条/AP/地点卡自包含轮询+执行
          P-0816-M：proto 主区由下方阶段驱动块统一渲染（此处保留非 proto 路径） */}
      {!protoMain && store.mode === 'script' && UI_PROTO_V2_ENABLED && scriptState && scriptState.phase === 'investigation' && scriptState.mode !== 'chat' && (
        <ScriptInvestigationPanel scriptState={scriptState} />
      )}

      {/* P-0816-M：proto 主区阶段驱动切换（setup 准备区 / investigation 搜证+消息流 / discussion 讨论主区 / vote 投票主区 / reveal·ended 揭晓终局） */}
      {protoMain && (
        <div className="proto-main">
          {/* 准备/剧本生成中：沿用现有对局准备区（生成进度 + 消息区由下方 ScriptGalChatPanel 承担） */}
          {scriptPhase === 'setup' && (
            <ScriptSetupPanel
              scriptState={scriptState}
              busy={!!script?.scriptBusy}
              onGenerateFull={script?.onGenerateFull ?? (() => {})}
            />
          )}
          {/* 搜证：行动条 + 地点卡片网格 + VN 演出（仅 full 模式；chat 无搜证） */}
          {scriptPhase === 'investigation' && scriptState.mode !== 'chat' && (
            <ScriptInvestigationPanel scriptState={scriptState} onStartDiscussion={script?.onStartDiscussion} busy={!!script?.scriptBusy} />
          )}
          {/* 讨论：VN 化对话流 + 质询/引用 + 快捷动作条 + 输入框 + 倒计时（真实 discussion_say） */}
          {scriptPhase === 'discussion' && (
            <ScriptDiscussionPanel scriptState={scriptState} currentPlayer={store.currentPlayer} onStartVoting={script?.onStartVoting} busy={!!script?.scriptBusy} />
          )}
          {/* 投票：4 嫌疑人卡 + 选中态 + 弃票 + 确认投票 + 信任度条壳 + 投票统计 */}
          {scriptPhase === 'vote' && (
            <ScriptVotePanel
              voteStatus={store.scriptVoteProgress}
              phase={scriptPhase}
              phaseElapsedMs={scriptState?.phase_elapsed_ms}
              phaseTimeoutMs={scriptState?.phase_timeout_ms}
              currentPlayer={store.currentPlayer}
              busy={!!script?.scriptBusy}
              onVote={script?.onVoteFor ?? (() => {})}
              onAbstain={script?.onAbstain ?? (() => {})}
            />
          )}
          {/* 揭晓/终局：沿用 ScriptStatePanel 揭晓区（决策 U5） */}
          {(scriptPhase === 'reveal' || scriptPhase === 'ended') && (
            <ScriptRevealPanel
              scriptState={scriptState}
              reveal={store.scriptReveal}
              busy={!!script?.scriptBusy}
              onFinish={script?.onFinish ?? (() => {})}
              onRestart={script?.onRestart ?? (() => {})}
              onBackToScene={script?.onBackToScene ?? (() => {})}
            />
          )}
          {/* P-0818-D：全阶段常驻 Gal 聊天区（对局内所有聊天消息统一 Gal 页面呈现；
              阶段面板收在聊天区上方，proto-main overflow-y:auto 兜底滚动） */}
          <div
            className="conversation script-gal-conversation"
            style={{ padding: 12, overflow: 'hidden', display: 'flex', minHeight: 320, flex: '1 1 45%' }}
          >
            <ScriptGalChatPanel
              sessionId={store.scriptSessionId}
              playerName={store.currentPlayer}
              playerKey={store.scriptRoleKey}
              scriptState={scriptState}
            />
          </div>
        </div>
      )}

      <div className="presence-bar">
        {store.agents.map(name => {
          const status = store.charStatuses[name] || 'offline';
          const statusLabels: Record<string, string> = { active: '活跃', silent: '静默', offline: '离线' };
          const isFiltered = store.historyFilter === name;
          return (
            <button key={name}
              className={`presence-chip ${isFiltered ? 'active' : ''}`}
              onClick={() => store.setHistoryFilter(isFiltered ? null : name)}
            >
              <span className="avatar" style={{ background: colorFor(name) }}>{name[0]}</span>
              <span className="presence-name">{name}</span>
              <span className={`dot ${status}`} />
              <span className="presence-status">{statusLabels[status]}</span>
            </button>
          );
        })}
      </div>

      {/* P-0815-B：剧本杀模式中间消息流对话区替换为 Gal 聊天部件（ScriptGalChatPanel：立绘+打字机+输入区）；
          右侧 ScriptStatePanel 原样保留；AnnouncementBanner/阶段横幅/2D/地图面板在上方原样保留
          P-0816-M：proto 主区已由上方阶段驱动块接管（setup/investigation 消息区），此处仅非 proto 路径 */}
      {store.mode === 'script' && !protoMain ? (
        <div className="conversation" style={{ padding: 12, overflow: 'hidden', display: 'flex', minHeight: 0 }}>
          <ScriptGalChatPanel
            sessionId={store.scriptSessionId}
            playerName={store.currentPlayer}
            playerKey={store.scriptRoleKey}
            scriptState={scriptState}
          />
        </div>
      ) : !protoMain ? (
      <div ref={convRef} className="conversation">
        {visibleMessages.length === 0 ? (
          <div className="empty-state">
            <div className="empty-title">从一轮对话开始</div>
            <div>点击“推进一轮”让角色自动互动，或在底部输入主控旁白来改变节奏、补充事实、指定行动方向。</div>
          </div>
        ) : visibleMessages.map((msg, i) => {
          if (msg.role === 'system') {
            const match = msg.content.match(/第\s*(\d+)\s*轮完成/);
            const round = match ? Number(match[1]) : 0;
            return (
              <div className="system-line" key={`${msg.timestamp}-${i}`}>
                {msg.content}
                {round > 0 && <button className="btn btn-smallall" style={{ marginLeft: 8 }} onClick={() => onRollback(round)}>回滚</button>}
              </div>
            );
          }
          if (msg.role === 'arbiter') {
            return (
              <div className="arbiter-box" key={`${msg.timestamp}-${i}`}>
                <div className="message-meta"><strong>主控整合</strong></div>
                <div>{msg.content}</div>
                {msg.round_number > 0 && (
                  <button className="btn btn-smallall" style={{ marginTop: 8 }} onClick={() => onRollback(msg.round_number)}>回滚到此轮</button>
                )}
              </div>
            );
          }
          return <MessageView key={`${msg.timestamp}-${i}`} msg={msg} />;
        })}
      </div>
      ) : null}

      {store.currentTasks.length > 0 && (
        <div className="task-box">
          <div className="label" style={{ marginBottom: 5 }}>本轮任务分配</div>
          {store.currentTasks.map((task, i) => (
            <div className="task-row" key={`${task.agent_name}-${i}`}><strong>{task.agent_name}</strong>：{task.task}</div>
          ))}
        </div>
      )}

      {store.ttsStatus && <div className="tts-indicator">{store.ttsStatus}</div>}
      {/* P-0815-B：剧本杀模式隐藏 ChatComposer——发言统一走 gal 输入区（ScriptGalChatPanel liveSay 路由），
          防双输入双发；阶段横幅/状态面板不受影响 */}
      {store.mode !== 'script' && <ChatComposer />}
    </main>
  );
}
