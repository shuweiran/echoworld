/**
 * galDemoData.ts — Gal 界面 demo 假数据（P-0810-02）
 *
 * 3 名像素风角色轮流说话 + 玩家选项节点 + 旁白节点。
 * 立绘为程序化像素画：12×16 字符模板 + 调色板 → SVG 渲染（无图片资源，离线可构建）。
 * 后续接真实 SSE + Pony 立绘时，仅需替换数据源（见 docs/gal-界面设计.md §6）。
 */

/** 像素画字符含义：h=头发 s=皮肤 e=眼 m=嘴 o=衣 a=点缀色 .=透明 */
export interface GalSpeaker {
  id: string;
  name: string;
  title: string; // 称号
  color: string; // 名字/强调色
  hue: number; // 立绘背景色相
  sprite: string[]; // 12 宽字符模板
  palette: Record<string, string>; // 字符 → 颜色
  isPlayer?: boolean;
  /**
   * P-0810-03：对后端生图角色的映射 id（Pony 立绘）。
   * 有值 → GalCharacter 在对应后端角色有图时用真实立绘，无图回退像素占位；
   * 无值但 SPEAKER_BACKEND_PROFILES 有档案 → 可「注册并生成」后自动映射。
   */
  backendId?: string;
  /** P-0810-06：真实对局流里的未知说话者（buildPlaceholderSpeaker 产物）——渲染姓名首字占位 */
  placeholder?: boolean;
}

export interface GalChoice {
  text: string;
  /** 绝对索引跳转（省略=顺延下一条） */
  goto?: number;
}

export interface GalMessage {
  id: string;
  /** speakerId：角色 id / 'player'（玩家节点）/ 'narrator'（旁白） */
  speakerId: string;
  text: string;
  type: 'line' | 'choice' | 'narrator';
  choices?: GalChoice[];
}

/** 模板 T1：齐刘海短发（凛） */
const T_BOB = [
  '....hhhh....',
  '...hhhhhh...',
  '..hhhhhhhh..',
  '.hhhhhhhhhh.',
  '.hssssssssh.',
  '.hsseessshh.',
  '.hssssssshh.',
  '.hssmmssshh.',
  '..ssssssss..',
  '..oooooooo..',
  '.oooooooooo.',
  '.oaooaooaoo.',
  '.oooooooooo.',
  '..oooooooo..',
  '..oooooooo..',
  '............',
];

/** 模板 T2：双马尾（绫） */
const T_TWINTAIL = [
  'hh..hhhh..hh',
  'hh..hhhh..hh',
  'hh.hhhhhh.hh',
  'hhhhhhhhhhhh',
  '.hssssssssh.',
  '.hsseessshh.',
  '.hssssssshh.',
  '.hssmmssshh.',
  '..ssssssss..',
  '..oooooooo..',
  '.oooooooooo.',
  '.oaooaooaoo.',
  '.oooooooooo.',
  '..oooooooo..',
  '..oooooooo..',
  '............',
];

/** 模板 T3：长直发（露娜） */
const T_LONG = [
  '....hhhh....',
  '...hhhhhh...',
  '..hhhhhhhh..',
  '.hhhhhhhhhh.',
  '.hssssssssh.',
  'hhsseessshh.',
  'hhssssssshh.',
  'hhssmmssshh.',
  'hhssssssss..',
  'hh..oooooo..',
  'hh.oooooooo.',
  'hh.oaoaoaoo.',
  'hh.oooooooo.',
  'hh..oooooo..',
  'hh..........',
  '............',
];

/** 模板 T4：未知对局角色（通用轮廓，姓名首字占位） */
const T_UNKNOWN = [
  '....hhhh....',
  '...hhhhhh...',
  '..hhhhhhhh..',
  '.hhhhhhhhhh.',
  '.hssssssssh.',
  '.hsseessshh.',
  '.hssssssshh.',
  '.hssmmssshh.',
  '..ssssssss..',
  '..oooooooo..',
  '.oooooooooo.',
  '.oaooaooaoo.',
  '.oooooooooo.',
  '..oooooooo..',
  '..oooooooo..',
  '............',
];

/** 玩家迷你头像（对话框用） */
const T_PLAYER = [
  '....hhhh....',
  '...hhhhhh...',
  '..hsssssshh.',
  '.hsseesshhh.',
  '.hssmmsshhh.',
  '..ssssssss..',
  '..oooooooo..',
  '............',
];

const SKIN = '#ffe0c0';
const MOUTH = '#d06080';

