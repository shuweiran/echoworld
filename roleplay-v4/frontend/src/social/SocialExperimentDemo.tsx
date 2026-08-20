import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { SOCIAL_DEMO_MAP, SOCIAL_DEMO_ROLES, SOCIAL_INTERIORS, SOCIAL_TILE_LAYERS, type InteriorMap, type SocialRoom } from './socialExperimentMap';
import './socialExperimentDemo.css';
import './socialExperimentLayers.css';

interface DemoAgent {
  agentName: string;
  x: number;
  y: number;
  emotion?: string;
  currentMessage?: string;
  inConversation?: boolean;
  insideRoom?: string;
}

interface MapEvent { id: number; agentName: string; text: string; roomId?: string; kind: 'enter' | 'leave' | 'say'; }

interface SocialExperimentDemoProps {
  playerName?: string;
  characters?: Array<{ name: string; persona?: string; background?: string }>;
}

const fallbackPositions: Record<string, [number, number]> = {
  林默: [62, 52], 苏遥: [45, 45], 周野: [91, 55], 唐梨: [143, 49],
  顾城: [96, 77], 白芷: [36, 68], 程放: [58, 70], 沈言: [45, 39],
};

function pts(points: number[][]): string {
  return points.map(p => p.join(',')).join(' ');
}

function scaledAgent(a: DemoAgent): DemoAgent {
  // 后端模拟以 32px 瓦片坐标返回；演示地图扩大到 96×64，按 2 倍投影到大地图。
  return { ...a, x: Math.max(4, Math.min(156, 35 + (Number(a.x) / 32) * 3)), y: Math.max(4, Math.min(100, 18 + (Number(a.y) / 32) * 3)) };
}

function RoomCard({ room, active, occupants, onClick }: { room: SocialRoom; active: boolean; occupants: number; onClick: () => void }) {
  return (
    <g className={`social-room ${active ? 'active' : ''}`} role="button" tabIndex={0} onClick={onClick} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') onClick(); }}>
      <rect x={room.x} y={room.y} width={room.w} height={room.h} rx="1.5" />
      <rect className="social-room-door" x={room.door[0] - 1} y={room.door[1] - 0.5} width="2" height="1" />
      <text x={room.x + room.w / 2} y={room.y + room.h / 2 + 1} textAnchor="middle">{room.name}</text>
      {occupants > 0 && <g className="social-room-occupants"><circle cx={room.x + room.w - 1} cy={room.y + 1} r="1.6" /><text x={room.x + room.w - 1} y={room.y + 1.55} textAnchor="middle">{occupants}</text></g>}
      <text className="social-room-enter" x={room.x + room.w / 2} y={room.y - 1} textAnchor="middle">进入室内</text>
    </g>
  );
}

function InteriorView({ room, interior, occupants, onBack }: { room: SocialRoom; interior: InteriorMap; occupants: DemoAgent[]; onBack: () => void }) {
  return (
    <div className="social-interior-view">
      <div className="social-interior-head">
        <button className="btn2 btn2-ghost btn2-sm" onClick={onBack}>← 返回晨雾镇</button>
        <div><b>{interior.name}</b><span>{room.description}</span></div>
      </div>
      <svg className="social-interior-svg" viewBox={`0 0 ${interior.width} ${interior.height}`} role="img" aria-label={interior.name}>
        <rect width={interior.width} height={interior.height} fill="#6f574c" />
        <rect x="1" y="1" width={interior.width - 2} height={interior.height - 2} fill="#b98d68" stroke="#352821" strokeWidth="1" />
        {Array.from({ length: interior.width - 2 }).map((_, i) => <line key={`x${i}`} x1={i + 1} y1="1" x2={i + 1} y2={interior.height - 1} stroke="#8d694f" strokeOpacity=".25" strokeWidth=".15" />)}
        {interior.furniture.map((f, i) => <g key={i}><rect x={f.x} y={f.y} width={f.w} height={f.h} rx=".3" fill="#4e342a" stroke="#e2b58a" strokeWidth=".25" /><text x={f.x + f.w / 2} y={f.y + f.h / 2 + .3} textAnchor="middle" fill="#f8e7cf" fontSize=".9">{f.label}</text></g>)}
        {occupants.map((a, i) => <g className="social-interior-agent" key={a.agentName}><circle cx={4 + (i % 4) * 4} cy={12 - Math.floor(i / 4) * 2.5} r=".75" /><text x={4 + (i % 4) * 4} y={10.5 - Math.floor(i / 4) * 2.5} textAnchor="middle">{a.agentName}</text></g>)}
        <rect x={interior.exits[0].x - 1} y={interior.exits[0].y - .4} width="2" height=".8" fill="#6ee7b7" />
        <text x={interior.exits[0].x} y={interior.exits[0].y - 1} textAnchor="middle" fill="#c9ffe8" fontSize=".9">出口</text>
      </svg>
    </div>
  );
}

