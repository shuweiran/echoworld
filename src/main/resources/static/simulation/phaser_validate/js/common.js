/**
 * common.js — demo 共享渲染管线（Phaser 3.90 消费地图 JSON 契约）
 *
 * 统一入口：任何契约 v1 地图 JSON → buildMap() 建 Tilemap（地面层渲染 + 碰撞层隐藏物理）
 * → createPlayer / createWanderer 复用同一套移动/碰撞/动画逻辑。
 * 该管线即阶段 1 ScenePage 渲染层替换的原型（数据流：契约 JSON → 渲染，后端零改动）。
 */
(function (global) {
  'use strict';

  var IS_FILE = typeof location !== 'undefined' && location.protocol === 'file:';

  // file:// 下浏览器禁止本地 XHR，Phaser 资源加载改用内嵌 base64（js/assets_embedded.js）；
  // http 下走真实文件管线（assets/ 目录）。
  function assetUrl(name) {
    if (IS_FILE && global.PHASER_DEMO_EMBEDDED && global.PHASER_DEMO_EMBEDDED[name]) {
      return global.PHASER_DEMO_EMBEDDED[name];
    }
    return 'assets/' + name;
  }

  /**
   * 契约 JSON → Phaser Tilemap。
   * 地面层(layers.ground)渲染可见瓦片；碰撞层(layers.collision)建隐藏层只做物理，
   * 这样 LLM/生成器可以独立给出碰撞语义（不依赖瓦片 id 推断）。
   */
  function buildMap(scene, mapJson, canvasW, canvasH) {
    var ts = mapJson.tile_size || 32;
    var ox = Math.floor((canvasW - mapJson.width * ts) / 2);
    var oy = Math.floor((canvasH - mapJson.height * ts) / 2);
    var map = scene.make.tilemap({ data: mapJson.layers.ground, tileWidth: ts, tileHeight: ts });
    var tileset = map.addTilesetImage('tiles', 'tiles', ts, ts, 0, 0);
    var groundLayer = map.createLayer(0, tileset, ox, oy);
    var collLayer = map.createBlankLayer('collision', tileset, ox, oy);
    var coll = mapJson.layers.collision;
    for (var y = 0; y < coll.length; y++) {
      for (var x = 0; x < coll[y].length; x++) {
        if (coll[y][x]) collLayer.putTileAt(1, x, y);
      }
    }
    collLayer.setVisible(false);
    collLayer.setCollisionByExclusion([-1]);
    scene.physics.world.setBounds(ox, oy, mapJson.width * ts, mapJson.height * ts);
    return { map: map, tileset: tileset, groundLayer: groundLayer, collLayer: collLayer, ox: ox, oy: oy, ts: ts };
  }

  function tileToWorld(tx, ty, b) {
    return { x: b.ox + tx * b.ts + b.ts / 2, y: b.oy + ty * b.ts + b.ts / 2 };
  }

  /**
   * 方向动画表：aseprite 模式（load.aseprite + anims.createFromAseprite，
   * 动画名 walk-down/up/left/right，idle 帧为数字串 "0"/"4"/"8"/"12"）与
   * spritesheet 模式（generateFrameNumbers，动画名 <key>-down 等）统一为同一结构。
   */
  function buildDirAnims(scene, key, animMode) {
    if (animMode === 'aseprite') {
      scene.anims.createFromAseprite(key, ['walk-down', 'walk-up', 'walk-left', 'walk-right']);
      return { down: 'walk-down', up: 'walk-up', left: 'walk-left', right: 'walk-right', idle: ['0', '4', '8', '12'] };
    }
    scene.anims.create({ key: key + '-down', frames: scene.anims.generateFrameNumbers(key, { start: 0, end: 3 }), frameRate: 8, repeat: -1 });
    scene.anims.create({ key: key + '-up', frames: scene.anims.generateFrameNumbers(key, { start: 4, end: 7 }), frameRate: 8, repeat: -1 });
    scene.anims.create({ key: key + '-left', frames: scene.anims.generateFrameNumbers(key, { start: 8, end: 11 }), frameRate: 8, repeat: -1 });
    scene.anims.create({ key: key + '-right', frames: scene.anims.generateFrameNumbers(key, { start: 12, end: 15 }), frameRate: 8, repeat: -1 });
    return { down: key + '-down', up: key + '-up', left: key + '-left', right: key + '-right', idle: [0, 4, 8, 12] };
  }

  function makeControls(scene, kind) {
    var k = scene.input.keyboard;
    if (kind === 'arrows') {
      return { left: k.addKey(Phaser.Input.Keyboard.KeyCodes.LEFT), right: k.addKey(Phaser.Input.Keyboard.KeyCodes.RIGHT), up: k.addKey(Phaser.Input.Keyboard.KeyCodes.UP), down: k.addKey(Phaser.Input.Keyboard.KeyCodes.DOWN) };
    }
    return { left: k.addKey(Phaser.Input.Keyboard.KeyCodes.A), right: k.addKey(Phaser.Input.Keyboard.KeyCodes.D), up: k.addKey(Phaser.Input.Keyboard.KeyCodes.W), down: k.addKey(Phaser.Input.Keyboard.KeyCodes.S) };
  }

  /** 移动精灵 + 方向动画（含静止回 idle 帧）。返回当前朝向信息。 */
  function moveSprite(ctl, sprite, anims, speed) {
    var vx = 0, vy = 0;
    if (ctl.left.isDown) vx = -1; else if (ctl.right.isDown) vx = 1;
    if (ctl.up.isDown) vy = -1; else if (ctl.down.isDown) vy = 1;
    if (vx !== 0 && vy !== 0) { vx *= 0.7071; vy *= 0.7071; }
    sprite.setVelocity(vx * speed, vy * speed);
    if (vx < 0) { sprite.anims.play(anims.left, true); sprite.lastDir = 2; }
    else if (vx > 0) { sprite.anims.play(anims.right, true); sprite.lastDir = 3; }
    else if (vy < 0) { sprite.anims.play(anims.up, true); sprite.lastDir = 1; }
    else if (vy > 0) { sprite.anims.play(anims.down, true); sprite.lastDir = 0; }
    else {
      sprite.anims.stop();
      if (sprite.lastDir !== undefined) sprite.setFrame(anims.idle[sprite.lastDir]);
    }
    return { vx: vx, vy: vy };
  }

  /**
   * 玩家角色：Arcade 精灵 + 瓦片碰撞 + 键盘移动。
   * opts: { controls:'wasd'|'arrows', animMode:'aseprite'|'sheet', label:'...' }
   */
  function createPlayer(scene, b, spawn, key, opts) {
    opts = opts || {};
    var w = (b && spawn) ? tileToWorld(spawn.x, spawn.y, b) : (opts.pos || { x: 0, y: 0 });
    var sprite = scene.physics.add.sprite(w.x, w.y, key);
    sprite.setCollideWorldBounds(true);
    sprite.body.setSize(14, 18).setOffset(9, 10);
    if (b) scene.physics.add.collider(sprite, b.collLayer);
    var anims = buildDirAnims(scene, key, opts.animMode || 'sheet');
    var ctl = makeControls(scene, opts.controls || 'wasd');
    if (opts.label) {
      var label = scene.add.text(w.x, w.y - 24, opts.label, { fontFamily: 'monospace', fontSize: '12px', color: '#ffe08a' }).setOrigin(0.5).setDepth(10);
      sprite.label = label;
    }
    return { sprite: sprite, ctl: ctl, anims: anims, label: label, update: function () { return moveSprite(ctl, sprite, anims, opts.speed || 150); } };
  }

  /** AI 漫游者：随机方向 + 撞墙换向，瓦片碰撞由 Arcade 承担（验证 AI 不可穿墙）。 */
  function createWanderer(scene, b, spawn, key, opts) {
    opts = opts || {};
    var w = tileToWorld(spawn.x, spawn.y, b);
    var sprite = scene.physics.add.sprite(w.x, w.y, key);
    sprite.setCollideWorldBounds(true);
    sprite.body.setSize(14, 18).setOffset(9, 10);
    scene.physics.add.collider(sprite, b.collLayer);
    var anims = buildDirAnims(scene, key, opts.animMode || 'sheet');
    var dirs = [[-1, 0, 2], [1, 0, 3], [0, -1, 1], [0, 1, 0]];
    var speed = opts.speed || 80;
    function pickDir() {
      var d = dirs[Math.floor(Math.random() * dirs.length)];
      sprite.setVelocity(d[0] * speed, d[1] * speed);
      sprite.anims.play(anims[['left', 'right', 'up', 'down'][d[2]]], true);
      sprite.lastDir = d[2];
    }
    pickDir();
    var timer = scene.time.addEvent({
      delay: opts.delay || 1800, loop: true,
      callback: function () { pickDir(); },
    });
    sprite.body.onWorldBounds = true;
    return {
      sprite: sprite, pickDir: pickDir,
      update: function () {
        // 撞墙（含瓦片碰撞）立即换向
        var bd = sprite.body.blocked;
        if (bd.left || bd.right || bd.up || bd.down) pickDir();
      },
    };
  }

  global.PhaserDemo = {
    assetUrl: assetUrl,
    buildMap: buildMap,
    tileToWorld: tileToWorld,
    buildDirAnims: buildDirAnims,
    makeControls: makeControls,
    moveSprite: moveSprite,
    createPlayer: createPlayer,
    createWanderer: createWanderer,
  };
  if (typeof module !== 'undefined' && module.exports) { module.exports = global.PhaserDemo; }
})(typeof window !== 'undefined' ? window : this);
