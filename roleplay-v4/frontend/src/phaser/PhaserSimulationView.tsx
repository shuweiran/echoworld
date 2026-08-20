/**
 * PhaserSimulationView.tsx —— 2D 模拟视图（Phaser 3.90 渲染层）
 *
 * 数据流（剧static/simulation.html 完全一致，Java 后端零改动）… *   GET  /api/simulation/state            初始/兜底快照
 *   SSE  /api/simulation/events           world_snapshot 增量
 *   POST /api/simulation/load-characters  加载角色`2D 世界
 *   POST /api/simulation/start|stop|reset
 *   POST /api/simulation/target/{name}    点击设目标 *   POST /api/simulation/scene/{name}     切换场景
 *   POST /api/simulation/send/{name}      以角色身份发言（右侧聊天面板复用）
 *
 * 生命周期（阶段0 实证模式复用）：
 *   - Game 实例如React Ref（ref div 为 parent） *   - 卸载 / StrictMode double-mount → game.destroy(true)（removeCanvas=true） *   - Vite dev HMR → import.meta.hot.dispose → destroy
 *   - 组件只读 store/外部传入"characters+scene，不足store（数据流不变， *
 * ── C-1 批次（2026-08-02）2D UI 重构 ─────────────────────────────
 *  1. 布局重构：左侧地图空间充）+ 右侧聊天面板（对话历史列表+ 发言输入框）， *  2. 右侧面板可折叠：控制条「💬 聊天」按钮+ 面板头部 ✕ —— 收起时地图全宽，展开时显示聊天面 *  3. P0-3 内嵌聊天（消息展开+ 输入）整合进右侧面板，不再保留旧底部双份对 *  4. P3-10：演讲广播 demo 从 ScenePage 场景设置迁入本视图（精简版，默认折叠）， *  5. 消息结构统一路SimChatMsg（status: pending|playing|done），CSS 类 .sim-chat-msg.status-* 就位。 *
 * ── C-2 批次 026-08-02）输出机制重构：打字机流式播（──────────
 *  1. 打字机队列严格串行：pending → playing（逐字，3 字/秒）.done；上一段播（+ 3s 句间停顿 → 下一段" *  2. 参数集中配置 simChatConfig（打字速度/句间停顿/60s 暂停超时/句长上限）， *  3. 用户在场判定（单轨/多轨）：conversation-status 群组成员含玩家名 → 在场 *     在场 → 世界内只显示「当前播放者」气泡单例；不在场 → 多气泡并行 + 锚定避让（硬约束不重叠）， *  4. 暂停/恢复：输入框有字 → 冻结播放进度；发送后恢复。0s 超时无操作 → 跳过当前句 *  5. 限句间+ 纯语言文本：渲染硬截断（省略号）+ cleanWorldText 过滤非语言噪音（保留中文标点）； *  6. 一般模式（m2D）不受影响（直接显示不打字机，顺序由后端调度保证）· */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Phaser from 'phaser';
import { SimulationScene, type SceneCallbacks, type AgentAnim } from './SimulationScene';
import { AVAILABLE_SCENES, type SimGroup } from './simulationData';
import type { ScriptMap } from './mapData';
import { simChatConfig, cleanWorldText, truncateText, simChatConfigSummary, simChatPlaybackTiming } from './simChatConfig';
import { AnnouncementBanner } from '../components/AnnouncementBanner';
import { AnnouncementTicker } from '../components/AnnouncementTicker';
import { api, assetFileUrl, asepriteJsonUrl } from '../api/client';
import { SilenceTurn, isSilenceText } from '../utils/silenceMarker';
import { SimGalChatPanel } from './SimGalChatPanel';
import { useGalStore } from '../gal/GalStore';

/**
 * P-0813-D：自动打招呼防抖（同一 NPC 两次点击间隔 < 4s 不重复发问候，防误触连发）
 */

export interface PhaserSimulationViewProps {
  /** 进入 2D 世界的角色（name/persona/voice/background，与后端 load-characters 契约一致） */
  characters: Array<{ name: string; persona?: string; voice?: string; background?: string }>;
  /** 场景名（park/city/cafe/forest/classroom/beach；非法值后端回退 park）。*/
  scene?: string;
  /** P-0811-G：可选 LLM 地图（契约 v1）——传入后后端把 collision 瓦片转障碍注入模拟世界（动态模拟用 LLM 布局） */
  map?: ScriptMap;
  /** 地图区域高度（px 或 CSS 长度，如 'min(640px, calc(100vh - 190px))'；P-0816-D 起不传=自适应模式填满视口剩余，传值=固定高度旧行为）*/
  height?: number | string;
  /** 加载角色后是否自动start（默认true，对齐原 simulation.html 自动开始行为） */
  autoStart?: boolean;
  /** P0-1/P0-3：玩家名（聊天发言以该角色身份发送；load-characters 显式标记玩家控制条*/
  playerName?: string;
  /**
   * P-0813-D：Gal 式对话区开关（一般模式 Gal 式聊天迁入 2D）。
   * true = 右侧聊天面板替换为 Gal 式对话区（GalDialogBox 打字机 + 点击继续 + 候选 + 输入框，
   * 消息源=2D 世界对话流）；false = 旧列表式聊天面板（默认，其余调用方零变化）。
   */
  galChat?: boolean;
}

/** C-2 衔接：消息播放状态（打字机流式队列驱动） */
export type ChatMsgStatus = 'pending' | 'playing' | 'done';
/** 统一消息结构（C-2 直接消费 status 字段，结构保持不变） */
export interface SimChatMsg {
  id: string;
  who: string;
  text: string;
  /** player=玩家发言 / world=世界角色对话 / system=系统提示 */
  kind: 'player' | 'world' | 'system';
  /** pending=待播放（入队友/ playing=正在打字机播（/ done=已播（*/
  status: ChatMsgStatus;
  ts: number;
  /** P-0815-H：所属对话群 id（recentConversations 条目 group 键，方案 A 保留群归属）——
   *  群聊面板（SimGalChatPanel）据此在玩家入群时按群过滤消息流；经典列表/世界气泡不消费。 */
  group?: string;
}

/** recentConversations 拍平时跳过的元数据键 */
const SKIP_CONV_KEYS = new Set(['pair', 'group', 'mode', 'tick', 'elapsedMs', 'round']);

