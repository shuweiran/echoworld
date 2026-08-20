/**
 * SimulationScene.ts — Phaser 3.90 渲染层（阶段 1）
 *
 * 职责：把后端 /api/simulation/* 的状态渲染为 2D 世界（角色/障碍/听觉带/连接线/
 * 点击目标/旁白/对话气泡/群组框）。视觉与 static/simulation.html 自研 Canvas 一致（无回归），
 * 数据经 applySnapshot() 增量喂入——渲染层与数据流解耦（D-020 结构性前提）。
 *
 * 生命周期：React 组件持有 Phaser.Game；本 Scene 只负责绘制。destroy/重建由组件层
 * 按阶段 0 实证模式处理（game.destroy(true) + HMR dispose）。
 */
import Phaser from 'phaser';
import {
  WORLD_W, WORLD_H, agentColor, obstacleColor, groupModeColor,
  normalizeSnapshot, normalizeAgent, findApproachable, findApproachableGroups, APPROACH_DIST,
  type SimAgent, type SimSnapshot, type SimGroup, type SimObstacle,
} from './simulationData';
import { normalizeMap, tileColor, type ScriptMap, type MapZone } from './mapData';
// P-0816-B：地图内容渲染（zones/rooms/spawn/decor）复用 preview 的装饰计划纯函数单源
import { buildDecorPlan, DEPTH_WATER, C_WATER_BLUE, type DecorCmd } from './decorData';

export interface SceneCallbacks {
  /** 点击画布 → 世界坐标 → 交给组件层 POST /api/simulation/target/{agentName} */
  onSetTarget: (agentName: string, x: number, y: number) => void;
  /**
   * P-0814-I：WASD/方向键持续移动 → 组件层 POST /api/simulation/move-dir/{agentName}
   * （服务端权威坐标 + 方向×步长设目标 + 刷新 manualTarget 时间戳；按住时高频 ~120ms 一次）。
   */
  onMoveDir?: (agentName: string, dx: number, dy: number) => void;
  /** 群组框操作：玩家可加入/离开；导演模式可旁听，不改变后端成员或轨道。 */
  onGroupAction: (groupId: string, action: 'join' | 'leave' | 'observe') => void;
  /**
   * P-0813-D：点击命中 agent（NPC 或玩家角色自身）→ 上抛组件层触发对话开始。
   * 命中判定：点击点与某 agent 距离 ≤ 32px（agent 视觉半径 12 + 容差）。
   */
  onAgentClick?: (agentName: string) => void;
  /**
   * P-0813-G：可交互（接近）NPC 名单变化 → 上抛组件层（DOM 层叠提示用）。
   * 仅在名单集合变化时回调（防每帧刷状态）；数组为当前接近玩家的 NPC 名。
   */
  onApproachChange?: (nearby: string[]) => void;
  /**
   * P-0813-K：可加入（接近）对话群名单变化 → 上抛组件层（DOM 层叠提示用，
   * 与 NPC 提示并存时群提示优先）。仅在名单集合变化时回调；数组为当前可加入的群 id。
   */
  onGroupApproachChange?: (groupIds: string[]) => void;
}

/**
 * P-0804-C：角色像素动画素材（素材库 CHARACTER_ANIMATION 类型，Aseprite PNG + JSON）。
 * key 为角色名（素材登记的 character_name）——后端 2D 世界 agentName 与角色名一致时命中。
 */
export interface AgentAnim {
  /** 精灵图 PNG URL（素材 file_path 解析，/assets/...） */
  pngUrl: string;
  /** Aseprite JSON URL（meta_json 的 Blob URL 或同名 .json 约定） */
  jsonUrl: string;
}

/**
 * 自研 Canvas 的虚线在 Phaser Graphics 上无原生 API，这里手写分段绘制：
 * dashCircle / dashRect / dashLine —— 视觉对齐 simulation.html 的 setLineDash。
 */
function dashRect(g: Phaser.GameObjects.Graphics, x: number, y: number, w: number, h: number, dash: number, gap: number) {
  const edges: Array<[number, number, number, number]> = [
    [x, y, x + w, y],
    [x + w, y, x + w, y + h],
    [x + w, y + h, x, y + h],
    [x, y + h, x, y],
  ];
  for (const [x1, y1, x2, y2] of edges) {
    const len = Math.hypot(x2 - x1, y2 - y1);
    if (len <= 0) continue;
    const nx = (x2 - x1) / len;
    const ny = (y2 - y1) / len;
    let d = 0;
    let drawing = true;
    while (d < len) {
      const seg = drawing ? dash : gap;
      const end = Math.min(d + seg, len);
      if (drawing) {
        g.beginPath();
        g.moveTo(x1 + nx * d, y1 + ny * d);
        g.lineTo(x1 + nx * end, y1 + ny * end);
        g.strokePath();
      }
      d = end;
      drawing = !drawing;
    }
  }
}

function dashLine(g: Phaser.GameObjects.Graphics, x1: number, y1: number, x2: number, y2: number, dash: number, gap: number) {
  const len = Math.hypot(x2 - x1, y2 - y1);
  if (len <= 0) return;
  const nx = (x2 - x1) / len;
  const ny = (y2 - y1) / len;
  let d = 0;
  let drawing = true;
  while (d < len) {
    const seg = drawing ? dash : gap;
    const end = Math.min(d + seg, len);
    if (drawing) {
      g.beginPath();
      g.moveTo(x1 + nx * d, y1 + ny * d);
      g.lineTo(x1 + nx * end, y1 + ny * end);
      g.strokePath();
    }
    d = end;
    drawing = !drawing;
  }
}

/**
 * P-0816-B：像素空间装饰绘制（SimulationScene 专用）——preview（ScriptMapScene）用均匀 ts，
 * 而游戏内世界 1000×600 / 地图 W×H 的瓦片可能非正方形（如 24×16 → 41.67×37.5），
 * 这里按 tileW/tileH 独立缩放（圆/点半径取 min 保持圆形），命令语义与 drawDecorCmds 逐字一致。
 */
function drawDecorCmdsPx(g: Phaser.GameObjects.Graphics, cmds: DecorCmd[], px: number, py: number, tileW: number, tileH: number) {
  const s = Math.min(tileW, tileH);
  for (const c of cmds) {
    switch (c.shape) {
      case 'rect':
        g.fillStyle(c.color, c.alpha);
        g.fillRect(px + c.x * tileW, py + c.y * tileH, Math.max(0.5, c.w * tileW), Math.max(0.5, c.h * tileH));
        break;
      case 'circle':
        g.fillStyle(c.color, c.alpha);
        g.fillCircle(px + c.x * tileW, py + c.y * tileH, Math.max(0.5, c.r * s));
        break;
      case 'triangle':
        g.fillStyle(c.color, c.alpha);
        g.fillTriangle(
          px + (c.x - c.w / 2) * tileW, py + (c.y + c.h) * tileH,
          px + (c.x + c.w / 2) * tileW, py + (c.y + c.h) * tileH,
          px + c.x * tileW, py + c.y * tileH,
        );
        break;
      case 'dots':
        for (let i = 0; i < c.pts.length; i++) {
          g.fillStyle(c.colors[i % c.colors.length], c.alpha);
          g.fillCircle(px + c.pts[i][0] * tileW, py + c.pts[i][1] * tileH, Math.max(0.5, s * 0.09));
        }
        break;
    }
  }
}

/** P-0814-I：方向键按住时的移动指令发送间隔（任务要求 100-200ms，取 120ms） */
const MOVE_DIR_INTERVAL_MS = 120;

export class SimulationScene extends Phaser.Scene {
  private agents = new Map<string, Phaser.GameObjects.Container>();
  private agentParts = new Map<string, {
    dot?: Phaser.GameObjects.Graphics;
    /** P-0804-C：素材库角色动画精灵（有素材时代替圆点 dot） */
    sprite?: Phaser.GameObjects.Sprite;
    emoji: Phaser.GameObjects.Text;
    name: Phaser.GameObjects.Text;
    bubble?: Phaser.GameObjects.Text;
    bubbleBg?: Phaser.GameObjects.Rectangle;
    speedLine?: Phaser.GameObjects.Graphics;
    /** P-0815-F：文本渲染缓存（值未变化时跳过 setText —— Phaser Text.setText 每次重建纹理，5Hz 快照下是纯浪费） */
    nameLast?: string;
    nameLastColor?: string;
    emojiLast?: string;
    bubbleLast?: string;
  }>();
  private lastAgentData = new Map<string, SimAgent>();
  private obstacleLabels: Phaser.GameObjects.Text[] = [];
  private groups: SimGroup[] = [];
  /** P-0803-G：群组加入/离开悬浮按钮（applyGroups 每轮重建，防泄漏；pointerdown 命中检查用） */
  private groupButtons: Phaser.GameObjects.Container[] = [];
  private clickTarget: { x: number; y: number } | null = null;
  private running = false;
  private lastObstacleCount = 0;
  private ready = false;
  private pendingSnapshots: SimSnapshot[] = [];
  // ── P-0816-C：链接层（听觉带虚线圆+角色连线）矢量线渲染已整体移除（主人 2026-08-16 反馈「矢量线还在」）──
  // 删除点：linkSigOf/lastLinkSig/linkDirty/lastLinkDrawAt/LINK_REDRAW_MIN_MS/redrawLinks/update 消费块。
  /** 障碍层签名缓存（场景切换/自定义地图注入才变化；避免每快照全量重绘+重建 label） */
  private lastObstacleSig = '';

