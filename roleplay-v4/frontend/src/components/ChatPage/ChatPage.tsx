/**
 * ChatPage.tsx — 对局主界面（阶段① P-0809-A 重构）
 *
 * 拆分前：单文件 1875 行，free/director/werewolf/script 四模式混在一起。
 * 拆分后：本文件为「编排壳」——持有对局级状态（剧本杀搜证/投票/转交、2D 面板开关）、
 * 3s 轮询兜底、SSE 激活（useGameSse）、剧本杀动作处理器；渲染由子组件承担：
 *
 *   ChatTopbar        —— 顶栏（模式抽屉开关 + ⚙️ 设置 + 🧭 逻辑链入口占位）
 *   ChatLeftPanel     —— 左侧角色面板（添加/移除/语音/过滤）
 *   ChatRightPanel    —— 右侧操作面板（狼人杀/剧本杀状态列）
 *   ChatMessageFlow   —— 中间消息流（2D 面板/公告横幅/阶段横幅/在场条/消息/输入区）
 *   ChatDrawers       —— 右侧抽屉集合（导演/历史/DM/私聊/美术/逻辑链）
 *   useGameSse        —— SSE 事件桥（对局状态/公告横幅/流式打字机）
 *
 * 状态管理沿用 Zustand（useAppStore），未引入新依赖。
 */
