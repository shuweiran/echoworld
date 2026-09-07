/**
 * GalGeneralView.tsx — 一般模式「呈现接管」Gal 界面（P-0810-08，主人拍板）
 *
 * 一般模式会话的呈现入口直接就是 Gal 视觉小说式角色扮演聊天视图
 * （替换 ChatPage 聊天视图，作为一般模式的唯一呈现入口）。
 *
 * 去对局化（需求）：
 *  - 顶部栏：返回（回会话列表）+ 会话标题/场景名 + mode 标签（一般·主角/导演）+ 角色成员小头像；
 *  - 去掉：对局状态 chips（阶段/事件计数/session_id）、连接面板、快速起局区块、类型切换；
 *  - 保留：立绘切换 + 打字机 + 底部发言框 + mode 中文标签；
 *  - 立绘：≤2 分列、>2 居中切换（GalGeneralStage）；
 *  - 玩家发言实时显示，并插入当前正在播放的对话之后，而不是等待整轮结束。
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

interface DirectorChatLine {
  role: 'player' | 'director';
  text: string;
}

/** SSE 桥（hook 必须常驻已挂载组件 → 独立组件按 sessionId 条件渲染） */
function GalGeneralSseBridge() {
  const sessionId = useGalStore(s => s.liveSessionId);
  const applySseEvent = useGalStore(s => s.applySseEvent);
  const bumpLiveEvent = useGalStore(s => s.bumpLiveEvent);
  const setLiveStatus = useGalStore(s => s.setLiveStatus);
  // P0 断线最小恢复：仅在进入 open 的边沿触发一次（初连 + 每次重连），防 open 重复回调刷屏
  const prevOpenRef = useRef(false);

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
      void pullGeneralHistory(useGalStore.getState().liveSessionId);
      void refreshSuggestions(useGalStore.getState().liveSessionId);
    }
  }, [applySseEvent, bumpLiveEvent]);
  const onStatus = useCallback((st: any) => {
    setLiveStatus(st);
    const open = st === 'open';
    if (open && !prevOpenRef.current) {
      void useGalStore.getState().resyncFromPersisted();
    }
    prevOpenRef.current = open;
  }, [setLiveStatus]);

  useSSE(onEvent, sessionId, onStatus);
  return null;
}

interface GalGeneralViewProps {
  sessionId: string;
  playerName?: string;
  displayName?: string;
  onBack?: () => void;
}

