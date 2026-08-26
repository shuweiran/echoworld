/**
 * ScriptMapScene.ts — 剧本杀地图渲染 Scene（阶段 2，契约 v1）
 *
 * 职责：把 POST /api/script/map 返回的契约 v1 地图 JSON 渲染为可交互 2D 地图：
 *   - 瓦片地面层 + 碰撞层（对齐阶段 0 tileScene：setCollisionByExclusion 隐藏物理层）
 *   - zones[] 搜证热点（金色区域 + 脉冲 + 名称标签；靠近提示 + 点击/E 触发搜证回调）
 *   - spawn_points[] 玩家/AI 出生点标记；rooms[] 房间名标注
 *   - 玩家 WASD 移动 + 瓦片碰撞（对齐阶段 0 zoneScene 交互模式）
 *
 * 交互回传：onSearch(zone) → React 组件调 POST /api/script/search（zones[].clue_location ↔
 * clues[].location 绑定）；搜证成功后 markZoneSearched(id) 把热点变绿（已搜证）。
 */
import Phaser from 'phaser';
import {
  normalizeMap, tileColor,
  type ScriptMap, type MapZone, type MapDecorItem, type MapExit, type MapWarp,
} from './mapData';
import {
  buildDecorPlan, charDepth, drawDecorCmds,
  DEPTH_OVERLAY, DEPTH_WATER, C_WATER_BLUE,
} from './decorData';
import { decorInRange, decorStateKey } from './interactData';

export interface ScriptMapSceneCallbacks {
  /** 触发搜证：zone 的 clue_location 即搜索地点（POST /api/script/search） */
  onSearch: (zone: MapZone) => void;
  /** P-0814-H: 触发 decor 交互（POST /api/script/interact）—— 携带玩家当前格坐标供后端半径校验 */
  onDecorInteract?: (decor: MapDecorItem, gridX: number, gridY: number) => void;
  /** 玩家位置变化回调（格坐标；仅格变化时触发，供小地图/HUD 消费） */
  onPlayerMove?: (gridX: number, gridY: number) => void;
  /** P-0817-G（房间模式）：当前房间切换回调（小地图高亮/React 层消费） */
  onRoomChange?: (roomId: string) => void;
  /** P-0817-Q（外部/内部分离）：玩家触发传送点 → 请求切换到目标地图（React 层持有多图注册表） */
  onWarp?: (mapId: string, toX: number, toY: number) => void;
}

/**
 * P-0803-M：只读模式（简单对话版可选地图的氛围展示）——
 * 禁搜证交互（点击热点/E 键不触发 onSearch），提示文案区分；WASD 漫游/滚轮缩放/全屏保留。
 */

/** 画布尺寸（对齐阶段 0 demo 800×560） */
const CANVAS_W = 800;
const CANVAS_H = 560;

/**
 * P-0817-F（人物固定比例尺）：玩家/AI 人物在屏幕上保持固定像素尺寸，
 * 地图（相机缩放）跟随人物缩放——放大/缩小只改变世界比例，人物不随之变大变小。
 */
const PLAYER_SCREEN_R = 16; // 玩家固定屏幕半径（px）
const AI_SCREEN_R = 12;     // AI 角色固定屏幕半径（px）
/** 基准缩放窗口：视口横跨约 18 格（瓦片屏显尺寸 = ts × zoom，大图自动缩小、小图不放大） */
const BASE_ZOOM_TILES_WIDE = 18;
/** 滚轮缩放绝对范围（世界比例随人物恒定屏显尺寸变化） */
const ZOOM_MIN = 0.3;
const ZOOM_MAX = 1.5;

export class ScriptMapScene extends Phaser.Scene {
  private mapJson: ScriptMap;
  private callbacks: ScriptMapSceneCallbacks;

  private b!: { ox: number; oy: number; ts: number; map: Phaser.Tilemaps.Tilemap };
  private zoneG!: Phaser.GameObjects.Graphics;
  private spawnG!: Phaser.GameObjects.Graphics;
  /** P-0814-H：decor 交互高亮层（靠近金框/已处理灰盖；depth 8.5 位于热点 8 与标签 9 之间） */
  private decorG!: Phaser.GameObjects.Graphics;
  /** P-0814-G：装饰行 Graphics 缓存（key=行号 y；depth = 1 + (y+0.5)/H，北侧被南侧遮挡） */
  private decorRowG = new Map<number, Phaser.GameObjects.Graphics>();
  /** P-0814-G：前景遮罩 Graphics（永远盖住角色，depth=5） */
  private overlayG!: Phaser.GameObjects.Graphics;
  /** P-0814-G：water 格半透明叠加 Graphics（depth=0.5） */
  private waterG!: Phaser.GameObjects.Graphics;
  /** P-0814-G：地图像素高（角色 y 深度归一用） */
  private mapPxH = 0;
  private zoneLabels: Phaser.GameObjects.Text[] = [];
  private zoneStates = new Map<string, boolean>(); // id → searched
  /** P-0814-H：decor 实例状态（键 = "mapId|decorId"，对齐后端 decorStates；processed=true → 灰显） */
  private decorStates = new Map<string, Record<string, unknown>>();
  private hint!: Phaser.GameObjects.Text;
  /** 玩家精灵：this.physics.add.image 返回 Arcade.Image（含 arcade body / 物理方法），故类型用 Physics.Arcade.Image 而非 GameObject Image */
  private player!: Phaser.Physics.Arcade.Image;
  private nearZone: MapZone | null = null;
  private eKey: Phaser.Input.Keyboard.Key | null = null; // keyboard 插件可能为 null（headless/无输入场景），可空
  private ready = false;
  /** P-0817-F：玩家屏显缩放基准（纹理世界半径 ts*0.45 → 固定屏显半径 PLAYER_SCREEN_R） */
  private playerBaseScale = 1;
  /** 上次上报的玩家格坐标（节流：仅格变化时回调 onPlayerMove） */
  private lastReportedGx = -1;
  private lastReportedGy = -1;
  /** P-0803-E 方案 B: 挂起待恢复的搜证足迹（create() 完成前调用 restoreSearched 时暂存） */
  private pendingSearched: string[] | null = null;
  /** P-0814-H: 挂起待恢复的 decor 实例状态（create() 完成前调用 restoreDecorStates 时暂存） */
  private pendingDecorStates: Record<string, Record<string, unknown>> | null = null;
  /** P-0803-M：只读模式（禁搜证交互；氛围展示用） */
  private readonly readOnly: boolean;
  /** P-0814-H：初始 decor 实例状态（快照恢复/对局状态注入；键 = "mapId|decorId"） */
  private readonly initialDecorStates: Record<string, Record<string, unknown>>;
  /** P-0804-C：素材库场景瓦片图集 URL（SCENE_TILESET 登记；缺省 = 运行时生成色块纹理零破坏） */
  private readonly tilesetUrl?: string;
  /** P-0804-D：外部瓦片图集素材的 tile_size（解析自素材 meta_json；缺省 = 按地图契约 m.tile_size） */
  private readonly tilesetTileSize?: number;
  /** P-0804-H 续：AI 角色名单（一般模式瓦片地图显示其他角色标记） */
  private aiCharacters: string[] = [];
  /** P-0804-H 续：AI 巡逻 actor（开始后沿可通行格随机游走；含 Phaser 圆点+名字标签） */
  private aiActors: { gx: number; gy: number; px: number; py: number; tx: number; ty: number; timer: number; circle: Phaser.GameObjects.Arc; label: Phaser.GameObjects.Text; bubble: Phaser.GameObjects.Text | null; bubbleUntil: number; baseScale: number }[] = [];
  /** P-0817-G：房间模式专用状态 */
  private readonly roomMode: boolean;
  /** P-0817-Q：warp 换图后玩家出生格覆盖（[x, y]；缺省用地图 spawn_points） */
  private readonly spawnTile?: [number, number];
  private currentRoomId: string | null = null;
  private roomLabels: { text: Phaser.GameObjects.Text; roomId: string }[] = [];
  private exitsG!: Phaser.GameObjects.Graphics;
  private roomSwitching = false;
  /** P-0817-Q：传送点标记层（青色光柱 + 名字；与出口金门区分） */
  private warpG!: Phaser.GameObjects.Graphics;
  private nearWarp: MapWarp | null = null;
  private warpSwitching = false;
  private aiRoomIds: string[] = [];
  /** P-0811-G：相遇对话候选（简短台词池，按角色名确定性取） */
  private static AI_LINES: string[] = [
    '你也来了？', '今晚的月色真好', '这里似乎藏着什么…', '好久不见', '那边好像有动静', '你说我们该往哪走？',
  ];
  /** AI 巡逻开关（React 层「开始/暂停」按钮控制；P-0811-G 默认 true=进入即走动） */
  private aiMoving = true;

