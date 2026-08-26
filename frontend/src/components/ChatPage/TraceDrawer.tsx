/**
 * TraceDrawer.tsx — 🧭 逻辑链抽屉（阶段② P-0809-B，调研报告 docs/ui-api-survey.md §5 方案 C 落地）
 *
 * 展示「前端操作 → API 请求 → 后端逻辑（LLM 子调用）→ SSE 实时回推」的真实请求链路：
 * - 数据源：GET /api/debug/trace（列表）+ GET /api/debug/trace/{requestId}（详情）
 * - 3s 轮询（与对局状态一致）+ 手动刷新按钮
 * - 后端开关关闭（roleplay.debug.trace-enabled=false，端点 404）→ 显示「未开启」提示，不报错
 * - 列表行：时间 / 方法（色块）/ 路径 / 状态（2xx 绿 4xx 黄 5xx 红）/ 耗时 / 🧠LLM ⚡SSE 徽标
 * - 点击行展开详情：请求体摘要 + LLM 子调用时间线 + SSE 事件标记
 * 暗色游戏风样式与阶段①一致（复用 chip/status-pill/panel 体系 + global.css .trace-* 追加）。
 */
import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';

interface TraceListItem {
  request_id: string;
  ts: number;
  method: string;
  path: string;
  query?: string;
  session_id?: string;
  source?: string;
  status: number;
  ms: number;
  sse_count?: number;
  llm_count?: number;
}

interface TraceDetail {
  request_id: string;
  ts: number;
  method: string;
  path: string;
  query?: string;
  session_id?: string;
  source?: string;
  status: number;
  ms: number;
  body_summary?: string;
  error?: string;
  sse_events?: Array<{ event_type: string; session_id?: string; ts: number }>;
  llm_calls?: Array<{ model: string; ms: number; ts: number }>;
}

const METHOD_COLOR: Record<string, string> = {
  GET: 'var(--phase-investigation)', POST: 'var(--color-success)', PUT: 'var(--phase-discussion)', DELETE: 'var(--color-danger)', PATCH: 'var(--phase-default)', OPTIONS: 'var(--text-3)',
};

function statusColor(s: number): string {
  if (s >= 500) return 'var(--color-danger)';
  if (s >= 400) return 'var(--phase-discussion)';
  return 'var(--color-success)';
}

