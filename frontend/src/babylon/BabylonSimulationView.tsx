/**
 * EchoWorld 3D 表现层：Java 保持位置、碰撞、听觉与交互的唯一权威；
 * Babylon 只负责快照插值、场景绘制、镜头与输入意图上报。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArcRotateCamera, Color3, DirectionalLight, Engine, HemisphericLight,
  Mesh, MeshBuilder, PointerEventTypes, Scene, StandardMaterial,
  TransformNode, Vector3,
} from '@babylonjs/core';
import type { ScriptMap } from '../phaser/mapData';
import type { SimAgent, SimObstacle, SimSnapshot } from '../phaser/simulationData';
import {
  disposePrivateMmdAvatar,
  loadPrivateMmdAvatar,
  PRIVATE_MMD_MODEL_LABEL,
  updatePrivateMmdAvatar,
} from './privateMmdAvatar';
import type { PrivateMmdAvatar } from './privateMmdAvatar';

export interface BabylonSimulationViewProps {
  map?: ScriptMap;
  height?: number | string;
  playerName?: string;
}

type MotionSemantic = 'idle' | 'walk' | 'run' | 'talk';

interface AgentVisual {
  root: TransformNode;
  body: Mesh;
  target: Vector3;
  velocity: Vector3;
  heading: number;
  lastSnapshotAt: number;
  phase: number;
  semantic: MotionSemantic;
  agent: SimAgent;
  avatar?: PrivateMmdAvatar;
}

interface WorldProjection {
  sceneWidth: number;
  sceneDepth: number;
  scaleX: number;
  scaleZ: number;
}

const TILE_METERS = 2;
const EXTRAPOLATE_MS = 150;
const MOVE_INTERVAL_MS = 120;

function makeMaterial(scene: Scene, name: string, color: string, alpha = 1) {
  const m = new StandardMaterial(name, scene);
  m.diffuseColor = Color3.FromHexString(color);
  m.specularColor = new Color3(0.08, 0.08, 0.08);
  m.alpha = alpha;
  return m;
}

function obstacleColor(type: string): string {
  if (type === 'WATER' || type === 'FOUNTAIN') return '#2563a6';
  if (type === 'TREE' || type === 'BUSH') return '#166534';
  if (type === 'ROCK') return '#78716c';
  if (type === 'TABLE' || type === 'BENCH') return '#92400e';
  if (type === 'LAMP') return '#fbbf24';
  return '#334155';
}

function projectionOf(snapshot: SimSnapshot, map?: ScriptMap): WorldProjection {
  const worldWidth = Math.max(1, Number(snapshot.worldWidth) || (map ? map.width * map.tile_size : 1000));
  const worldHeight = Math.max(1, Number(snapshot.worldHeight) || (map ? map.height * map.tile_size : 600));
  const sceneWidth = map ? map.width * TILE_METERS : 50;
  const sceneDepth = map ? map.height * TILE_METERS : 30;
  return { sceneWidth, sceneDepth, scaleX: sceneWidth / worldWidth, scaleZ: sceneDepth / worldHeight };
}

function toScenePosition(x: number, y: number, p: WorldProjection): Vector3 {
  return new Vector3(x * p.scaleX - p.sceneWidth / 2, 0, y * p.scaleZ - p.sceneDepth / 2);
}

function toServerPosition(point: Vector3, p: WorldProjection) {
  return {
    x: Math.max(0, Math.min((point.x + p.sceneWidth / 2) / p.scaleX, p.sceneWidth / p.scaleX)),
    y: Math.max(0, Math.min((point.z + p.sceneDepth / 2) / p.scaleZ, p.sceneDepth / p.scaleZ)),
  };
}

function motionSemantic(agent: SimAgent): MotionSemantic {
  const speed = Math.hypot(agent.vx || 0, agent.vy || 0);
  if ((agent.currentMessage || '').trim()) return 'talk';
  if (speed > Math.max(45, (agent.moveSpeed || 80) * 0.72)) return 'run';
  if (speed > 1) return 'walk';
  return 'idle';
}

function shortestAngle(current: number, target: number): number {
  let delta = (target - current) % (Math.PI * 2);
  if (delta > Math.PI) delta -= Math.PI * 2;
  if (delta < -Math.PI) delta += Math.PI * 2;
  return delta;
}

function mergeStatic(meshes: Mesh[], name: string): Mesh[] {
  if (meshes.length <= 1) {
    if (meshes[0]) meshes[0].name = name;
    return meshes;
  }
  const merged = Mesh.MergeMeshes(meshes, true, true, undefined, false, true);
  if (!merged) return meshes;
  merged.name = name;
  merged.isPickable = false;
  return [merged];
}

export function BabylonSimulationView({ map, height = 420, playerName }: BabylonSimulationViewProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const engineRef = useRef<Engine | null>(null);
  const sceneRef = useRef<Scene | null>(null);
  const cameraRef = useRef<ArcRotateCamera | null>(null);
  const agentsRef = useRef<Map<string, AgentVisual>>(new Map());
  const snapshotRef = useRef<SimSnapshot>({});
  const projectionRef = useRef<WorldProjection>(projectionOf({}, map));
  const staticMeshesRef = useRef<Mesh[]>([]);
  const staticMaterialsRef = useRef<StandardMaterial[]>([]);
  const staticKeyRef = useRef('');
  const keysRef = useRef<Set<string>>(new Set());
  const moveChainRef = useRef<Promise<void>>(Promise.resolve());
  const followRef = useRef(true);
  const selectedNameRef = useRef(playerName || '');
  const avatarLoadRef = useRef<{ scene: Scene; playerName: string; cancelled: boolean } | null>(null);

  const [snapshot, setSnapshot] = useState<SimSnapshot>({});
  const [status, setStatus] = useState('连接中…');
  const [running, setRunning] = useState(false);
  const [debug, setDebug] = useState(false);
  const [followPlayer, setFollowPlayer] = useState(true);
  const [selectedName, setSelectedName] = useState(playerName || '');
  const [renderStats, setRenderStats] = useState('');
  const [modelStatus, setModelStatus] = useState<'waiting' | 'loading' | 'ready' | 'fallback'>('waiting');
  const [modelSource, setModelSource] = useState('');
  followRef.current = followPlayer;
  selectedNameRef.current = selectedName;

  const selectedAgent = useMemo(
    () => (snapshot.agents || []).find(a => a.agentName === selectedName),
    [snapshot.agents, selectedName],
  );

  const acceptSnapshot = useCallback((next: SimSnapshot) => {
    snapshotRef.current = next;
    projectionRef.current = projectionOf(next, map);
    setSnapshot(next);
    if (typeof next.running === 'boolean') setRunning(next.running);
    setStatus(`tick ${next.tick ?? 0} · ${next.agents?.length ?? 0} 个 Agent`);
  }, [map]);

  useEffect(() => {
    let alive = true;
    let fallbackTimer: ReturnType<typeof setInterval> | null = null;
    const fetchSnapshot = async () => {
      try {
        const res = await fetch('/api/simulation/state');
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const next = await res.json() as SimSnapshot;
        if (alive) acceptSnapshot(next);
      } catch {
        if (alive) setStatus('后端未连接，等待模拟状态…');
      }
    };
    void fetchSnapshot();
    const events = new EventSource('/api/simulation/events');
    events.addEventListener('world_snapshot', event => {
      try { if (alive) acceptSnapshot(JSON.parse((event as MessageEvent).data) as SimSnapshot); } catch { /* 下一次快照自愈 */ }
    });
    events.onerror = () => {
      if (!fallbackTimer) fallbackTimer = setInterval(fetchSnapshot, 1000);
    };
    events.onopen = () => {
      if (fallbackTimer) { clearInterval(fallbackTimer); fallbackTimer = null; }
    };
    return () => {
      alive = false;
      events.close();
      if (fallbackTimer) clearInterval(fallbackTimer);
    };
  }, [acceptSnapshot]);

  const sendMoveDir = useCallback((dx: number, dy: number) => {
    if (!playerName) return;
    moveChainRef.current = moveChainRef.current
      .catch(() => {})
      .then(async () => {
        const res = await fetch(`/api/simulation/move-dir/${encodeURIComponent(playerName)}`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ dx, dy, step: 90 }),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
      })
      .catch(() => setStatus('移动指令发送失败'));
  }, [playerName]);

  const sendTarget = useCallback((point: Vector3) => {
    if (!playerName) return;
    const target = toServerPosition(point, projectionRef.current);
    void fetch(`/api/simulation/target/${encodeURIComponent(playerName)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(target),
    }).then(res => {
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setStatus(`目标：${Math.round(target.x)}, ${Math.round(target.y)}`);
    }).catch(() => setStatus('目标设置失败'));
  }, [playerName]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !playerName) return;
    const pressedKeys = keysRef.current;
    const movementKeys = new Set(['w', 'a', 's', 'd', 'arrowup', 'arrowdown', 'arrowleft', 'arrowright']);
    const editable = (target: EventTarget | null) => {
      const element = target as HTMLElement | null;
      return element?.tagName === 'INPUT' || element?.tagName === 'TEXTAREA' || element?.isContentEditable;
    };
    const onDown = (event: KeyboardEvent) => {
      const key = event.key.toLowerCase();
      if (!movementKeys.has(key) || editable(event.target)) return;
      pressedKeys.add(key); event.preventDefault();
    };
    const onUp = (event: KeyboardEvent) => {
      const key = event.key.toLowerCase();
      if (!movementKeys.has(key)) return;
      pressedKeys.delete(key); event.preventDefault();
      if (pressedKeys.size === 0) sendMoveDir(0, 0);
    };
    const onBlur = () => { pressedKeys.clear(); sendMoveDir(0, 0); };
    canvas.addEventListener('keydown', onDown);
    canvas.addEventListener('keyup', onUp);
    canvas.addEventListener('blur', onBlur);
    const timer = setInterval(() => {
      const keys = pressedKeys;
      const dx = (keys.has('d') || keys.has('arrowright') ? 1 : 0) - (keys.has('a') || keys.has('arrowleft') ? 1 : 0);
      const dy = (keys.has('s') || keys.has('arrowdown') ? 1 : 0) - (keys.has('w') || keys.has('arrowup') ? 1 : 0);
      if (dx || dy) sendMoveDir(dx, dy);
    }, MOVE_INTERVAL_MS);
    return () => {
      clearInterval(timer); pressedKeys.clear();
      canvas.removeEventListener('keydown', onDown); canvas.removeEventListener('keyup', onUp);
      canvas.removeEventListener('blur', onBlur);
    };
  }, [playerName, sendMoveDir]);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    const canvas = document.createElement('canvas');
    canvas.tabIndex = 0;
    canvas.setAttribute('aria-label', 'EchoWorld 3D 世界');
    canvas.style.width = '100%'; canvas.style.height = '100%'; canvas.style.display = 'block'; canvas.style.outline = 'none';
    host.replaceChildren(canvas); canvasRef.current = canvas;
    const engine = new Engine(canvas, true, { preserveDrawingBuffer: false, stencil: true, adaptToDeviceRatio: true });
    engine.setHardwareScalingLevel(Math.max(1, window.devicePixelRatio / 1.5));
    const scene = new Scene(engine);
    scene.clearColor = new Color3(0.025, 0.045, 0.075).toColor4();
    scene.skipPointerMovePicking = true;
    const camera = new ArcRotateCamera('camera', -Math.PI / 2, 1.02, 32, new Vector3(0, 0.7, 0), scene);
    camera.attachControl(canvas, true);
    camera.lowerRadiusLimit = 7; camera.upperRadiusLimit = 120; camera.wheelPrecision = 45;
    new HemisphericLight('ambient', new Vector3(0, 1, 0), scene).intensity = 0.82;
    const sun = new DirectionalLight('sun', new Vector3(-0.4, -1, -0.35), scene);
    sun.position = new Vector3(20, 40, 20); sun.intensity = 0.65;
    engineRef.current = engine; sceneRef.current = scene; cameraRef.current = camera;

    scene.onPointerObservable.add(pointer => {
      if (pointer.type !== PointerEventTypes.POINTERPICK || !pointer.pickInfo?.hit) return;
      const mesh = pointer.pickInfo.pickedMesh;
      const agentName = mesh?.metadata?.echoworldAgent as string | undefined;
      if (agentName) { setSelectedName(agentName); return; }
      if (mesh?.metadata?.echoworldGround && pointer.pickInfo.pickedPoint) sendTarget(pointer.pickInfo.pickedPoint);
    });

    engine.runRenderLoop(() => {
      const dt = Math.min(0.05, engine.getDeltaTime() / 1000);
      const now = performance.now();
      agentsRef.current.forEach(visual => {
        const extrapolate = Math.min(EXTRAPOLATE_MS, Math.max(0, now - visual.lastSnapshotAt)) / 1000;
        const desired = visual.target.add(visual.velocity.scale(extrapolate));
        const error = Vector3.Distance(visual.root.position, desired);
        if (error > 8) visual.root.position.copyFrom(desired);
        else visual.root.position.copyFrom(Vector3.Lerp(visual.root.position, desired, 1 - Math.exp(-12 * dt)));
        visual.root.rotation.y += shortestAngle(visual.root.rotation.y, visual.heading) * (1 - Math.exp(-10 * dt));
        const moving = visual.semantic === 'walk' || visual.semantic === 'run';
        visual.phase += dt * (visual.semantic === 'run' ? 13 : moving ? 8 : 3);
        if (visual.avatar) {
          updatePrivateMmdAvatar(visual.avatar, visual.semantic, visual.phase, selectedNameRef.current === visual.agent.agentName);
        } else {
          visual.body.position.y = 0.9 + (moving ? Math.abs(Math.sin(visual.phase)) * 0.045 : Math.sin(visual.phase) * 0.008);
          const talkScale = visual.semantic === 'talk' ? 1 + Math.sin(visual.phase * 1.7) * 0.015 : 1;
          visual.body.scaling.set(talkScale, 1, talkScale);
        }
      });
      if (followRef.current && playerName) {
        const player = agentsRef.current.get(playerName);
        if (player) camera.target.copyFrom(Vector3.Lerp(camera.target, player.root.position.add(new Vector3(0, 0.65, 0)), 1 - Math.exp(-6 * dt)));
      }
      scene.render();
    });
    const resize = () => engine.resize();
    window.addEventListener('resize', resize);
    const statsTimer = setInterval(() => {
      setRenderStats(`${Math.round(engine.getFps())} FPS · ${scene.getActiveMeshes().length} active meshes`);
    }, 1000);
    const agentVisuals = agentsRef.current;
    const staticMeshes = staticMeshesRef.current;
    const staticMaterials = staticMaterialsRef.current;
    return () => {
      clearInterval(statsTimer); window.removeEventListener('resize', resize);
      if (avatarLoadRef.current?.scene === scene) avatarLoadRef.current.cancelled = true;
      agentVisuals.forEach(visual => {
        if (visual.avatar) disposePrivateMmdAvatar(visual.avatar);
      });
      agentVisuals.clear(); staticMeshes.length = 0; staticMaterials.length = 0; staticKeyRef.current = '';
      scene.dispose(); engine.dispose(); sceneRef.current = null; engineRef.current = null; cameraRef.current = null; canvasRef.current = null;
    };
  }, [playerName, sendTarget]);

  useEffect(() => {
    const scene = sceneRef.current;
    if (!scene) return;
    const p = projectionOf(snapshot, map);
    projectionRef.current = p;
    const obstacleSignature = (snapshot.obstacles || []).map(o => `${o.type}:${o.x}:${o.y}:${o.width}:${o.height}`).join('|');
    const key = map ? `${map.map_id}:${map.width}:${map.height}:${map.tile_size}` : `${p.sceneWidth}:${p.sceneDepth}:${obstacleSignature}`;
    if (key === staticKeyRef.current) return;
    staticMeshesRef.current.forEach(mesh => mesh.dispose());
    staticMaterialsRef.current.forEach(mat => mat.dispose());
    staticMeshesRef.current.length = 0; staticMaterialsRef.current.length = 0;
    const floorMat = makeMaterial(scene, '3d-floor', map?.theme?.includes('night') ? '#18233a' : '#365847');
    const wallMat = makeMaterial(scene, '3d-wall', '#3f4f67');
    staticMaterialsRef.current.push(floorMat, wallMat);
    const ground = MeshBuilder.CreateGround('world-floor', { width: p.sceneWidth, height: p.sceneDepth }, scene);
    ground.material = floorMat; ground.metadata = { echoworldGround: true }; ground.isPickable = true;
    staticMeshesRef.current.push(ground);

    if (map) {
      const roomMat = makeMaterial(scene, '3d-room-floor', '#6b5541');
      staticMaterialsRef.current.push(roomMat);
      const roomMeshes: Mesh[] = [];
      for (const room of map.rooms || []) {
        const roomFloor = MeshBuilder.CreateBox(`room-${room.id}`, { width: room.w * TILE_METERS, depth: room.h * TILE_METERS, height: 0.035 }, scene);
        roomFloor.position.set((room.x + room.w / 2 - map.width / 2) * TILE_METERS, 0.018, (room.y + room.h / 2 - map.height / 2) * TILE_METERS);
        roomFloor.material = roomMat; roomFloor.isPickable = false; roomMeshes.push(roomFloor);
      }
      staticMeshesRef.current.push(...mergeStatic(roomMeshes, 'rooms-merged'));
      const wallMeshes: Mesh[] = [];
      for (let y = 0; y < map.height; y++) {
        let x = 0;
        while (x < map.width) {
          if (Number(map.layers.collision[y]?.[x] ?? 0) === 0) { x++; continue; }
          const start = x;
          while (x + 1 < map.width && Number(map.layers.collision[y]?.[x + 1] ?? 0) !== 0) x++;
          const length = x - start + 1;
          const wall = MeshBuilder.CreateBox(`wall-${y}-${start}`, { width: length * TILE_METERS, depth: TILE_METERS, height: 2.2 }, scene);
          wall.position.set((start + length / 2 - map.width / 2) * TILE_METERS, 1.1, (y + 0.5 - map.height / 2) * TILE_METERS);
          wall.material = wallMat; wall.isPickable = false; wallMeshes.push(wall); x++;
        }
      }
      staticMeshesRef.current.push(...mergeStatic(wallMeshes, 'walls-merged'));
      const decorLimit = Math.min(300, map.decor?.length || 0);
      const decorGroups = new Map<string, { material: StandardMaterial; meshes: Mesh[] }>();
      for (let i = 0; i < decorLimit; i++) {
        const decor = map.decor![i]!;
        const type = decor.type.toUpperCase();
        const color = obstacleColor(type);
        let group = decorGroups.get(color);
        if (!group) {
          const decorMaterial = makeMaterial(scene, `decor-mat-${decorGroups.size}`, color);
          staticMaterialsRef.current.push(decorMaterial);
          group = { material: decorMaterial, meshes: [] };
          decorGroups.set(color, group);
        }
        const mesh = type.includes('TREE')
          ? MeshBuilder.CreateCylinder(`decor-${decor.id}`, { height: 1.8, diameterTop: 0.15, diameterBottom: 0.5 }, scene)
          : MeshBuilder.CreateBox(`decor-${decor.id}`, { width: 0.9, depth: 0.9, height: type.includes('LAMP') ? 1.8 : 0.8 }, scene);
        mesh.position.set((decor.tile[0] + 0.5 - map.width / 2) * TILE_METERS, mesh.getBoundingInfo().boundingBox.extendSize.y, (decor.tile[1] + 0.5 - map.height / 2) * TILE_METERS);
        mesh.material = group.material; mesh.isPickable = false; group.meshes.push(mesh);
      }
      decorGroups.forEach((group, color) => staticMeshesRef.current.push(...mergeStatic(group.meshes, `decor-${color}-merged`)));
    } else {
      const obstacleGroups = new Map<string, { material: StandardMaterial; meshes: Mesh[] }>();
      (snapshot.obstacles || []).forEach((obstacle: SimObstacle, i) => {
        const color = obstacleColor(obstacle.type);
        const groupKey = `${color}:${obstacle.type === 'WATER' ? 'water' : 'solid'}`;
        let group = obstacleGroups.get(groupKey);
        if (!group) {
          const obstacleMaterial = makeMaterial(scene, `obstacle-mat-${obstacleGroups.size}`, color, obstacle.type === 'WATER' ? 0.82 : 1);
          staticMaterialsRef.current.push(obstacleMaterial);
          group = { material: obstacleMaterial, meshes: [] };
          obstacleGroups.set(groupKey, group);
        }
        const width = Math.max(0.3, obstacle.width * p.scaleX);
        const depth = Math.max(0.3, obstacle.height * p.scaleZ);
        const height3d = obstacle.type === 'TREE' ? 3.5 : obstacle.type === 'BUILDING' || obstacle.type === 'WALL' ? 2.5 : 0.9;
        const mesh = MeshBuilder.CreateBox(`obstacle-${i}`, { width, depth, height: height3d }, scene);
        mesh.position.set((obstacle.x + obstacle.width / 2) * p.scaleX - p.sceneWidth / 2, height3d / 2, (obstacle.y + obstacle.height / 2) * p.scaleZ - p.sceneDepth / 2);
        mesh.material = group.material; mesh.isPickable = false; group.meshes.push(mesh);
      });
      obstacleGroups.forEach((group, keyPart) => staticMeshesRef.current.push(...mergeStatic(group.meshes, `obstacles-${keyPart}-merged`)));
    }
    staticKeyRef.current = key;
  }, [map, snapshot]);

  useEffect(() => {
    const scene = sceneRef.current;
    if (!scene) return;
    const now = performance.now();
    const p = projectionOf(snapshot, map);
    const seen = new Set<string>();
    (snapshot.agents || []).forEach((agent: SimAgent, i) => {
      seen.add(agent.agentName);
      let visual = agentsRef.current.get(agent.agentName);
      if (!visual) {
        const root = new TransformNode(`agent-root-${agent.agentName}`, scene);
        const body = MeshBuilder.CreateCapsule(`agent-${agent.agentName}`, { height: 1.7, radius: 0.38, tessellation: 10 }, scene);
        body.parent = root; body.position.y = 0.9;
        body.material = makeMaterial(scene, `agent-mat-${i}`, agent.playerControlled ? '#fbbf24' : agent.ambient ? '#64748b' : '#38bdf8');
        body.metadata = { echoworldAgent: agent.agentName }; body.isPickable = true;
        const initial = toScenePosition(agent.x, agent.y, p);
        root.position.copyFrom(initial);
        visual = { root, body, target: initial, velocity: Vector3.Zero(), heading: 0, lastSnapshotAt: now, phase: i * 0.7, semantic: 'idle', agent };
        agentsRef.current.set(agent.agentName, visual);
      }
      visual.target = toScenePosition(agent.x, agent.y, p);
      visual.velocity.set((agent.vx || 0) * p.scaleX, 0, (agent.vy || 0) * p.scaleZ);
      if (visual.velocity.lengthSquared() > 0.0001) visual.heading = Math.atan2(visual.velocity.x, visual.velocity.z);
      visual.lastSnapshotAt = now; visual.semantic = motionSemantic(agent); visual.agent = agent;
      visual.root.setEnabled(!agent.ambient || debug || agent.agentName === playerName || !playerName);
      const mat = visual.body.material as StandardMaterial;
      mat.emissiveColor = agent.agentName === selectedName ? new Color3(0.18, 0.14, 0.02) : Color3.Black();
      if (playerName && agent.agentName === playerName && !visual.avatar && avatarLoadRef.current?.playerName !== playerName) {
        const request = { scene, playerName, cancelled: false };
        const targetVisual = visual;
        avatarLoadRef.current = request;
        setModelStatus('loading');
        void loadPrivateMmdAvatar(scene, targetVisual.root, playerName)
          .then(avatar => {
            if (request.cancelled || scene.isDisposed || !agentsRef.current.has(playerName)) {
              disposePrivateMmdAvatar(avatar);
              return;
            }
            targetVisual.avatar = avatar;
            targetVisual.body.setEnabled(false);
            setModelSource(avatar.sourceName);
            setModelStatus('ready');
            setStatus(`${avatar.sourceName} 已加载`);
          })
          .catch(error => {
            if (request.cancelled) return;
            console.warn('[EchoWorld 3D] 私人 PMX 模型加载失败，已保留 Capsule 回退', error);
            setModelStatus('fallback');
            setStatus('私人模型加载失败，已使用低模角色');
          });
      }
    });
    agentsRef.current.forEach((visual, name) => {
      if (!seen.has(name)) {
        if (visual.avatar) disposePrivateMmdAvatar(visual.avatar);
        visual.root.dispose(false, true);
        agentsRef.current.delete(name);
      }
    });
  }, [snapshot, map, debug, playerName, selectedName]);

  const control = async (action: 'start' | 'stop' | 'reset') => {
    try {
      const res = await fetch(`/api/simulation/${action}`, { method: 'POST' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setRunning(action === 'start');
      setStatus(action === 'start' ? '启动中…' : action === 'stop' ? '已暂停' : '已重置');
    } catch { setStatus('操作失败'); }
  };

  const selectedSemantic = selectedAgent ? motionSemantic(selectedAgent) : null;

  return <div className="babylon-sim-view" style={{ border: '1px solid var(--border, #2b3854)', borderRadius: 10, overflow: 'hidden', background: 'var(--bg)' }}>
    <div style={{ display: 'flex', gap: 8, padding: '8px 12px', background: 'var(--panel-2)', alignItems: 'center', flexWrap: 'wrap' }}>
      <strong style={{ fontSize: 13, color: '#38bdf8' }}>3D 世界（Babylon.js）</strong>
      <button className="btn btn-small" disabled={running} onClick={() => void control('start')}>▶ 开始</button>
      <button className="btn btn-small" disabled={!running} onClick={() => void control('stop')}>⏸ 暂停</button>
      <button className="btn btn-small btn-danger" onClick={() => void control('reset')}>🔄 重置</button>
      <button className={`btn btn-small ${followPlayer ? 'btn-primary' : ''}`} onClick={() => setFollowPlayer(v => !v)}>🎥 {followPlayer ? '跟随玩家' : '自由镜头'}</button>
      <button className={`btn btn-small ${debug ? 'btn-primary' : ''}`} onClick={() => setDebug(v => !v)}>👁 {debug ? '显示群演' : '局部视图'}</button>
      <span style={{ fontSize: 12, color: 'var(--text-2)' }}>{status}</span>
      <span style={{ fontSize: 11, color: modelStatus === 'ready' ? '#86efac' : modelStatus === 'fallback' ? '#fca5a5' : '#fde68a' }}>
        {modelStatus === 'ready' ? `人物：${modelSource || PRIVATE_MMD_MODEL_LABEL}` : modelStatus === 'loading' ? '正在加载私人模型…' : modelStatus === 'fallback' ? '人物：Capsule 回退' : '人物模型待命'}
      </span>
      <span style={{ fontSize: 11, color: 'var(--text-3)' }}>{renderStats}</span>
    </div>
    <div style={{ position: 'relative' }}>
      <div ref={hostRef} style={{ width: '100%', height, minHeight: 280 }} />
      <div style={{ position: 'absolute', left: 10, bottom: 10, padding: '6px 10px', borderRadius: 7, background: 'rgba(15,23,42,.82)', color: '#cbd5e1', fontSize: 11, pointerEvents: 'none' }}>
        点击地面移动 · WASD/方向键连续移动 · 拖拽旋转 · 滚轮缩放
        {selectedAgent && <span style={{ marginLeft: 12, color: '#fbbf24' }}>选中：{selectedAgent.agentName} · {selectedSemantic} · {Math.round(Math.hypot(selectedAgent.vx || 0, selectedAgent.vy || 0))} px/s</span>}
      </div>
    </div>
  </div>;
}
