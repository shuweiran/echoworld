/**
 * store.ts — 全新 demo 全局状态（P2-0805，定案架构）
 *
 * 6 新页面：模式选择 / 剧本选择(A) / 角色选择(B：剧本杀·一般·狼人杀三变体) / 剧本生成 / 设置 / 自由角色管理。
 * 对局沿用整机版前端（接后端）。
 */
import { create } from 'zustand';
import type {
  GeneralScript, MurderScript, RoleCard, Settings,
} from './types';
import type { ScriptMap } from '../phaser/mapData';
import { FREE_ROLE_SEEDS } from './mockData';
import { hashToView, viewToHash } from './navHistory';

export type View =
  | 'home'          // 模式选择
  | 'scripts'       // 剧本选择（A）
  | 'roles'         // 角色选择（B，按 selectCtx.kind 区分 剧本杀/一般/狼人杀 变体）
  | 'gen'           // 剧本生成
  | 'settings'      // 设置
  | 'roles-lib'     // 角色库（主页面：角色卡管理，剧本杀/一般互通）
  // 子视图
  | 'free-chars'    // 自由角色管理
  | 'role-detail'   // 角色卡详情
  | 'game'          // 对局（桥接整机版 ChatPage）

export type GameMode = 'murder' | 'general';

/** 角色选择页上下文（进入角色选择页所需） */
export interface SelectCtx {
  kind: 'murder' | 'general' | 'werewolf';
  /** 当前剧本 id（murder/general 有值；werewolf 为空） */
  scriptId: string | null;
}

export type RunMode = 'chat' | 'explore';

/** 场景历史记录（角色选择页展示） */
export interface SceneHistory {
  id: string;
  title: string;
  kind: 'murder' | 'general' | 'werewolf';
  /** P-0811-A：所属剧本 id（murder/general 有值；werewolf 为 null；旧数据缺失=无主记录仅狼人杀页可见） */
  scriptId: string | null;
  roleName: string;
  time: string;
  result: string;
}

const SETTINGS_KEY = 'roleplay_demo2_settings_v1';
const FREE_KEY = 'roleplay_demo2_free_roles_v1';
const HIST_KEY = 'roleplay_demo2_history_v1';
// P-0811-G：一般模式 LLM 生成地图持久化（scriptId → 契约 v1 地图；刷新/重进不丢，每次进入复用同一张）
const GEN_MAPS_KEY = 'roleplay_demo2_general_maps_v1';
// P-0811-E：生成的剧本/场景/角色持久化（刷新不丢）——generatedMurder/generatedGeneral 此前纯内存，
// 刷新即失（对比 freeRoles/historyList/settings 均有 localStorage）；本批统一补持久化。
const GEN_KEY = 'roleplay_demo2_generated_v1';
const GEN_ROLES_KEY = 'roleplay_demo2_gen_roles_v1';
const EXTRA_KEY = 'roleplay_demo2_extra_roles_v1';
const LIT_KEY = 'roleplay_demo2_lit_roles_v1';
const REMOVED_KEY = 'roleplay_demo2_removed_roles_v1';
// P-0819-P：刷新/断线恢复时保留进入对局所需的前端上下文；真正状态仍以后端 /resume 为准。
const ACTIVE_GAME_KEY = 'roleplay_demo2_active_game_v1';

type ActiveGameSnapshot = {
  gameMode: 'murder' | 'general' | 'werewolf';
  gamePlayers: string[];
  selectCtx: SelectCtx;
  runMode: RunMode;
  withPlayer: boolean;
  playerRole: RoleCard | null;
};

function loadActiveGame(): Partial<ActiveGameSnapshot> {
  try {
    const raw = localStorage.getItem(ACTIVE_GAME_KEY);
    if (!raw) return {};
    const value = JSON.parse(raw) as Partial<ActiveGameSnapshot>;
    return value && typeof value === 'object' ? value : {};
  } catch { return {}; }
}

const restoredGame = loadActiveGame();

