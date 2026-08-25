/**
 * galSseAdapter.ts — Gal 真实对局 SSE 适配层（P-0810-06，阶段 B）
 *
 * 职责（网络/副作用面，与 GalStore 的纯状态机解耦）：
 *   1) resolveSessionId —— 对局标识解析（session_id 直连 / 房间码·对局 ID 经 resume 端点反查）；
 *   2) startLiveSync —— 对局同步：类型探测（剧本杀/狼人杀/一般）+ 剧本杀讨论增量轮询
 *      （后端剧本杀讨论 AI 发言不走 SSE（D-012 讨论引擎独立实例无推送），经
 *       GET /api/script/status 的 discussion 转录增量入队，3s 轮询）；
 *   3) liveSay —— 玩家发言路由：剧本杀讨论 → scriptDiscussionSay / 狼人杀白天讨论 →
 *      werewolfDiscussionSay / 其他 → api.send（按当前对局类型与阶段判断）。
 *
 * 事件 → store 的纯映射在 GalStore.applySseEvent（本文件只做网络调用与增量源）。
 */
import { useGalStore, type GalStoreApi, isNarratorAgent } from './GalStore';
import { api } from '../api/client';
import { isSilenceText } from '../utils/silenceMarker';

// ── 对局标识解析 ───────────────────────────────────────────────

/**
 * P-0810-07：一般模式 4 分类（后端 RouterService mode；/api/init 可指定，/api/mode 可查/切）。
 * 仅接受这 4 个值：脚本/狼人杀会话不在 SessionRegistry，GET /api/mode 会回退默认单例
 * router 的 mode（可能是 script/werewolf）——只认一般值防误判。
 */
const GENERAL_MODES = new Set(['free', 'protagonist', 'multi_track', 'director']);

/**
 * 解析用户输入的对局标识：
 *  - 含连字符（后端 init 的 session_id 形如 UUID 前 12 位 "xxxxxxxx-xxx"）→ 直连；
 *  - 房间码 / 对局 ID（无连字符）→ 依次尝试 剧本杀 resume（需 player_key）→
 *    狼人杀 resume（需 player 名 + roleKey）反查 session_id；
 *  - 全部失败 → 仍按 session_id 直连兜底（由 SSE 事件自证，未知则事件过滤后无污染）。
 */
export async function resolveSessionId(
  input: string,
  playerName: string,
  playerKey: string,
): Promise<string> {
  const t = (input || '').trim();
  if (!t) throw new Error('对局标识为空');
  if (t.includes('-')) return t;

  // 剧本杀 resume（game_id / room_code 二选一 + player_key 身份校验）
  if (playerKey) {
    for (const key of ['game_id', 'room_code'] as const) {
      try {
        const r: any = await api.scriptResume({ [key]: t, player_key: playerKey });
        if (r?.session_id) return String(r.session_id);
      } catch { /* 尝试下一种 */ }
    }
  }
  // 狼人杀 resume（room_code + player + roleKey 身份校验）
  if (playerName && playerKey) {
    try {
      const r: any = await api.werewolfResume({ room_code: t, player: playerName, player_key: playerKey });
      if (r?.session_id) return String(r.session_id);
    } catch { /* 忽略 */ }
  }
  // 兜底：视为 session_id 直连
  return t;
}

// ── 对局同步（类型探测 + 剧本杀讨论增量轮询）────────────────

/**
 * 剧本杀讨论转录增量游标（按 store 实例隔离，P-0818-D）。
 * 旧实现为模块级单游标 + 单定时器：多面板（ScriptGalChatPanel / SimGalChatPanel /
 * live 共享同一游标与 timer，后挂载者覆盖前者的定时器、停用清理互相踩；
 * 且每次挂载游标从 0 重放——跨轮次（如第 2 轮搜证）会把上一轮讨论转录整段重放进聊天区，
 * 表现为「莫名其妙的对局聊天消息 + 输出语句错乱」。改为 WeakMap 按 store 实例隔离。
 */
const transcriptCursors = new WeakMap<object, number>();

/**
 * 启动对局同步（进入 live 模式且有 session_id 时调用，返回停止函数）：
 *  - 立即 + 每 3s 探测对局类型（狼人杀 status 显式 session_id / 剧本杀 status 按玩家名或当前局）；
 *  - 剧本杀对局：把 discussion 转录里新增的轮次（speaker+message）增量入队
 *    （跳过静默占位 SILENCE_MARKER；speaker=系统 的轮次入 system 旁白）。
 */
