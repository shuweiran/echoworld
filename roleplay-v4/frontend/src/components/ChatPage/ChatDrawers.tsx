/**
 * ChatDrawers.tsx — 右侧抽屉集合（阶段① P-0809-A 拆分自 ChatPage.tsx）
 *
 * 职责：🎬 导演面板 / 📋 历史 / 🎛 主持人（DM）/ 💬 私聊 / 🎨 对局美术 /
 * 🧭 逻辑链（阶段② P-0809-B 后端 API 追踪，见 ./TraceDrawer.tsx）。各抽屉自包含本地状态与 API 调用，仅开关经 props 控制。
 */
import { useEffect, useState } from 'react';
import { useAppStore } from '../../store/appStore';
import { api } from '../../api/client';
import { HistoryPanel } from '../HistoryPanel/HistoryPanel';
import { ScriptDmPanel } from '../ScriptDmPanel';
import { TraceDrawer } from './TraceDrawer';
import type { TopbarDrawers } from './ChatTopbar';
import { trackModeName } from './chatUtils';

/** 🎬 导演面板：剧情目标 / 场景事实 / 当前轨道 / 系统状态 */
function DirectorDrawer({ onClose }: { onClose: () => void }) {
  const store = useAppStore();
  const [goalInput, setGoalInput] = useState('');
  const latestTrackConfig = store.trackHistory[store.trackHistory.length - 1];

  const addGoal = async () => {
    const text = goalInput.trim();
    if (!text) return;
    await store.setGoals([...store.goals, text]);
    setGoalInput('');
  };
  const removeGoal = async (index: number) => {
    await store.setGoals(store.goals.filter((_, i) => i !== index));
  };

  return (
    <>
      <div className="drawer-overlay" onClick={onClose} />
      <aside className="panel drawer open">
        <div className="panel-header">
          <h2 className="panel-title">🎬 导演面板</h2>
          <button className="btn btn-smallall" onClick={onClose}>✕</button>
        </div>
        <div className="panel-body">
          <div className="section">
            <details open>
              <summary className="label">剧情目标</summary>
              {store.goals.length === 0 ? <div className="muted" style={{ fontSize: 12, marginTop: 8 }}>暂无目标，系统会自由推进。</div> : store.goals.map((goal, i) => (
                <div className="goal-item" key={`${goal}-${i}`}>
                  <span>{goal}</span>
                  <button className="btn btn-smallall btn-icon" onClick={() => removeGoal(i)}>×</button>
                </div>
              ))}
              <div className="form-row" style={{ marginTop: 8 }}>
                <input style={{ flex: 1 }} value={goalInput} onChange={e => setGoalInput(e.target.value)} onKeyDown={e => e.key === 'Enter' && addGoal()} placeholder="添加剧情目标" />
                <button className="btn" onClick={addGoal}>添加</button>
              </div>
            </details>
          </div>

          <div className="section">
            <details open>
              <summary className="label">场景事实</summary>
              <div className="card" style={{ fontSize: 12, lineHeight: 1.55, color: 'var(--text-2)', marginTop: 8 }}>{store.sceneDescription || '暂无场景描述'}</div>
            </details>
          </div>

          <div className="section">
            <details>
              <summary className="label">当前轨道</summary>
              <div style={{ marginTop: 8 }}>
              {!latestTrackConfig ? <div className="muted" style={{ fontSize: 12 }}>推进一轮后显示轨道分配。</div> : latestTrackConfig.tracks.map(track => (
                <div className="card" key={track.id} style={{ marginBottom: 6 }}>
                  <div className="section-row">
                    <strong>{track.label || track.id}</strong>
                    <span className="status-pill">{trackModeName(track.mode)}</span>
                  </div>
                  <div className="chip-list">
                    {track.agents.map(name => <span className="chip" key={name}>{name} · {track.agent_actions[name] || 'active'}</span>)}
                  </div>
                </div>
              ))}
              </div>
            </details>
          </div>

          <div className="section">
            <details>
              <summary className="label">系统状态</summary>
              <div className="kv" style={{ marginTop: 8 }}>
                <span>会话</span><strong>{store.sessionId || '未创建'}</strong>
                <span>回合</span><strong>{store.currentRound}</strong>
                <span>角色</span><strong>{store.agents.length}</strong>
              </div>
            </details>
          </div>
        </div>
      </aside>
    </>
  );
}