export const GAL_SPEAKERS: GalSpeaker[] = [
  {
    id: 'aya',
    name: '绫',
    title: '元气少女',
    color: '#ff8fc4',
    hue: 330,
    sprite: T_TWINTAIL,
    palette: { h: '#ff6fae', s: SKIN, e: '#5a3a7a', m: MOUTH, o: '#ff4f7b', a: '#ffd166' },
    backendId: 'heroine', // P-0810-03：绫 ↔ 预置角色「小铃」（heroine）
  },
  {
    id: 'rin',
    name: '凛',
    title: '冷静天才',
    color: '#7fd0ff',
    hue: 210,
    sprite: T_BOB,
    palette: { h: '#6fd0ff', s: SKIN, e: '#2b3a6a', m: MOUTH, o: '#4d6bff', a: '#c0e6ff' },
    backendId: 'knight', // P-0810-03：凛 ↔ 预置角色「凯尔」（knight）
  },
  {
    id: 'luna',
    name: '露娜',
    title: '神秘占卜师',
    color: '#c99bff',
    hue: 270,
    sprite: T_LONG,
    palette: { h: '#c99bff', s: SKIN, e: '#6a3ab8', m: '#c99bff', o: '#8a5cff', a: '#ffd166' },
    backendId: 'luna', // P-0810-03：露娜 → 前端注册并生成（见 SPEAKER_BACKEND_PROFILES）
  },
  {
    id: 'player',
    name: '你',
    title: '列车乘客',
    color: '#ffd166',
    hue: 160,
    sprite: T_PLAYER,
    palette: { h: '#3d3a55', s: SKIN, e: '#2b2b45', m: '#b06a6a', o: '#3f9e6f', a: '#ffd166' },
    isPlayer: true,
  },
];

export const NARRATOR_NAME = '旁白';

/**
 * P-0810-03：后端未预置的 demo 角色自动注册档案（「注册并生成」用）。
 * 外貌描述/风格固定——同角色固定外貌模板 + 固定风格词 + 固定 seed（角色 ID hash）
 * 保证同角色风格一致（与后端 ImageGenService 规则一致）。
 *
 * P-0810-06：补 heroine（小铃）/knight（凯尔）两个后端预置角色档案——
 * 后端角色注册表 yml 已有这两个预置角色（重启自动注册），此处档案供前端
 * 在状态未同步到时的「注册并生成」兜底（幂等覆盖同名角色）。
 */
export const SPEAKER_BACKEND_PROFILES: Record<string, { name: string; appearance: string; style: string }> = {
  luna: {
    name: '露娜',
    appearance: '银色长发，紫色眼眸，神秘占卜师，深紫长袍，星月发饰',
    style: 'anime style, cel shading, vibrant colors, detailed eyes',
  },
  heroine: {
    name: '小铃',
    appearance: 'silver long hair, purple eyes, 16-year-old japanese girl, white kimono with red ribbons',
    style: 'retro game character art style, 16-bit pixel art, clean outlines, flat colors',
  },
  knight: {
    name: '凯尔',
    appearance: 'golden short hair, blue eyes, handsome young knight, silver and blue armor',
    style: 'retro game character art style, 16-bit pixel art, clean outlines, flat colors',
  },
};

/** P-0810-06：后端角色名/发言名 → 后端生图角色 id（说话者立绘映射）。
 *  覆盖后端预置角色名（小铃/凯尔）+ demo 角色名（绫/凛）+ 前端注册档案名（露娜）；
 *  未知角色名不在表内 → 占位立绘 + 面板「可注册生成」提示。
 *  P-0818-F：改为可变 Map，支持运行时动态注册（用户在角色卡点「生成形象」后自动加入映射）。 */
export const BACKEND_NAME_TO_ID: Record<string, string> = {
  小铃: 'heroine',
  绫: 'heroine',
  凯尔: 'knight',
  凛: 'knight',
  露娜: 'luna',
};

/** P-0818-F：运行时动态注册角色名 → 后端 ID 映射（refreshImageStatus 按名字自动填充） */
export function registerBackendMapping(name: string, backendId: string) {
  if (name && backendId && !BACKEND_NAME_TO_ID[name]) {
    BACKEND_NAME_TO_ID[name] = backendId;
  }
}

/** 未知角色名 → 后端角色 id（无映射返回 undefined，走占位+可注册生成） */
export function backendIdForName(name: string): string | undefined {
  return BACKEND_NAME_TO_ID[name] ?? undefined;
}

/** 名字 → 确定性色相（占位立绘用，同角色稳定同色） */
export function hashHue(name: string): number {
  let h = 7;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return h % 360;
}

