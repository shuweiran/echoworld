/**
 * ScriptGalChatPanel.tsx — 剧本杀聊天部件 Gal 化（P-0815-B，方案 B）
 *
 * 需求：把一般模式 gal 聊天界面（立绘 + 打字机对话框 + 输入区）替换到剧本杀模式的
 * ChatPage 中间消息流对话区——右侧 ScriptStatePanel（秘密/搜证/AP/线索/投票/揭晓/DM）
 * 原样保留，仅替换「聊天呈现」；后端零改动、一般模式零回归（script 分支纯增量）。
 *
 * 设计（仿 SimGalChatPanel 范本 P-0813-D/G/K + GalGeneralStage 分层布局）：
 *  - 复用 GalStore live 消息流（liveQueue + typing 打字机 + advance「▼ 点击继续」）；
 *  - 消息源双通道：① script_speech SSE 实时发言（自建 useSSE 桥 → applySseEvent，
 *    GalStore 已消费 human?/AI 发言）；② startLiveSync 3s 轮询 discussion 转录增量
 *    （SSE 丢失窗口兜底，与 SSE 按 (speakerId,text) 去重防双播）；
 *  - 发言：GalInputArea 默认 liveSay 路由 —— liveGameType='script' && livePhase='DISCUSSION'
 *    → api.scriptDiscussionSay（后端已就绪，零改动）；
 *  - 候选区（GalChoicesArea）：isPlayerTurnGate 限定 liveGameType==='general'，
 *    script 模式天然隐藏（[NEEDS CHECK-1] 结论=无需额外禁用，仅输入框可用）；
 *  - 布局：P-0815-F 批3（方向5）抽公共核心 GalChatStage（bg/sprites/foreground 组合），
 *    顶部「对局信息条」ScriptGameInfoBar（连接状态 + 阶段/倒计时 + 角色/秘密/线索统一成一条）；
 *  - 挂载：enterLiveMode(sessionId,{playerName,playerKey}) + setLiveGameType('script')
 *    + setLiveStatus('open') + setHidePlayerBubbles(false)（剧本杀玩家发言应可见，
 *    与一般模式 hidePlayerBubbles=true 相反）；卸载 exitLiveMode 清理。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useGalStore, createGalStore, GalStoreProvider } from './GalStore';
import { GalChatStage } from './GalChatStage';
import { GalInputArea } from './GalChoiceBar';
import { ScriptGameInfoBar } from './ScriptGameInfoBar';
import { buildPlaceholderSpeaker } from './galDemoData';
// P-0816-I（讨论页 VN 化 B.1）：角色色名字铭牌 —— 与左栏/在场条同一 colorFor 调色板
import { colorFor } from '../components/ChatPage/chatUtils';
import { startLiveSync } from './galSseAdapter';
import { useSSE } from '../api/useSSE';
import { api } from '../api/client';
import './gal.css';
import './galGeneral.css';

/** P-0818-D：各阶段入队一次的对局旁白（对话框不空白，替代原 script_phase 自动系统行） */
const PHASE_NARRATIONS: Record<string, string> = {
  setup: '🎭 剧本已创建，等待生成完整剧本…（完成后自动进入搜证阶段）',
  investigation: '🔍 搜证阶段：点击地点卡片调查线索（消耗行动点），线索进入右侧证据库',
  discussion: '💬 讨论阶段：自由发言 · 质询矛盾 · 出示证据',
  vote: '🗳️ 投票阶段：指认你认为的凶手（可在投票面板弃票）',
  reveal: '🎬 揭晓时刻：投票结果揭晓，真相大白！',
  ended: '🏁 对局结束：感谢游玩，可返回剧本选择或重新开局',
};

export interface ScriptGalChatPanelProps {
  /** 剧本杀对局 session_id（ChatPage 从 store.scriptSessionId 透传） */
  sessionId: string;
  /** 当前玩家名（发言身份；store.currentPlayer） */
  playerName?: string;
  /** 剧本杀 roleKey（身份校验；store.scriptRoleKey，init 响应回写） */
  playerKey?: string;
  /** 剧本杀对局状态（阶段/秘密/线索旁路展示用；可选——缺失时仅聊天区） */
  scriptState?: any;
}

/** SSE 桥：订阅 /api/events（会话定向）→ GalStore.applySseEvent（script_speech 等全量消费）
 *  响应式读取 liveSessionId：父组件挂载 effect（enterLiveMode）执行后本组件自动重连到该会话。 */
