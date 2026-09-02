/** 移动端通过构建时 VITE_API_BASE_URL 指向可访问的 EchoWorld 服务端；桌面/网页保持同源。 */
export const API_ORIGIN = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');

const _controllers = new Map<number, AbortController>();
let _id = 0;

function getAuthHeaders(): Record<string, string> {
  const token = localStorage.getItem('token');
  if (token) {
    return { 'Authorization': `Bearer ${token}` };
  }
  return {};
}

async function request<T>(url: string, options?: RequestInit & { timeout?: number }): Promise<T> {
  const controller = new AbortController();
  const cid = ++_id;
  _controllers.set(cid, controller);

  const timeout = options?.timeout || 60000;
  const timer = setTimeout(() => controller.abort(), timeout);
  // P-0809-B（阶段② API 逻辑链）：每请求生成 X-Request-Id（UUID）——后端 TraceFilter 回显并
  // 以来源=frontend 记录，前端「🧭 逻辑链」抽屉按此 requestId 与后端链路合并；无则后端自动生成
  const requestId = (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function')
    ? crypto.randomUUID()
    : 'rid-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);

  try {
    const res = await fetch(`${API_ORIGIN}${url}`, {
      headers: {
        'Content-Type': 'application/json',
        ...getAuthHeaders(),
        'X-Request-Id': requestId,
      },
      signal: controller.signal,
      ...options,
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ detail: res.statusText }));
      throw new Error(err.error || err.detail || `HTTP ${res.status}`);
    }
    // P1-6：后端 DELETE（/api/scenes/{id}、/api/characters/{name} 等）返回 200 空 body，
    // 原无条件 res.json() 会抛 "Failed to execute 'json' on 'Response': Unexpected end of JSON input"。
    // 改为：空 body → 返回 null（调用方视为成功）；有 body → JSON 解析，非 JSON 也按成功空结果处理。
    const text = await res.text();
    if (!text) return null as unknown as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      // 200 但内容不是合法 JSON（如空字符串/纯文本）→ 不崩，视为成功空结果
      return null as unknown as T;
    }
  } catch (e: any) {
    // P-0803-F：超时 abort 友好化 —— Chromium 对 AbortSignal.abort() 的 fetch 失败抛
    // DOMException "signal is aborted without reason"，直接透传给用户是英文原始错误且误导
    // （后端 LLM 可能仍在生成，对局实际已创建）。转为明确中文提示，避免用户重复点击。
    if (e?.name === 'AbortError' || /aborted without reason/i.test(String(e?.message || ''))) {
      throw new Error(`请求超时：AI 生成耗时较长（剧本+地图两次 LLM 串行），后端可能仍在生成。请勿重复点击，稍后刷新查看对局状态。`);
    }
    throw e;
  } finally {
    clearTimeout(timer);
    _controllers.delete(cid);
  }
}

export function cancelAllRequests() {
  _controllers.forEach(c => c.abort());
  _controllers.clear();
}

/**
 * P-0802-P1-demo：玩家身份模型（改造方案 §3.1）——player_id 客户端生成 + localStorage 持久化。
 * 同一浏览器身份稳定：首次 crypto.randomUUID() 生成，之后复用；与 appStore 同键（'playerId'）读写。
 */
/**
 * P-0804-C：素材文件 URL 解析 —— file_path 相对 static/assets/ 下（如
 * "CHARACTER_ANIMATION/demo_player/player.png"）→ 可直接用于 Phaser 加载的 URL（"/assets/..."）。
 */
export function assetFileUrl(filePath: string): string {
  const p = String(filePath || '').replace(/^\/+/, '');
  return p.startsWith('assets/') ? '/' + p : '/assets/' + p;
}

/**
 * P-0804-C：Aseprite JSON 加载 URL —— meta_json（登记时贴入的 Aseprite JSON 全文）优先，
 * 经 Blob URL 提供（零网络往返、不依赖 JSON 文件落盘）；无 meta_json 时回退“PNG 同名 .json”约定。
 * 返回 { url, isBlob }；调用方负责在卸载时 revokeObjectURL 回收 blob URL。
 */
export function asepriteJsonUrl(metaJson?: string | null, pngPath?: string | null): { url: string; isBlob: boolean } {
  if (metaJson && String(metaJson).trim()) {
    try {
      const blob = new Blob([String(metaJson)], { type: 'application/json' });
      return { url: URL.createObjectURL(blob), isBlob: true };
    } catch { /* fallthrough：Blob 不可用（极老环境）时走文件约定 */ }
  }
  const p = String(pngPath || '');
  return { url: p.replace(/\.(png|PNG)$/, '.json'), isBlob: false };
}