  constructor(map: ScriptMap, callbacks: ScriptMapSceneCallbacks, readOnly = false, tilesetUrl?: string,
              tilesetTileSize?: number, aiCharacters: string[] = [], decorStates: Record<string, Record<string, unknown>> = {},
              roomMode = false, spawnTile?: [number, number]) {
    super({ key: 'ScriptMapScene' });
    this.mapJson = normalizeMap(map) ?? map;
    this.callbacks = callbacks;
    this.readOnly = readOnly;
    this.tilesetUrl = tilesetUrl;
    this.tilesetTileSize = tilesetTileSize;
    this.aiCharacters = aiCharacters;
    this.initialDecorStates = decorStates;
    this.roomMode = roomMode;
    this.spawnTile = spawnTile;
  }

  /**
   * P-0804-C：preload 加载外部瓦片图集（素材库 SCENE_TILESET 登记）。
   * 无 tilesetUrl → 零加载，create() 仍走运行时生成色块纹理（无素材行为逐字节不变）。
   */
  preload() {
    if (this.tilesetUrl) {
      this.load.image('map-tileset-asset', this.tilesetUrl);
    }
  }

  /** 地图 JSON（外部轮询获取后重载用；正常流程创建即注入） */
  setMap(map: ScriptMap) {
    this.mapJson = normalizeMap(map) ?? map;
  }

  /** P-0817-G（房间模式）：把视图切到当前房间——相机 bounds=房间墙环、只显示本房间标签/AI/出生点/装饰。 */
  private applyRoomView(initial: boolean) {
    const m = this.mapJson;
    const room = m.rooms.find(r => r.id === this.currentRoomId);
    if (!room) return;
    const ts = this.b.ts;
    // 相机限定到房间墙环（外 1 格）
    this.cameras.main.setBounds((room.x - 1) * ts, (room.y - 1) * ts, (room.w + 2) * ts, (room.h + 2) * ts);
    if (!initial) this.cameras.main.centerOn((room.x + room.w / 2) * ts, (room.y + room.h / 2) * ts);
    // 房间标签：只显示当前房间
    for (const rl of this.roomLabels) rl.text.setVisible(rl.roomId === room.id);
    // 搜证标签：只显示当前房间内（含墙环 1 格）
    for (const label of this.zoneLabels) {
      const z = m.zones.find(zz => zz.id === label.getData('zoneId'));
      label.setVisible(!!z && z.x >= room.x - 1 && z.x <= room.x + room.w
        && z.y >= room.y - 1 && z.y <= room.y + room.h);
    }
    // AI 角色：只显示本房间
    this.aiActors.forEach((a, i) => a.circle.setVisible(this.aiRoomIds[i] === room.id));
    // 装饰层重建（只渲染本房间；旧行 Graphics 销毁防泄漏）
    this.decorRowG.forEach(g => g.destroy());
    this.decorRowG.clear();
    if (this.overlayG) this.overlayG.destroy();
    if (this.waterG) this.waterG.destroy();
    this.renderDecorLayers({ x: room.x, y: room.y, w: room.w, h: room.h });
    // 出生点重绘（只画本房间）
    this.spawnG.clear();
    for (const s of m.spawn_points) {
      if (s.x < room.x - 1 || s.x > room.x + room.w || s.y < room.y - 1 || s.y > room.y + room.h) continue;
      const cx = (s.x + 0.5) * ts, cy = (s.y + 0.5) * ts;
      const color = s.type === 'player' ? 0x38bdf8 : 0x94a3b8;
      this.spawnG.fillStyle(color, 0.55).fillCircle(cx, cy, ts * 0.22);
      this.spawnG.lineStyle(1, 0xffffff, 0.8).strokeCircle(cx, cy, ts * 0.22);
    }
  }

  /** P-0817-G（房间模式）：当前房间出口列表。 */
  private exitsOfCurrentRoom(): MapExit[] {
    return (this.mapJson.exits || []).filter(e => e.from === this.currentRoomId);
  }

  /** P-0817-G：出口门洞命中（worldX/worldY → 出口；无 → null）。 */
  private hitTestExit(wx: number, wy: number): MapExit | null {
    const ts = this.b.ts;
    for (const e of this.exitsOfCurrentRoom()) {
      const cx = (e.door[0] + 0.5) * ts, cy = (e.door[1] + 0.5) * ts;
      if (Math.abs(wx - cx) <= ts * 0.9 && Math.abs(wy - cy) <= ts * 0.9) return e;
    }
    return null;
  }

  /** P-0817-Q：传送点命中（worldX/worldY → warp；无 → null）。 */
  private hitTestWarp(wx: number, wy: number): MapWarp | null {
    const ts = this.b.ts;
    for (const w of this.mapJson.warps || []) {
      const cx = (w.from[0] + 0.5) * ts, cy = (w.from[1] + 0.5) * ts;
      if (Math.abs(wx - cx) <= ts * 0.9 && Math.abs(wy - cy) <= ts * 0.9) return w;
    }
    return null;
  }

