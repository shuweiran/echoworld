import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import { useAppStore } from '../../store/appStore';

interface SessionInfo {
  session_id?: string;
  id?: string;
  created_at?: string;
  updated_at?: string;
  round_count?: number;
  rounds?: number;
  message_count?: number;
  agent_names?: string[];
}

interface SessionMessages {
  session_id: string;
  messages: any[];
  total: number;
  round_logs: any[];
}

interface ScriptHistoryMessage {
  speaker?: string;
  message?: string;
  round?: string | number;
  system?: boolean;
}

export function HistoryPanel({ onClose }: { onClose: () => void }) {
  const store = useAppStore();
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedSession, setSelectedSession] = useState<string | null>(null);
  const [sessionMessages, setSessionMessages] = useState<SessionMessages | null>(null);
  const [loadingSession, setLoadingSession] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [scriptHistory, setScriptHistory] = useState<any | null>(null);
  const [loadingScriptHistory, setLoadingScriptHistory] = useState(false);

  useEffect(() => {
    loadSessions();
  }, []);

  // P-0819-N：一般模式已有历史抽屉也服务剧本杀本局复盘。
  // 剧本杀讨论不走 RouterService 历史表，改从同一 role_key 视角读取脱敏 status，
  // 复用历史抽屉的消息预览样式，避免再造一套「剧本历史」入口。
  useEffect(() => {
    if (store.mode !== 'script' || !store.currentPlayer) {
      setScriptHistory(null);
      return;
    }
    let alive = true;
    setLoadingScriptHistory(true);
    api.scriptStatus(store.currentPlayer, store.scriptRoleKey)
      .then(data => { if (alive) setScriptHistory(data); })
      .catch(() => { if (alive) setScriptHistory(null); })
      .finally(() => { if (alive) setLoadingScriptHistory(false); });
    return () => { alive = false; };
  }, [store.mode, store.currentPlayer, store.scriptRoleKey]);

  const loadSessions = async () => {
    setLoading(true);
    try {
      const data = await api.getHistorySessions();
      setSessions(data.sessions || []);
    } catch (e: any) {
      console.error('Failed to load sessions:', e);
    } finally {
      setLoading(false);
    }
  };

  const viewSession = async (sessionId: string) => {
    setSelectedSession(sessionId);
    setLoadingSession(true);
    setLoadError('');
    try {
      const data = await api.getHistorySessionMessages(sessionId);
      setSessionMessages(data);
    } catch (e: any) {
      setLoadError(e.message || '加载失败');
      setSessionMessages(null);
    } finally {
      setLoadingSession(false);
    }
  };

  const loadSession = async (sessionId: string) => {
    setLoadError('');
    try {
      const data = await api.loadHistorySession(sessionId);
      store.addSystemMsg(`已加载历史会话：${sessionId}（第${data.round ?? 0}轮，${data.agents?.length ?? 0}个角色）`);
      // Refresh state
      await store.loadState();
      await store.loadHistory();
      onClose();
    } catch (e: any) {
      setLoadError(e.message || '加载失败');
    }
  };

  const formatDate = (isoStr: string) => {
    if (!isoStr) return '';
    try {
      const d = new Date(isoStr);
      return d.toLocaleString('zh-CN', {
        month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit',
      });
    } catch {
      return isoStr.slice(0, 16);
    }
  };

  const currentSessionId = store.sessionId;
  // GameBridge 恢复剧本时会先 loadState 再回写 mode；期间 scriptSessionId 已存在，
  // 仅依赖 mode 会短暂误显示一般模式历史列表，导致剧本杀右栏联动失效。
  const isScriptMode = store.mode === 'script' || !!store.scriptSessionId;
  const scriptMessages: ScriptHistoryMessage[] = Array.isArray(scriptHistory?.discussion)
    ? scriptHistory.discussion
        .map((m: any) => ({
          speaker: String(m?.speaker || m?.name || '系统'),
          message: String(m?.message || m?.content || ''),
          round: m?.round,
          system: m?.speaker === 'system' || m?.role === 'system',
        }))
        .filter((m: ScriptHistoryMessage) => m.message)
    : [];

  return (
    <div className="history-panel">
      <div className="history-panel-header">
        <h3>{isScriptMode ? '📜 本局历史' : '📋 历史会话'}</h3>
        <button className="btn btn-small" onClick={onClose}>✕</button>
      </div>

      {loadError && (
        <div className="error-banner" style={{ padding: '8px 12px', fontSize: 12, color: '#ff5252', background: 'rgba(255,82,82,0.1)' }}>
          {loadError}
        </div>
      )}

      {isScriptMode ? (
        <div className="history-session-list">
          <div className="history-list-header">
            <span>{scriptHistory?.name || '剧本杀对局'}</span>
            <button
              className="btn btn-small"
              onClick={() => {
                setLoadingScriptHistory(true);
                api.scriptStatus(store.currentPlayer, store.scriptRoleKey)
                  .then(setScriptHistory)
                  .catch(() => setLoadError('本局历史加载失败'))
                  .finally(() => setLoadingScriptHistory(false));
              }}
              disabled={loadingScriptHistory}
            >{loadingScriptHistory ? '⟳' : '↻'}</button>
          </div>
          {loadingScriptHistory && !scriptHistory ? (
            <div className="history-empty">加载本局记录中...</div>
          ) : (
            <>
              <div className="history-session-meta" style={{ padding: '6px 10px', gap: 8 }}>
                <span>第 {scriptHistory?.round ?? 1} 轮</span>
                <span>{scriptHistory?.phase || '准备'}阶段</span>
                <span>{scriptMessages.length} 条发言</span>
                <span>线索 {Array.isArray(scriptHistory?.clues) ? scriptHistory.clues.length : 0}</span>
              </div>
              <div className="history-preview-messages">
                {scriptMessages.length === 0 ? (
                  <div className="history-empty">本局暂时没有可复盘的发言</div>
                ) : scriptMessages.slice(-40).map((msg, i) => (
                  <div key={`${msg.speaker}-${msg.round}-${i}`} className={`history-preview-msg ${msg.system ? 'role-system' : 'role-assistant'}`}>
                    <span className="history-preview-name">{msg.speaker}{msg.round ? ` · ${msg.round}` : ''}</span>
                    <span className="history-preview-content">{msg.message}</span>
                  </div>
                ))}
                {scriptMessages.length > 40 && <div className="history-preview-more">... 共 {scriptMessages.length} 条发言（显示最近40条）</div>}
              </div>
            </>
          )}
        </div>
      ) : (
      /* Session list */
      <div className="history-session-list">
        <div className="history-list-header">
          <span>会话列表</span>
          <button className="btn btn-small" onClick={loadSessions} disabled={loading}>
            {loading ? '⟳' : '↻'}
          </button>
        </div>

        {loading && sessions.length === 0 ? (
          <div className="history-empty">加载中...</div>
        ) : sessions.length === 0 ? (
          <div className="history-empty">暂无历史会话</div>
        ) : (
          sessions.map((s, i) => {
            // Backend returns the id as `id` (not `session_id`) — normalize for compatibility
            const sid: string = s.session_id || s.id || String(i);
            return (
              <div
                key={sid}
                className={`history-session-item ${selectedSession === sid ? 'selected' : ''} ${currentSessionId === sid ? 'current' : ''}`}
                onClick={() => viewSession(sid)}
              >
                <div className="history-session-id" title={sid}>
                  {sid.replace('roleplay_', '').replace('script_', '').replace('werewolf_', '')}
                </div>
                <div className="history-session-meta">
                  <span>{s.round_count ?? s.rounds ?? 0}轮</span>
                  <span>{s.message_count || 0}条</span>
                  <span>{s.agent_names?.length || 0}人</span>
                </div>
                <div className="history-session-date">{formatDate(s.updated_at || s.created_at || '')}</div>
                {currentSessionId === sid && (
                  <div className="history-session-current-badge">当前</div>
                )}
              </div>
            );
          })
        )}
      </div>
      )}

      {/* Message preview for selected session */}
      {!isScriptMode && selectedSession && (
        <div className="history-message-preview">
          <div className="history-preview-header">
            <span>消息预览</span>
            <button
              className="btn btn-small btn-primary"
              onClick={() => loadSession(selectedSession)}
              disabled={loadingSession}
            >
              加载此会话
            </button>
          </div>

          {loadingSession ? (
            <div className="history-empty">加载消息中...</div>
          ) : sessionMessages ? (
            <div className="history-preview-messages">
              {sessionMessages.messages.slice(-30).map((msg: any, i: number) => (
                <div key={i} className={`history-preview-msg role-${msg.role || 'system'}`}>
                  <span className="history-preview-name">{msg.name || msg.role}</span>
                  <span className="history-preview-content">
                    {msg.content?.length > 120 ? msg.content.slice(0, 120) + '…' : msg.content}
                  </span>
                </div>
              ))}
              {sessionMessages.messages.length > 30 && (
                <div className="history-preview-more">
                  ... 共 {sessionMessages.total} 条消息（显示最近30条）
                </div>
              )}
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}