  // ── C-2：气泡单例 + 避让 ──
  /** 非空时世界内只显示该 agent 的气泡（单轨：用户在场 → 只播一人）；null = 显示全部（多轨） */
  private bubbleFilter: string | null = null;
  /** agent → 气泡避让层数（重叠时向上抬，硬约束不重叠） */
  private bubbleLanes = new Map<string, number>();

  private callbacks: SceneCallbacks;

  // ── P-0804-C：素材库角色动画（Aseprite）──
  /** 角色名 → 动画素材（组件层从素材库 CHARACTER_ANIMATION 登记拉取；空 = 无素材回退圆点渲染零破坏） */
  private agentAnims: Record<string, AgentAnim>;
  /** 角色名 → 已就绪动画（preload 加载 + create 建动画后填充） */
  private agentAnimReady = new Map<string, { key: string; animName: string | undefined }>();
  /** P-0811-G：可选 LLM 瓦片地图（渲染瓦片背景；null = 旧抽象背景+障碍矩形） */
  private tileMap: ScriptMap | null = null;
  /** P-0811-G：玩家角色名（点击地图 → 控制玩家移动；空 = 点击控制第一个 agent） */
  private playerName = '';
  /** P-0813-G：接近提示开关（仅 Gal 模式开启；false = 旧行为零变化） */
  private approachEnabled = false;
  /** P-0813-G：接近提示渲染对象（agent 名 → 提示容器；仅需显示时创建，远离后销毁） */
  private approachHints = new Map<string, Phaser.GameObjects.Container>();
  /** P-0813-G：上次上抛的可交互名单（集合变化才回调，防每帧刷状态） */
  private lastApproachNames: string[] = [];
  /** P-0813-K：接近提示渲染对象（群 id → 提示容器；仅需显示时创建，远离后销毁） */
  private groupApproachHints = new Map<string, Phaser.GameObjects.Container>();
  /** P-0813-K：上次上抛的可加入群名单（集合变化才回调，防每帧刷状态） */
  private lastApproachGroupIds: string[] = [];
  /** P-0814-I：WASD/方向键持续移动 —— 键状态（create() 注册；无键盘插件/headless = null，零变化） */
  private moveKeys: Record<string, Phaser.Input.Keyboard.Key> | null = null;
  /** P-0814-I：当前是否有方向键按住（按住时暂停点击目标处理，松开恢复；后到优先=键盘覆盖点击） */
  private moveActive = false;
  /** P-0814-I：上次发送 move-dir 的时间戳（节流 MOVE_DIR_INTERVAL_MS） */
  private lastMoveDirSent = 0;
  /** P-0814-I：导演模式提示（无玩家角色时点击/方向键控制被禁止的 UI 提示） */
  private directorHint!: Phaser.GameObjects.Text;
  /** P-0816-A：滚轮缩放基准（对齐 ScriptMapScene：baseZoom ~ 2×baseZoom；zoom=1 全景，>1 跟随玩家） */
  private baseZoom = 1;
  private zoomFollow = false;

  // ── P-0816-B：地图内容渲染层（tileMap 存在时渲染 zones/rooms/spawn/decor；只读视觉零交互）──
  /** 热点金色区域（脉冲每帧重绘；depth 7，不设 interactive 不拦截点击） */
  private mapZoneG!: Phaser.GameObjects.Graphics;
  /** 出生点标记（静态；depth 6） */
  private mapSpawnG!: Phaser.GameObjects.Graphics;
  /** water=true 格蓝色半透明叠加（depth 0.5，地面之上装饰之下） */
  private mapDecorG!: Phaser.GameObjects.Graphics;
  /** 前景遮罩（depth 5.5：盖装饰/墙标签，不盖角色与标记） */
  private mapOverlayG!: Phaser.GameObjects.Graphics;
  /** decor/objects/spawnMarkers 行 Graphics 缓存（key=行 y；depth = 1+(y+0.5)/H 北→南遮挡） */
  private mapDecorRows = new Map<number, Phaser.GameObjects.Graphics>();
  private mapRoomLabels: Phaser.GameObjects.Text[] = [];
  private mapZoneLabels: Phaser.GameObjects.Text[] = [];
  private mapSpawnLabels: Phaser.GameObjects.Text[] = [];
  /** 热点像素矩形缓存（脉冲重绘 + 点击命中共用） */
  private mapZones: { cx: number; cy: number; rx: number; ry: number; z: MapZone }[] = [];
  /** 地图内容点击提示（如「此区域无搜证」；底部居中，3s 淡出） */
  private mapHint!: Phaser.GameObjects.Text;

  // 渲染对象（create() 中初始化——Phaser Scene 构造时 this.add 尚不可用）
  private gridG!: Phaser.GameObjects.Graphics;
  private obstacleG!: Phaser.GameObjects.Graphics;
  private markerG!: Phaser.GameObjects.Graphics;
  private groupG!: Phaser.GameObjects.Graphics;
  private narrationText!: Phaser.GameObjects.Text;
  private statusText!: Phaser.GameObjects.Text;

  constructor(callbacks: SceneCallbacks, agentAnims?: Record<string, AgentAnim>, tileMap?: ScriptMap | null, playerName?: string, approachEnabled?: boolean) {
    super({ key: 'SimulationScene' });
    this.callbacks = callbacks;
    this.agentAnims = agentAnims || {};
    if (tileMap) this.tileMap = normalizeMap(tileMap) ?? tileMap;
    this.playerName = playerName || '';
    this.approachEnabled = !!approachEnabled;
  }

  /**
   * P-0804-C：preload 加载角色动画素材（load.aseprite：PNG + Aseprite JSON，Phaser 3.90 原生支持）。
   * 无素材（空表）时本方法零加载 → 行为与既有一致。
   */
  preload() {
    for (const name of Object.keys(this.agentAnims)) {
      const anim = this.agentAnims[name];
      if (!anim || !anim.pngUrl || !anim.jsonUrl) continue;
      const key = 'charanim-' + name;
      if (this.textures.exists(key)) continue;
      this.load.aseprite(key, anim.pngUrl, anim.jsonUrl);
    }
  }