function defaultSettings(): Settings {
  return {
    llm: {
      apiBase: 'https://api.deepseek.com',
      model: 'deepseek-chat',
      apiKey: '',
      temperature: 0.7,
      maxTokens: 4000,
      contextLength: 8192,
      mapModel: '',
      mapApiBase: '',
      mapApiKey: '',
      multimodal: false,
    },
    tts: { engine: '浏览器内置', model: 'edge-tts', apiBase: 'https://tts.example.com/v1', apiKey: '', voice: '默认女声', speed: 1, pitch: 1, emotion: 0.5 },
    image: { provider: 'comfyui', baseUrl: 'http://127.0.0.1:8188', loraName: '', rmbgEnabled: true, img2imgDenoise: 0.5 },
    // P-0817-D：默认 32×20（与既有硬编码生成尺寸一致，接线设置后行为零变化；可调 10-256）
    mapGen: {
      model: 'structure', style: '随剧本风格', width: 64, height: 48, tileSize: 32,
      rule: '结构模板优先，房间与走廊由程序化布局生成', kind: 'city_block',
      mapMode: 'single', seed: '', audit: false,
    },
    assets: [
      { name: 'demo_player', type: '角色动画', path: 'assets/anim/demo_player', time: '2026-08-05 10:00' },
    ],
    other: { dataPath: './data/', autoBackup: true, logLevel: 'info', uiTheme: '深色', shortcut: 'Ctrl+K', experiment: false },
  };
}

function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (!raw) return defaultSettings();
    const p = JSON.parse(raw) as Partial<Settings>;
    const b = defaultSettings();
    return {
      llm: { ...b.llm, ...(p.llm || {}) },
      tts: { ...b.tts, ...(p.tts || {}) },
      image: { ...b.image, ...(p.image || {}) },
      mapGen: { ...b.mapGen, ...(p.mapGen || {}) },
      assets: p.assets || b.assets,
      other: { ...b.other, ...(p.other || {}) },
    };
  } catch { return defaultSettings(); }
}

function loadFreeRoles(): RoleCard[] {
  try {
    const raw = localStorage.getItem(FREE_KEY);
    if (raw) {
      const arr = JSON.parse(raw) as RoleCard[];
      if (Array.isArray(arr)) return arr;
    }
  } catch { /* ignore */ }
  return [...FREE_ROLE_SEEDS];
}

function loadHistory(): SceneHistory[] {
  try {
    const raw = localStorage.getItem(HIST_KEY);
    if (raw) {
      const arr = JSON.parse(raw) as SceneHistory[];
      if (Array.isArray(arr)) return arr;
    }
  } catch { /* ignore */ }
  return [];
}

/** P-0811-E：生成的剧本/场景（剧本杀 + 一般模式）持久化读写 */
interface GeneratedBundle {
  murder: MurderScript | null;
  general: GeneralScript | null;
}

function loadGenerated(): GeneratedBundle {
  try {
    const raw = localStorage.getItem(GEN_KEY);
    if (raw) {
      const p = JSON.parse(raw) as Partial<GeneratedBundle>;
      return { murder: p.murder ?? null, general: p.general ?? null };
    }
  } catch { /* ignore */ }
  return { murder: null, general: null };
}

function saveGenerated(bundle: GeneratedBundle) {
  try { localStorage.setItem(GEN_KEY, JSON.stringify(bundle)); } catch { /* ignore */ }
}

/** P-0811-E：JSON 数组（genRoles）与 Record（extraRoles/litRoles/removedScriptRoles）持久化读写 */
function loadJsonArr<T>(key: string): T[] {
  try {
    const raw = localStorage.getItem(key);
    if (raw) {
      const arr = JSON.parse(raw) as T[];
      if (Array.isArray(arr)) return arr;
    }
  } catch { /* ignore */ }
  return [];
}

function loadJsonRec<T>(key: string): Record<string, T> {
  try {
    const raw = localStorage.getItem(key);
    if (raw) {
      const obj = JSON.parse(raw) as Record<string, T>;
      if (obj && typeof obj === 'object' && !Array.isArray(obj)) return obj;
    }
  } catch { /* ignore */ }
  return {};
}

function saveJson(key: string, value: unknown) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* ignore */ }
}

/** P-0811-G：一般模式 LLM 生成地图持久化加载（scriptId → ScriptMap；坏数据 → 空）。 */
function loadGeneralMaps(): Record<string, ScriptMap> {
  try {
    const raw = localStorage.getItem(GEN_MAPS_KEY);
    if (raw) {
      const obj = JSON.parse(raw);
      if (obj && typeof obj === 'object' && !Array.isArray(obj)) return obj as Record<string, ScriptMap>;
    }
  } catch { /* ignore */ }
  return {};
}

interface DemoState {
  // 路由
  view: View;
  history: View[];
  go: (v: View) => void;
  back: () => void;
  goHome: () => void;

