/**
 * GalHistoryDrawer.tsx — 右上「📜 历史记录」抽屉（P-0810-08）
 *
 * 两块：
 *  1) 消息列表 —— GET /api/history（limit 200）按时间倒序/正序展示，
 *     每条显示：类型（玩家/AI/旁白/系统）+ 说话者 + 时间 + 内容；
 *  2) 回滚 —— 每条 AI/玩家消息带「回滚到此」→ POST /api/round/rollback
 *     {round: 消息 round_number, session_id}（后端已查证：RoundController.rollback
 *     → RouterService.rollbackToRound 按轮截断会话记忆；轮内消息一并回滚，粒度=轮）。
 *
 * P-0814-C：移除 TTS 预留区（主人指示「把下面的 tts 预留删掉」）——ttsReserve.ts 已删除。
 *
 * 已知限制（后端契约）：GET /api/history 无 session_id 参数，读默认单例 router
 * （startScene 起局的一般会话即初始化在默认单例上，天然命中；/api/init 起局的
 * 会话后端也会同步初始化默认单例，取最近一次会话消息）。回滚端点支持 session_id
 * 定向，已透传。
 */
import { useCallback, useEffect, useState } from 'react';
import { useGalStore } from './GalStore';
import { api } from '../api/client';
import { useAppStore } from '../store/appStore';

interface HistoryMsg {
  role: string;
  name: string;
  content: string;
  timestamp?: string;
  round_number?: number;
}

const ROLE_LABEL: Record<string, string> = {
  user: '玩家',
  agent: 'AI',
  arbiter: '旁白',
  system: '系统',
};

/**
 * P-0814-G：玩家角色发言标注 —— 后端把玩家以自己角色身份说的话存为 role=agent + name=玩家名
 * （P0-2 speakerIsAgent 路径，speaker='me'/玩家角色名），列表里应标为「玩家」而非「AI」。
 * 'me' 是玩家保留名（P-0811-G 去除 AI 侧 'me' 兜底），恒归玩家。
 */
function isPlayerHistoryMsg(m: HistoryMsg, playerName: string): boolean {
  return m.role === 'user'
    || (m.role === 'agent' && !!m.name
      && (m.name === 'me' || !!(playerName && m.name === playerName)));
}