  create() {
    this.gridG = this.add.graphics();
    this.obstacleG = this.add.graphics();
    this.markerG = this.add.graphics();
    this.groupG = this.add.graphics();
    // P-0816-B：地图内容渲染层（tileMap 无内容时为空 Graphics，零开销零回归）
    this.mapZoneG = this.add.graphics().setDepth(7);
    this.mapSpawnG = this.add.graphics().setDepth(6);
    this.mapDecorG = this.add.graphics().setDepth(DEPTH_WATER);
    this.mapOverlayG = this.add.graphics().setDepth(5.5);
    this.narrationText = this.add.text(0, 0, '', { fontFamily: 'sans-serif', fontSize: '13px', color: '#fbbf24' }).setDepth(100).setOrigin(0.5);
    this.statusText = this.add.text(8, WORLD_H - 20, '', { fontFamily: 'sans-serif', fontSize: '11px', color: '#64748b' }).setDepth(100);
    // P-0814-I：导演模式提示（无玩家角色时点击/方向键控制被禁止；默认隐藏）
    this.directorHint = this.add.text(WORLD_W / 2, WORLD_H - 44, '', { fontFamily: 'sans-serif', fontSize: '12px', color: '#f59e0b' }).setOrigin(0.5).setDepth(101);
    // P-0816-B：地图内容点击提示（默认隐藏；点击热点区域时显示「无搜证」说明，3s 淡出）
    this.mapHint = this.add.text(WORLD_W / 2, WORLD_H - 22, '', {
      fontFamily: 'sans-serif', fontSize: '12px', color: '#ffe08a', backgroundColor: '#000000bb',
      padding: { x: 10, y: 4 },
    }).setOrigin(0.5).setDepth(101).setAlpha(0);
    // P-0814-I：WASD/方向键持续移动控制（无键盘插件/headless 时跳过，行为零变化）
    const kb = this.input.keyboard;
    if (kb) {
      this.moveKeys = kb.addKeys('W,A,S,D,UP,LEFT,DOWN,RIGHT') as Record<string, Phaser.Input.Keyboard.Key>;
    }
    this.drawGrid();

    // P-0816-A：滚轮缩放（对齐 ScriptMapScene：baseZoom ~ 2×baseZoom，中心缩放）——
    // zoom=1 全景（现状零变化）；>1 时相机跟随受控角色（保证玩家可见）；点击坐标转世界坐标。
    const cam = this.cameras.main;
    cam.setBounds(0, 0, WORLD_W, WORLD_H);
    cam.setZoom(this.baseZoom);
    this.input.on('wheel', (_p: Phaser.Input.Pointer, _go: unknown, _dx: number, dy: number) => {
      const z = Phaser.Math.Clamp(cam.zoom + (dy > 0 ? -0.12 : 0.12), this.baseZoom, this.baseZoom * 2);
      cam.setZoom(z);
      if (z > this.baseZoom + 0.01 && !this.zoomFollow) {
        // 缩放进入 → 跟随受控角色（解析同点击逻辑）；无受控角色则居中全景
        const names = Array.from(this.lastAgentData.keys());
        let me: string | undefined = this.playerName && names.includes(this.playerName) ? this.playerName : undefined;
        if (!me) me = names.find(n => n === 'me' || n === '我' || n === '主人');
        if (me) { cam.startFollow(this.agents.get(me)!, true, 0.15, 0.15); this.zoomFollow = true; }
      } else if (z <= this.baseZoom + 0.01 && this.zoomFollow) {
        cam.stopFollow();
        cam.centerOn(WORLD_W / 2, WORLD_H / 2);
        this.zoomFollow = false;
      }
    });

    // ready 标志 + 缓存重放：React 组件可能在 create() 完成前就 push 快照（挂载时序）
    this.ready = true;
    // P-0804-C：已加载的角色动画 → 由 Aseprite frameTags 建动画（createFromAseprite，Phaser 3.50+）
    for (const name of Object.keys(this.agentAnims)) {
      const key = 'charanim-' + name;
      if (!this.textures.exists(key) || this.agentAnimReady.has(name)) continue;
      let animName: string | undefined;
      try {
        const created = this.anims.createFromAseprite(key);
        if (created && created.length > 0) animName = created[0].key;
      } catch { /* 动画创建失败 → 静态帧回退（sprite 不 play） */ }
      this.agentAnimReady.set(name, { key, animName });
    }
    const pending = this.pendingSnapshots;
    this.pendingSnapshots = [];
    for (const snap of pending) this.applySnapshot(snap);

    this.input.on('pointerdown', (p: Phaser.Input.Pointer) => {
      // P-0814-I：方向键按住时暂停点击目标处理（松开恢复）
      if (this.moveActive) return;
      // P-0803-G：点击群组「加入/离开对话」按钮时，不触发移动目标（命中按钮则跳过）
      // P-0813-K：点击群接近提示「👥 加入对话」胶囊同理跳过（命中检查合并群提示容器）
      const clickables = this.groupButtons.concat(Array.from(this.groupApproachHints.values()));
      if (clickables.length > 0) {
        const hits = this.input.hitTestPointer(p);
        if (hits.some(go => clickables.includes(go as Phaser.GameObjects.Container))) return;
      }
      const pt = cam.getWorldPoint(p.x, p.y);
      const x = Math.round(Math.max(10, Math.min(WORLD_W - 10, pt.x)) * 100) / 100;
      const y = Math.round(Math.max(10, Math.min(WORLD_H - 10, pt.y)) * 100) / 100;
      // P-0816-B：点击热点区域 → 提示「此区域无搜证」（游戏内只读语义；不拦截后续点击目标逻辑）
      const zHit = this.hitTestMapZone(x, y);
      if (zHit) this.showMapHint(`🔍 ${zHit.name}：此区域无搜证（游戏内无搜证玩法，仅供地图标记）`);
      const names = Array.from(this.agents.keys());
      if (names.length === 0) return;
      // P-0813-D：点击命中 agent（NPC 或玩家角色自身）→ 触发对话开始（上抛组件层）；
      // 无论命中与否都继续设置移动目标（玩家走近 NPC）
      const hit = this.hitTestAgent(x, y, 32);
      if (hit) {
        try { this.callbacks.onAgentClick?.(hit); } catch { /* 忽略 */ }
      }
      // P-0811-G：优先选玩家角色名（用户控制的化身）；否则回退 'me'/'我'/'主人'
      // P-0814-I：无玩家角色（导演模式）→ 禁止点击兜底控制第一个 AI，仅提示「导演模式不可控制」
      let me: string | undefined = this.playerName && names.includes(this.playerName) ? this.playerName : undefined;
      if (!me) me = names.find(n => n === 'me' || n === '我' || n === '主人');
      if (!me) {
        this.showDirectorHint();
        return;
      }
      this.hideDirectorHint();
      this.clickTarget = { x, y };
      this.drawMarker();
      // P-0813-G：4s 超时仅作兜底（到达自动清除见 update()——受控 agent hasTarget=false 即清）
      setTimeout(() => { if (this.clickTarget && this.clickTarget.x === x && this.clickTarget.y === y) { this.clickTarget = null; this.drawMarker(); } }, 4000);
      this.callbacks.onSetTarget(me, x, y);
    });
  }

  /** P-0814-I：导演模式提示（无玩家角色时点击/方向键控制被禁止）——底部居中橙色提示，3.5s 后自动消失 */
  private showDirectorHint() {
    this.directorHint.setText('🎬 导演模式不可控制：未选择玩家角色，点击/方向键仅可观察与发起对话').setAlpha(1);
    this.time.delayedCall(3500, () => {
      if (this.directorHint && this.directorHint.active) {
        this.directorHint.setAlpha(0);
        this.directorHint.setText('');
      }
    });
  }

  /** P-0814-I：隐藏导演模式提示（成功解析受控角色时） */
  private hideDirectorHint() {
    if (this.directorHint && this.directorHint.active) {
      this.directorHint.setAlpha(0);
      this.directorHint.setText('');
    }
  }

  /** P-0814-I：发送一次方向移动指令（受控角色解析同点击：playerName → 'me'/'我'/'主人'；无则导演模式提示） */
  private sendMoveDir(dx: number, dy: number) {
    const names = Array.from(this.lastAgentData.keys());
    if (names.length === 0) return;
    let me: string | undefined = this.playerName && names.includes(this.playerName) ? this.playerName : undefined;
    if (!me) me = names.find(n => n === 'me' || n === '我' || n === '主人');
    if (!me) {
      this.showDirectorHint();
      return;
    }
    this.hideDirectorHint();
    try { this.callbacks.onMoveDir?.(me, dx, dy); } catch { /* 忽略 */ }
  }

  /** P-0814-I：聊天输入框聚焦时忽略 WASD（防止边打字边移动角色） */
  private isTypingInInput(): boolean {
    const el = document.activeElement;
    if (!el) return false;
    const tag = (el as HTMLElement).tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA';
  }

  private drawGrid() {
    const g = this.gridG;
    g.clear();
    // P-0811-G：有 LLM 瓦片地图 → 画瓦片背景（ground 色块 + collision 墙色）；无 → 旧深色背景+网格
    if (this.tileMap) {
      this.drawTileMap();
      // P-0816-B：地图内容渲染对齐 preview（zones 热点/rooms 房间名/spawn 出生点/decor 装饰）
      this.drawMapContent();
      return;
    }
    g.fillStyle(0x0f172a, 1);
    g.fillRect(0, 0, WORLD_W, WORLD_H);
    g.lineStyle(0.5, 0x1e293b, 1);
    for (let x = 100; x < WORLD_W; x += 100) {
      g.beginPath(); g.moveTo(x, 0); g.lineTo(x, WORLD_H); g.strokePath();
    }
    for (let y = 100; y < WORLD_H; y += 100) {
      g.beginPath(); g.moveTo(0, y); g.lineTo(WORLD_W, y); g.strokePath();
    }
    g.lineStyle(2, 0x334155, 1);
    g.strokeRect(0, 0, WORLD_W, WORLD_H);
  }