import { useEffect, useMemo, useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { useDemoStore } from '../../demo2/store';
import { api } from '../../api/client';
import { ChatTopbar, type TopbarDrawers } from './ChatTopbar';
import { ChatLeftPanel } from './ChatLeftPanel';
import { ChatRightPanel, type ScriptPanelHandlers } from './ChatRightPanel';
import { ChatMessageFlow } from './ChatMessageFlow';
import { ChatDrawers } from './ChatDrawers';
import { useGameSse } from './useGameSse';
import { normalizePhase } from './chatUtils';
// 阶段 D（P-0817-E）：scriptPhaseThemeClass 已抽至共享层 components/ui/PhaseBadge.tsx
import { scriptPhaseThemeClass } from '../ui/PhaseBadge';
import type { WerewolfPhase } from '../../types';
// P-0816-H（UI 重设计阶段一）：三栏布局渐进嵌入
import { UI_PROTO_V2_ENABLED } from '../../uiProtoV2';
import { useCollapsibleSidebars } from '../ui/useCollapsibleSidebars';
import { ScriptLeftPanel } from './ScriptLeftPanel';
// P-0816-M（对局页按原型重构）：精简顶栏 / 右栏四 Tab / 兜底状态面板抽屉
import { ScriptProtoTopbar } from './ScriptProtoTopbar';
import { ScriptProtoRightPanel } from './ScriptProtoRightPanel';
import { ScriptStatePanel } from './script/ScriptStatePanel';

export function ChatPage() {
  const store = useAppStore();

  /* ── 抽屉开关（顶栏 + 抽屉集合共享） ─────────── */
  const [showDirector, setShowDirector] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [showDm, setShowDm] = useState(false);
  const [showPrivate, setShowPrivate] = useState(false);
  const [showImages, setShowImages] = useState(false);
  const [showChain, setShowChain] = useState(false);
  // P-0816-M：兜底状态面板抽屉（ScriptStatePanel 保留为兜底，决策 U5；⚙️ 菜单「状态面板（兜底）」打开）
  const [showStatePanel, setShowStatePanel] = useState(false);
  const drawers: TopbarDrawers = useMemo(() => ({
    showDirector, setShowDirector,
    showHistory, setShowHistory,
    showDm, setShowDm,
    showPrivate, setShowPrivate,
    showImages, setShowImages,
    showChain, setShowChain,
  }), [showDirector, showHistory, showDm, showPrivate, showImages, showChain]);

  /* ── SSE 激活（阶段① 关键修复，见 useGameSse.ts 头注释）──── */
  useGameSse();

  /* ── P-0816-H（UI 重设计阶段一）：剧本杀三栏布局渐进嵌入（决策 U11/D7）──── */
  const protoV2 = UI_PROTO_V2_ENABLED && store.mode === 'script';
  // 折叠状态 localStorage 按页面分存（useCollapsibleSidebars，对齐原型 V2 交互：左 rail / 右抽屉+FAB）
  const sidebars = useCollapsibleSidebars('chat');

  /* ── P-0816-H：投票进度 + 目标 HUD 轮询（SSE 优先 + 3s 轮询兜底，D1；仅 ui-proto-v2 剧本杀对局） ── */
  useEffect(() => {
    if (!protoV2) return;
    let alive = true;
    const poll = async () => {
      try {
        const vs = await api.scriptVoteStatus(store.currentPlayer, store.scriptRoleKey);
        if (alive && vs && typeof vs === 'object') store.setScriptVoteProgress(vs);
        const g = await api.scriptGoal(store.currentPlayer, store.scriptRoleKey);
        if (alive && g && typeof g === 'object') store.setScriptGoal(g);
      } catch { /* 服务未就绪时忽略 */ }
    };
    poll();
    const t = setInterval(poll, 3000);
    return () => { alive = false; clearInterval(t); };
  }, [protoV2, store.currentPlayer]);

  /* ── 剧本杀对局状态（SSE 优先 + 3s 轮询兜底） ── */
  const scriptState = store.scriptState;
  const scriptReveal = store.scriptReveal;
  const [scriptClues, setScriptClues] = useState<any[]>([]);
  const [scriptPublicClues, setScriptPublicClues] = useState<any[]>([]);
  const [scriptVoteTarget, setScriptVoteTarget] = useState('');
  const [scriptSimulation, setScriptSimulation] = useState<any>(null);
  const [scriptBusy, setScriptBusy] = useState(false);
  const [scriptSearchMsg, setScriptSearchMsg] = useState('');
  const [scriptActionMsg, setScriptActionMsg] = useState('');
  const [transferTargets, setTransferTargets] = useState<Record<string, string>>({});

  // P0-3：内嵌 2D 模拟面板（单页不双开）——角色列表懒初始化一次（避免每渲染重建导致 Phaser 重挂载）
  const [simChars] = useState<Array<{ name: string; persona: string; voice: string; background: string }>>(() => {
    const st = useAppStore.getState();
    return st.agents.map(name => {
      const ch = st.characters.find((c: any) => c.name === name);
      return ch
        ? { name: ch.name, persona: ch.persona || '', voice: ch.voice || '', background: ch.background || '' }
        : { name, persona: name + '，一个角色', voice: '', background: '' };
    });
  });
  const [showSimPanel, setShowSimPanel] = useState(false);
  // P-0815-F（主人 08-16 反馈「只保留中间聊天区，其他都隐藏整理」）：剧本杀沉浸模式——
  // 默认只渲染中间 gal 聊天区；右侧操作面板（搜证/投票）收纳为右下悬浮按钮展开
  const [scriptPanelOpen, setScriptPanelOpen] = useState(false);
  // P-0815-F 沉浸模式交互：Esc 关闭操作面板（面板打开时生效）
  useEffect(() => {
    if (!scriptPanelOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setScriptPanelOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [scriptPanelOpen]);
  // C-1：2D 面板不再用 localStorage 自动展开——主入口合并到场景页「进入 2D 模拟」按钮；
  // P-0804-H 续：一般模式 + 当前场景绑定地图 → 进入游戏自动展开地图面板
  useEffect(() => {
    if (store.mode !== 'script' && store.currentSceneMap) setShowSimPanel(true);
  }, [store.mode, store.currentSceneMap]);
  const toggleSimPanel = () => setShowSimPanel(v => !v);

  const refreshScript = async () => {
    try {
      const st = await api.scriptStatus(store.currentPlayer, store.scriptRoleKey);
      store.setScriptState(st);
      // P-0802-J：轮询回写对局 session_id（SSE 会话定向连接；重连多局场景按对局定位。
      if (st?.session_id) store.setScriptSessionId(st.session_id);
      if (st?.simulation_started) {
        try { setScriptSimulation(await api.simulationState()); } catch { /* ignore */ }
      }
    } catch { /* 服务未就绪时忽略 */ }
  };

  /* ── 剧本杀 3s 轮询（SSE 兜底） ── */
  useEffect(() => {
    if (store.mode !== 'script') {
      store.setScriptState(null);
      store.setScriptReveal(null);
      store.setScriptVoteProgress(null);
      store.setScriptGoal(null);
      store.clearScriptSpeechTurns();
      setScriptClues([]);
      setScriptPublicClues([]);
      setScriptSimulation(null);
      setScriptSearchMsg('');
      setTransferTargets({});
      return;
    }
    let alive = true;
    const poll = async () => {
      try {
        const st = await api.scriptStatus(store.currentPlayer, store.scriptRoleKey);
        if (alive) {
          store.setScriptState(st);
          if (st?.session_id) store.setScriptSessionId(st.session_id);
          if (st?.simulation_started) {
            try { setScriptSimulation(await api.simulationState()); } catch { /* ignore */ }
          }
        }
      } catch { /* ignore */ }
    };
    poll();
    const t = setInterval(poll, 3000);
    return () => { alive = false; clearInterval(t); };
  }, [store.mode, store.currentPlayer]);

  /* ── P-0816-M：离开讨论阶段时清空实时发言缓冲（阶段切换/非剧本杀） ── */
  useEffect(() => {
    const phase = String(store.scriptPhase || store.scriptState?.phase || '');
    if (store.mode !== 'script' || (phase && phase !== 'discussion')) {
      store.clearScriptSpeechTurns();
    }
  }, [store.mode, store.scriptPhase, store.scriptState?.phase]);

  /* ── 狼人杀 3s 轮询（SSE 兜底） ── */
  useEffect(() => {
    if (store.mode !== 'werewolf' || !store.currentPlayer || !store.werewolfSessionId || !store.werewolfRoleKey) return;
    let aliveFlag = true;
    const wwRoleCn: Record<string, string> = { werewolf: '狼人', wolf: '狼人', seer: '预言家', witch: '女巫', hunter: '猎人', villager: '平民' };
    const poll = async () => {
      try {
        const st = await api.werewolfStatus(store.werewolfSessionId, store.currentPlayer, store.werewolfRoleKey);
        if (!aliveFlag || !st || typeof st !== 'object') return;
        if (st.session_id) store.setWerewolfSessionId(st.session_id);
        if (st.phase) store.setWerewolfPhase(normalizePhase(st.phase) as WerewolfPhase, st.round);
        if (st.your_role) store.setWerewolfMyRole(wwRoleCn[st.your_role] || st.your_role);
        if (Array.isArray(st.alive)) {
          store.setWerewolfAlive(st.alive);
          const players: { name: string; role: string; alive: boolean; roleRevealed: boolean }[] =
            (st.alive as string[]).map(n => ({ name: n, role: '', alive: true, roleRevealed: false }));
          if (Array.isArray(st.eliminated)) {
            (st.eliminated as any[]).forEach((e: any) => {
              if (e && e.name) players.push({ name: e.name, role: '', alive: false, roleRevealed: false });
            });
          }
          store.setWerewolfPlayers(players);
        }
        if (st.visible && typeof st.visible === 'object') store.setWerewolfVisible(st.visible);
        if (Array.isArray(st.discussion)) store.setWerewolfDiscussion(st.discussion);
        if (st.role_key) store.setWerewolfRoleKey(String(st.role_key));
        if (st.witch_victim) store.setWerewolfWitchVictim(String(st.witch_victim));
        if (st.winner) store.setWerewolfWinner(st.winner);
      } catch { /* 服务未就绪时忽略 */ }
    };
    poll();
    const t = setInterval(poll, 3000);
    return () => { aliveFlag = false; clearInterval(t); };
  }, [store.mode, store.currentPlayer, store.werewolfSessionId, store.werewolfRoleKey]);

  /* ── 剧本杀动作处理器（面板按钮 → REST → 立即刷新，不等轮询） ── */
  const doScriptSearch = async (location: string) => {
    setScriptBusy(true);
    try {
      const res = await api.scriptSearch(store.currentPlayer, location, store.scriptRoleKey);
      setScriptClues(res.clues || []);
      setScriptPublicClues(res.public_clues || []);
      setScriptSearchMsg(res.error ? `⚠️ ${res.error}` : (res.result || ''));
      await refreshScript();
    } catch { /* ignore */ }
    setScriptBusy(false);
  };
  const doScriptTransferClue = async (clueId: string, target: string) => {
    if (!target) return;
    setScriptBusy(true);
    try {
      const res = await api.scriptTransferClue(store.currentPlayer, target, clueId, store.scriptRoleKey);
      setScriptSearchMsg(res.error ? `⚠️ ${res.error}` : (res.result || `已将线索转交给 ${target}`));
      setTransferTargets({});
      await refreshScript();
    } catch { /* ignore */ }
    setScriptBusy(false);
  };
  /** P-0816-T（阶段三 API-9，决策 C8）：出示证据到对话流 —— 右栏线索卡「📎 出示」真实端点。
   *  成功：toast「已出示」+ 对话流出现「🃏 出示」系统行（SSE script_present 同步 + 本地乐观插入兜底，
   *  mergeTranscript 按 (speaker,message) 去重防双显）；失败 toast 后端 error；幂等重复出示提示已出示。 */
  const doScriptPresent = async (clueId: string) => {
    setScriptBusy(true);
    try {
      const res = await api.scriptPresent(store.currentPlayer, clueId, store.scriptRoleKey || undefined);
      if (res?.error) {
        setScriptSearchMsg(`⚠️ 出示失败：${res.error}`);
      } else if (res?.already) {
        setScriptSearchMsg(`ℹ️ ${res.message || '该线索已出示过'}`);
      } else {
        const title = res?.presented?.title ? ` ${res.presented.title}` : '';
        // 本地乐观插入（SSE 到达时按同 (speaker,message) 键去重；轮询 status.discussion 双通道兜底）
        store.addScriptSpeechTurn({ speaker: 'system', message: `🃏 出示：${clueId}${title}` });
        setScriptSearchMsg(`🃏 已出示 ${clueId}${title}（对话流已同步）`);
        await refreshScript();
      }
    } catch (e: any) {
      setScriptSearchMsg(`⚠️ 出示失败：${e?.message || '未知错误'}`);
    }
    setScriptBusy(false);
  };
  const doScriptStartDiscussion = async () => {
    setScriptBusy(true);
    try {
      const res = await api.scriptStartDiscussion(store.currentPlayer, store.scriptRoleKey);
      if (res?.simulation_started) {
        try { setScriptSimulation(await api.simulationState()); } catch { /* ignore */ }
      }
      if (res?.error) setScriptSearchMsg(`⚠️ ${res.error}`);
      await refreshScript();
    } catch (e: any) { setScriptSearchMsg(`⚠️ 进入讨论失败：${e?.message || '未知错误'}`); }
    setScriptBusy(false);
  };
  const doScriptStartVoting = async () => {
    setScriptBusy(true);
    try {
      const res = await api.scriptStartVoting(store.currentPlayer, store.scriptRoleKey);
      if (res?.error) setScriptSearchMsg(`⚠️ ${res.error}`);
      await refreshScript();
    } catch (e: any) { setScriptSearchMsg(`⚠️ 进入投票失败：${e?.message || '未知错误'}`); }
    setScriptBusy(false);
  };
  const doScriptVote = async () => {
    if (!scriptVoteTarget) return;
    setScriptBusy(true);
    try {
      const identity = await api.scriptStatus(store.currentPlayer);
      const playerKey = identity?.role_key ? String(identity.role_key) : store.scriptRoleKey;
      if (playerKey && playerKey !== store.scriptRoleKey) store.setScriptRoleKey(playerKey);
      await api.scriptVote(store.currentPlayer, scriptVoteTarget, playerKey);
      setScriptVoteTarget('');
      await refreshScript();
    } catch { /* ignore */ }
    setScriptBusy(false);
  };
  /** P-0816-H：投票页候选卡直接投票（ScriptVotePanel，投指定嫌疑人） */
  const doScriptVoteFor = async (suspect: string) => {
    setScriptBusy(true);
    try {
      const identity = await api.scriptStatus(store.currentPlayer);
      const playerKey = identity?.role_key ? String(identity.role_key) : store.scriptRoleKey;
      if (playerKey && playerKey !== store.scriptRoleKey) store.setScriptRoleKey(playerKey);
      await api.scriptVote(store.currentPlayer, suspect, playerKey);
      setScriptVoteTarget('');
      // P-0816-T（阶段三 U3）：记录本人投票 —— script_reveal 到达后与 most_voted 比对扣信任度（前端近似）
      store.setScriptMyVote(suspect);
      await refreshScript();
    } catch { /* ignore */ }
    setScriptBusy(false);
  };
  /** P-0816-H：弃票（POST /api/script/vote abstain:true，决策 U8） */
  const doScriptAbstain = async () => {
    setScriptBusy(true);
    try {
      const identity = await api.scriptStatus(store.currentPlayer);
      const playerKey = identity?.role_key ? String(identity.role_key) : store.scriptRoleKey;
      if (playerKey && playerKey !== store.scriptRoleKey) store.setScriptRoleKey(playerKey);
      await api.scriptVoteAbstain(store.currentPlayer, playerKey);
      await refreshScript();
    } catch { /* ignore */ }
    setScriptBusy(false);
  };
  const doScriptResolve = async () => {
    setScriptBusy(true);
    setScriptActionMsg('');
    try {
      const identity = await api.scriptStatus(store.currentPlayer);
      const playerKey = identity?.role_key ? String(identity.role_key) : store.scriptRoleKey;
      if (!playerKey) throw new Error('未取得当前玩家令牌，请刷新对局后重试');
      if (playerKey && playerKey !== store.scriptRoleKey) store.setScriptRoleKey(playerKey);
      const res = await api.scriptResolve(store.currentPlayer, playerKey);
      if (res?.error) throw new Error(String(res.error));
      if (res?.phase && res.phase !== 'reveal' && res.phase !== 'ended') {
        setScriptActionMsg('⏳ 揭晓尚未完成：' + (res.message || '等待主持人审批或服务端推进') + '。请稍后重试。');
        await refreshScript();
        setScriptBusy(false);
        return;
      }
      store.setScriptReveal(res);
      setScriptActionMsg('');
      await refreshScript();
    } catch (e: any) {
      setScriptActionMsg(`⚠️ 揭晓失败：${e?.message || '网络或权限错误'}。可以重试。`);
    }
    setScriptBusy(false);
  };
  const doScriptFinish = async () => {
    setScriptBusy(true);
    setScriptActionMsg('');
    try {
      const identity = await api.scriptStatus(store.currentPlayer);
      const playerKey = identity?.role_key ? String(identity.role_key) : store.scriptRoleKey;
      if (!playerKey) throw new Error('未取得当前玩家令牌，请刷新对局后重试');
      if (playerKey && playerKey !== store.scriptRoleKey) store.setScriptRoleKey(playerKey);
      const res = await api.scriptFinish(store.currentPlayer, playerKey);
      if (res?.error) throw new Error(String(res.error));
      if (res?.phase && res.phase !== 'ended') {
        setScriptActionMsg('⏳ 对局尚未收尾：' + (res.message || '服务端仍在揭晓') + '。请稍后重试。');
        await refreshScript();
        setScriptBusy(false);
        return;
      }
      setScriptActionMsg('');
      await refreshScript();
    } catch (e: any) {
      setScriptActionMsg(`⚠️ 结束对局失败：${e?.message || '网络或权限错误'}。可以重试。`);
    }
    setScriptBusy(false);
  };
  const doScriptRestart = async () => {
    setScriptBusy(true);
    try {
      const res = await api.scriptRestart(store.currentPlayer, store.scriptRoleKey);
      store.setScriptReveal(null);
      setScriptClues([]);
      setScriptPublicClues([]);
      setScriptVoteTarget('');
      setScriptSearchMsg('');
      setTransferTargets({});
      // P-0816-T（阶段三 U3）：新局重置信任度近似（初始 5/5）与本人投票记录
      store.setScriptTrust(5);
      store.setScriptMyVote('');
      if (res?.session_id) store.setScriptSessionId(res.session_id);
      if (res?.role_key) store.setScriptRoleKey(String(res.role_key));
      store.setScriptState(res);
      await refreshScript();
    } catch (e: any) {
      setScriptActionMsg(`⚠️ 重新开局失败：${e?.message || '身份凭据无效或网络错误'}。请重新进入对局后再试。`);
    }
    setScriptBusy(false);
  };
  const doScriptLeave = async () => {
    if (!confirm('退出后你的角色将转为 AI 托管（投票权作废），确定退出吗？')) return;
    setScriptBusy(true);
    try { await api.scriptLeave(store.currentPlayer); await refreshScript(); } catch { /* ignore */ }
    setScriptBusy(false);
  };
  /** P-0815-F（方向1，根因 A）：SETUP 阶段手动生成完整剧本（POST /api/script/generate_full，后端异步）——
   *  触发后立即刷新状态（generating=true 可见）；生成中由 script_status SSE/轮询推 generating=true，按钮禁用。 */
  const doScriptGenerateFull = async () => {
    setScriptBusy(true);
    try {
      await api.scriptGenerateFull(store.scriptSessionId);
      await refreshScript();
    } catch { /* ignore */ }
    setScriptBusy(false);
  };

  const rollback = async (round: number) => {
    if (round > 0 && confirm(`回滚到第 ${round} 轮完成后的状态？`)) await store.rollback(round);
  };

  /* ── 剧本杀面板处理器透传给右侧操作面板 ── */
  const scriptHandlers: ScriptPanelHandlers = useMemo(() => ({
    scriptState,
    currentPlayer: store.currentPlayer,
    scriptClues,
    scriptPublicClues,
    scriptReveal,
    scriptVoteTarget,
    setScriptVoteTarget,
    scriptSimulation,
    scriptBusy,
    scriptSearchMsg,
    transferTargets,
    setTransferTargets,
    onSearch: doScriptSearch,
    onTransferClue: doScriptTransferClue,
    onStartDiscussion: doScriptStartDiscussion,
    onStartVoting: doScriptStartVoting,
    onVote: doScriptVote,
    onVoteFor: doScriptVoteFor,
    onAbstain: doScriptAbstain,
    onResolve: doScriptResolve,
    onFinish: doScriptFinish,
    onRestart: doScriptRestart,
    onLeave: doScriptLeave,
    onGenerateFull: doScriptGenerateFull,
    onOpen2D: toggleSimPanel,
    // P-0815-F 批2（方向4）：原 onBackToScene 写 appStore.goToView('scene') 死字段（App2 路由不消费），
    // 按钮点击无任何效果 —— 改接 demo2 路由 go('scripts') 真实返回剧本选择页（ENDED 面板「📋 回到剧本选择」）
    onBackToScene: () => useDemoStore.getState().go('scripts'),
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }), [scriptState, scriptReveal, scriptClues, scriptPublicClues, scriptVoteTarget,
    scriptSimulation, scriptBusy, scriptSearchMsg, transferTargets, store.currentPlayer]);

  return (
    <div className="app-shell">
      {/* P-0816-M：proto-v2 精简顶栏（剧本名+轮次+阶段徽章+目标HUD+⚙️菜单收纳 9 旧按钮+❓）；旧顶栏保留非 proto 路径 */}
      {protoV2 ? (
        <ScriptProtoTopbar
          drawers={drawers}
          scriptState={scriptState}
          onLeave={() => doScriptLeave()}
          onOpenStatePanel={() => setShowStatePanel(true)}
          onOpen2D={toggleSimPanel}
          onBackToScene={() => useDemoStore.getState().go('scripts')}
        />
      ) : (
        <ChatTopbar drawers={drawers} />
      )}
      {scriptActionMsg && store.mode === 'script' && (
        <div role="status" style={{ position: 'fixed', zIndex: 120, left: '50%', top: 68, transform: 'translateX(-50%)', maxWidth: 'min(720px, calc(100vw - 32px))', padding: '9px 14px', borderRadius: 8, color: '#ffe8b0', background: 'rgba(63, 39, 12, .96)', border: '1px solid rgba(255, 194, 92, .55)', boxShadow: '0 8px 28px rgba(0,0,0,.35)', fontSize: 13 }}>
          {scriptActionMsg}
        </div>
      )}
      {/* P-0816-H：ui-proto-v2 三栏布局（左 240⇄56 / 中 flex / 右 280⇄抽屉+FAB，折叠状态 localStorage 记忆）
          P-0816-M：phase-<阶段> 类名驱动阶段色 CSS 变量（搜证青蓝/讨论暖橙/投票红紫） */}
      <div className={`workspace${store.mode === 'werewolf' ? ' werewolf-mode' : ''}${store.mode === 'script' ? ' script-mode' : ''}${protoV2 ? ' proto-v2' : ''}${protoV2 ? ` ${scriptPhaseThemeClass(scriptState?.phase || store.scriptPhase)}` : ''}${sidebars.leftCollapsed ? ' l-collapsed' : ''}${sidebars.rightCollapsed ? ' r-collapsed' : ''}${sidebars.drawerOpen ? ' r-drawer' : ''}`}>
        {protoV2 ? (
          /* ui-proto-v2：左栏（剧本杀：阶段进度 + 角色列表，240px ⇄ 56px icon rail） */
          <ScriptLeftPanel
            scriptState={scriptState}
            collapsed={sidebars.leftCollapsed}
            onToggle={sidebars.toggleLeft}
          />
        ) : (
          /* 旧布局：沉浸模式剧本杀默认不渲染左面板（角色列表对局内无操作价值） */
          store.mode !== 'script' && <ChatLeftPanel />
        )}
        {/* ui-proto-v2：右栏常驻 280px（» 收起 → FAB → 抽屉弹回）；P-0816-M 改为四 Tab（线索/逻辑链/角色库/历史） */}
        {protoV2 ? (
          <>
            {/* 右栏：同一实例常驻（280px）；» 收起 → FAB；FAB → 抽屉弹回（r-drawer，对齐原型 floatBtn） */}
            <div className="proto-right">
              <div className="proto-right-head">
                <span>📋 操作面板</span>
                <button
                  className="btn btn-smallall proto-side-toggle"
                  onClick={() => { if (sidebars.drawerOpen) sidebars.closeDrawer(); else sidebars.toggleRight(); }}
                  title={sidebars.rightCollapsed
                    ? (sidebars.drawerOpen ? '收起为右下角悬浮按钮' : '展开右栏（280px）')
                    : '» 收起右栏（右下角悬浮按钮唤出）'}
                >»</button>
              </div>
              {/* P-0816-M：右栏四 Tab（线索库检索+出示/转交 / 逻辑链矩阵 / 角色库 / 历史） */}
              <ScriptProtoRightPanel
                scriptState={scriptState}
                currentPlayer={store.currentPlayer}
                busy={scriptBusy}
                transferTargets={transferTargets}
                setTransferTargets={setTransferTargets}
                onTransferClue={doScriptTransferClue}
                onPresentClue={doScriptPresent}
              />
            </div>
            {/* 右栏收起 → 右下角悬浮按钮（抽屉模式，对齐原型 floatBtn） */}
            {sidebars.rightCollapsed && !sidebars.drawerOpen && (
              <button
                className="script-fab proto-drawer-fab"
                onClick={sidebars.openDrawer}
                title="展开操作面板（线索 / 逻辑链 / 角色库 / 历史）"
              >📋<small>面板</small></button>
            )}
            {/* 抽屉打开：点击遮罩关闭（对齐原型 floatBtn 弹回语义） */}
            {sidebars.drawerOpen && (
              <div className="script-panel-mask" onClick={sidebars.closeDrawer} />
            )}
          </>
        ) : (
          /* 旧布局：沉浸模式剧本杀右操作面板默认收纳，悬浮按钮展开（搜证/投票/阶段信息） */
          store.mode === 'script'
            ? (scriptPanelOpen && <ChatRightPanel script={scriptHandlers} />)
            : <ChatRightPanel script={scriptHandlers} />
        )}
        {/* 沉浸模式：面板展开时的点击外部遮罩（点遮罩 = 收起面板；仅旧布局使用） */}
        {!protoV2 && store.mode === 'script' && scriptPanelOpen && (
          <div className="script-panel-mask" onClick={() => setScriptPanelOpen(false)} />
        )}
        <ChatMessageFlow
          showSimPanel={showSimPanel}
          toggleSimPanel={toggleSimPanel}
          scriptState={scriptState}
          simChars={simChars}
          onRollback={rollback}
          onScriptRefresh={refreshScript}
          script={scriptHandlers}
        />
      </div>
      <ChatDrawers drawers={drawers} />
      {/* P-0816-M：兜底状态面板抽屉（ScriptStatePanel 保留为兜底，决策 U5；含揭晓区/搜证/投票旧交互） */}
      {protoV2 && (
        <>
          {showStatePanel && <div className="drawer-overlay" onClick={() => setShowStatePanel(false)} />}
          <aside className={`panel drawer ${showStatePanel ? 'open' : ''}`}>
            <div className="panel-header">
              <h2 className="panel-title">📜 状态面板（兜底）</h2>
              <button className="btn btn-smallall" onClick={() => setShowStatePanel(false)}>✕</button>
            </div>
            <div className="panel-body" style={{ padding: '8px' }}>
              <ScriptStatePanel
                state={scriptState}
                currentPlayer={store.currentPlayer}
                foundClues={scriptClues}
                publicClues={scriptPublicClues}
                reveal={scriptReveal}
                voteTarget={scriptVoteTarget}
                setVoteTarget={setScriptVoteTarget}
                simulation={scriptSimulation}
                busy={scriptBusy}
                searchMsg={scriptSearchMsg}
                transferTargets={transferTargets}
                setTransferTargets={setTransferTargets}
                onSearch={doScriptSearch}
                onTransferClue={doScriptTransferClue}
                onStartDiscussion={doScriptStartDiscussion}
                onStartVoting={doScriptStartVoting}
                onVote={doScriptVote}
                onResolve={doScriptResolve}
                onFinish={doScriptFinish}
                onOpen2D={toggleSimPanel}
                onRestart={doScriptRestart}
                onLeave={doScriptLeave}
                onBackToScene={() => useDemoStore.getState().go('scripts')}
                onGenerateFull={doScriptGenerateFull}
              />
            </div>
          </aside>
        </>
      )}
      {/* 沉浸模式：剧本杀悬浮操作按钮（展开/收起右侧面板；仅旧布局使用） */}
      {!protoV2 && store.mode === 'script' && (
        <button
          className={`script-fab${scriptPanelOpen ? ' active' : ''}`}
          onClick={() => setScriptPanelOpen(v => !v)}
          title={scriptPanelOpen ? '收起操作面板（Esc）' : '展开操作面板（搜证 / 投票 / 阶段信息）'}
        >
          {scriptPanelOpen ? '✕ 收起' : '📋 操作面板'}
        </button>
      )}
    </div>
  );
}