function fmtTime(ts: number): string {
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`;
}

/** 详情面板：请求体摘要 / LLM 子调用时间线 / SSE 事件标记 */
function TraceDetailView({ d }: { d: TraceDetail }) {
  const llmCalls = d.llm_calls || [];
  const sseEvents = d.sse_events || [];
  return (
    <div className="trace-detail">
      <div className="kv" style={{ fontSize: 12 }}>
        <span>链路 ID</span><strong style={{ wordBreak: 'break-all' }}>{d.request_id}</strong>
        <span>来源</span><strong>{d.source === 'frontend' ? '前端 X-Request-Id' : '服务端自动生成'}</strong>
        {d.session_id && (<><span>对局</span><strong>{d.session_id}</strong></>)}
        {d.query && (<><span>查询参数</span><strong style={{ wordBreak: 'break-all' }}>{d.query}</strong></>)}
      </div>

      {d.body_summary && (
        <div className="trace-block">
          <div className="trace-block-title">📦 请求体摘要</div>
          <pre className="trace-pre">{d.body_summary}</pre>
        </div>
      )}
      {d.error && (
        <div className="trace-block">
          <div className="trace-block-title" style={{ color: 'var(--color-danger)' }}>❌ 异常</div>
          <pre className="trace-pre" style={{ color: 'var(--color-danger)' }}>{d.error}</pre>
        </div>
      )}

      <div className="trace-block">
        <div className="trace-block-title">🧠 LLM 子调用（{llmCalls.length}）</div>
        {llmCalls.length === 0 && <div className="muted" style={{ fontSize: 12 }}>本次请求未触发 LLM 调用。</div>}
        {llmCalls.map((c, i) => (
          <div className="trace-llm-row" key={i}>
            <span className="trace-llm-model">{c.model}</span>
            <span className="trace-llm-ms">{c.ms} ms</span>
            <span className="trace-time">{fmtTime(c.ts)}</span>
          </div>
        ))}
      </div>

      <div className="trace-block">
        <div className="trace-block-title">⚡ SSE 事件（{sseEvents.length}）</div>
        {sseEvents.length === 0 && <div className="muted" style={{ fontSize: 12 }}>本次请求未同步触发 SSE 推送（后台线程触发的事件无法关联，属已知限制）。</div>}
        {sseEvents.map((e, i) => (
          <div className="trace-sse-row" key={i}>
            <span className="trace-sse-event">{e.event_type}</span>
            {e.session_id && <span className="trace-sse-session">{e.session_id}</span>}
            <span className="trace-time">{fmtTime(e.ts)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

export function TraceDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [entries, setEntries] = useState<TraceListItem[]>([]);
  const [enabled, setEnabled] = useState<boolean | null>(null); // null=未知（首拉前）
  const [expanded, setExpanded] = useState('');
  const [detail, setDetail] = useState<TraceDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    setBusy(true);
    try {
      const res = await api.traceList(50);
      if (res?.enabled === false) {
        setEnabled(false);
        setEntries([]);
        return;
      }
      setEnabled(true);
      setEntries(Array.isArray(res?.entries) ? res.entries : []);
    } catch {
      // 404 = 开关关闭；网络失败也按「未开启/不可用」提示，不报错
      setEnabled(false);
      setEntries([]);
    } finally {
      setBusy(false);
    }
  }, []);

  // 3s 轮询（与对局状态一致）；抽屉关闭时停止
  useEffect(() => {
    if (!open) return;
    refresh();
    const t = setInterval(refresh, 3000);
    return () => clearInterval(t);
  }, [open, refresh]);

  const expand = async (reqId: string) => {
    if (expanded === reqId) {
      setExpanded('');
      setDetail(null);
      return;
    }
    setExpanded(reqId);
    setDetailLoading(true);
    try {
      const d = await api.traceDetail(reqId);
      setDetail(d || null);
    } catch {
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  };

  return (
    <>
      {open && <div className="drawer-overlay" onClick={onClose} />}
      <aside className={`panel drawer trace-drawer ${open ? 'open' : ''}`}>
        <div className="panel-header">
          <h2 className="panel-title">🧭 逻辑链（后端 API 追踪）</h2>
          <button className="btn btn-smallall" onClick={onClose}>✕</button>
        </div>
        <div className="panel-body trace-body">
          <div className="trace-toolbar">
            <span className={`status-pill ${enabled ? 'good' : ''}`}>
              {enabled === null ? '…探测中' : enabled ? '● 追踪已开启' : '○ 追踪未开启'}
            </span>
            <button className="btn btn-small" disabled={busy} onClick={refresh}>
              {busy ? '…' : '🔄 刷新'}
            </button>
          </div>

          {enabled === false ? (
            <div className="trace-disabled">
              <div style={{ fontWeight: 700, marginBottom: 6 }}>🔕 后端逻辑链追踪未开启</div>
              <div style={{ fontSize: 12, lineHeight: 1.7, color: 'var(--text-2)' }}>
                配置项 <code>roleplay.debug.trace-enabled</code> 当前为 <code>false</code>，
                <code>GET /api/debug/trace</code> 返回 404，不影响任何既有功能。
                <br />开启方法：在 <code>application.yml</code> 设
                <code>roleplay.debug.trace-enabled: true</code> 并重启后端（纯调试功能，默认关闭）。
              </div>
            </div>
          ) : (
            <>
              <div className="trace-hint">
                展示「前端操作 → API 请求 → 后端逻辑（🧠 LLM 子调用）→ ⚡ SSE 回推」真实链路；
                每行可点击展开详情，3s 自动刷新。
              </div>
              <div className="trace-list">
                {entries.length === 0 && (
                  <div className="muted" style={{ fontSize: 12, padding: '10px 2px' }}>
                    暂无请求记录——发起任意对局操作（搜索/讨论/投票/发言…）后这里会实时出现链路。
                  </div>
                )}
                {entries.map(e => (
                  <div
                    key={e.request_id}
                    className={`trace-item${expanded === e.request_id ? ' open' : ''}`}
                    onClick={() => expand(e.request_id)}
                  >
                    <div className="trace-row">
                      <span className="trace-time">{fmtTime(e.ts)}</span>
                      <span className="trace-method" style={{ color: METHOD_COLOR[e.method] || 'var(--text-2)' }}>{e.method}</span>
                      <span className="trace-path" title={e.path}>{e.path}</span>
                      <span className="trace-status" style={{ color: statusColor(e.status) }}>{e.status || '—'}</span>
                      <span className="trace-ms">{e.ms}ms</span>
                      {(e.llm_count || 0) > 0 && <span className="trace-badge trace-badge-llm">🧠{e.llm_count}</span>}
                      {(e.sse_count || 0) > 0 && <span className="trace-badge trace-badge-sse">⚡{e.sse_count}</span>}
                    </div>
                    {expanded === e.request_id && (
                      <div className="trace-detail-wrap" onClick={ev => ev.stopPropagation()}>
                        {detailLoading ? (
                          <div className="muted" style={{ fontSize: 12, padding: 8 }}>加载中…</div>
                        ) : detail ? (
                          <TraceDetailView d={detail} />
                        ) : (
                          <div className="muted" style={{ fontSize: 12, padding: 8 }}>详情加载失败（链路可能已被环形缓冲淘汰）。</div>
                        )}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </aside>
    </>
  );
}
