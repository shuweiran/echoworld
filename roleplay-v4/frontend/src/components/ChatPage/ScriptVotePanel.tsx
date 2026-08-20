/**
 * ScriptVotePanel.tsx — 剧本杀对局页·投票主区（P-0816-M 按原型重构，ui-proto-v2）
 *
 * 对齐原型 vote.html 中栏（Among Us 会议投票心智）：
 *   ① 头部：🗳️ 投票 · 指认真凶 + 倒计时（U9 本地计时）
 *   ② 信任度条（U3 前端近似先行：初始 5/5，本人投票与 most_voted 不一致时 -1；服务端模型 API-12 P2 缓做）
 *   ③ 嫌疑人卡（4 卡网格）：角色色渐变头像 + 名字 + 疑点（candidates[].point）+ 票数；
 *      点击切换选中态（原型 .sus.sel 渐变描边 + ✓ 角标）；本人不可投自己
 *   ④ 投票栏：确认投票 · X（渐变主按钮）+ ⏭️ 弃票（灰色独立按钮）+ 已投票 x/y 进度
 *   ⑤ 投票统计（原型 Among Us 风格）：候选人头像 + 票数横条
 *   ⑥ 拍案演出：确认投票 → 全屏红光 + 震屏 + 「拍案！」大字（CSS 动画 2s 自动恢复，原型 slap）
 * 数据流（全部真实）：
 *   - GET /api/script/vote/status 聚合（只出聚合不出投票人 C13；SSE script_vote_progress + 3s 轮询兜底）
 *   - POST /api/script/vote（suspect）／abstain:true 弃票（U8：弃票计入已投票、不参与票型统计）
 *   - 已投票判定：currentPlayer 不在 pending[] 中 → 已投（禁用）
 */