function fmtTime(ts?: string): string {
  if (!ts) return '';
  const d = new Date(ts);
  if (isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

interface GalHistoryDrawerProps {
  open: boolean;
  onClose: () => void;
  /** 当前一般会话 session_id（回滚透传；历史读取后端无 session 参数，走默认单例） */
  sessionId?: string;
}

export function GalHistoryDrawer({ open, onClose, sessionId }: GalHistoryDrawerProps) {
  const [messages, setMessages] = useState<HistoryMsg[]>([]);
  // P-0814-G：默认正序（真实发生顺序：玩家在前、AI 回应紧跟其后，符合主人预期）；
  // 「⬇ 倒序 / ⬆ 正序」按钮可切换。
  const [desc, setDesc] = useState(false); // true=倒序（新在前）false=正序
  const [loading, setLoading] = useState(false);
  const [rollbackBusy, setRollbackBusy] = useState(false);
  const [rollbackMsg, setRollbackMsg] = useState('');
  const liveGameType = useGalStore(s => s.liveGameType);
  const liveGeneralMode = useGalStore(s => s.liveGeneralMode);
  // P-0814-G：玩家角色名（历史里玩家发言以角色名/'me' 存储，用于正确标注「玩家」）
  const livePlayerName = useGalStore(s => s.livePlayerName);

  const refresh = useCallback(async () => {
    setLoading(true);
    setRollbackMsg('');
    try {
      // P-0810-21：带 session_id 定向拉取（后端已支持；旧后端忽略该参数读默认单例，向前兼容）
      const data: any = await api.getHistory({
        limit: '200',
        ...(sessionId ? { session_id: sessionId } : {}),
      });
      const list: HistoryMsg[] = Array.isArray(data) ? data : (data?.messages || []);
      // 过滤空内容，按时间排序
      const filled = list.filter(m => m && m.content);
      filled.sort((a, b) => {
        const ta = a.timestamp ? new Date(a.timestamp).getTime() : 0;
        const tb = b.timestamp ? new Date(b.timestamp).getTime() : 0;
        return tb - ta;
      });
      setMessages(filled);
    } catch {
      setMessages([]);
      setRollbackMsg('历史加载失败（后端不可达）');
    } finally {
      setLoading(false);
    }
  }, [sessionId]);

  // 打开时加载一次；关闭时清空
  useEffect(() => {
    if (!open) { setMessages([]); return; }
    void refresh();
  }, [open, refresh]);

  /** 回滚到某条消息所在轮次（后端按轮截断：N 轮及之前保留，之后移除） */
  const doRollback = async (m: HistoryMsg) => {
    const round = m.round_number ?? 0;
    if (round <= 0) return;
    if (!confirm(`回滚到第 ${round} 轮结束后的状态？\n第 ${round} 轮及之前的消息保留，之后的全部移除（后端按轮截断）。`)) return;
    setRollbackBusy(true);
    setRollbackMsg('');
    try {
      // 后端已查证：POST /api/round/rollback {round, session_id} → rollbackToRound
      const res: any = await api.rollback(round, sessionId);
      await refresh();
      const st = res?.status ? String(res.status) : '';
      setRollbackMsg(st
        ? (st.includes('无效') ? `⚠️ ${st}（无更早轮次可截断）` : `✅ ${st}（会话记忆已截断）`)
        : `✅ 已回滚到第 ${round} 轮（会话记忆已截断）`);
      // 同步刷新对局状态（agents/round/scene 等回写 appStore 供经典视图使用）
      try {
        const st2: any = await api.getState(sessionId);
        useAppStore.setState({
          currentRound: Number(st2?.round ?? 0),
          agents: Array.isArray(st2?.agents) ? st2.agents : [],
          mode: st2?.mode || 'free',
          sceneDescription: st2?.scene || '',
        });
      } catch { /* 状态刷新失败不阻塞 */ }
    } catch (e: any) {
      setRollbackMsg(`✕ 回滚失败：${e?.message || '未知错误'}`);
    } finally {
      setRollbackBusy(false);
    }
  };

  if (!open) return null;

  const shown = desc ? messages : [...messages].reverse();
  // 会话标题（回滚区/顶栏用）：mode 中文标签
  const modeCn = (() => {
    if (liveGameType !== 'general') return '';
    const map: Record<string, string> = { free: '自由', protagonist: '主角', multi_track: '多轨', director: '导演' };
    const m = liveGeneralMode;
    return m && map[m] ? `一般·${map[m]}` : '一般模式';
  })();

  return (
    <div className="galg-drawer-mask" onClick={onClose}>
      <div className="galg-drawer galg-history" onClick={e => e.stopPropagation()}>
        <div className="galg-drawer-head">
          <span className="galg-drawer-title">📜 历史记录{modeCn ? ` · ${modeCn}` : ''}</span>
          <div className="galg-drawer-actions">
            <button
              className={`galg-sort-btn${desc ? ' active' : ''}`}
              onClick={() => setDesc(v => !v)}
              title="切换时间排序"
            >
              {desc ? '⬇ 倒序' : '⬆ 正序'}
            </button>
            <button className="galg-drawer-close" onClick={onClose}>✕</button>
          </div>
        </div>

        {rollbackMsg && <div className="galg-rollback-msg">{rollbackMsg}</div>}

        {/* ── 消息列表 ── */}
        <div className="galg-msg-list">
          {loading && <div className="galg-drawer-empty">加载中…</div>}
          {!loading && shown.length === 0 && (
            <div className="galg-drawer-empty">
              暂无消息记录
              <div className="galg-drawer-empty-sub">对话开始后这里会按时间列出玩家 / AI / 旁白消息</div>
            </div>
          )}
          {!loading && shown.map((m, i) => {
            const round = m.round_number ?? 0;
            const isPlayer = isPlayerHistoryMsg(m, livePlayerName || '');
            const canRollback = round > 0 && (m.role === 'agent' || m.role === 'user');
            const label = isPlayer ? '玩家' : (ROLE_LABEL[m.role] || m.role);
            // 玩家消息复用 user 样式（金色玩家条/标签）
            const styleRole = isPlayer ? 'user' : (m.role || 'other');
            return (
              <div key={i} className={`galg-msg-row galg-msg-${styleRole}`}>
                <div className="galg-msg-meta">
                  <span className={`galg-msg-type galg-type-${styleRole}`}>{label}</span>
                  <span className="galg-msg-name">{m.name || ''}</span>
                  <span className="galg-msg-time">{fmtTime(m.timestamp)}</span>
                  {round > 0 && <span className="galg-msg-round">R{round}</span>}
                  {canRollback && (
                    <button
                      className="galg-rollback-btn"
                      disabled={rollbackBusy}
                      onClick={() => void doRollback(m)}
                      title={`回滚到第 ${round} 轮结束后的状态（第 ${round} 轮及之前保留，之后移除）`}
                    >
                      回滚到此
                    </button>
                  )}
                </div>
                <div className="galg-msg-content">{m.content}</div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