export function startLiveSync(sessionId: string, store?: GalStoreApi): () => void {
  // P-0815-F 批3（方向5）：per-instance 支持——宿主面板（ScriptGalChatPanel 等）自建 store 实例传入；
  // 未传时回退默认单例（旧调用点零改动）。
  const stApi = store ?? useGalStore;
  // 每实例独立定时器（旧实现模块级单 timer，多面板并存时相互覆盖）
  let syncTimer: ReturnType<typeof setInterval> | null = null;
  const syncOnce = async () => {
    const st = stApi.getState();
    if (!st.liveMode) return;
    const player = st.livePlayerName || '';

    // 狼人杀探测（显式 session_id，可靠）
    try {
      const playerKey = st.livePlayerKey || '';
      if (!player || !playerKey) throw new Error('狼人杀身份凭据尚未就绪');
      const ww: any = await api.werewolfStatus(sessionId, player, playerKey);
      if (ww && ww.phase && ww.phase !== 'idle' && !ww.game_over) {
        st.setLiveGameType('werewolf', ww.phase);
      }
    } catch { /* 后端不可达/无对局：忽略 */ }

    // 剧本杀探测 + 讨论转录增量（player 名定位；无 player 名回退服务器当前局；
    // 已探测为狼人杀时跳过——防当前局串扰）
    if (stApi.getState().liveGameType === 'werewolf') return;
    try {
      const sc: any = await api.scriptStatus(player);
      // 精确匹配：script status 无匹配玩家时后端兜底 currentSessionId（可能是别的对局），
      // 误判会把主控/一般会话的 agent_output 过滤掉（applySseEvent 对 script/werewolf 跳过 agent_output）。
      if (sc && sc.phase && sc.phase !== 'idle' && sc.session_id === sessionId) {
        st.setLiveGameType('script', sc.phase, sc.name || sc.theme || '');
        const turns: any[] = Array.isArray(sc.discussion) ? sc.discussion : [];
        // P-0818-D：游标惰性初始化 —— 首次成功拉到对局状态时，基线=当前转录长度
        //（挂载前已发生的旧轮次不重放；避免跨轮/重挂载把历史讨论整段重放进聊天区）。
        let cursor = transcriptCursors.get(stApi);
        if (cursor === undefined) {
          cursor = turns.length;
        }
        // P-0815-B：轮询转录与 script_speech SSE 双通道去重 —— 入队前按 (speakerId,text)
        // 对 liveQueue+log 建 seen 键（复用 pullGeneralHistory 键模式）；SSE 先到的发言此处跳过，
        // 转录游标继续推进（不重置），防断线重连/SSE 丢失窗口重复或遗漏。
        const pn = st.livePlayerName || '';
        const seen = new Set<string>();
        for (const m of st.liveQueue) seen.add(`${m.speakerId}\u0000${m.text}`);
        for (const l of st.log) seen.add(`${l.speakerId}\u0000${l.text}`);
        for (let i = cursor; i < turns.length; i++) {
          const turn = turns[i] || {};
          const sp = turn.speaker || '';
          const msg = turn.message || '';
          if (!sp || !msg || isSilenceText(msg)) continue;
          if (sp === '系统' || String(sp).startsWith('系统')) {
            st.liveEnqueue({ kind: 'system', speakerId: 'system', name: `📢 ${sp}`, text: msg });
            continue;
          }
          // 玩家发言：轮询转录无 human 标记，按 speaker===当前玩家名 归玩家样式
          // （与 script_speech SSE 的 human=true 通道同键同形，双通道共享去重键）
          const isPlayer = !!pn && sp === pn;
          const sidKey = isPlayer ? 'player' : sp;
          const key = `${sidKey}\u0000${msg}`;
          if (seen.has(key)) continue;
          seen.add(key);
          if (isPlayer) {
            st.liveEnqueue({ kind: 'player', speakerId: 'player', name: pn, text: msg });
          } else {
            st.liveEnsureSpeaker(sp);
            st.liveEnqueue({ kind: 'agent', speakerId: sp, name: sp, text: msg });
          }
        }
        transcriptCursors.set(stApi, Math.max(cursor, turns.length));
      }
    } catch { /* 忽略 */ }

    // P-0810-07：一般模式探测 —— 仅在狼人杀/剧本杀精确比对均未命中（仍 unknown）时执行，
    // GET /api/mode?session_id= 返回该会话 RouterService 的 mode（free/protagonist/multi_track/director）。
    // 脚本/狼人杀会话未命中时会话不在 SessionRegistry，回退默认单例 mode（可能 script/werewolf）→
    // 只接受 4 个一般值，其余（含查询失败）保持 unknown（SSE agent_output 到达即按一般处理）。
    if (stApi.getState().liveGameType !== 'unknown') return;
    try {
      const gm: any = await api.getMode(sessionId);
      const mode = String(gm?.mode || '');
      if (GENERAL_MODES.has(mode)) {
        st.setLiveGameType('general');
        st.setLiveGeneralMode(mode);
      }
    } catch { /* 后端不可达等 → 保持 unknown，SSE 事件自证 */ }
  };

  void syncOnce();
  syncTimer = setInterval(() => void syncOnce(), 3000);
  return () => {
    if (syncTimer) clearInterval(syncTimer);
    syncTimer = null;
    transcriptCursors.delete(stApi);
  };
}