/** 💬 私聊抽屉（剧本杀模式）：目标 chips / 历史加载 / 3s 轮询兜底 / 气泡渲染 */
function PrivateChatDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const store = useAppStore();
  const [target, setTarget] = useState('');
  const [input, setInput] = useState('');
  const [msgs, setMsgs] = useState<any[]>([]);
  const [busy, setBusy] = useState(false);

  const players = () =>
    (Array.isArray(store.scriptState?.players) ? store.scriptState.players : [])
      .filter((p: string) => p !== store.currentPlayer);

  const send = async () => {
    const text = input.trim();
    if (!text || !target || busy) return;
    setBusy(true);
    try {
      const res = await api.scriptPrivateSay(
        store.currentPlayer, target, text, store.scriptRoleKey || undefined);
      if (res?.history) setMsgs(res.history);
      else setMsgs([...msgs, { from: store.currentPlayer, to: target, content: text }]);
      setInput('');
    } catch (e: any) {
      setMsgs([...msgs, { from: '系统', to: target, content: '发送失败: ' + (e?.message || '') }]);
    } finally {
      setBusy(false);
    }
  };

  const switchTarget = async (t: string) => {
    setTarget(t);
    setMsgs([]);
    if (!t) return;
    try {
      const res = await api.scriptPrivateHistory(store.currentPlayer, t, store.scriptRoleKey || undefined);
      if (res?.history) setMsgs(res.history);
    } catch { /* ignore */ }
  };

  // P-0805-C（私聊实时）：抽屉打开且已选目标时 3s 轮询私聊历史（SSE script_private 推送作实时通道）
  useEffect(() => {
    if (!open || !target || store.mode !== 'script') return;
    const poll = async () => {
      try {
        const res = await api.scriptPrivateHistory(store.currentPlayer, target, store.scriptRoleKey || undefined);
        if (res?.history && Array.isArray(res.history)) setMsgs(res.history);
      } catch { /* ignore */ }
    };
    poll();
    const t = setInterval(poll, 3000);
    return () => clearInterval(t);
  }, [open, target, store.mode]);

  return (
    <>
      {open && <div className="drawer-overlay" onClick={onClose} />}
      <aside className={`panel drawer ${open ? 'open' : ''}`}>
        <div className="panel-header">
          <h2 className="panel-title">💬 私聊（一对一密谈）</h2>
          <button className="btn btn-smallall" onClick={onClose}>✕</button>
        </div>
        <div className="panel-body" style={{ padding: '12px', display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div className="chip-list">
            {players().length === 0 && <span className="muted" style={{ fontSize: 12 }}>本局暂无其他玩家。</span>}
            {players().map((p: string) => (
              <button
                key={p}
                className={`btn btn-small ${target === p ? 'btn-primary' : ''}`}
                onClick={() => switchTarget(p)}
              >{p}</button>
            ))}
          </div>
          {target && (
            <>
              <div style={{ fontSize: 11, color: 'var(--text-dim)' }}>
                与 <strong>{target}</strong> 密谈 —— 可套话/结盟/传递情报，对方角色会以本人身份回应（不会轻易认罪）
              </div>
              <div
                className="private-msgs"
                style={{ flex: 1, overflowY: 'auto', background: 'var(--bg-dim, var(--panel-2))', borderRadius: 8, padding: 8, fontSize: 13, minHeight: 200, maxHeight: 320 }}
              >
                {msgs.length === 0 && <div className="muted" style={{ fontSize: 12 }}>还没有私聊记录，发一句话开始。</div>}
                {msgs.map((m: any, i: number) => (
                  <div key={i} style={{ marginBottom: 6, textAlign: m.from === store.currentPlayer ? 'right' : 'left' }}>
                    <div style={{ fontWeight: 700, fontSize: 11 }}>{m.from}</div>
                    <div
                      style={{
                        display: 'inline-block', background: m.from === store.currentPlayer ? 'var(--phase-investigation)' : 'var(--panel-3)',
                        borderRadius: 8, padding: '4px 8px', maxWidth: '80%', whiteSpace: 'pre-wrap',
                      }}
                    >{m.content}</div>
                  </div>
                ))}
              </div>
              <div style={{ display: 'flex', gap: 6 }}>
                <input
                  className="input"
                  style={{ flex: 1, fontSize: 13 }}
                  placeholder={`对 ${target} 说...`}
                  value={input}
                  onChange={e => setInput(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && send()}
                />
                <button className="btn btn-primary" disabled={!input.trim() || busy} onClick={send}>
                  {busy ? '…' : '发送'}
                </button>
              </div>
            </>
          )}
        </div>
      </aside>
    </>
  );
}

/** 🎨 对局美术抽屉（剧本杀模式）：image_spec 合成 → 逐个生成 → 展示（provider 可配/离线占位） */
function ImagesDrawer({ open, onClose }: { open: boolean; onClose: () => void }) {
  const store = useAppStore();
  const [specs, setSpecs] = useState<any[]>([]);
  const [results, setResults] = useState<Record<string, any>>({});
  const [busy, setBusy] = useState(false);

  const genImages = async () => {
    if (busy) return;
    setBusy(true);
    setResults({});
    try {
      const scriptState = store.scriptState;
      const spec = await api.imageSpec({ theme: scriptState?.name || scriptState?.theme || '', script: scriptState?.script_schema });
      const imgs = (Array.isArray(spec?.images) ? spec.images : []).slice(0, 6);
      setSpecs(imgs);
      for (const u of imgs) {
        const assetType = u.kind === 'character' ? 'ROLE_PORTRAIT' : u.kind === 'scene' ? 'SCENE_BACKGROUND' : u.kind === 'clue' ? 'CLUE_IMAGE' : 'ROLE_PORTRAIT';
        try {
          const r = await api.imageGenerate({ unit: u, name: u.name, asset_type: assetType });
          setResults(prev => ({ ...prev, [u.id]: r }));
        } catch (e: any) {
          setResults(prev => ({ ...prev, [u.id]: { error: e?.message || '生成失败' } }));
        }
      }
    } catch (e: any) {
      alert('生成美术失败：' + (e?.message || ''));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {open && <div className="drawer-overlay" onClick={onClose} />}
      <aside className={`panel drawer ${open ? 'open' : ''}`}>
        <div className="panel-header">
          <h2 className="panel-title">🎨 对局美术（AI 生图）</h2>
          <button className="btn btn-smallall" onClick={onClose}>✕</button>
        </div>
        <div className="panel-body" style={{ padding: '12px' }}>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginBottom: 8 }}>
            由剧本 schema 生成角色立绘 / 场景氛围 / 线索物证图；未配置生图 API 时输出占位图（离线可用）。
          </div>
          <button className="btn btn-primary" disabled={busy} onClick={genImages}>
            {busy ? '⏳ 生成中…' : '✨ 生成本局美术'}
          </button>
          {specs.length > 0 && (
            <div style={{ marginTop: 12, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              {specs.map(u => {
                const r = results[u.id];
                return (
                  <div key={u.id} style={{ background: 'var(--bg-dim, var(--panel-2))', borderRadius: 8, padding: 6 }}>
                    <div style={{ fontSize: 11, fontWeight: 700, marginBottom: 4 }}>
                      {u.kind === 'character' ? '👤' : u.kind === 'scene' ? '🖼️' : u.kind === 'clue' ? '🔎' : '🧱'} {u.name}
                    </div>
                    {r?.url ? (
                      <img src={r.url} alt={u.name} style={{ width: '100%', borderRadius: 6, display: 'block' }} />
                    ) : r?.error ? (
                      <div style={{ fontSize: 11, color: 'var(--color-danger)' }}>❌ {r.error}</div>
                    ) : (
                      <div style={{ fontSize: 11, color: 'var(--text-dim)' }}>{busy ? '生成中…' : '待生成'}</div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </aside>
    </>
  );
}

/** 🧭 逻辑链抽屉（阶段② P-0809-B 落地）：后端 API 追踪面板见 ./TraceDrawer.tsx（调研报告 §5 方案 C） */

export interface ChatDrawersProps {
  drawers: TopbarDrawers;
}

/** 全部右侧抽屉集合（历史 / 导演 / DM / 私聊 / 美术 / 逻辑链） */
export function ChatDrawers({ drawers }: ChatDrawersProps) {
  const store = useAppStore();

  return (
    <>
      {/* 📋 历史 */}
      {drawers.showHistory && <div className="drawer-overlay" onClick={() => drawers.setShowHistory(false)} />}
      <aside className={`panel drawer drawer-history ${drawers.showHistory ? 'open' : ''}`}>
        <HistoryPanel onClose={() => drawers.setShowHistory(false)} />
      </aside>

      {/* 🎬 导演（仅挂载时渲染内容；free/director 模式语义） */}
      {drawers.showDirector && <DirectorDrawer onClose={() => drawers.setShowDirector(false)} />}

      {/* 🎛 主持人（DM）抽屉 —— 剧本杀模式专属 */}
      {drawers.showDm && <div className="drawer-overlay" onClick={() => drawers.setShowDm(false)} />}
      <aside className={`panel drawer ${drawers.showDm ? 'open' : ''}`}>
        <div className="panel-body" style={{ padding: '12px' }}>
          <ScriptDmPanel sessionId={store.scriptState?.session_id || ''} onClose={() => drawers.setShowDm(false)} />
        </div>
      </aside>

      {/* 💬 私聊 */}
      <PrivateChatDrawer open={drawers.showPrivate} onClose={() => drawers.setShowPrivate(false)} />

      {/* 🎨 对局美术 */}
      <ImagesDrawer open={drawers.showImages} onClose={() => drawers.setShowImages(false)} />

      {/* 🧭 逻辑链（阶段② P-0809-B：后端 API 追踪真实链路，3s 轮询） */}
      <TraceDrawer open={drawers.showChain} onClose={() => drawers.setShowChain(false)} />
    </>
  );
}