  /**
   * P-0811-G：渲染 LLM 瓦片地图为动态模拟背景——ground 层按瓦片 id 画色块（tileColor），
   * collision 非0 画深色墙格。世界 1000×600 铺满整个地图（x/y 独立等分，无留白），
   * 与后端 Obstacle.fromCollisionGrid 同缩放——地图边界=世界边界，角色不挤出地图外。
   */
  private drawTileMap() {
    const m = this.tileMap!;
    const g = this.gridG;
    const ground = m.layers?.ground as number[][] | undefined;
    const collision = m.layers?.collision as number[][] | undefined;
    const W = Math.max(1, m.width || (ground?.[0]?.length ?? 0));
    const H = Math.max(1, m.height || ground?.length || 0);
    const tileW = WORLD_W / W;
    const tileH = WORLD_H / H;
    for (let y = 0; y < H; y++) {
      for (let x = 0; x < W; x++) {
        const gid = ground?.[y]?.[x] ?? 1;
        const col = collision?.[y]?.[x] ?? 0;
        const fill = col !== 0 ? 0x223044 : tileColor(gid);
        g.fillStyle(fill, 1);
        g.fillRect(x * tileW, y * tileH, tileW + 0.5, tileH + 0.5);
      }
    }
  }

  /**
   * P-0816-B：地图内容渲染（对齐 preview ScriptMapScene 视觉）——tileMap 存在时在瓦片之上补全：
   *   - decor/objects/spawnMarkers 装饰（buildDecorPlan 纯函数单源 + 像素空间绘制，非正方瓦片独立缩放）
   *   - water=true 蓝色叠加、overlay 前景遮罩
   *   - rooms[] 房间名标注（房间顶部中央，13px 深色底）
   *   - spawn_points[] 出生点标记（玩家蓝 / NPC 灰 + AI 小标）
   *   - zones[] 热点名称标签（金色区域脉冲在 update() 每帧重绘）
   * 全部为只读 Graphics/Text（不 setInteractive），不拦截 pointer 事件；地图经构造器单次注入，静态渲染一次。
   */
  private drawMapContent() {
    const m = this.tileMap!;
    const ground = m.layers?.ground as number[][] | undefined;
    const W = Math.max(1, m.width || (ground?.[0]?.length ?? 0));
    const H = Math.max(1, m.height || ground?.length || 0);
    const tileW = WORLD_W / W;
    const tileH = WORLD_H / H;
    const s = Math.min(tileW, tileH);

    // 清理旧渲染（地图重新注入时防泄漏；正常单次注入零成本）
    for (const t of this.mapRoomLabels) if (t.active) t.destroy();
    this.mapRoomLabels = [];
    for (const t of this.mapZoneLabels) if (t.active) t.destroy();
    this.mapZoneLabels = [];
    for (const t of this.mapSpawnLabels) if (t.active) t.destroy();
    this.mapSpawnLabels = [];
    for (const g of this.mapDecorRows.values()) if (g.active) g.destroy();
    this.mapDecorRows.clear();
    this.mapDecorG.clear();
    this.mapOverlayG.clear();
    this.mapSpawnG.clear();
    this.mapZones = [];

    // ── 装饰计划（复用 preview 纯函数单源：objects 层 / decor 列表 / spawnMarkers / water / overlay）──
    const plan = buildDecorPlan(m);
    for (const w of plan.water) {
      this.mapDecorG.fillStyle(C_WATER_BLUE, 0.35);
      this.mapDecorG.fillRect(w.x * tileW, w.y * tileH, tileW + 0.5, tileH + 0.5);
    }
    for (const item of plan.items) {
      let g = this.mapDecorRows.get(item.y);
      if (!g) {
        g = this.add.graphics().setDepth(item.depth);
        this.mapDecorRows.set(item.y, g);
      }
      drawDecorCmdsPx(g, item.cmds, item.x * tileW, item.y * tileH, tileW, tileH);
    }
    for (const ov of plan.overlay) {
      this.mapOverlayG.fillStyle(ov.style.fill, ov.style.alpha);
      this.mapOverlayG.fillRect(ov.x * tileW, ov.y * tileH, tileW + 0.5, tileH + 0.5);
    }

    // ── rooms[] 房间名标注（对齐 preview：房间顶部中央，origin(0.5,1)，depth 4 在角色层之下）──
    for (const r of m.rooms) {
      if (!r.name) continue;
      this.mapRoomLabels.push(this.add.text((r.x + r.w / 2) * tileW, r.y * tileH - 4, r.name, {
        fontFamily: 'sans-serif', fontSize: '13px', color: '#e2e8f0', backgroundColor: '#00000066',
        padding: { x: 4, y: 1 },
      }).setOrigin(0.5, 1).setDepth(4));
    }

    // ── spawn_points[] 出生点标记（玩家蓝 0x38bdf8 / NPC 灰 0x94a3b8 + AI 小标，对齐 preview）──
    for (const sp of m.spawn_points) {
      const cx = (sp.x + 0.5) * tileW, cy = (sp.y + 0.5) * tileH;
      const color = sp.type === 'player' ? 0x38bdf8 : 0x94a3b8;
      this.mapSpawnG.fillStyle(color, 0.55).fillCircle(cx, cy, s * 0.22);
      this.mapSpawnG.lineStyle(1, 0xffffff, 0.8).strokeCircle(cx, cy, s * 0.22);
      if (sp.type === 'npc') {
        this.mapSpawnLabels.push(this.add.text(cx, cy - s * 0.35, 'AI', {
          fontFamily: 'sans-serif', fontSize: '9px', color: '#cbd5e1',
        }).setOrigin(0.5).setDepth(8));
      }
    }

    // ── zones[] 热点（金色区域脉冲由 update() 的 updateMapZones 每帧重绘；名称标签静态）──
    for (const z of m.zones) {
      const cx = (z.x + 0.5) * tileW, cy = (z.y + 0.5) * tileH;
      const rx = Math.max((z.radius || 1) * tileW, tileW * 0.6);
      const ry = Math.max((z.radius || 1) * tileH, tileH * 0.6);
      this.mapZones.push({ cx, cy, rx, ry, z });
      const label = this.add.text(cx, cy + s * 0.8, z.name, {
        fontFamily: 'sans-serif', fontSize: '11px', color: '#ffe08a', backgroundColor: '#00000088',
        padding: { x: 3, y: 1 },
      }).setOrigin(0.5).setDepth(8);
      this.mapZoneLabels.push(label);
    }
  }

  /** P-0816-B：点击点 (x,y) 是否命中某热点区域（与 updateMapZones 同矩形；只读判定，不拦截事件） */
  private hitTestMapZone(x: number, y: number): MapZone | null {
    for (const { cx, cy, rx, ry, z } of this.mapZones) {
      if (Math.abs(x - cx) <= rx && Math.abs(y - cy) <= ry) return z;
    }
    return null;
  }

  /** P-0816-B：地图内容点击提示（底部居中，3s 后淡出）——热点区域点击说明「无搜证」（游戏内只读语义） */
  private showMapHint(text: string) {
    this.mapHint.setText(text).setAlpha(1);
    this.time.delayedCall(3000, () => {
      if (this.mapHint && this.mapHint.active) this.mapHint.setAlpha(0);
    });
  }

  /** P-0816-B：热点金色区域脉冲（对齐 preview 的 pulse = 0.35 + 0.25*sin(time/250)；每帧重绘，区域数少成本低） */
  private updateMapZones(time: number) {
    const g = this.mapZoneG;
    g.clear();
    if (this.mapZones.length === 0) return;
    const pulse = 0.35 + 0.25 * Math.sin(time / 250);
    for (const { cx, cy, rx, ry } of this.mapZones) {
      const rad = Math.min(rx, ry) * 0.2;
      g.fillStyle(0xffd166, pulse);
      g.fillRoundedRect(cx - rx, cy - ry, rx * 2, ry * 2, rad);
      g.lineStyle(2, 0xffd166, 0.9);
      g.strokeRoundedRect(cx - rx, cy - ry, rx * 2, ry * 2, rad);
    }
  }
  /**
   * P-0813-D：命中检测——点击点 (x,y) 附近半径 radius 内是否有 agent。
   * 返回最近 agent 名（命中）或 null（未命中）。
   */
  private hitTestAgent(x: number, y: number, radius: number): string | null {
    let best: string | null = null;
    let bestD = radius * radius;
    for (const [name, a] of this.lastAgentData) {
      const dx = a.x - x;
      const dy = a.y - y;
      const d = dx * dx + dy * dy;
      if (d < bestD) { bestD = d; best = name; }
    }
    return best;
  }