import { useMemo, useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { useLocalCountdown } from './useLocalCountdown';
import { countVoted, type VoteProgressData } from './voteUtils';
import { avatarGradientFor, roleColorFor } from './chatUtils';

export interface ScriptVotePanelProps {
  /** GET /api/script/vote/status 聚合数据（store.scriptVoteProgress） */
  voteStatus: VoteProgressData | null;
  phase: string;
  /** status.phase_elapsed_ms（本地倒计时基准） */
  phaseElapsedMs?: number | null;
  /** status.phase_timeout_ms（0=禁用展示型倒计时） */
  phaseTimeoutMs?: number | null;
  currentPlayer: string;
  busy: boolean;
  onVote: (suspect: string) => void;
  onAbstain: () => void;
}

export function ScriptVotePanel({
  voteStatus, phase, phaseElapsedMs, phaseTimeoutMs, currentPlayer, busy, onVote, onAbstain,
}: ScriptVotePanelProps) {
  // hooks 必须先于条件 return 调用（React hooks 规则）
  const store = useAppStore();
  const { remainSec } = useLocalCountdown(phaseElapsedMs, phaseTimeoutMs);
  const [selected, setSelected] = useState('');
  const [slap, setSlap] = useState(false);

  const candidates = useMemo(() => {
    const list = Array.isArray(voteStatus?.candidates) ? voteStatus.candidates : [];
    // 无聚合数据时（后端仅返回 {phase}）回退 players 派生（去掉本人）
    if (list.length > 0) return list;
    return [];
  }, [voteStatus]);

  // 非 VOTE 阶段 / 无聚合数据 → 隐藏
  if (phase !== 'vote' || !voteStatus || typeof voteStatus.total !== 'number') return null;

  const done = countVoted(voteStatus);
  const total = voteStatus.total ?? 0;
  const pending = Array.isArray(voteStatus.pending) ? voteStatus.pending : [];
  const abstainedCount = typeof voteStatus.abstained === 'number' ? voteStatus.abstained : 0;
  // 已投票判定：本人不在待投票名单中 → 已投（含弃票）
  const alreadyVoted = !pending.includes(currentPlayer);
  const cands = candidates.length > 0
    ? candidates
    : [{ name: currentPlayer, votes: 0 }]; // 兜底空态（正常不会出现：VOTE 阶段必有候选人）
  const selectedName = selected || '';
  const maxVotes = Math.max(1, ...cands.map(c => c.votes ?? 0));

  /** 确认投票：真实 POST /api/script/vote（拍案演出 2s 自动恢复，原型 slap 语义） */
  const confirmVote = () => {
    if (!selectedName || busy || alreadyVoted) return;
    setSlap(true);
    setTimeout(() => setSlap(false), 2000);
    // P-0816-U：拍案震屏（对齐原型 vote.html body.slamming + @keyframes bodyShake，0.5s 自动恢复）
    document.body.classList.add('proto-slamming');
    setTimeout(() => document.body.classList.remove('proto-slamming'), 500);
    onVote(selectedName);
  };

  return (
    <div className="proto-vote-main">
      {/* 拍案演出层（全屏红光 + 震屏 + 大字，2s 自动恢复） */}
      {slap && <div className="proto-slap"><span className="proto-slap-txt">拍案！</span></div>}

      {/* 头部：标题 + 倒计时 */}
      <div className="proto-vote-head">
        <span className="proto-vote-title">🗳️ 投票 · 指认真凶</span>
        {remainSec != null && (
          <span className={`proto-vote-countdown${remainSec <= 10 ? ' urgent' : ''}`} title="本地计时（status.phase_elapsed_ms / phase_timeout_ms）">
            ⏱ 剩余 {remainSec}s
          </span>
        )}
        <span className="proto-vote-sub">🚨 紧急会议 · 选择你认为的真凶 · 投票后不可更改</span>
      </div>

      {/* P-0816-T（阶段三，决策 U3）：团队信任度前端近似 —— 初始 5/5，本人投票与 most_voted 不一致时 -1
          （script_reveal SSE 到达时由 useGameSse 扣减；仅前端展示态，标注「本地近似」；
          服务端信任度模型 API-12 为 P2 缓做，复用既有信任度条壳） */}
      <div className="proto-trust">
        <span className="proto-trust-ico">⚖️</span>
        <div className="proto-trust-info">
          <div className="proto-trust-top">
            <b>团队信任度</b>
            <span className="proto-trust-num">{store.scriptTrust}/5</span>
          </div>
          <div className="proto-trust-cells">
            {[0, 1, 2, 3, 4].map(i => <span key={i} className={`proto-tc${i < store.scriptTrust ? ' on' : ''}`} />)}
          </div>
        </div>
        <span className="proto-trust-hint">本地近似 · 投票与多数不一致时 -1（服务端模型 API-12 阶段三 P2 缓做）</span>
      </div>

      {/* 嫌疑人卡网格（选中态渐变描边 + ✓ 角标） */}
      <div className="proto-sus-grid">
        {cands.map(c => {
          const isMe = c.name === currentPlayer;
          const isSel = selectedName === c.name;
          const votes = c.votes ?? 0;
          return (
            <button
              key={c.name}
              className={`proto-sus${isSel ? ' sel' : ''}${isMe ? ' self' : ''}`}
              disabled={busy || alreadyVoted || isMe}
              onClick={() => setSelected(c.name)}
              title={isMe ? '不能投自己' : `${c.name}（当前 ${votes} 票）· 点击选择`}
            >
              {isSel && <span className="proto-sus-mark">✓</span>}
              <span className="proto-sus-avatar" style={{ background: avatarGradientFor(c.name) }}>{c.name[0] || '?'}</span>
              {/* P-0816-U：嫌疑人名字角色色（对齐原型 .n-su/.n-chen 等角色色名） */}
              <span className="proto-sus-name" style={{ color: roleColorFor(c.name) }}>{c.name}{isMe ? '（你）' : ''}</span>
              <span className="proto-sus-point">{c.point || '嫌疑人'}</span>
              <span className="proto-sus-votes">{votes} 票</span>
            </button>
          );
        })}
      </div>

      {/* 投票栏：确认投票（渐变主按钮）+ 弃票（灰色独立）+ 已投票进度 */}
      <div className="proto-vote-bar">
        <div className="proto-vote-btns">
          <button
            className="proto-vote-btn"
            disabled={busy || alreadyVoted || !selectedName}
            onClick={confirmVote}
            title={alreadyVoted ? '你已完成投票' : `确认投票给「${selectedName || '…'}」`}
          >
            {alreadyVoted ? '✅ 已投票' : `确认投票 · ${selectedName || '选择嫌疑人'}`}
          </button>
          <button
            className="proto-abstain-btn"
            disabled={busy || alreadyVoted}
            onClick={onAbstain}
            title="弃票：跳过本轮表决（计入已投票 x/y，不参与票型统计，U8）"
          >
            {alreadyVoted ? '⏭️ 已表态' : '⏭️ 弃票'}
          </button>
        </div>
        <div className="proto-vote-progress">
          <div className="proto-vp-top">
            <span className="proto-vp-label">已投票</span>
            <span className="proto-vp-num">{done}/{total}</span>
          </div>
          <div className="proto-vp-cells">
            {Array.from({ length: Math.max(total, done) }).map((_, i) => (
              <span key={i} className={`proto-vp-cell${i < done ? ' done' : ''}`} />
            ))}
          </div>
          <div className="proto-vp-hint">
            {pending.length > 0 ? `${pending.length} 人待投 · 倒计时结束或全员表态后公布结果` : '全员已表态'}
            {abstainedCount > 0 ? `（含弃票 ${abstainedCount}）` : ''}
          </div>
        </div>
      </div>

      {/* 投票统计（Among Us 风格：头像 + 票数横条） */}
      <div className="proto-vote-stat">
        <div className="proto-vote-stat-title">📊 投票统计</div>
        {cands.map(c => {
          const votes = c.votes ?? 0;
          const width = Math.max(4, Math.round((votes / maxVotes) * 100));
          return (
            <div key={c.name} className="proto-bar-row">
              <span className="proto-mini-av" style={{ background: avatarGradientFor(c.name) }}>{c.name[0] || '?'}</span>
              <span className="proto-bar-name">{c.name}</span>
              <div className="proto-bar-track">
                <div className="proto-bar-fill" style={{ width: `${width}%`, background: avatarGradientFor(c.name) }} />
              </div>
              <span className="proto-bar-num">{votes}</span>
            </div>
          );
        })}
        <div className="proto-stat-note">当前 {done} 票已计入（含弃票 {abstainedCount}）· 只出聚合不出投票人（C13）</div>
      </div>
    </div>
  );
}
