export type Point = [number, number];

export interface SocialRoom {
  id: string;
  name: string;
  kind: 'cafe' | 'shop' | 'station' | 'home' | 'warehouse' | 'cabin';
  x: number;
  y: number;
  w: number;
  h: number;
  door: Point;
  description: string;
}

export interface InteriorMap {
  id: string;
  name: string;
  width: number;
  height: number;
  furniture: Array<{ x: number; y: number; w: number; h: number; label: string }>;
  exits: Array<{ x: number; y: number; label: string }>;
}

export type SocialTileKind = 'grass' | 'path' | 'water' | 'sand';
export interface SocialMapTile { x: number; y: number; kind: SocialTileKind; object?: 'tree' | 'lamp' | 'flower' | 'bench'; overlay?: 'canopy'; }

/**
 * Demo 专用地图：160×104，不追求契约 JSON 的完整性，重点演示「大地图→房屋→室内」的空间层级。
 * 外部地图用不规则水域/林地轮廓制造绕路；房屋保持小而密，均由道路连通。
 */
export const SOCIAL_DEMO_MAP = {
  id: 'dawn-town-social-demo',
  name: '晨雾镇 · AI 社会实验',
  width: 160,
  height: 104,
  water: [[106, 2], [120, 0], [132, 7], [128, 24], [139, 39], [132, 58], [119, 67], [106, 58], [110, 42], [101, 25]] as Point[],
  forest: [
    [[5, 7], [27, 2], [45, 10], [39, 29], [22, 34], [4, 25]] as Point[],
    [[4, 70], [27, 61], [51, 72], [45, 98], [19, 102], [2, 89]] as Point[],
    [[127, 69], [150, 60], [159, 76], [153, 102], [130, 98], [118, 83]] as Point[],
  ],
  roads: [
    [[8, 52], [36, 51], [62, 52], [90, 51], [122, 52], [151, 50]] as Point[],
    [[63, 8], [64, 30], [63, 52], [62, 76], [64, 97]] as Point[],
    [[120, 12], [118, 35], [120, 52], [119, 73], [123, 95]] as Point[],
    [[19, 77], [48, 75], [74, 77], [98, 75], [124, 77], [148, 75]] as Point[],
  ],
  rooms: [
    { id: 'cafe', name: '河畔咖啡馆', kind: 'cafe', x: 38, y: 39, w: 14, h: 9, door: [45, 48], description: '最容易发生偶遇和闲聊的公共场所。' },
    { id: 'shop', name: '杂货铺', kind: 'shop', x: 73, y: 40, w: 13, h: 8, door: [79, 48], description: '传闻、交易和秘密交换的交汇点。' },
    { id: 'station', name: '南站', kind: 'station', x: 136, y: 40, w: 15, h: 9, door: [143, 49], description: '角色进入和离开小镇的地方。' },
    { id: 'home-a', name: '居民小屋 A', kind: 'home', x: 30, y: 68, w: 12, h: 8, door: [36, 76], description: '安静的私人空间，适合独处和记忆整理。' },
    { id: 'home-b', name: '居民小屋 B', kind: 'home', x: 52, y: 68, w: 12, h: 8, door: [58, 76], description: '常有角色短暂停留或交换物品。' },
    { id: 'warehouse', name: '旧仓库', kind: 'warehouse', x: 88, y: 68, w: 16, h: 9, door: [96, 77], description: '工作目标、秘密和冲突的高风险地点。' },
    { id: 'cabin', name: '林间旧屋', kind: 'cabin', x: 17, y: 40, w: 11, h: 7, door: [22, 47], description: '地图边缘的秘密地点，偶尔有人来访。' },
  ] satisfies SocialRoom[],
  landmarks: [
    { x: 62, y: 52, label: '中央广场', icon: '✦' },
    { x: 104, y: 52, label: '喷泉', icon: '✧' },
    { x: 88, y: 58, label: '公告栏', icon: '▤' },
    { x: 122, y: 58, label: '河岸长椅', icon: '⌁' },
  ],
};

