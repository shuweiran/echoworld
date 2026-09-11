/**
 * ScriptGalChatPanel.tsx — 剧本杀聊天部件 Gal 化（P-0815-B，方案 B）
 *
 * 需求：把一般模式 gal 聊天界面（立绘 + 打字机对话框 + 输入区）替换到剧本杀模式的
 * ChatPage 中间消息流对话区——右侧 ScriptStatePanel（秘密/搜证/AP/线索/投票/揭晓/DM）
 * 原样保留，仅替换「聊天呈现」；后端零改动、一般模式零回归（script 分支纯增量）。
 *
 * 设计（仿 SimGalChatPanel 范本 P-0813-D/G/K + GalGeneralStage 分层布局）：
 *  - 复用 GalStore live 消息流（liveQueue + typing 打字机 + advance「▼ 点击继续」）；
 *  - 消息源双通道：① script_speech / script_chat SSE 实时发言；② startLiveSync 3s 轮询
 *    discussion 转录增量（SSE 丢失窗口兜底，与 SSE 去重）；
 *  - 正式讨论：GalInputArea 默认 liveSay → api.scriptDiscussionSay；
 *  - 开场就绪 SETUP：注入 liveSayOverride → /api/script/flow/opening/say，玩家每次发言可触发 NPC；
 *  - 生成中的 SETUP：仍是公共频道，只用于等待期间闲聊，不伪装成完整角色已就绪。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useGalStore, createGalStore, GalStoreProvider } from './GalStore';
import { GalChatStage } from './GalChatStage';
import { GalInputArea } from './GalChoiceBar';
import { ScriptGameInfoBar } from './ScriptGameInfoBar';
import { buildPlaceholderSpeaker } from './galDemoData';
import { colorFor } from '../components/ChatPage/chatUtils';
import { startLiveSync } from './galSseAdapter';
import { useSSE } from '../api/useSSE';
import { api } from '../api/client';
import { scriptFlowApi } from '../api/scriptFlow';
import './gal.css';
import './galGeneral.css';

/** 各阶段入队一次的对局旁白。 */
const PHASE_NARRATIONS: Record<string, string> = {
  setup: '🎭 准备阶段：完整剧本就绪后先进行角色开场交流，由你确认后再进入搜证。',
  investigation: '🔍 搜证阶段：玩家与 AI 分别调查；点击地点卡片调查线索（消耗行动点）',
  discussion: '💬 讨论阶段：自由发言 · 质询矛盾 · 出示证据',
  vote: '🗳️ 投票阶段：指认你认为的凶手（可在投票面板弃票）',
  reveal: '🎬 揭晓时刻：投票结果揭晓，真相大白！',
  ended: '🏁 对局结束：感谢游玩，可返回剧本选择或重新开局',
};

export interface ScriptGalChatPanelProps {
  sessionId: string;
  playerName?: string;
  playerKey?: string;
  scriptState?: any;
}

/** SSE 桥：订阅 /api/events（会话定向）→ GalStore.applySseEvent。 */
function ScriptGalSseBridge() {
  const sessionId = useGalStore(s => s.liveSessionId);
  const playerName = useGalStore(s => s.livePlayerName);
  const playerKey = useGalStore(s => s.livePlayerKey);
  const applySseEvent = useGalStore(s => s.applySseEvent);
  const bumpLiveEvent = useGalStore(s => s.bumpLiveEvent);
  const setLiveStatus = useGalStore(s => s.setLiveStatus);
  const onEvent = useCallback((evt: string, data: any) => {
    bumpLiveEvent();
    applySseEvent(evt, data);
  }, [applySseEvent, bumpLiveEvent]);
  const onStatus = useCallback((st: any) => setLiveStatus(st), [setLiveStatus]);
  useSSE(onEvent, sessionId || undefined, onStatus, { player: playerName, playerKey });
  return null;
}