  // 模式切换（剧本选择A / 剧本生成共用）
  mode: GameMode;
  setMode: (m: GameMode) => void;

  // 角色选择上下文
  selectCtx: SelectCtx;
  enterRoles: (ctx: SelectCtx) => void;

  // 剧本选择（A）
  selectedMurderId: string | null;
  selectedGeneralId: string | null;
  pickScript: (kind: 'murder' | 'general', id: string) => void;

  runMode: RunMode;
  setRunMode: (m: RunMode) => void;
  /** 一般模式：是否带玩家（带=玩家化身进局；不带=纯 AI 观看） */
  withPlayer: boolean;
  setWithPlayer: (v: boolean) => void;

  // 角色选择（B）
  selectedRoleId: string | null;
  playerRole: RoleCard | null;
  selectRole: (id: string | null) => void;
  setPlayerRole: (r: RoleCard | null) => void;
  /** 进入角色选择页时选定的角色进入玩家位 */
  choosePlayerRole: (r: RoleCard) => void;

  // 自由角色库
  freeRoles: RoleCard[];
  addFreeRole: (r: RoleCard) => void;
  updateFreeRole: (r: RoleCard) => void;
  deleteFreeRole: (id: string) => void;
  /** 统一角色库：新增或更新（按 id 去重，持久化到自由角色库） */
  upsertRole: (r: RoleCard) => void;
  /** 统一角色库：删除（从自由角色库或 AI 生成库移除） */
  removeRole: (id: string) => void;
  /** 把自由角色加入当前剧本默认角色（角色来源） */
  importRoleToScript: (roleId: string) => void;
  /** 新增自定义角色并加入指定剧本 */
  addNewRoleToScript: (r: RoleCard, scriptId?: string | null) => void;
  /** 从其他剧本导入角色卡到当前剧本 */
  addExternalRole: (r: RoleCard) => void;
  /** 打开角色卡详情页（角色库右键进入） */
  openRoleDetail: (kind: 'murder' | 'general', scriptId: string | null, roleId: string) => void;
  /** 当前剧本已添加的额外角色（按剧本 id 隔离） */
  extraRoles: Record<string, RoleCard[]>;
  setExtraRoles: (scriptId: string, r: RoleCard[]) => void;
  /** 从指定剧本删除已添加的角色 */
  removeExtraRole: (scriptId: string, roleId: string) => void;
  /** 更新指定剧本中已添加的角色 */
  updateExtraRole: (scriptId: string, role: RoleCard) => void;
  /** 角色选择点亮态：剧本 id → 选中的角色 id 列表（没点亮的不进游戏） */
  litRoles: Record<string, string[]>;
  toggleLitRole: (scriptId: string, roleId: string) => void;
  setLitRoles: (scriptId: string, ids: string[]) => void;
  /** 已从剧本移除的默认角色 id（持久，角色库/角色选择页均不显示） */
  removedScriptRoles: Record<string, string[]>;
  removeScriptRole: (scriptId: string, roleId: string) => void;
  /** 剧本有效默认角色（去掉已移除的） */
  effectiveScriptRoles: (scriptId: string, all: RoleCard[]) => RoleCard[];

  // 剧本生成
  generatedMurder: MurderScript | null;
  generatedGeneral: GeneralScript | null;
  genRoles: RoleCard[];
  setGeneratedMurder: (s: MurderScript | null) => void;
  setGeneratedGeneral: (s: GeneralScript | null) => void;
  addGenRoles: (roles: RoleCard[]) => void;
  /** P-0816-L：后端场景剧本（GET /api/scenes 映射，source='backend'；后端为数据源，不落 localStorage） */
  backendMurder: MurderScript[];
  backendGeneral: GeneralScript[];
  setBackendScripts: (murder: MurderScript[], general: GeneralScript[]) => void;
  /** P-0816-L：删除后端场景后从列表移除（返回是否命中） */
  removeBackendScript: (id: string) => boolean;
  /** P-0811-G：一般模式 LLM 生成地图缓存（scriptId → 契约 v1 地图；进入 explore 复用） */
  generalMaps: Record<string, ScriptMap>;
  /** P-0811-G：写入一般模式 LLM 生成地图（scriptId 维度缓存；空 map 清条目） */
  setGeneralMap: (scriptId: string, map: ScriptMap | null) => void;
  /** P-0811-E：LLM 生成失败回退 mock 时的可见提示（生成后跳转角色选择页仍可见） */
  genNotice: string;
  setGenNotice: (t: string) => void;