export function SocialExperimentDemo({ playerName = '林默', characters = SOCIAL_DEMO_ROLES }: SocialExperimentDemoProps) {
  const [agents, setAgents] = useState<DemoAgent[]>([]);
  const [conversations, setConversations] = useState<any[]>([]);
  const [room, setRoom] = useState<SocialRoom | null>(null);
  const [center, setCenter] = useState<[number, number]>([48, 32]);
  const [zoom, setZoom] = useState(1);
  const [selected, setSelected] = useState<string>(playerName);
  const [running, setRunning] = useState(false);
  const [displayAgents, setDisplayAgents] = useState<DemoAgent[]>([]);
  const [mapEvents, setMapEvents] = useState<MapEvent[]>([{ id: 1, agentName: '晨雾镇', text: '晨雾散开，角色开始在街道上寻找彼此。', kind: 'say' }]);
  const dragRef = useRef<{ x: number; y: number; cx: number; cy: number } | null>(null);
  const dragMovedRef = useRef(false);
  const displayAgentsRef = useRef<DemoAgent[]>([]);

  useEffect(() => {
    let alive = true;
    const poll = async () => {
      try {
        const r = await fetch('/api/simulation/state');
        const s = await r.json();
        if (!alive) return;
        setRunning(Boolean(s.running));
        setAgents(Array.isArray(s.agents) ? s.agents.map((a: DemoAgent) => scaledAgent(a)) : []);
        setConversations(Array.isArray(s.recentConversations) ? s.recentConversations.slice(-4).reverse() : []);
      } catch { if (alive) setRunning(false); }
    };
    void poll();
    const timer = window.setInterval(poll, 1600);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  const visible = 64 / zoom;
  const visibleH = 42 / zoom;
  const viewBox = `${center[0] - visible / 2} ${center[1] - visibleH / 2} ${visible} ${visibleH}`;
  const mapAgents = useMemo<DemoAgent[]>(() => {
    const live = new Map(agents.map(a => [a.agentName, a]));
    return characters.map((c, i) => live.get(c.name) ?? ({
      agentName: c.name,
      x: fallbackPositions[c.name]?.[0] ?? 26 + i * 7,
      y: fallbackPositions[c.name]?.[1] ?? 27 + (i % 3) * 8,
      inConversation: false,
      currentMessage: '正在等待进入公共空间。',
    }));
  }, [agents, characters]);
  useEffect(() => {
    setDisplayAgents(previous => mapAgents.map(a => {
      const old = previous.find(p => p.agentName === a.agentName);
      return old ? { ...a, x: old.x, y: old.y, insideRoom: old.insideRoom } : a;
    }));
  }, [mapAgents]);
  useEffect(() => { displayAgentsRef.current = displayAgents; }, [displayAgents]);

  // 演示层连续漫游：真实后端有坐标时使用其最新位置，后端暂时只返回少量角色时，
  // 其余角色也会在道路附近缓慢移动，避免 Demo 看起来像静态棋子。
  useEffect(() => {
    const timer = window.setInterval(() => {
      const now = Date.now() / 1800;
      setDisplayAgents(previous => previous.map((a, i) => {
        if (a.agentName === playerName) return a;
        const phase = now + i * 1.37;
        return {
          ...a,
          x: Math.max(4, Math.min(92, a.x + Math.cos(phase) * 0.16)),
          y: Math.max(4, Math.min(60, a.y + Math.sin(phase * .83) * 0.12)),
        };
      }));
    }, 120);
    return () => window.clearInterval(timer);
  }, [playerName]);

  // 室内是地图的第二层：AI 自己选择进屋、短暂停留、再离开。
  // 这一预览状态不覆盖后端权威坐标，只负责在后端没有完整多地图状态时展示空间行为。
  useEffect(() => {
    let turn = 0;
    const timer = window.setInterval(() => {
      const snapshot = displayAgentsRef.current;
      const target = snapshot.find((a, index) => a.agentName !== playerName && index === (turn % snapshot.length));
      if (!target) { turn++; return; }
      let emitted: MapEvent;
      let next: DemoAgent;
      if (target.insideRoom) {
        const oldRoom = SOCIAL_DEMO_MAP.rooms.find(r => r.id === target.insideRoom)!;
        emitted = { id: Date.now(), agentName: target.agentName, roomId: oldRoom.id, text: `${target.agentName} 从「${oldRoom.name}」走回街上。`, kind: 'leave' };
        next = { ...target, insideRoom: undefined, x: oldRoom.door[0], y: oldRoom.door[1] + 1.5, currentMessage: '刚离开室内，正在寻找下一位熟人。' };
      } else {
        const nextRoom = SOCIAL_DEMO_MAP.rooms[(turn + snapshot.indexOf(target) * 3) % SOCIAL_DEMO_MAP.rooms.length];
        emitted = { id: Date.now(), agentName: target.agentName, roomId: nextRoom.id, text: `${target.agentName} 进入「${nextRoom.name}」。`, kind: 'enter' };
        next = { ...target, insideRoom: nextRoom.id, x: nextRoom.door[0], y: nextRoom.door[1], currentMessage: `正在${nextRoom.name}里停留。`, inConversation: false };
      }
      setDisplayAgents(previous => previous.map(a => {
        return a.agentName === target.agentName ? next : a;
      }));
      setMapEvents(previous => [emitted, ...previous].slice(0, 7));
      turn++;
    }, 3400);
    return () => window.clearInterval(timer);
  }, [playerName]);

  const selectedAgent = displayAgents.find(a => a.agentName === selected);

  const focus = (x: number, y: number) => setCenter([Math.max(32, Math.min(128, x)), Math.max(21, Math.min(83, y))]);
  const focusPlayer = () => focus(selectedAgent?.x ?? 62, selectedAgent?.y ?? 52);
  const onMapPointerDown = (e: ReactPointerEvent<SVGSVGElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    dragRef.current = { x: e.clientX, y: e.clientY, cx: center[0], cy: center[1] };
    dragMovedRef.current = false;
  };
  const onMapPointerMove = (e: ReactPointerEvent<SVGSVGElement>) => {
    const start = dragRef.current;
    if (!start) return;
    const rect = e.currentTarget.getBoundingClientRect();
    const dx = e.clientX - start.x;
    const dy = e.clientY - start.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) dragMovedRef.current = true;
    setCenter([Math.max(32, Math.min(128, start.cx - (dx / rect.width) * visible)), Math.max(21, Math.min(83, start.cy - (dy / rect.height) * visibleH))]);
  };
  const onMapPointerUp = (e: ReactPointerEvent<SVGSVGElement>) => {
    if (dragRef.current) e.currentTarget.releasePointerCapture(e.pointerId);
    dragRef.current = null;
  };
  const openRoom = (next: SocialRoom) => {
    if (dragMovedRef.current) { dragMovedRef.current = false; return; }
    setRoom(next);
  };

  return (
    <div className="social-demo-shell">
      <div className="social-demo-topbar">
        <div><span className="social-demo-kicker">一般模式 · SOCIAL EXPERIMENT</span><h1>晨雾镇</h1><p>一个让 AI 自己走动、相遇、交谈、离场和形成关系的小型社会实验。</p></div>
        <div className="social-demo-status"><span className={running ? 'live-dot' : 'idle-dot'} />{running ? '后端模拟运行中' : '演示预览模式'}<small>160×104 三层瓦片地图 · {characters.length} 个角色</small></div>
      </div>
      {room ? <InteriorView room={room} interior={SOCIAL_INTERIORS[room.id]} occupants={displayAgents.filter(a => a.insideRoom === room.id)} onBack={() => setRoom(null)} /> : (
        <div className="social-demo-layout">
          <section className="social-map-panel">
            <div className="social-map-toolbar">
              <button className="btn2 btn2-sm" onClick={() => setZoom(z => Math.min(1.8, z + .2))}>＋</button>
              <span>{Math.round(zoom * 100)}% 视野</span>
              <button className="btn2 btn2-sm" onClick={() => setZoom(z => Math.max(.65, z - .2))}>－</button>
              <button className="btn2 btn2-ghost btn2-sm" onClick={() => { setZoom(1); setCenter([80, 52]); }}>全图</button>
              <button className="btn2 btn2-ghost btn2-sm" onClick={focusPlayer}>定位 {selected}</button>
              <span className="social-map-hint">①地表瓦片 · ②建筑/室内 · ③AI 消息层 · 点击建筑查看入屋角色</span>
            </div>
            <svg className="social-world-svg" viewBox={viewBox} role="img" aria-label="晨雾镇大地图" style={{ touchAction: 'none' }} onPointerDown={onMapPointerDown} onPointerMove={onMapPointerMove} onPointerUp={onMapPointerUp} onPointerCancel={onMapPointerUp}>
              <g className="social-layer-ground" aria-label="第一层：地表瓦片">{SOCIAL_TILE_LAYERS.ground.map(tile => <rect key={`${tile.x}-${tile.y}`} x={tile.x} y={tile.y} width="1.03" height="1.03" className={`social-tile social-tile-${tile.kind}`} />)}</g>
              <g className="social-layer-objects" aria-label="第二层：建筑与物件瓦片">{SOCIAL_TILE_LAYERS.objects.map(tile => <g key={`${tile.x}-${tile.y}`} className={`social-object social-object-${tile.object}`}><rect x={tile.x + .2} y={tile.y + .25} width=".6" height=".62" rx=".12" />{tile.object === 'tree' && <circle cx={tile.x + .5} cy={tile.y + .28} r=".48" />}</g>)}{SOCIAL_DEMO_MAP.roads.map((path, i) => <polyline key={i} points={pts(path)} className="social-road" />)}{SOCIAL_DEMO_MAP.landmarks.map(l => <g key={l.label}><circle cx={l.x} cy={l.y} r="2.2" className="social-landmark" /><text x={l.x + 2.5} y={l.y + .8} className="social-label">{l.icon} {l.label}</text></g>)}</g>
              {SOCIAL_DEMO_MAP.rooms.map(r => <RoomCard key={r.id} room={r} occupants={displayAgents.filter(a => a.insideRoom === r.id).length} active={selectedAgent ? selectedAgent.insideRoom === r.id || (Math.abs(selectedAgent.x - r.x) < r.w && Math.abs(selectedAgent.y - r.y) < r.h) : false} onClick={() => openRoom(r)} />)}
              <g className="social-layer-info" aria-label="第三层：AI 与消息">{displayAgents.filter(a => !a.insideRoom).map(a => <g key={a.agentName} className={`social-agent ${a.agentName === selected ? 'selected' : ''}`} onClick={() => { if (dragMovedRef.current) return; setSelected(a.agentName); focus(a.x, a.y); }}><circle cx={a.x} cy={a.y} r="1.35" /><text x={a.x} y={a.y - 2} textAnchor="middle">{a.agentName}</text>{(a.inConversation || mapEvents[0]?.agentName === a.agentName) && <g className="social-message-bubble"><rect x={a.x + 1.8} y={a.y - 4.8} width="13" height="3.4" rx=".7" /><text x={a.x + 2.4} y={a.y - 2.8}>{(a.currentMessage || mapEvents[0]?.text || '正在观察').slice(0, 15)}…</text></g>}{a.inConversation && <circle cx={a.x + 1.7} cy={a.y - 1.5} r=".65" className="social-chat-dot" />}</g>)}</g>
              <g className="social-layer-overlay" aria-label="树冠前景瓦片">{SOCIAL_TILE_LAYERS.overlay.map(tile => <circle key={`${tile.x}-${tile.y}`} cx={tile.x + .5} cy={tile.y + .5} r=".72" className="social-canopy" />)}</g>
            </svg>
            <div className="social-map-caption"><span>🟩 地表瓦片</span><span>🏠 建筑/室内层</span><span>💬 实时消息层</span><span>🌲 前景树冠遮罩</span></div>
          </section>
          <aside className="social-side-panel">
            <div className="social-mini-wrap"><div className="social-side-title">小地图 · 定位可视区域</div><svg className="social-mini-map" viewBox={`0 0 ${SOCIAL_DEMO_MAP.width} ${SOCIAL_DEMO_MAP.height}`} onClick={e => { const rect = e.currentTarget.getBoundingClientRect(); focus(((e.clientX - rect.left) / rect.width) * SOCIAL_DEMO_MAP.width, ((e.clientY - rect.top) / rect.height) * SOCIAL_DEMO_MAP.height); }}><rect width={SOCIAL_DEMO_MAP.width} height={SOCIAL_DEMO_MAP.height} fill="#14231d" />{SOCIAL_DEMO_MAP.roads.map((path, i) => <polyline key={i} points={pts(path)} className="social-road" />)}{SOCIAL_DEMO_MAP.rooms.map(r => <rect key={r.id} x={r.x} y={r.y} width={r.w} height={r.h} className="social-mini-room" />)}<rect x={center[0] - visible / 2} y={center[1] - visibleH / 2} width={visible} height={visibleH} className="social-viewport" />{displayAgents.map(a => <circle key={a.agentName} cx={a.x} cy={a.y} r=".9" className="social-mini-agent" />)}</svg><div className="social-mini-note">拖动主地图的视野，或点击小地图跳转</div></div>
            <div className="social-agent-list"><div className="social-side-title">镇上角色 <span>{displayAgents.length}</span></div>{displayAgents.map(a => <button key={a.agentName} className={`social-agent-row ${selected === a.agentName ? 'active' : ''}`} onClick={() => { setSelected(a.agentName); focus(a.x, a.y); }}><i className={a.inConversation ? 'talking' : ''} /> <span>{a.agentName}</span><small>{a.inConversation ? '正在交谈' : (a.emotion || '正在活动')}</small></button>)}</div>
            <div className="social-conversation-list"><div className="social-side-title">最近发生的对话</div>{conversations.length ? conversations.map((c, i) => <div className="social-conversation" key={i}><b>{c.group || c.pair || '偶遇'}</b><span>{Object.entries(c).filter(([k]) => !['group', 'pair', 'mode', 'tick', 'round', 'elapsedMs'].includes(k)).map(([, v]) => String(v)).join(' ')}</span></div>) : <div className="social-empty">角色还在寻找彼此……</div>}</div>
            <div className="social-event-feed"><div className="social-side-title">地图实时事件</div>{mapEvents.slice(0, 4).map(event => <div key={event.id} className={`social-event social-event-${event.kind}`}><i />{event.text}</div>)}</div>
            {selectedAgent && <div className="social-selected-card"><b>{selectedAgent.agentName}</b><span>{selectedAgent.currentMessage || '正在观察周围环境，等待下一次偶遇。'}</span></div>}
          </aside>
        </div>
      )}
    </div>
  );
}