  private drawMarker() {
    const g = this.markerG;
    g.clear();
    if (!this.clickTarget) return;
    const { x, y } = this.clickTarget;
    g.lineStyle(2, 0x38bdf8, 0.4);
    dashLine(g, x - 8, y, x + 8, y, 4, 4);
    dashLine(g, x, y - 8, x, y + 8, 4, 4);
    g.lineStyle(1, 0x38bdf8, 0.27);
    g.strokeCircle(x, y, 6);
  }

  /**
   * P-0813-G：接近提示——玩家角色与某 NPC 距离 < APPROACH_DIST 时，
   * 该 NPC 头顶浮现「💬 对话」气泡（脉冲动画），点击该 NPC 进入对话；远离后消失。
   * 每帧调用（廉价格：仅对接近名单内的 agent 维护容器）。
   */
  private updateApproachHints(time: number) {
    if (!this.approachEnabled || !this.playerName) {
      // 未开启 / 无玩家 → 清空所有提示
      if (this.approachHints.size > 0) {
        for (const h of this.approachHints.values()) if (h.active) h.destroy();
        this.approachHints.clear();
      }
      if (this.lastApproachNames.length > 0) {
        this.lastApproachNames = [];
        try { this.callbacks.onApproachChange?.([]); } catch { /* 忽略 */ }
      }
      return;
    }
    const agents = Array.from(this.lastAgentData.values());
    const nearby = findApproachable(this.playerName, agents, APPROACH_DIST);
    const wanted = new Set(nearby);
    // 新增/更新提示
    for (const name of nearby) {
      const a = this.lastAgentData.get(name);
      if (!a) continue;
      const baseY = a.y - 12 - 52; // 角色头顶上方（气泡避让层之外）
      let h = this.approachHints.get(name);
      if (!h) {
        h = this.createApproachHint(agentColor(name));
        this.approachHints.set(name, h);
      }
      h.setPosition(a.x, baseY);
      // 脉冲：呼吸式透明度 + 轻微上浮
      const pulse = 0.62 + 0.38 * (0.5 + 0.5 * Math.sin(time * 0.005));
      h.setAlpha(pulse);
    }
    // 移除不再接近的提示
    for (const [n, h] of Array.from(this.approachHints.entries())) {
      if (wanted.has(n)) continue;
      if (h.active) h.destroy();
      this.approachHints.delete(n);
    }
    // 名单集合变化 → 上抛（DOM 层叠提示）
    const sig = (arr: string[]) => arr.slice().sort().join('|');
    if (sig(nearby) !== sig(this.lastApproachNames)) {
      this.lastApproachNames = nearby;
      try { this.callbacks.onApproachChange?.(nearby); } catch { /* 忽略 */ }
    }
  }

  /** P-0813-G：创建单个「💬 对话」提示容器（圆角胶囊 + 小三角箭头 + 文字） */
  private createApproachHint(color: string): Phaser.GameObjects.Container {
    const W = 76;
    const H = 24;
    const g = this.add.graphics();
    const col = Phaser.Display.Color.HexStringToColor(color).color;
    // 胶囊底（深色半透明 + 角色色描边）
    g.fillStyle(0x0f172a, 0.94);
    g.fillRoundedRect(-W / 2, -H / 2, W, H, 12);
    g.lineStyle(1.5, col, 0.95);
    g.strokeRoundedRect(-W / 2, -H / 2, W, H, 12);
    // 小三角箭头（指向下方角色）
    g.fillStyle(col, 0.95);
    g.fillTriangle(-5, H / 2 - 1, 5, H / 2 - 1, 0, H / 2 + 6);
    const label = this.add.text(0, 0, '💬 对话', {
      fontFamily: 'sans-serif', fontSize: '13px', color: '#ffffff',
    }).setOrigin(0.5);
    const c = this.add.container(0, 0, [g, label]);
    c.setDepth(85); // 群组按钮(80)之上、旁白(100)之下
    return c;
  }

  /**
   * P-0813-K：群接近提示——玩家靠近「正在对话的 AI 群」（与任一成员 < 100px 或
   * 群中心 < 120px，见 findApproachableGroups）时，群中心上方浮现「👥 加入对话」
   * 胶囊（紫色、脉冲呼吸、可点击直接加入，复用 G 的接近提示样式、群聊专属图标/颜色）；
   * 远离后消失。每帧调用（廉价格：仅对接近名单内的群维护容器）。
   */
  private updateGroupApproachHints(time: number) {
    const near = (() => {
      if (!this.approachEnabled || !this.playerName) return [];
      const agents = Array.from(this.lastAgentData.values());
      return findApproachableGroups(this.playerName, agents, this.groups);
    })();
    const wanted = new Set(near);
    if (!this.approachEnabled || !this.playerName) {
      if (this.groupApproachHints.size > 0) {
        for (const h of this.groupApproachHints.values()) if (h.active) h.destroy();
        this.groupApproachHints.clear();
      }
      if (this.lastApproachGroupIds.length > 0) {
        this.lastApproachGroupIds = [];
        try { this.callbacks.onGroupApproachChange?.([]); } catch { /* 忽略 */ }
      }
      return;
    }
    // 新增/更新提示
    for (const gid of near) {
      const grp = this.groups.find(x => x.id === gid);
      if (!grp) continue;
      const center = this.groupCenterOf(grp);
      if (!center) continue;
      let h = this.groupApproachHints.get(gid);
      if (!h) {
        h = this.createGroupApproachHint();
        this.groupApproachHints.set(gid, h);
      }
      h.setData('groupId', gid);
      h.setPosition(center.x, center.y - 52);
      const pulse = 0.62 + 0.38 * (0.5 + 0.5 * Math.sin(time * 0.005));
      h.setAlpha(pulse);
    }
    // 移除不再接近的提示
    for (const [id, h] of Array.from(this.groupApproachHints.entries())) {
      if (wanted.has(id)) continue;
      if (h.active) h.destroy();
      this.groupApproachHints.delete(id);
    }
    // 名单集合变化 → 上抛（DOM 层叠提示，群优先于 NPC）
    const sig = (arr: string[]) => arr.slice().sort().join('|');
    if (sig(near) !== sig(this.lastApproachGroupIds)) {
      this.lastApproachGroupIds = near;
      try { this.callbacks.onGroupApproachChange?.(near); } catch { /* 忽略 */ }
    }
  }

  /** P-0813-K：群中心 = 在场成员位置均值（成员均不在场返回 null）。 */
  private groupCenterOf(grp: SimGroup): { x: number; y: number } | null {
    let sx = 0, sy = 0, cnt = 0;
    for (const name of grp.participants || []) {
      const a = this.lastAgentData.get(name);
      if (!a) continue;
      sx += a.x; sy += a.y; cnt++;
    }
    if (cnt === 0) return null;
    return { x: sx / cnt, y: sy / cnt };
  }

  /** P-0813-K：创建单个「👥 加入对话」群提示容器（紫色胶囊 + 小三角 + 文字，可点击加入） */
  private createGroupApproachHint(): Phaser.GameObjects.Container {
    const W = 108;
    const H = 26;
    const g = this.add.graphics();
    const col = 0xa78bfa; // 群聊紫（groupModeColor GROUP_DISCUSSION）
    g.fillStyle(0x0f172a, 0.94);
    g.fillRoundedRect(-W / 2, -H / 2, W, H, 13);
    g.lineStyle(1.5, col, 0.95);
    g.strokeRoundedRect(-W / 2, -H / 2, W, H, 13);
    g.fillStyle(col, 0.95);
    g.fillTriangle(-5, H / 2 - 1, 5, H / 2 - 1, 0, H / 2 + 6);
    const label = this.add.text(0, 0, '👥 加入对话', {
      fontFamily: 'sans-serif', fontSize: '13px', color: '#ffffff',
    }).setOrigin(0.5);
    const c = this.add.container(0, 0, [g, label]);
    c.setDepth(86); // NPC 提示(85)之上、旁白(100)之下；群提示优先
    // 可点击：命中整个胶囊 → 直接加入该群（与角落按钮同 onGroupAction 链路）
    // groupId 由 updateGroupApproachHints 每帧 setData 写入
    const hit = new Phaser.Geom.Rectangle(-W / 2, -H / 2, W, H);
    c.setInteractive(hit, Phaser.Geom.Rectangle.Contains);
    c.on(Phaser.Input.Events.GAMEOBJECT_POINTER_OVER, () => { g.clear(); g.fillStyle(0x1e293b, 0.95); g.fillRoundedRect(-W / 2, -H / 2, W, H, 13); g.lineStyle(1.5, col, 0.95); g.strokeRoundedRect(-W / 2, -H / 2, W, H, 13); g.fillStyle(col, 0.95); g.fillTriangle(-5, H / 2 - 1, 5, H / 2 - 1, 0, H / 2 + 6); });
    c.on(Phaser.Input.Events.GAMEOBJECT_POINTER_OUT, () => { g.clear(); g.fillStyle(0x0f172a, 0.94); g.fillRoundedRect(-W / 2, -H / 2, W, H, 13); g.lineStyle(1.5, col, 0.95); g.strokeRoundedRect(-W / 2, -H / 2, W, H, 13); g.fillStyle(col, 0.95); g.fillTriangle(-5, H / 2 - 1, 5, H / 2 - 1, 0, H / 2 + 6); });
    c.on(Phaser.Input.Events.GAMEOBJECT_POINTER_DOWN, () => {
      const gid = c.getData('groupId') as string;
      if (gid) this.callbacks.onGroupAction(gid, 'join');
    });
    return c;
  }

