/**
 * ScriptSetupPanel.tsx — 剧本杀对局页·准备阶段主区（P-0816-M 按原型重构，ui-proto-v2）
 *
 * 对应重构目标「准备/剧本生成中：沿用现有对局准备区（剧本生成进度+消息区）」：
 *   - 生成进度：state.generating（后端 generate_full 异步，SSE/轮询推 generating=true）
 *   - 手动兜底「🔄 生成完整剧本」（POST /api/script/generate_full，后端唯一出口）
 *   - LLM 降级提示条（llm_degraded，P1 任务 3）/ 本人托管提示（trustees，P1 任务 2a）
 * 消息区由 ChatMessageFlow 的 ScriptGalChatPanel 承担（本面板只出生成卡片）。
 */
export interface ScriptSetupPanelProps {
  scriptState: any;
  busy: boolean;
  onGenerateFull: () => void;
}

export function ScriptSetupPanel({ scriptState, busy, onGenerateFull }: ScriptSetupPanelProps) {
  const generating = scriptState?.generating === true;
  const degraded = scriptState?.llm_degraded === true;
  return (
    <div className="proto-setup">
      <div className="proto-setup-head">
        <span className="proto-setup-title">📜 剧本生成</span>
        <span className="proto-setup-phase">准备阶段 · 两阶段生成（完整剧本 + 地图）</span>
      </div>
      <div className="proto-setup-body">
        {generating ? (
          <div className="proto-setup-progress">
            <span className="spinner" />
            <span>完整剧本生成中…（自动）—— 完成后自动进入搜证/讨论阶段</span>
          </div>
        ) : (
          <div className="proto-setup-row">
            <span className="proto-setup-tip">点击生成完整剧本开始对局（生成中按钮禁用，由 script_status 推送 generating 状态）</span>
            <button className="btn btn-smallall proto-setup-btn" disabled={busy} onClick={onGenerateFull}>
              🔄 生成完整剧本
            </button>
          </div>
        )}
        {degraded && (
          <div className="proto-setup-warn">⚠️ 当前为离线模板模式，内容为占位剧本（未检测到 LLM 配置，AI 发言可能静默）</div>
        )}
        {(Array.isArray(scriptState?.trustees) && scriptState.trustees.length > 0) && (
          <div className="proto-setup-warn dim">🤖 托管（AI 代管，投票权作废）：{scriptState.trustees.join('、')}</div>
        )}
      </div>
    </div>
  );
}
