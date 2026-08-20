/**
 * ScriptRevealPanel.tsx — 剧本杀对局页·揭晓/终局主区（P-0816-M 按原型重构，ui-proto-v2）
 *
 * 决策 U5（沿用现有揭晓区）：REVEAL/ENDED 揭晓交互沿用 ScriptStatePanel 揭晓区语义，
 * 本组件为「主区化」的揭晓/终局面板（同一数据源与动作回调，JSX 摘自 ScriptStatePanel）：
 *   - REVEAL：得票最多 / 结果 / 低参与度 / 超时弃票 / 真相 / 胜者 + 🏁 结束对局（confirmEnded）
 *   - ENDED：被定罪 / 真凶 / 判定 / 真相 + 🔄 再来一局（restart）+ 📋 回到剧本选择
 * 兜底：ScriptStatePanel 全量面板仍可从 ⚙️ 设置菜单「状态面板（兜底）」打开。
 */
export interface ScriptRevealPanelProps {
  scriptState: any;
  reveal: any;
  busy: boolean;
  onFinish: () => void;
  onRestart: () => void;
  onBackToScene: () => void;
}

export function ScriptRevealPanel({ scriptState, reveal, busy, onFinish, onRestart, onBackToScene }: ScriptRevealPanelProps) {
  const phase: string = scriptState?.phase || 'reveal';
  const showReveal = phase === 'reveal' || !!reveal;
  if (!showReveal && phase !== 'ended') return null;

  return (
    <div className="proto-reveal">
      {/* ── 揭晓区（REVEAL / 已有 reveal 数据） ── */}
      {showReveal && (
        <section className="proto-reveal-sec">
          <div className="proto-reveal-title">🎬 揭晓</div>
          <div className="proto-reveal-rows">
            <div>得票最多：<b>{reveal?.most_voted || scriptState?.most_voted || '无人投票'}</b></div>
            <div>结果：{reveal?.result || scriptState?.result || ''}</div>
            {(reveal?.low_participation || scriptState?.low_participation) && (
              <div className="proto-reveal-warn">⚠️ 低参与度判定（投票人数不足门槛，按已投票计）</div>
            )}
            {Array.isArray(reveal?.abstained) && (reveal.abstained || []).length > 0 && (
              <div className="proto-reveal-note">弃票（投票超时转托管）：{(reveal.abstained || []).join('、')}</div>
            )}
            <div className="proto-reveal-truth"><strong>真相：</strong>{reveal?.truth || scriptState?.truth || ''}</div>
            {scriptState?.winner && <div className="proto-reveal-note">🏆 {scriptState.winner}</div>}
          </div>
          {phase === 'reveal' && (
            <button className="btn btn-smallall proto-reveal-btn" disabled={busy} onClick={onFinish}>
              🏁 结束对局
            </button>
          )}
        </section>
      )}

      {/* ── 终局区（ENDED） ── */}
      {phase === 'ended' && (
        <section className="proto-reveal-sec">
          <div className="proto-reveal-title">🏁 终局</div>
          <div className="proto-reveal-rows">
            <div>被定罪：<b>{scriptState?.winner || reveal?.most_voted || '无人投票'}</b></div>
            <div>真凶：{reveal?.murderer || scriptState?.murderer || '未识别'}</div>
            <div>判定：{reveal?.correct === true ? '✅ 成功找到真凶' : reveal?.correct === false ? '❌ 冤枉了好人' : ''}</div>
            {scriptState?.low_participation && (
              <div className="proto-reveal-warn">⚠️ 低参与度判定（投票人数不足门槛，按已投票计）</div>
            )}
            <div className="proto-reveal-truth"><strong>真相：</strong>{reveal?.truth || scriptState?.truth || ''}</div>
            <div className="proto-reveal-actions">
              <button className="btn btn-smallall btn-primary" disabled={busy} onClick={onRestart}>
                🔄 再来一局（同剧本）
              </button>
              <button className="btn btn-smallall" onClick={onBackToScene}>
                📋 回到剧本选择
              </button>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
