/**
 * ZoneScene — 验证点 3：Zone 热点（可交互区域，模拟剧本杀搜证点）
 *   - 热点渲染：半透明区域 + 脉冲高亮 + 名称标签
 *   - 靠近触发：玩家进入 radius 内 → 提示条回调（onEnter）
 *   - 点击触发：pointerdown 命中热点 → 搜证回调（onInteract），弹出线索文本
 *   - E 键：对当前高亮热点执行搜证（等价点击）
 * 阶段 2 将把 zones[].clue_location 与剧本杀 ScriptSchemaV1 clues[].location 绑定。
 */
(function (global) {
  'use strict';
  function ZoneScene() { Phaser.Scene.call(this, { key: 'ZoneScene' }); }
  ZoneScene.prototype = Object.create(Phaser.Scene.prototype);
  ZoneScene.prototype.constructor = ZoneScene;

  ZoneScene.prototype.preload = function () {
    this.load.image('tiles', PhaserDemo.assetUrl('tiles.png'));
    this.load.spritesheet('player', PhaserDemo.assetUrl('player.png'), { frameWidth: 32, frameHeight: 32 });
  };

  ZoneScene.prototype.create = function () {
    var self = this;
    var mapJson = MAP_SAMPLES.manor;
    var b = PhaserDemo.buildMap(this, mapJson, 800, 560);
    this.b = b;
    this.mapJson = mapJson;

    var player = PhaserDemo.createPlayer(this, b, mapJson.spawn_points[0], 'player', { controls: 'wasd', animMode: 'sheet', label: '玩家(WASD)' });
    this.player = player;

    // 热点渲染
    this.zoneGraphics = this.add.graphics().setDepth(8);
    this.zoneLabels = [];
    this.zoneStates = {}; // id -> {searched: bool}
    mapJson.zones.forEach(function (z) {
      self.zoneStates[z.id] = { searched: false };
      var t = self.add.text(b.ox + z.x * b.ts + b.ts / 2, b.oy + z.y * b.ts + b.ts + 10, z.name, { fontFamily: 'monospace', fontSize: '11px', color: '#ffe08a', backgroundColor: '#00000088', padding: { x: 3, y: 1 } }).setOrigin(0.5).setDepth(9);
      self.zoneLabels.push(t);
    });

    // 提示条（靠近时更新）
    this.hint = this.add.text(400, 528, '', { fontFamily: 'monospace', fontSize: '14px', color: '#ffe08a', backgroundColor: '#000000bb', padding: { x: 10, y: 6 } }).setOrigin(0.5, 1).setScrollFactor(0).setDepth(20);

    // 已搜证列表（DOM 回调）
    this.searched = [];
    if (global.updateDemoPanel) global.updateDemoPanel('zones', {
      '热点数': mapJson.zones.length + ' 个（type=search）',
      '互动方式': '靠近 → 提示条（onEnter 回调）；点击热点 或 按 E → 搜证回调（onInteract）',
      '线索绑定': 'zones[].clue_location ↔ 剧本杀 clues[].location（阶段 2 接线）',
      '已搜证': '（无）',
    });

    // 靠近检测：每帧计算
    this.nearZone = null;
    // 点击
    this.input.on('pointerdown', function (pointer) {
      var z = self.hitTest(pointer.x, pointer.y);
      if (z) self.interact(z);
    });
    // E 键
    this.eKey = this.input.keyboard.addKey(Phaser.Input.Keyboard.KeyCodes.E);
  };

  ZoneScene.prototype.hitTest = function (px, py) {
    var b = this.b;
    for (var i = 0; i < this.mapJson.zones.length; i++) {
      var z = this.mapJson.zones[i];
      var r = (z.radius || 1) * b.ts;
      var cx = b.ox + z.x * b.ts + b.ts / 2;
      var cy = b.oy + z.y * b.ts + b.ts / 2;
      if (px >= cx - r && px <= cx + r && py >= cy - r && py <= cy + r) return z;
    }
    return null;
  };

  ZoneScene.prototype.interact = function (z) {
    // 搜证回调：标记已搜证 + 弹线索文本（模拟剧本杀 search 的返回）
    var st = this.zoneStates[z.id];
    if (st.searched) {
      if (global.showClue) global.showClue(z, '（该处已搜证过）');
      return;
    }
    st.searched = true;
    this.searched.push(z);
    if (global.showClue) global.showClue(z, z.prompt);
    if (global.updateDemoPanel) global.updateDemoPanel('zones', {
      '热点数': this.mapJson.zones.length + ' 个（type=search）',
      '互动方式': '靠近 → 提示条（onEnter 回调）；点击热点 或 按 E → 搜证回调（onInteract）',
      '线索绑定': 'zones[].clue_location ↔ 剧本杀 clues[].location（阶段 2 接线）',
      '已搜证': this.searched.map(function (z2) { return z2.name; }).join('、') || '（无）',
    });
  };

  ZoneScene.prototype.update = function () {
    this.player.update();
    var b = this.b;
    var px = this.player.sprite.x, py = this.player.sprite.y;

    // 脉冲高亮
    var pulse = 0.35 + 0.25 * Math.sin(this.time.now / 250);
    this.zoneGraphics.clear();
    var near = null;
    var self = this;
    this.mapJson.zones.forEach(function (z) {
      var st = self.zoneStates[z.id];
      var cx = b.ox + z.x * b.ts + b.ts / 2;
      var cy = b.oy + z.y * b.ts + b.ts / 2;
      var r = (z.radius || 1) * b.ts;
      var dist = Math.hypot(px - cx, py - cy);
      if (dist <= r + 4) near = z; // 靠近（含小缓冲）
      if (st.searched) {
        self.zoneGraphics.fillStyle(0x3ddc84, 0.28).fillRoundedRect(cx - r, cy - r, r * 2, r * 2, 6);
      } else {
        self.zoneGraphics.fillStyle(0xffd166, near === z ? 0.5 : 0.3).fillRoundedRect(cx - r, cy - r, r * 2, r * 2, 6);
      }
      self.zoneGraphics.lineStyle(2, near === z ? 0xffffff : 0xffd166, 0.9).strokeRoundedRect(cx - r, cy - r, r * 2, r * 2, 6);
    });

    // 靠近提示（onEnter 回调语义）
    if (near) {
      this.hint.setText('🔍 靠近搜证点：' + near.name + ' —— 点击该区域 或 按 E 搜证').setVisible(true);
    } else {
      this.hint.setText('靠近金色区域触发提示，点击或按 E 搜证（' + this.searched.length + '/' + this.mapJson.zones.length + '）').setVisible(true);
    }
    this.nearZone = near;

    if (Phaser.Input.Keyboard.JustDown(this.eKey) && this.nearZone) {
      this.interact(this.nearZone);
    }
  };

  global.ZoneScene = ZoneScene;
})(typeof window !== 'undefined' ? window : this);