  // 对局（桥接整机版）
  gameMode: 'murder' | 'general' | 'werewolf' | null;
  /** 进入对局的选中角色名（点亮选中的） */
  gamePlayers: string[];
  startGame: (kind: 'murder' | 'general' | 'werewolf', players?: string[]) => void;

  // 场景历史
  historyList: SceneHistory[];
  addHistory: (h: Omit<SceneHistory, 'id' | 'time'>) => void;

  // 设置
  settings: Settings;
  updateSettings: (patch: Partial<Settings>) => void;
  resetSettings: () => void;

}

export const useDemoStore = create<DemoState>((set, get) => ({
  // P-0811-E：生成的剧本/场景从 localStorage 恢复（此前纯内存刷新即失）
  generatedMurder: loadGenerated().murder,
  generatedGeneral: loadGenerated().general,
  genRoles: loadJsonArr<RoleCard>(GEN_ROLES_KEY),
  // P-0816-L：后端场景剧本初始为空，由 ScriptSelectPage 挂载时 GET /api/scenes 填充
  backendMurder: [],
  backendGeneral: [],
  extraRoles: loadJsonRec<RoleCard[]>(EXTRA_KEY),
  litRoles: loadJsonRec<string[]>(LIT_KEY),
  removedScriptRoles: loadJsonRec<string[]>(REMOVED_KEY),
  genNotice: '',

  // P-0817-R：初始视图从 URL hash 恢复（深链直达；无 hash → home）
  view: (typeof window !== 'undefined' ? (hashToView(window.location.hash) ?? (restoredGame.gameMode ? 'game' : 'home')) : 'home'),
  history: [],

  mode: 'murder',
  selectCtx: restoredGame.selectCtx ?? { kind: 'murder', scriptId: null },

  selectedMurderId: null,
  selectedGeneralId: null,
  runMode: restoredGame.runMode ?? 'chat',
  withPlayer: restoredGame.withPlayer ?? true,

  selectedRoleId: null,
  playerRole: restoredGame.playerRole ?? null,

  freeRoles: loadFreeRoles(),
  // P-0811-E：以下初值改为从 localStorage 恢复（见上方初始化块）
  gameMode: restoredGame.gameMode ?? null,
  gamePlayers: restoredGame.gamePlayers ?? [],
  generalMaps: loadGeneralMaps(),
  historyList: loadHistory(),
  settings: loadSettings(),
  // P-0817-R：切换页面同步浏览器历史（hash 路由；浏览器后退/前进经 popstate 恢复）
  go: (v) => {
    set(s => ({ view: v, history: [...s.history, s.view].slice(-30) }));
    if (typeof window !== 'undefined') window.history.pushState(null, '', viewToHash(v));
  },
  back: () => {
    const s = get();
    if (s.history.length === 0) {
      set({ view: 'home', history: [] });
      if (typeof window !== 'undefined') window.history.replaceState(null, '', viewToHash('home'));
      return;
    }
    // 优先走浏览器历史（前进/后退一致）；popstate 事件负责更新 store
    if (typeof window !== 'undefined' && window.history.length > 1) {
      window.history.back();
      return;
    }
    const history = [...s.history];
    const prev = history.pop()!;
    set({ view: prev, history });
    if (typeof window !== 'undefined') window.history.replaceState(null, '', viewToHash(prev));
  },
  goHome: () => {
    set({ view: 'home', history: [] });
    if (typeof window !== 'undefined') window.history.replaceState(null, '', viewToHash('home'));
  },

  setMode: (m) => set({ mode: m }),

  enterRoles: (ctx) => {
    set({ selectCtx: ctx, selectedRoleId: null, view: 'roles', history: [...get().history, get().view].slice(-30) });
    if (typeof window !== 'undefined') window.history.pushState(null, '', viewToHash('roles'));
  },

  pickScript: (kind, id) => {
    if (kind === 'murder') set({ selectedMurderId: id });
    else set({ selectedGeneralId: id });
  },

  setRunMode: (m) => set({ runMode: m }),
  setWithPlayer: (v) => set({ withPlayer: v }),

  selectRole: (id) => set({ selectedRoleId: id }),
  setPlayerRole: (r) => set({ playerRole: r }),
  choosePlayerRole: (r) => set({ playerRole: r, selectedRoleId: r.id }),

  addFreeRole: (r) => {
    const next = [...get().freeRoles, r];
    try { localStorage.setItem(FREE_KEY, JSON.stringify(next)); } catch { /* ignore */ }
    set({ freeRoles: next });
  },
  updateFreeRole: (r) => {
    const next = get().freeRoles.map(x => x.id === r.id ? r : x);
    try { localStorage.setItem(FREE_KEY, JSON.stringify(next)); } catch { /* ignore */ }
    set({ freeRoles: next });
  },
  deleteFreeRole: (id) => {
    const next = get().freeRoles.filter(x => x.id !== id);
    try { localStorage.setItem(FREE_KEY, JSON.stringify(next)); } catch { /* ignore */ }
    set({ freeRoles: next });
  },

  upsertRole: (r) => {
    const s = get();
    if (s.freeRoles.some(x => x.id === r.id)) {
      const next = s.freeRoles.map(x => x.id === r.id ? r : x);
      try { localStorage.setItem(FREE_KEY, JSON.stringify(next)); } catch { /* ignore */ }
      set({ freeRoles: next });
    } else if (s.genRoles.some(x => x.id === r.id)) {
      const next = s.genRoles.map(x => x.id === r.id ? r : x);
      saveJson(GEN_ROLES_KEY, next);
      set({ genRoles: next });
    } else {
      const next = [{ ...r, source: 'free' as const }, ...s.freeRoles];
      try { localStorage.setItem(FREE_KEY, JSON.stringify(next)); } catch { /* ignore */ }
      set({ freeRoles: next });
    }
  },

  removeRole: (id) => {
    const s = get();
    if (s.freeRoles.some(x => x.id === id)) {
      const next = s.freeRoles.filter(x => x.id !== id);
      try { localStorage.setItem(FREE_KEY, JSON.stringify(next)); } catch { /* ignore */ }
      set({ freeRoles: next });
    } else {
      const next = s.genRoles.filter(x => x.id !== id);
      saveJson(GEN_ROLES_KEY, next);
      set({ genRoles: next });
    }
  },

  importRoleToScript: (roleId) => {
    const s = get();
    const role = s.freeRoles.find(r => r.id === roleId);
    if (!role) return;
    const scriptId = s.selectCtx.scriptId;
    const copy: RoleCard = { ...role, source: 'free', homeScripts: scriptId ? [...role.homeScripts, scriptId] : role.homeScripts };
    const next = { ...s.extraRoles, [scriptId ?? '']: [...(s.extraRoles[scriptId ?? ''] || []), copy] };
    saveJson(EXTRA_KEY, next);
    set({ extraRoles: next });
  },

  addNewRoleToScript: (r, scriptId) => {
    const s = get();
    const key = scriptId ?? s.selectCtx.scriptId ?? '';
    const next = { ...s.extraRoles, [key]: [...(s.extraRoles[key] || []), r] };
    saveJson(EXTRA_KEY, next);
    set({ extraRoles: next });
  },
  addExternalRole: (r) => {
    const s = get();
    const key = s.selectCtx.scriptId ?? '';
    const next = { ...s.extraRoles, [key]: [...(s.extraRoles[key] || []), r] };
    saveJson(EXTRA_KEY, next);
    set({ extraRoles: next });
  },
  openRoleDetail: (kind, scriptId, roleId) => {
    set({
      selectCtx: { kind, scriptId },
      selectedRoleId: roleId,
      view: 'role-detail',
      history: [...get().history, get().view].slice(-30),
    });
  },
  setExtraRoles: (scriptId, r) => {
    const next = { ...get().extraRoles, [scriptId]: r };
    saveJson(EXTRA_KEY, next);
    set({ extraRoles: next });
  },
  removeExtraRole: (scriptId, roleId) => {
    const next = { ...get().extraRoles, [scriptId]: (get().extraRoles[scriptId] || []).filter(r => r.id !== roleId) };
    saveJson(EXTRA_KEY, next);
    set({ extraRoles: next });
  },
  updateExtraRole: (scriptId, role) => {
    const next = { ...get().extraRoles, [scriptId]: (get().extraRoles[scriptId] || []).map(r => r.id === role.id ? role : r) };
    saveJson(EXTRA_KEY, next);
    set({ extraRoles: next });
  },
  toggleLitRole: (scriptId, roleId) => {
    const cur = get().litRoles[scriptId] || [];
    const next = cur.includes(roleId) ? cur.filter(id => id !== roleId) : [...cur, roleId];
    const rec = { ...get().litRoles, [scriptId]: next };
    saveJson(LIT_KEY, rec);
    set({ litRoles: rec });
  },
  setLitRoles: (scriptId, ids) => {
    const rec = { ...get().litRoles, [scriptId]: ids };
    saveJson(LIT_KEY, rec);
    set({ litRoles: rec });
  },
  removeScriptRole: (scriptId, roleId) => {
    const rec = { ...get().removedScriptRoles, [scriptId]: [...(get().removedScriptRoles[scriptId] || []), roleId] };
    saveJson(REMOVED_KEY, rec);
    set({ removedScriptRoles: rec });
  },
  effectiveScriptRoles: (scriptId, all) => {
    const removed = get().removedScriptRoles[scriptId] || [];
    return all.filter(r => !removed.includes(r.id));
  },

  setGeneratedMurder: (s) => {
    saveGenerated({ murder: s, general: get().generatedGeneral });
    set({ generatedMurder: s });
  },
  setGeneratedGeneral: (s) => {
    saveGenerated({ murder: get().generatedMurder, general: s });
    set({ generatedGeneral: s });
  },
  setBackendScripts: (murder, general) => set({ backendMurder: murder, backendGeneral: general }),
  removeBackendScript: (id) => {
    const s = get();
    const inMurder = s.backendMurder.some(x => x.id === id);
    const inGeneral = s.backendGeneral.some(x => x.id === id);
    if (!inMurder && !inGeneral) return false;
    set({
      backendMurder: inMurder ? s.backendMurder.filter(x => x.id !== id) : s.backendMurder,
      backendGeneral: inGeneral ? s.backendGeneral.filter(x => x.id !== id) : s.backendGeneral,
    });
    return true;
  },
  setGeneralMap: (scriptId, map) => {
    const next = { ...get().generalMaps };
    if (map) next[scriptId] = map;
    else delete next[scriptId];
    saveJson(GEN_MAPS_KEY, next);
    set({ generalMaps: next });
  },
  addGenRoles: (roles) => {
    const next = [...get().genRoles, ...roles];
    saveJson(GEN_ROLES_KEY, next);
    set({ genRoles: next });
  },
  setGenNotice: (t) => set({ genNotice: t }),

  startGame: (kind, players) => {
    const nextPlayers = players ?? [];
    const snapshot: ActiveGameSnapshot = {
      gameMode: kind,
      gamePlayers: nextPlayers,
      selectCtx: get().selectCtx,
      runMode: get().runMode,
      withPlayer: get().withPlayer,
      playerRole: get().playerRole,
    };
    try {
      localStorage.setItem(ACTIVE_GAME_KEY, JSON.stringify(snapshot));
      // 新开局不得误用上一局的令牌；GameBridge 会在 init/resume 成功后重新写入。
      localStorage.removeItem('scriptSessionId');
      localStorage.removeItem('scriptRoleKey');
    } catch { /* ignore */ }
    set({ gameMode: kind, gamePlayers: nextPlayers, view: 'game', history: [...get().history, get().view].slice(-30) });
    if (typeof window !== 'undefined') window.history.pushState(null, '', viewToHash('game'));
  },

  addHistory: (h) => {
    const item: SceneHistory = {
      ...h,
      id: `h_${Date.now()}`,
      time: new Date().toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }),
    };
    const next = [item, ...get().historyList].slice(0, 20);
    try { localStorage.setItem(HIST_KEY, JSON.stringify(next)); } catch { /* ignore */ }
    set({ historyList: next });
  },

  updateSettings: (patch) => {
    const next = { ...get().settings, ...patch };
    try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(next)); } catch { /* ignore */ }
    set({ settings: next });
  },
  resetSettings: () => {
    try { localStorage.removeItem(SETTINGS_KEY); } catch { /* ignore */ }
    set({ settings: defaultSettings() });
  },

}));

// P-0817-R：浏览器前进/后退 → popstate → 恢复视图（含局内页面：structure/game/settings…）
if (typeof window !== 'undefined') {
  window.addEventListener('popstate', () => {
    const v = hashToView(window.location.hash);
    const cur = useDemoStore.getState().view;
    if (v && v !== cur) {
      useDemoStore.setState(s => ({ view: v, history: [...s.history, s.view].slice(-30) }));
    }
  });
}