  /** P-0817-Q：触发传送（淡出 → 回调 React 层切换地图；重建后淡入由新 scene 自然呈现）。 */
  private triggerWarp(w: MapWarp) {
    if (this.warpSwitching) return;
    this.warpSwitching = true;
    this.cameras.main.fadeOut(180, 0, 0, 0);
    this.cameras.main.once(Phaser.Cameras.Scene2D.Events.FADE_OUT_COMPLETE, () => {
      this.callbacks.onWarp?.(w.to[0], w.to[1], w.to[2]);
    });
  }

  /** P-0817-G：走门切换到相邻房间（淡出 → 切视图/定位 → 淡入；回调 React 高亮小地图）。 */
  private switchToRoom(toId: string, door: [number, number]) {
    if (this.roomSwitching || !this.roomMode) return;
    this.roomSwitching = true;
    this.cameras.main.fadeOut(180, 0, 0, 0);
    this.cameras.main.once(Phaser.Cameras.Scene2D.Events.FADE_OUT_COMPLETE, () => {
      this.currentRoomId = toId;
      const ts = this.b.ts;
      this.player.setPosition((door[0] + 0.5) * ts, (door[1] + 0.5) * ts);
      this.applyRoomView(false);
      this.callbacks.onRoomChange?.(toId);
      this.cameras.main.fadeIn(180, 0, 0, 0);
      this.cameras.main.once(Phaser.Cameras.Scene2D.Events.FADE_IN_COMPLETE, () => { this.roomSwitching = false; });
    });
  }

  /**
   * P-0814-G：把装饰渲染计划画到场景（water 叠加 → objects/decor/spawnMarkers 行层 → overlay 遮罩）。
   * 契约 v0.2 新键缺失 = 空计划 = 零渲染（v1 地图逐像素零回归）；仅 create() 调用一次（静态装饰）。
   */
  private renderDecorLayers(room?: { x: number; y: number; w: number; h: number }) {
    const m = this.mapJson;
    const ts = this.b.ts;
    const plan = buildDecorPlan(m);
    // P-0817-G（房间模式）：只渲染当前房间内的装饰（含房间墙环 1 格内）
    const inRoom = (x: number, y: number) => {
      if (!room) return true;
      return x >= room.x - 1 && x <= room.x + room.w && y >= room.y - 1 && y <= room.y + room.h;
    };

    // ⑤ tileProps water=true：蓝色半透明叠加（depth 0.5，ground 之上装饰之下）
    const water = plan.water.filter(w => inRoom(w.x, w.y));
    if (water.length > 0) {
      this.waterG = this.add.graphics().setDepth(DEPTH_WATER);
      for (const w of water) {
        this.waterG.fillStyle(C_WATER_BLUE, 0.35);
        this.waterG.fillRect(w.x * ts, w.y * ts, ts, ts);
      }
    }

    // ①+②+③ objects / spawnMarkers / decor：按行分组画（depth = 1 + (y+0.5)/H，北→南遮挡）
    for (const item of plan.items) {
      if (!inRoom(item.x, item.y)) continue;
      let g = this.decorRowG.get(item.y);
      if (!g) {
        g = this.add.graphics();
        g.setDepth(item.depth);
        this.decorRowG.set(item.y, g);
      }
      drawDecorCmds(g, item.cmds, item.x * ts, item.y * ts, ts);
    }

    // ④ layers.overlay：前景遮罩（永远盖住角色，depth=5；canopy → 深绿 alpha 0.35）
    const overlay = plan.overlay.filter(o => inRoom(o.x, o.y));
    if (overlay.length > 0) {
      this.overlayG = this.add.graphics().setDepth(DEPTH_OVERLAY);
      for (const ov of overlay) {
        this.overlayG.fillStyle(ov.style.fill, ov.style.alpha);
        this.overlayG.fillRect(ov.x * ts, ov.y * ts, ts, ts);
      }
    }
  }

  /** P-0804-H 续：React 层控制 AI 巡逻开关（开始/暂停按钮） */
  setAiMoving(moving: boolean) {
    this.aiMoving = moving;
  }

  /** P-0804-H 续：AI 巡逻——每个 actor 定时随机选相邻可通行格，平滑移动 */
  private updateAiActors() {
    // P-0817-F：人物固定比例尺——即使暂停巡逻，缩放时 AI 屏显尺寸也保持恒定
    const zoom = this.cameras.main.zoom;
    for (const a of this.aiActors) a.circle.setScale(a.baseScale / zoom);
    if (!this.aiMoving || this.aiActors.length === 0) return;
    const ts = this.b.ts;
    const col = this.mapJson.layers?.collision as number[][] | undefined;
    const W = this.mapJson.width, H = this.mapJson.height;
    const dt = this.game.loop.delta / 1000;
    for (const a of this.aiActors) {
      // 到达目标 → 停一会，再选新目标（相邻可通行格）
      const dx = a.tx - a.px, dy = a.ty - a.py;
      const dist = Math.hypot(dx, dy);
      if (dist < 2) {
        a.timer -= dt;
        if (a.timer <= 0) {
          const dirs = [[1,0],[-1,0],[0,1],[0,-1]];
          const opts: [number, number][] = [];
          for (const [ox, oy] of dirs) {
            const nx = a.gx + ox, ny = a.gy + oy;
            if (nx >= 1 && nx < (W || 0) - 1 && ny >= 1 && ny < (H || 0) - 1) {
              const c = col ? col[ny]?.[nx] : 0;
              if (c === 0 || c === undefined) opts.push([nx, ny]);
            }
          }
          if (opts.length > 0) {
            const pick = opts[Math.floor(Math.random() * opts.length)];
            a.gx = pick[0]; a.gy = pick[1];
            a.tx = (a.gx + 0.5) * ts; a.ty = (a.gy + 0.5) * ts;
          }
          a.timer = 0.8 + Math.random() * 1.6;
        }
      } else {
        // 平滑移动（60px/s ≈ 2 格/s）
        const spd = 60 * dt;
        const step = Math.min(spd, dist);
        a.px += (dx / dist) * step;
        a.py += (dy / dist) * step;
      }
      a.circle.setPosition(a.px, a.py);
      // P-0814-G：AI 角色圆点 y 连续深度（北侧装饰遮挡之，南侧装饰被其遮挡）
      a.circle.setDepth(charDepth(a.py, this.mapPxH));
      // P-0817-F：按相机 zoom 反向缩放 AI 视觉（世界缩放、人物屏显恒定）
      a.circle.setScale(a.baseScale / zoom);
      a.label.setPosition(a.px, a.py - ts * 0.42);
      // 气泡跟随（若有）
      if (a.bubble) a.bubble.setPosition(a.px, a.py - ts * 0.95);
    }
    // P-0811-G：相遇对话——任意两 actor 格距 ≤1 时，各弹一句简短台词（气泡 4s 后消失）
    const now = this.time.now;
    for (let i = 0; i < this.aiActors.length; i++) {
      const ai = this.aiActors[i];
      for (let j = i + 1; j < this.aiActors.length; j++) {
        const a = this.aiActors[i], b = this.aiActors[j];
        const d = Math.max(Math.abs(a.gx - b.gx), Math.abs(a.gy - b.gy));
        if (d <= 1) {
          if (a.bubbleUntil < now && b.bubbleUntil < now) {
            const line = ScriptMapScene.AI_LINES[(a.gx * 7 + b.gy * 13 + i * 3 + j) % ScriptMapScene.AI_LINES.length];
            this.showBubble(a, line, now);
            this.showBubble(b, ScriptMapScene.AI_LINES[(b.gx * 7 + a.gy * 13 + j * 3 + i) % ScriptMapScene.AI_LINES.length], now);
          }
        }
      }
      if (ai.bubble && now > ai.bubbleUntil) { ai.bubble.destroy(); ai.bubble = null; }
    }
  }