// ── 一般模式历史补拉（P-0810-21）─────────────────────────────

/**
 * P-0810-21：一般模式连接后补拉一次历史（GET /api/history?session_id= 定向）。
 *
 * 背景：断点①——后端 GET /api/history 原读默认单例、session_id 参数无效 → 起局后
 * Gal 界面查不到已往消息；后端已支持 session_id 定向（HistoryController），前端在此
 * 把历史消息并入直播队列（agent → 立绘打字机 / user → 玩家（hidePlayerBubbles 下
 * 由 liveEnqueue 丢弃）/ 其他 → 旁白）。
 *
 * 去重：起局自动首轮（P-0810-14）与 SSE 连接存在重叠窗口（首轮生成中连接 → 历史与
 * agent_output 可能各播一次），按 (speakerId + text) 对 liveQueue/log 去重跳过。
 */
export async function pullGeneralHistory(sessionId: string): Promise<void> {
  const st = useGalStore.getState();
  if (!st.liveMode || !sessionId) return;
  try {
    const data: any = await api.getHistory({ limit: '100', session_id: sessionId });
    const list: any[] = Array.isArray(data) ? data : (data?.messages || []);
    if (!list.length) return;
    const s = useGalStore.getState();
    if (!s.liveMode) return;
    const playerName = s.livePlayerName || '';
    const seen = new Set<string>();
    for (const m of s.liveQueue) seen.add(`${m.speakerId}\u0000${m.text}`);
    for (const l of s.log) seen.add(`${l.speakerId}\u0000${l.text}`);
    for (const m of list) {
      const role = String(m?.role || '');
      const name = String(m?.name || '');
      const content = String(m?.content || '');
      if (!content.trim()) continue;
      // P-0814-G：玩家角色发言归类 —— 后端把玩家以自己角色身份说的话存为 role=agent + name=玩家名
      // （P0-2 speakerIsAgent 路径，speaker='me'/玩家角色名），若一律按 AI 消息入队会：①绕过
      // hidePlayerBubbles 设计以 AI 样式渲染玩家消息；②入队位置排在 SSE 先到的 AI 回复之后
      // （乱序——玩家消息绝不应出现在 AI 回复后面）。归为 player → 主控视图按设计隐藏、
      // 其余视图（hidePlayerBubbles=false）以玩家气泡渲染。'me' 是玩家保留名（P-0811-G 去除
      // AI 侧 'me' 兜底），恒归玩家。
      const isPlayerMsg = role === 'user'
        || (role === 'agent' && !!name && (name === 'me' || (playerName && name === playerName)));
      // 去重键与入队后的 speakerId 对齐（玩家→player / agent→name / 其他→system）
      const sid = isPlayerMsg ? 'player' : role === 'agent' ? name : 'system';
      const key = `${sid}\u0000${content}`;
      if (seen.has(key)) continue;
      seen.add(key);
      if (isPlayerMsg) {
        s.liveEnqueue({ kind: 'player', speakerId: 'player', name: name || playerName || '你', text: content });
      } else if (role === 'agent') {
        s.liveEnsureSpeaker(name);
        s.liveEnqueue({ kind: 'agent', speakerId: name, name, text: content });
      } else {
        // arbiter/system/其他 → 旁白样式（系统名前缀）
        s.liveEnqueue({ kind: 'system', speakerId: 'system', name: `📢 ${name || '系统'}`, text: content });
      }
    }
  } catch {
    // 历史拉取失败静默（SSE 流继续，历史抽屉仍可手动拉取）
  }
}

// ── 玩家发言路由 ───────────────────────────────────────────────

/**
 * P-0810-21-D：拉取玩家发言候选话术（POST /api/round/suggest，一般模式玩家回合可选项）——
 * 进入会话 / round_complete 时调用；后端 LLM 失败恒返回规则兜底候选；请求失败清空（不显示选项条）。
 */
