/**
 * GalGeneralView.tsx — 一般模式「呈现接管」Gal 界面（P-0810-08，主人拍板）
 *
 * 一般模式会话的呈现入口直接就是 Gal 视觉小说式角色扮演聊天视图
 * （替换 ChatPage 聊天视图作为默认呈现；经典视图保留为右上角回退按钮）。
 *
 * 去对局化（需求）：
 *  - 顶部栏：返回（回会话列表）+ 会话标题/场景名 + mode 标签（一般·主角/导演）+ 角色成员小头像；
 *  - 去掉：对局状态 chips（阶段/事件计数/session_id）、连接面板、快速起局区块、类型切换；
 *  - 保留：立绘切换 + 打字机 + 底部发言框 + mode 中文标签；
 *  - 立绘：≤2 分列、>2 居中切换（GalGeneralStage）；
 *  - 主控（玩家）发言不渲染气泡（hidePlayerBubbles：输入框保留，发送后清空即可）。
 *
 * 数据接线：
 *  - SSE：useSSE(sessionId) → GalStore.applySseEvent（agent_output/agent_token/
 *    announcement/user_input/… 全走既有 live 链路）；
 *  - 元信息：GET /api/state?session_id=（scene/agents/mode）+ GET /api/mode?session_id=
 *    （mode 探测 → 「一般·自由/主角/多轨/导演」中文标签）；
 *  - 历史抽屉 / 场景卡：见 GalHistoryDrawer / GalSceneCard。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { useSSE } from '../api/useSSE';
import { useGalStore } from './GalStore';
import { api } from '../api/client';
import { hashHue, buildPlaceholderSpeaker, backendIdForName, GAL_SPEAKERS } from './galDemoData';
import { GalGeneralStage } from './GalGeneralStage';
import { GalHistoryDrawer } from './GalHistoryDrawer';
import { GalSceneCard, type GalSceneInfo } from './GalSceneCard';
import { useAutoPlaybackDone } from './useAutoPlaybackDone';
import { startLiveSync, pullGeneralHistory, refreshSuggestions } from './galSseAdapter';
// P-0817-I：Gal 视图顶栏全局静音开关
import { TtsMuteButton } from '../components/TtsMuteButton';
// P-0817-K：Gal 视图单角色静音控制（与对局顶栏同源 mimoTts 单例）
import { isCharacterMuted, toggleCharacterMuted, subscribeTtsStatus } from '../services/mimoTts';
import './gal.css';
import './galGeneral.css';

/** 一般模式 4 分类中文标签（与 galSseAdapter 同源映射） */
const GENERAL_MODE_LABEL: Record<string, string> = {
  free: '自由',
  protagonist: '主角',
  multi_track: '多轨',
  director: '导演',
};

interface AmbientRole {
  roleId: string;
  name: string;
  line?: string;
}

/** SSE 桥（hook 必须常驻已挂载组件 → 独立组件按 sessionId 条件渲染） */
function GalGeneralSseBridge() {
  const sessionId = useGalStore(s => s.liveSessionId);
  const applySseEvent = useGalStore(s => s.applySseEvent);
  const bumpLiveEvent = useGalStore(s => s.bumpLiveEvent);
  const setLiveStatus = useGalStore(s => s.setLiveStatus);

  const onEvent = useCallback((evt: string, data: any) => {
    bumpLiveEvent();
    // P-0811-G(B-2)：round_complete 带 session_id 时过滤（多会话并存防他局触发本会话候选刷新）
    if (evt === 'round_complete') {
      const st = useGalStore.getState();
      const evtSid = data && typeof data === 'object' ? (data as any).session_id : undefined;
      if (evtSid && st.liveSessionId && evtSid !== st.liveSessionId) return;
    }
    applySseEvent(evt, data);
    if (evt === 'round_complete' && useGalStore.getState().liveGameType === 'general') {
      // P-0811-G 修复：自动第一轮可能在 SSE 连接建立前就广播完（起局后立即触发），
      // 前端会错过 agent_output → 「已连接·等待对局消息」卡住。round_complete 后补拉历史追回。
      void pullGeneralHistory(useGalStore.getState().liveSessionId);
      // P-0810-21-D：AI 回合完成 → 刷新玩家发言候选（仅一般模式）
      void refreshSuggestions(useGalStore.getState().liveSessionId);
      // P-0814-A：点击驱动对话模式 —— 本轮生成完 → 置「播出完毕待推进」标志；
      // P-0814-C：不再显示按钮，队列排空后由 useAutoPlaybackDone 自动推进下一轮。
      useGalStore.getState().setLivePlaybackArmed(true);
    }
  }, [applySseEvent, bumpLiveEvent]);
  const onStatus = useCallback((st: any) => setLiveStatus(st), [setLiveStatus]);

  useSSE(onEvent, sessionId, onStatus);
  return null;
}

