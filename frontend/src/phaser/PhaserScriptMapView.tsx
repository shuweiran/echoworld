/**
 * PhaserScriptMapView.tsx — 剧本杀地图视图（阶段 2 + P-0803-E 地图增强）
 *
 * 数据流（后端零改动契约，阶段 2 新增 POST /api/script/map）：
 *   map JSON（契约 v1）→ Phaser ScriptMapScene 渲染（瓦片/碰撞/热点/出生点）
 *   热点搜证 → POST /api/script/search（player + zone.clue_location）→ 搜证结果卡片
 *
 * P-0803-E（调研项 2/3）：
 *   - 相机跟随 + 滚轮缩放（ScriptMapScene 内实现，本组件零感知）
 *   - 小地图（MiniMap DOM canvas 覆盖层，消费 onPlayerMove 玩家格坐标 + 已搜证列表）
 *   - 全屏（Fullscreen API 作用于本卡片容器，Phaser Scale.FIT 随容器自适应；「单页不双开」决策兼容）
 *
 * 生命周期（阶段 0 实证模式复用，与 PhaserSimulationView 一致）：
 *   Game 实例挂 React Ref；卸载 / StrictMode double-mount → game.destroy(true)；
 *   Vite HMR → import.meta.hot.dispose 销毁。
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import Phaser from 'phaser';
import { ScriptMapScene, type ScriptMapSceneCallbacks } from './ScriptMapScene';
import { MiniMap } from './MiniMap';
import { normalizeMap, type ScriptMap, type MapZone, type MapDecorItem } from './mapData';
import { formatInteractResult } from './interactData';
import { api, assetFileUrl } from '../api/client';

export interface PhaserScriptMapViewProps {
  /** 契约 v1 地图 JSON（POST /api/script/map 响应 .map） */
  map: ScriptMap;
  /** 当前玩家名（搜证主体，POST /api/script/search player 字段） */
  playerName: string;
  /** 容器高度（px），默认 560 */
  height?: number;
  /** P-0803-E 方案 B: 搜证足迹（已搜过地点列表，来自 map 响应/对局状态）→ 挂载时恢复绿点 */
  searchedLocations?: string[];
  /**
   * P-0803-M：只读模式（简单对话版 chat 可选地图的氛围展示）——
   * 禁搜证交互（点击热点/E 键不触发 POST /api/script/search），提示文案区分；WASD 漫游/缩放/全屏保留。
   */
  readOnly?: boolean;
  /**
   * P-0804-G（前端重写 demo）：搜证提供者。传入后搜证不再 fetch 后端，改由本地 mock 层处理；
   * 缺省保持原行为（POST /api/script/search）。接真实 API 时替换 mock 层即可。
   */
  searchProvider?: (zone: MapZone) => Promise<SearchResult>;
  /**
   * P-0804-G：是否拉取素材库瓦片图集（缺省 true=原行为）。
   * demo 模式（无后端）传 false 跳过网络请求，回退运行时色块纹理。
   */
  fetchAssets?: boolean;
  /** P-0804-G：左上角标题文案（缺省原「剧本杀地图」） */
  title?: string;
  /** P-0804-H 续：AI 角色名单（一般模式瓦片地图显示其他角色标记，缺省空） */
  aiCharacters?: string[];
  /**
   * P-0814-H：decor 实例状态（键 = "mapId|decorId"，对齐后端 decor_states）——
   * processed=true 的交互物灰显（一次性语义）；对局状态/快照恢复注入，缺省空。
   */
  decorStates?: Record<string, Record<string, unknown>>;
  /** P-0817-Q（外部/内部分离）：多图注册表（mapId → 契约地图）——传送点切换地图的数据源；缺省单图零变化 */
  maps?: Record<string, ScriptMap>;
  /** P-0817-Q：地图切换回调（传送点进屋/出屋后 React 层同步当前地图 id） */
  onMapChange?: (mapId: string) => void;
  /** P-0819-O：地图动作完成后通知对局容器刷新权威剧本状态（Gal/线索/AP 联动） */
  onActionComplete?: () => void | Promise<void>;
}

