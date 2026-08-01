/**
 * TileCollisionScene — 验证点 1：瓦片渲染 + 碰撞
 * 契约样例地图（老宅 manor_01）→ Tilemap 渲染 + 碰撞层隐藏物理层；
 * 玩家 WASD 移动 + 3 个 AI 漫游，墙体验证（玩家/AI 均不可穿墙）。
 */
(function (global) {
  'use strict';
  function TileCollisionScene() { Phaser.Scene.call(this, { key: 'TileCollisionScene' }); }
  TileCollisionScene.prototype = Object.create(Phaser.Scene.prototype);
  TileCollisionScene.prototype.constructor = TileCollisionScene;

  TileCollisionScene.prototype.preload = function () {
    this.load.image('tiles', PhaserDemo.assetUrl('tiles.png'));
    this.load.spritesheet('player', PhaserDemo.assetUrl('player.png'), { frameWidth: 32, frameHeight: 32 });
  };

  TileCollisionScene.prototype.create = function () {
    var self = this;
    var mapJson = MAP_SAMPLES.manor;
    var b = PhaserDemo.buildMap(this, mapJson, 800, 560);

    // 玩家
    var player = PhaserDemo.createPlayer(this, b, mapJson.spawn_points[0], 'player', { controls: 'wasd', animMode: 'sheet', label: '玩家(WASD)' });
    this.player = player;

    // AI 漫游者（红色换肤）
    this.wanderers = [];
    mapJson.spawn_points.slice(1).forEach(function (sp) {
      var w = PhaserDemo.createWanderer(self, b, sp, 'player', { animMode: 'sheet' });
      w.sprite.setTint(0xff6b6b);
      self.wanderers.push(w);
    });

    // HUD
    this.hud = this.add.text(12, 10,
      '瓦片渲染 + 碰撞（manor_01 老宅 20×14）\nWASD 移动 · AI 红色漫游 · 墙体验证：玩家/AI 均不可穿墙',
      { fontFamily: 'monospace', fontSize: '14px', color: '#ffffff', backgroundColor: '#00000088', padding: { x: 8, y: 6 } }).setScrollFactor(0).setDepth(20);

    // 统计回调给 DOM 面板
    var wallCount = 0;
    mapJson.layers.collision.forEach(function (row) { row.forEach(function (v) { if (v) wallCount++; }); });
    if (global.updateDemoPanel) global.updateDemoPanel('tile', {
      '地图': mapJson.name + '（' + mapJson.width + '×' + mapJson.height + '）',
      '瓦片层': 'ground 行数 ' + mapJson.layers.ground.length + ' × 列数 ' + mapJson.layers.ground[0].length,
      '碰撞层': '障碍格 ' + wallCount + '（隐藏物理层 setCollisionByExclusion）',
      '房间': mapJson.rooms.length + '（客厅/书房/卧室/花园）',
      '实体': '1 玩家 + ' + this.wanderers.length + ' AI（全部不可穿墙）',
    });
  };

  TileCollisionScene.prototype.update = function () {
    this.player.update();
    this.wanderers.forEach(function (w) { w.update(); });
  };

  global.TileCollisionScene = TileCollisionScene;
})(typeof window !== 'undefined' ? window : this);
