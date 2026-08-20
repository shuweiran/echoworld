/**
 * GalStore.ts — Gal 界面状态（P-0810-02，Zustand）
 *
 * 状态机：seq[index] → 选项节点（等待 GalChoiceBar）/ 打字机（等待 GalDialogBox 点击推进）
 * 数据流详见 docs/gal-界面设计.md §3。
 *
 * P-0815-F 批3（方向5）：模块级单例 → per-instance 化 ——
 *  - createGalStore() 工厂：每个 gal 面板宿主（GalGeneralView / ScriptGalChatPanel /
 *    SimGalChatPanel）自建实例，经 GalStoreProvider 注入子树，消除 enter/exitLiveMode
 *    跨面板互踩（P-0815-B 报告已知限制，未来同屏并存必需）；
 *  - defaultGalStore：无 Provider 的组件（调试）与模块函数（galSseAdapter
 *    缺省）兜底，行为与旧模块级单例一致；
 *  - useGalStore(selector)：上下文感知 hook（Provider 内绑定实例 / 外绑定默认单例）；
 *    useGalStoreApi()：命令式 getState/setState 取当前实例。
 */
import { createStore } from 'zustand/vanilla';
import { useStore } from 'zustand';
import { createContext, createElement, useContext } from 'react';
import type { ReactNode } from 'react';
import { GAL_SEQUENCE, GAL_SPEAKERS, SPEAKER_BACKEND_PROFILES, speakerName, backendIdForName, buildPlaceholderSpeaker, registerBackendMapping } from './galDemoData';
import type { GalChoice, GalMessage, GalSpeaker } from './galDemoData';
import {
  listStatus,
  registerCharacter,
  subscribeAiImageEvents,
  triggerGenerate,
} from '../api/aiImage';
import type { AiImageErrorPayload, AiImageReadyPayload } from '../api/aiImage';

export type GalMode = 'chat' | '2d';

export interface GalTyping {
  speakerId: string;
  full: string;
  chars: number;
  done: boolean;
}

export interface GalLogLine {
  id: string;
  speakerId: string;
  name: string;
  text: string;
  isPlayer?: boolean;
  /** P-0810-06：行时间戳（live 模式本地回显去重用） */
  ts?: number;
}

// ── P-0810-06：真实对局 SSE 直播状态 ────────────────────────────

/** 连接状态（GalLiveBridge → useSSE onStatus → store） */
export type GalLiveStatus = 'idle' | 'connecting' | 'open' | 'reconnecting';

/** 数据源：demo 假数据 / 真实对局 SSE（顶部切换） */
export type GalDataSource = 'demo' | 'live';

/** 对局类型（发言路由 + 事件过滤用；unknown=尚未探测到） */
export type GalGameType = 'unknown' | 'general' | 'script' | 'werewolf';

/** 直播队列消息（SSE → 打字机播放的中间形态） */
export interface GalLiveMessage {
  id: string;
  /** agent=AI 发言 / player=玩家回显 / system=公告旁白 */
  kind: 'agent' | 'player' | 'system';
  /** 后端 agent_name / 'player' / 'system' */
  speakerId: string;
  /** 展示名 */
  name: string;
  text: string;
  /** 流式缓冲中（agent_token 追加，agent_output 结算后置 false） */
  streamed?: boolean;
  /** announcement level（SYSTEM 金色高亮） */
  level?: string;
  ts: number;
}

// ── P-0810-03：Pony 立绘状态 ───────────────────────────────────

/**
 * P-0817-N：主控/旁白/系统类名字 → 旁白样式（不显示 TTS 播放按钮）。
 * SSE agent_output / /api/send 同步返回里主控（arbiter）消息的 agent_name 为「主控」等，
 * 若按普通角色入队会显示 🎙 播放按钮——主控叙述不属于角色语音，统一归旁白。
 */
export function isNarratorAgent(name: string): boolean {
  const n = String(name || '').trim().toLowerCase();
  return n === '主控' || n === '旁白' || n === '系统' || n === 'narrator' || n === 'arbiter' || n === 'system' || n === 'gm';
}

/** 后端生图角色（GET /api/ai-image/status 条目，立绘面板数据源） */
export interface GalBackendCharacter {
  id: string;
  name: string;
  appearance: string;
  /** frame → URL（avatar/happy/angry/sad/surprised/embarrassed/neutral） */
  images: Record<string, string>;
  task?: {
    status: string;
    progress?: string;
    error?: string;
  };
}

/** 单角色立绘状态（characterId → 本状态；驱动 GalCharacter 展示与面板） */
export interface GalPortraitState {
  backendId: string;
  name: string;
  /** 是否已在后端注册（预置角色启动即注册；露娜需前端注册） */
  registered: boolean;
  /** frame → URL（磁盘扫描结果，随生成进度增长） */
  frames: Record<string, string>;
  /** 当前展示帧（面板可切换，默认 avatar） */
  selectedFrame: string;
  /** 是否有运行中生成任务 */
  generating: boolean;
  /** 任务进度（当前帧名 / submitting / done / 空） */
  progress: string;
  error: string;
}

interface GalState {
  mode: GalMode;
  started: boolean;
  seq: GalMessage[];
  index: number;
  /** 当前展示消息（demo=GalMessage / live=GalLiveMessage） */
  current: GalMessage | GalLiveMessage | null;
  typing: GalTyping | null;
  log: GalLogLine[];
  choiceNode: GalMessage | null;
  speakers: GalSpeaker[];
  activeSpeakerId: string | null;
  finished: boolean;

  // P-0810-03：Pony 立绘
  /** 后端生图角色列表（面板数据源） */
  backendCharacters: GalBackendCharacter[];
  /** 立绘状态表（backendId → 状态） */
  portraits: Record<string, GalPortraitState>;
  /** SSE 订阅取消函数（内部，勿序列化） */
  _imageSub?: () => void;

