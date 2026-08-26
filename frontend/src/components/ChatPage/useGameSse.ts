/**
 * useGameSse.ts — 对局 SSE 桥（阶段① P-0809-A 激活死代码 useSSE）
 *
 * 背景（docs/ui-api-survey.md §4.3 关键发现①）：useSSE 此前只在 AppLegacy.tsx（死代码）被调用，
 * 活跃构建（App2 → GameBridge → ChatPage）未接线 → 剧本杀/狼人杀状态全靠 3s 轮询、
 * 公告横幅无数据源、agent_token 流式打字机失效。本钩子把 AppLegacy 的 38 事件处理逻辑
 * 收敛到对局 UI 层（仅在 ChatPage 挂载时建立连接）：
 *   - 对局状态（script 星号 与 werewolf 星号事件）→ 写 store（轮询降级为兜底）
 *   - announcement → store.addAnnouncement（横幅/公告栏数据源恢复）
 *   - agent_token → 流式打字机逐字渲染
 * 会话定向：script 模式带 scriptSessionId + 玩家凭证，werewolf 带 werewolfSessionId，
 * 一般模式带 Router sessionId；无匹配会话时定向事件静默丢弃，前端轮询兜底。
 */
import { useCallback, useEffect, useRef } from 'react';
import { useAppStore } from '../../store/appStore';
import { useSSE } from '../../api/useSSE';
import { ttsPlayer } from '../../services/ttsPlayer';
import type { WerewolfPhase } from '../../types';

let _skipTts = false;

function normalizeWerewolfPhase(p: string): string {
  if (p === 'discussion') return 'day_discussion';
  if (p === 'voting') return 'day_vote';
  return p;
}

const WW_ROLE_CN: Record<string, string> = {
  wolf: '狼人', seer: '预言家', witch: '女巫', hunter: '猎人',
  villager: '平民', guard: '守卫', idiot: '白痴', elder: '长老', knight: '骑士',
};

