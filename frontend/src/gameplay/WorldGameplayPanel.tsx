import { useCallback, useEffect, useMemo, useState } from 'react';
import type { SimAgent, SimSnapshot } from '../phaser/simulationData';
import { nearbyObjects, normalizeWorldObject, submitWorldAction, type GameplayView, type WorldAction, type WorldObjectView } from './worldGameplay';

export function WorldGameplayPanel({ actorName }: { actorName?: string }) {
  const [snapshot, setSnapshot] = useState<SimSnapshot>({});
  const [gameplay, setGameplay] = useState<GameplayView | null>(null);
  const [open, setOpen] = useState(true);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState('');

  const refresh = useCallback(async () => {
    if (!actorName) return;
    const [stateResponse, gameplayResponse] = await Promise.all([
      fetch('/api/simulation/state'),
      fetch(`/api/simulation/gameplay/${encodeURIComponent(actorName)}`),
    ]);
    if (!stateResponse.ok || !gameplayResponse.ok) throw new Error('游戏状态暂不可用');
    setSnapshot(await stateResponse.json() as SimSnapshot);
    setGameplay(await gameplayResponse.json() as GameplayView);
  }, [actorName]);

  useEffect(() => {
    if (!actorName) return;
    let alive = true;
    const run = () => void refresh().catch(error => { if (alive) setMessage(error.message); });
    run(); const timer = setInterval(run, 900);
    return () => { alive = false; clearInterval(timer); };
  }, [actorName, refresh]);

  const actor = useMemo(() => (snapshot.agents || []).find(agent => agent.agentName === actorName) as SimAgent | undefined,
    [snapshot.agents, actorName]);
  const objects = useMemo(() => (snapshot.worldObjects || []).map(normalizeWorldObject)
    .filter((value): value is WorldObjectView => value !== null), [snapshot.worldObjects]);
  const nearby = useMemo(() => actor ? nearbyObjects(objects, actor) : [], [objects, actor]);

  const act = async (action: WorldAction, targetId: string) => {
    if (!actorName || busy) return;
    setBusy(`${action}:${targetId}`); setMessage('');
    try {
      const result = await submitWorldAction(actorName, action, targetId, gameplay?.worldVersion);
      const code = String(result.code || result.status || '已提交');
      setMessage(`${actionLabel(action)}：${code}`);
      await refresh();
    } catch (error) { setMessage(error instanceof Error ? error.message : '交互失败'); }
    finally { setBusy(''); }
  };

  if (!actorName) return null;
  return <aside style={{ position: 'absolute', right: 12, top: 58, zIndex: 45, width: open ? 300 : 54,
    maxHeight: 'calc(100% - 76px)', overflow: 'hidden', borderRadius: 10,
    border: '1px solid rgba(148,163,184,.35)', background: 'rgba(15,23,42,.94)', color: '#e2e8f0',
    boxShadow: '0 12px 34px rgba(0,0,0,.38)' }}>
    <button onClick={() => setOpen(value => !value)} style={{ width: '100%', padding: '8px 10px', border: 0,
      background: 'rgba(30,41,59,.96)', color: '#fbbf24', cursor: 'pointer', textAlign: open ? 'left' : 'center' }}>
      {open ? '🎒 物品与状态' : '🎒'}
    </button>
    {open && <div style={{ padding: 10, overflowY: 'auto', maxHeight: 'calc(100vh - 190px)', fontSize: 12 }}>
      <section>
        <strong>状态</strong>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6, marginTop: 7 }}>
          {['health', 'stamina', 'focus', 'hunger', 'stress'].map(key => {
            const metric = gameplay?.metrics?.[key]; if (!metric) return null;
            const ratio = Math.max(0, Math.min(1, (metric.value - metric.min) / Math.max(1, metric.max - metric.min)));
            return <div key={key} title={`${metric.value}/${metric.max}${metric.unit}`}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}><span>{metric.label}</span><span>{Math.round(metric.value)}</span></div>
              <div style={{ height: 4, background: '#334155', borderRadius: 2 }}><div style={{ width: `${ratio * 100}%`, height: '100%', borderRadius: 2, background: key === 'health' ? '#ef4444' : key === 'stamina' ? '#22c55e' : '#38bdf8' }} /></div>
            </div>;
          })}
        </div>
      </section>
      <section style={{ marginTop: 12 }}>
        <strong>背包 {gameplay ? `${gameplay.inventory.length}/${gameplay.capacity}` : ''}</strong>
        <div style={{ marginTop: 6, display: 'grid', gap: 5 }}>
          {(gameplay?.inventory || []).length === 0 && <span style={{ color: '#94a3b8' }}>暂时是空的</span>}
          {(gameplay?.inventory || []).map(item => <div key={item.id} style={rowStyle}>
            <span>📦 {item.displayName}</span><span style={{ display: 'flex', gap: 4 }}>
              {item.supportedActions.includes('USE') && <MiniButton disabled={!!busy} onClick={() => act('USE', item.id)}>使用</MiniButton>}
              <MiniButton disabled={!!busy} onClick={() => act('PUT_DOWN', item.id)}>放下</MiniButton>
            </span>
          </div>)}
        </div>
      </section>
      <section style={{ marginTop: 12 }}>
        <strong>附近可交互物</strong>
        <div style={{ marginTop: 6, display: 'grid', gap: 5 }}>
          {nearby.length === 0 && <span style={{ color: '#94a3b8' }}>走近物件后会在这里出现</span>}
          {nearby.slice(0, 6).map(item => <div key={item.id} style={rowStyle}>
            <span title={item.description}>◈ {item.displayName}</span><span style={{ display: 'flex', gap: 4 }}>
              {item.supportedActions.filter(action => ['OPEN', 'CLOSE', 'PICK_UP', 'USE', 'SIT'].includes(action)).slice(0, 2)
                .map(action => <MiniButton key={action} disabled={!!busy} onClick={() => act(action, item.id)}>{actionLabel(action)}</MiniButton>)}
            </span>
          </div>)}
        </div>
      </section>
      {message && <div style={{ marginTop: 9, color: message.includes('失败') || message.includes('error') ? '#fca5a5' : '#86efac' }}>{message}</div>}
    </div>}
  </aside>;
}

const rowStyle = { display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 6,
  padding: '5px 7px', borderRadius: 6, background: 'rgba(51,65,85,.6)' } as const;
function MiniButton({ children, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button {...props} style={{ border: '1px solid #64748b', borderRadius: 5, padding: '2px 6px',
    background: '#1e293b', color: '#e2e8f0', cursor: props.disabled ? 'wait' : 'pointer', fontSize: 11 }}>{children}</button>;
}
function actionLabel(action: WorldAction) {
  return ({ LOOK_AT: '观察', OPEN: '打开', CLOSE: '关闭', PICK_UP: '拾取', PUT_DOWN: '放下', USE: '使用', SIT: '坐下' } as Record<WorldAction, string>)[action] || action;
}
