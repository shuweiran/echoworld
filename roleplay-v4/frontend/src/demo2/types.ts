/**
 * types.ts — 全新 demo（P2-0805）数据契约
 *
 * 本地 mock 数据层；接真实 API 时仅替换 mockData/mockEngine 产出层，页面消费类型不变。
 * 界面结构：5 主页面 —— 模式选择 / 剧本选择 / 剧本生成 / 狼人杀 / 设置。
 */
import type { ScriptMap } from '../phaser/mapData';

/* ── 角色卡 ───────────────────────────────────────── */

export type RoleSource = 'preset' | 'free' | 'ai' | 'import' | 'backend';

/** 单独 TTS 设置（角色级，覆盖全局 TTS） */
export interface RoleTts {
  engine: string;
  /** 语音生成模型 API */
  model: string;
  apiBase: string;
  apiKey: string;
  voice: string;
  speed: number;
  pitch: number;
  emotion: number;
}

export interface RoleCard {
  id: string;
  name: string;
  /** 头像（demo 用 emoji 占位） */
  avatar: string;
  /** 一句话简介 */
  intro: string;
  /** 人格设定 */
  personality: string;
  /** 说话风格 */
  talkStyle: string;
  /** 背景故事 */
  background?: string;
  motive?: string;
  /** 剧本杀秘密（无则为空） */
  secret?: string;
  hasSecret: boolean;
  /** 单独 TTS 设置（缺省继承全局） */
  tts?: RoleTts;
  /** P-0817-A（MiMo TTS 声线）：basic=内置音色 / clone=参考音频 / design=音色描述 */
  voice_mode?: string;
  /** P-0817-A：voice_data（basic=内置音色名 / clone=参考音频路径或 data URL / design=音色描述） */
  voice_data?: string;
  source: RoleSource;
  /** 归属剧本 id 列表（自由角色为空数组） */
  homeScripts: string[];
}

/* ── 剧本杀剧本 ───────────────────────────────────── */

export interface Clue {
  id: string;
  title: string;
  location: string;
  content: string;
}

export interface MurderScript {
  id: string;
  title: string;
  tags: string[];
  /** 世界背景 */
  background: string;
  playerMin: number;
  playerMax: number;
  /** 剧情流程 */
  plot: string;
  /** 角色关系 */
  relations: string[];
  /** 剧本默认角色（作为角色卡来源） */
  roles: RoleCard[];
  clues: Clue[];
  locations: string[];
  truth: string;
  killerId: string;
  source: RoleSource;
}

/* ── 一般模式剧本（场景） ─────────────────────────── */

export interface GeneralScript {
  id: string;
  title: string;
  /** 场景卡片图标 */
  emoji: string;
  /** 主题：校园/科幻/奇幻/古代 等 */
  theme: string;
  tags: string[];
  /** 场景介绍 */
  desc: string;
  /** 世界背景 */
  background: string;
  /** 角色关系 */
  relations: string[];
  roles: RoleCard[];
  /** 同步生成 2D 地图（契约 v1） */
  map: ScriptMap;
  /** 场景开场白（自由聊天用） */
  opening: string;
  source: RoleSource;
}

/* ── 设置 ─────────────────────────────────────────── */

export interface Settings {
  llm: {
    apiBase: string;
    model: string;
    apiKey: string;
    temperature: number;
    maxTokens: number;
    contextLength: number;
    /** 地图生成 LLM：空=复用主 LLM */
    mapModel: string;
    mapApiBase: string;
    mapApiKey: string;
    multimodal: boolean;
  };
  tts: {
    engine: string;
    /** 语音生成模型 API */
    model: string;
    apiBase: string;
    apiKey: string;
    voice: string;
    speed: number;
    pitch: number;
    emotion: number;
  };
  image: {
    provider: string;
    baseUrl: string;
    loraName: string;
    rmbgEnabled: boolean;
    img2imgDenoise: number;
  };
  mapGen: {
    model: string;
    /** 地图风格：随剧本风格 / 幻想 / 现实 / 科幻 / 古风 */
    style: string;
    width: number;
    height: number;
    tileSize: number;
    rule: string;
    /** 结构模板：城堡 / 庄园 / 城市街区 / 地牢 / 自定义 LLM */
    kind: string;
    /** 地图组织方式：单图 / 多图 / 外部-内部 */
    mapMode: string;
    /** 可复现种子；空字符串表示自动生成 */
    seed: string;
    /** 是否启用生成后的视觉审核 */
    audit: boolean;
  };
  assets: { name: string; type: string; path: string; time: string }[];
  other: {
    dataPath: string;
    autoBackup: boolean;
    logLevel: string;
    uiTheme: string;
    shortcut: string;
    experiment: boolean;
  };
}

/* ── 狼人杀 ───────────────────────────────────────── */

export type WWRole = '狼人' | '预言家' | '女巫' | '猎人' | '平民';

export interface WWPlayer {
  name: string;
  role: WWRole;
  alive: boolean;
  isHuman: boolean;
}

export type WWPhase = 'lobby' | 'roles' | 'night' | 'day' | 'vote' | 'result' | 'ended';

export interface WWRoom {
  code: string;
  host: string;
  players: WWPlayer[];
  round: number;
  phase: WWPhase;
  nightStep: string;
  wolfTarget: string | null;
  seerCheck: { target: string; result: string } | null;
  witchUsedSave: boolean;
  witchUsedPoison: boolean;
  deadTonight: string[];
  savedVictim: string | null;
  dayTalk: { speaker: string; text: string }[];
  votes: { voter: string; target: string }[];
  exiled: string | null;
  winner: string | null;
  log: { round: number; phase: string; text: string }[];
}

/* ── 剧本杀对局（mock 流程） ──────────────────────── */

export type MurderPhase = 'investigation' | 'discussion' | 'vote' | 'reveal' | 'ended';

export interface MurderGameState {
  script: MurderScript;
  myRole: RoleCard;
  /** 我持有的线索 */
  myClues: Clue[];
  searched: string[];
  phase: MurderPhase;
  discussion: { speaker: string; text: string }[];
  voteTally: Record<string, number>;
  mostVoted: string | null;
  correct: boolean | null;
  ap: number;
}

/* ── 自由聊天 ─────────────────────────────────────── */

export interface ChatMsg {
  id: string;
  who: string;
  kind: 'player' | 'bot' | 'system';
  text: string;
  ts: number;
}
