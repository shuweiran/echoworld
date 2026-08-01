/**
 * AnimScene — 验证点 4：Aseprite 角色动画（帧动画素材管线）
 * 两条管线对比：
 *   - 角色1（蓝衣）：this.load.aseprite(png + Aseprite JSON) + anims.createFromAseprite
 *   - 角色2（红衣）：this.load.spritesheet + anims.generateFrameNumbers（对照基线）
 * 素材：程序生成占位帧（tools/gen_assets.js，零第三方版权）；真实 Aseprite 素材
 * 替换同构 png+json 即可，管线验证的是加载/动画路径而非美术质量。
 */
(function (global) {
  'use strict';
  function AnimScene() { Phaser.Scene.call(this, { key: 'AnimScene' }); }
  AnimScene.prototype = Object.create(Phaser.Scene.prototype);
  AnimScene.prototype.constructor = AnimScene;

  AnimScene.prototype.preload = function () {
    // 管线 A：Aseprite（png + Aseprite 格式 json，frameTags 定义 walk-down/up/left/right）
    this.load.aseprite('player', PhaserDemo.assetUrl('player.png'), PhaserDemo.assetUrl('player.json'));
    // 管线 B：普通 spritesheet
    this.load.spritesheet('player2', PhaserDemo.assetUrl('player2.png'), { frameWidth: 32, frameHeight: 32 });
  };

  AnimScene.prototype.create = function () {
    var self = this;
    // 简单地板
    this.add.graphics().fillStyle(0x2b2f3a, 1).fillRect(0, 0, 800, 560).setDepth(0);
    var grid = this.add.graphics().setDepth(0);
    grid.lineStyle(1, 0x3a3f4d, 1);
    for (var x = 0; x <= 800; x += 32) grid.lineBetween(x, 0, x, 560);
    for (var y = 0; y <= 560; y += 32) grid.lineBetween(0, y, 800, y);

    // 角色1：aseprite 管线
    this.p1 = PhaserDemo.createPlayer(this, null, null, 'player', { controls: 'wasd', animMode: 'aseprite', pos: { x: 260, y: 300 }, label: '角色1（Aseprite 管线）' });
    this.p1.label.setColor('#7fc8ff');

    // 角色2：spritesheet 管线
    this.p2 = PhaserDemo.createPlayer(this, null, null, 'player2', { controls: 'arrows', animMode: 'sheet', pos: { x: 560, y: 300 }, label: '角色2（spritesheet 管线）' });
    this.p2.sprite.setTint(0xff8a7a);
    this.p2.label.setColor('#ff9d8f');

    this.hud = this.add.text(12, 10,
      'Aseprite 角色动画（帧动画素材管线）\nWASD 移动角色1（aseprite） · 方向键移动角色2（spritesheet）',
      { fontFamily: 'monospace', fontSize: '14px', color: '#ffffff', backgroundColor: '#00000088', padding: { x: 8, y: 6 } }).setScrollFactor(0).setDepth(20);

    this.status = this.add.text(400, 528, '', { fontFamily: 'monospace', fontSize: '13px', color: '#c8f0c8', backgroundColor: '#00000099', padding: { x: 10, y: 5 } }).setOrigin(0.5, 1).setScrollFactor(0).setDepth(20);

    if (global.updateDemoPanel) global.updateDemoPanel('anim', {
      '管线 A': 'load.aseprite(player.png, player.json) + anims.createFromAseprite（4 方向 × 4 帧）',
      '管线 B': 'load.spritesheet + anims.generateFrameNumbers（对照基线）',
      '动画就绪': 'walk-down/up/left/right（Aseprite）：' +
        (self.anims.exists('walk-down') && self.anims.exists('walk-up') && self.anims.exists('walk-left') && self.anims.exists('walk-right') ? '✅ 4 个动画已创建' : '❌ 缺失') +
        ' · spritesheet：' +
        (self.anims.exists('player2-down') ? '✅ 已创建' : '❌ 缺失'),
      '素材来源': 'tools/gen_assets.js 程序生成占位帧（无第三方版权）；真实 Aseprite 素材替换同构 png+json 即可',
      '验证点': '走动时方向动画切换、静止回 idle 帧、两管线并存互不干扰',
    });
  };

  AnimScene.prototype.update = function () {
    var r1 = this.p1.update();
    var r2 = this.p2.update();
    var anim1 = this.p1.sprite.anims.currentAnim ? this.p1.sprite.anims.currentAnim.key : 'idle';
    var anim2 = this.p2.sprite.anims.currentAnim ? this.p2.sprite.anims.currentAnim.key : 'idle';
    this.status.setText(
      '角色1: ' + anim1 + (r1.vx || r1.vy ? '（移动中）' : '（静止）') +
      '    |    角色2: ' + anim2 + (r2.vx || r2.vy ? '（移动中）' : '（静止）'));
  };

  global.AnimScene = AnimScene;
})(typeof window !== 'undefined' ? window : this);