  // P-0810-06：真实对局 SSE 直播
  /** 是否已连接真实对局（true=直播数据源，false=demo 数据源） */
  liveMode: boolean;
  /** 已连接的对局标识（session_id） */
  liveSessionId: string;
  /** 连接状态（useSSE onStatus 回写） */
  liveStatus: GalLiveStatus;
  /** 事件计数（连接状态展示） */
  liveEventCount: number;
  /** 对局类型（发言路由 + 全局事件过滤） */
  liveGameType: GalGameType;
  /** 当前阶段（剧本杀 phase / 狼人杀 phase，GalTopBar 展示） */
  livePhase: string;
  /** 剧本名/对局名（script_status） */
  liveScriptTitle: string;
  /** P-0810-07：一般模式分类（free/protagonist/multi_track/director；GET /api/mode 探测，空=未探到/查询失败） */
  liveGeneralMode: string;
  /** 待播放消息队列（SSE 入队 → 打字机播放） */
  liveQueue: GalLiveMessage[];
  /** agent → 流式 token 累计缓冲（agent_token 追加，agent_output 结算清空） */
  liveStreams: Record<string, string>;
  /** 当前玩家名（发言用） */
  livePlayerName: string;
  /** 剧本杀 roleKey（可选，身份校验） */
  livePlayerKey: string;
  /** P-0810-08：玩家（主控）发言不渲染气泡（呈现接管视图置 true；输入框保留发送即清空） */
  hidePlayerBubbles: boolean;
  /** 玩家发言请求中 */
  liveSending: boolean;
  /** 玩家发言最近错误（输入区提示） */
  liveSendError: string;
  /** P-0810-21：最近一次成功发送时间戳（输入区「已发送」反馈，不依赖玩家气泡回显） */
  liveLastSent: number;
  /** P-0810-21-D：玩家发言候选话术（一般模式玩家回合可选项；round_complete/进入时刷新，空=不显示） */
  liveSuggestions: string[];
  /** P-0813-D：2D 模拟视图注入的玩家发言发送器（替代 liveSay 路由——2D 世界发言走 /api/simulation/send）；
   *  缺省 undefined → GalInputArea/GalChoicesArea 走默认 liveSay（一般模式零影响） */
  liveSayOverride?: (text: string) => Promise<void>;
  /** P-0813-D：设置/清除 liveSayOverride（2D 视图挂载注入，卸载清除） */
  setLiveSayOverride: (fn?: (text: string) => Promise<void>) => void;
  /** P-0814-A/C：播放完毕自动推进 —— 新一轮「播出完毕」待推进标志（round_complete 置位；
   *  队列排空后由 useAutoPlaybackDone 自动 POST /api/simulation/playback_done → 清除）。
   *  后端幂等：非等待态重复信号被忽略。 */
  livePlaybackArmed: boolean;
  /** P-0814-A/C：设置/清除 livePlaybackArmed（round_complete 置位；自动推进 hook 消费/新会话清除）。 */
  setLivePlaybackArmed: (v: boolean) => void;
  /** 已生成占位的未知角色名（面板「可注册生成」数据源） */
  liveUnknownRoles: string[];
  /** P-0810-16：场景卡目标状态（后端 goals 视图 + scene_target_update 增量合并；null=无目标/未就绪） */
  liveGoals: GalSceneGoals | null;

  start: () => void;
  advance: () => void;
  skipTyping: () => void;
  choose: (c: GalChoice) => void;
  submitText: (text: string) => void;
  tick: (n: number) => void;
  setMode: (m: GalMode) => void;

  // P-0810-06：直播动作
  enterLiveMode: (sessionId: string, opts?: { playerName?: string; playerKey?: string }) => void;
  exitLiveMode: () => void;
  setLiveStatus: (s: GalLiveStatus) => void;
  setLiveGameType: (t: GalGameType, phase?: string, title?: string) => void;
  /** P-0810-07：一般模式分类写入（GET /api/mode 结果；free/protagonist/multi_track/director） */
  setLiveGeneralMode: (mode: string) => void;
  bumpLiveEvent: () => void;
  /** SSE 事件总入口（agent_output / agent_token / werewolf_speech / announcement / user_input / script_* / ai_image_*） */
  applySseEvent: (eventType: string, data: any) => void;
  /** P-0810-16：写入场景卡目标（起局响应 goals / /api/state scene_goals；空/未启用置 null） */
  setLiveGoals: (goals: any) => void;
  /** P-0810-16：scene_target_update SSE 增量合并（状态更新 + revealed 揭示全文去重累积） */
  applySceneTargetUpdate: (data: any) => void;
  /** 注册未知说话者为占位立绘角色（幂等） */
  liveEnsureSpeaker: (name: string) => void;
  /** agent_token 流式增量（缓冲 + 打字机实时渲染） */
  liveToken: (agent: string, delta: string) => void;
  /** agent_output 结算（完成当前流式句） */
  liveCompleteAgent: (agent: string, content: string) => void;
  /** 完整消息入队（公告/狼人杀发言/剧本杀轮询增量等） */
  liveEnqueue: (msg: Omit<GalLiveMessage, 'id' | 'ts'>) => void;
  /** 玩家本地回显（user_input 去重） */
  enqueuePlayerEcho: (text: string) => void;
  setLiveIdentity: (playerName?: string, playerKey?: string) => void;
  setSending: (v: boolean) => void;
  /** P-0810-08：玩家发言气泡开关（true=玩家消息不入队不渲染） */
  setHidePlayerBubbles: (v: boolean) => void;
  /** P-0810-21-D：写入玩家发言候选（条数 ≤4；空数组=隐藏选项条） */
  setLiveSuggestions: (s: string[]) => void;
  /** P-0810-08：替换舞台角色表（呈现接管视图用会话 roster 替换 demo 角色） */
  setSpeakers: (speakers: GalSpeaker[]) => void;

  // P-0810-03：立绘动作
  /** 拉取 /api/ai-image/status 合并到立绘状态（轮询 + 挂载刷新） */
  refreshImageStatus: () => Promise<void>;
  /** 触发生成（未注册且有档案的先注册；异步幂等） */
  generatePortrait: (backendId: string) => Promise<void>;
  /** 切换展示帧（头像/表情） */
  selectFrame: (backendId: string, frame: string) => void;
  /** SSE 事件入口（ready → 刷新状态；error → 置错） */
  applyImageEvent: (kind: 'ready' | 'error', payload: AiImageReadyPayload | AiImageErrorPayload) => void;
  /** 订阅 SSE（幂等，返回取消函数；页面卸载调 disposeImageEvents） */
  initImageEvents: () => () => void;
  /** 取消 SSE 订阅 */
  disposeImageEvents: () => void;
}

let logSeq = 0;

