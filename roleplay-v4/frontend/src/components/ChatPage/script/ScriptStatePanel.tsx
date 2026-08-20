/**
 * ScriptStatePanel.tsx — 剧本杀状态面板（六态状态机 / 搜证 / 线索转交 / 投票 / 揭晓 / 终局）
 * 阶段① P-0809-A 拆分自 ChatPage.tsx（原 ScriptStatePanel 函数组件，props 契约不变）。
 * 数据流：父组件（ChatPage）轮询/SSE 写入 store，本组件 props 驱动 + 回调上抛动作。
 */
import { useState } from 'react';
import { SCRIPT_PHASE_EMOJI, SCRIPT_PHASE_LABEL } from '../chatUtils';

export function ScriptStatePanel(props: {
  state: any;
  currentPlayer: string;
  foundClues: any[];
  publicClues: any[];
  reveal: any;
  voteTarget: string;
  setVoteTarget: (n: string) => void;
  simulation: any;
  busy: boolean;
  searchMsg: string;
  transferTargets: Record<string, string>;
  setTransferTargets: (m: Record<string, string>) => void;
  onSearch: (location: string) => void;
  onTransferClue: (clueId: string, target: string) => void;
  onStartDiscussion: () => void;
  onStartVoting: () => void;
  onVote: () => void;
  onResolve: () => void;
  onFinish: () => void;
  /** P0-3：内嵌 2D 模拟面板开关（替代 window.open 双开）*/
  onOpen2D?: () => void;
  /** P1（剧本杀可玩性修复）：ENDED 后重开一局（同剧本同玩家）*/
  onRestart?: () => void;
  /** P1：玩家退出对局 → 角色转托管（AI 代管，投票权作废）*/
  onLeave?: () => void;
  /** P1：回到剧本选择（ENDED 面板出口之一）*/
  onBackToScene?: () => void;
  /** P-0815-F（方向1，根因 A）：SETUP 阶段生成完整剧本（两阶段 init 后半程；生成中由 script_status 推 generating=true）*/
  onGenerateFull?: () => void;
}) {
  const {
    state, currentPlayer, foundClues, publicClues, reveal, voteTarget,
    setVoteTarget, simulation, busy, searchMsg, transferTargets, setTransferTargets,
    onSearch, onTransferClue, onStartDiscussion, onStartVoting, onVote, onResolve, onFinish,
    onOpen2D, onRestart, onLeave, onBackToScene, onGenerateFull,
  } = props;
  if (!state) {
    return (
      <div className="ww-panel game-card">
        <div className="muted" style={{ padding: 8, fontSize: 12 }}>剧本局加载中..</div>
      </div>
    );
  }
  const phase: string = state.phase || 'setup';
  // P-0815-F 批2（方向3）：秘密卡展开/收起（长秘密默认截断，与聊天区旁路条 24 字截断对齐）
  const [secretOpen, setSecretOpen] = useState(false);
  const secretText: string = state.your_secret ? String(state.your_secret) : '';
  const secretTruncated = secretText.length > 24;
  // P-0815-F 批2（方向3）：简单对话版（mode=chat）—— 无取证无地图，隐藏搜证区/2D 讨论区
  const isChatMode = state.mode === 'chat';
  const otherPlayers: string[] = (state.players || []).filter((n: string) => n !== currentPlayer);

  return (
    <div className="ww-panel game-card">
      {/* Header — current phase */}
      <div className="ww-panel-header">
        <span>{SCRIPT_PHASE_EMOJI[phase] || '🎮'}</span>
        <span>{SCRIPT_PHASE_LABEL[phase] || phase}</span>
      </div>

      {/* P1（任务 3）：LLM 降级提示条 —— generateScript 走了 defaultScript 兜底（无 LLM key / LLM 失败）时后端注入 llm_degraded=true */}
      {state.llm_degraded && (
        <div style={{
          margin: '4px 10px', padding: '6px 8px', fontSize: 12, lineHeight: 1.5,
          background: 'var(--phase-discussion-soft)', border: '1px solid color-mix(in srgb, var(--phase-discussion) 40%, transparent)', borderRadius: 6, color: 'var(--phase-discussion)',
        }}>
          ⚠️ 当前为离线模板模式，内容为占位剧本（未检测到 LLM 配置，AI 发言可能静默）
        </div>
      )}

      {/* P1（任务 2a）：本人已托管提示（退出/断线/投票超时无操作 → AI 代管，投票权作废）*/}
      {(state.trustees || []).includes(currentPlayer) && phase !== 'ended' && (
        <div style={{
          margin: '4px 10px', padding: '6px 8px', fontSize: 12, lineHeight: 1.5,
          background: 'var(--phase-default-soft)', border: '1px solid color-mix(in srgb, var(--phase-default) 40%, transparent)', borderRadius: 6, color: 'color-mix(in srgb, var(--phase-default) 80%, white)',
        }}>
          🤖 你已托管（AI 代管，投票权作废）—— 可等待他人结束对局，或重开后重新加入
        </div>
      )}

      {/* P-0815-F（方向1，根因 A）：SETUP 阶段完整剧本生成入口（后端 generate_full 唯一出口前端接线——
          init 为两阶段生成 outline_only 缺省 true 停 SETUP；GameBridge 已自动触发，此处为手动兜底/进度展示）*/}
      {phase === 'setup' && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <div className="ww-panel-section-title">📜 完整剧本</div>
          <div style={{ fontSize: 12, lineHeight: 1.5, color: 'var(--text-2)', marginBottom: 6 }}>
            {state.generating
              ? '完整剧本生成中…（自动）—— 完成后自动进入搜证/讨论阶段'
              : '点击生成完整剧本开始对局（两阶段生成：完整剧本 + 地图）'}
          </div>
          <button
            className="btn btn-smallall"
            disabled={busy || !!state.generating}
            onClick={onGenerateFull}
          >
            {state.generating ? '🔄 生成中…' : '🔄 生成完整剧本'}
          </button>
        </div>
      )}

      {/* P-0815-F 批2（方向3）：信息分区 —— 阶段（header）/ 我的信息 / 操作区，标题层级统一 */}
      {/* My role */}
      <div className="ww-panel-section-title">🎭 我的信息</div>
      <div className="ww-my-role-box">
        <span>🎭</span>
        <span>{state.your_role || '未分配角色'}</span>
        {(state.trustees || []).includes(currentPlayer) && <span style={{ marginLeft: 6, fontSize: 11, color: 'color-mix(in srgb, var(--phase-default) 80%, white)' }}>🤖 托管</span>}
      </div>

      {/* C2: 行动点余额（初始 = 基础值 + 角色 ap_bonus，搜证消耗） */}
      <div className="ww-my-role-box" style={{ marginTop: 4 }}>
        <span>⚡</span>
        <span>行动点 {state.ap ?? 0} / {state.ap_max ?? 0}</span>
      </div>

      {/* My secret (only visible to me) —— P-0815-F 批2（方向3）：长秘密默认截断 + 展开/收起
          （与 ScriptGalChatPanel 旁路条 24 字截断对齐，260px 窄栏不再被大段换行撑高） */}
      {secretText && (
        <div className="card" style={{ margin: '0 10px 8px', padding: 8, fontSize: 12, background: 'var(--bg-2)', color: 'var(--text-2)', lineHeight: 1.5 }}>
          🔒 <strong>你的秘密</strong>（只有你知道，切勿泄露）：{secretTruncated && !secretOpen ? secretText.slice(0, 24) + '…' : secretText}
          {secretTruncated && (
            <button
              className="btn btn-small"
              style={{ marginLeft: 6, padding: '0 6px', fontSize: 11, verticalAlign: 'middle' }}
              onClick={() => setSecretOpen(v => !v)}
            >
              {secretOpen ? '收起' : '展开'}
            </button>
          )}
        </div>
      )}

      {/* Investigation: search locations (backend search 仅搜证阶段可用；C2: 搜索消耗 AP)
          P-0803-K：简单对话版（chat）无搜证阶段，此区天然不渲染 */}
      {!isChatMode && phase === 'investigation' && (
        <div className="ww-panel-section">
          <div className="ww-panel-section-title">🔍 搜证地点（⚡{state.ap ?? 0}）</div>
          {/* P-0815-F 批2（方向3）：AP 消耗预提示 —— 玩家搜证前知道成本与不足后果（报告 §4.3 缺口） */}
          <div style={{ padding: '0 10px 6px', fontSize: 11, color: 'var(--text-3)', lineHeight: 1.5 }}>
            每次搜索消耗线索对应的行动点；行动点不足将整次拒绝（不部分授予）
          </div>
          <div style={{ padding: '0 10px 6px', display: 'flex', flexWrap: 'wrap', gap: 4 }}>
            {(state.locations || []).map((loc: string) => (
              <button key={loc} className="btn btn-smallall" disabled={busy} onClick={() => onSearch(loc)}>📍 {loc}</button>
            ))}
          </div>
          {/* C2: 搜证结果/行动点不足提示。*/}
          {searchMsg && (
            <div style={{ padding: '0 10px 6px', fontSize: 12, color: 'var(--text-2)' }}>{searchMsg}</div>
          )}
        </div>
      )}
      {/* 本次搜证结果（上次 search 响应；主反馈已走 searchMsg，此处保留线索明细） */}
      {(foundClues.length > 0 || publicClues.length > 0) && (
        <div className="ww-panel-section">
          <div style={{ padding: '0 10px 8px', fontSize: 12, color: 'var(--text-2)' }}>
            {foundClues.length > 0 && (
              <div style={{ marginBottom: 4 }}>
                <strong>本次搜证：</strong>
                {foundClues.map((c: any, i: number) => (
                  <div key={i} style={{ marginTop: 2 }}>• {c.content}</div>
                ))}
              </div>
            )}
            {publicClues.length > 0 && (
              <div>
                <strong>公开线索：</strong>
                {publicClues.map((c: any, i: number) => (
                  <div key={i} style={{ marginTop: 2 }}>• {c.content}</div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {/* C2: 我持有的线索（含转入的；investigation/discussion 均展示） */}
      {(phase === 'investigation' || phase === 'discussion') && (state.my_clues || []).length > 0 && (
        <div className="ww-panel-section">
          <div className="ww-panel-section-title">📋 我持有的线索</div>
          <div style={{ padding: '0 10px 8px', fontSize: 12, color: 'var(--text-2)', lineHeight: 1.5 }}>
            {(state.my_clues || []).map((c: any, i: number) => (
              <div key={c.id || i} style={{ marginTop: 2 }}>
                • {c.title || c.content}{c.transferable ? ' 🔁' : ''}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* C2: 线索转交（仅可转交线索；选择目标玩家后转交，ownership 变更。*/}
      {(phase === 'investigation' || phase === 'discussion')
        && (state.my_clues || []).some((c: any) => c.transferable) && otherPlayers.length > 0 && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <div className="ww-panel-section-title">🔁 线索转交</div>
          {(state.my_clues || []).filter((c: any) => c.transferable).map((c: any) => (
            <div key={c.id} style={{ display: 'flex', gap: 4, alignItems: 'center', marginTop: 4, fontSize: 12 }}>
              <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {c.title || (c.content || '').slice(0, 10)}
              </span>
              <select
                value={transferTargets[c.id] || ''}
                onChange={(e) => setTransferTargets({ ...transferTargets, [c.id]: e.target.value })}
                style={{ maxWidth: 90, fontSize: 12 }}
              >
                <option value="">选玩家</option>
                {otherPlayers.map((p: string) => <option key={p} value={p}>{p}</option>)}
              </select>
              <button
                className="btn btn-smallall"
                disabled={busy || !transferTargets[c.id]}
                onClick={() => onTransferClue(c.id, transferTargets[c.id])}
              >
                转交
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Phase transitions */}
      {phase === 'investigation' && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <button className="btn btn-smallall" disabled={busy} onClick={onStartDiscussion}>🗣️ 结束搜证，进入讨论</button>
        </div>
      )}
      {phase === 'discussion' && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <button className="btn btn-smallall" disabled={busy} onClick={onStartVoting}>🗳️ 结束讨论，进入投票</button>
        </div>
      )}

      {/* P1（任务 2a）：退出对局（角色转托管）—— 局中任意阶段可用；本人已托管/终态时隐藏 */}
      {phase !== 'ended' && phase !== 'reveal' && !(state.trustees || []).includes(currentPlayer) && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <button className="btn btn-smallall" disabled={busy} onClick={onLeave} style={{ opacity: 0.75 }}>
            🚪 退出对局（AI 代管）
          </button>
        </div>
      )}

      {/* 2D simulation bridge —— P-0803-K：简单对话版（chat）无 2D 空间讨论区*/}
      {!isChatMode && (phase === 'discussion' || state.simulation_started) && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <div className="ww-panel-section-title">🗺️ 2D 空间讨论</div>
          <div style={{ fontSize: 12, lineHeight: 1.5, color: 'var(--text-2)', marginBottom: 6 }}>
            {state.simulation_started
              ? `已接入 2D 世界：{simulation?.agentCount ?? state.players?.length ?? 0} 名角色，tick ${simulation?.tick ?? 0}`
              : '进入讨论后会自动加载 2D 世界'}
          </div>
          <button className="btn btn-smallall" disabled={!state.simulation_started} onClick={() => onOpen2D && onOpen2D()}>
            查看 2D 模拟（内嵌）
          </button>
        </div>
      )}

      {/* P-0803-M：简单对话版（chat）开启「配置地图」后 —— 地图查看入口（氛围展示 · 只读，无搜证。*/}
      {isChatMode && state.map && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <div className="ww-panel-section-title">🗺️ 对局地图（氛围展示）</div>
          <div style={{ fontSize: 12, lineHeight: 1.5, color: 'var(--text-2)', marginBottom: 6 }}>
            {state.map.width}×{state.map.height} 格 · {(state.map.zones || []).length} 热点 · 只读无搜证
          </div>
          <button className="btn btn-smallall" onClick={() => onOpen2D && onOpen2D()}>
            查看地图（Phaser 渲染）</button>
        </div>
      )}

      {/* Vote */}
      {phase === 'vote' && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <div className="ww-panel-section-title">🗳️ 投票：你怀疑谁是真凶？</div>
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 6 }}>
            {otherPlayers.map((n: string) => (
              <button key={n} className={`chip ${voteTarget === n ? 'selected' : ''}`} onClick={() => setVoteTarget(n)}>
                {n}{(state.trustees || []).includes(n) ? ' 🤖' : ''}
              </button>
            ))}
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            <button className="btn btn-smallall btn-primary" disabled={busy || !voteTarget} onClick={onVote}>🗳️ {voteTarget || '...'}</button>
            <button className="btn btn-smallall" disabled={busy} onClick={onResolve}>🎬 揭晓真相</button>
          </div>
          {/* P-0815-F 批2（方向3）：投票超时 / 审批门玩家侧提示（报告 §4.3 VOTE/REVEAL 引导缺口） */}
          <div style={{ marginTop: 4, fontSize: 11, color: 'var(--text-3)', lineHeight: 1.6 }}>
            ⏱ 投票超时未投将按弃票处理（角色转托管，不参与有效票门槛）
            <br />🎬 点击「揭晓真相」将进入主持人（DM）审批门，批准后显示结果
          </div>
          {(state.trustees || []).length > 0 && (
            <div style={{ marginTop: 4, fontSize: 11, color: 'var(--text-3)' }}>
              🤖 托管（AI 代管，票作废）：{(state.trustees || []).join('、')} —— 有效票门槛按在线玩家计算
            </div>
          )}
        </div>
      )}

      {/* Reveal result */}
      {(phase === 'reveal' || reveal) && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <div className="ww-panel-section-title">🎬 揭晓</div>
          <div style={{ fontSize: 12, lineHeight: 1.6, color: 'var(--text-2)' }}>
            <div>得票最多：{reveal?.most_voted || state.most_voted || '无人投票'}</div>
            <div>结果：{reveal?.result || state.result || ''}</div>
            {/* P1（任务 1）：低参与度判定 / 超时弃票提示 */}
            {(reveal?.low_participation || state.low_participation) && (
              <div style={{ marginTop: 4, color: 'var(--phase-discussion)' }}>⚠️ 低参与度判定（投票人数不足门槛，按已投票计）</div>
            )}
            {Array.isArray(reveal?.abstained) && (reveal?.abstained || []).length > 0 && (
              <div style={{ marginTop: 4 }}>弃票（投票超时转托管）：{(reveal?.abstained || []).join('、')}</div>
            )}
            <div style={{ marginTop: 4 }}><strong>真相：</strong>{reveal?.truth || state.truth || ''}</div>
            {state.winner && <div style={{ marginTop: 4 }}>🏆 {state.winner}</div>}
          </div>
          {/* GAP-4b: REVEAL 展示后由前端确认进入 ENDED */}
          {phase === 'reveal' && (
            <button className="btn btn-smallall" style={{ marginTop: 6 }} disabled={busy} onClick={onFinish}>
              🏁 结束对局
            </button>
          )}
        </div>
      )}

      {/* GAP-4b: ENDED 终态展示。*/}
      {phase === 'ended' && (
        <div className="ww-panel-section" style={{ padding: '0 10px 8px' }}>
          <div className="ww-panel-section-title">🏁 终局</div>
          <div style={{ fontSize: 12, lineHeight: 1.6, color: 'var(--text-2)' }}>
            <div>被定罪：{state.winner || reveal?.most_voted || '无人投票'}</div>
            <div>真凶：{reveal?.murderer || state.murderer || '未识别'}</div>
            <div>判定：{reveal?.correct === true ? '✅ 成功找到真凶' : reveal?.correct === false ? '❌ 冤枉了好人' : ''}</div>
            {state.low_participation && (
              <div style={{ marginTop: 4, color: 'var(--phase-discussion)' }}>⚠️ 低参与度判定（投票人数不足门槛，按已投票计）</div>
            )}
            <div style={{ marginTop: 4 }}><strong>真相：</strong>{reveal?.truth || state.truth || ''}</div>
            {/* P1（任务 2b）：ENDED 后重开入口 —— 同剧本同玩家重开一局 / 回到剧本选择 */}
            <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
              <button className="btn btn-smallall btn-primary" disabled={busy} onClick={onRestart}>
                🔄 再来一局（同剧本）
              </button>
              <button className="btn btn-smallall" onClick={onBackToScene}>
                📋 回到剧本选择
              </button>
            </div>
          </div>
        </div>
      )}

      <div style={{ padding: '4px 10px', fontSize: 11, color: 'var(--text-3)' }}>
        《{state.name}》 · 第 {state.round || 1} 轮</div>
    </div>
  );
}
