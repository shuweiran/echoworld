/**
 * main.js — demo 入口：Tab 管理 + Phaser Game 生命周期 + 契约页
 *
 * 生命周期策略（对应迁移计划风险表「React 内嵌 Phaser 生命周期冲突」缓解验证）：
 *   每次切换 Tab 时对上一个 Phaser Game 显式 destroy(true)（removeCanvas=true），
 *   切回时重建新 Game 实例——页面内反复创建/销毁，验证 Game 实例生命周期可收敛
 *   （阶段 1 React 内嵌时采用同样模式：Ref 挂载 + 卸载时显式 destroy）。
 */
(function (global) {
  'use strict';

  var GAME_W = 800, GAME_H = 560;

  var TABS = [
    { id: 'tile', title: '① 瓦片渲染+碰撞', kind: 'phaser', scene: TileCollisionScene },
    { id: 'bsp', title: '② BSP 分区', kind: 'phaser', scene: BspScene },
    { id: 'zones', title: '③ Zone 热点', kind: 'phaser', scene: ZoneScene },
    { id: 'anim', title: '④ Aseprite 动画', kind: 'phaser', scene: AnimScene },
    { id: 'contract', title: '⑤ 地图 JSON 契约', kind: 'dom' },
  ];

  var games = {};      // tabId -> Phaser.Game | null
  var currentTab = null;
  var booted = null;

  function el(id) { return document.getElementById(id); }

  function gameConfig(tab) {
    return {
      type: Phaser.AUTO,
      parent: 'game-' + tab.id,
      width: GAME_W,
      height: GAME_H,
      backgroundColor: '#1d212b',
      physics: { default: 'arcade', arcade: { gravity: { y: 0 }, debug: false } },
      scene: [tab.scene],
      banner: false,
    };
  }

  function mountPhaser(tab) {
    if (games[tab.id]) return; // 已存在（不应发生，销毁策略下）
    games[tab.id] = new Phaser.Game(gameConfig(tab));
  }

  function destroyPhaser(tabId) {
    var g = games[tabId];
    if (g) {
      g.destroy(true); // removeCanvas=true：显式销毁，验证生命周期收敛
      games[tabId] = null;
    }
  }

  // ---------- DOM 面板（scene 通过 updateDemoPanel 回写统计） ----------
  global.updateDemoPanel = function (tabId, stats, mapJson) {
    var box = el('stats-' + tabId);
    if (!box) return;
    var html = '';
    Object.keys(stats).forEach(function (k) {
      html += '<div class="stat-row"><span class="stat-k">' + k + '</span><span class="stat-v">' + stats[k] + '</span></div>';
    });
    box.innerHTML = html;
    if (mapJson && el('json-' + tabId)) {
      el('json-' + tabId).textContent = JSON.stringify(mapJson, null, 1);
    }
  };

  // 线索弹窗（ZoneScene 回调 → DOM）
  global.showClue = function (zone, text) {
    var box = el('clue-box');
    if (!box) return;
    box.innerHTML = '<div class="clue-title">🔍 ' + zone.name + (zone.clue_location ? '（' + zone.clue_location + '）' : '') + '</div><div class="clue-body">' + text + '</div>';
    box.style.display = 'block';
  };

  // ---------- 契约页 ----------
  function initContractTab() {
    if (booted === 'contract') return;
    booted = 'contract';
    var schemaHtml = [
      ['map_version', 'number', '内嵌版本号（阶段 0=1）。D-014 纪律：JSON 内嵌版本、宽容解析归一；缺省按 1 处理', '必填'],
      ['map_id / name / theme', 'string', '地图标识/名称/主题描述（LLM 可自由发挥的叙事字段）', '可选'],
      ['width / height', 'int', '地图尺寸（格数，非像素）', '必填'],
      ['tile_size', 'int', '瓦片像素（缺省 32）', '可选'],
      ['tileset', 'object', '{src, first_gid, tile_count} 素材引用（demo 固定用 assets/tiles.png）', '可选'],
      ['layers.ground', 'int[][]', '瓦片层：瓦片 id 二维数组（行数=height）', '必填'],
      ['layers.collision', 'int[][]', '碰撞层：1=不可通行 0=可通行（显式给出，不依赖瓦片 id 推断）', '必填'],
      ['rooms[]', 'array', '房间区域 {id, name, x, y, w, h, tags?}（阶段 2 可与剧本杀 locations[] 对应）', '可选'],
      ['corridors[]', 'array', '走廊 {id, from, to, points[]}（points 四邻接连通路径）', '可选'],
      ['zones[]', 'array', '热点 {id, name, type(search/door/broadcast), x, y, radius, clue_location?, prompt?}——搜证点；阶段 2 与 clues[].location 绑定', '可选'],
      ['spawn_points[]', 'array', '实体出生点 {id, type(player/npc), x, y}', '可选'],
      ['generator', 'object', '生成器元信息（BSP 用 {kind, seed, note}；LLM 路径可记录 model 等）', '可选'],
    ].map(function (r) {
      return '<tr><td><code>' + r[0] + '</code></td><td>' + r[1] + '</td><td>' + r[2] + '</td><td>' + r[3] + '</td></tr>';
    }).join('');
    el('schema-table').innerHTML = schemaHtml;

    // 样例展示（manor）
    var sampleSel = el('contract-sample-select');
    sampleSel.innerHTML =
      '<option value="manor">manor_01 老宅（手写样例）</option>' +
      '<option value="bsp">BSP 生成样例（seed=20260801）</option>';
    function renderSample() {
      var m = sampleSel.value === 'manor' ? MAP_SAMPLES.manor : Bsp.generateBspMap({ width: 24, height: 16, seed: 20260801 });
      el('contract-json').textContent = JSON.stringify(m, null, 1);
    }
    sampleSel.addEventListener('change', renderSample);
    renderSample();

    // 校验器
    var valBtn = el('val-run');
    var valArea = el('val-input');
    var valResult = el('val-result');
    el('val-load-sample').addEventListener('click', function () {
      valArea.value = JSON.stringify(sampleSel.value === 'manor' ? MAP_SAMPLES.manor : Bsp.generateBspMap({ width: 24, height: 16, seed: 20260801 }), null, 1);
    });
    valBtn.addEventListener('click', function () {
      var raw = valArea.value.trim();
      if (!raw) { valResult.innerHTML = '<div class="val-err">请粘贴或载入样例地图 JSON。</div>'; return; }
      var map;
      try { map = JSON.parse(raw); } catch (e) { valResult.innerHTML = '<div class="val-err">JSON 解析失败：' + e.message + '</div>'; return; }
      var v = Bsp.validateMap(map);
      var html = '<div class="val-' + (v.ok ? 'ok' : 'err') + '">' + (v.ok ? '✅ 通过' : '❌ 失败 ' + v.errors.length + ' 项') + '</div>';
      v.errors.forEach(function (e) { html += '<div class="val-err">- ' + e + '</div>'; });
      v.warnings.forEach(function (w) { html += '<div class="val-warn">- ' + w + '</div>'; });
      valResult.innerHTML = html;
    });
    valArea.value = JSON.stringify(MAP_SAMPLES.manor, null, 1);
  }

  // ---------- Tab 切换 ----------
  function openTab(tab) {
    var prev = currentTab;
    if (prev) {
      el('panel-' + prev.id).classList.remove('active');
      document.querySelector('.tab-btn[data-tab="' + prev.id + '"]').classList.remove('active');
      if (prev.kind === 'phaser') destroyPhaser(prev.id); // 显式销毁（生命周期验证）
    }
    currentTab = tab;
    el('panel-' + tab.id).classList.add('active');
    document.querySelector('.tab-btn[data-tab="' + tab.id + '"]').classList.add('active');
    if (tab.kind === 'phaser') mountPhaser(tab);
    else if (tab.id === 'contract') initContractTab();
  }

  function boot() {
    // 顶部信息
    el('phaser-version').textContent = Phaser.VERSION;
    var mode = (typeof location !== 'undefined' && location.protocol === 'file:') ? 'file://（内嵌资源模式）' : 'http://（真实文件管线）';
    el('access-mode').textContent = mode;

    // 全局错误面（自测用：任何 JS 异常都会落到 #boot-errors）
    window.addEventListener('error', function (e) {
      var box = el('boot-errors');
      if (!box) return;
      box.style.display = 'block';
      box.textContent = (box.textContent ? box.textContent + '\n' : '') + e.message + ' @ ' + (e.filename || '') + ':' + (e.lineno || '');
    });

    var bar = el('tab-bar');
    TABS.forEach(function (t) {
      var btn = document.createElement('button');
      btn.className = 'tab-btn';
      btn.dataset.tab = t.id;
      btn.textContent = t.title;
      btn.addEventListener('click', function () { openTab(t); });
      bar.appendChild(btn);
    });

    // 生成 5 个面板容器
    TABS.forEach(function (t) {
      var panel = document.createElement('section');
      panel.className = 'panel';
      panel.id = 'panel-' + t.id;
      if (t.kind === 'phaser') {
        panel.innerHTML =
          '<div class="game-wrap"><div id="game-' + t.id + '"></div>' +
          '<div class="side"><div class="side-title">统计 / 说明</div><div class="stats" id="stats-' + t.id + '"></div>' +
          '<div class="side-title" style="margin-top:10px">地图 JSON（契约 v1）</div><pre class="json-pre" id="json-' + t.id + '"></pre></div></div>';
        if (t.id === 'zones') {
          panel.innerHTML += '<div id="clue-box" style="display:none"></div>';
        }
        if (t.id === 'bsp') {
          panel.innerHTML += '<div class="row-actions"><button id="bsp-regenerate" class="mini-btn">🔄 重新生成（随机 seed）</button></div>';
        }
      } else {
        panel.innerHTML =
          '<div class="contract-wrap">' +
          '<div class="contract-col"><div class="side-title">字段表（草案 v1）</div><table id="schema-table" class="schema-table"></table>' +
          '<div class="side-title" style="margin-top:14px">校验器（BSP 校验器 = 无 LLM 备选 / LLM 输出防线）</div>' +
          '<textarea id="val-input" rows="10" spellcheck="false"></textarea>' +
          '<div class="row-actions"><button id="val-load-sample" class="mini-btn">📋 载入样例到输入框</button><button id="val-run" class="mini-btn">🔍 校验</button></div>' +
          '<div id="val-result"></div></div>' +
          '<div class="contract-col"><div class="side-title">样例 JSON</div>' +
          '<select id="contract-sample-select"></select><pre id="contract-json" class="json-pre tall"></pre></div>' +
          '</div>';
      }
      document.getElementById('panels').appendChild(panel);
    });

    // BSP 重新生成按钮
    var regen = el('bsp-regenerate');
    if (regen) {
      regen.addEventListener('click', function () {
        var scene = games.bsp && games.bsp.scene.keys.BspScene;
        if (scene) scene.generate(Math.floor(Math.random() * 100000));
      });
    }

    openTab(TABS[0]);

    // ?tab=xxx 深链（自测/演示直达某个验证点）
    var want = (typeof location !== 'undefined') && new URLSearchParams(location.search).get('tab');
    if (want) {
      var t = TABS.filter(function (x) { return x.id === want; })[0];
      if (t) openTab(t);
    }

    // ?selftest=cycle：自动轮巡全部页签，验证 Game 实例 destroy/重建生命周期收敛
    //（对应迁移计划风险表「React 内嵌 Phaser 生命周期冲突」的缓解验证）
    if ((typeof location !== 'undefined') && new URLSearchParams(location.search).get('selftest') === 'cycle') {
      runLifecycleSelfTest();
    }
  }

  function runLifecycleSelfTest() {
    var report = [];
    var i = 0;
    function step() {
      if (i >= TABS.length) {
        var box = el('selftest-report');
        if (box) { box.style.display = 'block'; box.textContent = report.join('\n'); }
        return;
      }
      var tab = TABS[i];
      var prevId = currentTab ? currentTab.id : null;
      var prevGameAlive = prevId ? !!games[prevId] : null;
      openTab(tab);
      // 切换后立即检查：上一 tab 的 Game 应已被 destroy（显式生命周期收敛）
      var destroyed = (prevId ? !games[prevId] : true);
      var created = (tab.kind === 'phaser' ? !!games[tab.id] : true);
      report.push('[' + (i + 1) + '/' + TABS.length + '] tab=' + tab.id +
        ' prev(' + prevId + ') destroyed=' + (destroyed ? 'Y' : 'N') +
        ' current created=' + (created ? 'Y' : 'N') +
        ' prevGameAliveBefore=' + (prevGameAlive === null ? '-' : (prevGameAlive ? 'Y' : 'N')));
      i++;
      setTimeout(step, 400);
    }
    step();
  }

  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
  }
  global.PhaserDemoBoot = { openTab: openTab, boot: boot };
})(typeof window !== 'undefined' ? window : this);