export function PhaserSimulationView({ characters, scene = 'park', map, height, autoStart = true, playerName, galChat = false }: PhaserSimulationViewProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const gameRef = useRef<Phaser.Game | null>(null);
  /** P-0816-A：2D 视图全屏（宿主 div requestFullscreen，与预览地图全屏能力对齐） */
  const toggleFullscreen = () => {
    const el = hostRef.current;
    if (!el) return;
    if (document.fullscreenElement) void document.exitFullscreen().catch(() => {});
    else void el.requestFullscreen().catch(() => {});
  };
  const esRef = useRef<EventSource | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const [status, setStatus] = useState('初始化中...');
  const [currentScene, setCurrentScene] = useState(scene);
  const [running, setRunning] = useState(false);
  // ── C-1：右侧聊天面板（对话历史 + 发言输入）──
  const [conversations, setConversations] = useState<any[]>([]);   // 后端 recentConversations（世界对话）
  const [localMsgs, setLocalMsgs] = useState<SimChatMsg[]>([]);    // 玩家发言 + 系统提示
  const [chatInput, setChatInput] = useState('');
  const [chatOpen, setChatOpen] = useState(false);                 // 仅加入会话组后展开；默认地图全宽
  /** 页面/浏览器切走时只暂停本地回放，回来后从同一字继续。 */
  const [pageHidden, setPageHidden] = useState(() => typeof document !== 'undefined' && document.hidden);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyMode, setHistoryMode] = useState<'group' | 'time'>('group');
  // P-0813-D：galChat 时默认 Gal 视图；可切回经典列表
  const [galView, setGalView] = useState(true);
  /**
   * P-0813-G：Gal 对话状态机——false=未对话（Gal 面板不渲染，自由探索）；
   * true=对话中（点击 NPC/自己进入，面板淡入；点「退出对话」→ false 淡出回到自由探索）。
   */
  const [galOpen, setGalOpen] = useState(false);
  /**
   * P-0813-G：待处理对话行（面板未挂载时点击 NPC 产生的系统提示/问候语）。
   * 面板挂载（enterLiveMode）后由 SimGalChatPanel 消费：system → liveEnqueue；send → simSend。
   * 解决「面板未挂载时 liveMode=false → liveEnqueue/liveSayOverride 全部丢弃」的问题。
   */
  const [pendingLines, setPendingLines] = useState<Array<{ kind: 'system' | 'send'; text: string }>>([]);
  /** P-0813-G：可交互（接近）NPC 名单（SimulationScene.onApproachChange 上抛 → DOM 层叠提示） */
  const [approachNames, setApproachNames] = useState<string[]>([]);
  /** P-0813-K：可加入（接近）对话群名单（SimulationScene.onGroupApproachChange 上抛 → DOM 层叠提示，群优先） */
  const [approachGroups, setApproachGroups] = useState<string[]>([]);
  /** P-0813-K：玩家当前所在群（conversation-status currentTrack 命中）——Gal 面板群聊模式数据源 */
  const [joinedGroup, setJoinedGroup] = useState<SimGroup | null>(null);
  const [demoOpen, setDemoOpen] = useState(false);                 // P3-10：演讲广播 demo（默认折叠）
  const [demoMsg, setDemoMsg] = useState('');
  const [demoBusy, setDemoBusy] = useState(false);
  const [demoResult, setDemoResult] = useState('');
  const [broadcastMode, setBroadcastMode] = useState('merged');    // merged=正式版/ auto=方案A / split=方案B
  // C-2：群组（conversation-status）——用户在场判定数据源
  const [groups, setGroups] = useState<SimGroup[]>([]);
  // ── P-0803-G：群组加入/离开交互 ──
  /** 玩家名镜像（供轮询回调读取最新值，避免闭包过期摘*/
  const playerNameRef = useRef(playerName);
  playerNameRef.current = playerName;
  /** 世界角色名列表镜像（fetchState 每 3s 更新；判断玩家角色是否在场 → 是否显示加入入口重*/
  const worldAgentsRef = useRef<string[]>([]);
  /** 加入/离开结果角标提示（ok=绿 / error=红，4.5s 自消；后端错误message 可见）*/
  const [joinMsg, setJoinMsg] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  // P-0813-D：自动打招呼防抖表（NPC 名 → 上次问候时间戳）
  const galChatRef = useRef(galChat);
  galChatRef.current = galChat;
  const worldSigRef = useRef<Map<string, ChatMsgStatus>>(new Map()); // 世界消息签名 → 播放状态（打字机队列状态源）
  /** P-0814-B：世界消息首见时间戳（签名 → 首次进入列表的 epoch ms；玩家回声 Date.now 与之一致可比） */
  const worldTsRef = useRef<Map<string, number>>(new Map());
  const localSeqRef = useRef(0);
  const timersRef = useRef<Set<ReturnType<typeof setTimeout>>>(new Set());
  const chatListRef = useRef<HTMLDivElement>(null);
  const [, bump] = useState(0);
  const startedRef = useRef(false);
  // P1-8：公告栏显示开关（默认开；localStorage 持久化，后 ChatPage「⚙️ 设置」面板共用同一个roleplay_ann_show）
  const [, forceRender] = useState(0);
  const annShow = (() => { try { return localStorage.getItem('roleplay_ann_show') !== '0'; } catch { return true; } })();
  const toggleAnnShow = () => {
    const next = !annShow;
    try { localStorage.setItem('roleplay_ann_show', next ? '1' : '0'); } catch { /* ignore */ }
    forceRender(v => v + 1);
  };

  // ── C-2：打字机播放队列状态（严格串行）──
  const queueRef = useRef<SimChatMsg[]>([]);                        // 待播放队列（pending）
  const playingRef = useRef<SimChatMsg | null>(null);               // 当前播放消息
  const revealRef = useRef<Map<string, number>>(new Map());         // 消息 id → 已揭示字数
  const typingTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const nextTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pauseStartRef = useRef<number | null>(null);                // 暂停起始时间戳（输入框有字）
  const pausedRef = useRef(false);                                  // 暂停镜像（异步回调读最新值）
  const lastSpeakerRef = useRef<string | null>(null);               // 最近播放者（气泡单例：在场时只显示它）
  const [bubbleTick, setBubbleTick] = useState(0);                  // 播放者变化 → 同步气泡过滤

  // 点击设目标 → 直接 POST（与 simulation.html 相同端点击
  const onSetTarget: SceneCallbacks['onSetTarget'] = (agentName, x, y) => {
    fetch(`/api/simulation/target/${encodeURIComponent(agentName)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ x, y }),
    }).catch(() => {});
  };

  /** P-0814-I：WASD/方向键持续移动 → POST /api/simulation/move-dir（服务端权威坐标 + 方向×步长，按住高频 120ms）
   *  P-0816-C：dx/dy 均为 0 → 停止移动（后端清除 manualTarget，防止松开后角色继续滑向最后目标点） */
  const onMoveDir: SceneCallbacks['onMoveDir'] = (agentName, dx, dy) => {
    fetch(`/api/simulation/move-dir/${encodeURIComponent(agentName)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dx, dy }),
    }).catch(() => {});
  };

  // ── P-0813-D：玩家角色加入对话触发 ──
  /** 解析当前玩家角色名（显式 playerName → 'me'/'我'/'主人' → 第一个角色） */
  const resolveMeName = useCallback((): string => {
    const names = characters.map(c => c && c.name).filter(Boolean) as string[];
    let me = playerName && names.includes(playerName) ? playerName : '';
    if (!me) me = names.find(n => n === 'me' || n === '我' || n === '主人') || '';
    if (!me && names.length > 0) me = names[0]!;
    return me;
  }, [characters, playerName]);

  /** P-0813-D：Gal 区玩家发言 → /api/simulation/send/{playerName}（2D 世界发言通道，非 RouterService） */
  const simSend = useCallback(async (text: string) => {
    const msg = text.trim();
    if (!msg) return;
    const me = resolveMeName();
    if (!me) {
      useGalStore.getState().liveEnqueue({ kind: 'system', speakerId: 'system', name: '⚠️ 系统', text: '没有可发言的角色' });
      return;
    }
    try {
      await fetch(`/api/simulation/send/${encodeURIComponent(me)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: msg }),
      });
      // 本地回显（玩家气泡在 Gal 区可见；2D 世界 AI 回应经 recentConversations 回流入队）
      useGalStore.getState().enqueuePlayerEcho(msg);
    } catch (e: any) {
      useGalStore.getState().liveEnqueue({ kind: 'system', speakerId: 'system', name: '⚠️ 系统', text: '发送失败 ' + (e?.message || '') });
    }
  }, [resolveMeName]);

  /** 点角色只做地图定位：不能以一次观察操作把所有人并入主控对话轨道。 */
  const handleAgentClick: SceneCallbacks['onAgentClick'] = useCallback((agentName: string) => {
    setJoinMsg({ kind: 'ok', text: `已聚焦 ${agentName}；点击地图上的会话组可加入旁听或发言。` });
  }, []);


  /** P-0813-G：SimulationScene 上抛的接近 NPC 名单变化 → DOM 层叠提示 */
  const handleApproachChange: SceneCallbacks['onApproachChange'] = useCallback((nearby: string[]) => {
    setApproachNames(nearby || []);
  }, []);

  /** P-0813-K：SimulationScene 上抛的可加入对话群变化 → DOM 层叠提示（群提示优先于 NPC 提示） */
  const handleGroupApproachChange: SceneCallbacks['onGroupApproachChange'] = useCallback((groupIds: string[]) => {
    setApproachGroups(groupIds || []);
  }, []);

  // ── Phaser Game 生命周期（创建/ destroy / StrictMode double-mount 收敛 ──
  useEffect(() => {
    if (!hostRef.current) return;
    const host = hostRef.current;
    let cancelled = false;
    const blobUrls: string[] = [];
    let groupPoll: ReturnType<typeof setInterval> | null = null;
    // P-0804-C：素材库角色动画（CHARACTER_ANIMATION 登记，按 character_name 关联 2D 世界角色名）
    const agentAnims: Record<string, AgentAnim> = {};

    const initGame = () => {
      const game = new Phaser.Game({
      type: Phaser.AUTO,
      parent: host,
      width: 1000,
      height: 600,
      backgroundColor: '#0f172a',
      scale: {
        mode: Phaser.Scale.FIT,
        autoCenter: Phaser.Scale.CENTER_BOTH,
        width: 1000,
        height: 600,
      },
      physics: { default: 'arcade', arcade: { gravity: { x: 0, y: 0 }, debug: false } },
      scene: [new SimulationScene({ onSetTarget, onMoveDir, onGroupAction: handleGroupAction, onAgentClick: handleAgentClick, onApproachChange: handleApproachChange, onGroupApproachChange: handleGroupApproachChange }, agentAnims, map, playerName, galChat)],
      banner: false,
    });
    gameRef.current = game;

    const scene = () => game.scene.getScene('SimulationScene') as SimulationScene;

    // ── 数据流：SSE world_snapshot 增量 ──
  const es = new EventSource('/api/simulation/events');
    esRef.current = es;
    es.onopen = () => setStatus('SSE 已连接');
    es.onerror = () => setStatus('SSE 断开（重连中）');
    es.addEventListener('world_snapshot', (e: MessageEvent) => {
      try {
        const d = JSON.parse(e.data);
        scene().applySnapshot(d);
        // P-0815-E 需求3：SSE 事件已附 recentConversations → 世界对话即时入列
        //（不再等 3s 轮询，消「刷新太慢」）；重复消息由 C-2 队列 sig 去重 /
        // SimGalChatPanel seen 去重兜底，与 3s 轮询双通道并存不重复入队。
        // 内容未变化时返回同一引用（React bail out，同 P-0815-F fetchState 模式）——
        // SSE 每 400ms 一次全量快照，无条件 setState 会每 400ms 全量重渲染。
        if (Array.isArray(d.recentConversations)) {
          setConversations(prev => {
            const next = d.recentConversations;
            if (prev.length === next.length &&
                (prev.length === 0 || JSON.stringify(prev[prev.length - 1]) === JSON.stringify(next[next.length - 1]))) {
              return prev;
            }
            return next;
          });
        }
      } catch { /* 忽略坏帧 */ }
    });

    // ── 数据流：GET state 轮询兜底（对局simulation.html fetchState 双通道，──
  const fetchState = async () => {
      try {
        const r = await fetch('/api/simulation/state');
        const d = await r.json();
        scene().applySnapshot(d);
        setRunning(Boolean(d.running));
        if (d.scene) setCurrentScene(d.scene);
        // P0-3：内嵌视图消息展示（recerecentConversations）
  if (Array.isArray(d.recentConversations)) {
          // P-0815-F：内容未变化时不 setState（返回同一引用 → React bail out）——
          // 原实现每 3s 轮询无条件 setConversations → 整个组件每 3s 全量重渲染（含全部 useMemo 链）
          setConversations(prev => {
            const next = d.recentConversations;
            if (prev.length === next.length &&
                (prev.length === 0 || JSON.stringify(prev[prev.length - 1]) === JSON.stringify(next[next.length - 1]))) {
              return prev;
            }
            return next;
          });
        }
        // P-0803-G：世界角色名列表（玩家角色在场判定；群组加入入口显隐藏
  if (Array.isArray(d.agents)) {
          worldAgentsRef.current = (d.agents as Array<{ agentName?: string }>)
            .map(a => (a && a.agentName ? String(a.agentName) : ''))
            .filter(Boolean);
        }
      } catch { /* 后端未就绪时忽略 */ }
    };
    fetchState();
    pollRef.current = setInterval(fetchState, 3000);

    // ── 数据流：加载角色 → 自动开始 ──
  const loadCharacters = async () => {
      if (startedRef.current) return;
      startedRef.current = true;
      try {
        const clean = characters.filter(c => c && c.name && String(c.name).trim());
        if (clean.length === 0) {
          setStatus('没有可加载的角色（≥2 个）');
          return;
        }
        const r = await fetch('/api/simulation/load-characters', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            characters: clean.map(c => ({
              name: String(c.name).trim(),
              persona: (c.persona || c.name + '，一个角色').trim(),
              voice: c.voice || '',
              background: c.background || '',
            })),
            scene: currentScene,
            // P-0811-G：仅显式玩家名才标记玩家控制（此前 `playerName || 'me'` 硬编码兜底 'me'，
            // 未选定玩家角色时后端也会判定 'me' 在说话——用户反馈「没选定自己还判定自己说话」）
            ...(playerName ? { player_name: playerName } : {}),
            // P-0811-G：可选 LLM 地图（契约 v1）→ 后端 collision 瓦片转障碍注入模拟世界
            ...(map ? { map } : {}),
          }),
        });
        const d = await r.json();
        setStatus(d.message || '角色已加载');
        if (autoStart) {
          await fetch('/api/simulation/start', { method: 'POST' });
          setRunning(true);
        }
        await fetchState();
      } catch (e: any) {
        setStatus('加载失败: ' + (e?.message || '网络错误'));
      }
    };
    loadCharacters();

    // ── 群组"+ 用户在场数据源（conversation-status，每 4s 拉取；P-0803-G 顺带刷新加入/离开入口重──
    fetchGroups();
    groupPoll = setInterval(fetchGroups, 4000);
    };

    // P-0804-C：先拉取素材库角色动画登记（失败/为空 → 空表，SimulationScene 无素材回退圆点渲染零破坏），
    // 再创建 Phaser.Game（SimulationScene preload 加载 Aseprite）。
    (async () => {
      try {
        const list = await api.assetList({ type: 'CHARACTER_ANIMATION' });
        for (const a of list || []) {
          if (!a || !a.character_name || !a.file_path) continue;
          const j = asepriteJsonUrl(a.meta_json, a.file_path);
          if (j.isBlob) blobUrls.push(j.url);
          agentAnims[a.character_name] = { pngUrl: assetFileUrl(a.file_path), jsonUrl: j.url };
        }
      } catch { /* 素材拉取失败 → 空表，零破坏 */ }
      if (!cancelled) initGame();
    })();

    return () => {
      // 卸载 / StrictMode 双挂载收敛：清理全部资源 + destroy(true)
      cancelled = true;
      blobUrls.forEach(u => URL.revokeObjectURL(u));
      blobUrls.length = 0;
      if (groupPoll) clearInterval(groupPoll);
      if (pollRef.current) clearInterval(pollRef.current);
      if (esRef.current) { esRef.current.close(); esRef.current = null; }
      timersRef.current.forEach(t => clearTimeout(t));
      timersRef.current.clear();
      clearPlaybackTimers();
      if (gameRef.current) {
        gameRef.current.destroy(true); // removeCanvas=true（阶段0 实证模式切        gameRef.current = null;
      }
      startedRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [characters, autoStart]);

  // ── Vite dev HMR 保护：模块热替换前销。Game 实例（阶段0 实证模式切──
  useEffect(() => {
    const dispose = () => {
      if (gameRef.current) {
        gameRef.current.destroy(true);
        gameRef.current = null;
      }
      if (esRef.current) { esRef.current.close(); esRef.current = null; }
      startedRef.current = false;
    };
    if (import.meta.hot) {
      import.meta.hot.dispose(dispose);
    }
    return () => { /* 组件卸载由主 effect cleanup 处理 */ };
  }, []);

  // ── C-1：世界对话（recentConversations）拍平为统一消息列表（附播放状态） ──
  // C-2：拍平时即清洗（过滤非语言噪音 + 硬截断句长上限），列表与打字机队列共用清洗后文本+  //      保证揭示字数与显示文本一致（原始文本+emoji/超长时逐字计数会错位），
  const worldMsgs = useMemo(() => {
    const out: SimChatMsg[] = [];
    const now = Date.now();
    for (const c of conversations) {
      if (!c || typeof c !== 'object') continue;
      // P-0815-H：保留群归属（方案 A）——group 键是元数据（群 id），不能当消息拍平，
      // 单独取出挂到每条消息上，供 SimGalChatPanel 在群聊模式下按群过滤入队。
      const gid = typeof c.group === 'string' && c.group.trim() ? c.group : undefined;
      for (const [k, v] of Object.entries(c)) {
        if (SKIP_CONV_KEYS.has(k)) continue;
        if (typeof v === 'string' && v.trim()) {
          const sig = String(c.tick ?? '') + '|' + k + '|' + v; // 签名用原始文本（稳定去重复
  const cleaned = truncateText(cleanWorldText(v), simChatConfig.maxSentenceChars);
          if (!cleaned) continue; // 过滤后为空 → 不展示不排队
  const st = worldSigRef.current.get(sig) || 'pending';
          // P-0814-B：时间戳=首见 epoch ms（首次轮询/SSE 到达时刻）——与玩家本地回声 Date.now 同一时间轴，
          // 供 allMsgs 统一归并排序（消灭「玩家回声永远拼在 worldMsgs 之后」的乱序）
          let ts = worldTsRef.current.get(sig);
          if (ts === undefined) {
            ts = now;
            worldTsRef.current.set(sig, ts);
          }
          out.push({ id: 'w-' + sig, who: k, text: cleaned, kind: 'world', status: st, ts, group: gid });
        }
      }
    }
    return out;
  }, [conversations, bump]);

  const isObserverPlayback = Boolean(joinedGroup && chatOpen && !playerName?.trim());
  const playbackTiming = simChatPlaybackTiming(isObserverPlayback);

  // ── C-2：打字机队列引擎 ──
  const clearPlaybackTimers = () => {
    if (typingTimerRef.current) { clearInterval(typingTimerRef.current); typingTimerRef.current = null; }
    if (nextTimerRef.current) { clearTimeout(nextTimerRef.current); nextTimerRef.current = null; }
  };

  /** 播完当前段："done，清除打字机进度（下一段由句间停顿定时器接续） */
  const finishPlaying = () => {
    const cur = playingRef.current;
    if (!cur) return;
    worldSigRef.current.set(cur.id, 'done');
    revealRef.current.delete(cur.id);
    playingRef.current = null;
    clearPlaybackTimers();
    bump(v => v + 1);
  };

  /** 开始逐字播放一段消息（打字机 3 字/秒） */
  const startTyping = (msg: SimChatMsg) => {
    playingRef.current = msg;
    lastSpeakerRef.current = msg.who;
    worldSigRef.current.set(msg.id, 'playing');
    revealRef.current.set(msg.id, 0);
    setBubbleTick(t => t + 1); // 播放者变化 → 同步世界气泡（单例）
    bump(v => v + 1);
    typingTimerRef.current = setInterval(() => {
      const cur = revealRef.current.get(msg.id) ?? 0;
      if (cur + 1 >= msg.text.length) {
        // 播完 → done + 句间停顿后播下一段（严格串行）        finishPlaying();
        nextTimerRef.current = setTimeout(() => {
          nextTimerRef.current = null;
          startNext();
    }, playbackTiming.pauseMs);
      } else {
        revealRef.current.set(msg.id, cur + 1);
        bump(v => v + 1);
      }
    }, playbackTiming.tickMs);
  };

  /** 出队播下一段（队列严格串行：只有当前段播完/被跳过后才轮到下一段） */
  const startNext = () => {
    if (playingRef.current) return;
    if (pausedRef.current) return; // 输入框有字 → 保持暂停
  const next = queueRef.current.shift();
    if (!next) return;
    startTyping(next);
  };

  // 入队：世界对话已拍平清洗（见 worldMsgs memo），这里只负责去重入"+ 空文本跳过）。
  useEffect(() => {
    for (const m of worldMsgs) {
      if (m.status !== 'pending') continue;
      const sig = m.id.slice(2);
      if (worldSigRef.current.has(sig)) continue; // 已登记，避免重复入队
      worldSigRef.current.set(sig, 'pending');
      if (!m.text) { worldSigRef.current.set(sig, 'done'); continue; }
      queueRef.current.push(m);
    }
    if (!playingRef.current && !pausedRef.current) startNext();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [worldMsgs]);

  // 输入框有字或页面切到后台（切换浏览器/标签）都冻结本地回放；后端 AI 仍按自己的时钟继续。
  const inputPaused = chatInput.trim().length > 0;
  const paused = inputPaused || pageHidden;
  useEffect(() => {
    const syncVisibility = () => setPageHidden(document.hidden);
    const markHidden = () => setPageHidden(true);
    document.addEventListener('visibilitychange', syncVisibility);
    window.addEventListener('blur', markHidden);
    window.addEventListener('focus', syncVisibility);
    return () => {
      document.removeEventListener('visibilitychange', syncVisibility);
      window.removeEventListener('blur', markHidden);
      window.removeEventListener('focus', syncVisibility);
    };
  }, []);
  useEffect(() => {
    pausedRef.current = paused;
    if (paused) {
      if (pauseStartRef.current == null) pauseStartRef.current = Date.now();
      clearPlaybackTimers(); // 冻结：打字机逐字 + 下一段定时都清）    } else {
      pauseStartRef.current = null;
      if (playingRef.current) {
        // 从冻结位置继续逐字（revealRef 保留进度"
  const msg = playingRef.current;
        typingTimerRef.current = setInterval(() => {
          const cur = revealRef.current.get(msg.id) ?? 0;
          if (cur + 1 >= msg.text.length) {
            finishPlaying();
            nextTimerRef.current = setTimeout(() => {
              nextTimerRef.current = null;
              startNext();
        }, playbackTiming.pauseMs);
          } else {
            revealRef.current.set(msg.id, cur + 1);
            bump(v => v + 1);
          }
        }, playbackTiming.tickMs);
      } else {
        startNext();
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paused, isObserverPlayback]);

  // C-2：60s 暂停超时看门狗 —— 输入框持续有字且无操作 → 跳过当前句（跳 done，继续下一段）
  useEffect(() => {
    const wd = setInterval(() => {
      if (inputPaused && pauseStartRef.current != null && Date.now() - pauseStartRef.current >= simChatConfig.pauseTimeoutMs) {
        pauseStartRef.current = null; // 重置，避免同一暂停期连续跳
  if (playingRef.current) finishPlaying();
        // 输入框仍有字 → 保持暂停等恢复；无字 → 播下一一轮
  if (!pausedRef.current) startNext();
      }
    }, 1000);
    return () => clearInterval(wd);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inputPaused]);

  // 一般模式的世界消息只进入右侧群聊面板；地图上不显示任何聊天气泡。
  useEffect(() => {
    const sc = gameRef.current?.scene.getScene('SimulationScene') as SimulationScene | null;
    sc?.setBubbleFilter('__map_bubbles_hidden__');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bubbleTick]);

  // P-0814-B：统一按时间戳归并排序渲染（玩家回声与 SSE 世界消息同序列，消灭「拼在末尾」乱序）——
  // worldMsgs.ts=首见时刻（与 localMsgs 的 Date.now 同一时间轴）；同 ts 保持插入序（数组 sort 稳定）
  const allMsgs = useMemo(() => {
    return [...worldMsgs, ...localMsgs].sort((a, b) => (a.ts ?? 0) - (b.ts ?? 0));
  }, [worldMsgs, localMsgs]);
  /** 右侧只呈现当前被点击/加入的组；没有选组时不泄露全世界混杂对话。 */
  const activeMsgs = useMemo(() => {
    const groupId = joinedGroup?.id;
    if (!groupId) return [];
    return allMsgs.filter(m => m.group === groupId || (m.kind !== 'world' && m.group === groupId));
  }, [allMsgs, joinedGroup?.id]);
  const historyByGroup = useMemo(() => {
    const buckets = new Map<string, SimChatMsg[]>();
    for (const msg of allMsgs) {
      if (!msg.group) continue;
      const list = buckets.get(msg.group) || [];
      list.push(msg);
      buckets.set(msg.group, list);
    }
    return [...buckets.entries()].sort((a, b) => (b[1][b[1].length - 1]?.ts ?? 0) - (a[1][a[1].length - 1]?.ts ?? 0));
  }, [allMsgs]);
  const historyByTime = useMemo(() => {
    const buckets = new Map<string, SimChatMsg[]>();
    for (const msg of allMsgs) {
      const label = new Date(msg.ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
      const list = buckets.get(label) || [];
      list.push(msg);
      buckets.set(label, list);
    }
    return [...buckets.entries()];
  }, [allMsgs]);
  /** 每个未旁听会话组只保留最早一句作为地图预览，点击旁听后该组预览收起，避免气泡刷屏。 */
  const conversationPreviews = useMemo(() => {
    const selectedId = joinedGroup?.id;
    return groups.flatMap(g => {
      if (!g.id || g.id === selectedId) return [];
      const first = worldMsgs.find(m => m.group === g.id);
      return first ? [{ agentName: first.who, text: first.text }] : [];
    });
  }, [groups, worldMsgs, joinedGroup?.id]);

  useEffect(() => {
    const sc = gameRef.current?.scene.getScene('SimulationScene') as SimulationScene | null;
    sc?.setConversationPreviews(conversationPreviews);
  }, [conversationPreviews]);

  // 新消息自动滚动到底部（C-2：仅在新消息到达或本就在底部时滚动，避免打字机逐字刷新时打断上翻阅读）
  const prevLenRef = useRef(0);
  useEffect(() => {
    const el = chatListRef.current;
    if (!el) return;
    const lenIncreased = activeMsgs.length !== prevLenRef.current;
    prevLenRef.current = activeMsgs.length;
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
    if (lenIncreased || nearBottom) el.scrollTop = el.scrollHeight;
  }, [activeMsgs.length, chatOpen, bump]);

  const pushLocal = (m: { who: string; text: string; kind: 'player' | 'system' }) => {
    setLocalMsgs(prev => [...prev, { id: 'l-' + (localSeqRef.current++), who: m.who, text: m.text, kind: m.kind, status: 'done' as ChatMsgStatus, ts: Date.now() }]);
  };

  const control = async (path: string, label: string) => {
    try {
      await fetch('/api/simulation/' + path, { method: 'POST' });
      setStatus(label + ' 成功');
      const r = await fetch('/api/simulation/state');
      const d = await r.json();
      const sc = gameRef.current?.scene.getScene('SimulationScene') as SimulationScene | null;
      sc?.applySnapshot(d);
      setRunning(Boolean(d.running));
    } catch (e: any) {
      setStatus(label + ' 失败: ' + (e?.message || ''));
    }
  };

  const changeScene = async (name: string) => {
    setCurrentScene(name);
    try {
      await fetch('/api/simulation/scene/' + name, { method: 'POST' });
      setStatus('场景切换: ' + name);
      const r = await fetch('/api/simulation/state');
      const d = await r.json();
      const sc = gameRef.current?.scene.getScene('SimulationScene') as SimulationScene | null;
      sc?.applySnapshot(d);
    } catch (e: any) {
      setStatus('切换失败: ' + (e?.message || ''));
    }
  };

  // ── P-0803-G：群组状态轮询（conveconversation-status → 群组加入/离开入口；join/leave 后手动触发即时刷新） ──
  const fetchGroups = useCallback(async () => {
    try {
      const r = await fetch('/api/simulation/conversation-status');
      const d = await r.json();
      const list = (d.groups || []) as SimGroup[];
      setGroups(list);
      // currentTrack 只对真正加入世界的玩家有意义。导演旁听没有 currentTrack；此前每 4 秒
      // 把已选旁听组重置为 null，导致右侧只闪出几行便自动关闭。
      const track = d.currentTrack ? String(d.currentTrack) : '';
      const hasPlayer = Boolean((playerNameRef.current || '').trim())
        && worldAgentsRef.current.includes((playerNameRef.current || '').trim());
      if (hasPlayer) {
        setJoinedGroup(track ? (list.find(g => g.id === track) ?? null) : null);
      } else {
        setJoinedGroup(previous => previous
          ? (list.find(g => g.id === previous.id) ?? previous)
          : null);
      }
      const sc = gameRef.current?.scene.getScene('SimulationScene') as SimulationScene | null;
      const pn = ((playerNameRef.current || '').trim()) || 'me';
      // 玩家角色在场（世界角色列表含玩家名）→ 场景叠加「加入对话」入口；不在场不显示
      sc?.applyGroups(list, { playerName: pn, playerInWorld: worldAgentsRef.current.includes(pn) });
    } catch { /* 忽略 */ }
  }, []);
  /**
   * P-0813-G：退出对话 → Gal 面板淡出/隐藏，玩家回到自由探索。
   * 面板卸载 → exitLiveMode + 清 liveSayOverride；再次点击 NPC 重新进入（worldMsgs 重喂）。
   * P-0813-K：玩家正在群聊（joinedGroup）→ 先调 leave API 退出群（成员移除/状态恢复），再收起面板。
   */
  const exitConversation = useCallback(() => {
    const pn = ((playerNameRef.current || '').trim()) || 'me';
    const gid = joinedGroup?.id;
    if (gid) {
      // 退出群回自由探索（不阻塞 UI；失败提示走 handleGroupAction 同款角标）
      void api.leaveConversation(gid, pn).then(r => {
        if (r && r.status === 'ok') {
          pushLocal({ who: '系统', text: `👋 已退出群聊｜成员：${((r.group?.participants) || []).join('、') || '无'}`, kind: 'system' });
          setJoinMsg({ kind: 'ok', text: '👋 已退出群聊' });
        } else {
          const msg = (r && (r.message as string)) || '未知错误';
          setJoinMsg({ kind: 'error', text: `❌ 退出群失败：${msg}` });
        }
        fetchGroups();
      }).catch((e: any) => {
        setJoinMsg({ kind: 'error', text: `❌ 退出群失败：${e?.message || '网络错误'}` });
      });
    }
    setGalOpen(false);
    setChatOpen(false);
    setPendingLines([]); // 丢弃未消费的问候/提示（下次进入重新生成）
  }, [joinedGroup, fetchGroups]);

  /**
   * P-0803-G：群组「加入/离开对话」按钮点击后join/leave API → 成功/失败可见提示；手动刷新一次状态。   * 后端错误（组不存在/重复加入/已在组等）message 原样展示（聊天面板系统消耗+ 地图角标）：   */
  const handleGroupAction = useCallback(async (groupId: string, action: 'join' | 'leave' | 'observe') => {
    if (action === 'observe') {
      // 群组按钮来自上一帧快照，4 秒轮询间可能已换组；旁听不改后端状态，保留该 id 即可
      // 让右侧先打开并等待下一条同 group 消息，而不是把每一次迟到点击判成失败。
      const selected = groups.find(g => g.id === groupId) ?? { id: groupId, participants: [] };
      setJoinedGroup(selected);
      setGalView(false);
      setGalOpen(false);
      setChatOpen(true);
      setJoinMsg({ kind: 'ok', text: `正在旁听：${selected.participants?.join('、') || '会话组'}` });
      return;
    }
    const hasPlayer = Boolean((playerNameRef.current || '').trim());
    // 导演模式没有可加入世界的玩家角色：点组只打开经典观察视图，绝不伪造 "me" 发言或加入轨道。
    // AI 组本身仍由后端 ConversationManager 正常自动推进。
    if (!hasPlayer) {
      const selected = groups.find(g => g.id === groupId) ?? null;
      if (action === 'join' && selected) {
        setJoinedGroup(selected);
        setGalView(false);
        setGalOpen(false);
        setChatOpen(true);
        setJoinMsg({ kind: 'ok', text: `正在旁听会话组：${selected.participants?.join('、') || selected.id}` });
      } else if (action === 'leave') {
        setJoinedGroup(null);
        setChatOpen(false);
      }
      return;
    }
    const pn = ((playerNameRef.current || '').trim()) || 'me';
    try {
      const r = action === 'join'
        ? await api.joinConversation(groupId, pn)
        : await api.leaveConversation(groupId, pn);
      if (r && r.status === 'ok') {
        const members = ((r.group?.participants) || []) as string[];
        pushLocal({ who: '系统', text: `${action === 'join' ? '✅ 已加入对话组' : '👋 已离开对话组'}｜成员：${members.join('、') || '无'}`, kind: 'system' });
        setJoinMsg({ kind: 'ok', text: action === 'join' ? '✅ 已加入对话组' : '👋 已离开对话组' });
        // P-0813-K：加入群 → 展开 Gal 面板进入群聊；离开群 → 面板回到自由对话模式
        if (action === 'join') {
          setChatOpen(true);
          setGalOpen(true);
        } else {
          setGalOpen(false);
          setChatOpen(false);
        }
      } else {
        const msg = (r && (r.message as string)) || '未知错误';
        pushLocal({ who: '系统', text: `${action === 'join' ? '❌ 加入对话失败' : '❌ 离开对话失败'}：${msg}`, kind: 'system' });
        setJoinMsg({ kind: 'error', text: `${action === 'join' ? '❌ 加入失败' : '❌ 离开失败'}：${msg}` });
      }
    } catch (e: any) {
      pushLocal({ who: '系统', text: `${action === 'join' ? '❌ 加入对话失败' : '❌ 离开对话失败'}：${e?.message || '网络错误'}`, kind: 'system' });
      setJoinMsg({ kind: 'error', text: `${action === 'join' ? '❌ 加入失败' : '❌ 离开失败'}：${e?.message || '网络错误'}` });
    }
    // 手动触发一个conversation-status 刷新（既有 4s 轮询兜底，这里保存 UI 即时反馈）    fetchGroups();
  }, [fetchGroups, groups]);

  // P-0803-G：join/leave 角标提示自动消失（4.5s）
  useEffect(() => {
    if (!joinMsg) return;
    const t = setTimeout(() => setJoinMsg(null), 4500);
    return () => clearTimeout(t);
  }, [joinMsg]);

  // ── P0-3/C-1：右侧聊天面板发言——以玩家角色身份参2D 世界说话（复点legacy /api/simulation/send/{name}，后端零改动运──
  const sendChat = async () => {
    const msg = chatInput.trim();
    if (!msg) return;
    setChatInput(''); // 发送 → 输入框清空 → 暂停解除（恢复播放）
    // 找玩家角色：显式 playerName → 'me'/'你'/'主人' → 第一个角色
  const names = characters.map(c => c && c.name).filter(Boolean) as string[];
    let meName = playerName && names.includes(playerName) ? playerName : '';
    if (!meName) meName = names.find(n => n === 'me' || n === '我' || n === '主人') || '';
    if (!meName && names.length > 0) meName = names[0];
    pushLocal({ who: '系统', text: msg, kind: 'player' });
    if (!meName) {
      pushLocal({ who: '系统', text: '没有可发言的角色', kind: 'system' });
      return;
    }
    try {
      await fetch('/api/simulation/send/' + encodeURIComponent(meName), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: msg }),
      });
    } catch (e: any) {
      pushLocal({ who: '系统', text: '发送失败 ' + (e?.message || ''), kind: 'system' });
    }
  };

  // ── P3-10：演讲广播 demo（从 ScenePage 场景设置迁入，精简版） ──
  useEffect(() => {
    api.broadcastModeGet().then(r => setBroadcastMode(r.mode || 'merged')).catch(() => {});
  }, []);

  const triggerAiSpeech = async () => {
    setDemoBusy(true);
    setDemoResult('');
    try {
      const r = await api.simulationSpeech(undefined, demoMsg.trim() || undefined);
      if (r.status === 'error') setDemoResult('✅' + (r.message || '失败'));
      else setDemoResult(`✅ ${r.speaker} → ${r.mode === 'speech' ? '🎙 演讲（区域，听众判定通过）' : '📢 全局广播（无听众）'}：${(r.text || '').slice(0, 24)}…`);
    } catch (e: any) {
      setDemoResult('❌' + (e.message || '请求失败'));
    }
    setDemoBusy(false);
  };

  const sendPlayerBroadcast = async () => {
    const text = demoMsg.trim();
    if (!text) { setDemoResult('⚠️ 先输入广播内容'); return; }
    setDemoBusy(true);
    try {
      const r = await api.announcementSend(text, { level: 'PLAYER', channel: 'global', mode: 'announcement', speaker: playerName || '玩家' });
      setDemoResult(`✅ 公告已发出（${r.level}/${r.channel}）— 所有在线玩家将看到横幅`);
    } catch (e: any) {
      setDemoResult('❌' + (e.message || '请求失败'));
    }
    setDemoBusy(false);
  };

  // 演讲广播模式切换：同一运行实例运行时切换（merged=正式版/ auto=方案A 回退 / split=方案B 回退出
  const switchBroadcastMode = async (m: string) => {
    if (m === broadcastMode) return;
    setDemoBusy(true);
    try {
      const r = await api.broadcastModeSet(m);
      if (r.status === 'ok') {
        setBroadcastMode(r.mode);
        const label = r.mode === 'merged' ? '正式版merged' : r.mode === 'split' ? '方案B（内联区域广播）' : '方案A（回调判定）';
        setDemoResult(`🔄 已切换为${label}——下一轮演讲生效`);
      } else {
        setDemoResult('✅' + (r.message || '切换失败'));
      }
    } catch (e: any) {
      setDemoResult('❌' + (e.message || '请求失败'));
    }
    setDemoBusy(false);
  };

  const statusTag = (m: SimChatMsg) => {
    if (m.status === 'pending') return <span className="status-tag">⏳ 待播放</span>;
    if (m.status === 'playing') return <span className="status-tag">▶ 播放</span>;
    return null;
  };

  // P-0816-D：地图显示太小修复——height 未显式指定时进入自适应模式（宿主高度=视口剩余，
  // Phaser Scale.FIT 随容器放大画布显示尺寸；此前固定 height=560 时 FIT 受高度瓶颈限制，
  // 画布显示宽度恒 ≤933px（1920 屏上只占半屏），实测 scaleFactor=0.9333 受限高度）。
  // 显式 height（如经典视图 420）保持原行为零变化。
  const adaptiveHeight = height == null;
  return (
    <div
      className="phaser-sim-view"
      style={{
        position: 'relative', border: '1px solid var(--border, #2b3854)', borderRadius: 10, overflow: 'hidden', background: 'var(--bg)',
        display: 'flex', flexDirection: 'column',
        height: adaptiveHeight ? 'calc(100vh - 48px)' : undefined,
      }}
    >
      {/* 控制条*/}
      <div style={{ display: 'flex', gap: 8, padding: '8px 12px', background: 'var(--panel-2)', alignItems: 'center', flexWrap: 'wrap' }}>
        <span style={{ fontSize: 13, color: 'var(--phase-investigation)', fontWeight: 600 }}>2D 模拟（Phaser 渲染层）</span>
        <select
          className="input"
          style={{ width: 120, padding: '3px 6px', fontSize: 12 }}
          value={currentScene}
          onChange={e => changeScene(e.target.value)}
        >
          {AVAILABLE_SCENES.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
        <button className="btn btn-small" disabled={running} onClick={() => control('start', '开始')}>▶ 开始</button>
        <button className="btn btn-small" disabled={!running} onClick={() => control('stop', '暂停')}>⏸ 暂停</button>
        <button className="btn btn-small btn-danger" onClick={() => control('reset', '重置')}>🔄 重置</button>
        {/* P1-8：公告栏显示开关（横幅+公告栏仅在 2D 视图内出现，可一键隐藏/显示开关*/}
        <button
          className={`btn btn-small ${annShow ? '' : 'btn-danger'}`}
          onClick={toggleAnnShow}
          title="公告栏显示开关（AI 演讲/广播横幅与公告栏）">
          📢 {annShow ? '开' : '关'}
        </button>
        {joinedGroup && (
          <button
            className={`btn btn-small ${chatOpen ? 'btn-primary' : ''}`}
            onClick={() => setChatOpen(v => !v)}
            title={chatOpen ? '收起当前会话组' : '打开当前会话组'}
          >
            💬 会话组 {chatOpen ? '✕' : '💬'}
          </button>
        )}
        <span style={{ fontSize: 12, color: 'var(--text-2, #93a1bd)' }}>{status}</span>
        {/* P-0816-A：滚轮缩放 + 全屏（对齐预览地图能力；缩放后相机跟随玩家，点击坐标已转世界坐标） */}
        <span style={{ fontSize: 11, color: 'var(--text-3)' }} title="滚轮缩放（1~2×，>1 跟随玩家）；点击/方向键移动不受影响">🔍 滚轮缩放</span>
        <button className="btn btn-small" onClick={toggleFullscreen} title="全屏浏览 2D 世界（Esc 退出）">⛶ 全屏</button>
      </div>

      {/* P3-10：演讲广播 demo（精简版，默认折叠）*/}
      <div style={{ borderTop: '1px solid var(--border)', background: 'var(--bg)' }}>
        <div
          style={{ padding: '6px 12px', fontSize: 12, color: 'var(--text-2)', cursor: 'pointer', userSelect: 'none', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
          onClick={() => setDemoOpen(!demoOpen)}
        >
          <span>🎙 演讲 + 广播（demo：AI 演讲 / 玩家广播 / 模式切换）</span>
          <span>{demoOpen ? '⏶ 收起' : '⏷ 展开'}</span>
        </div>
        {demoOpen && (
          <div style={{ padding: '0 12px 10px' }}>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
              <input
                className="input"
                style={{ flex: 1, minWidth: 200, fontSize: 12, padding: '5px 8px' }}
                placeholder="发言/广播内容（留空用默认演示文案）"
                value={demoMsg}
                onChange={e => setDemoMsg(e.target.value)}
              />
              <button className="btn btn-small" disabled={demoBusy} onClick={triggerAiSpeech} title="AI 自动选择演讲（有听众→区域）或广播（无听众→全局广播）">
                🎙 AI 自动演讲
              </button>
              <button className="btn btn-small btn-primary" disabled={demoBusy} onClick={sendPlayerBroadcast} title="玩家发全员公告（横幅）">
                📣 玩家发广播              </button>
              <select
                className="input"
                style={{ width: 158, padding: '3px 6px', fontSize: 12 }}
                value={broadcastMode}
                onChange={e => switchBroadcastMode(e.target.value)}
                title="演讲广播模式（merged=正式版HearingSystem 声学判定 / auto=方案A 回调 / split=方案B 内联区域广播）"
              >
                <option value="merged">⭐ 正式版（merged）</option>
                <option value="auto">方案A（回调判定）</option>
                <option value="split">方案B（内联区域）</option>
              </select>
            </div>
            {demoResult && <div style={{ marginTop: 6, fontSize: 12, color: 'var(--color-success)' }}>{demoResult}</div>}
          </div>
        )}
      </div>

      {/* C-1：主体 —— 左地图（空间充足）+ 右聊天面板（可折叠）；自适应模式 flex:1 撑满剩余视口 */}
      <div style={{ display: 'flex', alignItems: 'stretch', flex: adaptiveHeight ? 1 : undefined, minHeight: adaptiveHeight ? 0 : undefined }}>
        {/* 左：地图 + 公告覆盖。*/}
        <div style={{ flex: 1, minWidth: 0, position: 'relative', display: 'flex', flexDirection: 'column' }}>
          <div ref={hostRef} style={{ width: '100%', height: adaptiveHeight ? undefined : height, flex: adaptiveHeight ? 1 : undefined, minHeight: adaptiveHeight ? 0 : undefined }} />
          {/* P1-8：公告横幅 + 公告栏 —— 仅在 2D 游戏视图内挂载（不再 App.tsx 全局常驻右上角），              绝对定位覆盖地图上方「ann-*.inline），由「📢」开关控制显示开*/}
          {annShow && (
            <>
              <AnnouncementBanner inline />
              <AnnouncementTicker inline />
            </>
          )}
          {/* P-0813-G：接近提示 DOM 层叠（玩家靠近 NPC → 底部居中提示，点击 NPC 进入对话）
              P-0813-K：靠近对话群 → 「👥 加入对话」提示（优先于 NPC 提示，两者并存不打架） */}
          {galChat && approachGroups.length > 0 && (
            <div className="sim-approach-toast sim-approach-toast-group">
              <span className="sim-approach-toast-icon">👥</span>
              <span>靠近了对话中的群 —— 点击「加入对话」进群聊天</span>
            </div>
          )}
          {galChat && approachGroups.length === 0 && approachNames.length > 0 && (
            <div className="sim-approach-toast">
              <span className="sim-approach-toast-icon">💬</span>
              <span>靠近了 {approachNames.join('、')} —— 点击 {approachNames.length === 1 ? 'ta' : '角色'} 开始对话</span>
            </div>
          )}
          {/* P-0803-G：加入/离开对话结果角标（后端错误message 可见提示词.5s 自消耗*/}
          {joinMsg && (
            <div
              style={{
                position: 'absolute', left: 10, bottom: 10, zIndex: 20,
                padding: '6px 12px', borderRadius: 6, fontSize: 12, maxWidth: '70%',
                background: joinMsg.kind === 'ok' ? 'rgba(16,185,129,0.92)' : 'rgba(239,68,68,0.92)',
                color: '#fff', boxShadow: '0 2px 8px rgba(0,0,0,0.45)',
              }}
            >
              {joinMsg.text}
            </div>
          )}
        </div>
        {/* 右：聊天面板（对话历史+ 发言输入；收起时地图全宽）*/}
        {chatOpen && (
          <div className="sim-chat-panel">
            <div
              style={{ padding: '6px 12px', fontSize: 12, color: 'var(--text-2)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottom: '1px solid var(--border)', flexShrink: 0 }}
            >
              <span title={simChatConfigSummary()}>
                💬 {joinedGroup?.participants?.join('、') || '会话组'} {activeMsgs.length > 0 ? `（${activeMsgs.length} 条）` : ''}
                {isObserverPlayback ? ` · 旁听 ${playbackTiming.charsPerSec}字/秒` : ''}
              </span>
              <span style={{ display: 'flex', gap: 6 }}>
                <button className="btn btn-small" onClick={() => setHistoryOpen(v => !v)} title="按会话组或时间查看已收到的记录">🗂 记录</button>
                {/* P-0813-G：对话中 → 「退出对话」按钮（回到自由探索；面板淡出/隐藏） */}
                {galChat && galView && galOpen && (
                  <button className="btn btn-small sim-gal-exit" onClick={exitConversation} title="退出对话，回到自由探索">🚪 退出对话</button>
                )}
                {/* P-0813-D：Gal 式对话 / 经典列表 切换（galChat 时默认 Gal 视图，可切回列表） */}
                {galChat && (
                  <button className="btn btn-small" onClick={() => setGalView(v => !v)} title="切换对话区样式">
                    {galView ? '📋 列表' : '🎮 Gal'}
                  </button>
                )}
                <button className="btn btn-small" onClick={() => setChatOpen(false)} title="收起聊天面板（地图全宽）">✕</button>
              </span>
            </div>
            {galChat && galView ? (
              /* P-0813-G：对话中 → Gal 式对话区（淡入）；未对话 → 提示占位（面板不渲染） */
              galOpen ? (
                <div className="sim-gal-panel" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1 }}>
                  <SimGalChatPanel
                    playerName={playerName}
                    worldMsgs={worldMsgs}
                    sendText={simSend}
                    pendingLines={pendingLines}
                    onConsumedLine={l => setPendingLines(prev => prev.filter(x => x !== l))}
                    /* P-0813-K：群聊模式数据（群名/成员列表；null = 自由对话模式不显示群头） */
                    groupInfo={joinedGroup && joinedGroup.id ? { id: joinedGroup.id, mode: joinedGroup.mode, participants: joinedGroup.participants, topic: joinedGroup.topic } : undefined}
                  />
                </div>
              ) : (
                <div className="sim-gal-idle">
                  <div className="sim-gal-idle-icon">🎮</div>
                  <div className="sim-gal-idle-title">尚未进入对话</div>
                  <div className="sim-gal-idle-hint">
                    点击地图上的会话组并加入后，
                    才会在这里显示该组内容与发言框。
                  </div>
                </div>
              )
            ) : (
              <>
                <div className="sim-chat-list" ref={chatListRef}>
                  {activeMsgs.length === 0 && (
                    <div style={{ color: 'var(--text-3)', padding: '4px 0', lineHeight: 1.7 }}>
                      正在旁听该组。AI 会在下一次调度后自动发言；当前成员：{joinedGroup?.participants?.join('、') || '未知'}。
                    </div>
                  )}
                  {activeMsgs.map(m => (
                    <div key={m.id} className={`sim-chat-msg kind-${m.kind} status-${m.status}${isSilenceText(m.text) ? ' silence' : ''}`}>
                      <strong className="who">{m.who}：</strong>
                      {/* C-2：播放中的消息按打字机进度逐字显示（revealRef 驱动，3 字/秒）；静默占位（……（沉默））渲染为静默样式。*/}
                      {m.status === 'playing'
                        ? <>{m.text.slice(0, revealRef.current.get(m.id) ?? 0)}<span className="typewriter-caret">▌</span></>
                        : (isSilenceText(m.text) ? <SilenceTurn /> : m.text)}
                      {statusTag(m)}
                    </div>
                  ))}
                </div>
                {historyOpen && (
                  <div style={{ borderTop: '1px solid var(--border)', padding: '8px 10px', maxHeight: 190, overflowY: 'auto', background: 'var(--panel-2)', flexShrink: 0 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 7, fontSize: 12 }}>
                      <strong>旁听记录</strong>
                      <span style={{ display: 'flex', gap: 4 }}>
                        <button className={`btn btn-small ${historyMode === 'group' ? 'btn-primary' : ''}`} onClick={() => setHistoryMode('group')}>按组</button>
                        <button className={`btn btn-small ${historyMode === 'time' ? 'btn-primary' : ''}`} onClick={() => setHistoryMode('time')}>按时间</button>
                      </span>
                    </div>
                    {historyMode === 'group' ? historyByGroup.map(([groupId, messages]) => (
                      <button
                        key={groupId}
                        className="sim-history-row"
                        onClick={() => {
                          setJoinedGroup(groups.find(g => g.id === groupId) ?? { id: groupId, participants: [] });
                          setHistoryOpen(false);
                        }}
                      >
                        <strong>{groupId}</strong><span>{messages.length} 条 · {new Date(messages[messages.length - 1].ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</span>
                      </button>
                    )) : historyByTime.map(([time, messages]) => (
                      <div className="sim-history-row sim-history-time" key={time}>
                        <strong>{time}</strong><span>{messages.map(m => `${m.group || '系统'} · ${m.who}`).join('；')}</span>
                      </div>
                    ))}
                  </div>
                )}
                {Boolean(playerName?.trim()) ? (
                  <div style={{ display: 'flex', gap: 6, padding: '8px 10px', borderTop: '1px solid var(--border)', background: 'var(--panel-2)', flexShrink: 0 }}>
                    <input
                      className="input"
                      style={{ flex: 1, fontSize: 12, padding: '5px 8px' }}
                      placeholder={`以 ${playerName || characters[0]?.name || '玩家'} 的身份对 2D 世界说话（输入时暂停播放）..`}
                      value={chatInput}
                      onChange={e => setChatInput(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter') sendChat(); }}
                    />
                    <button className="btn btn-small btn-primary" onClick={sendChat}>发送</button>
                  </div>
                ) : (
                  <div style={{ padding: '9px 10px', borderTop: '1px solid var(--border)', color: 'var(--text-3)', fontSize: 12, background: 'var(--panel-2)' }}>
                    导演旁听模式：AI 会话会自动继续，加入玩家角色后才可发言。
                  </div>
                )}
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
