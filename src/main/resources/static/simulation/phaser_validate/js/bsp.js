/**
 * bsp.js — BSP 分区生成器 + 地图 JSON 契约校验器（纯逻辑，无 Phaser 依赖）
 *
 * 角色定位（阶段 0）：
 *   - BSP 生成器：无 LLM 时程序化生成房间/走廊布局的「备选生成器」
 *   - 输出与 LLM 生成路径同构（对齐 docs/地图JSON契约-v1.md v1 契约），
 *     同一份渲染管线（common.js buildMap）直接消费
 *   - 校验器：对任意契约 JSON（LLM 或生成器产出）做结构/越界/可通行性检查，
 *     作为「LLM 输出的校验器」防线（阶段 2 复用）
 *
 * 确定性：支持 seed（mulberry32），同一 seed 输出同一地图（demo 默认固定 seed）。
 */
(function (global) {
  'use strict';

  // ---------- 可复现 RNG ----------
  function makeRng(seed) {
    var a = seed >>> 0;
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      var t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  // ---------- BSP 分区 ----------
  /**
   * 生成一张契约 v1 地图 JSON。
   * opts: { width, height, tile_size, seed, min_leaf, max_leaf, min_room, zones_count }
   */
  function generateBspMap(opts) {
    opts = opts || {};
    var W = opts.width || 24;
    var H = opts.height || 16;
    var tileSize = opts.tile_size || 32;
    var minLeaf = opts.min_leaf || 7;
    var minRoom = opts.min_room || 3;
    var zonesCount = opts.zones_count === undefined ? 3 : opts.zones_count;
    var rng = makeRng(opts.seed === undefined ? 20260801 : opts.seed);

    var TILE_FLOOR = 1, TILE_WALL = 2, TILE_GRASS = 3, TILE_CARPET = 4, TILE_STONE = 5;

    // 1) 递归二分叶子
    var root = { x: 0, y: 0, w: W, h: H };
    var leaves = [];

    function split(node, depth) {
      var canH = node.h >= minLeaf * 2;
      var canV = node.w >= minLeaf * 2;
      if (!canH && !canV) { leaves.push(node); return; }
      var horizontal;
      if (canH && canV) {
        // 偏好切长边，加随机扰动（70% 切长边 / 30% 随机）
        horizontal = (node.h >= node.w) ? (rng() < 0.7) : (rng() < 0.3);
      } else {
        horizontal = canH;
      }
      var a, b;
      if (horizontal) {
        var splitY = minLeaf + Math.floor(rng() * (node.h - minLeaf * 2 + 1));
        a = { x: node.x, y: node.y, w: node.w, h: splitY };
        b = { x: node.x, y: node.y + splitY, w: node.w, h: node.h - splitY };
      } else {
        var splitX = minLeaf + Math.floor(rng() * (node.w - minLeaf * 2 + 1));
        a = { x: node.x, y: node.y, w: splitX, h: node.h };
        b = { x: node.x + splitX, y: node.y, w: node.w - splitX, h: node.h };
      }
      node.a = a; node.b = b;
      split(a, depth + 1);
      split(b, depth + 1);
    }
    split(root, 0);

    // 2) 每叶子内生成房间（留 1 格墙边距）
    leaves.forEach(function (leaf, i) {
      var rw = minRoom + Math.floor(rng() * Math.max(1, leaf.w - minRoom - 2));
      var rh = minRoom + Math.floor(rng() * Math.max(1, leaf.h - minRoom - 2));
      rw = Math.min(rw, leaf.w - 2);
      rh = Math.min(rh, leaf.h - 2);
      var rx = leaf.x + 1 + Math.floor(rng() * Math.max(1, leaf.w - rw - 1));
      var ry = leaf.y + 1 + Math.floor(rng() * Math.max(1, leaf.h - rh - 1));
      leaf.room = {
        id: 'room_' + i,
        name: '房间 ' + String.fromCharCode(65 + (i % 26)),
        x: rx, y: ry, w: rw, h: rh,
        tags: ['searchable'],
      };
    });

    // 3) 兄弟子树房间间开 L 形走廊（连通 = 二叉树，全连通）
    var corridors = [];
    function connect(node) {
      if (node.a) {
        var ra = connect(node.a);
        var rb = connect(node.b);
        var c1 = { x: ra.x + Math.floor(ra.w / 2), y: ra.y + Math.floor(ra.h / 2) };
        var c2 = { x: rb.x + Math.floor(rb.w / 2), y: rb.y + Math.floor(rb.h / 2) };
        var pts = [];
        var first = rng() < 0.5;
        if (first) {
          // 先水平后垂直（方向感知，转角处连续相邻）
          for (var x = c1.x; x !== c2.x; x += (c2.x > c1.x ? 1 : -1)) pts.push([x, c1.y]);
          pts.push([c2.x, c1.y]);
          for (var y = c1.y; y !== c2.y; y += (c2.y > c1.y ? 1 : -1)) pts.push([c2.x, y]);
          pts.push([c2.x, c2.y]);
        } else {
          // 先垂直后水平
          for (var y2 = c1.y; y2 !== c2.y; y2 += (c2.y > c1.y ? 1 : -1)) pts.push([c1.x, y2]);
          pts.push([c1.x, c2.y]);
          for (var x2 = c1.x; x2 !== c2.x; x2 += (c2.x > c1.x ? 1 : -1)) pts.push([x2, c2.y]);
          pts.push([c2.x, c2.y]);
        }
        // 去重保序
        var seen = {};
        var uniq = [];
        pts.forEach(function (p) {
          var k = p[0] + ',' + p[1];
          if (!seen[k]) { seen[k] = 1; uniq.push(p); }
        });
        corridors.push({ id: 'cor_' + corridors.length, from: ra.id, to: rb.id, points: uniq });
        return rng() < 0.5 ? ra : rb;
      }
      return node.room;
    }
    connect(root);

    // 4) 落格：外部=草地(3)，房间=地板(1)/地毯(4)，走廊=石板(5)
    var ground = [];
    for (var gy = 0; gy < H; gy++) {
      var row = [];
      for (var gx = 0; gx < W; gx++) row.push(TILE_GRASS);
      ground.push(row);
    }
    leaves.forEach(function (leaf) {
      var r = leaf.room;
      var carpet = rng() < 0.3;
      for (var y = r.y; y < r.y + r.h; y++)
        for (var x = r.x; x < r.x + r.w; x++)
          ground[y][x] = carpet ? TILE_CARPET : TILE_FLOOR;
    });
    corridors.forEach(function (cor) {
      cor.points.forEach(function (p) { ground[p[1]][p[0]] = TILE_STONE; });
    });

    // 碰撞层：草地(外部)不可通行，其余可通行
    var collision = ground.map(function (row) {
      return row.map(function (t) { return t === TILE_GRASS ? 1 : 0; });
    });

    // 5) zones / spawn_points
    var roomList = leaves.map(function (l) { return l.room; });
    var zones = [];
    var count = Math.min(zonesCount, roomList.length);
    for (var z = 0; z < count; z++) {
      var room = roomList[z];
      zones.push({
        id: 'z_' + room.id,
        name: room.name + ' 线索点',
        type: 'search',
        x: room.x + Math.floor(room.w / 2),
        y: room.y + Math.floor(room.h / 2),
        radius: 1,
        clue_location: room.name,
        prompt: '（BSP 自动生成）这里似乎藏着什么线索……阶段 2 将绑定剧本杀 clues[].location。',
      });
    }
    var spawns = [
      { id: 'sp_player', type: 'player', x: roomList[0].x + 1, y: roomList[0].y + 1 },
    ];
    for (var n = 1; n < Math.min(3, roomList.length); n++) {
      spawns.push({ id: 'sp_npc_' + n, type: 'npc', x: roomList[n].x + 1, y: roomList[n].y + 1 });
    }

    return {
      map_version: 1,
      map_id: 'bsp_seed_' + (opts.seed === undefined ? 20260801 : opts.seed),
      name: 'BSP 生成地图',
      theme: 'BSP 分区生成（无 LLM 备选生成器，契约与 LLM 输出同构）',
      tile_size: tileSize,
      width: W,
      height: H,
      tileset: { src: 'assets/tiles.png', first_gid: 1, tile_count: 5 },
      generator: {
        kind: 'bsp',
        seed: opts.seed === undefined ? 20260801 : opts.seed,
        leaf_count: leaves.length,
        note: 'demo 验证用；阶段 2 LLM 生成路径输出同结构 JSON',
      },
      layers: { ground: ground, collision: collision },
      rooms: roomList,
      corridors: corridors,
      zones: zones,
      spawn_points: spawns,
    };
  }

  // ---------- 契约校验器 ----------
  var DEFAULT_TILE_COUNT = 5;
  /**
   * validateMap(map) -> { ok, errors[], warnings[] }
   * 检查：版本号/尺寸/层结构/瓦片 id 范围/碰撞值/房间越界与重叠/热点越界与可通行/出生点可通行/走廊点连通
   */
  function validateMap(map) {
    var errors = [];
    var warnings = [];
    var has = function (o, k) { return o !== null && typeof o === 'object' && k in o; };

    if (!map || typeof map !== 'object') { return { ok: false, errors: ['地图 JSON 不是对象'], warnings: [] }; }

    // 版本
    if (!has(map, 'map_version')) {
      warnings.push('缺少 map_version，宽容解析按 1 处理（D-014 版本纪律：JSON 内嵌版本）');
    } else if (typeof map.map_version !== 'number') {
      errors.push('map_version 必须是数字');
    }
    // 尺寸
    var W = map.width, H = map.height;
    if (typeof W !== 'number' || W <= 0 || W !== Math.floor(W)) errors.push('width 必须为正整数');
    if (typeof H !== 'number' || H <= 0 || H !== Math.floor(H)) errors.push('height 必须为正整数');
    if (typeof map.tile_size !== 'number' || map.tile_size <= 0) {
      warnings.push('tile_size 缺失或非法，宽容解析按 32 处理');
    }
    // 层
    var ground = map.layers && map.layers.ground;
    var collision = map.layers && map.layers.collision;
    if (!Array.isArray(ground)) { errors.push('layers.ground 必须为二维数组'); }
    else if (ground.length !== H) { errors.push('layers.ground 行数 ' + ground.length + ' ≠ height ' + H); }
    else {
      for (var y = 0; y < H; y++) {
        if (!Array.isArray(ground[y]) || ground[y].length !== W) {
          errors.push('layers.ground[' + y + '] 列数不为 ' + W); break;
        }
        for (var x = 0; x < W; x++) {
          var t = ground[y][x];
          if (typeof t !== 'number' || t < 0 || t > DEFAULT_TILE_COUNT) {
            warnings.push('ground[' + y + '][' + x + '] 瓦片 id ' + t + ' 超出 tileset 范围 0..' + DEFAULT_TILE_COUNT + '（可能是 LLM 自定义装饰瓦片，渲染按 id 直取；超出部分显示为空白）');
          }
        }
      }
    }
    if (!Array.isArray(collision)) { errors.push('layers.collision 必须为二维数组（1=阻挡 0=通行）'); }
    else if (collision.length !== H) { errors.push('layers.collision 行数 ≠ height'); }
    else {
      for (var cy = 0; cy < H; cy++) {
        if (!Array.isArray(collision[cy]) || collision[cy].length !== W) {
          errors.push('layers.collision[' + cy + '] 列数不为 ' + W); break;
        }
        for (var cx = 0; cx < W; cx++) {
          var v = collision[cy][cx];
          if (v !== 0 && v !== 1) errors.push('collision[' + cy + '][' + cx + '] 值 ' + v + ' 非 0/1');
        }
      }
    }
    var walkable = function (x, y) {
      return x >= 0 && y >= 0 && x < W && y < H &&
        Array.isArray(collision) && Array.isArray(collision[y]) && collision[y][x] === 0;
    };
    var inBounds = function (x, y) { return x >= 0 && y >= 0 && x < W && y < H; };

    // 房间
    if (map.rooms !== undefined) {
      if (!Array.isArray(map.rooms)) errors.push('rooms 必须为数组');
      else {
        map.rooms.forEach(function (r, i) {
          if (!r || typeof r !== 'object') { errors.push('rooms[' + i + '] 不是对象'); return; }
          if (typeof r.x !== 'number' || typeof r.y !== 'number' || typeof r.w !== 'number' || typeof r.h !== 'number') {
            errors.push('rooms[' + i + '] 缺少 x/y/w/h'); return;
          }
          if (r.x < 0 || r.y < 0 || r.x + r.w > W || r.y + r.h > H) errors.push('rooms[' + i + '] (' + r.id + ') 越界');
        });
        // 重叠（仅警告，BSP 允许相邻）
        for (var i = 0; i < map.rooms.length; i++) {
          for (var j = i + 1; j < map.rooms.length; j++) {
            var a = map.rooms[i], b = map.rooms[j];
            if (a && b && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) {
              warnings.push('rooms ' + a.id + ' 与 ' + b.id + ' 重叠');
            }
          }
        }
      }
    }
    // 走廊
    if (map.corridors !== undefined) {
      if (!Array.isArray(map.corridors)) errors.push('corridors 必须为数组');
      else {
        map.corridors.forEach(function (c, i) {
          if (!c || !Array.isArray(c.points)) { errors.push('corridors[' + i + '] 缺少 points 数组'); return; }
          for (var k = 0; k < c.points.length; k++) {
            var p = c.points[k];
            if (!Array.isArray(p) || p.length !== 2 || !inBounds(p[0], p[1])) {
              errors.push('corridors[' + i + '] 点 ' + k + ' 越界'); break;
            }
            if (k > 0) {
              var q = c.points[k - 1];
              var d = Math.abs(p[0] - q[0]) + Math.abs(p[1] - q[1]);
              if (d !== 1) warnings.push('corridors[' + i + '] 点 ' + (k - 1) + '→' + k + ' 非四邻接（d=' + d + '）');
            }
          }
        });
      }
    }
    // 热点：必须落在可通行格（搜证点不能埋在墙里）
    if (map.zones !== undefined) {
      if (!Array.isArray(map.zones)) errors.push('zones 必须为数组');
      else {
        map.zones.forEach(function (z, i) {
          if (!z || typeof z.x !== 'number' || typeof z.y !== 'number') { errors.push('zones[' + i + '] 缺少 x/y'); return; }
          if (!inBounds(z.x, z.y)) errors.push('zones[' + i + '] (' + z.id + ') 越界');
          else if (!walkable(z.x, z.y)) errors.push('zones[' + i + '] (' + z.id + ') 落在不可通行格（碰撞=1）');
          if (typeof z.radius !== 'number' || z.radius < 0) warnings.push('zones[' + i + '] (' + z.id + ') radius 缺失按 1 处理');
        });
      }
    }
    // 出生点：必须可通行
    if (map.spawn_points !== undefined) {
      if (!Array.isArray(map.spawn_points)) errors.push('spawn_points 必须为数组');
      else {
        map.spawn_points.forEach(function (s, i) {
          if (!s || typeof s.x !== 'number' || typeof s.y !== 'number') { errors.push('spawn_points[' + i + '] 缺少 x/y'); return; }
          if (!inBounds(s.x, s.y)) errors.push('spawn_points[' + i + '] (' + s.id + ') 越界');
          else if (!walkable(s.x, s.y)) errors.push('spawn_points[' + i + '] (' + s.id + ') 落在不可通行格');
        });
      }
    }

    return { ok: errors.length === 0, errors: errors, warnings: warnings };
  }

  var api = { makeRng: makeRng, generateBspMap: generateBspMap, validateMap: validateMap, DEFAULT_TILE_COUNT: DEFAULT_TILE_COUNT };
  global.Bsp = api;
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; }
})(typeof window !== 'undefined' ? window : this);