function pointInPolygon(x: number, y: number, polygon: Point[]): boolean {
  let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [xi, yi] = polygon[i]; const [xj, yj] = polygon[j];
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

function nearRoad(x: number, y: number): boolean {
  return Math.abs(y - 52) <= 2 || Math.abs(y - 76) <= 2 || Math.abs(x - 63) <= 2 || Math.abs(x - 120) <= 2;
}

/** 逐格生成三层中的地表/物件瓦片；信息层在 React 中按实时状态覆盖。 */
export const SOCIAL_TILE_LAYERS: { ground: SocialMapTile[]; objects: SocialMapTile[]; overlay: SocialMapTile[] } = (() => {
  const ground: SocialMapTile[] = []; const objects: SocialMapTile[] = []; const overlay: SocialMapTile[] = [];
  for (let y = 0; y < SOCIAL_DEMO_MAP.height; y++) for (let x = 0; x < SOCIAL_DEMO_MAP.width; x++) {
    const water = pointInPolygon(x + .5, y + .5, SOCIAL_DEMO_MAP.water);
    const forest = SOCIAL_DEMO_MAP.forest.some(area => pointInPolygon(x + .5, y + .5, area));
    const kind: SocialTileKind = water ? 'water' : nearRoad(x, y) ? 'path' : forest && (x + y) % 5 === 0 ? 'sand' : 'grass';
    ground.push({ x, y, kind });
    if (forest && (x * 17 + y * 11) % 13 === 0) {
      objects.push({ x, y, kind, object: 'tree' });
      if ((x + y) % 3 === 0) overlay.push({ x, y, kind, overlay: 'canopy' });
    } else if (kind === 'path' && (x * 7 + y * 3) % 41 === 0) objects.push({ x, y, kind, object: 'lamp' });
    else if (kind === 'grass' && (x * 13 + y * 19) % 101 === 0) objects.push({ x, y, kind, object: 'flower' });
  }
  return { ground, objects, overlay };
})();

export const SOCIAL_INTERIORS: Record<string, InteriorMap> = {
  cafe: { id: 'cafe', name: '河畔咖啡馆 · 室内', width: 24, height: 16, furniture: [{ x: 5, y: 5, w: 4, h: 2, label: '双人桌' }, { x: 12, y: 5, w: 4, h: 2, label: '长桌' }, { x: 18, y: 3, w: 2, h: 8, label: '吧台' }], exits: [{ x: 11, y: 15, label: '回到镇上' }] },
  shop: { id: 'shop', name: '杂货铺 · 室内', width: 22, height: 15, furniture: [{ x: 4, y: 4, w: 3, h: 7, label: '货架' }, { x: 10, y: 4, w: 3, h: 7, label: '货架' }, { x: 17, y: 10, w: 3, h: 2, label: '柜台' }], exits: [{ x: 10, y: 14, label: '回到镇上' }] },
  station: { id: 'station', name: '南站 · 候车室', width: 26, height: 16, furniture: [{ x: 4, y: 8, w: 7, h: 2, label: '候车座' }, { x: 15, y: 3, w: 2, h: 8, label: '公告牌' }, { x: 20, y: 5, w: 3, h: 3, label: '售票窗' }], exits: [{ x: 12, y: 15, label: '回到镇上' }] },
  'home-a': { id: 'home-a', name: '居民小屋 A · 室内', width: 18, height: 14, furniture: [{ x: 4, y: 4, w: 4, h: 3, label: '书桌' }, { x: 11, y: 4, w: 4, h: 3, label: '床' }], exits: [{ x: 8, y: 13, label: '回到镇上' }] },
  'home-b': { id: 'home-b', name: '居民小屋 B · 室内', width: 18, height: 14, furniture: [{ x: 4, y: 5, w: 4, h: 2, label: '餐桌' }, { x: 11, y: 4, w: 3, h: 5, label: '床' }], exits: [{ x: 8, y: 13, label: '回到镇上' }] },
  warehouse: { id: 'warehouse', name: '旧仓库 · 室内', width: 28, height: 17, furniture: [{ x: 4, y: 4, w: 5, h: 4, label: '木箱' }, { x: 12, y: 3, w: 5, h: 4, label: '木箱' }, { x: 20, y: 10, w: 4, h: 3, label: '旧机器' }], exits: [{ x: 14, y: 16, label: '回到镇上' }] },
  cabin: { id: 'cabin', name: '林间旧屋 · 室内', width: 20, height: 14, furniture: [{ x: 5, y: 5, w: 4, h: 3, label: '壁炉' }, { x: 12, y: 4, w: 3, h: 5, label: '密柜' }], exits: [{ x: 9, y: 13, label: '回到镇上' }] },
};

export const SOCIAL_DEMO_ROLES = [
  { name: '林默', persona: '安静但观察力很强，喜欢记录别人说过的话', background: '旧仓库临时看守' },
  { name: '苏遥', persona: '外向热情，喜欢主动认识陌生人', background: '咖啡馆常客' },
  { name: '周野', persona: '谨慎多疑，知道一个没有告诉别人的秘密', background: '杂货铺帮工' },
  { name: '唐梨', persona: '好奇心强，容易被公告和传闻吸引', background: '南站旅客' },
  { name: '顾城', persona: '务实寡言，更在意完成自己的任务', background: '仓库搬运工' },
  { name: '白芷', persona: '温和善于调解冲突，但不喜欢被追问', background: '居民小屋 A' },
  { name: '程放', persona: '喜欢开玩笑，社交欲望很强', background: '居民小屋 B' },
  { name: '沈言', persona: '独来独往，偶尔会突然离开谈话', background: '河畔咖啡馆' },
];