  /** P-0811-G：角色头顶弹对话气泡（4s 后由 updateAiActors 清理） */
  private showBubble(actor: { bubble: Phaser.GameObjects.Text | null; bubbleUntil: number; px: number; py: number }, line: string, now: number) {
    const ts = this.b.ts;
    if (actor.bubble) actor.bubble.destroy();
    actor.bubble = this.add.text(actor.px, actor.py - ts * 0.95, line, {
      fontFamily: 'sans-serif', fontSize: '11px', color: '#ffffff', backgroundColor: '#000000cc',
      padding: { x: 4, y: 2 },
    }).setOrigin(0.5, 1).setDepth(9);
    actor.bubbleUntil = now + 4000;
  }
  /** 搜证成功 → 热点变绿（已搜证标记，React 组件在 search 成功后调用） */
  markZoneSearched(id: string) {
    this.zoneStates.set(id, true);
    const label = this.zoneLabels.find(t => t.getData('zoneId') === id);
    if (label) label.setColor('#3ddc84');
  }

  /**
   * P-0803-E 方案 B: 按搜证足迹恢复绿点（zone.clue_location ∈ locations → 已搜证态）。
   * 可在 create() 完成前调用（暂存，create 末尾执行）；幂等。
   */
  restoreSearched(locations: string[] | undefined | null) {
    if (!locations || locations.length === 0) return;
    if (!this.ready) {
      this.pendingSearched = locations;
      return;
    }
    const set = new Set(locations);
    for (const z of this.mapJson.zones) {
      if (z.clue_location && set.has(z.clue_location)) this.markZoneSearched(z.id);
    }
  }

  /**
   * P-0814-H：decor 交互物命中（worldX/worldY，摄像头变换后坐标）——
   * 与后端半径判定同源（decorInRange：Chebyshev |dx|≤r 且 |dy|≤r，r=decor.radius||1）；
   * 命中返回该 decor（已处理也返回 —— 后端返回「已处理」语义，前端灰显提示）。
   */
  private hitTestDecor(wx: number, wy: number): MapDecorItem | null {
    const ts = this.b.ts;
    const gx = Math.floor(wx / ts);
    const gy = Math.floor(wy / ts);
    for (const d of this.mapJson.decor || []) {
      if (decorInRange(d, gx, gy)) return d;
    }
    return null;
  }

  /** P-0814-H：decor 交互（只读模式拦截；回传玩家格坐标供后端半径校验） */
  private interactDecor(d: MapDecorItem) {
    if (this.readOnly) {
      this.hint.setText(`🔎 ${d.type}（只读模式，无交互）`).setColor('#7dd3fc');
      return;
    }
    const ts = this.b.ts;
    const gx = Math.floor(this.player.x / ts);
    const gy = Math.floor(this.player.y / ts);
    const key = decorStateKey(this.mapJson.map_id, d.id);
    const st = this.decorStates.get(key);
    const processed = !!st?.processed;
    this.hint.setText(processed
      ? `✅ ${d.type}（该处已处理过）`
      : `🖱 交互中：${d.type}（${d.id}）...`).setColor(processed ? '#3ddc84' : '#ffe08a');
    this.callbacks.onDecorInteract?.(d, gx, gy);
  }

  /** P-0814-H：once 交互成功后置灰（React 层在交互响应 processed=true 时调用） */
  markDecorProcessed(decorId: string) {
    const key = decorStateKey(this.mapJson.map_id, decorId);
    this.decorStates.set(key, { ...(this.decorStates.get(key) || {}), processed: true });
  }

  /**
   * P-0814-H：按实例状态恢复灰显（对局状态/快照注入；create 完成前幂等挂起，对齐 restoreSearched 模式）。
   * states 键 = "mapId|decorId"（后端 decorStates 键）。
   */
  restoreDecorStates(states: Record<string, Record<string, unknown>> | undefined | null) {
    if (!states || Object.keys(states).length === 0) return;
    if (!this.ready) {
      this.pendingDecorStates = states;
      return;
    }
    for (const [k, v] of Object.entries(states)) {
      if (v && typeof v === 'object') this.decorStates.set(k, v as Record<string, unknown>);
    }
  }

  /** 冒烟自测用：Scene create() 是否已完成（对齐阶段 1 SimulationScene.isReady 模式） */
  isReady(): boolean {
    return this.ready;
  }