export function getPlayerId(): string {
  let pid = localStorage.getItem('playerId');
  if (!pid) {
    pid = (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function')
      ? crypto.randomUUID()
      : 'pid-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    localStorage.setItem('playerId', pid);
  }
  return pid;
}

/**
 * G2（P-0802-M）：剧本杀对局 session_id 读取 —— appStore.setScriptSessionId 写入时镜像到
 * localStorage（避免 api↔store 循环依赖）；script 四端点（start_discussion/start_voting/
 * resolve/finish）据此携带真实 session_id，修复硬编码 '' 阻断讨论推进的缺陷。
 */
function getScriptSessionId(): string {
  try { return localStorage.getItem('scriptSessionId') || ''; } catch { return ''; }
}

/**
 * P-0802-P4（改造方案 §3.2）：已绑定角色名读取 —— appStore.setBoundCharacterName 写入时镜像到
 * localStorage（对齐 getPlayerId/getScriptSessionId 先例，避免 api↔store 循环依赖）。
 * 用途：① createCharacter 仅当无绑定角色时携带 player_id（第一个角色自动绑定为「玩家本人角色」，
 * 之后创建的不携带 → 不受「一玩家一角色」唯一约束，消除 409 副作用）；
 * ② updateCharacter 仅编辑已绑定角色时携带 player_id（保留绑定）；编辑未绑定角色不携带。
 */
function getBoundCharacterName(): string {
  try { return localStorage.getItem('boundCharacterName') || ''; } catch { return ''; }
}

export const api = {
  cancelAll: cancelAllRequests,
  verifyCode: (code: string) => request<any>('/api/auth/verify', {
    method: 'POST', body: JSON.stringify({ code }),
  }),
  getMe: () => request<any>('/api/auth/me'),
  getState: (sessionId?: string) => request<any>(sessionId ? `/api/state?session_id=${encodeURIComponent(sessionId)}` : '/api/state'),
  init: (data?: any) => request<any>('/api/init', { method: 'POST', body: JSON.stringify(data || {}) }),
  createCharacter: (data: any) => request<any>('/api/characters', {
    method: 'POST',
    // P-0802-P4（改造方案 §6 Phase 4）：仅当当前无绑定角色时携带 player_id —— 第一个创建的角色
    // 自动绑定为「玩家本人角色」；之后创建的新角色不携带 player_id（普通 NPC 角色，不受
    // 「一玩家一角色」唯一约束，消除 Phase 1 遗留的 409 副作用）；data 显式提供时以 data 为准
    body: JSON.stringify({ ...(!getBoundCharacterName() ? { player_id: getPlayerId() } : {}), ...data }),
  }),
  updateCharacter: (oldName: string, data: any) => request<any>(`/api/characters/${encodeURIComponent(oldName)}`, {
    method: 'PUT',
    // P-0802-P4（改造方案 §6 Phase 4）：仅编辑已绑定的本人角色时携带 player_id（保留绑定）；
    // 编辑未绑定角色不携带（普通 NPC 编辑，不触发绑定校验）；data 显式提供时以 data 为准
    body: JSON.stringify({ ...(oldName === getBoundCharacterName() ? { player_id: getPlayerId() } : {}), ...data }),
  }),
  /**
   * P-0802-P4（改造方案 §4.1 新端点）：玩家本人角色（已绑定 player_id）局中改名 ——
   * 后端编排 角色库改名 + Router/2D/狼人杀/剧本杀四处运行态同步 + 撞名校验② + 失败回滚。
   * 仅绑定角色改名调用；非绑定角色改名仍走 updateCharacter（PUT /api/characters/{name}，无局中同步，降级可接受）。
   */
  playerRename: (oldName: string, newName: string) => request<any>('/api/player/rename', {
    method: 'POST',
    body: JSON.stringify({ player_id: getPlayerId(), old_name: oldName, new_name: newName }),
  }),
  deleteCharacter: (name: string) => request<any>(`/api/characters/${encodeURIComponent(name)}`, { method: 'DELETE' }),
  /** P-0816-L：后端场景列表（剧本选择页接入 GET /api/scenes；含 scene_id/name/description/category/default_roles/default_map 等） */
  listScenes: () => request<any[]>('/api/scenes'),
  createScene: (data: any) => request<any>('/api/scenes', { method: 'POST', body: JSON.stringify(data) }),
  updateScene: (id: string, data: any) => request<any>(`/api/scenes/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteScene: (id: string) => request<any>(`/api/scenes/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  /**
   * P-0803-H：剧本默认地图生成（契约 v1）——剧本编辑弹窗「生成默认地图」绑定到剧本卡 default_map。
   * P-0803-O：双模式 —— body 带 theme → LLM 全量生成（失败自动 BSP 兜底）；无 theme → BSP 确定性（零 LLM）。
   */
  sceneMap: (body?: { seed?: number; theme?: string; width?: number; height?: number }) => request<{ map: any; mode?: string; generator?: any; validation?: any; fallback?: string[] }>('/api/scenes/map', {
    method: 'POST',
    body: JSON.stringify(body || {}),
  }),
  // P-0817-L（结构树契约 + 生成 API）：大型结构生成统一入口
  // 响应 {structure, maps{map_id→契约v1}, current_map_id, connections[](exit/warp), generator, fallback[]}
  structureGenerate: (body?: { theme?: string; kind?: string; seed?: number; width?: number; height?: number; map_mode?: string; style?: string; audit?: boolean; locations?: string[]; clue_locations?: string[] }) =>
    request<{
      structure?: any;
      maps?: Record<string, any>;
      current_map_id?: string;
      connections?: Array<{ type: string; map_id?: string; from_map?: string; to_map?: string; exit?: any; warp?: any }>;
      generator?: { l0?: string; kind?: string; seed?: number; map_mode?: string; validation?: { ok?: boolean; errors?: string[]; warnings?: string[] } };
      fallback?: string[];
      audit?: { score?: number; issues?: Array<{ level?: string; what?: string; suggest?: string }>; rounds?: Array<{ round?: number; score?: number; issues?: Array<{ level?: string; what?: string; suggest?: string }>; error?: string }>; tweaks?: Record<string, number>; error?: string };
    }>('/api/structure/generate', {
      method: 'POST',
      body: JSON.stringify(body || {}),
      // 视觉审核最多 180s，审核修正还可能再调用一轮；避免浏览器先于服务端超时。
      timeout: 360000,
    }),
  generateScene: (keywords: string) => request<any>('/api/scenes/generate', { method: 'POST', body: JSON.stringify({ keywords }), timeout: 180000 }),
  /** P-0811-B：生成角色（五层 persona 拟合 + 场景上下文注入）——sceneName/sceneDescription 可选，
   *  缺省不传 body 键零破坏；后端注入「当前场景」要求角色与场景契合。
   *  P-0811-E：超时放宽到 240s（maxTokens 4000 五层卡生成真实耗时可达数十秒）。 */
  generateCharacter: (keywords: string, sceneName?: string, sceneDescription?: string) => {
    const body: Record<string, string> = { keywords };
    if (sceneName) body.scene_name = sceneName;
    if (sceneDescription) body.scene_description = sceneDescription;
    return request<any>('/api/characters/generate', { method: 'POST', body: JSON.stringify(body), timeout: 240000 });
  },
  /** P-0811-E：独立剧本生成（不建局）——POST /api/session/script/generate，输出 Schema v1
   *  （metadata/roles[]/clues[]/killer_id/truth/background/locations）；characters 数量决定角色数。 */
  scriptGen: (theme: string, characters: string[]) =>
    request<any>('/api/session/script/generate', {
      method: 'POST',
      body: JSON.stringify({ theme, characters }),
      timeout: 600000,
    }),
  startScene: (sceneId: string, agents: string[], me?: string, characterDetails?: Array<{ name: string; persona?: string; voice?: string; background?: string }>) => {
    // Keep query params for backward compatibility (backend accepts both query + body)
    const qs = `?agents=${encodeURIComponent(agents.join(','))}${me ? `&me=${encodeURIComponent(me)}` : ''}`;
    return request<any>(`/api/scenes/${encodeURIComponent(sceneId)}/start${qs}`, {
      method: 'POST',
      body: JSON.stringify({ agents, me: me || '', characters: characterDetails || [] }),
    });
  },
  startRound: (turns: number = 1, sessionId?: string) => request<any>('/api/round/start', {
    method: 'POST', body: JSON.stringify({ turns, ...(sessionId ? { session_id: sessionId } : {}) }),
  }),
  rollback: (round: number, sessionId?: string) => request<any>('/api/round/rollback', { method: 'POST', body: JSON.stringify({ round, ...(sessionId ? { session_id: sessionId } : {}) }) }),
  /** P-0810-21-D：玩家发言候选话术（一般模式玩家回合可选项；POST /api/round/suggest，LLM 失败后端兜底通用候选） */
  suggest: (sessionId: string, count?: number) => request<any>('/api/round/suggest', { method: 'POST', body: JSON.stringify({ session_id: sessionId, count: count || 3 }) }),
  /** P-0810-07：send 增可选 session_id（一般模式多会话定向；缺省走默认单例，旧调用零变化） */
  send: (text: string, playerName?: string, sessionId?: string) => request<any>('/api/send', { method: 'POST', body: JSON.stringify({ text, player_name: playerName || '', ...(sessionId ? { session_id: sessionId } : {}) }) }),
  /** P-0824-L：一般模式异步输入邮箱；202 只表示入队，AI 输出继续经会话 SSE 到达。 */
  worldInput: (text: string, playerName: string | undefined, sessionId: string, inputId?: string,
               focusedRoleId?: string, focusedRoleIds?: string[], conversationMembers?: string[]) =>
    request<any>('/api/world/input', {
      method: 'POST',
      body: JSON.stringify({
        session_id: sessionId,
        input_id: inputId || (globalThis.crypto?.randomUUID?.() ?? `input-${Date.now()}`),
        content: text,
        speaker: playerName || '',
        ...(focusedRoleId ? { focused_role_id: focusedRoleId } : {}),
        ...(focusedRoleIds?.length ? { focused_role_ids: focusedRoleIds } : {}),
        ...(conversationMembers?.length ? { conversation_members: conversationMembers } : {}),
        priority: 'CRITICAL',
      }),
    }),
  /** 一般模式世界状态：轻量路人、生命周期与场景人口概况。 */
  worldState: (sessionId: string) =>
    request<any>(`/api/world/state?session_id=${encodeURIComponent(sessionId)}`),
  /** P-0810-07：查询当前一般模式 mode（free/protagonist/multi_track/director；GET /api/mode?session_id=） */
  getMode: (sessionId?: string) =>
    request<any>(`/api/mode${sessionId ? `?session_id=${encodeURIComponent(sessionId)}` : ''}`),
  /** P-0810-15：场景背景图（配合后端 P-0810-14 背景端点；body {scene} → {url}；未就绪/失败由调用方渐变占位兜底） */
  sceneBackground: (scene: string) =>
    request<any>('/api/ai-image/scene-background', { method: 'POST', body: JSON.stringify({ scene }), timeout: 120000 }),
  stop: (sessionId?: string) => request<any>('/api/stop', {
    method: 'POST', body: JSON.stringify(sessionId ? { session_id: sessionId } : {}),
  }),
  setMode: (mode: string, protagonist?: string, directorCharacter?: string, sessionId?: string) => request<any>('/api/mode', {
    method: 'POST',
    body: JSON.stringify({ mode, protagonist: protagonist || '', director_character: directorCharacter || '', ...(sessionId ? { session_id: sessionId } : {}) }),
  }),

  setGoals: (goals: string[], sessionId?: string) => request<any>('/api/goals', {
    method: 'POST', body: JSON.stringify({ goals, ...(sessionId ? { session_id: sessionId } : {}) }),
  }),
  getGoals: () => request<any>('/api/goals'),
  getHistory: (params?: Record<string, string>) => {
    const qs = params ? '?' + new URLSearchParams(params).toString() : '';
    return request<any>(`/api/history${qs}`);
  },
  getHistorySessions: () => request<any>('/api/history/sessions'),
  getHistorySessionMessages: (sessionId: string, params?: Record<string, string>) => {
    const qs = params ? '?' + new URLSearchParams(params).toString() : '';
    return request<any>(`/api/history/sessions/${encodeURIComponent(sessionId)}${qs}`);
  },
  loadHistorySession: (sessionId: string) => request<any>(`/api/history/load/${encodeURIComponent(sessionId)}`, { method: 'POST' }),
  addAgent: (name: string) => request<any>('/api/agents', { method: 'POST', body: JSON.stringify({ name }) }),
  removeAgent: (name: string) => request<any>(`/api/agents/${encodeURIComponent(name)}`, { method: 'DELETE' }),
  createRoom: (playerName: string, mode = 'rules') => request<any>('/api/rooms', { method: 'POST', body: JSON.stringify({ player_name: playerName, mode }) }),
  joinRoom: (code: string, playerName: string, mode = 'rules') => request<any>(`/api/rooms/${encodeURIComponent(code)}/join`, { method: 'POST', body: JSON.stringify({ player_name: playerName, mode }) }),
  getRoom: (code: string) => request<any>(`/api/rooms/${encodeURIComponent(code)}`),
  leaveRoom: (code: string, playerName: string) => request<any>(`/api/rooms/${encodeURIComponent(code)}/leave`, { method: 'POST', body: JSON.stringify({ player_name: playerName }) }),
  assignRoomCharacters: (code: string, characters: string[]) => request<any>(`/api/rooms/${encodeURIComponent(code)}/assign`, { method: 'POST', body: JSON.stringify({ characters }) }),
  /** P-0802-C：狼人杀 init 改 JSON body 全量进局（players=真人+AI 全集，roles=职业配置按玩家映射）。
   *  修复根因（调研报告 §二）：原 query 方式 human_players 未建房=空 → 1 人村民死局、AI 从未进 GameState；
   *  职业配置原走 query 被后端静默丢弃。注意 roles 值须用后端枚举名（werewolf/seer/witch/hunter/villager）。 */
  werewolfInit: (playerName: string, players: string[], roles?: Record<string, string>, roomCode?: string) =>
    request<any>(`/api/werewolf/init?player_name=${encodeURIComponent(playerName)}`, {
      method: 'POST',
      body: JSON.stringify({ players, roles: roles || {}, ...(roomCode ? { room_code: roomCode } : {}) }),
    }),
  /** 狼人杀私密状态：对局、玩家和 roleKey 均显式传递，禁止按玩家名或当前局回退。 */
  werewolfStatus: (sessionId: string, player: string, playerKey: string) =>
    request<any>(`/api/werewolf/status?session_id=${encodeURIComponent(sessionId)}&player=${encodeURIComponent(player)}&player_key=${encodeURIComponent(playerKey)}`),
  /** P-0802-I：断线重连恢复 —— body: session_id 或 room_code + player；
   *  P-0802-J：+player_key（本人 roleKey，必填，防跨角色冒充；对齐剧本杀 C3 roleKey 体系） */
  werewolfResume: (body: { session_id?: string; room_code?: string; player?: string; player_key?: string }) =>
    request<any>('/api/werewolf/resume', { method: 'POST', body: JSON.stringify(body) }),
  // P-0802-C：狼人杀游戏端点封装（调研报告 G0 阶段缺口：原仅 init/status 两个封装，游戏端点前端零调用）
  werewolfNightAction: (sessionId: string, player: string, playerKey: string, action: string, target: string) =>
    request<any>('/api/werewolf/night_action', { method: 'POST', body: JSON.stringify({ session_id: sessionId, player, player_key: playerKey, action, target }) }),
  werewolfHunterShoot: (sessionId: string, player: string, playerKey: string, target: string) =>
    request<any>('/api/werewolf/hunter_shoot', { method: 'POST', body: JSON.stringify({ session_id: sessionId, player, player_key: playerKey, target }) }),
  werewolfVote: (sessionId: string, player: string, playerKey: string, target: string) =>
    request<any>('/api/werewolf/vote', { method: 'POST', body: JSON.stringify({ session_id: sessionId, player, player_key: playerKey, target }) }),
  werewolfResolveNight: (sessionId: string, dmKey: string) =>
    request<any>('/api/werewolf/resolve_night', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-DM-Key': dmKey }, body: JSON.stringify({ session_id: sessionId }) }),
  werewolfStartVoting: (sessionId: string, dmKey: string) =>
    request<any>('/api/werewolf/start_voting', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-DM-Key': dmKey }, body: JSON.stringify({ session_id: sessionId }) }),
  werewolfResolveVote: (sessionId: string, dmKey: string) =>
    request<any>('/api/werewolf/resolve_vote', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-DM-Key': dmKey }, body: JSON.stringify({ session_id: sessionId }) }),
  /** P-0802-F：白天讨论人类发言（接入后端讨论引擎，下轮入发言记录） */
  werewolfDiscussionSay: (sessionId: string, player: string, playerKey: string, message: string) =>
    request<any>('/api/werewolf/discussion_say', { method: 'POST', body: JSON.stringify({ session_id: sessionId, player, player_key: playerKey, message }) }),
  // 剧本杀 (Script murder mystery)
  // P-0803-K（剧本杀双版本）：mode 可选 —— 'full'（默认，真剧本杀：搜证+地图）/ 'chat'（简单对话版：无取证无地图，init 直达 DISCUSSION）
  // P-0803-P（联机房剧本杀接线，原缺口）：roomCode 可选 —— 非空时随 body 传 room_code 绑定房间
  //   （后端 ScriptController 已支持可选 room_code：init 登记 room→sessionId 映射，resume 可按房间码定位）
  scriptInit: (theme: string, players: string[], mode?: string, roomCode?: string, humanPlayer?: string) =>
    // P-0803-F：120s → 300s —— init 自动串联后 = 剧本 LLM + 地图 LLM 两次串行（各 30-90s），
    // 120s 必然触发 abort（"signal is aborted without reason"）。300s 覆盖正常+地图降级最坏路径。
    // P-0803-H2: 300s→600s，后端最坏 450s（剧本 180s+地图 270s 双重试）仍可能超 300s；chat 模式无地图 LLM，正常 60s 内返回
    request<any>('/api/script/init', { method: 'POST', body: JSON.stringify({
      theme, players, mode: mode || 'full', ...(roomCode ? { room_code: roomCode } : {}), ...(humanPlayer ? { human_player: humanPlayer } : {}),
    }), timeout: 600000 }),
  /** 阶段 2: 生成/获取对局地图（LLM 统一路径 → 校验 → BSP 降级，契约 v1）；P-0803-J/P-0803-P：可选 width/height/seed */
  scriptMap: (body: { session_id?: string; theme?: string; seed?: number; width?: number; height?: number; regenerate?: boolean }) =>
    request<any>('/api/script/map', { method: 'POST', body: JSON.stringify(body), timeout: 600000 }),
  /** P-0815-F（方向1，根因 A）：完整剧本生成（两阶段 init 的后半程）——
   *  body: session_id（可选，缺省当前对局）；返回 {ok, generating, session_id, phase, message}。
   *  后端异步执行（完整剧本→落库→阶段推进→地图），完成推 script_ready + script_phase + script_status；
   *  生成中状态经 status.generating / script_status.generating 可查（前端显示「生成中…」）。
   *  30s 超时足够——端点立即返回，LLM 在后台线程跑。 */
  scriptGenerateFull: (sessionId?: string) =>
    request<any>('/api/script/generate_full', { method: 'POST', body: JSON.stringify({ session_id: sessionId || '' }), timeout: 30000 }),
  scriptStatus: (player?: string, playerKey?: string) =>
    request<any>(`/api/script/status?player=${encodeURIComponent(player || '')}${playerKey ? `&player_key=${encodeURIComponent(playerKey)}` : ''}`),
  scriptSearch: (player: string, location: string, playerKey?: string) =>
    request<any>('/api/script/search', { method: 'POST', body: JSON.stringify({ player, location, session_id: getScriptSessionId(), ...(playerKey ? { player_key: playerKey } : {}) }) }),
  /** P-0814-H: 地图热点/交互点统一动作键交互（decor 实体 / tileProps 瓦片动作 / 环境占位）
   *  body: player（必填）/ player_key?（C3 身份认证）/ session_id?（缺省按 player 回退）/ map_id?（缺省当前图）/
   *        decor_id?（显式目标，与 tile 至少其一）/ tile?（"x,y" 目标格）/ x·y?（玩家瓦片坐标，靠近校验缺省跳过） */
  scriptInteract: (body: { player: string; player_key?: string; session_id?: string; map_id?: string; decor_id?: string; tile?: string; x?: number; y?: number }) =>
    request<any>('/api/script/interact', { method: 'POST', body: JSON.stringify(body) }),
  /** C2: 线索转交（body: player, target_player, clue_id）—— 转交后 ownership 变更，接收方 status 可见 */
  scriptTransferClue: (player: string, targetPlayer: string, clueId: string, playerKey?: string) =>
    request<any>('/api/script/transfer_clue', { method: 'POST', body: JSON.stringify({ player, target_player: targetPlayer, clue_id: clueId, ...(playerKey ? { player_key: playerKey } : {}) }) }),
  // G2（P-0802-M）：session_id 读 localStorage 镜像（store 同键持久化），不再硬编码 ''
  scriptStartDiscussion: (player: string, playerKey: string) =>
    request<any>('/api/script/start_discussion', { method: 'POST', body: JSON.stringify({ session_id: getScriptSessionId(), player, player_key: playerKey }) }),
  /** P-0805-A（B1）：剧本杀讨论阶段人类发言 —— 并入 discussion_say 单一讨论组（人类发言权豁免，不过 SpeechGate）
   *  body: player（发言者）/ message（内容）/ player_key?（本人 roleKey，身份校验，防冒充） */
  scriptDiscussionSay: (player: string, message: string, playerKey?: string) =>
    request<any>('/api/script/discussion_say', { method: 'POST', body: JSON.stringify({
      player, message, ...(playerKey ? { player_key: playerKey } : {}),
    }) }),
  /** P-0805-B（私聊闭环）：剧本杀私聊 —— 玩家与 AI 角色一对一密聊（body: player/target/message/player_key?） */
  scriptPrivateSay: (player: string, target: string, message: string, playerKey?: string) =>
    request<any>('/api/script/private', { method: 'POST', body: JSON.stringify({
      player, target, message, ...(playerKey ? { player_key: playerKey } : {}),
    }) }),
  /** P-0805-B：剧本杀私聊历史（player/other 双向可查） */
  scriptPrivateHistory: (player: string, other: string, playerKey?: string) =>
    request<any>(`/api/script/private/history?player=${encodeURIComponent(player)}&other=${encodeURIComponent(other)}${playerKey ? `&player_key=${encodeURIComponent(playerKey)}` : ''}`),
  /** P-0805-A（生图接入）：image_spec 生成（剧本 schema v1 → 结构化生图描述契约 v1） */
  imageSpec: (body: { theme?: string; script?: any }) =>
    request<any>('/api/image/spec', { method: 'POST', body: JSON.stringify(body), timeout: 120000 }),
  /** P-0805-C（生图接入）：单图生成（provider 可配 / 离线 SVG 占位；落 assets 登记） */
  imageGenerate: (body: { unit: any; name?: string; asset_type?: string }) =>
    request<any>('/api/image/generate', { method: 'POST', body: JSON.stringify(body), timeout: 120000 }),
  /** P-0809-B（阶段② API 逻辑链）：最近请求链路列表（后端开关关闭时 404，前端显示未开启提示） */
  traceList: (limit?: number) =>
    request<any>(`/api/debug/trace?limit=${limit || 50}`),
  /** P-0809-B：单条请求链路详情（含 LLM 子调用时间线 / 请求体摘要 / SSE 事件标记） */
  traceDetail: (requestId: string) =>
    request<any>(`/api/debug/trace/${encodeURIComponent(requestId)}`),
  scriptStartVoting: (player: string, playerKey: string) =>
    request<any>('/api/script/start_voting', { method: 'POST', body: JSON.stringify({ session_id: getScriptSessionId(), player, player_key: playerKey }) }),
  scriptVote: (player: string, suspect: string, _playerKey?: string) =>
    request<any>('/api/script/vote', { method: 'POST', body: JSON.stringify({ player, suspect }) }),
  /** P-0816-H（UI 重设计阶段一 API-11，决策 U8）：弃票分支 —— body 增 abstain:true（suspect 可空），
   *  独立 abstainedVoters 集合（不污染票型统计）；响应 {result} */
  scriptVoteAbstain: (player: string, _playerKey?: string) =>
    request<any>('/api/script/vote', { method: 'POST', body: JSON.stringify({
      player, abstain: true,
    }) }),
  /** P-0816-H（UI 重设计阶段一 API-10，决策 C13）：投票进度聚合 —— 只出聚合不出投票人；
   *  非 VOTE 阶段返回 {phase}（前端隐藏统计区） */
  scriptVoteStatus: (player?: string, playerKey?: string) =>
    request<any>(`/api/script/vote/status?player=${encodeURIComponent(player || '')}${playerKey ? `&player_key=${encodeURIComponent(playerKey)}` : ''}`),
  /** P-0816-H（UI 重设计阶段一 API-13，决策 U4/U14）：目标 HUD 规则模板 —— {ok, phase, goal{title,progress,detail}} */
  scriptGoal: (player?: string, playerKey?: string) =>
    request<any>(`/api/script/goal?player=${encodeURIComponent(player || '')}${playerKey ? `&player_key=${encodeURIComponent(playerKey)}` : ''}`),
  /** P-0816-I（UI 重设计阶段一 API-1，§3.2）：行动建议集 —— 搜证阶段主区行动条数据源
   *  响应 {ok, phase, actions[], ap, ap_max}；投票阶段 actions 为空（前端隐藏行动条） */
  scriptActions: (player?: string, playerKey?: string) =>
    request<any>(`/api/script/actions?player=${encodeURIComponent(player || '')}${playerKey ? `&player_key=${encodeURIComponent(playerKey)}` : ''}`),
  /** P-0816-I（UI 重设计阶段一 API-2，§3.2）：执行行动 —— ask|目标 / research|地点 / present|线索
   *  已搜地点回看不扣 AP（{replayed:true, clues:[]}，U7）；失败 {error}（如「行动点不足」） */
  scriptAction: (player: string, actionId: string, playerKey?: string) =>
    request<any>('/api/script/action', { method: 'POST', body: JSON.stringify({
      player, action_id: actionId, ...(playerKey ? { player_key: playerKey } : {}),
    }) }),
  /** P-0816-R（UI 重设计阶段二 API-3，§3.2，决策 U1）：心锁列表 —— 左栏 🔒 标记数据源
   *  规则推导过渡 + 终态 LLM 标注 unlock_role 宽容解析；{ok, locks:[{role,lock_count,unlock_clue_ids,unlocked}]}
   *  session_id 处理对齐 scriptActions（P-0816-P1 修复后模式：player + player_key，后端 resolveSessionId 兜底） */
  scriptLocks: (player?: string, playerKey?: string) =>
    request<any>(`/api/script/locks?player=${encodeURIComponent(player || '')}${playerKey ? `&player_key=${encodeURIComponent(playerKey)}` : ''}`),
  /** P-0816-R（UI 重设计阶段二 API-4，§3.2，决策 U1）：出示证据破锁
   *  body: {player, target_role, clue_id, player_key?} —— 命中解锁线索→破锁归零（SSE script_locks）；
   *  失败明确错误：{error:"这张线索解不开 TA 的心锁"} / {error:"线索不存在或未持有"}；幂等（已解锁提示） */
  scriptUnlock: (player: string, targetRole: string, clueId: string, playerKey?: string) =>
    request<any>('/api/script/unlock', { method: 'POST', body: JSON.stringify({
      player, target_role: targetRole, clue_id: clueId, ...(playerKey ? { player_key: playerKey } : {}),
    }) }),
  /** P-0816-R（UI 重设计阶段二 API-5，§3.2）：质询发言 —— pressed 标记写 discussionTranscript
   *  body: {player, target, message_id?, player_key?} —— 成功 SSE script_press；
   *  失败 {error:"当前不是讨论阶段"} / {error:"目标不在本局"}；同人重复质询幂等 */
  scriptPress: (player: string, target: string, messageId?: string, playerKey?: string) =>
    request<any>('/api/script/press', { method: 'POST', body: JSON.stringify({
      player, target, ...(messageId ? { message_id: messageId } : {}),
      ...(playerKey ? { player_key: playerKey } : {}),
    }) }),
  /** P-0816-T（UI 重设计阶段三 API-9，§3.2，决策 C8）：出示证据到对话流
   *  body: {player, clue_id, player_key?} —— 「🃏 出示：CL-xx 线索名」system 行插入 discussionTranscript（全员可见）
   *  + SSE script_present；幂等（重复出示提示已出示）；阶段守卫：仅 DISCUSSION 阶段可出示；
   *  失败 {error:"当前不是讨论阶段"} / {error:"未持有该线索"} / {error:"线索不存在: xx"} */
  scriptPresent: (player: string, clueId: string, playerKey?: string) =>
    request<any>('/api/script/present', { method: 'POST', body: JSON.stringify({
      player, clue_id: clueId, ...(playerKey ? { player_key: playerKey } : {}),
    }) }),
  /** P-0816-R（UI 重设计阶段二 API-8，§3.2，决策 U2 MVP 内容推导）：关系矩阵 —— 右栏逻辑链 Tab
   *  服务端推导：内容提及★ / 持有者◯ / 其余–；{ok, roles[], clues[], matrix, relations[]} */
  scriptRelations: (player?: string, playerKey?: string) =>
    request<any>(`/api/script/relations?player=${encodeURIComponent(player || '')}${playerKey ? `&player_key=${encodeURIComponent(playerKey)}` : ''}`),
  // resolve/finish 必须以当前 player_key 定位会话；旧 localStorage session_id
  // 可能属于上一局，显式带上会导致后端按旧会话鉴权失败。
  scriptResolve: (player: string, playerKey?: string) =>
    request<any>('/api/script/resolve', { method: 'POST', body: JSON.stringify({ player, ...(playerKey ? { player_key: playerKey } : {}) }) }),
  scriptFinish: (player: string, playerKey?: string) =>
    request<any>('/api/script/finish', { method: 'POST', body: JSON.stringify({ player, ...(playerKey ? { player_key: playerKey } : {}) }) }),
  /** P1（剧本杀可玩性修复，任务 2b）：ENDED 后重开一局 —— 同剧本主题同玩家（复用 session_id，轮询/SSE 定位不变） */
  scriptRestart: (player: string, playerKey: string) =>
    request<any>('/api/script/restart', {
      method: 'POST',
      body: JSON.stringify({ session_id: getScriptSessionId(), player, player_key: playerKey }),
    }),
  /** P1（剧本杀可玩性修复，任务 2a）：玩家退出对局 —— 角色转托管（AI 代管，投票权作废） */
  scriptLeave: (player: string, playerKey?: string) =>
    request<any>('/api/script/leave', { method: 'POST', body: JSON.stringify({ player, ...(playerKey ? { player_key: playerKey } : {}) }) }),
  /** C4: DM 全量视图（state:dm_dashboard）—— 所有玩家角色/秘密/AP/线索/投票/roleKey + 对局元数据 */
  scriptDmStatus: (sessionId: string, dmKey?: string) =>
    request<any>(`/api/script/dm/status?session_id=${encodeURIComponent(sessionId)}`, {
      ...(dmKey ? { headers: { 'Content-Type': 'application/json', 'X-DM-Key': dmKey } } : {}),
    }),
  /** C4: DM 手动推进阶段（dm:advance）—— INVESTIGATION→DISCUSSION→VOTE→REVEAL→ENDED；VOTE 步经审批门（阻塞等待批准） */
  scriptAdvance: (sessionId: string, dmKey?: string) =>
    request<any>('/api/script/advance', {
      method: 'POST',
      body: JSON.stringify({ session_id: sessionId }),
      timeout: 100000,
      ...(dmKey ? { headers: { 'Content-Type': 'application/json', 'X-DM-Key': dmKey } } : {}),
    }),
  /** C3: DM 分发 roleKey（全员令牌一览） */
  scriptKeys: (sessionId: string, dmKey?: string) =>
    request<any>(`/api/script/keys?session_id=${encodeURIComponent(sessionId)}`, { headers: dmKey ? { 'X-DM-Key': dmKey } : {} }),
  /** P-0802-J: 狼人杀 roleKey 分发（全员令牌一览，对齐剧本杀 scriptKeys） */
  werewolfKeys: (sessionId: string, dmKey: string) =>
    request<any>(`/api/werewolf/keys?session_id=${encodeURIComponent(sessionId)}`, { headers: { 'X-DM-Key': dmKey } }),
  /** C3: 断线重连恢复 —— body: game_id 或 room_code + player_key → 恢复玩家视图（ENDED 含终态结果） */
  scriptResume: (body: { game_id?: string; room_code?: string; player_key?: string }) =>
    request<any>('/api/script/resume', { method: 'POST', body: JSON.stringify(body) }),
  /** C4: D7 审批门 —— DM 批准 / 驳回 / 查询状态（主持人面板用） */
  approvalApprove: (sessionId: string) =>
    request<any>('/api/approval/approve', { method: 'POST', body: JSON.stringify({ session_id: sessionId }) }),
  approvalReject: (sessionId: string, reason = 'DM 驳回') =>
    request<any>('/api/approval/reject', { method: 'POST', body: JSON.stringify({ session_id: sessionId, reason }) }),
  approvalStatus: (sessionId: string) =>
    request<any>(`/api/approval/status?session_id=${encodeURIComponent(sessionId)}`),
  simulationState: () => request<any>('/api/simulation/state'),
  simulationTrackState: () => request<any>('/api/simulation/track/state'),
  /**
   * P-0803-G（轨道系统用户加入，方案A 前端）：玩家加入 2D 世界现有对话组。
   * POST /api/simulation/group/{groupId}/join  body {player_name}
   * 成功 → {status:"ok", group:{id,mode,participants}}；失败 → {status:"error", message}（组不存在/角色不在场/已在组/组已满）。
   */
  joinConversation: (groupId: string, playerName: string) =>
    request<any>(`/api/simulation/group/${encodeURIComponent(groupId)}/join`, {
      method: 'POST',
      body: JSON.stringify({ player_name: playerName }),
    }),
  /** P-0803-G：玩家离开对话组（组内无人时后端自动解散）。POST /api/simulation/group/{groupId}/leave */
  leaveConversation: (groupId: string, playerName: string) =>
    request<any>(`/api/simulation/group/${encodeURIComponent(groupId)}/leave`, {
      method: 'POST',
      body: JSON.stringify({ player_name: playerName }),
    }),
  /**
   * P-0814-A：点击驱动对话模式 —— 前端「播出完毕」信号（一轮展示完成 → 请求生成下一轮）。
   * POST /api/simulation/playback_done  body {session_id?, group_id?}
   *  - group_id 非空 → 2D 世界对话组推进（该组下一轮）；
   *  - 否则 → 一般模式 RouterService 轮次推进（下一轮）。
   * 幂等：非等待态重复信号被后端忽略（不产生多余轮次）。
   */
  simPlaybackDone: (body: { session_id?: string; group_id?: string }) =>
    request<any>('/api/simulation/playback_done', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  // 演讲+广播合并地基（demo 入口）
  /** 玩家发广播：POST /api/announcements（默认 PLAYER 级全局公告） */
  announcementSend: (text: string, opts?: { level?: string; channel?: string; mode?: string; speaker?: string }) =>
    request<any>('/api/announcements', {
      method: 'POST',
      body: JSON.stringify({ text, level: opts?.level || 'PLAYER', channel: opts?.channel || 'global', mode: opts?.mode || 'announcement', speaker: opts?.speaker || '玩家' }),
    }),
  /** AI 自动演讲/广播：POST /api/simulation/speech（形态由系统自动判定） */
  simulationSpeech: (speaker?: string, text?: string) =>
    request<any>('/api/simulation/speech', {
      method: 'POST',
      body: JSON.stringify({ speaker: speaker || '', text: text || '' }),
    }),
  announcementRecent: (since = 0) =>
    request<any>(`/api/announcements/recent?since=${since}`),
  /** 演讲广播模式查看：GET /api/announcements/mode（merged=正式版默认 / auto=方案A 旧行为 / split=方案B 旧行为） */
  broadcastModeGet: () =>
    request<{ mode: string }>('/api/announcements/mode'),
  /** 演讲广播模式切换：POST /api/announcements/mode（{mode:'merged'|'auto'|'split'}） */
  broadcastModeSet: (mode: string) =>
    request<any>('/api/announcements/mode', {
      method: 'POST',
      body: JSON.stringify({ mode }),
    }),
  /** P-0804-C：素材列表（可选 ?type=&character=&scene= 过滤；响应含 linked_character_name/linked_scene_name） */
  assetList: (params?: { type?: string; character?: string; scene?: string }) => {
    const qs = params
      ? '?' + new URLSearchParams(
          Object.entries(params)
            .filter(([, v]) => v && String(v).trim())
            .map(([k, v]) => [k, String(v)])
        ).toString()
      : '';
    return request<any[]>(`/api/assets${qs}`);
  },
  // Voice loop
  voiceStatus: () => request<any>('/api/voice/status'),
  voiceStart: () => request<any>('/api/voice/start', { method: 'POST' }),
  voiceStop: () => request<any>('/api/voice/stop', { method: 'POST' }),
  getApiKeyConfig: () => request<any>('/api/config/apikey'),
  setApiKeyConfig: (apiKey: string, apiBase?: string, model?: string, language?: string, trackActivity?: string) =>
    request<any>('/api/config/apikey', { method: 'POST', body: JSON.stringify({ api_key: apiKey, api_base: apiBase || '', model: model || '', language: language || 'zh', track_activity: trackActivity || 'auto' }) }),
  getLanguage: () => request<{language: string}>('/api/config/language'),
  setLanguage: (language: string) => request<any>('/api/config/language', { method: 'POST', body: JSON.stringify({ language }) }),
    getModelRecommendations: () => request<any>('/api/config/models'),
    discoverModels: (baseUrl: string, apiKey: string) => request<{ models: Array<{ id: string; name?: string }> }>('/api/config/models/discover', {
      method: 'POST', body: JSON.stringify({ base_url: baseUrl, api_key: apiKey }), timeout: 12000,
    }),
  getVoiceConfig: () => request<any>('/api/config/voice'),
  setVoiceConfig: (voiceEnabled: boolean) => request<any>('/api/config/voice', { method: 'POST', body: JSON.stringify({ voice_enabled: voiceEnabled }) }),
  /** LLM / 地图 LLM / TTS / ComfyUI 图片生成的统一运行时配置。GET 不返回明文密钥。 */
  getIntegrationConfig: () => request<any>('/api/config/integrations'),
  setIntegrationConfig: (body: any) => request<any>('/api/config/integrations', {
    method: 'POST', body: JSON.stringify(body),
  }),

  // ── AI 生图（P-0810-01：本地 ComfyUI + Pony V6 XL 角色表情集预生成）──────────────────
  /** 全量状态（注册表 + 生成任务 + 已生成图 URL；前端头像映射数据源） */
  aiImageStatus: () => request<any>('/api/ai-image/status'),
  /** 某角色已生成图：{characterId, name, avatar, expressions:{happy:url,...}, images:{frame:url}} */
  aiImageCharacterImages: (id: string) =>
    request<any>(`/api/ai-image/character/${encodeURIComponent(id)}/images`),
  /** 注册/更新角色：{id, name, appearance 外貌描述, style 风格描述}（风格必须固定保证同角色一致） */
  aiImageRegisterCharacter: (data: { id: string; name: string; appearance: string; style: string }) =>
    request<any>('/api/ai-image/character', { method: 'POST', body: JSON.stringify(data) }),
  /** 触发某角色生成（头像 1 + 表情 6，异步；返回 {taskId, status, progress}） */
  aiImageGenerate: (characterId: string) =>
    request<any>('/api/ai-image/generate', { method: 'POST', body: JSON.stringify({ characterId }) }),

  // ── MiMo TTS（P-0817-A 后端就绪 · 前端接入批次）──────────────────────
  /** 同步合成（?json=true → {audio_base64, format, transcript, model, elapsed_ms, mode, bytes}）
   *  body: {text 必填, mode=basic|clone|design(默认 basic), voice? 内置音色名,
   *         voice_data?(clone=参考音频路径或 data URL / design=音色描述 / basic=内置音色名),
   *         tone? 语气, format='wav', character? 按角色名从角色库解析声线}
   *  真实 LLM 音色合成可能数秒，超时放宽到 120s（异步友好）。 */
  mimoTtsSynthesize: (body: { text: string; mode?: string; voice?: string; voice_data?: string; tone?: string; format?: string; character?: string }) =>
    request<any>('/api/tts/mimo/synthesize?json=true', {
      method: 'POST',
      body: JSON.stringify(body),
      timeout: 120000,
    }),
  /** 内置音色清单（basic 模式可用名；GET /api/tts/mimo/voices） */
  mimoTtsVoices: () => request<string[]>('/api/tts/mimo/voices'),
  /** 运行时状态（enabled/configured/apiBase/model/builtinVoices；不暴露 apiKey） */
  mimoTtsStatus: () => request<any>('/api/tts/mimo/status'),
  /** 角色声线配置（voice_mode/voice_data/voice 来自角色库 + tts 状态；角色不存在 → 404） */
  mimoTtsVoiceConfig: (characterName: string) =>
    request<any>(`/api/tts/mimo/voice-config/${encodeURIComponent(characterName)}`),
};