interface GalGeneralViewProps {
  /** 当前一般模式会话 session_id（GameBridge 起局后传入） */
  sessionId: string;
  /** 玩家名（发言用；缺省 localStorage playerId） */
  playerName?: string;
  /** 返回（回会话列表 / 上一页） */
  onBack?: () => void;
  /** 切经典视图（ChatPage 同会话，右上角回退按钮） */
  onClassic?: () => void;
}

export function GalGeneralView({ sessionId, playerName, onBack, onClassic }: GalGeneralViewProps) {
  const enterLiveMode = useGalStore(s => s.enterLiveMode);
  const exitLiveMode = useGalStore(s => s.exitLiveMode);
  const setHidePlayerBubbles = useGalStore(s => s.setHidePlayerBubbles);
  const setLiveGameType = useGalStore(s => s.setLiveGameType);
  const setLiveGeneralMode = useGalStore(s => s.setLiveGeneralMode);
  const setSpeakers = useGalStore(s => s.setSpeakers);
  const started = useGalStore(s => s.started);
  const finished = useGalStore(s => s.finished);
  const tick = useGalStore(s => s.tick);
  const liveGeneralMode = useGalStore(s => s.liveGeneralMode);
  const livePlayerName = useGalStore(s => s.livePlayerName);
  // P-0814-A：点击驱动对话模式 —— 待推进标志 + 队列排空检测（「播出完毕」→ 显示推进按钮）
  const livePlaybackArmed = useGalStore(s => s.livePlaybackArmed);
  const setLivePlaybackArmed = useGalStore(s => s.setLivePlaybackArmed);
  const liveQueue = useGalStore(s => s.liveQueue);
  const typing = useGalStore(s => s.typing);
  const current = useGalStore(s => s.current);
  // P-0810-16：场景卡目标（起局响应 / /api/state scene_goals / scene_target_update SSE 合并）
  const liveGoals = useGalStore(s => s.liveGoals);
  const setLiveGoals = useGalStore(s => s.setLiveGoals);
  const focusedRoleId = useGalStore(s => s.liveFocusedRoleId);
  const conversationMembers = useGalStore(s => s.liveConversationMembers);
  const setLiveConversation = useGalStore(s => s.setLiveConversation);

  // ── 元信息（场景名 / agents / mode 中文标签） ──
  const [scene, setScene] = useState<string>('');
  const [roster, setRoster] = useState<string[]>([]);
  const [modeLabel, setModeLabel] = useState('');
  const [metaReady, setMetaReady] = useState(false);
  const [ambientRoles, setAmbientRoles] = useState<AmbientRole[]>([]);
  const [knownRoleIds, setKnownRoleIds] = useState<Record<string, string>>({});
  const [storyScript, setStoryScript] = useState<any>(null);
  const [storyOpen, setStoryOpen] = useState(false);

  // ── 抽屉/卡片开关 ──
  const [historyOpen, setHistoryOpen] = useState(false);
  const [sceneCardOpen, setSceneCardOpen] = useState(false);
  const [roleDrawerOpen, setRoleDrawerOpen] = useState(false);
  const [groupDraft, setGroupDraft] = useState<string[]>([]);
  // P-0817-K：角色声音面板开关 + 静音状态刷新（mimoTts emit 时重渲染）
  const [voicePanelOpen, setVoicePanelOpen] = useState(false);
  const [, setVoiceTick] = useState(0);
  useEffect(() => subscribeTtsStatus(() => setVoiceTick(t => t + 1)), []);

  // 进入即连接 live（hidePlayerBubbles=true：玩家发言不渲染气泡）
  useEffect(() => {
    if (!sessionId) return;
    // P-0811-G：玩家名只取显式传入的 playerName —— 不再回退 localStorage.playerId
    // （用户反馈：未选定玩家角色时仍判定自己在说话；导演模式应无玩家身份）
    const name = playerName || '';
    enterLiveMode(sessionId, { playerName: name });
    // P-0818-F：进入对局后立即拉取 AI 形象状态（注册后端角色名 → ID 映射，保证局内立绘可查）
    void useGalStore.getState().refreshImageStatus();
    setHidePlayerBubbles(true);
    return () => {
      exitLiveMode();
      setHidePlayerBubbles(false);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  // 打字机定时器（与 GalDemoPage 同款：每 25ms 推进 2 字符）
  useEffect(() => {
    if (!started || finished) return;
    const t = setInterval(() => tick(2), 25);
    return () => clearInterval(t);
  }, [started, finished, tick]);

  // 元信息拉取（挂载 + 5s 轮询：scene / roster / mode 变化可见）
  const seededRosterRef = useRef('');
  /** P-0814-C：轮询兜底武装 —— 已发过 playback_done 信号的轮次（后端 roundCount=最近完成轮）；
   *  后端空闲（status=idle）且 round 推进到未发信号的轮次 → 武装，由自动推进 hook 在队列排空后发信号。
   *  自愈覆盖：SSE round_complete 错过 / 断线重连 / 挂载前轮次已播完（原 mountRound 基准轮 diff 会漏） */
  const lastFiredRoundRef = useRef(0);
  /** 最近一次轮询到的后端轮次（发信号时记录用） */
  const backendRoundRef = useRef(0);
  useEffect(() => {
    if (!sessionId) return;
    let alive = true;
    const refreshMeta = async () => {
      try {
        const st: any = await api.getState(sessionId);
        if (!alive) return;
        const sc = st?.scene || st?.scene_description || '';
        if (sc) setScene(String(sc));
        // P-0810-16：场景卡目标随 /api/state 下发（scene_goals 键）——进入/刷新/重连兜底拉取
        if (st?.scene_goals && typeof st.scene_goals === 'object') {
          setLiveGoals(st.scene_goals);
        }
        // P-0814-B/C：导演模式武装轮询兜底 —— round_complete SSE 错过（重连/连接前广播完）时，
        // 5s 轮询发现后端「等待播出完毕」（awaiting_playback=true）且轮次推进到未发信号轮次即武装；
        // 自动推进 hook 在队列排空后发信号。awaiting_playback 由后端 /api/state 暴露（P-0814-C），
        // 精确区分「轮次完成待信号」与「生成中」（status 常驻 running 不可作完成信号）。
        const round = Number(st?.round ?? 0);
        const awaiting = st?.awaiting_playback === true;
        backendRoundRef.current = round;
        if (round > 0 && awaiting && round > lastFiredRoundRef.current) {
          useGalStore.getState().setLivePlaybackArmed(true);
        }
        if (Array.isArray(st?.agents)) {
          const names = st.agents.map(String).filter(Boolean);
          setRoster(names);
          // P-0810-08：舞台角色表 = 会话 roster（替换 demo 角色）——保持玩家位，NPC 用占位立绘
          const key = names.join(',');
          if (names.length > 0 && key !== seededRosterRef.current) {
            seededRosterRef.current = key;
            const playerSp = GAL_SPEAKERS.find(x => x.isPlayer);
            const npcs = names.map((name: string) => buildPlaceholderSpeaker(name, backendIdForName(name)));
            setSpeakers(playerSp ? [...npcs, playerSp] : npcs);
          }
        }
        setMetaReady(true);
      } catch { /* 后端不可达：保持现状 */ }
      try {
        const world: any = await api.worldState(sessionId);
        if (alive) {
          if (world?.story_script && typeof world.story_script === 'object') setStoryScript(world.story_script);
          const pendingId = useGalStore.getState().livePendingInputId;
          if (pendingId) {
            const terminal = Array.isArray(world?.recent_results) ? world.recent_results.find((item: any) =>
              item?.kind === 'input' && String(item?.input_id || '') === pendingId) : null;
            if (terminal) {
              const failed = String(terminal?.status || '').toLowerCase() === 'failed';
              useGalStore.setState({ livePendingInputId: '', liveSendError: failed
                ? `世界调度失败：${String(terminal?.error || '请稍后重试')}` : '' });
            }
          }
          const extras = Array.isArray(world?.ambient_agents) ? world.ambient_agents : [];
          setAmbientRoles(extras.map((role: any) => ({
            roleId: String(role?.roleId || ''),
            name: String(role?.name || role?.agentName || '路人'),
            line: String(role?.line || ''),
          })).filter((role: AmbientRole) => role.roleId));
          setKnownRoleIds(previous => {
            const next = { ...previous };
            for (const role of extras) {
              const name = String(role?.name || role?.agentName || '');
              const roleId = String(role?.roleId || '');
              if (name && roleId) next[name] = roleId;
            }
            return next;
          });
        }
      } catch { /* 世界运行时尚未就绪时保持空列表 */ }
      try {
        const gm: any = await api.getMode(sessionId);
        const mode = String(gm?.mode || '');
        if (mode && GENERAL_MODE_LABEL[mode]) {
          setLiveGameType('general');
          setLiveGeneralMode(mode);
          if (alive) setModeLabel(`一般·${GENERAL_MODE_LABEL[mode]}`);
        } else if (mode) {
          if (alive) setModeLabel(`一般模式（${mode}）`);
        } else if (alive && !modeLabel) {
          setModeLabel('一般模式');
        }
      } catch { /* 忽略 */ }
    };
    void refreshMeta();
    // P-0818-F：定期刷新 AI 形象状态（每 5s 一次，与 refreshMeta 同周期）
    void useGalStore.getState().refreshImageStatus();
    const t = setInterval(() => { void refreshMeta(); void useGalStore.getState().refreshImageStatus(); }, 5000);
    return () => { alive = false; clearInterval(t); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  // 对局同步（类型探测 + 讨论增量；对一般会话主要为 liveGameType/liveGeneralMode 补位）
  useEffect(() => {
    if (!sessionId) return;
    const stop = startLiveSync(sessionId);
    // P-0810-21：连接后补拉一次历史（session_id 定向，SSE 重叠窗口按 speaker+text 去重）——
    // 起局自动首轮/已往消息回放入队（历史抽屉与主队列同源，玩家消息受 hidePlayerBubbles 守卫）
    // P-0811-G 修复：自动第一轮在 SSE 连接建立前可能已广播完（round_complete 也错过），
    // 单次/round_complete 触发补拉都不够 → 连接后定时重试补拉直到拉到消息（最多 ~24s）。
    const pull = () => {
      const s = useGalStore.getState();
      const idle = s.liveQueue.length === 0 && !s.current && !s.typing;
      if (idle) void pullGeneralHistory(sessionId);
    };
    void pullGeneralHistory(sessionId);
    const pullTimer = setInterval(pull, 3000);
    const maxPullTries = 8;
    let pullTries = 0;
    const stopPullTimer = setInterval(() => {
      const s = useGalStore.getState();
      if (s.liveQueue.length > 0 || s.current || s.typing || ++pullTries >= maxPullTries) {
        clearInterval(pullTimer);
        clearInterval(stopPullTimer);
      }
    }, 3000);
    // P-0810-21-D：进入即拉一次玩家发言候选（round_complete 后再刷新）
    void refreshSuggestions(sessionId);
    return () => {
      stop();
      clearInterval(pullTimer);
      clearInterval(stopPullTimer);
    };
  }, [sessionId]);

  // 调试钩子（CDP 走查用）
  useEffect(() => {
    (window as any).__galGeneralStore = useGalStore;
    return () => { delete (window as any).__galGeneralStore; };
  }, []);

  // 场景卡数据：scene 名有则显示，无则隐藏（GalSceneCard 内部 null 守卫）
  // name 截取前20字作为短名，description 用完整文本
  const sceneInfo: GalSceneInfo | undefined = scene
    ? { name: scene.length > 20 ? scene.slice(0, 20) + '…' : scene, description: scene }
    : undefined;

  // P-0814-C：自动推进（删「▶ 推进下一轮」按钮）——本轮播放完毕（打字机队列排空）自动
  // POST /api/simulation/playback_done 驱动下一轮（一般模式无 group_id）。触发点是「播放完成」
  // 事件（队列排空），不是定时器；节奏由播放速度天然控制。
  // P-0814-E：一问一答门控 —— 有玩家（livePlayerName 非空）：AI 轮播完即**停**，不自动发
  // playback_done（AI 绝不自动连说），候选选项自动出现等玩家输入——玩家输入（liveSay →
  // api.send）后端 runRound 玩家分支输入即推进（无需播放完成信号）；无玩家（导演模式）：
  // 维持 P-0814-C 播完自动推进（无输入者，播完自动下一轮防卡死）。2D（SimGalChatPanel）
  // 保持自动推进（主人拍板 2D 群聊氛围保留，本文件只改一般模式）。
  const hasPlayer = !!livePlayerName && String(livePlayerName).trim().length > 0
    || !!playerName && String(playerName).trim().length > 0;
  const autoDrained = !liveQueue.length && !typing && !current;
  useAutoPlaybackDone({
    enabled: !hasPlayer,
    armed: livePlaybackArmed,
    drained: autoDrained,
    sessionId,
    onAdvancing: () => {
      setLivePlaybackArmed(false);
      // 同步记录已发信号的轮次（防轮询/SSE 对同一轮重复武装）：后端轮次再推进才再次武装
      lastFiredRoundRef.current = backendRoundRef.current;
    },
    onAdvanceFailed: () => {
      // 失败延迟重新武装（3s 后；轮询兜底 5s 也会重新武装）——自动推进不因单次失败永久停摆
      setTimeout(() => useGalStore.getState().setLivePlaybackArmed(true), 3000);
    },
  });

  const displayName = livePlayerName || playerName || '';
  const ambientNames = new Set(ambientRoles.map(role => role.name));
  const roleCards = [
    ...roster.filter(name => name !== displayName).map(name => ({
      name, roleId: knownRoleIds[name] || '', ambient: false,
    })),
    ...ambientRoles.filter(role => !roster.includes(role.name)).map(role => ({
      name: role.name, roleId: role.roleId, ambient: true,
    })),
  ];
  const openDirectChat = (name: string, roleId?: string) => {
    setLiveConversation([name], roleId ? [roleId] : []);
    setRoleDrawerOpen(false);
  };
  const toggleGroupRole = (name: string) => {
    setGroupDraft(current => current.includes(name)
      ? current.filter(item => item !== name)
      : [...current, name].slice(0, 12));
  };
  const createGroupChat = () => {
    if (groupDraft.length < 2) return;
    setLiveConversation(groupDraft, groupDraft.map(name => knownRoleIds[name]).filter(Boolean));
    setRoleDrawerOpen(false);
  };

  return (
    <div className="galg-page">
      {/* ── 顶部栏：返回 + 标题 + mode 标签 + 成员小头像 + 右按钮 ── */}
      <div className="galg-topbar">
        <button className="galg-top-btn" onClick={onBack} title="返回会话列表">← 返回</button>
        <div className="galg-top-title-wrap">
          <div className="galg-top-title" title={scene || '一般模式会话'}>
            {scene || (metaReady ? '一般模式会话' : '连接中…')}
          </div>
          <div className="galg-top-sub">
            <span className="galg-mode-chip">{modeLabel || (liveGeneralMode && GENERAL_MODE_LABEL[liveGeneralMode] ? `一般·${GENERAL_MODE_LABEL[liveGeneralMode]}` : '一般模式')}</span>
            <span className="galg-top-session" title={sessionId}>会话 {sessionId}</span>
          </div>
        </div>
        <div className="galg-top-actions">
          {hasPlayer && (
            <button className="galg-top-btn" onClick={() => setRoleDrawerOpen(true)} title="打开隐藏角色栏">
              👥 角色 {roleCards.length}
            </button>
          )}
          {/* P-0817-I：全局语音开关（静音/恢复）—— Gal 视图顶栏入口，与对局顶栏同一 mimoTts 单例 */}
          <TtsMuteButton className="galg-top-btn" />
          {/* P-0817-K：单角色静音控制 —— 弹出面板列出当前会话角色，可单独静音某个角色的语音 */}
          <button className="galg-top-btn" onClick={() => setVoicePanelOpen(v => !v)} title="角色声音（单独静音某个角色）">
            🔊 角色声音
          </button>
          <button className="galg-top-btn" onClick={() => setSceneCardOpen(v => !v)} title="场景卡">
            🗺️ 场景卡
          </button>
          <button className="galg-top-btn" onClick={() => setStoryOpen(v => !v)} title="查看主控实时编排的动态剧本">
            📖 动态剧本
          </button>
          <button className="galg-top-btn" onClick={() => setHistoryOpen(true)} title="历史记录（消息列表 / 回滚）">
            📜 历史记录
          </button>
          <button className="galg-top-btn galg-classic-btn" onClick={onClassic} title="切换到经典聊天视图（同会话）">
            经典视图
          </button>
        </div>
      </div>

      {/* ── 舞台：左立绘（说话角色才出现）+ 右对话框 + 底部发言框 + 背景槽位 ── */}
      <div className="galg-body">
        <GalGeneralStage scene={scene} />
      </div>

      {hasPlayer && conversationMembers.length > 0 && (
        <div className="galg-active-chat" aria-label="当前聊天">
          <strong>{conversationMembers.length === 1 ? '单独聊天' : '群聊'}</strong>
          <span>{displayName}、{conversationMembers.join('、')}</span>
          {focusedRoleId && ambientNames.has(conversationMembers[0]) && <em>首次有效发言后补全人物卡</em>}
          <button onClick={() => setLiveConversation([], [])}>结束聊天</button>
        </div>
      )}

      {displayName && (
        <div className="galg-identity">🎭 你扮演：{displayName}（发言不显示气泡，输入后直接发送）</div>
      )}

      {/* ── 历史记录抽屉（右上） ── */}
      <GalHistoryDrawer open={historyOpen} onClose={() => setHistoryOpen(false)} sessionId={sessionId} />

      {/* ── 场景卡（右上；无 scene 名自动隐藏；目标：起局响应 goals + /api/state scene_goals + SSE 增量） ── */}
      {sceneCardOpen && (
        <div className="galg-scene-mask" onClick={() => setSceneCardOpen(false)}>
          <div onClick={e => e.stopPropagation()}>
            <GalSceneCard scene={sceneInfo} targets={[]} goals={liveGoals || undefined} onClose={() => setSceneCardOpen(false)} />
          </div>
        </div>
      )}

      {storyOpen && storyScript && (
        <div className="galg-story-mask" onClick={() => setStoryOpen(false)}>
          <aside className="galg-story-panel" onClick={event => event.stopPropagation()}>
            <div className="galg-role-head"><div><strong>{storyScript.title || '动态剧本'}</strong><small>主控只改写后续，不会替你改写已作出的选择</small></div><button onClick={() => setStoryOpen(false)}>✕</button></div>
            <section><small>总目标</small><p>{storyScript.total_goal || '等待主控建立总目标'}</p></section>
            <section><small>当前阶段 · 张力 {Number(storyScript?.stage?.tension || 0)}%</small><p><strong>{storyScript?.stage?.title || '开场'}</strong>：{storyScript?.stage?.goal || '等待阶段目标'}</p></section>
            <section><small>主控手里的下一页</small><p>{storyScript.script || '剧情会随每一步更新。'}</p><p className="galg-story-next">下一拍：{storyScript.next_beat || '等待下一次互动。'}</p></section>
            {Array.isArray(storyScript.recent_changes) && storyScript.recent_changes.length > 0 && <section><small>已发生</small><ul>{storyScript.recent_changes.map((change: string, index: number) => <li key={`${index}:${change}`}>{change}</li>)}</ul></section>}
          </aside>
        </div>
      )}

      {sessionId && <GalGeneralSseBridge />}

      {roleDrawerOpen && hasPlayer && (
        <div className="galg-role-mask" onClick={() => setRoleDrawerOpen(false)}>
          <aside className="galg-role-drawer" onClick={event => event.stopPropagation()}>
            <div className="galg-role-head">
              <div><strong>角色</strong><small>点击不会晋升，成功发言才计有效互动</small></div>
              <button onClick={() => setRoleDrawerOpen(false)}>✕</button>
            </div>
            <div className="galg-role-list">
              {roleCards.length === 0 && <div className="galg-role-empty">场景中暂时没有可互动角色</div>}
              {roleCards.map(role => {
                const selected = groupDraft.includes(role.name);
                const chatting = conversationMembers.includes(role.name);
                return (
                  <article key={`${role.name}:${role.roleId}`} className={`galg-role-card${chatting ? ' chatting' : ''}`}>
                    <span className="galg-role-avatar" style={{ background: `hsl(${hashHue(role.name)} 55% 22%)` }}>
                      {role.name.slice(0, 1)}
                    </span>
                    <div className="galg-role-info">
                      <strong>{role.name}</strong>
                      <small>{role.ambient ? '轻量路人 · 尚未补全人物卡' : '完整角色 · 可立即回复'}</small>
                    </div>
                    <div className="galg-role-options">
                      <button onClick={() => openDirectChat(role.name, role.roleId)}>单独聊天</button>
                      <button className={selected ? 'active' : ''} onClick={() => toggleGroupRole(role.name)}>
                        {selected ? '已加入群聊' : '加入群聊'}
                      </button>
                    </div>
                  </article>
                );
              })}
            </div>
            <div className="galg-role-footer">
              <span>已选 {groupDraft.length} 名角色（至少 2 名）</span>
              <button disabled={groupDraft.length < 2} onClick={createGroupChat}>建立群聊</button>
            </div>
          </aside>
        </div>
      )}

      {/* P-0817-K：角色声音面板（单角色静音；点外部关闭） */}
      {voicePanelOpen && (
        <div style={{ position: 'fixed', inset: 0, zIndex: 98 }} onClick={() => setVoicePanelOpen(false)} />
      )}
      {voicePanelOpen && (
        <div style={{
          position: 'fixed', right: 16, top: 64, zIndex: 99, width: 280,
          background: 'var(--bg-2, #141e33)', border: '1px solid var(--border, #2b3854)',
          borderRadius: 10, padding: 12, boxShadow: '0 8px 30px rgba(0,0,0,0.4)',
        }}>
          <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 4 }}>🔊 角色声音</div>
          <div className="hint" style={{ fontSize: 12, marginBottom: 8 }}>
            单独静音某个角色的语音（不影响其他角色）；顶栏 🔇 可全局静音/恢复。
          </div>
          {roster.length === 0 ? (
            <div className="hint" style={{ fontSize: 12 }}>
              当前会话暂无角色列表，进入对局后可在此单独静音某个角色。
            </div>
          ) : (
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {roster.map(name => {
                const m = isCharacterMuted(name);
                return (
                  <button
                    key={name}
                    className={`chip ${m ? 'active' : ''}`}
                    onClick={() => toggleCharacterMuted(name)}
                    title={m ? `恢复「${name}」的语音` : `静音「${name}」的语音`}
                    style={m ? { color: '#ff9b9b', borderColor: 'rgba(255,107,107,0.45)' } : undefined}
                  >{m ? '🔇' : '🔊'} {name}</button>
                );
              })}
            </div>
          )}
          <div className="hint" style={{ fontSize: 11, marginTop: 8, lineHeight: 1.5 }}>
            静音角色消息旁的 🎙 按钮将变灰不可播放。
          </div>
        </div>
      )}
    </div>
  );
}