export function useGameSse() {
  const scriptSessionId = useAppStore(s => s.scriptSessionId);
  const werewolfSessionId = useAppStore(s => s.werewolfSessionId);
  const generalSessionId = useAppStore(s => s.sessionId);
  const currentPlayer = useAppStore(s => s.currentPlayer);
  const scriptRoleKey = useAppStore(s => s.scriptRoleKey);
  const werewolfRoleKey = useAppStore(s => s.werewolfRoleKey);
  const mode = useAppStore(s => s.mode);

  // 稳定 handler（ref 持有，避免每次渲染重建导致 useSSE 重连）
  const handlerRef = useRef((eventType: string, data: any) => {
    const store = useAppStore.getState();
    switch (eventType) {
      case 'round_start': {
        store.setCurrentRound(data.round);
        store.settleAllStreaming();
        const rs = useAppStore.getState();
        const smallMode = rs.mode === 'free' || rs.mode === 'director';
        if (!(smallMode && rs.agents.length < 3)) {
          store.addSystemMsg(`第 ${data.round} 轮开始`);
        }
        break;
      }
      case 'arbiter_task': {
        const at = useAppStore.getState();
        const smallMode = at.mode === 'free' || at.mode === 'director';
        if (!(smallMode && at.agents.length < 3)) {
          if (at.mode !== 'werewolf' && data.tasks?.length) store.addTaskBlock(data.tasks);
        }
        break;
      }
      case 'agent_output': {
        store.addAgentMsg(data.agent_name, data.content, data.track_id, data.track_label, data.track_mode, data.visible_to);
        store.setCharStatus(data.agent_name, 'active');
        break;
      }
      // P-0802-M：LLM 流式增量 —— 逐片累积到同名草稿消息（完整内容由 agent_output 结算）
      case 'agent_token': {
        if (data.agent_name && data.delta) {
          store.appendAgentToken(data.agent_name, data.delta, data.track_id, data.track_label, data.track_mode);
        }
        break;
      }
      case 'agent_silent': {
        store.addSystemMsg(`${data.agent_name} 本轮旁听`);
        store.setCharStatus(data.agent_name, 'silent');
        break;
      }
      case 'arbiter_integrate': {
        if (data.narration) {
          const ai = useAppStore.getState();
          const sm = ai.mode === 'free' || ai.mode === 'director';
          if (!(sm && ai.agents.length < 3)) {
            store.addIntegration(data.narration);
          }
        }
        break;
      }
      case 'round_complete': {
        store.setCurrentRound(data.round);
        store.setRunning(false);
        store.settleAllStreaming();
        // P-0814-C：经典视图自动推进武装 —— 一般模式轮完即停，置「播出完毕待推进」标志
        // → ChatMessageFlow 自动 POST playback_done 驱动下一轮（原 P-0814-A 武装加在
        // AppLegacy 死代码上未生效，本桥才是经典视图（ChatPage）的 SSE 入口）
        const rc = useAppStore.getState();
        const evtSid = data && typeof data === 'object' ? (data as any).session_id : undefined;
        if (rc.mode !== 'script' && rc.mode !== 'werewolf'
            && (!evtSid || !rc.sessionId || evtSid === rc.sessionId)) {
          useAppStore.setState({ playbackArmed: true });
        }
        const smallMode2 = rc.mode === 'free' || rc.mode === 'director';
        if (!(smallMode2 && rc.agents.length < 3)) {
          store.addSystemMsg(`第 ${data.round} 轮完成`);
        }
        break;
      }
      case 'compression': {
        store.addSystemMsg(`记忆压缩完成：${data.summary || '已更新长期摘要'}`);
        break;
      }
      case 'user_input': {
        if (data.category === 'director_speech' && data.character) {
          store.addAgentMsg(data.character, data.content, 'main', '', 'merged');
        } else if (data.category === 'human_discussion' && data.character) {
          store.addAgentMsg(data.character, data.content, 'day_discussion', '', 'merged');
          store.setWerewolfWaitHuman(false);
        } else if (data.category === 'human_vote' && data.character) {
          store.addAgentMsg(data.character, data.content, 'day_vote', '', 'merged');
          store.setWerewolfWaitHuman(false);
        } else if (data.category === 'human_speech' && data.character) {
          // Skip — loadHistory handles display to avoid duplicates
          break;
        } else if (data.content?.startsWith?.('[系统] 狼人已选择')
                || data.content?.startsWith?.('[系统] 你已选择')
                || data.content?.startsWith?.('[系统] 你已使用')) {
          store.addSystemMsg(data.content.replace('[系统] ', ''));
          store.setWerewolfWaitHuman(false);
        } else if (data.content?.startsWith?.('[系统] ')) {
          store.addSystemMsg(data.content.replace('[系统] ', ''));
        } else {
          store.addUserMsg(data.content?.replace?.('[主控旁白] ', '') || data.content);
        }
        break;
      }
      case 'werewolf_phase': {
        const phase = normalizeWerewolfPhase(data.phase) as WerewolfPhase;
        store.setWerewolfPhase(phase, data.round);
        if (data.session_id) store.setWerewolfSessionId(data.session_id);
        // P-0802-I (G1-2)：新夜清空女巫获知信息（等下一次获知事件）
        if (phase === 'night') store.setWerewolfWitchVictim('');
        const phaseLabels: Record<string, string> = {
          night: '夜间', day_discussion: '白天讨论', day_vote: '投票',
          ended: '已结束', game_over: '游戏结束',
        };
        const label = phaseLabels[phase] || phase;
        const roundNum = data.round || store.werewolfRound;
        store.addSystemMsg(`🌙 第 ${roundNum} ${label} 开始`);
        store.setWerewolfWaitHuman(false);
        break;
      }
      case 'werewolf_player_update': {
        if (data.players) {
          store.setWerewolfPlayers(data.players);
          store.addSystemMsg(`[玩家列表] ${data.players.length} 人`);
        }
        break;
      }
      case 'werewolf_my_role': {
        if (data.role) store.setWerewolfMyRole(WW_ROLE_CN[data.role] || data.role);
        break;
      }
      case 'werewolf_player_eliminated': {
        if (data.name) {
          if (data.role) {
            store.setWerewolfPlayerEliminated(data.name, WW_ROLE_CN[data.role] || data.role);
            store.addSystemMsg(`${data.name} 出局 (${WW_ROLE_CN[data.role] || data.role})`);
          } else {
            store.addSystemMsg(`${data.name} 出局`);
          }
        }
        break;
      }
      case 'werewolf_wait_human': {
        store.setWerewolfWaitHuman(true);
        store.addSystemMsg(data.message || '请真人玩家发言');
        if (data.phase) store.setWerewolfPhase(data.phase, data.round || store.werewolfRound);
        break;
      }
      case 'werewolf_game_over': {
        store.addSystemMsg(data.message || '游戏结束');
        store.setRunning(false);
        store.setWerewolfPhase('game_over');
        useAppStore.setState({ isRunning: false, werewolfPhase: 'game_over', werewolfWaitHuman: false });
        break;
      }
      case 'werewolf_witch_info': {
        store.addSystemMsg(data.hint);
        store.setWerewolfWaitHuman(true);
        // P-0802-I (G1-2)：女巫获知被刀者 → 前端面板先展示被刀者，再让女巫决定救/不救/毒
        if (data.victim) store.setWerewolfWitchVictim(String(data.victim));
        break;
      }
      case 'werewolf_night_result': {
        store.setWerewolfWaitHuman(false);
        if (data.session_id) store.setWerewolfSessionId(data.session_id);
        const died = Array.isArray(data.died) ? data.died : [];
        store.addSystemMsg(died.length > 0
          ? `🌙 昨夜死亡：${died.join('、')}`
          : '🌙 昨夜平安夜，无人死亡');
        break;
      }
      case 'werewolf_vote_update': {
        store.setWerewolfWaitHuman(false);
        if (data.session_id) store.setWerewolfSessionId(data.session_id);
        if (typeof data.votes_count === 'number') store.setWerewolfVoteCount(data.votes_count);
        if (data.approval) store.setWerewolfApproval(data.approval);
        if (data.exiled) store.addSystemMsg(`🗳️ ${data.exiled} 被放逐（${data.reason || ''}）`);
        if (data.winner) store.setWerewolfWinner(data.winner);
        if (data.phase) {
          const p2 = normalizeWerewolfPhase(data.phase) as WerewolfPhase;
          store.setWerewolfPhase(p2, data.round || store.werewolfRound);
        }
        break;
      }
      case 'werewolf_speech': {
        if (data.speaker && data.message) {
          store.addAgentMsg(data.speaker, data.message, 'day_discussion', '', 'merged');
          store.addWerewolfDiscussionTurn({ speaker: data.speaker, message: data.message });
        }
        break;
      }
      case 'werewolf_status': {
        if (data.session_id) store.setWerewolfSessionId(data.session_id);
        if (Array.isArray(data.players)) store.setWerewolfPlayers(data.players);
        break;
      }
      // 剧本杀 SSE（GAP-8）：阶段流转 / 状态推送 / 揭晓结果
      case 'script_phase': {
        store.setScriptPhase(data.phase);
        const labels: Record<string, string> = {
          setup: '准备阶段', investigation: '搜证阶段', discussion: '讨论阶段',
          vote: '投票阶段', reveal: '揭晓阶段', ended: '对局已结束',
        };
        const lbl = labels[data.phase] || data.phase || '';
        store.addSystemMsg(`🎭 剧本杀：${lbl}${data.phase === 'ended' ? '（终局）' : ''}`);
        break;
      }
      case 'script_status': {
        store.setScriptState(data);
        if (data.phase) store.setScriptPhase(data.phase);
        break;
      }
      case 'script_reveal': {
        store.setScriptReveal(data);
        // P-0816-T（阶段三，决策 U3）：信任度前端近似 —— 本人投票与 most_voted 不一致时 -1
        // （仅前端展示态；比对后清空本人投票防重复扣减；服务端模型 API-12 P2 缓做）
        const myVote = store.scriptMyVote;
        if (myVote && data.most_voted && myVote !== String(data.most_voted)) {
          store.setScriptTrust(store.scriptTrust - 1);
          store.addSystemMsg(`⚖️ 团队信任度 -1（你的投票与大多数人不一致，本地近似）`);
        }
        store.setScriptMyVote('');
        const verdict = data.correct ? '✅ 成功找到真凶' : '❌ 冤枉了好人';
        store.addSystemMsg(`🎬 揭晓：得票最多 ${data.most_voted || '无'}，真凶 ${data.murderer || '未识别'}（${verdict}）`);
        break;
      }
      case 'script_private': {
        // 私聊实时推送：会话定向事件，按本人 player 过滤，气泡由私聊抽屉轮询兜底展示
        break;
      }
      // P-0816-H（UI 重设计阶段一 §3.3）：投票进度聚合实时推送（SSE 优先，3s 轮询兜底）
      case 'script_vote_progress': {
        store.setScriptVoteProgress(data);
        break;
      }
      // P-0816-H（UI 重设计阶段一 §3.3）：目标 HUD 实时推送（SSE 优先，3s 轮询兜底）
      case 'script_goal': {
        store.setScriptGoal(data);
        break;
      }
      // P-0816-R（UI 重设计阶段二 §3.3）：心锁状态实时推送（API-4 破锁后广播新锁状态；
      // 前端左栏 🔒 标记刷新；另有 3s 轮询 GET /api/script/locks 兜底）
      case 'script_locks': {
        store.setScriptLocks(data);
        break;
      }
      // P-0816-R（UI 重设计阶段二 §3.3）：质询实时推送（API-5 质询成功 → 服务端 pressed 标记驱动
      // 讨论气泡红色「矛盾点？」角标，不再纯本地；另有 status.discussion（含 pressed 键）轮询兜底）
      case 'script_press': {
        store.addScriptPressEvent(data);
        break;
      }
      // P-0816-T（UI 重设计阶段三 §3.3）：出示证据实时推送（API-9 出示成功 → 「🃏 出示：CL-xx 线索名」
      // system 行立即进讨论主区 VN 流，全员可见；另有 status.discussion（含 system 出示行）轮询兜底，
      // mergeTranscript 按 (speaker,message) 去重防双显）
      case 'script_present': {
        if (data && data.clue_id) {
          const title = data.title ? ` ${data.title}` : '';
          store.addScriptSpeechTurn({ speaker: 'system', message: `🃏 出示：${data.clue_id}${title}` });
        }
        break;
      }
      // P-0816-M（对局页按原型重构）：讨论实时发言（script_speech → 主区 VN 对话流即时展示；
      // 转录由 status.discussion 轮询兜底，useGameSse 与 ScriptGalChatPanel 自建桥双通道并存，
      // 去重交给 mergeTranscript（(speaker,message) 键））
      case 'script_speech': {
        if (data && data.speaker && data.message) {
          store.addScriptSpeechTurn({ speaker: String(data.speaker), message: String(data.message) });
        }
        break;
      }
      case 'auto_complete': {
        store.addSystemMsg(`自动对话结束，共 ${data.rounds || store.currentRound} 轮`);
        store.setRunning(false);
        break;
      }
      case 'stopped': {
        store.addSystemMsg('已停止');
        store.setRunning(false);
        break;
      }
      case 'error': {
        store.addSystemMsg(`错误：${data.error}`);
        store.setRunning(false);
        break;
      }
      case 'saved': {
        store.addSystemMsg('已保存');
        break;
      }
      case 'agent_added': {
        store.addAgent(data.name, data.char_status || 'active');
        store.addSystemMsg(`${data.name} 加入会话`);
        break;
      }
      case 'agent_removed': {
        const state = useAppStore.getState();
        useAppStore.setState({ agents: state.agents.filter(n => n !== data.name) });
        store.addSystemMsg(`${data.name} 离开会话`);
        break;
      }
      case 'track_created': {
        store.addSystemMsg(`[轨道] ${data.label || data.id} 已创建`);
        break;
      }
      case 'track_closed': {
        store.addSystemMsg(`[轨道] ${data.label || data.id} 已关闭`);
        break;
      }
      case 'phase_changed': {
        store.addSystemMsg(`[阶段] → ${data.phase}`);
        break;
      }
      case 'announcement': {
        // 演讲+广播合并地基：SSE announcement → 公告栏 + 中央横幅（打字机）
        store.addAnnouncement(data);
        break;
      }
      // TTS 流式语音
      case 'tts_start': {
        const agentName = data.agent_name || '';
        const at = useAppStore.getState();
        if (agentName && at.voiceMap[agentName] === false) {
          _skipTts = true;
          break;
        }
        _skipTts = false;
        useAppStore.setState({ ttsStatus: '🔊 语音播报中...' });
        break;
      }
      case 'tts_chunk': {
        if (_skipTts) break;
        ttsPlayer.addChunk(data.data);
        break;
      }
      case 'tts_end': {
        if (!_skipTts) useAppStore.setState({ ttsStatus: '' });
        _skipTts = false;
        break;
      }
      case 'tts_error': {
        useAppStore.setState({ ttsStatus: '⚠️ 语音播报失败' });
        console.warn('TTS error:', data.error);
        break;
      }
    }
  });

  const getSessionId = useCallback((): string => {
    const st = useAppStore.getState();
    if (st.mode === 'script') return st.scriptSessionId;
    if (st.mode === 'werewolf') return st.werewolfSessionId;
    return '';
  }, []);

  const onSseEvent = useCallback((evt: string, data: any) => handlerRef.current(evt, data), []);
  const onSseStatus = useCallback((status: 'connecting' | 'open' | 'reconnecting') => {
    useAppStore.getState().setSseHealthy(status === 'open');
  }, []);

  // 所有模式都按当前 session 订阅；剧本杀额外携带本人身份，才能收到服务端私密事件。
  const sseSessionId = mode === 'script'
    ? scriptSessionId
    : (mode === 'werewolf' ? werewolfSessionId : generalSessionId);
  const gameIdentity = mode === 'script'
    ? { player: currentPlayer, playerKey: scriptRoleKey }
    : (mode === 'werewolf' ? { player: currentPlayer, playerKey: werewolfRoleKey } : undefined);
  useSSE(onSseEvent, sseSessionId || undefined, onSseStatus, gameIdentity);
  useEffect(() => () => useAppStore.getState().setSseHealthy(false), []);

  // 暴露 getSessionId 供需要按当前对局取会话的场景使用（保留引用稳定性）
  return { getSessionId };
}
