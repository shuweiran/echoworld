/**
 * BspScene — 验证点 2：BSP 分区（房间/走廊程序化生成）
 * 无 LLM 时的备选生成器/校验器演示：
 *   - BSP 递归二分 → 房间 + L 形走廊连通（固定 seed 可复现）
 *   - 输出契约 v1 JSON（与 LLM 生成路径同构）→ 复用 buildMap 渲染
 *   - 侧栏显示生成 JSON + 校验器结果（validateMap）
 *   - 「重新生成」按钮用随机 seed 再跑一遍（验证器兜底）
 */
(function (global) {
  'use strict';
  function BspScene() { Phaser.Scene.call(this, { key: 'BspScene' }); }
  BspScene.prototype = Object.create(Phaser.Scene.prototype);
  BspScene.prototype.constructor = BspScene;

  BspScene.prototype.preload = function () {
    this.load.image('tiles', PhaserDemo.assetUrl('tiles.png'));
    this.load.spritesheet('player', PhaserDemo.assetUrl('player.png'), { frameWidth: 32, frameHeight: 32 });
  };

  BspScene.prototype.generate = function (seed) {
    var self = this;
    // 清理旧地图（重建时销毁已有层/实体）
    if (this.cleanup) this.cleanup();
    var mapJson = Bsp.generateBspMap({ width: 24, height: 16, seed: seed });
    var b = PhaserDemo.buildMap(this, mapJson, 800, 560);
    this.mapJson = mapJson;
    this.b = b;

    var player = PhaserDemo.createPlayer(this, b, mapJson.spawn_points[0], 'player', { controls: 'wasd', animMode: 'sheet', label: '玩家(WASD)' });
    this.player = player;
    this.wanderers = [];
    mapJson.spawn_points.slice(1).forEach(function (sp) {
      var w = PhaserDemo.createWanderer(self, b, sp, 'player', { animMode: 'sheet' });
      w.sprite.setTint(0xff6b6b);
      self.wanderers.push(w);
    });

    // 房间/走廊标注
    this.roomLabels = [];
    mapJson.rooms.forEach(function (r) {
      var t = self.add.text(b.ox + r.x * b.ts + (r.w * b.ts) / 2, b.oy + r.y * b.ts - 6, r.name, { fontFamily: 'monospace', fontSize: '13px', color: '#d8f0ff', backgroundColor: '#00000066', padding: { x: 3, y: 1 } }).setOrigin(0.5).setDepth(9);
      self.roomLabels.push(t);
    });

    this.hud = this.add.text(12, 10,
      'BSP 分区生成（seed=' + seed + '，' + mapJson.width + '×' + mapJson.height + '）\nWASD 移动 · 房间 ' + mapJson.rooms.length + ' · 走廊 ' + mapJson.corridors.length + ' · 热点 ' + mapJson.zones.length,
      { fontFamily: 'monospace', fontSize: '14px', color: '#ffffff', backgroundColor: '#00000088', padding: { x: 8, y: 6 } }).setScrollFactor(0).setDepth(20);

    // 侧栏：JSON 摘要 + 校验结果
    var v = Bsp.validateMap(mapJson);
    var summary = {
      '生成器': 'BSP 递归二分（seed ' + (seed === undefined ? 20260801 : seed) + '，可复现）',
      '尺寸': mapJson.width + '×' + mapJson.height + '，tile ' + mapJson.tile_size + 'px',
      '房间': mapJson.rooms.length + ' 个（L 形走廊全连通，BFS 验证可达）',
      '走廊段': mapJson.corridors.length,
      '热点': mapJson.zones.length + '（搜证点，自动生成）',
      '契约版本': 'map_version ' + mapJson.map_version,
      '校验器': (v.ok ? '✅ 通过' : '❌ 失败 ' + v.errors.length + ' 项') + ' · 警告 ' + v.warnings.length + ' 项',
    };
    if (global.updateDemoPanel) global.updateDemoPanel('bsp', summary, mapJson);

    // 清理函数（下次 regenerate 时销毁旧实体，避免重复叠加）
    this.cleanup = function () {
      if (self.player) { self.player.sprite.destroy(true); }
      self.wanderers.forEach(function (w) { w.sprite.destroy(true); });
      (self.roomLabels || []).forEach(function (t) { t.destroy(); });
      if (self.hud) self.hud.destroy();
      if (self.b) {
        self.b.groundLayer.destroy();
        self.b.collLayer.destroy();
        self.b.map.destroy();
      }
      self.player = null; self.wanderers = []; self.roomLabels = [];
    };
  };

  BspScene.prototype.create = function () {
    this.seed = 20260801;
    this.generate(this.seed);
  };

  BspScene.prototype.update = function () {
    if (this.player) this.player.update();
    this.wanderers.forEach(function (w) { w.update(); });
  };

  global.BspScene = BspScene;
})(typeof window !== 'undefined' ? window : this);
