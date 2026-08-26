/**
 * ScriptInvestigationPanel.tsx — 搜证页替换（P-0816-I，ui-proto-v2，主区顶部新增 UI 块）
 *
 * 接入（决策 U6/U7/U13 + API-1/API-2 真实响应契约，P-0816-G）：
 *   - 行动条：GET /api/script/actions → {ok, phase, actions[], ap, ap_max}
 *     actions[] = {id:"ask|苏晚", type:ask|research|present, target, label, ap_cost, enabled, reason}
 *     （服务端权威生成；AP 不足/阶段不符 → enabled=false + reason；投票阶段 actions 为空 → 面板隐藏）
 *   - 地点卡片网格：status.locations / searched_locations / clues（公开+本人持有）派生——
 *     未搜=「🔍 点击搜证」可搜态；已搜=「✓ 已搜证」+ 线索数徽章（clueCountAtLocation）
 *   - 点击卡片 → POST /api/script/action {player, action_id:"research|<loc>"}（后端分派）：
 *       未搜过 → 委托 search 扣 AP（线索 ap_cost 之和），成功弹 VN 演出（新线索态）
 *       已搜过 → 回看 {replayed:true, clues:[...]} 不扣 AP（U7），弹 VN 演出（已记录态）
 *   - ask 行动 → 成功 toast reply；执行失败 → toast {error}（后端 {error} 文案直显）
 *   - 3s 轮询刷新行动建议（SSE 无专属事件；对齐 script_status 轮询兜底口径，D1）
 *
 * 本组件自包含（挂载期间自轮询 + 自执行 + 刷新 store.scriptState），不侵入既有组件逻辑。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { api } from '../../api/client';
import {
  clueCountAtLocation,
  actionEmoji,
  type ScriptClueLike,
} from './actionUtils';
// 阶段 D（P-0817-E）：VN 文本拼装已抽至共享层 utils/ui/vnText.ts
import { buildVnLines } from '../../utils/ui/vnText';
import { ScriptVnReveal } from './ScriptVnReveal';

export interface ScriptActionLike {
  id?: string;
  type?: string;
  target?: string;
  label?: string;
  ap_cost?: number;
  enabled?: boolean;
  reason?: string;
}

export interface ScriptActionsData {
  ok?: boolean;
  phase?: string;
  actions?: ScriptActionLike[];
  ap?: number;
  ap_max?: number;
  error?: string;
}

interface VnState {
  open: boolean;
  name: string;
  lines: string[];
  recorded: boolean;
}

export function ScriptInvestigationPanel({ scriptState, onStartDiscussion, busy: parentBusy = false }: {
  scriptState: any;
  onStartDiscussion?: () => void;
  busy?: boolean;
}) {
  const store = useAppStore();
  const currentPlayer = store.currentPlayer;
  const [actions, setActions] = useState<ScriptActionLike[]>([]);
  const [ap, setAp] = useState<number | null>(null);
  const [apMax, setApMax] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState('');
  const [vn, setVn] = useState<VnState>({ open: false, name: '', lines: [], recorded: false });
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const aliveRef = useRef(true);

  const showToast = useCallback((msg: string) => {
    setToast(msg);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(''), 2400);
  }, []);

  /** 刷新行动建议 + 对局状态（行动后立即刷新，不等轮询；对齐 ChatPage refreshScript 模式） */
  const refresh = useCallback(async (withStatus = false) => {
    try {
      // A role-select history resume can restore the player name before the
      // init response has repopulated scriptRoleKey.  The status endpoint is
      // authoritative for this player's role token; hydrate it before the
      // action request so the first search is authenticated.
      let playerKey = store.scriptRoleKey;
      if (currentPlayer) {
        const identity = await api.scriptStatus(currentPlayer);
        if (identity?.role_key) {
          const authoritativeKey = String(identity.role_key);
          if (authoritativeKey !== playerKey) store.setScriptRoleKey(authoritativeKey);
          playerKey = authoritativeKey;
        }
      }
      const acts = await api.scriptActions(currentPlayer, playerKey);
      if (aliveRef.current && acts && typeof acts === 'object') {
        setActions(Array.isArray(acts.actions) ? acts.actions : []);
        if (typeof acts.ap === 'number') setAp(acts.ap);
        if (typeof acts.ap_max === 'number') setApMax(acts.ap_max);
      }
      // 行动执行后顺带刷新对局状态（AP/足迹/线索即时生效）；周期轮询不重复拉 status
      //（ChatPage 已有 3s script_status 轮询兜底，此处避免双拉）
      if (withStatus) {
        const st = await api.scriptStatus(currentPlayer, playerKey);
        if (aliveRef.current && st && typeof st === 'object') {
          store.setScriptState(st);
          if (st.session_id) store.setScriptSessionId(st.session_id);
        }
      }
    } catch { /* 服务未就绪时忽略 */ }
  }, [currentPlayer, store]);

  // 3s 轮询行动建议（面板仅搜证阶段挂载；卸载清理）
  useEffect(() => {
    aliveRef.current = true;
    void refresh();
    const t = setInterval(() => void refresh(), 3000);
    return () => { aliveRef.current = false; clearInterval(t); };
  }, [refresh]);

  /** 执行行动（POST /api/script/action；错误 {error} 直显 toast，不造假交互） */
  const runAction = useCallback(async (actionId: string) => {
    if (busy || parentBusy || !actionId) return;
    // P-0816-P2 修复（P-0816-O 修复无效）：无角色身份 = boundCharacterName 为空（未绑定角色）时点搜证 → 直接提示，不发请求
    //（P-0816-O 曾用 store.scriptRoleKey 判断，但 role_key 由 status 开局即发放恒非空，故 toast 从未触发；
    //   真实「角色身份」信号是 boundCharacterName，localStorage 'boundCharacterName' 镜像，未绑定时为空）
    if (actionId.startsWith('research|') && !store.boundCharacterName) {
      showToast('需先分配角色（角色令牌）才能搜证');
      return;
    }
    setBusy(true);
    try {
      const res: any = await api.scriptAction(currentPlayer, actionId, store.scriptRoleKey);
      if (!res || typeof res !== 'object') { showToast('行动执行失败（无响应）'); return; }
      if (res.error) { showToast(String(res.error)); return; }
      const id = String(res.action_id || '');
      if (id.startsWith('research|')) {
        // 搜证/回看 → VN 演出（U13 前端拼装；回看态 recorded=true 不扣 AP）
        const loc = id.split('|')[1] || '';
      // 首次搜证时后端把公开线索放在 public_clues、私有线索放在 clues；两者都应
      // 进入同一场 VN 演出，否则只有公开线索的地点会出现「第一次没线索、第二次才有」假象。
      const rawClues = [
        ...(Array.isArray(res.clues) ? res.clues : []),
        ...(Array.isArray(res.public_clues) ? res.public_clues : []),
      ];
      const clues: ScriptClueLike[] = [...new Map(rawClues
        .filter(c => c && c.id != null)
        .map(c => [String(c.id), c] as const)).values()];
        setVn({
          open: true,
          name: scriptState?.your_role || currentPlayer,
          lines: buildVnLines(clues, loc, String(res.result || '')),
          recorded: res.replayed === true,
        });
      } else if (id.startsWith('ask|')) {
        const reply = String(res.reply || '');
        showToast(reply ? `${String(res.result || '')}：${reply}` : String(res.result || '行动完成'));
      } else if (id.startsWith('present|')) {
        showToast(String(res.result || '已出示线索'));
      } else if (res.result) {
        showToast(String(res.result));
      } else {
        showToast('行动完成');
      }
      await refresh(true);
    } catch (e: any) {
      showToast(e?.message ? String(e.message) : '行动执行失败');
    } finally {
      setBusy(false);
    }
  }, [busy, currentPlayer, refresh, showToast]);

  // 数据源（status 派生：地点 / 足迹 / 可见线索）
  const locations: string[] = Array.isArray(scriptState?.locations) ? scriptState.locations : [];
  const searched = new Set<string>(Array.isArray(scriptState?.searched_locations) ? scriptState.searched_locations : []);
  const clues: ScriptClueLike[] = Array.isArray(scriptState?.clues) ? scriptState.clues : [];
  const apDisplay = ap ?? scriptState?.ap ?? 0;
  const apMaxDisplay = apMax ?? scriptState?.ap_max ?? 0;
  // 行动条（enabled=false + reason 原样透出；AP 显示与后端响应联动）
  const actionBar = Array.isArray(actions) ? actions : [];
  const visible = actionBar.length > 0 || locations.length > 0;

  if (!visible) return null;

  return (
    <div className="proto-invest">
      {/* ── 阶段横幅（P-0816-U：对齐原型 main-head h1 + sub，视觉仅增） ── */}
      <div className="proto-invest-head">
        <h1 className="proto-invest-title">🔍 搜证阶段</h1>
        <p className="proto-invest-sub">
          已搜证 {searched.size}/{locations.length} 个地点 · 点击地点卡片搜证（消耗行动点），线索进入右侧证据库
        </p>
      </div>

      {/* ── 行动条（主区顶部，Ren'Py 风格，决策 U6/API-1） ── */}
      {actionBar.length > 0 && (
        <div className="proto-choice-bar">
          <div className="proto-choice-head">
            <span className="proto-choice-title">🎯 行动选择</span>
            <span className="proto-choice-ap">
              行动点 <b>{apDisplay}/{apMaxDisplay}</b> · 行动建议由服务端生成（GET /api/script/actions）
            </span>
          </div>
          <div className="proto-choice-list">
            {actionBar.map(a => (
              <button
                key={a.id}
                className={`proto-choice${a.enabled ? '' : ' disabled'}`}
                disabled={busy || a.enabled === false}
                onClick={() => runAction(a.id ?? '')}
                title={a.enabled === false ? (a.reason || '当前不可执行') : `${a.label} · 消耗 ${a.ap_cost ?? 0} 行动点`}
              >
                {actionEmoji(a.type)} {a.label}
                <small>· 消耗 {a.ap_cost ?? 0} AP</small>
                {a.enabled === false && <em className="proto-choice-reason">{a.reason || '不可用'}</em>}
              </button>
            ))}
          </div>
        </div>
      )}

      {/* ── 地点卡片网格（现有搜证入口卡片化；已搜=✓+线索数徽章） ── */}
      <div className="proto-sec-label">🗺️ 地点探索（点击地点卡片 → VN 发现演出）</div>
      {locations.length === 0 && (
        <div className="proto-loc-empty">暂无搜证地点（地图/剧本未生成时为空）</div>
      )}
      <div className="proto-loc-grid">
        {locations.map(loc => {
          const isSearched = searched.has(loc);
          const count = clueCountAtLocation(clues, loc);
          return (
            <button
              key={loc}
              className={`proto-loc${isSearched ? ' searched' : ' unsearched'}`}
              disabled={busy || parentBusy}
              onClick={() => runAction(`research|${loc}`)}
              title={isSearched
                ? `已搜证 ✓（${count} 条可见线索）· 点击回看（不扣行动点，U7）`
                : `未搜证 · 点击搜证（消耗行动点，线索 ap_cost 之和）`}
            >
              {isSearched && count > 0 && <span className="proto-loc-count">{count} 条线索</span>}
              <span className="proto-loc-name">📍 {loc}</span>
              {isSearched ? (
                <span className="proto-loc-state">已搜证 ✓</span>
              ) : (
                <span className="proto-loc-cta">🔍 点击搜证</span>
              )}
            </button>
          );
        })}
      </div>

      {/* 执行反馈（失败 toast {error} / ask reply） */}
      {toast && <div className="proto-toast">{toast}</div>}

      <div className="proto-phase-action">
        <span>搜证完成后再进入讨论；已搜地点可点击回看，不重复消耗行动点。</span>
        <button
          className="btn btn-smallall proto-next-phase"
          disabled={busy || parentBusy || !onStartDiscussion}
          onClick={onStartDiscussion}
        >🗣️ 结束搜证，进入讨论</button>
      </div>

      {/* VN 演出弹层（U13 前端拼装；已搜回看态 recorded=true） */}
      <ScriptVnReveal
        open={vn.open}
        name={vn.name || currentPlayer}
        lines={vn.lines}
        recorded={vn.recorded}
        onClose={() => setVn(v => ({ ...v, open: false }))}
      />
    </div>
  );
}
