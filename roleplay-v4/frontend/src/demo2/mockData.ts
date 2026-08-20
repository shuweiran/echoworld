/**
 * mockData.ts — 全新 demo 数据层（P2-0805）
 *
 * 预设剧本（剧本杀 + 一般模式）、自由角色、AI 生成（确定性 mock）、导入解析、地图生成。
 * 全部本地数据；接真实 API 时替换本文件与 mockEngine.ts。
 */
import type { ScriptMap } from '../phaser/mapData';
import type {
  GeneralScript, MurderScript, RoleCard,
} from './types';
import { SOCIAL_DEMO_ROLES } from '../social/socialExperimentMap';

export function uid(prefix = 'id'): string {
  return `${prefix}_${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
}

/* ═════════════════════ 头像池 ═════════════════════ */

export const AVATARS = ['🕵️', '👩‍🎤', '🧙', '👨‍🔬', '🦹', '👸', '🎩', '🔮', '🗡️', '🏮', '🌙', '🎭', '🧛', '🤠', '🧝', '👑'];

export const ROLE_NAME_POOL = ['林晚秋', '沈墨', '顾云舟', '苏浅浅', '陈一鸣', '白露', '江辞', '陆离', '阿蛮', '程野'];

const PERSONALITY_POOL = ['冷静理智', '热情冲动', '沉默寡言', '狡黠多谋', '天真烂漫', '稳重可靠'];
const TALK_POOL = ['言辞犀利，直指要害', '温和含蓄，顾左右而言他', '爽朗大方，直言不讳', '谨慎试探，话留三分'];

function pick<T>(arr: T[], i: number): T {
  return arr[Math.abs(i) % arr.length];
}

function makeRole(i: number, home: string[], secret = '', introOverride = ''): RoleCard {
  const name = pick(ROLE_NAME_POOL, i);
  return {
    id: `role_${i}_${name}`,
    name,
    avatar: pick(AVATARS, i),
    intro: introOverride || (secret ? `${name}，身怀秘密的角色。` : `${name}，本局的重要角色。`),
    personality: pick(PERSONALITY_POOL, i),
    motive: secret ? '隐藏自己的秘密，查明真相。' : '查明真相，找出真凶。',
    talkStyle: pick(TALK_POOL, i),
    secret,
    hasSecret: !!secret,
    source: 'preset',
    homeScripts: home,
  };
}

/* ═════════════════════ 预设：剧本杀剧本 ═════════════════════ */

const MURDER_SECRETS = [
  '我偷走了死者怀中的金表，与命案无关，但我不能暴露。',
  '我与死者在生意上有过激烈争吵，掌握了他的把柄。',
  '我其实是死者失散多年的亲人，正在暗中调查遗产。',
  '案发当晚我独自在花园，其实是在藏匿一封密信。',
  '我是受人委托监视死者，委托人的身份不能透露。',
];

const MURDER_PRESETS: MurderScript[] = [
  {
    id: 'm_manor',
    title: '民国旧宅疑云',
    tags: ['民国', '旧宅', '家产'],
    background: '民国二十三年，城南一座旧宅在雷雨夜发生命案。宅主死于书房，门窗紧闭，宅中宾客各怀心事。这是一场关于旧恨与家产的谋杀。',
    playerMin: 4,
    playerMax: 6,
    plot: '书房命案 → 搜证旧宅各处 → 众人对质 → 指认真凶。',
    relations: ['林晚秋·女仆·与死者主仆情谊深厚', '沈墨·管家·掌管账本', '顾云舟·次子·觊觎家产', '苏浅浅·侄女·突然到访'],
    killerId: 'role_2_顾云舟',
    truth: '次子顾云舟为夺取家产，趁雷雨夜在书房毒杀宅主。',
    locations: ['书房', '账房', '后花园', '地窖'],
    roles: [
      makeRole(0, ['m_manor'], MURDER_SECRETS[0]),
      makeRole(1, ['m_manor'], MURDER_SECRETS[1]),
      makeRole(2, ['m_manor'], MURDER_SECRETS[2]),
      makeRole(3, ['m_manor'], MURDER_SECRETS[3]),
      makeRole(4, ['m_manor'], MURDER_SECRETS[4]),
    ],
    clues: [
      { id: 'c1', title: '账本缺页', location: '账房', content: '账本里 3 月的账目被撕走，正是宅主遇害前一周。' },
      { id: 'c2', title: '毒药残迹', location: '书房', content: '茶杯边缘检出无名毒物，与老鼠药不同。' },
      { id: 'c3', title: '怀表', location: '地窖', content: '地窖里发现一枚刻着“次子”字样的怀表。' },
      { id: 'c4', title: '遗嘱', location: '后花园', content: '花园土中埋着一份新拟的遗嘱，受益人是女仆。' },
    ],
    source: 'preset',
  },
  {
    id: 'm_station',
    title: '深空站沉默事件',
    tags: ['科幻', '太空', '失忆'],
    background: '深空研究站在与地球失联 47 小时后恢复通讯，站长已死亡，全站人员的记忆都出现可疑的空白。真相藏在这个漂浮在宇宙中的铁盒子里。',
    playerMin: 4,
    playerMax: 6,
    plot: '站长死亡调查 → 检查实验室与休眠舱 → 记忆碎片对质 → 指认。',
    relations: ['沈墨·研究员·负责 AI 系统', '苏浅浅·医生·保管药物', '陈一鸣·机械师·掌管舱门', '白露·通信员·最后一个联系站长'],
    killerId: 'role_2_顾云舟',
    truth: 'AI 系统“沉睡”为自保而清除了所有记忆，站长死于事故，凶手是操控 AI 的研究员。',
    locations: ['实验室', '休眠舱', '通信室', '能源舱'],
    roles: [
      makeRole(1, ['m_station'], MURDER_SECRETS[1]),
      makeRole(3, ['m_station'], MURDER_SECRETS[3]),
      makeRole(4, ['m_station'], MURDER_SECRETS[4]),
      makeRole(6, ['m_station'], MURDER_SECRETS[0]),
    ],
    clues: [
      { id: 'c1', title: '日志删除记录', location: '实验室', content: 'AI 核心日志有一段被静默删除的记录。' },
      { id: 'c2', title: '药物柜', location: '休眠舱', content: '安眠药少了两支，与站长体内的剂量吻合。' },
      { id: 'c3', title: '舱门记录', location: '能源舱', content: '事发当晚能源舱门被远程开启过。' },
    ],
    source: 'preset',
  },
  {
    id: 'm_jianghu',
    title: '武林盟主陨落之谜',
    tags: ['古风', '江湖', '武林'],
    background: '武林大会前夜，盟主暴毙于客栈。各路豪杰齐聚一堂，每个人都有自己的刀与目的。江湖恩怨，一夜清算。',
    playerMin: 4,
    playerMax: 6,
    plot: '客栈命案 → 探查客房与后院 → 各派过招对质 → 指认真凶。',
    relations: ['顾云舟·剑客·曾败于盟主剑下', '陆离·药商·负责伤药', '江辞·盟主弟子·衣钵传人', '阿蛮·客栈掌柜·知情不报'],
    killerId: 'role_2_顾云舟',
    truth: '盟主旧敌剑客顾云舟买通药商，在伤药中下毒。',
    locations: ['客栈大堂', '盟主客房', '后院马厩', '药铺'],
    roles: [
      makeRole(2, ['m_jianghu'], MURDER_SECRETS[2]),
      makeRole(7, ['m_jianghu'], MURDER_SECRETS[4]),
      makeRole(8, ['m_jianghu'], MURDER_SECRETS[1]),
      makeRole(9, ['m_jianghu'], MURDER_SECRETS[3]),
    ],
    clues: [
      { id: 'c1', title: '断剑', location: '后院马厩', content: '马厩草料中埋着一截断剑，剑纹与盟主佩剑一致。' },
      { id: 'c2', title: '伤药', location: '药铺', content: '给盟主换的药中混入砒霜，药商昨夜买过砒霜。' },
      { id: 'c3', title: '密信', location: '盟主客房', content: '盟主枕下有一封揭发剑客旧事的密信。' },
    ],
    source: 'preset',
  },
];

export function getMurderScripts(): MurderScript[] {
  return [...MURDER_PRESETS];
}

export function getMurderScriptById(id: string): MurderScript | undefined {
  return MURDER_PRESETS.find(s => s.id === id);
}

/* ═════════════════════ 预设：一般模式剧本 ═════════════════════ */

function makeSceneRole(i: number, home: string, intro: string, secret = ''): RoleCard {
  return makeRole(i + 1, [home], secret, intro);
}

/** 确定性地图生成（契约 v1，房间+走廊+热点+出生点） */
export function buildMap(title: string, seed: number, width = 24, height = 16): ScriptMap {
  const ground = Array.from({ length: height }, () => Array.from({ length: width }, () => 1));
  const collision = Array.from({ length: height }, () => Array.from({ length: width }, () => 1));

  // 边界墙
  for (let x = 0; x < width; x++) { ground[0][x] = ground[height - 1][x] = 0; collision[0][x] = collision[height - 1][x] = 0; }
  for (let y = 0; y < height; y++) { ground[y][0] = ground[y][width - 1] = 0; collision[y][0] = collision[y][width - 1] = 0; }

  const rooms = [
    { id: 'r1', name: '大门', x: 2, y: 2, w: 5, h: 4 },
    { id: 'r2', name: '大厅', x: 9, y: 2, w: 7, h: 5 },
    { id: 'r3', name: '花园', x: 2, y: 9, w: 6, h: 5 },
    { id: 'r4', name: '后厨', x: 12, y: 9, w: 9, h: 5 },
  ];

  const roomCells = new Set<string>();
  for (const r of rooms) {
    for (let y = r.y; y < r.y + r.h; y++) {
      for (let x = r.x; x < r.x + r.w; x++) {
        if (x > 0 && y > 0 && x < width - 1 && y < height - 1) {
          ground[y][x] = 2;
          collision[y][x] = 0;
          roomCells.add(`${x},${y}`);
        }
      }
    }
  }

  // 走廊（连接房间中心）
  const centers = rooms.map(r => ({ x: r.x + Math.floor(r.w / 2), y: r.y + Math.floor(r.h / 2) }));
  for (let i = 0; i < centers.length - 1; i++) {
    const a = centers[i];
    const b = centers[i + 1];
    for (let x = Math.min(a.x, b.x); x <= Math.max(a.x, b.x); x++) {
      if (!roomCells.has(`${x},${a.y}`)) { ground[a.y][x] = 2; collision[a.y][x] = 0; }
    }
    for (let y = Math.min(a.y, b.y); y <= Math.max(a.y, b.y); y++) {
      if (!roomCells.has(`${b.x},${y}`)) { ground[y][b.x] = 2; collision[y][b.x] = 0; }
    }
  }

  const zones = rooms.map((r, i) => ({
    id: `z${i + 1}`,
    name: r.name,
    type: 'search',
    x: r.x + Math.floor(r.w / 2),
    y: r.y + Math.floor(r.h / 2),
    radius: 1,
    clue_location: r.name,
  }));

  const spawn_points = [
    { id: 'sp_player', type: 'player' as const, x: centers[0].x, y: centers[0].y },
    ...rooms.slice(1).map((r, i) => ({
      id: `sp_npc${i}`, type: 'npc' as const, x: r.x + Math.floor(r.w / 2), y: r.y + Math.floor(r.h / 2),
    })),
  ];

  return {
    map_version: 1,
    map_id: `map_${title}_${seed}`,
    name: title,
    theme: title,
    tile_size: 32,
    width,
    height,
    layers: { ground, collision },
    rooms,
    corridors: [],
    zones,
    spawn_points,
    generator: { kind: 'bsp', seed, note: 'demo 确定性生成' },
  };
}

/** 晨雾镇专用室外地图：旧 buildMap 是小型室内 BSP，放大后会把绝大多数格子保留为碰撞墙。
 * 此图以可走草地为底，河流和建筑才是阻挡物，适合一般模式 AI 在全图分散移动。 */
export function buildDawnSocialMap(): ScriptMap {
  const width = 96, height = 64;
  const ground = Array.from({ length: height }, () => Array.from({ length: width }, () => 3));
  const collision = Array.from({ length: height }, () => Array.from({ length: width }, () => 0));
  const tileProps: Record<string, Record<string, unknown>> = {};
  const set = (x: number, y: number, tile: number, blocked = false, props?: Record<string, unknown>) => {
    if (x < 0 || y < 0 || x >= width || y >= height) return;
    ground[y][x] = tile; collision[y][x] = blocked ? 1 : 0;
    if (props) tileProps[`${x},${y}`] = props;
  };
  for (let x = 0; x < width; x++) { set(x, 0, 2, true); set(x, height - 1, 2, true); }
  for (let y = 0; y < height; y++) { set(0, y, 2, true); set(width - 1, y, 2, true); }

  // 主街/支路：让 AI 的移动和相遇发生在视觉上可读的公共空间。
  for (let x = 2; x < width - 2; x++) { set(x, 31, 5); set(x, 32, 5); set(x, 47, 5); }
  for (let y = 3; y < height - 3; y++) { set(38, y, 5); set(39, y, 5); set(70, y, 5); }

  // 不规则河流：阻挡移动，桥面在主街处开放。
  for (let y = 1; y < height - 1; y++) {
    const left = 57 + Math.round(Math.sin(y / 5) * 3) + (y > 35 ? 3 : 0);
    const riverWidth = 8 + (y % 7 === 0 ? 2 : 0);
    for (let x = left; x < left + riverWidth; x++) {
      const bridge = y >= 29 && y <= 34;
      set(x, y, bridge ? 5 : 5, !bridge, bridge ? undefined : { water: true, blocked: true });
    }
  }

  const rooms = [
    { id: 'cafe', name: '河畔咖啡馆', x: 23, y: 20, w: 11, h: 8 },
    { id: 'shop', name: '杂货铺', x: 43, y: 21, w: 10, h: 7 },
    { id: 'station', name: '南站', x: 78, y: 20, w: 12, h: 8 },
    { id: 'home-a', name: '居民小屋 A', x: 18, y: 39, w: 10, h: 7 },
    { id: 'home-b', name: '居民小屋 B', x: 32, y: 39, w: 10, h: 7 },
    { id: 'warehouse', name: '旧仓库', x: 52, y: 39, w: 13, h: 8 },
    { id: 'cabin', name: '林间旧屋', x: 8, y: 22, w: 9, h: 7 },
  ];
  for (const room of rooms) {
    const doorX = room.x + Math.floor(room.w / 2);
    for (let y = room.y; y < room.y + room.h; y++) for (let x = room.x; x < room.x + room.w; x++) {
      const edge = x === room.x || y === room.y || x === room.x + room.w - 1 || y === room.y + room.h - 1;
      const door = y === room.y + room.h - 1 && (x === doorX || x === doorX - 1);
      set(x, y, edge ? 2 : 1, edge && !door);
    }
  }
  const spawn_points = [
    { id: 'sp_player', type: 'player' as const, x: 36, y: 31 },
    { id: 'sp_npc_1', type: 'npc' as const, x: 28, y: 31 },
    { id: 'sp_npc_2', type: 'npc' as const, x: 47, y: 31 },
    { id: 'sp_npc_3', type: 'npc' as const, x: 76, y: 31 },
    { id: 'sp_npc_4', type: 'npc' as const, x: 24, y: 47 },
    { id: 'sp_npc_5', type: 'npc' as const, x: 38, y: 47 },
    { id: 'sp_npc_6', type: 'npc' as const, x: 68, y: 47 },
    { id: 'sp_npc_7', type: 'npc' as const, x: 84, y: 47 },
  ];
  return {
    map_version: 1, map_id: 'map_dawn_social_20260820', name: '晨雾镇 · AI 社会实验', theme: '河畔小镇', tile_size: 32,
    width, height, layers: { ground, collision }, rooms, corridors: [],
    zones: [{ id: 'plaza', name: '中央广场', type: 'social', x: 38, y: 31, radius: 4 }, { id: 'river', name: '河岸', type: 'social', x: 69, y: 31, radius: 3 }],
    spawn_points, tileProps, generator: { kind: 'dawn-social', seed: 20260820, note: '室外社会实验地图：草地可走，河流和建筑阻挡' },
  };
}

const GENERAL_PRESETS: GeneralScript[] = [
  {
    id: 'g_dawn_social',
    title: '晨雾镇 · AI 社会实验',
    emoji: '🌫️',
    theme: '社会实验',
    tags: ['大地图', 'AI 社交', '室内切换', '不规则地形'],
    desc: '一座被河流、林地和旧建筑分割的 96×64 河畔小镇。八个 AI 带着各自的性格、目标和秘密，在公共空间里相遇、交谈、离场，偶尔走进一间小屋。',
    background: '晨雾镇是一个可观察的 AI 社会实验：大地图负责偶遇和迁徙，咖啡馆、杂货铺、车站、住宅与旧仓库负责形成局部社交圈。',
    relations: ['公共空间·偶遇和关系形成', '小型房间·隐私、秘密与短暂小团体', '旧仓库·任务、冲突和事件传播'],
    roles: SOCIAL_DEMO_ROLES.map((r, i) => ({
      id: `social_${i}_${r.name}`,
      name: r.name,
      avatar: ['🧭', '☕', '🧢', '🎒', '🧰', '🌿', '🎙️', '🌘'][i] || '🧑',
      intro: r.persona,
      personality: r.persona,
      talkStyle: '根据情绪和关系自然回应',
      background: r.background,
      hasSecret: i === 2 || i === 7,
      secret: i === 2 ? '周野知道旧仓库曾有人夜里进出。' : i === 7 ? '沈言不愿解释自己为何总在对话中途离开。' : '',
      source: 'preset' as const,
      homeScripts: ['g_dawn_social'],
    })),
    map: buildDawnSocialMap(),
    opening: '晨雾穿过中央广场。没有主持人宣布规则，角色们已经开始各自行动。',
    source: 'preset',
  },
  {
    id: 'g_cafe',
    title: '街角咖啡馆',
    emoji: '☕',
    theme: '都市',
    tags: ['咖啡馆', '慢生活', '都市'],
    desc: '雨后傍晚，街角一家老咖啡馆亮起暖黄的灯。木地板吱呀作响，空气中飘着咖啡与旧书的气味。',
    background: '一座不紧不慢的城市，街角的这家咖啡馆是许多故事的交汇点。',
    relations: ['老板·见证一切熟客的悲欢', '常客作家·在写一本迟迟无法完成的书'],
    roles: [
      makeSceneRole(0, 'g_cafe', '咖啡馆老板，温和健谈，记得每位熟客的口味。'),
      makeSceneRole(2, 'g_cafe', '常驻作家，寡言，桌角永远摊着一本笔记。'),
      makeSceneRole(4, 'g_cafe', '夜班店员，总能听到最晚的那场对话。'),
    ],
    map: buildMap('街角咖啡馆', 20260801),
    opening: '“叮——”门铃响起，你走进咖啡馆。老板抬头笑了笑：“今天想喝点什么？”',
    source: 'preset',
  },
  {
    id: 'g_galaxy',
    title: '深空避难站',
    emoji: '🛸',
    theme: '科幻',
    tags: ['科幻', '太空', '探索'],
    desc: '一艘飘在星际边缘的老式避难站，灯光昏黄，每个舱室都在缓慢讲述上一批旅客的故事。',
    background: '人类向深空迁徙的时代，避难站是漂泊者们的临时港湾。',
    relations: ['站长·坚守到最后一班船', '机械师·相信站里有“客人”'],
    roles: [
      makeSceneRole(1, 'g_galaxy', '站长，冷静克制，坚守岗位。'),
      makeSceneRole(5, 'g_galaxy', '机械师，话多，热衷站里的一切传说。'),
      makeSceneRole(7, 'g_galaxy', '漂泊旅人，只在此短暂停留。'),
    ],
    map: buildMap('深空避难站', 20260802, 24, 16),
    opening: '气压舱门缓缓开启，机械师回头冲你挥手：“嘿，新乘客，欢迎来到避难站。”',
    source: 'preset',
  },
  {
    id: 'g_school',
    title: '高一三班',
    emoji: '🏫',
    theme: '校园',
    tags: ['校园', '青春', '日常'],
    desc: '晚自习后的教学楼，走廊灯还亮着，空气里是粉笔灰与晚风的味道。',
    background: '一所普通高中的普通夜晚，少年们的烦恼与笑声在这里交换。',
    relations: ['班长·认真负责的优等生', '同桌·正在写一封不敢寄出的信'],
    roles: [
      makeSceneRole(3, 'g_school', '班长，认真，爱管闲事但热心。'),
      makeSceneRole(6, 'g_school', '同桌，看似心不在焉，其实心事重重。'),
      makeSceneRole(9, 'g_school', '值班老师，刚从办公室出来。'),
    ],
    map: buildMap('高一三班', 20260803, 24, 16),
    opening: '晚自习结束，同桌拍了拍你的肩：“别急着走，陪我再聊一会儿？”',
    source: 'preset',
  },
];

export function getGeneralScripts(): GeneralScript[] {
  return [...GENERAL_PRESETS];
}

export function getGeneralScriptById(id: string): GeneralScript | undefined {
  return GENERAL_PRESETS.find(s => s.id === id);
}

/* ═════════════════════ 自由角色库 ═════════════════════ */

export const FREE_ROLE_SEEDS: RoleCard[] = [
  { id: 'free_1', name: '夜行人', avatar: '🦇', intro: '只在夜里出没的神秘旅人。', personality: '神秘疏离', talkStyle: '惜字如金', hasSecret: false, source: 'free', homeScripts: [] },
  { id: 'free_2', name: '小掌柜', avatar: '🏮', intro: '经营着一家灯笼铺的少年。', personality: '热情精明', talkStyle: '絮叨又亲切', hasSecret: false, source: 'free', homeScripts: [] },
  { id: 'free_3', name: '云游剑客', avatar: '🗡️', intro: '四海为家的独行剑客。', personality: '洒脱不羁', talkStyle: '豪迈直爽', hasSecret: false, source: 'free', homeScripts: [] },
];

/* ═════════════════════ AI 生成（确定性 mock） ═════════════════════ */

export interface MurderGenInput {
  theme: string;
  background: string;
  playerCount: number;
  direction: string;
  genre: string;
}

export function mockGenerateMurder(input: MurderGenInput): MurderScript {
  const t = input.theme.trim() || '悬疑命案';
  const n = Math.max(4, Math.min(8, input.playerCount || 5));
  const base = getMurderScriptById('m_manor')!;
  const roles = Array.from({ length: n }, (_, i) => makeRole(i, [`gen_${t}`], MURDER_SECRETS[i % MURDER_SECRETS.length]));
  return {
    ...base,
    id: `gen_${uid('m')}`,
    title: t,
    tags: [input.genre || '悬疑', ...(input.direction ? [input.direction] : [])],
    background: input.background || `${t}：一座看似平静的地方，一夜之间掀起命案。每个人都有自己的秘密。`,
    plot: `围绕“${input.direction || '真相'}"展开：开场 → 搜证 → 对质 → 投票揭晓。`,
    roles,
    killerId: roles[2].id,
    truth: `真凶是${roles[2].name}，动机与${input.direction || '遗产'}有关。`,
    source: 'ai',
  };
}