  /** 渲染统计（自测/面板展示用）：agents/obstacles/groups 数量 */
  getStats(): { agents: number; obstacles: number; groups: number } {
    return { agents: this.agents.size, obstacles: this.lastObstacleCount, groups: this.groups.length };
  }

  /** create() 是否已完成（渲染对象就绪）；组件/自测在 push 快照前可轮询 */
  isReady(): boolean {
    return this.ready;
  }

  /** 全量快照（GET /api/simulation/state / SSE world_snapshot）→ 增量更新 */
  applySnapshot(snap: SimSnapshot) {
    if (!this.ready) {
      this.pendingSnapshots.push(snap); // create() 未完成时缓存，完成后重放
      return;
    }
    const s = normalizeSnapshot(snap);
    if (s.running !== undefined) this.running = s.running;
    if (s.agents) {
      // C-2：先算气泡避让层（基于本帧位置），再逐个 upsert（renderAgent 读层号）
      this.computeBubbleLanes(s.agents);
      for (const a of s.agents) this.upsertAgent(a);
      // 后端已移除的角色 → 删除渲染对象
      const alive = new Set(s.agents.map(a => a.agentName));
      for (const name of Array.from(this.agents.keys())) {
        if (!alive.has(name)) {
          this.agents.get(name)?.destroy();
          this.agents.delete(name);
          this.agentParts.delete(name);
          this.lastAgentData.delete(name);
        }
      }
      // P-0816-C：链接层矢量线已整体移除（原 P-0815-F 此处按位置签名置 linkDirty → redrawLinks）
      // 快照数据流（lastAgentData）照常更新，仅不再渲染听觉带虚线圆/角色连线。
    }
    if (s.obstacles) {
      // P-0815-F：障碍只在场景切换/自定义地图注入时变化——签名不变则跳过全量重绘（
      // 原实现每快照 5Hz 清空重画全部障碍 + 销毁重建 label 文本）
      const oSig = s.obstacles.map(o => [o.type, o.x, o.y, o.width, o.height].join(',')).join('|');
      if (oSig !== this.lastObstacleSig) {
        this.lastObstacleSig = oSig;
        this.drawObstacles(s.obstacles.map(o => ({ ...o })));
      }
    }
    if (s.worldNarration !== undefined || s.directorActive !== undefined) {
      this.updateNarration(s.worldNarration || '', s.directorActive || false);
    }
    this.updateStatus(s.tick ?? 0);
  }

  /** C-2：世界气泡单例过滤（非空 → 只渲染该 agent 气泡；null → 全部，配避让层防重叠） */
  setBubbleFilter(name: string | null) {
    if (this.bubbleFilter === name) return;
    this.bubbleFilter = name;
    // 过滤变化 → 重算避让层并重绘全部气泡
    this.computeBubbleLanes(Array.from(this.lastAgentData.values()));
    for (const [n, c] of this.agents) {
      const parts = this.agentParts.get(n);
      const data = this.lastAgentData.get(n);
      if (c && parts && data) this.renderAgent(data, c, parts);
    }
  }

  /**
   * C-2：气泡避让层计算（硬约束：角色气泡不能重叠）。
   * 收集本帧可见气泡（filter 生效时只看单人），按 y 升序 x 升序确定性排序；
   * 逐个放入已放置矩形集合，重叠则向上抬一层（步进 22px，最多 4 层），
   * 层号存 bubbleLanes 供 renderAgent 使用（气泡锚定 agent 头部上方）。
   */
  private computeBubbleLanes(agents: SimAgent[]) {
    const R = 12;            // 角色半径（renderAgent 同值）
    const BASE = -R - 36;    // 气泡基准偏移（renderAgent 同值）
    const BW = 20;           // 气泡高（renderAgent 同值）
    const STEP = BW + 2;     // 层步进
    const MAX_LANES = 4;
    const FONT = 11;         // 气泡字号（renderAgent 同值）
    const MAX_W = 220;       // 气泡最大宽（renderAgent 同值）
    const TRUNC = 50;        // 气泡文本截断（renderAgent 同值）

    const entries: { name: string; x: number; y: number; w: number }[] = [];
    for (const a of agents) {
      if (!a || !a.agentName) continue;
      const msg = a.currentMessage && !a.currentMessage.startsWith('(主控') ? a.currentMessage : '';
      if (!msg) continue;
      if (this.bubbleFilter != null && a.agentName !== this.bubbleFilter) continue;
      const short = msg.length > TRUNC ? msg.slice(0, TRUNC) + '...' : msg;
      const tw = Math.min(short.length * FONT, MAX_W) + 16;
      entries.push({ name: a.agentName, x: a.x, y: a.y, w: tw });
    }
    entries.sort((a, b) => (a.y !== b.y ? a.y - b.y : a.x - b.x));

    const placed: { x0: number; x1: number; y0: number; y1: number }[] = [];
    const lanes = new Map<string, number>();
    for (const e of entries) {
      let lane = 0;
      let y0 = e.y + BASE;
      let y1 = y0 + BW;
      while (lane < MAX_LANES && placed.some(p => e.x - e.w / 2 < p.x1 && e.x + e.w / 2 > p.x0 && y0 < p.y1 && y1 > p.y0)) {
        lane++;
        y0 = e.y + BASE - lane * STEP;
        y1 = y0 + BW;
      }
      lanes.set(e.name, lane);
      placed.push({ x0: e.x - e.w / 2, x1: e.x + e.w / 2, y0, y1 });
    }
    this.bubbleLanes = lanes;
  }

  /** 单 agent 增量（SSE 事件里按需细粒度更新时用；当前快照路径已覆盖） */
  applyAgent(raw: unknown) {
    const a = normalizeAgent(raw);
    if (a) this.upsertAgent(a);
  }

  private upsertAgent(a: SimAgent) {
    this.lastAgentData.set(a.agentName, a);
    let c = this.agents.get(a.agentName);
    if (!c) {
      c = this.add.container(0, 0);
      c.setDepth(10);
      this.agents.set(a.agentName, c);
      const nameT = this.add.text(0, 0, a.agentName, { fontFamily: 'sans-serif', fontSize: '12px', color: agentColor(a.agentName) }).setOrigin(0.5);
      const emojiT = this.add.text(0, 0, a.emotionEmoji || '😐', { fontFamily: 'sans-serif', fontSize: '14px' }).setOrigin(0.5);
      // P-0804-C：有素材 → 动画精灵代替圆点（名字/情绪标签保留）；无素材 → 圆点（既有行为零变化）
      const anim = this.agentAnimReady.get(a.agentName);
      if (anim) {
        const sprite = this.add.sprite(0, 0, anim.key);
        // 帧尺寸自适应缩放（32×32 常见帧 ≈1.0；更大帧缩小到 30px 内）
        sprite.setScale(this.animScaleOf(anim.key));
        if (anim.animName) sprite.play(anim.animName);
        c.add([sprite, emojiT, nameT]);
        this.agentParts.set(a.agentName, { sprite, emoji: emojiT, name: nameT });
      } else {
        const dot = this.add.graphics();
        c.add([dot, emojiT, nameT]);
        this.agentParts.set(a.agentName, { dot, emoji: emojiT, name: nameT });
      }
    }
    this.renderAgent(a, c, this.agentParts.get(a.agentName)!);
  }

  /** P-0804-C：动画精灵帧宽 → 缩放（目标视觉直径 ≈30px，帧宽 32 保持原尺寸附近） */
  private animScaleOf(key: string): number {
    try {
      const tex = this.textures.get(key);
      const names = tex.getFrameNames();
      if (!names || names.length === 0) return 1;
      const f = tex.get(names[0]);
      const fw = f && f.width > 0 ? f.width : 32;
      return Math.min(1.5, 30 / fw);
    } catch {
      return 1;
    }
  }