  create() {
    const m = this.mapJson;
    // P-0804-D：有外部瓦片图集素材时，地图格子/切片/相机边界（mapPxW = width × ts）全按素材 tile_size
    // （meta_json 解析值主导，真机 oga_topdown 为 16px 瓦片）；无素材回退契约 m.tile_size（默认 32，零破坏）
    // P-0804-H：瓦片渲染尺寸 ×2（单个瓦片放大两倍，营造大地图感——视口 800×320 只显示地图局部）
    const ts = ((this.tilesetUrl && this.tilesetTileSize) ? this.tilesetTileSize : (m.tile_size || 32)) * 2;
    const mapPxW = m.width * ts;
    const mapPxH = m.height * ts;

    // P-0817-F（人物固定比例尺）：基准缩放按视口跨 ~18 格自适应（大图自动缩小视野更广、
    // 小图不放大），人物屏显尺寸恒定、地图随人物缩放；bounds/centerOn 配合相机跟随营造大地图感
    const baseZoom = Phaser.Math.Clamp(CANVAS_W / (BASE_ZOOM_TILES_WIDE * ts), ZOOM_MIN, 1.2);
    this.cameras.main.setZoom(baseZoom);
    // P-0803-E（调研项 2 相机跟随）：边界 clamp + 初始居中；startFollow 在玩家创建后启用
    this.cameras.main.setBounds(0, 0, mapPxW, mapPxH);
    this.cameras.main.centerOn(mapPxW / 2, mapPxH / 2);
    // P-0803-E（调研项 2 滚轮缩放增强）+ P-0817-F：滚轮缩放世界比例（人物屏显恒定），范围 [0.3, 1.5]
    this.input.on('wheel', (_p: Phaser.Input.Pointer, _go: unknown, _dx: number, dy: number) => {
      const cam = this.cameras.main;
      const z = Phaser.Math.Clamp(cam.zoom + (dy > 0 ? -0.12 : 0.12), ZOOM_MIN, ZOOM_MAX);
      cam.setZoom(z);
    });

    // ── 瓦片纹理：素材库图集优先（P-0804-C），无素材回退运行时生成（既有 5 色块语义零变化） ──
    // 外部图集按 ts 切片（P-0804-D：素材 tile_size 主导；非素材场景 ts=契约 m.tile_size）；格数不足的索引渲染为空块（用户素材需覆盖契约 5 格）
    const texKey = this.tilesetUrl && this.textures.exists('map-tileset-asset')
      ? 'map-tileset-asset'
      : this.createTilesetTexture(ts);

    // ── 瓦片地面层 + 隐藏碰撞层（对齐阶段 0 buildMap） ──
    const map = this.make.tilemap({ data: m.layers.ground as unknown as number[][], tileWidth: ts, tileHeight: ts });
    const tileset = map.addTilesetImage(texKey, texKey, ts, ts, 0, 0);
    // null 收窄：纹理键为我们自己运行时生成，addTilesetImage 失败属于异常状态，直接抛错（避免把 null 传给 createLayer）
    if (!tileset) throw new Error('tileset 创建失败：' + texKey);
    map.createLayer(0, tileset, 0, 0);
    const collLayer = map.createBlankLayer('collision', tileset, 0, 0);
    // null 收窄：createBlankLayer 返回 TilemapLayer | null，后续 putTileAt/setVisible/setCollisionByExclusion/collider 均需非空
    if (!collLayer) throw new Error('碰撞层创建失败');
    const coll = m.layers.collision;
    for (let y = 0; y < coll.length; y++) {
      for (let x = 0; x < coll[y].length; x++) {
        if (coll[y][x]) collLayer.putTileAt(1, x, y);
      }
    }
    collLayer.setVisible(false);
    collLayer.setCollisionByExclusion([-1]);
    this.physics.world.setBounds(0, 0, mapPxW, mapPxH);
    this.b = { ox: 0, oy: 0, ts, map };
    this.mapPxH = mapPxH;

    // P-0814-G：装饰多层渲染（water → objects/decor/markers → overlay；新键缺失零渲染）
    this.renderDecorLayers();

    // ── 房间名标注 ──
    for (const r of m.rooms) {
      if (!r.name) continue;
      const label = this.add.text((r.x + r.w / 2) * ts, r.y * ts - 4, r.name, {
        fontFamily: 'sans-serif', fontSize: '13px', color: '#e2e8f0', backgroundColor: '#00000066',
        padding: { x: 4, y: 1 },
      }).setOrigin(0.5, 1).setDepth(4);
      // P-0817-G（房间模式）：存房间标签（房间模式只显示当前房间）
      this.roomLabels.push({ text: label, roomId: r.id });
    }

    // ── 玩家出生点（P-0817-Q：warp 换图用 spawnTile 覆盖；缺省 spawn_points type=player） ──
    const playerSpawn = this.spawnTile
      ? { x: this.spawnTile[0], y: this.spawnTile[1] }
      : (m.spawn_points.find(s => s.type === 'player') ?? m.spawn_points[0]);
    const px = playerSpawn ? (playerSpawn.x + 0.5) * ts : ts * 1.5;
    const py = playerSpawn ? (playerSpawn.y + 0.5) * ts : ts * 1.5;
    const playerTex = this.createDotTexture('player-dot', 0x38bdf8, ts * 0.45);
    this.player = this.physics.add.image(px, py, playerTex);
    // P-0817-F：玩家固定比例尺——屏显半径恒为 PLAYER_SCREEN_R（纹理世界半径 ts*0.45 × 缩放 × 相机 zoom）
    this.playerBaseScale = PLAYER_SCREEN_R / (ts * 0.45);
    this.player.setScale(this.playerBaseScale / baseZoom);
    this.player.setCollideWorldBounds(true);
    // P-0814-G：玩家按 y 连续深度与装饰互遮挡（depth = 1 + py/mapPxH + 层偏移；每帧 update 刷新）
    this.player.setDepth(charDepth(py, mapPxH));
    // arcade body 可空：physics.add.image 后 body 一般立即可用，但类型上为 Body | null，用可选链 + 非空守卫
    const pBody = this.player.body as Phaser.Physics.Arcade.Body | null;
    if (pBody) pBody.setCircle(ts * 0.3);
    this.physics.add.collider(this.player, collLayer);
    // P-0803-E（调研项 2）：玩家创建后启用相机跟随（地图大时局部视野漫游；地图小时 clamped 居中不抖）
    this.cameras.main.startFollow(this.player, true, 0.1, 0.1);
    this.add.text(px, py - ts * 0.7, '玩家', { fontFamily: 'sans-serif', fontSize: '11px', color: '#38bdf8' })
      .setOrigin(0.5).setDepth(10);

    // WASD 控制
    // keyboard 插件可能为 null（类型可空）；无键盘时跳过按键注册，update() 内对 keys 做非空守卫
    const keys = this.input.keyboard?.addKeys('W,A,S,D,UP,LEFT,DOWN,RIGHT') as Record<string, Phaser.Input.Keyboard.Key> | undefined;
    if (keys) this.registry.set('keys', keys);

    // ── 热点（zones） ──
    this.zoneG = this.add.graphics().setDepth(8);
    // P-0814-H：decor 交互高亮层（靠近金框 / 已处理灰盖；位于热点 8 之上、标签 9 之下）
    this.decorG = this.add.graphics().setDepth(8.5);
    this.spawnG = this.add.graphics().setDepth(7);
    // P-0817-G（房间模式）：出口门标记层（金门+箭头，位于热点/标签之上）
    this.exitsG = this.add.graphics().setDepth(9.5);
    // P-0817-Q（外部/内部分离）：传送点标记层（青色光柱，高于出口金门便于辨识）
    this.warpG = this.add.graphics().setDepth(9.6);
    for (const z of m.zones) {
      const searched = this.zoneStates.get(z.id);
      const label = this.add.text((z.x + 0.5) * ts, (z.y + 0.5) * ts + ts * 0.8, z.name, {
        fontFamily: 'sans-serif', fontSize: '11px', color: searched ? '#3ddc84' : '#ffe08a', backgroundColor: '#00000088',
        padding: { x: 3, y: 1 },
      }).setOrigin(0.5).setDepth(9);
      label.setData('zoneId', z.id);
      this.zoneLabels.push(label);
    }

    // 出生点标记（玩家蓝 / NPC 灰）
    for (const s of m.spawn_points) {
      const cx = (s.x + 0.5) * ts, cy = (s.y + 0.5) * ts;
      const color = s.type === 'player' ? 0x38bdf8 : 0x94a3b8;
      this.spawnG.fillStyle(color, 0.55).fillCircle(cx, cy, ts * 0.22);
      this.spawnG.lineStyle(1, 0xffffff, 0.8).strokeCircle(cx, cy, ts * 0.22);
      if (s.type === 'npc') {
        this.add.text(cx, cy - ts * 0.35, 'AI', { fontFamily: 'sans-serif', fontSize: '9px', color: '#cbd5e1' })
          .setOrigin(0.5).setDepth(8);
      }
    }

    // P-0804-H 续：AI 角色标记（一般模式瓦片地图显示其他角色）——优先 npc 出生点，不足用玩家出生点旁环绕；
    // 存为可移动 actor（开始/暂停巡逻）
    const npcSpawns = m.spawn_points.filter((s: any) => s.type === 'npc');
    const baseSpawns = npcSpawns.length > 0 ? npcSpawns : m.spawn_points;
    this.aiCharacters.forEach((name, idx) => {
      const sp = baseSpawns.length > 0 ? baseSpawns[idx % baseSpawns.length] : null;
      const ax = sp ? (sp.x + 0.5) * ts : ts * (1.5 + idx);
      const ay = sp ? (sp.y + 0.5) * ts : ts * (1.5 + idx);
      const colors = [0xf472b6, 0x4ade80, 0xc084fc, 0xfbbf24, 0x22d3ee, 0xfb7185];
      const ac = colors[idx % colors.length];
      const circle = this.add.circle(ax, ay, ts * 0.28, ac, 0.6);
      circle.setStrokeStyle(1, 0xffffff, 0.8);
      // P-0817-F：AI 固定比例尺（屏显半径恒为 AI_SCREEN_R，缩放不改变人物大小）
      const aiBaseScale = AI_SCREEN_R / (ts * 0.28);
      circle.setScale(aiBaseScale / baseZoom);
      // P-0814-G：AI 角色圆点按 y 连续深度（与装饰互遮挡）；名字标签保留固定深度 8 恒可见
      circle.setDepth(charDepth(ay, mapPxH));
      const label = this.add.text(ax, ay - ts * 0.42, name, { fontFamily: 'sans-serif', fontSize: '10px', color: '#ffffff', backgroundColor: '#00000088', padding: { x: 2, y: 0 } })
        .setOrigin(0.5).setDepth(8);
      const gx = sp ? sp.x : 1 + idx, gy = sp ? sp.y : 1 + idx;
      const actorRoom = m.rooms.find(r => gx >= r.x && gx < r.x + r.w && gy >= r.y && gy < r.y + r.h)?.id ?? '';
      this.aiRoomIds.push(actorRoom);
      this.aiActors.push({ gx, gy, px: ax, py: ay, tx: ax, ty: ay, timer: 0.8 + idx * 0.6, circle, label, bubble: null, bubbleUntil: 0, baseScale: aiBaseScale });
    });

    // 提示条 + 标题
    this.hint = this.add.text(CANVAS_W / 2, CANVAS_H - 12, '', {
      fontFamily: 'sans-serif', fontSize: '13px', color: '#ffe08a', backgroundColor: '#000000bb',
      padding: { x: 10, y: 5 },
    }).setOrigin(0.5, 1).setScrollFactor(0).setDepth(20);
    this.add.text(10, 8, `🗺️ ${m.name}（${m.map_id}）`, {
      fontFamily: 'sans-serif', fontSize: '13px', color: '#38bdf8', backgroundColor: '#00000088',
      padding: { x: 6, y: 3 },
    }).setScrollFactor(0).setDepth(20);
    // P-0814-I：本地漫游定位提示（WASD 移动仅前端本地渲染，不上报服务器权威移动；等产品意图确认再接后端）
    this.add.text(10, 28, '📍 本地漫游模式：移动仅本地显示，不上报服务器', {
      fontFamily: 'sans-serif', fontSize: '11px', color: '#94a3b8', backgroundColor: '#00000066',
      padding: { x: 6, y: 3 },
    }).setScrollFactor(0).setDepth(20);

    // 点击热点 → 搜证（P-0803-M：只读模式不注册搜证交互）；P-0814-H：decor 交互物优先于热点
    if (!this.readOnly) {
      this.input.on('pointerdown', (p: Phaser.Input.Pointer) => {
        // P-0817-G（房间模式）：出口门优先于 decor/热点（点门 = 走门切换）
        if (this.roomMode) {
          const ex = this.hitTestExit(p.worldX, p.worldY);
          if (ex) {
            this.switchToRoom(ex.to, ex.door);
            return;
          }
        }
        // P-0817-Q（外部/内部分离）：传送点命中优先于 decor/热点（点门 = 进屋/出屋切图）
        const w = this.hitTestWarp(p.worldX, p.worldY);
        if (w) {
          this.triggerWarp(w);
          return;
        }
        const d = this.hitTestDecor(p.worldX, p.worldY);
        if (d) {
          this.interactDecor(d);
          return;
        }
        const z = this.hitTest(p.worldX, p.worldY);
        if (z) this.interact(z);
      });
    }
    this.eKey = this.input.keyboard?.addKey(Phaser.Input.Keyboard.KeyCodes.E) ?? null;

    // P-0814-H：初始 decor 实例状态（对局状态/快照恢复注入；create 完成前幂等挂起）
    if (Object.keys(this.initialDecorStates).length > 0) {
      this.restoreDecorStates(this.initialDecorStates);
    }

    // P-0817-G（房间模式）：初始房间 = 玩家出生所在房间；无房间数据则退化为整图模式
    if (this.roomMode) {
      const spawnRoom = m.rooms.find(r =>
        px >= r.x * ts && px < (r.x + r.w) * ts && py >= r.y * ts && py < (r.y + r.h) * ts);
      this.currentRoomId = (spawnRoom?.id) ?? m.rooms[0]?.id ?? null;
      if (this.currentRoomId) {
        this.applyRoomView(true);
        this.callbacks.onRoomChange?.(this.currentRoomId);
      }
    }

    this.ready = true;
    // P-0803-E 方案 B: create 完成前挂起的足迹恢复（幂等）
    if (this.pendingSearched) {
      const p = this.pendingSearched;
      this.pendingSearched = null;
      this.restoreSearched(p);
    }
    // P-0814-H: create 完成前挂起的 decor 实例状态恢复（幂等）
    if (this.pendingDecorStates) {
      const p = this.pendingDecorStates;
      this.pendingDecorStates = null;
      this.restoreDecorStates(p);
    }
  }