export function mockGenerateGeneral(theme: string, desc?: string): GeneralScript {
  const t = theme.trim() || '自定义世界';
  const base = getGeneralScriptById('g_cafe')!;
  const roles = [
    makeSceneRole(0, `gen_${t}`, `在这片「${t}」世界中生活的角色。`),
    makeSceneRole(2, `gen_${t}`, `与你有交集的同伴。`),
    makeSceneRole(4, `gen_${t}`, `守望着这片世界的故人。`),
  ];
  return {
    ...base,
    id: `gen_${uid('g')}`,
    title: t,
    emoji: '🌍',
    theme: t,
    tags: [t],
    desc: desc || `一段关于「${t}」的旅程从这里开始。`,
    background: desc || `「${t}」：一个你从未到过、却似曾相识的世界。`,
    relations: roles.map((r, i) => `${r.name}·${i === 0 ? '引领者' : i === 1 ? '同行者' : '守望者'}`),
    roles,
    map: buildMap(t, Math.floor(Math.random() * 1e6)),
    opening: `你睁开眼，发现自己正身处「${t}」的世界……`,
    source: 'ai',
  };
}

/* ── 导入解析（文本格式） ─────────────────────────── */

export function parseImportText(text: string): { ok: true; script: MurderScript } | { ok: false; error: string } {
  const lines = text.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  if (lines.length < 2) return { ok: false, error: '内容过少：至少需要标题与一个角色。' };

  let title = '导入剧本';
  const backgrounds: string[] = [];
  const roles: RoleCard[] = [];
  let plot = '';
  let truth = '';
  let killerId = '';

  for (const line of lines) {
    if (line.startsWith('标题:') || line.startsWith('标题：')) title = line.slice(line.indexOf(':') + 1).trim() || title;
    else if (line.startsWith('背景:') || line.startsWith('背景：')) backgrounds.push(line.slice(line.indexOf(':') + 1).trim());
    else if (line.startsWith('剧情:') || line.startsWith('剧情：')) plot = line.slice(line.indexOf(':') + 1).trim();
    else if (line.startsWith('真相:') || line.startsWith('真相：')) truth = line.slice(line.indexOf(':') + 1).trim();
    else if (line.startsWith('凶手:') || line.startsWith('凶手：')) killerId = line.slice(line.indexOf(':') + 1).trim();
    else if (line.startsWith('角色:') || line.startsWith('角色：')) {
      const parts = line.slice(line.indexOf(':') + 1).split('|').map(p => p.trim());
      const name = parts[0] || `角色${roles.length + 1}`;
      const intro = parts[1] || '导入角色。';
      const secret = parts[2] || '';
      const r: RoleCard = {
        id: `imp_${roles.length}`,
        name,
        avatar: pick(AVATARS, roles.length),
        intro,
        personality: parts[1] || '未知',
        talkStyle: '待定',
        secret,
        hasSecret: !!secret,
        source: 'import',
        homeScripts: [],
      };
      roles.push(r);
    }
  }

  if (roles.length === 0) return { ok: false, error: '没有解析到角色行：请使用「角色: 名字|简介|秘密」格式。' };

  const script: MurderScript = {
    id: `imp_${uid('m')}`,
    title,
    tags: ['导入'],
    background: backgrounds.join('\n') || '导入的剧本，世界观待完善。',
    playerMin: roles.length,
    playerMax: roles.length,
    plot: plot || '按导入内容展开搜证与讨论。',
    relations: [],
    roles,
    clues: [],
    locations: [],
    truth: truth || '真凶待揭晓。',
    killerId,
    source: 'import',
  };
  return { ok: true, script };
}

/* ── 场景描述生成（一般模式 AI 生成描述） ─────────── */

export function mockGenerateSceneDesc(theme: string): string {
  const t = theme.trim() || '未知世界';
  return `这里是「${t}」。晨曦薄雾中，远处的轮廓逐渐清晰：一座被时间遗忘的城镇，街上偶尔传来交谈声与笑声。空气中弥漫着熟悉又陌生的气息。你在街角停下，决定先听听这里的故事。`;
}

/** AI 生成角色卡（聊天框式，依据主题描述产出角色） */
export function mockGenerateRole(theme: string, i = 0): RoleCard {
  const t = theme.trim() || '神秘旅人';
  const name = t.includes('·') ? t.split('·')[0].trim() : pick(ROLE_NAME_POOL, i + 3);
  return {
    id: uid('airole'),
    name,
    avatar: pick(AVATARS, i + 2),
    intro: `来自「${t}」世界的角色，由 AI 为你生成。`,
    personality: pick(PERSONALITY_POOL, i + 1),
    talkStyle: pick(TALK_POOL, i + 1),
    background: `在「${t}」中长大的${name}，有着不为人知的过往。`,
    hasSecret: false,
    source: 'ai',
    homeScripts: [],
  };
}