  private renderAgent(a: SimAgent, c: Phaser.GameObjects.Container, parts: {
    dot?: Phaser.GameObjects.Graphics;
    sprite?: Phaser.GameObjects.Sprite;
    emoji: Phaser.GameObjects.Text;
    name: Phaser.GameObjects.Text;
    bubble?: Phaser.GameObjects.Text;
    bubbleBg?: Phaser.GameObjects.Rectangle;
    speedLine?: Phaser.GameObjects.Graphics;
    /** P-0815-F：文本渲染缓存（值未变化时跳过 setText —— Phaser Text.setText 每次重建纹理） */
    nameLast?: string;
    nameLastColor?: string;
    emojiLast?: string;
    bubbleLast?: string;
  }) {
    const r = 12;
    c.setPosition(a.x, a.y);
    const color = agentColor(a.agentName);
    const { dot, emoji: emojiT, name: nameT, sprite } = parts;
    // P-0804-C：素材库动画精灵 —— 位置跟随容器（容器原点即角色位置），动画循环播放；无素材走圆点渲染
    if (sprite) {
      sprite.setPosition(0, 0);
      const anim = this.agentAnimReady.get(a.agentName);
      if (anim && anim.animName && !sprite.anims.isPlaying) sprite.play(anim.animName);
    } else if (dot) {
      dot.clear();
      if (a.inConversation) {
        // glow：同心圆递减 alpha（径向渐变近似）
        for (let i = 0; i < 3; i++) {
          dot.fillStyle(Phaser.Display.Color.HexStringToColor(color).color, 0.18 - i * 0.05);
          dot.fillCircle(0, 0, r * (2.5 - i * 0.6));
        }
      }
      dot.fillStyle(Phaser.Display.Color.HexStringToColor(color).color, 1);
      dot.fillCircle(0, 0, r);
      dot.lineStyle(2, 0xffffff, 1);
      dot.strokeCircle(0, 0, r);
    }
    emojiT.setPosition(0, -r - 8);
    // P-0815-F：表情/名字 setText 缓存——值未变化跳过（Phaser Text.setText 重建纹理，5Hz 快照下重复调用是纯浪费）
    const emojiStr = a.emotionEmoji || '😐';
    if (parts.emojiLast !== emojiStr) { parts.emojiLast = emojiStr; emojiT.setText(emojiStr); }
    nameT.setPosition(0, r + 16);
    if (parts.nameLast !== a.agentName || parts.nameLastColor !== color) {
      parts.nameLast = a.agentName;
      parts.nameLastColor = color;
      nameT.setText(a.agentName).setColor(color);
    }

    // 消息气泡（替代 Canvas roundRect 气泡）
    // C-2：bubbleFilter 非空时只显示该 agent 气泡（世界内单轨只播一人）；避让层抬升防重叠
    const msg = a.currentMessage && !a.currentMessage.startsWith('(主控') ? a.currentMessage : '';
    const showBubble = msg && (this.bubbleFilter == null || a.agentName === this.bubbleFilter);
    if (showBubble) {
      const short = msg.length > 50 ? msg.slice(0, 50) + '...' : msg;
      const fontSize = 11;
      const textW = Math.min(short.length * fontSize, 220);
      const tw = textW + 16;
      const th = 20;
      const bx = -tw / 2;
      const lane = this.bubbleLanes.get(a.agentName) ?? 0;
      const by = -r - 36 - lane * 22;
      if (parts.bubbleBg) {
        parts.bubbleBg.setSize(tw, th).setPosition(bx + tw / 2, by + th / 2).setFillStyle(0x1e293b, 0.8);
      } else {
        const rect = this.add.rectangle(bx + tw / 2, by + th / 2, tw, th, 0x1e293b, 0.8);
        rect.setStrokeStyle(1, Phaser.Display.Color.HexStringToColor(color).color, 0.5);
        rect.setOrigin(0.5);
        parts.bubbleBg = rect;
        c.add(rect);
      }
      if (parts.bubble) {
        // P-0815-F：气泡文本缓存——相同文本不重建（避免每快照重建纹理）
        if (parts.bubbleLast !== short) { parts.bubbleLast = short; parts.bubble.setText(short); }
        parts.bubble.setPosition(0, by + 14);
      } else {
        const t = this.add.text(0, by + 14, short, { fontFamily: 'sans-serif', fontSize: '11px', color: '#e2e8f0' }).setOrigin(0.5);
        parts.bubble = t;
        parts.bubbleLast = short;
        c.add(t);
      }
    } else {
      if (parts.bubbleBg) { parts.bubbleBg.destroy(); parts.bubbleBg = undefined; }
      if (parts.bubble) { parts.bubble.destroy(); parts.bubble = undefined; parts.bubbleLast = undefined; }
    }

    // 速度线
    const speed = Math.sqrt((a.vx || 0) * (a.vx || 0) + (a.vy || 0) * (a.vy || 0));
    if (speed > 2) {
      if (!parts.speedLine) {
        const sg = this.add.graphics();
        parts.speedLine = sg;
        c.add(sg);
      }
      parts.speedLine.clear();
      parts.speedLine.lineStyle(1.5, Phaser.Display.Color.HexStringToColor(color).color, 0.5);
      parts.speedLine.beginPath();
      parts.speedLine.moveTo(0, 0);
      parts.speedLine.lineTo((a.vx || 0) * 2, (a.vy || 0) * 2);
      parts.speedLine.strokePath();
    } else if (parts.speedLine) {
      parts.speedLine.destroy();
      parts.speedLine = undefined;
    }
  }

  private drawObstacles(obstacles: SimObstacle[]) {
    // 先清理上一轮 label，再重画障碍
    for (const t of this.obstacleLabels) if (t.active) t.destroy();
    this.obstacleLabels = [];
    this.lastObstacleCount = obstacles.length;

    const g = this.obstacleG;
    g.clear();
    // 自定义契约地图已经在 drawTileMap() 逐格渲染了 collision。
    // 后端 obstacles 是同一 collision 的物理投影；再次画会覆盖瓦片并为每块墙重复标注地图名。
    if (this.tileMap) return;
    for (const o of obstacles) {
      const fill = Phaser.Display.Color.HexStringToColor(obstacleColor(o.type)).color;
      const stroke = Phaser.Display.Color.HexStringToColor(o.type === 'WALL' ? '#64748b' : '#475569').color;
      g.fillStyle(fill, 1);
      g.fillRect(o.x, o.y, o.width, o.height);
      g.lineStyle(1, stroke, 1);
      g.strokeRect(o.x, o.y, o.width, o.height);
      if (o.label) {
        const t = this.add.text(o.x + o.width / 2, o.y + o.height / 2 + 3, o.label, { fontFamily: 'sans-serif', fontSize: '9px', color: '#94a3b8' }).setOrigin(0.5).setDepth(5);
        this.obstacleLabels.push(t);
      }
      if (o.blocksSound) {
        g.lineStyle(0.5, 0xef4444, 0.2);
        dashRect(g, o.x - 2, o.y - 2, o.width + 4, o.height + 4, 2, 4);
      }
    }
  }

  /** P-0816-C：链接层（听觉带虚线圆+角色连线）矢量线渲染已整体移除（主人 2026-08-16 反馈「矢量线还在」）。
   *  linkG 保留创建（create() 中 this.add.graphics() 无害），不再有任何绘制调用。 */

