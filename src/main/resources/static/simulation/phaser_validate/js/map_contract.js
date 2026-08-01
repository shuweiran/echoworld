/**
 * map_contract.js — 地图 JSON 契约样例（契约草案 v1 的数据实现）
 *
 * 用途：
 *   - 浏览器端：window.MAP_SAMPLES 供各 demo scene / 契约页使用
 *   - Node 端：module.exports 供 tools/export_assets.js 导出 maps/*.json 规范副本
 *
 * 契约草案文档：docs/地图JSON契约-v1.md（字段表/宽容解析规则/版本纪律对齐 D-014）
 * 阶段 2：LLM 生成地图输出同结构 JSON，经宽容解析归一后交给 Phaser 渲染。
 *
 * manor 样例说明（剧本杀·老宅 20×14）：
 *   瓦片 id：1=木地板 2=墙 3=草地(花园室内) 4=地毯 5=石板走廊
 *   碰撞层：1=墙（不可通行），0=可通行（草地在庄园内可走）
 *   房间：客厅(左上)/书房(右上)/卧室(左下)/花园(右下)，中央竖走廊连通
 *   zones 为搜证热点（阶段 2 与剧本杀 clues[].location 绑定）
 */
(function (global) {
  'use strict';

  // 行串定义（每个字符=一个瓦片 id，行序自上而下）
  var MANOR_ROWS = [
    '22222222222222222222', // r0
    '22222222222222222222', // r1
    '22111111255211111122', // r2 客厅(左) | 走廊 | 书房(右)
    '22111111555511111122', // r3 客厅门 c8 / 书房门 c11
    '22111141255211111122', // r4 客厅地毯 c6
    '22111111255211111122', // r5
    '22222222255222222222', // r6 分隔墙带（走廊贯通）
    '22222222255222222222', // r7
    '22111111255233333322', // r8 卧室(左) | 花园(右)
    '22111111555533333322', // r9 卧室门 c8 / 花园门 c11
    '22111111255233343322', // r10 花园石板 c15
    '22111111255233333322', // r11
    '22222222222222222222', // r12
    '22222222222222222222', // r13
  ];

  function rowsToGrid(rows) {
    return rows.map(function (r) {
      return r.split('').map(Number);
    });
  }

  // 由 ground 网格派生碰撞层：瓦片 id 2（墙）不可通行，其余可通行。
  // （契约允许 LLM/生成器显式给出碰撞层；此处用派生规则保持样例一致，BSP 生成器则显式输出）
  function deriveCollision(ground) {
    return ground.map(function (row) {
      return row.map(function (t) { return t === 2 ? 1 : 0; });
    });
  }

  var manor = {
    map_version: 1,
    map_id: 'manor_01',
    name: '老宅',
    theme: '剧本杀·民国老宅（契约样例）',
    tile_size: 32,
    width: 20,
    height: 14,
    tileset: { src: 'assets/tiles.png', first_gid: 1, tile_count: 5 },
    layers: {
      ground: rowsToGrid(MANOR_ROWS),
      collision: null // 下方填充
    },
    rooms: [
      { id: 'living_room', name: '客厅', x: 2, y: 2, w: 6, h: 4, tags: ['searchable'] },
      { id: 'study', name: '书房', x: 12, y: 2, w: 6, h: 4, tags: ['searchable'] },
      { id: 'bedroom', name: '卧室', x: 2, y: 8, w: 6, h: 4, tags: ['searchable'] },
      { id: 'garden', name: '花园', x: 12, y: 8, w: 6, h: 4, tags: ['searchable'] },
    ],
    corridors: [
      {
        id: 'cor_main',
        from: 'living_room',
        to: 'study',
        // 蛇形路径：沿 c9 下行 → 横跨 c10 → 沿 c10 上行（连续四邻接）
        points: [[9, 2], [9, 3], [9, 4], [9, 5], [9, 6], [9, 7], [9, 8], [9, 9], [9, 10], [9, 11],
                 [10, 11], [10, 10], [10, 9], [10, 8], [10, 7], [10, 6], [10, 5], [10, 4], [10, 3], [10, 2]],
      },
    ],
    zones: [
      { id: 'z_living_table', name: '客厅八仙桌', type: 'search', x: 4, y: 3, radius: 1, clue_location: '客厅',
        prompt: '八仙桌上摊着一封没有署名的信，字迹潦草……' },
      { id: 'z_study_bookshelf', name: '书房书架', type: 'search', x: 13, y: 3, radius: 1, clue_location: '书房',
        prompt: '书架第三层的《福尔摩斯探案集》里夹着一张泛黄的照片。' },
      { id: 'z_bedroom_bed', name: '卧室床底', type: 'search', x: 3, y: 9, radius: 1, clue_location: '卧室',
        prompt: '床底露出一角日记本，封皮写着“勿看”。' },
      { id: 'z_garden_pavilion', name: '花园凉亭', type: 'search', x: 14, y: 9, radius: 1, clue_location: '花园',
        prompt: '凉亭石桌上放着一把沾着泥土的铜钥匙。' },
    ],
    spawn_points: [
      { id: 'sp_player', type: 'player', x: 9, y: 4 },
      { id: 'sp_npc_1', type: 'npc', x: 4, y: 3 },
      { id: 'sp_npc_2', type: 'npc', x: 13, y: 3 },
      { id: 'sp_npc_3', type: 'npc', x: 3, y: 9 },
    ],
  };
  manor.layers.collision = deriveCollision(manor.layers.ground);

  var MAP_SAMPLES = { manor: manor };

  global.MAP_SAMPLES = MAP_SAMPLES;
  if (typeof module !== 'undefined' && module.exports) { module.exports = MAP_SAMPLES; }
})(typeof window !== 'undefined' ? window : this);