export function GalGeneralView({ sessionId, playerName, displayName: customDisplayName, onBack }: GalGeneralViewProps) {
  const enterLiveMode = useGalStore(s => s.enterLiveMode);
  const exitLiveMode = useGalStore(s => s.exitLiveMode);
  const setHidePlayerBubbles = useGalStore(s => s.setHidePlayerBubbles);
  const setLiveGameType = useGalStore(s => s.setLiveGameType);
  const setLiveGeneralMode = useGalStore(s => s.setLiveGeneralMode);
  const setLiveIdentity = useGalStore(s => s.setLiveIdentity);
  const setSpeakers = useGalStore(s => s.setSpeakers);
  const started = useGalStore(s => s.started);
  const finished = useGalStore(s => s.finished);
  const tick = useGalStore(s => s.tick);
  const liveGeneralMode = useGalStore(s => s.liveGeneralMode);
  const livePlayerName = useGalStore(s => s.livePlayerName);
  const liveGoals = useGalStore(s => s.liveGoals);
  const setLiveGoals = useGalStore(s => s.setLiveGoals);
  const focusedRoleId = useGalStore(s => s.liveFocusedRoleId);
  const conversationMembers = useGalStore(s => s.liveConversationMembers);
  const setLiveConversation = useGalStore(s => s.setLiveConversation);
  const sessionEpochRef = useRef(0);

  const [scene, setScene] = useState<string>('');
  const [roster, setRoster] = useState<string[]>([]);
  const [modeLabel, setModeLabel] = useState('');
  const [metaReady, setMetaReady] = useState(false);
  const [ambientRoles, setAmbientRoles] = useState<AmbientRole[]>([]);
  const [knownRoleIds, setKnownRoleIds] = useState<Record<string, string>>({});
  const [storyScript, setStoryScript] = useState<any>(null);
  const [storyOpen, setStoryOpen] = useState(false);
  const [directorDraft, setDirectorDraft] = useState('');
  const [directorSending, setDirectorSending] = useState(false);
  const [directorChat, setDirectorChat] = useState<DirectorChatLine[]>([{
    role: 'director',
    text: '我是动态剧本主控。我能解释公开剧情、安排后续线索与选择；不能改写已发生的事、替你决定或直接执行世界动作。',
  }]);
  const [cardName, setCardName] = useState('');
  const [cardJson, setCardJson] = useState('');
  const [cardMeta, setCardMeta] = useState<any>(null);
  const [cardLoading, setCardLoading] = useState(false);
  const [cardSaving, setCardSaving] = useState(false);
  const [cardError, setCardError] = useState('');

  const [historyOpen, setHistoryOpen] = useState(false);
  const [sceneCardOpen, setSceneCardOpen] = useState(false);
  const [roleDrawerOpen, setRoleDrawerOpen] = useState(false);
  const [groupDraft, setGroupDraft] = useState<string[]>([]);
  const [voicePanelOpen, setVoicePanelOpen] = useState(false);
  const [, setVoiceTick] = useState(0);
  useEffect(() => subscribeTtsStatus(() => setVoiceTick(t => t + 1)), []);

  useEffect(() => {
    if (!sessionId) return;
    setDirectorDraft('');
    setDirectorChat([{
      role: 'director',
      text: '我是权威主控。我负责玩家身份、角色登记与进退场、关系和场景状态；只有服务器真正执行成功后，我才会确认状态已改变。',
    }]);
    enterLiveMode(sessionId, { playerName: '' });
    sessionEpochRef.current = useGalStore.getState().liveSessionEpoch;
    void useGalStore.getState().refreshImageStatus();
    setHidePlayerBubbles(false);
    return () => {
      exitLiveMode();
      setHidePlayerBubbles(false);
    };
  }, [sessionId]);

  useEffect(() => {
    if (!started || finished) return;
    const t = setInterval(() => tick(2), 25);
    return () => clearInterval(t);
  }, [started, finished, tick]);

  const seededRosterRef = useRef('');
  useEffect(() => {
    if (!sessionId) return;
    let alive = true;
    const sessionEpoch = useGalStore.getState().liveSessionEpoch;
    const isCurrent = () => {
      const current = useGalStore.getState();
      return alive && current.liveMode && current.liveSessionId === sessionId && current.liveSessionEpoch === sessionEpoch;
    };
    const refreshMeta = async () => {
      try {
        const st: any = await api.getState(sessionId);
        if (!isCurrent()) return;
        const sc = st?.scene || st?.scene_description || '';
        if (sc) setScene(String(sc));
        if (st?.scene_goals && typeof st.scene_goals === 'object') {
          setLiveGoals(st.scene_goals);
        }
        if (Array.isArray(st?.agents)) {
          const names = st.agents.map(String).filter(Boolean);
          setRoster(names);
          const declaredPlayer = String(playerName || '').trim();
          const protagonist = String(st?.protagonist || '').trim();
          setLiveIdentity(declaredPlayer && declaredPlayer === protagonist && names.includes(protagonist)
            ? protagonist : '', undefined, true);
          const key = names.join(',');
          if (names.length > 0 && key !== seededRosterRef.current) {
            seededRosterRef.current = key;
            const playerSp = GAL_SPEAKERS.find(x => x.isPlayer);
            const npcs = names.map((name: string) => buildPlaceholderSpeaker(name, backendIdForName(name)));
            setSpeakers(playerSp ? [...npcs, playerSp] : npcs);
          }
        }
        setMetaReady(true);
      } catch { }
      try {
        const world: any = await api.worldState(sessionId);
        if (isCurrent()) {
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
      } catch { }
      try {
        const gm: any = await api.getMode(sessionId);
        if (!isCurrent()) return;
        const mode = String(gm?.mode || '');
        if (mode && GENERAL_MODE_LABEL[mode]) {
          setLiveGameType('general');
          setLiveGeneralMode(mode);
          setModeLabel(`一般·${GENERAL_MODE_LABEL[mode]}`);
        } else if (mode) {
          setModeLabel(`一般模式（${mode}）`);
        } else if (!modeLabel) {
          setModeLabel('一般模式');
        }
      } catch { }
    };
    void refreshMeta();
    void useGalStore.getState().refreshImageStatus();
    const t = setInterval(() => { void refreshMeta(); void useGalStore.getState().refreshImageStatus(); }, 5000);
    return () => { alive = false; clearInterval(t); };
  }, [sessionId]);

  useEffect(() => {
    if (!sessionId) return;
    const stop = startLiveSync(sessionId);
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
    void refreshSuggestions(sessionId);
    return () => {
      stop();
      clearInterval(pullTimer);
      clearInterval(stopPullTimer);
    };
  }, [sessionId]);

  useEffect(() => {
    (window as any).__galGeneralStore = useGalStore;
    return () => { delete (window as any).__galGeneralStore; };
  }, []);

  const sceneInfo: GalSceneInfo | undefined = scene
    ? { name: scene.length > 20 ? scene.slice(0, 20) + '…' : scene, description: scene }
    : undefined;

  const hasPlayer = !!String(livePlayerName || '').trim();
  const displayName = livePlayerName || '';
  const shownName = (hasPlayer && customDisplayName && customDisplayName.trim()) ? customDisplayName.trim() : displayName;
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

  const sendDirectorChat = async () => {
    const text = directorDraft.trim();
    if (!text || !sessionId || directorSending) return;
    const sessionEpoch = sessionEpochRef.current;
    setDirectorSending(true);
    setDirectorChat(lines => [...lines, { role: 'player', text }]);
    setDirectorDraft('');
    try {
      const result = await api.directorChat(sessionId, text);
      const current = useGalStore.getState();
      if (!current.liveMode || current.liveSessionId !== sessionId || current.liveSessionEpoch !== sessionEpoch) return;
      if (result.story) setStoryScript(result.story);
      setDirectorChat(lines => [...lines, { role: 'director', text: result.reply }]);
    } catch (e: any) {
      const current = useGalStore.getState();
      if (!current.liveMode || current.liveSessionId !== sessionId || current.liveSessionEpoch !== sessionEpoch) return;
      setDirectorChat(lines => [...lines, { role: 'director', text: `暂时无法回复：${e?.message || '未知错误'}` }]);
    } finally {
      const current = useGalStore.getState();
      if (current.liveMode && current.liveSessionId === sessionId && current.liveSessionEpoch === sessionEpoch) {
        setDirectorSending(false);
      }
    }
  };

  const openCard = async (name: string) => {
    if (!name || cardLoading) return;
    setCardName(name);
    setCardJson('');
    setCardMeta(null);
    setCardError('');
    setCardLoading(true);
    try {
      const res: any = await api.getCharacterCard(name);
      setCardMeta({ source: res?.source, version: res?.version, versions: res?.versions, surface: res?.surface });
      setCardJson(JSON.stringify(res?.card ?? {}, null, 2));
    } catch (e: any) {
      setCardError(`加载失败：${e?.message || '未知错误'}`);
    } finally {
      setCardLoading(false);
    }
  };

  const saveCard = async () => {
    if (!cardName || cardSaving) return;
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(cardJson || '{}');
    } catch {
      setCardError('JSON 格式非法，未保存');
      return;
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || Object.keys(parsed).length === 0) {
      setCardError('卡片内容不能为空');
      return;
    }
    setCardSaving(true);
    setCardError('');
    try {
      const res: any = await api.saveCharacterCard(cardName, parsed);
      setCardMeta((m: any) => ({ ...(m || {}), version: res?.version ?? m?.version }));
      setCardError('');
    } catch (e: any) {
      setCardError(`保存失败：${e?.message || '未知错误'}`);
    } finally {
      setCardSaving(false);
    }
  };

  return (
    <div className="galg-page">
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
          <TtsMuteButton className="galg-top-btn" />
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
        </div>
      </div>

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
        <div className="galg-identity">🎭 你扮演：{shownName}{shownName && shownName !== displayName && <span style={{ opacity: 0.75 }}>（化身：{displayName}）</span>}（发言会立即插入当前对话）</div>
      )}

      <GalHistoryDrawer open={historyOpen} onClose={() => setHistoryOpen(false)} sessionId={sessionId} />

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
            <section>
              <small>与主控对话</small>
              <p className="galg-story-next">主控可执行角色进退场、创建 NPC、关系与场景状态操作；只有服务器执行成功后才会确认。</p>
              <div style={{ display: 'grid', gap: 6, maxHeight: 180, overflowY: 'auto', marginBottom: 8 }} aria-live="polite">
                {directorChat.map((line, index) => <p key={`${index}:${line.text}`} style={{ margin: 0, padding: '6px 8px', borderRadius: 6,
                  background: line.role === 'player' ? 'rgba(77,225,255,.12)' : 'rgba(255,209,102,.1)' }}>
                  <strong>{line.role === 'player' ? '你' : '主控'}：</strong>{line.text}
                </p>)}
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <input
                  value={directorDraft}
                  onChange={e => setDirectorDraft(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') void sendDirectorChat(); }}
                  placeholder="例如：添加一个叫林夏的新 NPC，性格有点怕生"
                  disabled={directorSending}
                  style={{ flex: 1, minWidth: 0 }}
                />
                <button onClick={() => void sendDirectorChat()} disabled={directorSending || !directorDraft.trim()}>
                  {directorSending ? '主控思考中…' : '发送'}
                </button>
              </div>
            </section>
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
                      <button onClick={() => void openCard(role.name)} title="查看完整角色卡（来源/版本/五层）">🪪 卡片</button>
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

      {cardName && (
        <div className="galg-role-mask" onClick={() => setCardName('')}>
          <aside className="galg-role-drawer galg-card-drawer" onClick={event => event.stopPropagation()}>
            <div className="galg-role-head">
              <div>
                <strong>🪪 {cardName}</strong>
                <small>
                  {cardMeta ? `来源 ${cardMeta.source || 'LEGACY'} · 版本 ${cardMeta.version ?? '—'}` : '加载中…'}
                </small>
              </div>
              <button onClick={() => setCardName('')}>✕</button>
            </div>
            {cardLoading && <div className="hint" style={{ padding: 12 }}>加载中…</div>}
            {cardError && <div className="gal-live-error" style={{ margin: 12 }}>{cardError}</div>}
            {!cardLoading && !cardError && (
              <>
                <textarea
                  value={cardJson}
                  onChange={e => setCardJson(e.target.value)}
                  spellCheck={false}
                  rows={18}
                  style={{ width: '100%', boxSizing: 'border-box', fontFamily: 'monospace', fontSize: 12 }}
                />
                <div className="galg-role-footer">
                  <a href={api.characterCardExportUrl(cardName)} download>
                    <button type="button">导出 JSON</button>
                  </a>
                  <button onClick={() => void saveCard()} disabled={cardSaving}>
                    {cardSaving ? '保存中…' : '保存修改'}
                  </button>
                </div>
              </>
            )}
          </aside>
        </div>
      )}

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