export function ScriptGalChatPanel({ sessionId, playerName, playerKey, scriptState }: ScriptGalChatPanelProps) {
  const mountedRef = useRef('');
  const galStore = useMemo(() => createGalStore(), []);

  useEffect(() => {
    if (!sessionId || mountedRef.current === sessionId) return;
    mountedRef.current = sessionId;
    const st = galStore.getState();
    st.enterLiveMode(sessionId, { playerName, playerKey });
    void st.refreshImageStatus();
    const phase = scriptState?.phase || '';
    const title = scriptState?.name || scriptState?.theme || '';
    st.setLiveGameType('script', phase, title);
    st.setLiveStatus('open');
    st.setHidePlayerBubbles(false);
    const stopSync = startLiveSync(sessionId, galStore);
    return () => {
      mountedRef.current = '';
      stopSync();
      const s2 = galStore.getState();
      s2.setLiveSayOverride(undefined);
      s2.setHidePlayerBubbles(false);
      s2.exitLiveMode();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId]);

  // 本局角色注册为 VN 说话者。
  useEffect(() => {
    const fullRoleNames: string[] = Array.isArray(scriptState?.roles) ? scriptState.roles : [];
    const outlineRoleNames: string[] = Array.isArray(scriptState?.outline?.roles)
      ? scriptState.outline.roles.map((r: any) => String(r?.name || '')).filter(Boolean)
      : [];
    const roleNames = [...new Set([...fullRoleNames, ...outlineRoleNames])];
    if (!sessionId || roleNames.length === 0) return;
    const s = galStore.getState();
    if (!s.liveMode) return;
    const existing = new Set(s.speakers.map(sp => sp.id));
    const add = roleNames
      .filter(r => r && !existing.has(r) && r !== 'player' && r !== 'system' && r !== 'narrator')
      .map(r => ({ ...buildPlaceholderSpeaker(r), color: colorFor(r) }));
    if (add.length > 0) s.setSpeakers([...s.speakers, ...add]);
  }, [sessionId, scriptState?.roles, scriptState?.outline?.roles, galStore]);

  return (
    <GalStoreProvider store={galStore}>
      <ScriptGalChatInner
        sessionId={sessionId}
        playerName={playerName}
        playerKey={playerKey}
        scriptState={scriptState}
      />
    </GalStoreProvider>
  );
}

function ScriptGalChatInner({ sessionId, playerName, playerKey, scriptState }: ScriptGalChatPanelProps) {
  const started = useGalStore(s => s.started);
  const finished = useGalStore(s => s.finished);
  const tick = useGalStore(s => s.tick);
  const livePhase = useGalStore(s => s.livePhase);
  const current = useGalStore(s => s.current);
  const typing = useGalStore(s => s.typing);
  const log = useGalStore(s => s.log);
  const liveQueue = useGalStore(s => s.liveQueue);
  const liveEnqueue = useGalStore(s => s.liveEnqueue);
  const setLiveSayOverride = useGalStore(s => s.setLiveSayOverride);

  const [quotes, setQuotes] = useState<Array<{ key: string; speaker: string; text: string }>>([]);
  const [formalDiscussion, setFormalDiscussion] = useState(false);
  const [pressing, setPressing] = useState(false);
  const [actionMsg, setActionMsg] = useState('');
  const lastNarrationPhaseRef = useRef('');
  const setupIntroSessionRef = useRef('');

  const phase = scriptState?.phase || livePhase || '';
  const isDiscussion = String(phase).toLowerCase() === 'discussion';
  const isSetup = String(phase).toLowerCase() === 'setup';
  const fullRolesReady = Array.isArray(scriptState?.roles) && scriptState.roles.length > 0;
  const openingReady = isSetup && scriptState?.generating !== true && fullRolesReady;

  // 完整剧本已就绪时，准备阶段输入不再走“永久在线公共聊天”，而走 opening responder。
  // 回复仍通过 script_chat SSE 回到同一 Gal 消息流，因此呈现层不需要第二套消息协议。
  useEffect(() => {
    if (!openingReady || !sessionId || !playerName) {
      setLiveSayOverride(undefined);
      return;
    }
    setLiveSayOverride(async (text: string) => {
      const res = await scriptFlowApi.openingSay({
        sessionId,
        player: playerName,
        playerKey: playerKey || undefined,
      }, text);
      if (res?.error) throw new Error(res.error);
    });
    return () => setLiveSayOverride(undefined);
  }, [openingReady, sessionId, playerName, playerKey, setLiveSayOverride]);

  useEffect(() => {
    if (!started || finished) return;
    const t = setInterval(() => tick(2), 25);
    return () => clearInterval(t);
  }, [started, finished, tick]);

  useEffect(() => {
    if (!started) return;
    const p = String(scriptState?.phase || '').toLowerCase();
    if (!p || lastNarrationPhaseRef.current === p) return;
    const text = PHASE_NARRATIONS[p];
    if (!text) return;
    lastNarrationPhaseRef.current = p;
    const seen = [...liveQueue, ...log].slice(-6).some(m => (m as any).text === text);
    if (!seen) liveEnqueue({ kind: 'system', speakerId: 'system', name: '📢 剧本杀', text });
  }, [started, scriptState?.phase, liveEnqueue, liveQueue, log]);

  // 完整剧本生成前仍播放 outline 的本地人物简介；full roles 就绪后不再伪造“AI 自我介绍”，
  // 真正的 AI 开场由后端 ScriptGameFlowService 生成并通过 script_chat SSE 发回。
  useEffect(() => {
    if (!started || String(scriptState?.phase || '').toLowerCase() !== 'setup' || fullRolesReady) return;
    const sid = String(scriptState?.session_id || sessionId || '');
    if (!sid || setupIntroSessionRef.current === sid) return;
    setupIntroSessionRef.current = sid;
    const outline = scriptState?.outline;
    const outlineRoles = Array.isArray(outline?.roles) ? outline.roles : [];
    const intro = String(outline?.storyline || scriptState?.background || '').trim();
    if (intro) liveEnqueue({ kind: 'system', speakerId: 'system', name: '📖 故事旁白', text: intro });
    outlineRoles.slice(0, 8).forEach((r: any) => {
      const name = String(r?.name || '').trim();
      const text = String(r?.intro || r?.summary || r?.background || '').trim();
      if (name && text) liveEnqueue({ kind: 'agent', speakerId: name, name, text: `「${text}」` });
    });
  }, [started, sessionId, scriptState?.phase, scriptState?.session_id, scriptState?.outline,
      scriptState?.background, fullRolesReady, liveEnqueue]);

  const currentAgent = useMemo(() => {
    const cur = current as any;
    if (cur && cur.kind === 'agent') {
      return {
        speaker: String(cur.speakerId || cur.name || ''),
        text: String((typing && typing.full) || cur.text || ''),
      };
    }
    for (let i = log.length - 1; i >= 0; i--) {
      const l = log[i] as any;
      if (l && !l.isPlayer) return { speaker: String(l.speakerId || l.name || ''), text: String(l.text || '') };
    }
    return null;
  }, [current, typing, log]);

  const doPress = async () => {
    if (!currentAgent || !currentAgent.speaker || pressing) return;
    setPressing(true);
    try {
      const res: any = await api.scriptPress(
        playerName || '我',
        currentAgent.speaker,
        undefined,
        playerKey || undefined,
      );
      setActionMsg(res?.ok ? `已质询 ${currentAgent.speaker} 的发言（矛盾点标记）` : (res?.error || '质询失败'));
    } catch (e: any) {
      setActionMsg(`质询失败：${e?.message || '未知错误'}`);
    } finally {
      setPressing(false);
    }
  };

  const addQuote = () => {
    if (!currentAgent || !currentAgent.speaker || !currentAgent.text) return;
    const key = `${currentAgent.speaker}-${currentAgent.text}`;
    setQuotes(prev => (prev.some(q => q.key === key) ? prev : [...prev, { key, speaker: currentAgent.speaker, text: currentAgent.text }]));
    setActionMsg(`已引用 ${currentAgent.speaker} 的发言（反驳弹药 +1）`);
  };

  return (
    <div className="script-gal-chat" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1 }}>
      <ScriptGameInfoBar scriptState={scriptState} />
      <GalChatStage
        scene={scriptState?.name || scriptState?.theme || ''}
        hasPlayer
        foregroundGap={6}
        style={{ flex: 1, minHeight: 0, borderRadius: 10, overflow: 'hidden' }}
        inputSlot={isDiscussion ? (
          <div className="script-gal-discuss-slot" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {currentAgent && currentAgent.speaker && (
              <div className="script-gal-action-bar" style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <span className="script-gal-chip script-gal-chip-phase" title="当前发言">
                  🎤 {currentAgent.speaker}：{(currentAgent.text || '').slice(0, 28)}{(currentAgent.text || '').length > 28 ? '…' : ''}
                </span>
                <button
                  className="btn btn-smallall proto-p-btn"
                  disabled={pressing}
                  onClick={() => void doPress()}
                  title="质询该发言（POST /api/script/press → 服务端矛盾点标记 + 目标角色辩解）"
                >
                  🔍 {pressing ? '质询中…' : '质询'}
                </button>
                <button className="btn btn-smallall proto-p-btn" onClick={addQuote} title="引用该发言（收进下方反驳弹药）">
                  📌 引用
                </button>
                {actionMsg && <span className="script-gal-action-msg" style={{ fontSize: 11, color: 'var(--gal-gold, #e8c15a)' }}>{actionMsg}</span>}
              </div>
            )}
            {quotes.length > 0 && (
              <div className="script-gal-ammo" style={{ display: 'flex', alignItems: 'flex-start', gap: 6, flexWrap: 'wrap', fontSize: 11 }}>
                <span className="script-gal-chip" style={{ fontWeight: 700 }}>📌 反驳弹药 ×{quotes.length}</span>
                {quotes.slice(-4).map(q => (
                  <span key={q.key} className="script-gal-chip" title={q.text} style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {q.speaker}：{q.text}
                  </span>
                ))}
              </div>
            )}
            <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              <button className="btn btn-smallall proto-p-btn" onClick={() => setFormalDiscussion(v => !v)}>
                {formalDiscussion ? '🎭 正式讨论' : '💬 公共聊天'}
              </button>
              <span className="script-gal-action-msg" style={{ fontSize: 11, color: 'var(--gal-dim, #9ba6bf)' }}>
                {formalDiscussion ? '会进入讨论机制并可触发 NPC 回应' : '不影响线索、NPC 指令或剧情状态'}
              </span>
            </div>
            <GalInputArea scriptPublic={!formalDiscussion} />
          </div>
        ) : isSetup ? (
          <div className="script-gal-setup-input">
            <div className="script-gal-setup-hint">
              {openingReady
                ? '🎭 开场交流已开始：这里的发言会触发 AI 角色回应；交流完成后点击上方“确认并开始搜证”。'
                : '💬 完整剧本仍在准备中。此时公共聊天保持开放；角色正式开场会在完整剧本就绪后开始。'}
            </div>
            <GalInputArea scriptPublic />
          </div>
        ) : (
          <div className="script-gal-public-slot" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div style={{ padding: '6px 10px', borderRadius: 8, fontSize: 12, lineHeight: 1.5,
              background: 'rgba(12,19,34,0.6)', border: '1px solid rgba(255,255,255,0.12)', color: 'var(--gal-text, #e8eef9)' }}>
              💬 公共聊天持续开放；{phase ? String(phase).toUpperCase() : '当前'}阶段的游戏行动仍请使用主区面板。
            </div>
            <GalInputArea scriptPublic />
          </div>
        )}
      />
      <ScriptGalSseBridge />
    </div>
  );
}