  /**
   * 群组框（conversation-status → 可视化 + P-0803-G 玩家加入/离开入口）。
   * opts.playerName + opts.playerInWorld：玩家角色在场时，为可加入的组叠加悬浮按钮——
   * 玩家已在组内 → 「🚪 离开对话」；未在组内且非 DYAD（后端 DYAD 上限 2 必满）→ 「💬 加入对话」；
   * 其余（玩家不在场 / DYAD 已满 / 无组 id）不显示入口。加入成功后 participants 自动含玩家名 → 变离开入口（4s 轮询自动反映）。
   */
  applyGroups(groups: SimGroup[], opts?: { playerName?: string; playerInWorld?: boolean }) {
    this.groups = groups || [];
    // 清理上一轮按钮（4s 轮询重建，防泄漏）
    for (const b of this.groupButtons) if (b.active) b.destroy();
    this.groupButtons = [];
    const g = this.groupG;
    g.clear();
    const pn = opts?.playerName ? String(opts.playerName).trim() : '';
    const playerInWorld = Boolean(pn && opts?.playerInWorld);
    // P-0813-K：角落「加入」按钮接近才显示——先算玩家当前可加入的群（与任一成员<100px
    // 或群中心<120px，与群提示胶囊同判定）；已在组内的群显示「离开」不受距离限制（随时可退出）。
    const approachable = new Set(
      playerInWorld ? findApproachableGroups(pn, Array.from(this.lastAgentData.values()), this.groups) : []);
    for (const grp of this.groups) {
      const members = grp.participants || [];
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      for (const name of members) {
        const a = this.lastAgentData.get(name);
        if (!a) continue;
        minX = Math.min(minX, a.x - 25); maxX = Math.max(maxX, a.x + 25);
        minY = Math.min(minY, a.y - 50); maxY = Math.max(maxY, a.y + 25);
      }
      if (minX === Infinity) continue; // 成员均不在场 → 无可视区域（含按钮）
      const mc = Phaser.Display.Color.HexStringToColor(groupModeColor(grp.mode || '')).color;
      const pad = 15;
      // P-0803-G：玩家加入/离开入口（群组框右上角悬浮按钮）
      // P-0813-K：加入按钮仅在玩家接近该群时显示（approachable 命中）；离开按钮不受距离限制
      if (grp.id) {
        const inGroup = members.includes(pn);
        if (!playerInWorld) {
          // 导演没有可加入世界的角色：也必须有可点的旁听入口，不能让群组框只是装饰。
          this.drawGroupButton(grp.id, 'observe', maxX + pad, minY - pad, mc);
        } else if (inGroup) {
          this.drawGroupButton(grp.id, 'leave', maxX + pad, minY - pad, mc);
        } else if (grp.mode !== 'DYAD' && approachable.has(grp.id)) {
          // DYAD 后端上限 2（1v1 语义，调研 §4.2 #5）——玩家不在组内时必满，不提供加入入口
          this.drawGroupButton(grp.id, 'join', maxX + pad, minY - pad, mc);
        }
      }
      if (members.length < 2) continue; // 可视化沿用既有规则：单成员组不画框
      g.lineStyle(1.5, mc, 1);
      dashRect(g, minX - pad, minY - pad, maxX - minX + pad * 2, maxY - minY + pad * 2, 8, 4);
      const label = `${grp.mode || 'GROUP'} (R${grp.rounds ?? 1})`;
      const t = this.add.text(minX - pad, minY - pad - 4, label, { fontFamily: 'sans-serif', fontSize: '10px', color: groupModeColor(grp.mode || '') }).setOrigin(0, 0).setDepth(9);
      this.time.delayedCall(6000, () => { if (t.active) t.destroy(); });
    }
  }

  /** 群组悬浮操作按钮（导演模式的旁听不触及后端成员）。 */
  private drawGroupButton(groupId: string, action: 'join' | 'leave' | 'observe', right: number, top: number, color: number) {
    const text = action === 'join' ? '💬 加入对话' : action === 'leave' ? '🚪 离开对话' : '👁 旁听对话';
    const w = 92;
    const h = 22;
    const x = right - w;
    const y = top - h - 4;
    const btn = this.add.container(x, y);
    // Container 无 origin（子对象以容器本地 (0,0) 为基准）——bg/label 放 (w/2,h/2)，命中区 Rectangle(0,0,w,h) 即覆盖按钮可视区
    const bg = this.add.rectangle(w / 2, h / 2, w, h, 0x0f172a, 0.92).setStrokeStyle(1, color, 0.95);
    const label = this.add.text(w / 2, h / 2 + 0.5, text, { fontFamily: 'sans-serif', fontSize: '11px', color: '#e2e8f0' }).setOrigin(0.5);
    btn.add([bg, label]);
    btn.setDepth(80);
    btn.setInteractive(new Phaser.Geom.Rectangle(0, 0, w, h), Phaser.Geom.Rectangle.Contains);
    // hover 反馈（指针移入高亮）
    btn.on(Phaser.Input.Events.GAMEOBJECT_POINTER_OVER, () => { bg.setFillStyle(0x1e293b, 0.95); });
    btn.on(Phaser.Input.Events.GAMEOBJECT_POINTER_OUT, () => { bg.setFillStyle(0x0f172a, 0.92); });
    btn.on(Phaser.Input.Events.GAMEOBJECT_POINTER_DOWN, () => {
      this.callbacks.onGroupAction(groupId, action);
    });
    this.groupButtons.push(btn);
  }

  private updateNarration(text: string, directorActive: boolean) {
    this.narrationText.setText(text ? '【主控】' + text : '');
    this.narrationText.setPosition(WORLD_W / 2, 8);
    this.narrationText.setAlpha(directorActive ? 1 : 0.7);
  }

  private updateStatus(tick: number) {
    this.statusText.setText(`Tick ${tick} | Agents ${this.agents.size} | ${this.running ? '运行中' : '已停止'}`);
  }

  /** 每帧：重绘链接层（听觉带/连线实时跟随角色移动）+ 接近提示 + 平滑插值 */
  update(time: number, delta: number) {
    // P-0814-I：WASD/方向键持续移动 —— 按住时每 120ms 发送一次移动方向（POST /move-dir）
    // 聊天输入框聚焦（打字）时不响应 WASD，防止边打字边移动角色
    const keys = this.moveKeys;
    let kx = 0, ky = 0;
    if (keys && !this.isTypingInInput()) {
      if (keys.A.isDown || keys.LEFT.isDown) kx = -1;
      else if (keys.D.isDown || keys.RIGHT.isDown) kx = 1;
      if (keys.W.isDown || keys.UP.isDown) ky = -1;
      else if (keys.S.isDown || keys.DOWN.isDown) ky = 1;
    }
    if (kx !== 0 || ky !== 0) {
      if (!this.moveActive) {
        this.moveActive = true;
        // 键盘优先（后到优先）：方向键按下 → 清掉点击目标标记
        this.clickTarget = null;
        this.drawMarker();
      }
      if (time - this.lastMoveDirSent >= MOVE_DIR_INTERVAL_MS) {
        this.lastMoveDirSent = time;
        this.sendMoveDir(kx, ky);
      }
    } else if (this.moveActive) {
      // 松开 → 停止发送 + P-0816-C：发零方向停止信号（后端 clearTarget，角色立即静止——
      // 原实现仅停发，后端 manualTarget 残留 → 角色继续滑向最后目标点 = 用户角色乱动根因）
      this.moveActive = false;
      this.lastMoveDirSent = 0;
      try { this.sendMoveDir(0, 0); } catch { /* 忽略 */ }
    }
    // P-0813-G：快照间视觉平滑——容器位置向「最新快照位置」追赶（跟手），
    // 逻辑不变（后端权威坐标），仅渲染层插值：移动中连续无跳变，停下平滑收敛。
    // P-0816-A：删除速度外推（vx/vy）——玩家角色残余速度被外推放大导致自走（主人 2026-08-16 拍板），
    // 一律以快照位置为准插值收敛，不做 vx/vy 外推。
    // ⚠️ P-0815-F 批2（并行碰撞）：原外推块遗留的 const dt 未被使用（TS6133），已删（纯死代码清理，行为零变化）
    const k = Math.min(1, (delta / 16.666) * 0.35); // 每帧向目标收敛 ~35%
    for (const [name, c] of this.agents) {
      const d = this.lastAgentData.get(name);
      if (!d) continue;
      const tx = d.x;
      const ty = d.y;
      const nx = c.x + (tx - c.x) * k;
      const ny = c.y + (ty - c.y) * k;
      if (Math.abs(nx - c.x) > 0.05 || Math.abs(ny - c.y) > 0.05) c.setPosition(nx, ny);
    }
    // P-0813-G：受控 agent 到达 → 点击标记自动消失（hasTarget=false 即清，不再等 4s 兜底）
    if (this.clickTarget) {
      let me: string | undefined = this.playerName && this.lastAgentData.has(this.playerName) ? this.playerName : undefined;
      if (!me) {
        const names = Array.from(this.lastAgentData.keys());
        me = names.find(n => n === 'me' || n === '我' || n === '主人') || names[0];
      }
      const d = me ? this.lastAgentData.get(me) : undefined;
      if (d && !d.hasTarget) {
        this.clickTarget = null;
        this.drawMarker();
      }
    }
    // P-0816-C：链接层（听觉带虚线圆+角色连线）矢量线渲染已整体移除——原 P-0815-F 此处
    // 按 linkDirty 消费 redrawLinks；不再渲染。
    // P-0816-B：热点金色脉冲（每帧重绘；tileMap 无 zones 时 mapZones 为空，clear 后立即返回）
    this.updateMapZones(time);
    this.updateApproachHints(time);
    this.updateGroupApproachHints(time);
  }
}