/**
 * P-0810-16：场景卡目标（一般模式 goals 视图结构，与后端 SceneGoalService 契约对齐）：
 * <pre>{@code
 * {
 *   enabled: boolean,                              // 会话是否有场景目标集
 *   player_goal?:  { desc: string, status?: string },  // 玩家目标明文
 *   global_goal?:  { desc: string, status?: string },  // 全局目标（AI 侧 ?? 占位，揭示后 desc 全文）
 *   role_goals?:   { <角色名>: { desc, status } },     // 每 AI 角色隐藏目标（?? 占位）
 *   ai_goal_count?: number,                        // AI 隐藏目标数量
 *   revealed?: string[]                            // 已揭示全文的目标 desc（scene_target_update 累积）
 * }
 * }</pre>
 */
export interface GalSceneGoals {
  enabled?: boolean;
  player_goal?: { desc: string; status?: string };
  global_goal?: { desc: string; status?: string };
  role_goals?: Record<string, { desc: string; status?: string }>;
  ai_goal_count?: number;
  revealed?: string[];
}

/** 目标状态中文标签（后端枚举 → 展示文案） */
export const GOAL_STATUS_LABEL: Record<string, string> = {
  NOT_STARTED: '未开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  FAILED: '已失败',
};

/** 后端目标掩码（AI 目标对玩家隐藏的占位符） */
export const GOAL_MASK = '??';

/**
 * P-0810-06：live 模式播放引擎——空闲时取出队列头开始打字机播放。
 * 流式消息（streamed）的完整文本取 liveStreams 缓冲（打字机逐字重放）。
 */
function ensureLivePlay(s: GalState, set: (p: Partial<GalState>) => void) {
  if (!s.liveMode || !s.started || s.finished) return;
  if (s.choiceNode) return;
  // 正在打字 / 已完成未点击 → 等待用户推进
  if (s.typing && !s.typing.done) return;
  if (s.current && s.typing && s.typing.done) return;
  // P-0810-08：呈现接管隐藏玩家气泡 → 队内残留的玩家消息直接丢弃不播放
  if (s.hidePlayerBubbles) {
    let idx = 0;
    while (idx < s.liveQueue.length && s.liveQueue[idx].kind === 'player') idx++;
    if (idx > 0) {
      const trimmed = s.liveQueue.slice(idx);
      set({ liveQueue: trimmed });
      s = { ...s, liveQueue: trimmed };
    }
  }
  const head = s.liveQueue[0];
  if (!head) return;
  const full = head.streamed ? (s.liveStreams[head.speakerId] || head.text) : head.text;
  set({
    current: head,
    typing: { speakerId: head.speakerId, full, chars: 0, done: full.length === 0 },
    activeSpeakerId: head.speakerId,
  });
}

/** 玩家本地回显去重：最近 10s 内同文本的玩家消息（api.send 会经 user_input 回显） */
function isRecentPlayerEcho(s: GalState, text: string): boolean {
  const now = Date.now();
  const q = s.liveQueue;
  if (q.length) {
    const t = q[q.length - 1];
    if (t.kind === 'player' && t.text === text && now - t.ts < 10000) return true;
  }
  const log = s.log;
  for (let i = log.length - 1; i >= Math.max(0, log.length - 4); i--) {
    const l = log[i];
    if (l.isPlayer && l.text === text && l.ts && now - l.ts < 10000) return true;
  }
  return false;
}

function revealNext(set: (p: Partial<GalState>) => void, get: () => GalState) {
  const { seq, index } = get();
  if (index >= seq.length) {
    set({ finished: true, current: null, choiceNode: null, typing: null, activeSpeakerId: null });
    return;
  }
  const msg = seq[index];
  set({ index: index + 1, current: msg });
  if (msg.type === 'choice') {
    // 轮到玩家：等选项条 / 自选输入
    set({ choiceNode: msg, typing: null, activeSpeakerId: 'player' });
  } else {
    // 角色/旁白：开始打字机
    set({
      choiceNode: null,
      typing: { speakerId: msg.speakerId, full: msg.text, chars: 0, done: msg.text.length === 0 },
      activeSpeakerId: msg.speakerId,
    });
  }
}

function pushLog(set: (p: Partial<GalState>) => void, get: () => GalState, line: Omit<GalLogLine, 'id'>) {
  const id = `log-${++logSeq}-${Date.now()}`;
  const next = [...get().log, { ...line, id, ts: line.ts ?? Date.now() }].slice(-40);
  set({ log: next });
}

/**
 * P-0815-E：live 队列推进一步——当前条入 log → 弹队首 → 播下一条。
 * advance 点击推进与玩家消息自动推进共用同一逻辑，保证行为一致。
 */
function advanceLiveMessage(set: (p: Partial<GalState>) => void, get: () => GalState) {
  const s = get();
  if (s.current) {
    const head = s.current as unknown as GalLiveMessage;
    const t = s.typing;
    const text = t ? t.full : (head.text || '');
    pushLog(set, get, {
      speakerId: head.speakerId,
      name: head.name || speakerName(head.speakerId),
      text,
      isPlayer: head.kind === 'player',
      ts: head.ts,
    });
  }
  set({ liveQueue: get().liveQueue.slice(1), current: null, typing: null });
  ensureLivePlay(get(), set);
}

/**
 * P-0815-E：玩家自己的消息播完（typing.done）即自动推进——任何置 done 的路径
 * （打字机自然播完 tick / 点击跳过 skipTyping / advance 打字分支）统一触发，
 * 保证玩家消息从不带「点击继续」等待态；AI 消息不受影响。
 */
function maybeAutoAdvancePlayerMessage(set: (p: Partial<GalState>) => void, get: () => GalState) {
  const s = get();
  if (!s.liveMode || !s.typing || !s.typing.done) return;
  const head = s.current as GalLiveMessage | null;
  if (head && head.kind === 'player') {
    advanceLiveMessage(set, get);
  }
}