function ScriptGalSseBridge() {
  const sessionId = useGalStore(s => s.liveSessionId);
  const applySseEvent = useGalStore(s => s.applySseEvent);
  const bumpLiveEvent = useGalStore(s => s.bumpLiveEvent);
  const setLiveStatus = useGalStore(s => s.setLiveStatus);
  const onEvent = useCallback((evt: string, data: any) => {
    bumpLiveEvent();
    applySseEvent(evt, data);
  }, [applySseEvent, bumpLiveEvent]);
  const onStatus = useCallback((st: any) => setLiveStatus(st), [setLiveStatus]);
  useSSE(onEvent, sessionId || undefined, onStatus);
  return null;
}

export function ScriptGalChatPanel({ sessionId, playerName, playerKey, scriptState }: ScriptGalChatPanelProps) {
  /** 最近一次有效挂载的 sessionId（防 sessionId 空窗期重挂载竞态） */
  const mountedRef = useRef('');
  /** P-0815-F 批3（方向5）：本面板自建 GalStore 实例（per-instance，消除跨面板互踩）；
   *  与 ScriptGameInfoBar/SSE 桥同实例（GalStoreProvider 注入），卸载时整实例随面板丢弃。 */
  const galStore = useMemo(() => createGalStore(), []);

  // ── 挂载：进入 GalStore live 模式（剧本杀双通道同步；卸载退出清理） ──
  useEffect(() => {
    if (!sessionId || mountedRef.current === sessionId) return;
    mountedRef.current = sessionId;
    const st = galStore.getState();
    st.enterLiveMode(sessionId, { playerName, playerKey });
    // P-0818-F：进入对局后立即拉取 AI 形象状态（注册后端角色名 → ID 映射，保证局内立绘可查）
    void st.refreshImageStatus();
    // 剧本杀模式：置类型/状态使输入框门控生效；玩家发言可见（hidePlayerBubbles=false）
    const phase = scriptState?.phase || '';
    const title = scriptState?.name || scriptState?.theme || '';
    st.setLiveGameType('script', phase, title);
    st.setLiveStatus('open');
    st.setHidePlayerBubbles(false);
    // 双通道同步：3s 轮询 discussion 转录增量（SSE 丢失窗口兜底，与 script_speech 去重）
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

  // ── P-0816-I（讨论页 VN 化 B.1）：本局角色注册为 VN 说话者 ──
  // 角色色名字铭牌 + 占位立绘头像（复用 buildPlaceholderSpeaker 占位资产 + colorFor 角色色）；
  // 独立 effect 幂等（已注册跳过），roles 随轮询更新时补注册；不动消息数据流。
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

/**
 * 面板内部（GalStoreProvider 子树内）：所有 useGalStore 读本面板自建实例。
 * P-0818-D：此前打字机定时器/livePhase 等 hook 写在 Provider 外层，读的是默认单例
 * （started 恒 false）→ 消息永远停在 0 字符不逐字显示 =「准备环节没有局内聊天 /
 * 输出语句错乱」根因之一。拆入子树后与 GalDialogBox/GalInputArea 同实例同状态。
 */
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

  // P-0818-D：讨论「当前发言」质询/引用（反驳弹药本地收集；承接原 ScriptDiscussionPanel 逐条按钮语义）
  const [quotes, setQuotes] = useState<Array<{ key: string; speaker: string; text: string }>>([]);
  const [pressing, setPressing] = useState(false);
  const [actionMsg, setActionMsg] = useState('');
  const lastNarrationPhaseRef = useRef('');
  const setupIntroSessionRef = useRef('');

  // ── 打字机定时器（与 GalGeneralView 同款：每 25ms 推进 2 字符；本实例状态） ──
  useEffect(() => {
    if (!started || finished) return;
    const t = setInterval(() => tick(2), 25);
    return () => clearInterval(t);
  }, [started, finished, tick]);

  // ── P-0818-D：阶段旁白（每个阶段入队一次，替代原 script_phase 自动系统行） ──
  useEffect(() => {
    // live 模式就绪后才入队（enterLiveMode 会清空队列——此前旁白在挂载 effect 前入队被清掉）
    if (!started) return;
    const phase = String(scriptState?.phase || '').toLowerCase();
    if (!phase || lastNarrationPhaseRef.current === phase) return;
    const text = PHASE_NARRATIONS[phase];
    if (!text) return;
    lastNarrationPhaseRef.current = phase;
    // 防重复：同文本已在队尾/log 尾（如重挂载后首阶段）则跳过
    const seen = [...liveQueue, ...log]
      .slice(-6)
      .some(m => (m as any).text === text);
    if (!seen) {
      liveEnqueue({ kind: 'system', speakerId: 'system', name: '📢 剧本杀', text });
    }
  }, [started, scriptState?.phase, liveEnqueue, liveQueue, log]);

  // P-0819-A：准备阶段不是空白等待页。概略剧本已包含角色简介，先用 Gal 舞台
  // 播放一轮「人物自我介绍/背景旁白」，让玩家在完整剧本生成期间理解人物；不把
  // setup 伪装成可发言阶段，也不向后端发送非法 discussion_say。
  useEffect(() => {
    if (!started || String(scriptState?.phase || '').toLowerCase() !== 'setup') return;
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
  }, [started, sessionId, scriptState?.phase, scriptState?.session_id, scriptState?.outline, scriptState?.background, liveEnqueue]);

  // ── P-0818-D：当前发言（讨论操作对象）= 正在播放的 AI 消息；播放完回退最近一条 AI log ──
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
      if (l && !l.isPlayer) {
        return { speaker: String(l.speakerId || l.name || ''), text: String(l.text || '') };
      }
    }
    return null;
  }, [current, typing, log]);

  // 质询：服务端 press（无 message_id → 目标角色最近一条发言，兼容讨论组实时历史）
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

  // 引用：收进反驳弹药（沿用原 ScriptDiscussionPanel 纯前端语义）
  const addQuote = () => {
    if (!currentAgent || !currentAgent.speaker || !currentAgent.text) return;
    const key = `${currentAgent.speaker}-${currentAgent.text}`;
    setQuotes(prev => (prev.some(q => q.key === key) ? prev : [...prev, { key, speaker: currentAgent.speaker, text: currentAgent.text }]));
    setActionMsg(`已引用 ${currentAgent.speaker} 的发言（反驳弹药 +1）`);
  };

  // ── 对局信息条 + GalChatStage 公共核心（立绘舞台/对话框/输入区） ──
  const phase = scriptState?.phase || livePhase || '';
  const isDiscussion = String(phase).toLowerCase() === 'discussion';
  const isSetup = String(phase).toLowerCase() === 'setup';

  return (
    <div className="script-gal-chat" style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1 }}>
      {/* 对局信息条（P-0815-F 批3 方向5：旁路条 + 阶段横幅统一为一条） */}
      <ScriptGameInfoBar scriptState={scriptState} />
      {/* Gal 分层舞台：背景 z0 → 立绘 z1 → 前景 z2（对话框 + 输入区） */}
      <GalChatStage
        scene={scriptState?.name || scriptState?.theme || ''}
        hasPlayer
        foregroundGap={6}
        style={{ flex: 1, minHeight: 0, borderRadius: 10, overflow: 'hidden' }}
        inputSlot={isDiscussion ? (
          <div className="script-gal-discuss-slot" style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {/* 当前发言操作条：质询 / 引用（承接原讨论流逐条按钮） */}
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
                <button
                  className="btn btn-smallall proto-p-btn"
                  onClick={addQuote}
                  title="引用该发言（收进下方反驳弹药）"
                >
                  📌 引用
                </button>
                {actionMsg && <span className="script-gal-action-msg" style={{ fontSize: 11, color: 'var(--gal-gold, #e8c15a)' }}>{actionMsg}</span>}
              </div>
            )}
            {/* 反驳弹药（弹丸论破言弹；引用按钮收集，纯前端本地） */}
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
            <GalInputArea />
          </div>
        ) : isSetup ? (
          <div className="script-gal-setup-input">
            <div className="script-gal-setup-hint">
              💬 后台正在准备完整剧本，你可以先和在场角色交流；消息会在剧本就绪后带入两轮收尾讨论。
            </div>
            <GalInputArea />
          </div>
        ) : (
          <div style={{ padding: '8px 10px', borderRadius: 8, fontSize: 12, lineHeight: 1.5,
            background: 'rgba(12,19,34,0.6)', border: '1px solid rgba(255,255,255,0.12)', color: 'var(--gal-text, #e8eef9)' }}>
            🔒 当前阶段不可发言（{phase ? String(phase).toUpperCase() : '准备'}阶段）—— 搜证 / 投票请在主区对应面板操作
          </div>
        )}
      />
      {/* SSE 桥（script_speech 实时发言 → GalStore） */}
      <ScriptGalSseBridge />
    </div>
  );
}
