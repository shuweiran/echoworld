/**
 * WerewolfActionPanel.tsx — 狼人杀行动面板（P-0802-F：夜间行动/讨论发言/投票/猎人开枪/审批）
 * 阶段① P-0809-A 拆分自 ChatPage.tsx（原 WerewolfActionPanel 函数组件）。
 * 直接调 werewolf API（后台 autoPlay 自动推进，真人只需提交己方行动）。
 */
import { useState } from 'react';
import { useAppStore } from '../../../store/appStore';
import { api } from '../../../api/client';
import { SilenceTurn, isSilenceText } from '../../../utils/silenceMarker';
import { normalizePhase } from '../chatUtils';

export function WerewolfActionPanel() {
  const store = useAppStore();
  const [target, setTarget] = useState('');
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);
  const player = store.currentPlayer;
  const p = normalizePhase(store.werewolfPhase);
  const role = store.werewolfMyRole;
  const alive = store.werewolfAlive;
  const aliveOthers = alive.filter(n => n !== player);
  const isEliminated = store.werewolfPlayers.some(pw => pw.name === player && !pw.alive);
  const isGameOver = store.werewolfPhase === 'game_over' || store.werewolfPhase === 'ended' || !!store.werewolfWinner;

  const toast = (t: string) => store.addSystemMsg(t);
  const afterAction = () => { setTarget(''); setBusy(false); };

  const act = async (action: string) => {
    if (!target) return;
    setBusy(true);
    try {
      const res = await api.werewolfNightAction(player, action, target);
      toast(res?.result || `行动完成（${action} → ${target}）`);
    } catch (e: any) { toast('⚠️ ' + (e.message || '夜间行动失败')); }
    afterAction();
  };
  // P-0802-I (G1-2)：女巫获知被刀者后直接救被刀者（无需选目标）/ 明确不使用解药/ 明确不使用毒药
  const saveWitchVictim = async () => {
    const victim = store.werewolfWitchVictim;
    if (!victim) return;
    setBusy(true);
    try {
      const res = await api.werewolfNightAction(player, 'save', victim);
      toast(res?.result || `已使用解药救 ${victim}`);
    } catch (e: any) { toast('⚠️ ' + (e.message || '救失败')); }
    setBusy(false);
  };
  const declineWitchSave = async () => {
    setBusy(true);
    try {
      const res = await api.werewolfNightAction(player, 'nosave', '');
      toast(res?.result || '已选择不使用解药（保留解药）');
    } catch (e: any) { toast('⚠️ ' + (e.message || '操作失败')); }
    setBusy(false);
  };
  const declineWitchPoison = async () => {
    setBusy(true);
    try {
      const res = await api.werewolfNightAction(player, 'nopoison', '');
      toast(res?.result || '已选择不使用毒药（保留毒药）');
    } catch (e: any) { toast('⚠️ ' + (e.message || '操作失败')); }
    setBusy(false);
  };
  const vote = async () => {
    if (!target) return;
    setBusy(true);
    try {
      const res = await api.werewolfVote(player, target);
      toast(res?.result || `已投票给 ${target}`);
    } catch (e: any) { toast('⚠️ ' + (e.message || '投票失败')); }
    afterAction();
  };
  const shoot = async () => {
    if (!target) return;
    setBusy(true);
    try {
      const res = await api.werewolfHunterShoot(player, target);
      toast(res?.result || `已开枪击杀 ${target}`);
    } catch (e: any) { toast('⚠️ ' + (e.message || '开枪失败')); }
    afterAction();
  };
  const say = async () => {
    if (!msg.trim()) return;
    setBusy(true);
    try {
      const res = await api.werewolfDiscussionSay(player, msg.trim());
      if (res?.ok) { toast(`🗣️ 你发言：${msg.trim()}`); setMsg(''); }
      else toast('⚠️ ' + (res?.error || '发言失败'));
    } catch (e: any) { toast('⚠️ ' + (e.message || '发言失败')); }
    setBusy(false);
  };
  const approve = async () => {
    setBusy(true);
    try { await api.approvalApprove(store.werewolfSessionId); toast('✅ 已批准投票结束'); }
    catch (e: any) { toast('⚠️ ' + (e.message || '批准失败')); }
    setBusy(false);
  };
  const reject = async () => {
    setBusy(true);
    try { await api.approvalReject(store.werewolfSessionId); toast('❌ 已驳回，重新投票'); }
    catch (e: any) { toast('⚠️ ' + (e.message || '驳回失败')); }
    setBusy(false);
  };

  const targetChips = (
    <div className="ww-action-targets">
      {aliveOthers.map(n => (
        <button
          key={n}
          className={`ww-target-chip${target === n ? ' on' : ''}`}
          onClick={() => setTarget(n)}
        >{n}</button>
      ))}
      {aliveOthers.length === 0 && <span className="muted">无存活目标</span>}
    </div>
  );

  // 已出局猎人：任何阶段（未终局）可开枪
  if (isEliminated && role === '猎人' && !isGameOver) {
    return (
      <div className="ww-action-box">
        <div className="ww-action-title">🏹 猎人反击（你已被淘汰，可开枪带走一人）</div>
        {targetChips}
        <button className="btn btn-small btn-danger" disabled={!target || busy} onClick={shoot}>🏹 开枪</button>
      </div>
    );
  }
  if (isGameOver) return null;

  if (p === 'night') {
    const canKill = role === '狼人';
    const canCheck = role === '预言家';
    const canWitch = role === '女巫';
    if (!canKill && !canCheck && !canWitch) {
      return <div className="ww-action-box muted">🌙 你闭眼等待天亮（AI 角色正在行动）</div>;
    }
    // P-0802-I (G1-2)：女巫先获知被刀者（werewolf_witch_info 推送），再决定救不救/毒
    if (canWitch) {
      const victim = store.werewolfWitchVictim;
      return (
        <div className="ww-action-box">
          <div className="ww-action-title">🌙 女巫夜间（先获知被刀者，再决定）</div>
          {!victim ? (
            <div className="muted" style={{ fontSize: 12, padding: '2px 0' }}>
              🌙 你正在等待获知昨夜被刀者…（狼人行动后显示）
            </div>
          ) : (
            <>
              <div className="ww-witch-victim" style={{ marginBottom: 6, fontSize: 13 }}>
                💀 昨夜被刀者：<b>{victim}</b>
              </div>
              <div className="ww-action-btns">
                <button className="btn btn-small" disabled={busy} onClick={saveWitchVictim}>💊 救 {victim}（解药）</button>
                <button className="btn btn-small" disabled={busy} onClick={declineWitchSave}>🚫 不使用解药（保留解药）</button>
                <button className="btn btn-small" disabled={busy} onClick={declineWitchPoison}>☠️ 不用毒（保留毒药）</button>
              </div>
              <div style={{ marginTop: 6 }}>
                <div className="muted" style={{ fontSize: 11, marginBottom: 2 }}>☠️ 使用毒药（选择目标）：</div>
                {targetChips}
                <button className="btn btn-small btn-danger" disabled={!target || busy} onClick={() => act('poison')}>☠️ 毒药</button>
              </div>
            </>
          )}
        </div>
      );
    }
    return (
      <div className="ww-action-box">
        <div className="ww-action-title">🌙 夜间行动：{role}（选择目标）</div>
        {targetChips}
        <div className="ww-action-btns">
          {canKill && <button className="btn btn-small btn-danger" disabled={!target || busy} onClick={() => act('kill')}>🔪 刀杀</button>}
          {canCheck && <button className="btn btn-small" disabled={!target || busy} onClick={() => act('check')}>🔮 查验</button>}
        </div>
      </div>
    );
  }
  if (p === 'day_discussion') {
    return (
      <div className="ww-action-box">
        <div className="ww-action-title">☀️ 白天讨论（AI 自动发言，可插入你的推理）</div>
        <div className="ww-discussion">
          {store.werewolfDiscussion.slice(-15).map((t, i) => (
            isSilenceText(t.message)
              ? <div key={i} className="ww-disc-turn ww-disc-silence"><SilenceTurn /></div>
              : <div key={i} className="ww-disc-turn"><b>{t.speaker}</b>：{t.message}</div>
          ))}
          {store.werewolfDiscussion.length === 0 && <div className="muted">暂无发言。</div>}
        </div>
        <div style={{ display: 'flex', gap: 4, marginTop: 4 }}>
          <input
            className="input"
            style={{ flex: 1, fontSize: 12 }}
            placeholder="发表你的推理…"
            value={msg}
            onChange={e => setMsg(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') say(); }}
          />
          <button className="btn btn-small" disabled={busy || !msg.trim()} onClick={say}>💬 发言</button>
        </div>
      </div>
    );
  }
  if (p === 'day_vote') {
    return (
      <div className="ww-action-box">
        <div className="ww-action-title">🗳️ 投票：选择你怀疑的狼人</div>
        {targetChips}
        <div className="ww-action-btns">
          <button className="btn btn-small btn-primary" disabled={!target || busy} onClick={vote}>🗳️ 投票</button>
          {store.werewolfApproval === 'pending' && (
            <>
              <span className="muted" style={{ fontSize: 11 }}>⏳ 结算待审批</span>
              <button className="btn btn-small btn-primary" disabled={busy} onClick={approve}>✅ 批准</button>
              <button className="btn btn-small btn-danger" disabled={busy} onClick={reject}>❌ 驳回</button>
            </>
          )}
        </div>
      </div>
    );
  }
  return null;
}