export function createGalStore() {
  return createStore<GalState>()((set, get) => ({
  mode: 'chat',
  started: false,
  seq: GAL_SEQUENCE,
  index: 0,
  current: null,
  typing: null,
  log: [],
  choiceNode: null,
  speakers: GAL_SPEAKERS,
  activeSpeakerId: null,
  finished: false,
  backendCharacters: [],
  portraits: {},

  // P-0810-06：直播初始态
  liveMode: false,
  liveSessionId: '',
  liveStatus: 'idle',
  liveEventCount: 0,
  liveGameType: 'unknown',
  livePhase: '',
  liveScriptTitle: '',
  liveGeneralMode: '',
  liveQueue: [],
  liveStreams: {},
  livePlayerName: '',
  livePlayerKey: '',
  hidePlayerBubbles: false,
  liveSending: false,
  liveSendError: '',
  liveLastSent: 0,
  liveSuggestions: [],
  liveUnknownRoles: [],
  liveGoals: null,

  /** P-0813-D：默认无 override（一般模式走 liveSay；2D 模拟视图挂载时注入） */
  liveSayOverride: undefined,
  /** P-0814-A：默认无待推进（round_complete 置位；自动推进 hook 消费/新会话清除） */
  livePlaybackArmed: false,

  start: () => {
    // P-0810-06：真实对局模式下不重置（防直播状态被 demo 重置清空）
    if (get().liveMode) return;
    logSeq = 0;
    set({ started: true, finished: false, index: 0, current: null, typing: null, log: [], choiceNode: null, activeSpeakerId: null });
    revealNext(set, get);
  },

  /** 主推进：打字中→完成；完成→本条入 log 并推下一条；选项节点→忽略（等选择） */
  advance: () => {
    const s = get();
    if (!s.started || s.finished) return;
    if (s.choiceNode) return;
    const t = s.typing;
    if (t && !t.done) {
      set({ typing: { ...t, chars: t.full.length, done: true } });
      // P-0815-E：玩家消息被点击跳过时同样直接推进（不留「点击继续」等待态）
      maybeAutoAdvancePlayerMessage(set, get);
      return;
    }
    if (s.liveMode) {
      // live 分支：完成态点击 → 入 log → 弹队首 → 播下一条
      advanceLiveMessage(set, get);
      return;
    }
    if (t && t.done && s.current) {
      pushLog(set, get, {
        speakerId: s.current.speakerId,
        name: speakerName(s.current.speakerId),
        text: t.full,
        isPlayer: s.current.speakerId === 'player',
      });
      set({ typing: null });
    }
    revealNext(set, get);
  },

  /** 点击跳过：直接把当前打字机置为完成（等价 advance 的打字分支） */
  skipTyping: () => {
    const s = get();
    const t = s.typing;
    if (s.started && !s.finished && t && !t.done) {
      set({ typing: { ...t, chars: t.full.length, done: true } });
      // P-0815-E：玩家消息同样直接推进
      maybeAutoAdvancePlayerMessage(set, get);
    }
  },

  /** 玩家选中选项：玩家发言入 log → goto 跳转或顺延 */
  choose: (c: GalChoice) => {
    const s = get();
    if (!s.choiceNode) return;
    pushLog(set, get, { speakerId: 'player', name: speakerName('player'), text: `▶ ${c.text}`, isPlayer: true });
    if (c.goto !== undefined) {
      set({ choiceNode: null, index: c.goto });
    } else {
      set({ choiceNode: null });
    }
    revealNext(set, get);
  },

  /** 玩家自由输入（常驻输入框）：随时可发；choice 节点时视为自定义回答 */
  submitText: (text: string) => {
    const s = get();
    const t = text.trim();
    if (!t || !s.started || s.finished) return;
    pushLog(set, get, { speakerId: 'player', name: speakerName('player'), text: t, isPlayer: true });
    if (s.choiceNode) {
      set({ choiceNode: null });
      revealNext(set, get);
      return;
    }
    // 打断当前条：完成打字 → 入 log → 顺延
    const cur = s.typing;
    if (cur && cur.done && s.current) {
      pushLog(set, get, {
        speakerId: s.current.speakerId,
        name: speakerName(s.current.speakerId),
        text: cur.full,
        isPlayer: s.current.speakerId === 'player',
      });
    }
    set({ typing: null });
    revealNext(set, get);
  },

  /** 打字机逐字推进（由页面定时器调用） */
  tick: (n: number) => {
    const s = get();
    const t = s.typing;
    if (!s.started || s.finished || !t || t.done) return;
    const chars = Math.min(t.full.length, t.chars + n);
    const done = chars >= t.full.length;
    set({ typing: { ...t, chars, done } });
    // P-0815-E：玩家自己的消息播完即自动推进（不在自己对话框里设「点击继续」等待按钮）——
    // 发消息后等 AI 回复到达，回复直接自动显示；AI 消息保留 Gal 式点击推进。
    if (done) maybeAutoAdvancePlayerMessage(set, get);
  },

  setMode: (m: GalMode) => set({ mode: m }),

  // ── P-0810-06：真实对局直播动作 ────────────────────────────────

  enterLiveMode: (sessionId, opts) => {
    logSeq = 0;
    set({
      liveMode: true,
      liveSessionId: sessionId,
      liveStatus: 'connecting',
      liveEventCount: 0,
      liveGameType: 'unknown',
      livePhase: '',
      liveScriptTitle: '',
      liveGeneralMode: '',
      liveQueue: [],
      liveStreams: {},
      livePlayerName: opts?.playerName || '',
      livePlayerKey: opts?.playerKey || '',
      liveSending: false,
      liveSendError: '',
      liveLastSent: 0,
      liveSuggestions: [],
      liveUnknownRoles: [],
      liveSayOverride: undefined,
      livePlaybackArmed: false,
      started: true,
      finished: false,
      index: 0,
      current: null,
      typing: null,
      log: [],
      choiceNode: null,
      activeSpeakerId: null,
      speakers: GAL_SPEAKERS,
    });
  },

  exitLiveMode: () => {
    set({
      liveMode: false,
      liveSessionId: '',
      liveStatus: 'idle',
      liveEventCount: 0,
      liveGameType: 'unknown',
      livePhase: '',
      liveScriptTitle: '',
      liveGeneralMode: '',
      liveQueue: [],
      liveStreams: {},
      liveSending: false,
      liveSendError: '',
      liveLastSent: 0,
      liveSuggestions: [],
      liveUnknownRoles: [],
      liveSayOverride: undefined,
      livePlaybackArmed: false,
      liveGoals: null,
      started: false,
      finished: false,
      current: null,
      typing: null,
      log: [],
      choiceNode: null,
      activeSpeakerId: null,
      speakers: GAL_SPEAKERS,
    });
  },

  setLiveStatus: (st) => set({ liveStatus: st }),

  setLiveGameType: (t, phase, title) =>
    set(s => ({
      liveGameType: t,
      // 阶段名统一大写（剧本杀=大写枚举；狼人杀=小写 day_discuss/day_vote…）——
      // 发言路由与展示共用同一规范化值
      livePhase: phase !== undefined ? String(phase).toUpperCase() : s.livePhase,
      liveScriptTitle: title !== undefined ? title : s.liveScriptTitle,
    })),

  setLiveGeneralMode: (mode) => set({ liveGeneralMode: mode }),

  bumpLiveEvent: () => set(s => ({ liveEventCount: s.liveEventCount + 1 })),

  applySseEvent: (eventType, data) => {
    const s = get();
    if (!s.liveMode) return;
    // P-0811-G(B-2)：SSE 会话过滤——事件载荷携带 session_id 且与当前会话不符时丢弃
    // （agent_output/agent_token/round_complete/user_input 经后端带 session_id 广播，
    //   多会话并存时防止他局事件串入本会话呈现；无 session_id 的全局事件（announcement 等）不受影响）
    const evtSid = data && typeof data === 'object' ? (data as any).session_id : undefined;
    if (evtSid && s.liveSessionId && evtSid !== s.liveSessionId) return;
    switch (eventType) {
      case 'agent_output': {
        // 剧本杀/狼人杀局不经一般对话管线（无 agent_output），过滤防跨局串扰
        if (s.liveGameType === 'script' || s.liveGameType === 'werewolf') return;
        const agent = data?.agent_name;
        const content = data?.content;
        if (!agent || !content) return;
        // P-0817-N：主控/旁白/系统消息 → 旁白样式（无 TTS 播放按钮），不当作角色发言
        if (isNarratorAgent(agent)) {
          s.liveEnqueue({ kind: 'system', speakerId: 'system', name: `?? ${agent}`, text: content });
          break;
        }
        s.liveEnsureSpeaker(agent);
        s.liveCompleteAgent(agent, content);
        break;
      }
      case 'agent_token': {
        if (s.liveGameType === 'script' || s.liveGameType === 'werewolf') return;
        const agent = data?.agent_name;
        const delta = data?.delta;
        if (!agent || !delta) return;
        s.liveEnsureSpeaker(agent);
        s.liveToken(agent, delta);
        break;
      }
      case 'werewolf_speech': {
        const sp = data?.speaker;
        const msg = data?.message;
        if (!sp || !msg) return;
        if (s.liveGameType === 'unknown') s.setLiveGameType('werewolf');
        s.liveEnsureSpeaker(sp);
        s.liveEnqueue({ kind: 'agent', speakerId: sp, name: sp, text: msg });
        break;
      }
      case 'werewolf_phase': {
        if (data?.session_id && data.session_id !== s.liveSessionId) break;
        if (s.liveGameType === 'unknown') s.setLiveGameType('werewolf');
        s.setLiveGameType(s.liveGameType, data?.phase || s.livePhase);
        break;
      }
      case 'announcement': {
        const text = data?.text;
        if (!text) return;
        const speaker = data?.speaker || '系统';
        const level = data?.level || 'EVENT';
        s.liveEnqueue({
          kind: 'system',
          speakerId: 'system',
          name: `📢 ${speaker}`,
          text,
          level,
        });
        break;
      }
      case 'arbiter_integrate': {
        // P-0815-E：主控整合旁白（director 导演模式叙事旁白 / 狼人杀·剧本杀 GM 推进旁白）。
        // 载荷 {round, narration}（无 session_id，全局事件）；narration 非空才入队。
        // 一般模式（free/protagonist/multi_track）后端 P-0811-G 已不入史不推送，此处仅消费导演模式等叙事旁白；
        // 复用 announcement 同款 system 旁白样式（GalDialogBox kind=system/narrator 渲染，GalHistoryDrawer 归『旁白』）。
        // P-0818-D：后端实证 arbiter_integrate 仅 RouterService（一般模式）推送，剧本杀/狼人杀不推——
        // script/werewolf 模式收到该事件只能是并行一般会话串扰（无 session_id 无法按会话过滤），直接跳过。
        if (s.liveGameType === 'script' || s.liveGameType === 'werewolf') break;
        const text = data?.narration;
        if (!text) return;
        s.liveEnqueue({
          kind: 'system',
          speakerId: 'system',
          name: '📢 主控',
          text,
        });
        break;
      }
      case 'user_input': {
        // P-0818-D：剧本杀/狼人杀对局不走一般对话管线，不广播 user_input——
        // script/werewolf 模式收到（尤其无 session_id 的旧路径广播）即跨会话噪音，跳过。
        if (s.liveGameType === 'script' || s.liveGameType === 'werewolf') break;
        const content = data?.content;
        if (!content) return;
        if (isRecentPlayerEcho(get(), content)) return;
        const character = data?.character || '玩家';
        s.liveEnqueue({ kind: 'player', speakerId: 'player', name: character, text: content });
        break;
      }
      case 'script_phase': {
        if (data?.session_id && data.session_id !== s.liveSessionId) break;
        s.setLiveGameType('script', data?.phase || '', s.liveScriptTitle);
        // P-0818-D：不再自动入队「阶段切换：X」system 行——阶段信息已由
        // ScriptGameInfoBar / 阶段横幅 / announcement（GM 公告）三处覆盖，
        // 该行会被用户视为「莫名其妙的对局聊天消息」；阶段旁白改由
        // ScriptGalChatPanel 按阶段入队一次（见其 narration effect）。
        break;
      }
      case 'script_status': {
        if (data?.session_id && data.session_id !== s.liveSessionId) break;
        s.setLiveGameType('script', data?.phase || '', data?.name || data?.theme || s.liveScriptTitle);
        break;
      }
      case 'script_speech': {
        // P-0815-B：剧本杀讨论实时发言（会话定向 SSE，SSEController.broadcastScriptSpeech）。
        // 字段 {session_id, speaker, message, round, human?} —— human=true 为玩家发言（按玩家样式入队），
        // 其余为 AI 讨论发言（kind='agent'）。与 startLiveSync 的 3s 轮询转录增量构成双通道，
        // 入队前按 (speakerId,text) 对 liveQueue+log 去重（先到的通道入队，后到者跳过，防双播）。
        const sp = data?.speaker;
        const msg = data?.message;
        if (!sp || !msg) return;
        if (s.liveGameType === 'unknown') s.setLiveGameType('script', data?.round ? String(data.round) : s.livePhase);
        const human = data?.human === true;
        // 去重键与入队后的 speakerId 对齐（玩家→'player'；AI→角色名）——与轮询转录同源同键
        // P-0815-B 审核修复（未衡）：玩家消息入队固定 speakerId='player'，去重键必须同为 'player'，
        // 否则 SSE 通道与轮询转录通道对玩家发言去重失效（断线重连/延迟时玩家气泡双播）。
        const sidKey = human ? 'player' : sp;
        const dup = [...s.liveQueue, ...s.log].some(m =>
          (m as any).speakerId === sidKey && m.text === msg);
        if (dup) break;
        // P-0815-B 审核修复（未衡）：liveEnsureSpeaker 仅对 AI 发言调用；human 分支的 sp 若等于玩家名，
        // 会把玩家注册成非玩家 NPC 立绘（npcs 过滤 isPlayer 会误上舞台），与轮询通道行为对齐。
        if (!human) s.liveEnsureSpeaker(sp);
        s.liveEnqueue(human
          ? { kind: 'player', speakerId: 'player', name: s.livePlayerName || sp, text: msg }
          : { kind: 'agent', speakerId: sp, name: sp, text: msg });
        break;
      }
      case 'script_ready': {
        // P-0815-B：剧本杀完整剧本/地图生成完成事件——GalStore 无独立展示，仅确保类型/标题刷新
        if (data?.session_id && data.session_id !== s.liveSessionId) break;
        if (s.liveGameType === 'unknown') s.setLiveGameType('script');
        s.setLiveGameType('script', data?.phase || s.livePhase, data?.name || data?.theme || s.liveScriptTitle);
        // P-0815-F（方向2，根因 B）：完整剧本就绪入队 system 消息（gal 聊天区可见）
        s.liveEnqueue({ kind: 'system', speakerId: 'system', name: '📢 剧本杀', text: '完整剧本已就绪，对局开始' });
        break;
      }
      case 'ai_image_ready':
      case 'ai_image_error':
        s.applyImageEvent(eventType === 'ai_image_ready' ? 'ready' : 'error', data);
        break;
      case 'scene_target_update': {
        // P-0810-16：场景目标进展（定向广播；session_id 不符/为空不消费，防多局串扰）
        if (data?.session_id && data.session_id !== s.liveSessionId) break;
        s.applySceneTargetUpdate(data);
        break;
      }
      default:
        break;
    }
  },

  /** P-0810-16：写入场景卡目标（起局响应 goals / /api/state scene_goals）；未启用/空 → null（场景卡优雅降级） */
  setLiveGoals: (goals) => {
    const g = goals && typeof goals === 'object' ? goals : null;
    const enabled = !!(g && g.enabled);
    const hasContent = !!(g && (g.player_goal || g.global_goal || g.role_goals || g.ai_goal_count));
    set({ liveGoals: enabled && hasContent ? {
      enabled: true,
      player_goal: g.player_goal ? { desc: String(g.player_goal.desc ?? ''), status: g.player_goal.status } : undefined,
      global_goal: g.global_goal ? { desc: String(g.global_goal.desc ?? GOAL_MASK), status: g.global_goal.status } : undefined,
      role_goals: g.role_goals ? Object.fromEntries(Object.entries(g.role_goals).map(([k, v]: any) => [k, { desc: String(v?.desc ?? GOAL_MASK), status: v?.status }])) : undefined,
      ai_goal_count: typeof g.ai_goal_count === 'number' ? g.ai_goal_count : undefined,
      revealed: Array.isArray(g.revealed) ? g.revealed.map(String) : [],
    } : null });
  },

  /** P-0810-16：scene_target_update 增量合并（状态更新 + revealed 揭示全文去重累积） */
  applySceneTargetUpdate: (data) => {
    const s = get();
    if (!s.liveMode || !data || typeof data !== 'object') return;
    set(st => {
      const prev = st.liveGoals;
      if (!prev) return {}; // 无目标集（旧会话/未起局）→ 不凭空创建
      const roleGoals: Record<string, { desc: string; status?: string }> = { ...(prev.role_goals || {}) };
      if (data.role_goal_status && typeof data.role_goal_status === 'object') {
        for (const [name, status] of Object.entries(data.role_goal_status)) {
          roleGoals[name] = { ...(roleGoals[name] || { desc: GOAL_MASK }), status: String(status) };
        }
      }
      const revealed = [...(prev.revealed || [])];
      if (Array.isArray(data.revealed)) {
        for (const r of data.revealed) {
          const t = String(r);
          if (t && !revealed.includes(t)) revealed.push(t);
        }
      }
      const merged: GalSceneGoals = {
        ...prev,
        role_goals: roleGoals,
        global_goal: data.global_goal_status && prev.global_goal
          ? { ...prev.global_goal, status: String(data.global_goal_status) }
          : prev.global_goal,
        player_goal: data.player_goal_status && prev.player_goal
          ? { ...prev.player_goal, status: String(data.player_goal_status) }
          : prev.player_goal,
        revealed,
      };
      return { liveGoals: merged };
    });
  },

  liveEnsureSpeaker: (name) => {
    const s = get();
    if (!name || !s.liveMode) return;
    if (name === 'narrator' || name === 'system' || name === 'player') return;
    if (s.speakers.some(x => x.id === name)) return;
    const backendId = backendIdForName(name);
    const sp = buildPlaceholderSpeaker(name, backendId);
    set(st => ({
      speakers: [...st.speakers, sp],
      liveUnknownRoles: backendId
        ? st.liveUnknownRoles
        : st.liveUnknownRoles.includes(name) ? st.liveUnknownRoles : [...st.liveUnknownRoles, name],
    }));
  },

  liveToken: (agent, delta) => {
    const s = get();
    if (!s.liveMode || !agent || !delta) return;
    const prev = s.liveStreams[agent] || '';
    const text = prev + delta;
    // 队列中若无该 agent 的流式消息 → 建一条（打字机实时渲染的载体，agent_output 结算替换文本）
    let msgId: string | null = null;
    for (let i = s.liveQueue.length - 1; i >= 0; i--) {
      if (s.liveQueue[i].speakerId === agent && s.liveQueue[i].streamed) {
        msgId = s.liveQueue[i].id;
        break;
      }
    }
    const newMsg: GalLiveMessage = {
      id: `live-${++logSeq}-${Date.now()}`,
      kind: 'agent',
      speakerId: agent,
      name: agent,
      text: '',
      streamed: true,
      ts: Date.now(),
    };
    set(st => ({
      liveStreams: { ...st.liveStreams, [agent]: text },
      liveQueue: msgId ? st.liveQueue : [...st.liveQueue, newMsg],
      // 正在播放该 agent 的流式句 → 打字机文本实时增长（逐字重放）
      // P-0810-08 fix：即使已 done（缓冲文本曾很短先播完）也要继续更新 full ——
      // 流式 token 仍在增长时，tick 会随 full 变长自动重新打开打字机（done 在 tick 内重算）
      typing: st.typing && st.typing.speakerId === agent
        ? { ...st.typing, full: text }
        : st.typing,
    }));
    ensureLivePlay(get(), set);
  },

  liveCompleteAgent: (agent, content) => {
    const s = get();
    if (!s.liveMode || !agent) return;
    // 找队列中该 agent 最近一条流式消息
    let foundId: string | null = null;
    for (let i = s.liveQueue.length - 1; i >= 0; i--) {
      if (s.liveQueue[i].speakerId === agent && s.liveQueue[i].streamed) {
        foundId = s.liveQueue[i].id;
        break;
      }
    }
    const isCurrent = s.current && (s.current as unknown as GalLiveMessage).id === foundId;
    set(st => ({
      liveStreams: Object.fromEntries(Object.entries(st.liveStreams).filter(([k]) => k !== agent)),
      liveQueue: st.liveQueue.map(m =>
        m.id === foundId ? { ...m, text: content, streamed: false } : m),
      // 结算后：正在播放 → 立即完成当前句（P-0810-08 fix：即使已 done 也替换为完整内容，
      // 防流式先播完的短缓冲把完整文本冻结）；未播放 → 文本已替换，播放时逐字重放
      typing: isCurrent && st.typing
        ? { speakerId: agent, full: content, chars: content.length, done: true }
        : st.typing,
    }));
    if (!foundId && s.current && (s.current as unknown as GalLiveMessage).speakerId === agent
        && (s.current as unknown as GalLiveMessage).streamed === true
        && s.typing && !s.typing.done) {
      // 流式消息已被消费（点击跳过）但 agent_output 才到 → 补完整文本入队。
      // P-0814-G：限定 streamed 消息 —— 同步返回已先入队的完整文本（非流式）正在播放时，
      // agent_output 迟到结算不能走补全分支（否则重复入队成两条）。
      // P-0817-N：主控/旁白类兜底归旁白（正常情况下已在 agent_output 分流，此处双保险）
      get().liveEnqueue({
        kind: isNarratorAgent(agent) ? 'system' : 'agent',
        speakerId: isNarratorAgent(agent) ? 'system' : agent,
        name: isNarratorAgent(agent) ? `?? ${agent}` : agent,
        text: content,
      });
      return;
    }
    if (!foundId) {
      // P-0814-G：双路径去重 —— /api/send 同步返回的 agent_outputs 已先入队（非流式）时，
      // SSE agent_output 结算找不到流式消息，不能再次入队（否则 AI 消息重复/批量出现）。
      // 与 liveSay 同步入队前的去重同源（同 speaker+text 已在队/log 则跳过）。
      const st = get();
      const dup = [...st.liveQueue, ...st.log].some(m =>
        (m as GalLiveMessage).speakerId === agent && m.text === content);
      if (!dup) {
        get().liveEnqueue({
          kind: isNarratorAgent(agent) ? 'system' : 'agent',
          speakerId: isNarratorAgent(agent) ? 'system' : agent,
          name: isNarratorAgent(agent) ? `?? ${agent}` : agent,
          text: content,
        });
      }
    }
  },

  liveEnqueue: (msg) => {
    if (!get().liveMode) return;
    // P-0810-08：呈现接管视图隐藏玩家气泡 → 玩家消息不入队（不渲染；后端历史仍可查）
    if (get().hidePlayerBubbles && msg.kind === 'player') return;
    const m: GalLiveMessage = { id: `live-${++logSeq}-${Date.now()}`, ts: Date.now(), ...msg };
    set(st => ({ liveQueue: [...st.liveQueue, m] }));
    ensureLivePlay(get(), set);
  },

  enqueuePlayerEcho: (text) => {
    if (!get().liveMode || !text.trim()) return;
    // P-0810-21：成功发送 → 记录时间戳（输入区「已发送」反馈 3s 闪烁，独立于气泡回显）
    set({ liveLastSent: Date.now() });
    get().liveEnqueue({
      kind: 'player',
      speakerId: 'player',
      name: get().livePlayerName || '你',
      text: text.trim(),
    });
  },

  setLiveIdentity: (playerName, playerKey) =>
    set(s => ({
      livePlayerName: playerName !== undefined ? playerName : s.livePlayerName,
      livePlayerKey: playerKey !== undefined ? playerKey : s.livePlayerKey,
    })),

  setSending: (v) => set({ liveSending: v }),

  setHidePlayerBubbles: (v) => set({ hidePlayerBubbles: v }),

  /** P-0810-21-D：写入玩家发言候选（≤4 条；非法入参清空=隐藏选项条） */
  setLiveSuggestions: (s) => set({ liveSuggestions: Array.isArray(s) ? s.slice(0, 4) : [] }),

  /** P-0813-D：2D 模拟视图注入发言发送器（卸载置 undefined 恢复默认 liveSay） */
  setLiveSayOverride: (fn) => set({ liveSayOverride: fn }),
  /** P-0814-A：置位/清除「播出完毕待推进」标志（round_complete 置位；推进按钮点击/新会话清除）。 */
  setLivePlaybackArmed: (v) => set({ livePlaybackArmed: v }),

  setSpeakers: (speakers) => set({ speakers }),

  // ── P-0810-03：立绘动作 ────────────────────────────────────────

  refreshImageStatus: async () => {
    try {
      const res = await listStatus();
      // P-0818-F：后端角色名 → ID 动态注册（用户在角色卡点「生成形象」后，局内自动有立绘）
      for (const c of res?.characters ?? []) {
        if (c.name && c.id) {
          registerBackendMapping(c.name, c.id);
          // 同时注册 id → id 映射（后端 name 可能等于 id，如 recoverOrphanCharacters 产物）
          if (c.name !== c.id) registerBackendMapping(c.id, c.id);
        }
      }
      const chars: GalBackendCharacter[] = (res?.characters ?? []).map(c => ({
        id: c.id,
        name: c.name,
        appearance: c.appearance || '',
        images: c.images || {},
        task: c.task,
      }));
      set(s => {
        const portraits: Record<string, GalPortraitState> = { ...s.portraits };
        for (const c of res?.characters ?? []) {
          const prev = portraits[c.id];
          const images = c.images || {};
          const task = c.task;
          const generating = !!task && task.status === 'running';
          portraits[c.id] = {
            backendId: c.id,
            name: c.name,
            registered: true,
            // 合并既有 + 新帧（生成期间磁盘扫描逐帧增长）
            frames: { ...(prev?.frames || {}), ...images },
            selectedFrame: prev?.selectedFrame || 'avatar',
            generating,
            progress: generating
              ? (task?.progress || 'generating')
              : (prev?.generating ? 'done' : (prev?.progress || '')),
            error: task && task.status === 'failed' ? (task.error || '生成失败') : '',
          };
        }
        return { backendCharacters: chars, portraits };
      });
    } catch {
      // 后端不可达：保留既有状态（页面仍可展示占位立绘）
    }
  },

  generatePortrait: async (backendId: string) => {
    const existing = get().portraits[backendId];
    set(s => ({
      portraits: {
        ...s.portraits,
        [backendId]: {
          ...(existing || { backendId, name: backendId, registered: false, frames: {}, selectedFrame: 'avatar', generating: false, progress: '', error: '' }),
          generating: true,
          progress: 'submitting',
          error: '',
        },
      },
    }));
    try {
      // 未注册角色：先按档案注册（露娜/小铃/凯尔）再触发；
      // P-0810-06：无档案的未知对局角色（liveUnknownRoles）用名字派生通用档案（「可注册生成」）
      if (!existing?.registered) {
        const profile = SPEAKER_BACKEND_PROFILES[backendId];
        await registerCharacter(profile
          ? { id: backendId, name: profile.name, appearance: profile.appearance, style: profile.style }
          : {
              id: backendId,
              name: backendId,
              appearance: `${backendId}，动漫风格角色，全身立绘，站姿，精致服装细节`,
              style: 'anime style, cel shading, vibrant colors, detailed eyes',
            });
      }
      const res = await triggerGenerate(backendId);
      set(s => ({
        portraits: {
          ...s.portraits,
          [backendId]: {
            ...(s.portraits[backendId] || { backendId, name: backendId, registered: true, frames: {}, selectedFrame: 'avatar', generating: false, progress: '', error: '' }),
            registered: true,
            generating: true,
            progress: res?.progress || 'queued',
            error: '',
          },
        },
      }));
      // 立即拉一次状态（同步任务快照，缩短首帧等待）
      void get().refreshImageStatus();
    } catch (e: any) {
      set(s => ({
        portraits: {
          ...s.portraits,
          [backendId]: {
            ...(s.portraits[backendId] || { backendId, name: backendId, registered: false, frames: {}, selectedFrame: 'avatar', generating: false, progress: '', error: '' }),
            generating: false,
            error: e?.message || '触发生成失败',
          },
        },
      }));
    }
  },

  selectFrame: (backendId: string, frame: string) =>
    set(s => {
      const p = s.portraits[backendId];
      if (!p) return {};
      return { portraits: { ...s.portraits, [backendId]: { ...p, selectedFrame: frame } } };
    }),

  applyImageEvent: (kind, payload) => {
    const cid = payload?.characterId;
    if (!cid) return;
    if (kind === 'ready') {
      // 刷新全量状态（生成态由任务状态重新推导；不在这里强制停 generating）
      void get().refreshImageStatus();
    } else {
      set(s => {
        const p = s.portraits[cid];
        if (!p) return {};
        return {
          portraits: {
            ...s.portraits,
            [cid]: {
              ...p,
              generating: false,
              error: (payload as AiImageErrorPayload)?.error || '生成失败',
            },
          },
        };
      });
    }
  },

  initImageEvents: () => {
    if (get()._imageSub) return get()._imageSub!;
    const unsub = subscribeAiImageEvents({
      onReady: (p) => get().applyImageEvent('ready', p),
      onError: (p) => get().applyImageEvent('error', p),
    });
    set({ _imageSub: unsub });
    return unsub;
  },

  disposeImageEvents: () => {
    get()._imageSub?.();
    set({ _imageSub: undefined });
  },
}));
}