  /** 运行时瓦片纹理：5 色块横排（契约 tile_count=5） */
  private createTilesetTexture(ts: number): string {
    const key = `map-tiles-${ts}`;
    if (this.textures.exists(key)) return key;
    const g = this.make.graphics({ x: 0, y: 0 }, false);
    for (let i = 1; i <= 5; i++) {
      g.fillStyle(tileColor(i), 1);
      g.fillRect((i - 1) * ts, 0, ts, ts);
      g.lineStyle(1, 0x000000, 0.35);
      g.strokeRect((i - 1) * ts, 0, ts, ts);
    }
    g.generateTexture(key, ts * 5, ts);
    g.destroy();
    return key;
  }

  /** 单色圆点纹理（玩家/AI） */
  private createDotTexture(key: string, color: number, r: number): string {
    if (this.textures.exists(key)) return key;
    const g = this.make.graphics({ x: 0, y: 0 }, false);
    g.fillStyle(color, 1);
    g.fillCircle(r, r, r);
    g.lineStyle(2, 0xffffff, 0.9);
    g.strokeCircle(r, r, r);
    g.generateTexture(key, r * 2, r * 2);
    g.destroy();
    return key;
  }

  /** 热点命中（worldX/worldY，摄像头变换后坐标） */
  private hitTest(wx: number, wy: number): MapZone | null {
    const ts = this.b.ts;
    for (const z of this.mapJson.zones) {
      const r = Math.max((z.radius || 1) * ts, ts * 0.6);
      const cx = (z.x + 0.5) * ts, cy = (z.y + 0.5) * ts;
      if (wx >= cx - r && wx <= cx + r && wy >= cy - r && wy <= cy + r) return z;
    }
    return null;
  }

