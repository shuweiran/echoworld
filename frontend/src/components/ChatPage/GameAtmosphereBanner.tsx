/**
 * GameAtmosphereBanner.tsx — 对局信息横幅 / 氛围区（#122 游戏内界面打磨）
 *
 * 在 ChatMessageFlow 中间区域渲染一块暗色游戏风的对局信息横幅，包含：
 *   ① 对局主题横幅：剧本/模式名 + 徽标（剧本杀模式阶段/轮次/玩家数已单点化到阶段横幅，不重复）
 *   ② 场景氛围区：剧本背景 / 场景描述（有则显示，无则优雅占位）
 *   ③ 操作引导条：按阶段提示玩家下一步能做什么（剧本杀文案单点源 SCRIPT_GUIDES，见 chatUtils）
 *
 * 数据源：useAppStore（mode / scriptState / werewolf 系列字段），零后端改动。
 * 空态/加载态不报错：数据缺失时显示友好占位。
 */
import { useAppStore } from '../../store/appStore';
import {
  SCRIPT_PHASE_EMOJI,
  PHASE_EMOJI, PHASE_LABEL, normalizePhase, getPhaseGuide, SCRIPT_GUIDES,
} from './chatUtils';

/** 狼人杀各阶段氛围文案 */
const WEREWOLF_ATMO: Record<string, string> = {
  night: '夜幕降临，狼人在黑暗中行动，村民们闭目等待黎明…',
  day_discussion: '天亮了，村民们围坐在一起，互相试探着昨夜的秘密。',
  day_vote: '投票时刻！每个村民都要做出自己的选择…',
  ended: '游戏结束，村庄恢复了平静。',
  game_over: '游戏结束，村庄恢复了平静。',
};

export function GameAtmosphereBanner({ scriptState }: { scriptState: any }) {
  const store = useAppStore();
  const mode = store.mode;

  /* ── 主题横幅数据 ─────────────────────── */
  let themeTitle = '';
  let themeBadge = '';        // 模式徽标（真剧本杀 / 简单对话版 / 狼人杀 / 自由对话…）
  let phaseEmoji = '🎮';
  let phaseLabel = '';
  let phaseCls = '';          // 阶段色（day/vote/night/ended…，对齐 .phase-banner 语义）
  let roundText = '';
  let playersText = '';
  /* ── 场景氛围 ─────────────────────────── */
  let atmosphere = '';
  /* ── 操作引导 ─────────────────────────── */
  let guide = '';

  if (mode === 'script') {
    const st = scriptState;
    themeBadge = st?.mode === 'chat' ? '简单对话版' : '真剧本杀';
    if (st && st.phase) {
      const p: string = st.phase;
      themeTitle = st.name || '剧本杀对局';
      phaseEmoji = SCRIPT_PHASE_EMOJI[p] || '🎮';
      phaseCls = p === 'discussion' ? 'day' : p === 'vote' ? 'vote' : p === 'ended' || p === 'reveal' ? 'ended' : p;
      // P-0815-F 批2（方向3）：阶段信息单点化 —— 阶段/轮次/玩家数由 ChatMessageFlow
      // 阶段横幅 + 右侧 ScriptStatePanel 承担，本主题行不再重复堆叠（只留 剧本名+徽标）。
      phaseLabel = '';
      roundText = '';
      playersText = '';
      atmosphere = st.background
        ? st.background
        : p === 'setup'
          ? (st.generating ? '完整剧本生成中…' : '剧本概要已就绪，生成完整剧本后开始对局')
          : '（本局未提供背景描述）';
      guide = p === 'setup' && st.generating
        ? '完整剧本生成中…（完成后自动进入搜证阶段）'
        : (SCRIPT_GUIDES[p] || '剧本杀对局进行中');
    } else {
      // 空态 / 加载态：不报错，友好占位
      themeTitle = '剧本杀对局';
      phaseEmoji = '🎭';
      phaseLabel = '';
      roundText = '';
      playersText = '';
      atmosphere = '完整剧本生成中…';
      guide = '完整剧本生成中，完成后自动开始';
    }
  } else if (mode === 'werewolf') {
    const p = normalizePhase(store.werewolfPhase);
    themeTitle = '狼人杀';
    themeBadge = '阵营对抗';
    phaseEmoji = PHASE_EMOJI[p] || '🌙';
    phaseLabel = PHASE_LABEL[p] || store.werewolfPhase || '阶段';
    phaseCls = p === 'day_discussion' ? 'day' : p === 'day_vote' ? 'vote' : p === 'night' ? 'night' : p;
    roundText = `第 ${store.werewolfRound || 1} 轮`;
    playersText = store.werewolfPlayers.length > 0 ? `${store.werewolfPlayers.length} 名玩家` : '';
    if (store.werewolfAlive.length > 0) playersText += ` · ${store.werewolfAlive.length} 存活`;
    atmosphere = WEREWOLF_ATMO[p] || '夜幕降临，狼人潜伏在村民之中…';
    guide = getPhaseGuide(store.werewolfPhase, store.werewolfMyRole) || '等待阶段推进…';
  } else if (mode === 'free' || mode === 'director') {
    // 一般模式（自由对话 / 导演）：场景描述作为氛围
    themeTitle = mode === 'director' ? '导演模式' : '自由对话';
    themeBadge = mode === 'director' ? '主控引导' : 'AI 互动';
    phaseEmoji = store.isRunning ? '⏳' : '⏸';
    phaseLabel = store.isRunning ? '进行中' : '空闲';
    phaseCls = store.isRunning ? 'day' : 'idle';
    roundText = `第 ${store.currentRound || 0} 轮`;
    playersText = store.agents.length > 0 ? `${store.agents.length} 名角色` : '';
    atmosphere = store.sceneDescription || '未加载场景描述';
    guide = store.isRunning
      ? '角色互动进行中，可在下方输入主控旁白改变节奏'
      : '在下方输入框发言，或点击「推进一轮」让角色自动互动';
  } else {
    // 兜底空态
    themeTitle = '对局';
    phaseEmoji = '⏸';
    phaseLabel = '空闲';
    phaseCls = 'idle';
    atmosphere = '对局尚未开始，等待主持人…';
    guide = '选择剧本开始对局';
  }

  return (
    <div className={`game-atmo game-atmo-${phaseCls || 'idle'}`}>
      {/* ① 主题横幅 */}
      <div className="game-atmo-row">
        <span className="game-atmo-theme-emoji">{phaseEmoji}</span>
        <span className="game-atmo-title">{themeTitle}</span>
        {themeBadge && <span className="game-atmo-badge">{themeBadge}</span>}
        {/* P-0815-F 批2（方向3）：阶段 chip 仅非剧本杀模式展示（剧本杀阶段已单点化到阶段横幅） */}
        {phaseLabel && <span className="game-atmo-phase">{phaseLabel}</span>}
        {roundText && <span className="game-atmo-meta">{roundText}</span>}
        {playersText && <span className="game-atmo-meta">{playersText}</span>}
      </div>

      {/* ② 场景氛围区 */}
      {atmosphere && (
        <div className="game-atmo-atmo">
          <span className="game-atmo-atmo-label">🌌</span>
          <span>{atmosphere}</span>
        </div>
      )}

      {/* ③ 操作引导条 */}
      {guide && (
        <div className="game-atmo-guide">
          <span className="game-atmo-guide-arrow">▶</span>
          <span>{guide}</span>
        </div>
      )}
    </div>
  );
}