export type GalStoreApi = ReturnType<typeof createGalStore>;

/** 默认单例（无 GalStoreProvider 的组件/模块函数兜底；行为与旧模块级单例一致） */
export const defaultGalStore = createGalStore();

const GalStoreContext = createContext<GalStoreApi>(defaultGalStore);

/** 提供 gal store 实例（宿主面板挂载时注入；子树组件经 useGalStore 绑定该实例） */
export function GalStoreProvider({ store, children }: { store: GalStoreApi; children?: ReactNode }) {
  return createElement(GalStoreContext.Provider, { value: store }, children);
}

/** 组件内取当前 gal store 实例（Provider 内=实例；外=默认单例）。命令式 getState 用。 */
export function useGalStoreApi(): GalStoreApi {
  return useContext(GalStoreContext);
}

type GalStoreSelector = <T>(selector: (s: GalState) => T) => T;

/**
 * gal store 选择器 hook（上下文感知）：
 *  - 宿主面板各建实例经 GalStoreProvider 注入 → 子树组件绑定各自实例（互踩隔离）；
 *  - 无 Provider（调试）→ 绑定默认单例（向后兼容）。
 */
export const useGalStore = Object.assign(
  (<T>(selector: (s: GalState) => T): T => useStore(useGalStoreApi(), selector)) as GalStoreSelector,
  {
    /** 兼容旧静态调用：指向默认单例（宿主内组件请用 useGalStoreApi()） */
    getState: (): GalState => defaultGalStore.getState(),
    setState: (partial: GalState | Partial<GalState> | ((state: GalState) => GalState | Partial<GalState>)) =>
      defaultGalStore.setState(partial as any),
  },
);