  private interact(z: MapZone) {
    // P-0803-M：只读模式防御性拦截（氛围展示，无搜证）
    if (this.readOnly) {
      this.hint.setText(`🔭 ${z.name}`).setColor('#7dd3fc');
      return;
    }
    if (this.zoneStates.get(z.id)) {
      this.hint.setText(`✅ ${z.name}（该处已搜证过）`).setColor('#3ddc84');
      return;
    }
    this.hint.setText(`🔍 搜证中：${z.name}（${z.clue_location || '未知地点'}）...`).setColor('#ffe08a');
    this.callbacks.onSearch(z);
  }

  /** 渲染统计（冒烟自测用） */
  getStats() {
    return {
      zones: this.mapJson.zones.length,
      spawns: this.mapJson.spawn_points.length,
      rooms: this.mapJson.rooms.length,
      width: this.mapJson.width,
      height: this.mapJson.height,
    };
  }

  update() {
    if (!this.ready) return;
    // P-0804-H 续：AI 巡逻（不依赖键盘；开始/暂停由 React 层控制）
    this.updateAiActors();
    // WASD / 方向键移动（keys 非空守卫：无键盘插件时跳过移动逻辑）
    const keys = this.registry.get('keys') as Record<string, Phaser.Input.Keyboard.Key> | undefined;
    if (!keys) return;
    const speed = 150;
    let vx = 0, vy = 0;
    if (keys.A.isDown || keys.LEFT.isDown) vx = -1;
    else if (keys.D.isDown || keys.RIGHT.isDown) vx = 1;
    if (keys.W.isDown || keys.UP.isDown) vy = -1;
    else if (keys.S.isDown || keys.DOWN.isDown) vy = 1;
    if (vx !== 0 && vy !== 0) { vx *= 0.7071; vy *= 0.7071; }
    this.player.setVelocity(vx * speed, vy * speed);

    const ts = this.b.ts;
    const px = this.player.x, py = this.player.y;
    // P-0814-G：玩家 y 连续深度每帧刷新（与 objects/decor 互遮挡；星露谷 standingY/10000 范式）
    this.player.setDepth(charDepth(py, this.mapPxH));
    // P-0817-F：每帧按相机 zoom 反向缩放玩家视觉——世界缩放、人物屏显恒定；
    // Arcade body 保持世界尺寸（ts*0.3）不受视觉缩放影响，碰撞语义不变
    this.player.setScale(this.playerBaseScale / this.cameras.main.zoom);

    // 脉冲热点 + 靠近检测（对齐阶段 0 zoneScene）；pulse 用于未靠近热点的呼吸透明度
    const pulse = 0.35 + 0.25 * Math.sin(this.time.now / 250);
    this.zoneG.clear();
    let near: MapZone | null = null;
    // P-0817-G（房间模式）：只渲染/检测当前房间内的搜证点（含墙环 1 格）
    const room = this.roomMode && this.currentRoomId
      ? this.mapJson.rooms.find(r => r.id === this.currentRoomId) : undefined;
    const inRoom = (x: number, y: number) => !room
      || (x >= room.x - 1 && x <= room.x + room.w && y >= room.y - 1 && y <= room.y + room.h);
    for (const z of this.mapJson.zones) {
      if (!inRoom(z.x, z.y)) continue;
      const searched = this.zoneStates.get(z.id);
      const cx = (z.x + 0.5) * ts, cy = (z.y + 0.5) * ts;
      const r = Math.max((z.radius || 1) * ts, ts * 0.6);
      const dist = Math.hypot(px - cx, py - cy);
      if (dist <= r + ts * 0.4) near = z;
      if (searched) {
        this.zoneG.fillStyle(0x3ddc84, 0.28).fillRoundedRect(cx - r, cy - r, r * 2, r * 2, 6);
        this.zoneG.lineStyle(2, 0x3ddc84, 0.9).strokeRoundedRect(cx - r, cy - r, r * 2, r * 2, 6);
      } else {
        this.zoneG.fillStyle(0xffd166, near === z ? 0.5 : pulse).fillRoundedRect(cx - r, cy - r, r * 2, r * 2, 6);
        this.zoneG.lineStyle(2, near === z ? 0xffffff : 0xffd166, 0.9).strokeRoundedRect(cx - r, cy - r, r * 2, r * 2, 6);
      }
    }
    this.nearZone = near;

    // P-0814-H：decor 交互物 —— 靠近检测（Chebyshev 半径与后端同源）+ 高亮（已处理灰盖 / 靠近金框）
    const gx = Math.floor(px / ts), gy = Math.floor(py / ts);
    let nearD: MapDecorItem | null = null;
    this.decorG.clear();
    for (const d of this.mapJson.decor || []) {
      if (!inRoom(d.tile[0], d.tile[1])) continue;
      const key = decorStateKey(this.mapJson.map_id, d.id);
      const processed = !!this.decorStates.get(key)?.processed;
      const [dx, dy] = d.tile;
      const inRange = decorInRange(d, gx, gy);
      if (inRange) nearD = d;
      if (processed) {
        // 已处理：灰盖 + 绿框（一次性语义，对齐搜证绿点风格）
        this.decorG.fillStyle(0x334155, 0.5).fillRect(dx * ts, dy * ts, ts, ts);
        this.decorG.lineStyle(2, 0x3ddc84, 0.8).strokeRect(dx * ts, dy * ts, ts, ts);
        if (inRange) this.decorG.fillStyle(0x3ddc84, 0.22).fillRect(dx * ts, dy * ts, ts, ts);
      } else if (inRange) {
        // 靠近：呼吸金框（脉冲与热点同频）
        const a = 0.5 + 0.3 * Math.sin(this.time.now / 200);
        this.decorG.lineStyle(2, 0xffd166, a).strokeRoundedRect(dx * ts + 2, dy * ts + 2, ts - 4, ts - 4, 4);
      }
    }

    // P-0817-G（房间模式）：出口门标记（金门+箭头） + 走门自动切换
    let nearExit: MapExit | null = null;
    if (this.roomMode && this.currentRoomId) {
      const exits = this.exitsOfCurrentRoom();
      this.exitsG.clear();
      for (const e of exits) {
        const [ex, ey] = e.door;
        const cx = (ex + 0.5) * ts, cy = (ey + 0.5) * ts;
        this.exitsG.fillStyle(0xffd166, 0.28).fillRoundedRect(ex * ts + 2, ey * ts + 2, ts - 4, ts - 4, 5);
        this.exitsG.lineStyle(2, 0xffd166, 0.95).strokeRoundedRect(ex * ts + 2, ey * ts + 2, ts - 4, ts - 4, 5);
        // 指向房间内部的箭头（按 side 反向：门在外环，箭头指向内）
        const dir = e.side === 'top' ? [0, 1] : e.side === 'bottom' ? [0, -1]
          : e.side === 'left' ? [1, 0] : [-1, 0];
        const ax = cx + dir[0] * ts * 0.22, ay = cy + dir[1] * ts * 0.22;
        this.exitsG.fillStyle(0xffd166, 1);
        this.exitsG.fillTriangle(ax - ts * 0.12, ay + ts * 0.12, ax + ts * 0.12, ay + ts * 0.12, ax, ay - ts * 0.16);
        if (Math.abs(gx - ex) <= 1 && Math.abs(gy - ey) <= 1) nearExit = e;
      }
      // 玩家踩中门洞 → 自动走门切换（经典 RPG 手感）
      if (!this.roomSwitching && nearExit) {
        const hit = exits.find(e => e.door[0] === gx && e.door[1] === gy);
        if (hit) this.switchToRoom(hit.to, hit.door);
      }
    }

    // P-0817-Q（外部/内部分离）：传送点标记（青色光柱 + 名字） + 踩中自动切图
    this.nearWarp = null;
    const warps = this.mapJson.warps || [];
    this.warpG.clear();
    for (const w of warps) {
      const [wx, wy] = w.from;
      const cx = (wx + 0.5) * ts, cy = (wy + 0.5) * ts;
      this.warpG.fillStyle(0x22d3ee, 0.25).fillRoundedRect(wx * ts + 2, wy * ts + 2, ts - 4, ts - 4, 5);
      this.warpG.lineStyle(2, 0x22d3ee, 0.95).strokeRoundedRect(wx * ts + 2, wy * ts + 2, ts - 4, ts - 4, 5);
      this.warpG.fillStyle(0x67e8f9, 0.6).fillCircle(cx, cy, ts * 0.16);
      if (Math.abs(gx - wx) <= 1 && Math.abs(gy - wy) <= 1) this.nearWarp = w;
    }
    if (!this.warpSwitching && this.nearWarp) {
      const hit = warps.find(w => w.from[0] === gx && w.from[1] === gy);
      if (hit) this.triggerWarp(hit);
    }

    const searchedCount = this.mapJson.zones.filter(z => this.zoneStates.get(z.id)).length;
    if (this.readOnly) {
      // P-0803-M：只读氛围模式 —— 不提示搜证操作
      const mm = this.mapJson;
      this.hint.setText(`🔭 地图浏览（只读 · 氛围展示）：${mm.name}（${mm.width}×${mm.height}）`).setColor('#7dd3fc');
      return;
    }
    // P-0817-Q：传送提示优先；P-0817-G：出口提示次之；P-0814-H：decor 交互物再次之
    if (this.nearWarp) {
      this.hint.setText(`🚪 传送：进入 ${this.nearWarp.to[0]} —— 走进去 / 点击 或 按 E 切换`).setColor('#67e8f9');
    } else if (nearExit) {
      const toRoom = this.mapJson.rooms.find(r => r.id === nearExit.to);
      this.hint.setText(`🚪 出口：${toRoom?.name || nearExit.to} —— 走进去 / 点击 或 按 E 切换`).setColor('#ffe08a');
    } else if (nearD) {
      const key = decorStateKey(this.mapJson.map_id, nearD.id);
      const processed = !!this.decorStates.get(key)?.processed;
      this.hint.setText(processed
        ? `✅ ${nearD.type}（该处已处理过）—— 点击查看`
        : `🖱 靠近交互物：${nearD.type}（${nearD.id}）—— 点击 或 按 E 交互`).setColor(processed ? '#3ddc84' : '#ffe08a');
    } else if (near) {
      this.hint.setText(`🔍 靠近搜证点：${near.name} —— 点击该区域 或 按 E 搜证（地点：${near.clue_location || '未知'}）`).setColor('#ffe08a');
    } else {
      const dCount = (this.mapJson.decor || []).length;
      this.hint.setText(`金色区域=搜证点：点击 或 靠近后按 E（已搜证 ${searchedCount}/${this.mapJson.zones.length}）${dCount > 0 ? ` ｜ 黄色边框=可交互物` : ''}`).setColor('#ffe08a');
    }

    if (this.eKey && !this.readOnly && Phaser.Input.Keyboard.JustDown(this.eKey)) {
      // P-0817-Q：E 键优先传送；P-0817-G：其次走门切换（房间模式）；P-0814-H：再 decor，后搜证热点
      if (this.nearWarp) this.triggerWarp(this.nearWarp);
      else if (this.roomMode && nearExit) this.switchToRoom(nearExit.to, nearExit.door);
      else if (nearD) this.interactDecor(nearD);
      else if (this.nearZone) this.interact(this.nearZone);
    }

    // P-0803-E（调研项 2）：玩家格坐标变化 → 回调 onPlayerMove（小地图消费；节流=仅格变化时触发）
    if (this.callbacks.onPlayerMove) {
      if (gx !== this.lastReportedGx || gy !== this.lastReportedGy) {
        this.lastReportedGx = gx;
        this.lastReportedGy = gy;
        this.callbacks.onPlayerMove(gx, gy);
      }
    }
  }
}