export async function refreshSuggestions(sessionId: string): Promise<void> {
  const st = useGalStore.getState();
  if (!st.liveMode || !sessionId) return;
  try {
    const res: any = await api.suggest(sessionId, 3);
    const list: string[] = Array.isArray(res?.suggestions)
      ? res.suggestions.map(String).filter(Boolean).slice(0, 4)
      : [];
    useGalStore.getState().setLiveSuggestions(list);
  } catch {
    useGalStore.getState().setLiveSuggestions([]);
  }
}

/**
 * 玩家发言（底部输入框常驻，live 模式走这里）：
 *  剧本杀 SETUP/DISCUSSION → api.scriptDiscussionSay(player, text, playerKey)
 *  狼人杀 DAY_DISCUSS → api.werewolfDiscussionSay(sessionId, player, playerKey, text)
 *  其他（含未知/一般模式）→ api.send(text, playerName, sessionId)（P-0810-07：带 session_id
 *  定向到该一般会话实例，避免落到默认单例 router）
 * 本地回显入队（api.send 的 user_input SSE 回显经 isRecentPlayerEcho 去重；
 * script/werewolf discussion_say 无 SSE 回显，本地回显即唯一展示）。
 * 失败 → 系统提示行 + liveSendError（输入区红字）。
 */
export async function liveSay(text: string): Promise<void> {
  const st = useGalStore.getState();
  const body = text.trim();
  if (!body || !st.liveMode || !st.liveSessionId || st.liveSending) return;
  const player = st.livePlayerName || 'player';
  const key = st.livePlayerKey;
  st.setSending(true);
  st.setLiveIdentity(player, key);
  try {
    let agentOutputs: any[] | undefined;
    if (st.liveGameType === 'script' && (st.livePhase === 'SETUP' || st.livePhase === 'DISCUSSION')) {
      await api.scriptDiscussionSay(player, body, key || undefined);
    } else if (st.liveGameType === 'werewolf' && st.livePhase === 'DAY_DISCUSS') {
      if (!key) throw new Error('缺少狼人杀玩家令牌，请重新进入或恢复对局');
      await api.werewolfDiscussionSay(st.liveSessionId, player, key, body);
    } else {
      // P-0824-L：一般模式改走异步输入邮箱，请求线程立即返回；同 session 后台顺序消费，
      // AI 增量/结算继续走既有定向 SSE。旧/默认会话无 session_id 时保留同步 send 兼容。
      if (st.liveSessionId) {
        const queued: any = await api.worldInput(body, player, st.liveSessionId, undefined,
          st.liveFocusedRoleId || undefined, st.liveFocusedRoleIds, st.liveConversationMembers);
        useGalStore.setState({ livePendingInputId: String(queued?.input_id || '') });
      } else {
        const resp: any = await api.send(body, player, st.liveSessionId);
        agentOutputs = Array.isArray(resp?.agent_outputs) ? resp.agent_outputs : [];
      }
    }
    st.enqueuePlayerEcho(body);
    // 消费同步返回的 AI 回复（入队播放；SSE 若重复推送同一句由文本去重兜底）
    if (agentOutputs && agentOutputs.length > 0) {
      for (const out of agentOutputs) {
        const agent = String(out?.agent_name || '');
        const content = String(out?.content || '');
        if (!agent || !content.trim()) continue;
        // 入队前去重：同一 (speaker, text) 已在队/log 则跳过（防与 SSE agent_output 双播）
        const st2 = useGalStore.getState();
        const dup = [...st2.liveQueue, ...st2.log].some(m =>
          (m as any).speakerId === agent && m.text === content);
        if (dup) continue;
        // P-0817-N：主控/旁白/系统消息 → 旁白样式（无 TTS 播放按钮）
        if (isNarratorAgent(agent)) {
          st2.liveEnqueue({ kind: 'system', speakerId: 'system', name: `?? ${agent}`, text: content });
          continue;
        }
        st2.liveEnsureSpeaker(agent);
        st2.liveEnqueue({ kind: 'agent', speakerId: agent, name: agent, text: content });
      }
    }
    useGalStore.setState({ liveSending: false, liveSendError: '' });
  } catch (e: any) {
    const msg = e?.message || '未知错误';
    useGalStore.setState({ liveSending: false, livePendingInputId: '', liveSendError: msg });
    st.liveEnqueue({ kind: 'system', speakerId: 'system', name: '⚠️ 系统', text: `发言失败：${msg}` });
  }
}
