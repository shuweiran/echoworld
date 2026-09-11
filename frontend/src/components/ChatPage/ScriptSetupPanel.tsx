/**
 * ScriptSetupPanel.tsx — 剧本杀对局页·准备/开场阶段主区。
 *
 * SETUP 现在有两个明确子状态：
 *   1) 生成中：outline 已有、完整剧本/地图尚未就绪；
 *   2) 开场就绪：完整角色/线索/AP 已生成，但必须由玩家确认后才进入搜证。
 *
 * 生成入口改走 /api/script/flow/generate_full，使后端编排器在完整生成结束后把阶段停在
 * opening SETUP；“开始搜证”走 /api/script/flow/investigation/start，并同时启动 AI 自主搜证。
 */
import { useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { scriptFlowApi } from '../../api/scriptFlow';

export interface ScriptSetupPanelProps {
  scriptState: any;
  busy: boolean;
  /** 旧生成入口仅作会话身份尚未建立时的兼容兜底。 */
  onGenerateFull: () => void;
}

export function ScriptSetupPanel({ scriptState, busy, onGenerateFull }: ScriptSetupPanelProps) {
  const store = useAppStore();
  const [flowBusy, setFlowBusy] = useState(false);
  const [flowMsg, setFlowMsg] = useState('');

  const generating = scriptState?.generating === true;
  const degraded = scriptState?.llm_degraded === true;
  const rolesReady = Array.isArray(scriptState?.roles) && scriptState.roles.length > 0;
  const openingReady = !generating && rolesReady && String(scriptState?.phase || '').toLowerCase() === 'setup';
  const disabled = busy || flowBusy;

  const identity = () => ({
    sessionId: String(scriptState?.session_id || store.scriptSessionId || ''),
    player: String(store.currentPlayer || ''),
    playerKey: String(store.scriptRoleKey || ''),
  });

  const generateFull = async () => {
    const id = identity();
    // 极旧恢复态可能暂时没有 session/player；保留原 handler 作为零破坏兜底。
    if (!id.sessionId || !id.player) {
      onGenerateFull();
      return;
    }
    setFlowBusy(true);
    setFlowMsg('');
    try {
      const res = await scriptFlowApi.generateFull(id);
      setFlowMsg(res?.error ? `⚠️ ${res.error}` : '完整剧本生成已启动；完成后先进入开场交流。');
    } catch (e: any) {
      setFlowMsg(`⚠️ ${e?.message || '启动完整剧本生成失败'}`);
    } finally {
      setFlowBusy(false);
    }
  };

  const startInvestigation = async () => {
    const id = identity();
    if (!id.sessionId || !id.player) return;
    setFlowBusy(true);
    setFlowMsg('');
    try {
      const res = await scriptFlowApi.startInvestigation(id);
      setFlowMsg(res?.error
        ? `⚠️ ${res.error}`
        : (res?.ai_investigation_started ? '已进入搜证；AI 角色也开始分别调查。' : '已进入搜证。'));
    } catch (e: any) {
      setFlowMsg(`⚠️ ${e?.message || '进入搜证失败'}`);
    } finally {
      setFlowBusy(false);
    }
  };

  return (
    <div className="proto-setup">
      <div className="proto-setup-head">
        <span className="proto-setup-title">{openingReady ? '🎭 开场交流' : '📜 剧本准备'}</span>
        <span className="proto-setup-phase">
          {openingReady ? '准备阶段 · 角色交流后由玩家确认进入搜证' : '准备阶段 · 完整剧本 + 地图生成'}
        </span>
      </div>
      <div className="proto-setup-body">
        {generating ? (
          <div className="proto-setup-progress">
            <span className="spinner" />
            <span>完整剧本生成中… 完成后进入开场交流，不会自动开始搜证。</span>
          </div>
        ) : openingReady ? (
          <div className="proto-setup-row">
            <span className="proto-setup-tip">
              角色已就绪。先在下方和大家交流；确认后，玩家与 AI 会分别调查并各自持有发现的线索。
            </span>
            <button className="btn btn-smallall proto-setup-btn" disabled={disabled} onClick={() => void startInvestigation()}>
              {flowBusy ? '处理中…' : '🔍 确认并开始搜证'}
            </button>
          </div>
        ) : (
          <div className="proto-setup-row">
            <span className="proto-setup-tip">先生成完整剧本；完成后会停在开场交流，不再直接跳到搜证。</span>
            <button className="btn btn-smallall proto-setup-btn" disabled={disabled} onClick={() => void generateFull()}>
              {flowBusy ? '启动中…' : '🔄 生成完整剧本'}
            </button>
          </div>
        )}
        {flowMsg && <div className="proto-setup-warn dim">{flowMsg}</div>}
        {degraded && (
          <div className="proto-setup-warn">⚠️ 当前为离线模板模式，内容为占位剧本（未检测到 LLM 配置，AI 发言可能使用降级话术）</div>
        )}
        {(Array.isArray(scriptState?.trustees) && scriptState.trustees.length > 0) && (
          <div className="proto-setup-warn dim">🤖 托管（AI 代管，投票权作废）：{scriptState.trustees.join('、')}</div>
        )}
      </div>
    </div>
  );
}