interface SearchResult {
  ok: boolean;
  text: string;
  clues: { id: string; content?: string; title?: string; ap_cost?: number }[];
  ap?: number;
  /** P-0814-H：交互结果额外字段（dialog 文本 / 已处理态） */
  dialog?: string[];
  processed?: boolean;
}

/**
 * P-0804-D：解析素材 meta_json 中的 tile_size。
 * 兼容两种格式：标准 JSON（{"tile_size":16} / {"tileSize":16}）与后端 Java Map.toString 输出
 * （"{tile_size=16, width=256, height=400, ...}"，非 JSON）；解析失败/无值 → undefined（回退契约 m.tile_size）。
 */
function parseMetaTileSize(metaJson: unknown): number | undefined {
  if (metaJson === null || metaJson === undefined) return undefined;
  const raw = String(metaJson);
  if (!raw.trim()) return undefined;
  try {
    const obj = JSON.parse(raw);
    if (obj && typeof obj === 'object') {
      const rec = obj as Record<string, unknown>;
      const v = rec.tile_size ?? rec.tileSize;
      const n = Number(v);
      if (v !== undefined && v !== null && Number.isFinite(n) && n > 0) return n;
    }
  } catch { /* 非 JSON（Java Map.toString）→ 正则提取 */ }
  // Java Map.toString 格式：{tile_size=16, width=256, ...}（值可为含 = 的 URL，仅取数字字段）
  const m = raw.match(/(?:^|[,{]\s*)tile_size\s*=\s*(\d+)/);
  if (m && Number(m[1]) > 0) return Number(m[1]);
  return undefined;
}

export function PhaserScriptMapView({
  map, playerName, height = 560, searchedLocations = [], readOnly = false, searchProvider,
  fetchAssets = true, title, aiCharacters = [], decorStates = {}, maps, onMapChange,
  onActionComplete,
}: PhaserScriptMapViewProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const cardRef = useRef<HTMLDivElement>(null); // 全屏目标容器（整卡）
  const gameRef = useRef<Phaser.Game | null>(null);
  const sceneRef = useRef<ScriptMapScene | null>(null);
  const [result, setResult] = useState<SearchResult | null>(null);
  const [busy, setBusy] = useState(false);
  // P-0803-E：小地图数据（玩家格坐标 + 已搜证列表）+ 全屏态
  const [playerPos, setPlayerPos] = useState<{ x: number; y: number } | null>(null);
  const [searchedZones, setSearchedZones] = useState<string[]>([]);
  const [fullscreen, setFullscreen] = useState(false);
  // P-0811-G：AI 角色巡逻开关（瓦片地图上人物走动；默认进入即动）
  const [aiRunning, setAiRunning] = useState(true);
  // P-0817-G：房间模式开关（一屏一房间 / 整图漫游；默认一屏一房间）+ 当前房间（小地图高亮）
  const [roomView, setRoomView] = useState(true);
  const [currentRoom, setCurrentRoom] = useState<string | undefined>(undefined);
  // P-0817-Q：当前地图（warp 传送后切换；map prop 变化时同步回外部选择）
  const [currentMap, setCurrentMap] = useState<ScriptMap>(map);
  const [spawnTile, setSpawnTile] = useState<[number, number] | undefined>(undefined);
  // P-0803-H4（频闪修复）：地图稳定化 —— ChatPage 轮询 scriptStatus 时 map 每次都是新对象引用，
  // 直接依赖 map 会让 useEffect 反复重建 Phaser.Game（地图频闪）。改为按内容 key 比较：
  // map_id + map_version + rooms/zones 结构不变 → key 不变 → 不重建；内容真变才重建。
  const mapRef = useRef(currentMap);
  mapRef.current = currentMap;
  const mapsRef = useRef(maps);
  mapsRef.current = maps;
  const onMapChangeRef = useRef(onMapChange);
  onMapChangeRef.current = onMapChange;
  const spawnTileRef = useRef(spawnTile);
  spawnTileRef.current = spawnTile;
  // P-0817-T 修复：map prop（外部选择/父级切换）变化 → 只同步当前地图，
  // 不在这里清空传送落点——warp 切图（进屋/出屋）也会触发 map prop 变化，
  // 若提前清空 spawnTile，重建后玩家会落在 spawn_points（房间中心）而非门口。
  // 落点在本次切图被 scene 消费后（initGame 内）清除。
  useEffect(() => {
    setCurrentMap(map);
  }, [map]);
  // P-0804-G：searchProvider 用 ref 承载最新引用，避免函数身份变化触发 Phaser 重建（地图频闪）
  const searchProviderRef = useRef(searchProvider);
  searchProviderRef.current = searchProvider;
  // P-0804-H 续：AI 角色名单 ref（避免身份变化触发 Phaser 重建）
  const aiCharactersRef = useRef(aiCharacters);
  aiCharactersRef.current = aiCharacters;
  // P-0814-H：decor 实例状态 ref（对局轮询更新时同步场景灰显；避免身份变化触发 Phaser 重建）
  const decorStatesRef = useRef(decorStates);
  decorStatesRef.current = decorStates;
  useEffect(() => {
    sceneRef.current?.restoreDecorStates(decorStates);
  }, [decorStates]);
  const mapKey = useMemo(() => {
    if (!currentMap) return 'none';
    const m = currentMap as any;
    try {
      // P-0814-G：v0.2 可选键（objects/overlay/decor/spawnMarkers/tileProps）纳入内容 key——
      // 装饰内容变化触发重建；内容稳定（含缺省）时 key 不变不重建（P-0803-H4 频闪修复语义保持）
      return `${m.map_id || ''}|${m.map_version || ''}|${JSON.stringify(m.rooms || '')}|${JSON.stringify(m.zones || '')}`
        + `|${JSON.stringify(m.layers?.objects || '')}|${JSON.stringify(m.layers?.overlay || '')}`
        + `|${JSON.stringify(m.decor || '')}|${JSON.stringify(m.spawnMarkers || '')}|${JSON.stringify(m.tileProps || '')}`;
    } catch {
      return 'unknown';
    }
  }, [currentMap]);

  // 全屏态监听（Fullscreen API，ESC/系统退出也同步按钮态）
  useEffect(() => {
    const onFs = () => setFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', onFs);
    return () => document.removeEventListener('fullscreenchange', onFs);
  }, []);

  const toggleFullscreen = () => {
    const el = cardRef.current;
    if (!el) return;
    if (document.fullscreenElement) {
      void document.exitFullscreen();
    } else {
      void el.requestFullscreen();
    }
  };

  // P-0811-G：AI 巡逻开关 → 同步 ScriptMapScene（瓦片地图上人物走动/相遇对话）
  const toggleAi = () => {
    const next = !aiRunning;
    setAiRunning(next);
    sceneRef.current?.setAiMoving(next);
  };
  useEffect(() => {
    sceneRef.current?.setAiMoving(aiRunning);
  }, [aiRunning]);

  useEffect(() => {
    if (!hostRef.current) return;
    const host = hostRef.current;
    let cancelled = false;

    const onSearch: ScriptMapSceneCallbacks['onSearch'] = async (zone: MapZone) => {      const location = zone.clue_location || zone.name || '';
      setBusy(true);
      setResult(null);
      try {
        // P-0804-G：demo 模式走本地 mock 搜证（searchProvider），缺省保持原 fetch 后端行为
        const d = searchProviderRef.current
          ? await searchProviderRef.current(zone)
          : await (async () => {
              const r = await fetch('/api/script/search', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ player: playerName, location }),
              });
              if (!r.ok) {
                const err = await r.json().catch(() => null);
                throw new Error(err?.error || err?.detail || `HTTP ${r.status}`);
              }
              return await r.json();
            })();
        const found = Array.isArray(d.clues) ? d.clues : [];
        const ap = d.ap !== undefined ? Number(d.ap) : undefined;
        const text = String(d.text ?? d.result ?? (found.length ? `搜证成功：获得 ${found.length} 条线索` : '该地点没有更多可搜证线索'));
        const ok = !d.error && !text.includes('行动点不足');
        if (ok && found.length > 0) {
          // 搜证成功 → 热点变绿（已搜证标记）+ 小地图绿点同步
          sceneRef.current?.markZoneSearched(zone.id);
          setSearchedZones(prev => (prev.includes(zone.id) ? prev : [...prev, zone.id]));
        }
        setResult({ ok, text, clues: found as SearchResult['clues'], ap });
        if (ok) await onActionComplete?.();
      } catch (e: any) {
        setResult({ ok: false, text: '搜证请求失败：' + (e?.message || '网络错误'), clues: [] });
      } finally {
        setBusy(false);
      }
    };

    // P-0814-H：decor 交互 → POST /api/script/interact（统一动作键；玩家格坐标随请求供后端半径校验）
    const onDecorInteract: ScriptMapSceneCallbacks['onDecorInteract'] = async (decor: MapDecorItem, gx: number, gy: number) => {
      setBusy(true);
      setResult(null);
      try {
        const tile = `${decor.tile[0]},${decor.tile[1]}`;
        const r = await api.scriptInteract({
          player: playerName,
          session_id: (mapRef.current as any)?.session_id || undefined,
          decor_id: decor.id,
          tile,
          x: gx,
          y: gy,
        });
        const fmt = formatInteractResult(r);
        // once 已处理 → 场景灰显（一次性语义）
        if (fmt.processed) sceneRef.current?.markDecorProcessed(decor.id);
        setResult({ ok: fmt.ok, text: fmt.text, clues: fmt.clues, dialog: fmt.dialog, processed: fmt.processed });
        if (fmt.ok) await onActionComplete?.();
      } catch (e: any) {
        setResult({ ok: false, text: '交互请求失败：' + (e?.message || '网络错误'), clues: [] });
      } finally {
        setBusy(false);
      }
    };

    // P-0817-Q（外部/内部分离）：传送点触发 → 多图注册表里找目标地图并切换（重建 scene + 落点定位）
    const onWarp: ScriptMapSceneCallbacks['onWarp'] = (mapId, toX, toY) => {
      const target = mapsRef.current?.[mapId];
      if (!target) return;
      setSpawnTile([toX, toY]);
      setCurrentMap(target);
      onMapChangeRef.current?.(mapId);
    };

    // P-0804-C：瓦片图集素材（SCENE_TILESET 登记，取首个）→ 有素材优先用素材图集，无素材回退运行时色块（零破坏）
    // P-0804-D：素材 tile_size 一并下传（外部素材 tile_size 主导切片，见 ScriptMapScene.create ts 取值）
    const initGame = (tilesetUrl?: string, tilesetTileSize?: number) => {
      const game = new Phaser.Game({
      // P-0804-F（2026-08-04）：软件 WebGL（disable-gpu/无 GPU 加速环境）下 Phaser WebGL 渲染
      // 外部瓦片图集崩溃（React 白屏）。强制 Canvas 2D 渲染器——瓦片地图为 2D 场景，Canvas 渲染
      // 兼容性最佳（软件光栅化零崩溃）；SimulationView 等保留 AUTO 不受影响。
      type: Phaser.CANVAS,
      parent: host,
      width: 800,
      height,
      backgroundColor: '#0f172a',
      scale: {
        mode: Phaser.Scale.FIT,
        autoCenter: Phaser.Scale.CENTER_BOTH,
        width: 800,
        height,
      },
      physics: { default: 'arcade', arcade: { gravity: { x: 0, y: 0 }, debug: false } },
      scene: [new ScriptMapScene(normalizeMap(mapRef.current) ?? mapRef.current, {
        onSearch,
        onDecorInteract,
        onPlayerMove: (gx, gy) => setPlayerPos({ x: gx, y: gy }),
        onRoomChange: (rid) => setCurrentRoom(rid),
        onWarp,
      }, readOnly, tilesetUrl, tilesetTileSize, aiCharactersRef.current, decorStatesRef.current, roomView,
        spawnTileRef.current)],
      banner: false,
    });
    gameRef.current = game;
    // P-0817-T：本次重建已消费传送落点 → 清除（下次外部手动切图用目标图 spawn_points）
    setSpawnTile(undefined);
    // P-0803-E 方案 B: 恢复搜证足迹绿点（快照/重连后地图不丢已搜证状态）。
    // Phaser 场景启动异步：getScene 在 scene 激活前返回 null → 轮询等待实例后恢复；
    // create() 未完成时 restoreSearched 内部挂起（pendingSearched），create 末尾执行。
    const existing = game.scene.getScene('ScriptMapScene') as ScriptMapScene | null;
    if (existing) {
      sceneRef.current = existing;
      existing.restoreSearched(searchedLocations);
    } else {
      let tries = 0;
      const timer = window.setInterval(() => {
        const sc = game.scene.getScene('ScriptMapScene') as ScriptMapScene | null;
        if (sc) {
          sceneRef.current = sc;
          sc.restoreSearched(searchedLocations);
          window.clearInterval(timer);
        } else if (++tries > 40) {
          window.clearInterval(timer); // 2s 内未激活（异常路径）放弃，不阻塞
        }
      }, 50);
    }
    };

    (async () => {
      let tilesetUrl: string | undefined;
      let tilesetTileSize: number | undefined;
      // P-0804-G：demo 模式（fetchAssets=false）跳过素材拉取（无后端），回退运行时色块纹理
      if (fetchAssets) {
        try {
          const list = await api.assetList({ type: 'SCENE_TILESET' });
          if (list && list.length > 0 && list[0].file_path) {
            tilesetUrl = assetFileUrl(list[0].file_path);
            // P-0804-D：素材 tile_size 主导——meta_json 可能是 Java Map.toString（"{tile_size=16,...}"）或 JSON，容错解析
            tilesetTileSize = parseMetaTileSize(list[0].meta_json);
          }
        } catch { /* 素材拉取失败 → 运行时色块，零破坏 */ }
      }
      if (!cancelled) initGame(tilesetUrl, tilesetTileSize);
    })();

    return () => {
      cancelled = true;
      if (gameRef.current) {
        gameRef.current.destroy(true);
        gameRef.current = null;
        sceneRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mapKey, playerName, height, readOnly, fetchAssets, aiCharacters, roomView]);

  // ── Vite dev HMR 保护 ──
  useEffect(() => {
    const dispose = () => {
      if (gameRef.current) {
        gameRef.current.destroy(true);
        gameRef.current = null;
        sceneRef.current = null;
      }
    };
    if (import.meta.hot) {
      import.meta.hot.dispose(dispose);
    }
    return () => { /* 组件卸载由主 effect cleanup 处理 */ };
  }, []);

  const gen = map?.generator as Record<string, unknown> | undefined;

  return (
    <div className="script-map-view" ref={cardRef} style={{ border: '1px solid var(--border, #2b3854)', borderRadius: 10, overflow: 'hidden', background: 'var(--bg)' }}>
      <div style={{ display: 'flex', gap: 8, padding: '8px 12px', background: 'var(--panel-2)', alignItems: 'center', flexWrap: 'wrap' }}>
        <span style={{ fontSize: 13, color: 'var(--phase-investigation)', fontWeight: 600 }}>{title || '🗺️ 剧本杀地图（Phaser 渲染）'}</span>
        <span style={{ fontSize: 11, color: 'var(--text-2)' }}>
          {map.width}×{map.height} 格 · {map.zones.length} 热点{(map as any).decor?.length ? ` · ${(map as any).decor.length} 交互物` : ''} · 生成器：
          {gen && gen.kind === 'bsp' ? <span style={{ color: 'var(--phase-discussion)' }}>BSP（降级）</span> : <span style={{ color: 'var(--color-success)' }}>LLM</span>}
        </span>
        <span style={{ fontSize: 11, color: 'var(--phase-discussion)' }} title="WASD/方向键移动仅在前端本地渲染，不会上报服务器（后端不持有剧本杀地图的权威玩家位置）">
          📍 本地漫游模式（移动仅本地显示，不上报服务器）
        </span>
        <span style={{ fontSize: 11, color: 'var(--text-3)' }}>
          {readOnly ? '🔭 只读浏览（氛围展示，无搜证/交互）· WASD 移动 · 滚轮缩放' : 'WASD 移动 · 滚轮缩放 · 点击金色区域或按 E 搜证 · 点击黄色边框交互物'}
        </span>
        <span style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
          {/* P-0817-G：房间模式 / 整图模式切换（一屏一房间 · 走门切换） */}
          <button
            onClick={() => setRoomView(v => !v)}
            title={roomView ? '切换到整图漫游模式' : '切换到一屏一房间模式（走门切换）'}
            style={{
              background: roomView ? 'var(--panel-2)' : 'color-mix(in srgb, var(--color-accent) 18%, var(--bg))',
              color: roomView ? 'var(--phase-investigation)' : 'var(--color-accent)',
              border: '1px solid var(--border)', borderRadius: 6, fontSize: 12, padding: '4px 10px', cursor: 'pointer', whiteSpace: 'nowrap',
            }}
          >
            {roomView ? '🚪 房间模式' : '🗺️ 整图模式'}
          </button>
          {/* P-0811-G：AI 角色巡逻开关（瓦片地图上人物走动/相遇对话） */}
          <button
            onClick={toggleAi}
            title={aiRunning ? '暂停 AI 角色走动' : '开始 AI 角色走动'}
            style={{
              background: aiRunning ? 'var(--panel-2)' : 'color-mix(in srgb, var(--color-success) 18%, var(--bg))', color: aiRunning ? 'var(--phase-investigation)' : 'var(--color-success)',
              border: '1px solid var(--border)', borderRadius: 6, fontSize: 12, padding: '4px 10px', cursor: 'pointer', whiteSpace: 'nowrap',
            }}
          >
            {aiRunning ? '⏸ AI 暂停' : '▶ AI 开始'}
          </button>
          {/* P-0803-E：小地图（调研项 2 方案 B，DOM canvas 覆盖层） */}
          <MiniMap map={map} player={playerPos} searched={searchedZones} currentRoom={roomView ? currentRoom : undefined} />
          {/* P-0803-E：全屏（调研项 3，Fullscreen API，不新开窗口） */}
          <button
            onClick={toggleFullscreen}
            title="全屏探索地图（ESC 退出）"
            style={{
              background: 'var(--panel-2)', color: 'var(--phase-investigation)', border: '1px solid var(--border)', borderRadius: 6,
              fontSize: 12, padding: '4px 10px', cursor: 'pointer', whiteSpace: 'nowrap',
            }}
          >
            {fullscreen ? '⛶ 退出全屏' : '⛶ 全屏'}
          </button>
        </span>
      </div>
      {/* P-0803-E：host 容器全屏时 flex:1 撑满（CSS :fullscreen 规则），Phaser Scale.FIT 自适应 */}
      <div ref={hostRef} className="script-map-host" style={{ width: '100%', height: fullscreen ? undefined : height }} />
      {/* 搜证结果卡片 */}
      {(result || busy) && (
        <div className="script-map-result" style={{ padding: '10px 14px', borderTop: '1px solid var(--border, #2b3854)', background: 'var(--panel)', fontSize: 13 }}>
          {busy ? (
            <span style={{ color: 'var(--text-2)' }}>处理中...</span>
          ) : result ? (
            <div>
              <div style={{ color: result.ok ? 'var(--color-success)' : 'var(--color-danger)', fontWeight: 600, marginBottom: 4 }}>
                {result.ok ? (result.processed ? '✅ ' : '✨ ') : '⚠️ '}{result.text}
                {result.ap !== undefined && <span style={{ color: 'var(--text-2)', fontWeight: 400 }}> ｜ 剩余 AP：{result.ap}</span>}
              </div>
              {/* P-0814-H：decor 交互 dialog 动作文本 */}
              {result.dialog && result.dialog.length > 0 && (
                <div style={{ color: 'var(--text)', lineHeight: 1.6, margin: '4px 0 0 4px' }}>
                  {result.dialog.map((d, i) => <div key={i}>💬 {d}</div>)}
                </div>
              )}
              {result.clues.length > 0 && (
                <ul style={{ margin: '4px 0 0 18px', padding: 0 }}>
                  {result.clues.map(c => (
                    <li key={c.id} style={{ color: 'var(--text)', lineHeight: 1.6 }}>
                      🔎 {c.content}
                      {c.ap_cost !== undefined && <span style={{ color: 'var(--text-2)' }}>（消耗 {c.ap_cost} AP）</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}