/**
 * P-0810-06：未知说话者占位立绘角色（SVG 姓名首字 + 名字色相派生调色板）。
 * 真实 SSE 流里的任意 agent_name / 玩家名都会经此生成一个可展示的 GalSpeaker，
 * placeholder=true 时 GalCharacter 渲染姓名首字大图（非像素小人）。
 */
export function buildPlaceholderSpeaker(name: string, backendId?: string): GalSpeaker {
  const hue = hashHue(name);
  return {
    id: name,
    name,
    title: '对局角色',
    color: `hsl(${hue} 90% 72%)`,
    hue,
    sprite: T_UNKNOWN,
    palette: {
      h: `hsl(${hue} 70% 52%)`,
      s: SKIN,
      e: `hsl(${(hue + 180) % 360} 60% 62%)`,
      m: MOUTH,
      o: `hsl(${hue} 55% 42%)`,
      a: '#ffd166',
    },
    backendId,
    placeholder: true,
  };
}

export function speakerName(id: string): string {
  if (id === 'narrator') return NARRATOR_NAME;
  return GAL_SPEAKERS.find(s => s.id === id)?.name ?? id;
}

export function speakerOf(id: string): GalSpeaker | undefined {
  return GAL_SPEAKERS.find(s => s.id === id);
}

/**
 * demo 消息序列（深夜星光列车）
 * 索引：0-5 铺垫 → 6 玩家选项① → 7-9 展开 → 10 玩家选项② → 11-17 收束
 * goto：选项②「交给你们」跳到 14（跳过 11-13）；选项①「无所谓」跳到 10（跳过 7-9）
 */
export const GAL_SEQUENCE: GalMessage[] = [
  { id: 'm0', speakerId: 'narrator', type: 'narrator', text: '—— 深夜 11:58，末班星光列车缓缓驶入 7 号月台 ——' },
  { id: 'm1', speakerId: 'aya', type: 'line', text: '哇——好险！差点就赶不上这班车了！你们怎么都不着急呀？' },
  { id: 'm2', speakerId: 'rin', type: 'line', text: '你每次都掐着点冲进来。再晚三秒，车门就关了。' },
  { id: 'm3', speakerId: 'luna', type: 'line', text: '……今晚的星光很亮。适合说一些，平时不会说的话。' },
  { id: 'm4', speakerId: 'aya', type: 'line', text: '对了对了！听说这班列车的终点站，会随乘客的心愿改变——是真的吗？' },
  { id: 'm5', speakerId: 'luna', type: 'line', text: '列车会听见的哦。愿望也好，谎言也好。' },
  {
    id: 'm6', speakerId: 'player', type: 'choice',
    text: '「终点站会随心愿改变」——你相信吗？',
    choices: [
      { text: '我相信，这班车一定有什么特别的。' },
      { text: '只是都市传说吧。' },
      { text: '我无所谓，能回家就行。', goto: 10 },
    ],
  },
  { id: 'm7', speakerId: 'aya', type: 'line', text: '欸——！？你们看窗外，那片云……像不像一只发光的兔子？' },
  { id: 'm8', speakerId: 'rin', type: 'line', text: '气象现象而已。不过……确实有点好看。' },
  { id: 'm9', speakerId: 'luna', type: 'line', text: '兔子会带路。很久以前的传说里，是这样说的。' },
  {
    id: 'm10', speakerId: 'player', type: 'choice',
    text: '要不要一起去找找传说里的「星光宝藏」？',
    choices: [
      { text: '好！我们一起去找！' },
      { text: '先补个觉再说……' },
      { text: '宝藏什么的，交给你们就好。', goto: 14 },
    ],
  },
  { id: 'm11', speakerId: 'aya', type: 'line', text: '好耶！那我们的目标就是——车尾的星光车厢！' },
  { id: 'm12', speakerId: 'rin', type: 'line', text: '……我负责看时刻表，别让咱们坐过站。' },
  { id: 'm13', speakerId: 'narrator', type: 'narrator', text: '—— 列车穿行在银河之间，三人的身影被星光拉得很长 ——' },
  { id: 'm14', speakerId: 'luna', type: 'line', text: '到了。这里……就是传说中的星光车厢。' },
  { id: 'm15', speakerId: 'aya', type: 'line', text: '呜哇……车厢里全是星星！像做梦一样！' },
  { id: 'm16', speakerId: 'rin', type: 'line', text: '……看来今晚的终点站，真的会不一样。' },
  { id: 'm17', speakerId: 'narrator', type: 'narrator', text: '—— 深夜的星光列车，载着三个愿望，驶向黎明 ——' },
];
