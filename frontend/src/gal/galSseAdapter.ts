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
import { useGalStore, defaultGalStore, type GalStoreApi, isNarratorAgent } from './GalStore';
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

  if (playerKey) {
    for (const key of ['game_id', 'room_code'] as const) {
      try {
        const r: any = await api.scriptResume({ [key]: t, player_key: playerKey });
        if (r?.session_id) return String(r.session_id);
      } catch { }
    }
  }
  if (playerName && playerKey) {
    try {
      const r: any = await api.werewolfResume({ room_code: t, player: playerName, player_key: playerKey });
      if (r?.session_id) return String(r.session_id);
    } catch { }
  }
  return t;
}

// ── 对局同步（类型探测 + 剧本杀讨论增量轮询）────────────────

const transcriptCursors = new WeakMap<object, number>();
const publicChatCursors = new WeakMap<object, number>();

export function startLiveSync(sessionId: string, store?: GalStoreApi): () => void {
  const stApi = store ?? useGalStore;
  const sessionEpoch = stApi.getState().liveSessionEpoch;
  const isCurrent = () => {
    const current = stApi.getState();
    return current.liveMode && current.liveSessionId === sessionId && current.liveSessionEpoch === sessionEpoch;
  };
  let syncTimer: ReturnType<typeof setInterval> | null = null;
  const syncOnce = async () => {
    const st = stApi.getState();
    if (!isCurrent()) return;
    const player = st.livePlayerName || '';

    try {
      const playerKey = st.livePlayerKey || '';
      if (!player || !playerKey) throw new Error('狼人杀身份凭据尚未就绪');
      const ww: any = await api.werewolfStatus(sessionId, player, playerKey);
      if (!isCurrent()) return;
      if (ww && ww.phase && ww.phase !== 'idle' && !ww.game_over) {
        st.setLiveGameType('werewolf', ww.phase);
      }
    } catch { }

    if (stApi.getState().liveGameType === 'werewolf') return;
    try {
      const sc: any = await api.scriptStatus(player);
      if (!isCurrent()) return;
      if (sc && sc.phase && sc.phase !== 'idle' && sc.session_id === sessionId) {
        st.setLiveGameType('script', sc.phase, sc.name || sc.theme || '');
        const turns: any[] = Array.isArray(sc.discussion) ? sc.discussion : [];
        let cursor = transcriptCursors.get(stApi);
        if (cursor === undefined) cursor = turns.length;
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

        const publicTurns: any[] = Array.isArray(sc.public_chat) ? sc.public_chat : [];
        let publicCursor = publicChatCursors.get(stApi);
        if (publicCursor === undefined) publicCursor = Math.max(0, publicTurns.length - 100);
        for (let i = publicCursor; i < publicTurns.length; i++) {
          const turn = publicTurns[i] || {};
          const sp = String(turn.speaker || '');
          const msg = String(turn.message || '');
          if (!sp || !msg) continue;
          const narrator = turn.kind === 'narrator';
          const playerChat = turn.kind === 'player';
          const isPlayer = playerChat || (!narrator && !!pn && sp === pn);
          const speakerId = narrator ? 'system' : sp === pn ? 'player' : sp;
          const key = `${speakerId}\u0000${msg}`;
          if (seen.has(key)) continue;
          seen.add(key);
          if (narrator) st.liveEnqueue({ kind: 'system', speakerId: 'system', name: '📖 旁白', text: msg });
          else if (isPlayer) st.liveEnqueue({ kind: 'player', speakerId, name: sp, text: msg });
          else { st.liveEnsureSpeaker(sp); st.liveEnqueue({ kind: 'agent', speakerId: sp, name: sp, text: msg }); }
        }
        publicChatCursors.set(stApi, Math.max(publicCursor, publicTurns.length));
      }
    } catch { }

    if (stApi.getState().liveGameType !== 'unknown') return;
    try {
      const gm: any = await api.getMode(sessionId);
      if (!isCurrent()) return;
      const mode = String(gm?.mode || '');
      if (GENERAL_MODES.has(mode)) {
        st.setLiveGameType('general');
        st.setLiveGeneralMode(mode);
      }
    } catch { }
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

export async function pullGeneralHistory(sessionId: string): Promise<void> {
  const st = useGalStore.getState();
  if (!st.liveMode || !sessionId) return;
  const sessionEpoch = st.liveSessionEpoch;
  try {
    const data: any = await api.getHistory({ limit: '100', session_id: sessionId });
    const list: any[] = Array.isArray(data) ? data : (data?.messages || []);
    if (!list.length) return;
    const s = useGalStore.getState();
    if (!s.liveMode || s.liveSessionId !== sessionId || s.liveSessionEpoch !== sessionEpoch) return;
    const playerName = s.livePlayerName || '';
    const seen = new Set<string>();
    for (const m of s.liveQueue) seen.add(`${m.speakerId}\u0000${m.text}`);
    for (const l of s.log) seen.add(`${l.speakerId}\u0000${l.text}`);
    for (const m of list) {
      const role = String(m?.role || '');
      const name = String(m?.name || '');
      const content = String(m?.content || '');
      if (!content.trim()) continue;
      const isPlayerMsg = role === 'user'
        || (role === 'agent' && !!name && (name === 'me' || (playerName && name === playerName)));
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
        s.liveEnqueue({ kind: 'system', speakerId: 'system', name: `📢 ${name || '系统'}`, text: content });
      }
    }
  } catch { }
}

// ── 玩家发言路由 ───────────────────────────────────────────────

export async function refreshSuggestions(sessionId: string): Promise<void> {
  const st = useGalStore.getState();
  if (!st.liveMode || !sessionId) return;
  const sessionEpoch = st.liveSessionEpoch;
  try {
    const res: any = await api.suggest(sessionId, 3);
    const list: string[] = Array.isArray(res?.suggestions)
      ? res.suggestions.map(String).filter(Boolean).slice(0, 4)
      : [];
    const current = useGalStore.getState();
    if (current.liveMode && current.liveSessionId === sessionId && current.liveSessionEpoch === sessionEpoch) {
      current.setLiveSuggestions(list);
    }
  } catch {
    const current = useGalStore.getState();
    if (current.liveMode && current.liveSessionId === sessionId && current.liveSessionEpoch === sessionEpoch) {
      current.setLiveSuggestions([]);
    }
  }
}

/**
 * 玩家消息必须按“正在显示的消息之后”插入，而不是追加到整轮队尾。
 * 先复用 store 的标准 echo 创建消息/启动空闲播放器，再把刚创建的玩家消息
 * 移到 current 后一位；这样已到达但尚未展示的同轮 AI 输出不会挡在玩家前面。
 */
function enqueuePlayerInterjection(text: string, store: GalStoreApi): void {
  const before = store.getState();
  const sentAt = Date.now();
  before.enqueuePlayerEcho(text);
  const after = store.getState();
  if (after.hidePlayerBubbles) return;

  const queue = [...after.liveQueue];
  let echoIndex = -1;
  for (let i = queue.length - 1; i >= 0; i--) {
    const item = queue[i];
    if (item.kind === 'player' && item.text === text && item.ts >= sentAt - 1000) {
      echoIndex = i;
      break;
    }
  }
  if (echoIndex < 0) return;

  const [echo] = queue.splice(echoIndex, 1);
  const currentId = (after.current as any)?.id ? String((after.current as any).id) : '';
  const currentIndex = currentId ? queue.findIndex(item => item.id === currentId) : -1;
  const insertAt = currentIndex >= 0 ? currentIndex + 1 : 0;
  queue.splice(Math.min(insertAt, queue.length), 0, echo);
  store.setState({ liveQueue: queue, liveLastSent: Date.now(), livePlaybackArmed: false });
}

/**
 * 玩家发言：先在本地时间线即时插入，再提交网络请求。
 * 一般模式走异步世界邮箱；后台完成后仍由 session 定向 SSE 推送 AI 增量/结算。
 */
export async function liveSay(text: string, store: GalStoreApi = defaultGalStore, scriptPublic = false): Promise<void> {
  const st = store.getState();
  const body = text.trim();
  if (!body || !st.liveMode || !st.liveSessionId || st.liveSending || !!st.livePendingInputId) return;
  const player = st.livePlayerName || 'player';
  const key = st.livePlayerKey;
  st.setSending(true);
  st.setLiveIdentity(player, key);

  // 用户按下发送即进入当前对话流，不等待 202/整轮生成完成。
  enqueuePlayerInterjection(body, store);

  try {
    let agentOutputs: any[] | undefined;
    if (st.liveGameType === 'script' && scriptPublic) {
      await api.scriptChat(st.liveSessionId, player, body, key || undefined);
    } else if (st.liveGameType === 'script' && (st.livePhase === 'SETUP' || st.livePhase === 'DISCUSSION')) {
      await api.scriptDiscussionSay(player, body, key || undefined);
    } else if (st.liveGameType === 'werewolf' && st.livePhase === 'DAY_DISCUSS') {
      if (!key) throw new Error('缺少狼人杀玩家令牌，请重新进入或恢复对局');
      await api.werewolfDiscussionSay(st.liveSessionId, player, key, body);
    } else {
      if (st.liveSessionId) {
        const inputId = globalThis.crypto?.randomUUID?.() ?? `input-${Date.now()}`;
        store.setState({ livePendingInputId: inputId });
        const queued: any = await api.worldInput(body, player, st.liveSessionId, inputId,
          st.liveFocusedRoleId || undefined, st.liveFocusedRoleIds, st.liveConversationMembers);
        const acceptedId = String(queued?.input_id || inputId);
        if (acceptedId !== inputId) store.setState({ livePendingInputId: acceptedId });
      } else {
        const resp: any = await api.send(body, player, st.liveSessionId);
        agentOutputs = Array.isArray(resp?.agent_outputs) ? resp.agent_outputs : [];
      }
    }

    if (agentOutputs && agentOutputs.length > 0) {
      for (const out of agentOutputs) {
        const agent = String(out?.agent_name || '');
        const content = String(out?.content || '');
        if (!agent || !content.trim()) continue;
        const st2 = store.getState();
        const dup = [...st2.liveQueue, ...st2.log].some(m =>
          (m as any).speakerId === agent && m.text === content);
        if (dup) continue;
        if (isNarratorAgent(agent)) {
          st2.liveEnqueue({ kind: 'system', speakerId: 'system', name: `📖 ${agent}`, text: content });
          continue;
        }
        st2.liveEnsureSpeaker(agent);
        st2.liveEnqueue({ kind: 'agent', speakerId: agent, name: agent, text: content });
      }
    }
    store.setState({ liveSending: false, liveSendError: '' });
  } catch (e: any) {
    const msg = e?.message || '未知错误';
    store.setState({ liveSending: false, livePendingInputId: '', liveSendError: msg });
    store.getState().liveEnqueue({ kind: 'system', speakerId: 'system', name: '⚠️ 系统', text: `发言失败：${msg}` });
  }
}
