/**
 * main.js — imagegen_demo 控制器（阶段一 demo 版）
 *
 * 流程：输入主题（可选粘贴剧本 JSON）→ 合成 image_spec → 逐个经 provider 生图 → 渲染卡片
 * + 「后端接入路径」汇总（展示整合进资产表 assets / Phaser 的映射）。
 */

(function () {
  'use strict';

  const $ = (sel) => document.querySelector(sel);

  let currentSpec = null;

  /* ── 初始化 ─────────────────────────────────────────── */

  function init() {
    const cfg = window.ImageProviders.loadCfg();
    $('#provider').value = cfg.provider || 'offline';
    $('#p_url').value = cfg.url || '';
    $('#p_key').value = cfg.api_key || '';
    $('#p_model').value = cfg.model || '';
    $('#p_llm_url').value = cfg.llm_url || '';
    $('#p_llm_key').value = cfg.llm_key || '';
    $('#p_llm_model').value = cfg.llm_model || '';
    $('#p_style_extend').checked = !!cfg.style_extend;
    $('#theme').value = '民国宅邸凶案';
    updateStyleHint();

    $('#btnSynthesize').addEventListener('click', synthesize);
    $('#btnGenerate').addEventListener('click', generateAll);
    $('#btnSaveCfg').addEventListener('click', saveCfg);
    $('#useDemo').addEventListener('click', useDemoScript);
    $('#theme').addEventListener('input', updateStyleHint);

    $('#scriptInput').value = JSON.stringify(window.ImageSpec.DEMO_SCRIPT, null, 2);
  }

  function updateStyleHint() {
    const style = window.ImageSpec.styleForTheme($('#theme').value || '');
    $('#styleHint').textContent = '自动派生风格：' + style;
  }

  function useDemoScript() {
    $('#scriptInput').value = JSON.stringify(window.ImageSpec.DEMO_SCRIPT, null, 2);
  }

  /* ── spec 合成 ──────────────────────────────────────── */

  function synthesize() {
    const theme = $('#theme').value.trim();
    let script = null;
    const raw = $('#scriptInput').value.trim();
    if (raw) {
      try {
        script = JSON.parse(raw);
      } catch (e) {
        alert('剧本 JSON 解析失败：' + e.message + '\n（可点「填入演示剧本」使用内置样例）');
        return;
      }
    }
    currentSpec = window.ImageSpec.synthesizeFromScript(script, { theme: theme });
    renderSpec();
    renderGrid(currentSpec);
    $('#specJson').textContent = JSON.stringify(currentSpec, null, 2);
    $('#count').textContent = currentSpec.images.length + ' 张';
  }

  function renderSpec() {
    const s = currentSpec;
    $('#specSummary').innerHTML =
      '主题：<b>' + esc(s.theme) + '</b> · 风格锚点：<b>' + esc(s.style) + '</b> · 图片数：<b>' + s.images.length + '</b>';
  }

  /* ── 生图 ───────────────────────────────────────────── */

  async function generateAll() {
    if (!currentSpec) { synthesize(); }
    if (!currentSpec) return;
    saveCfg(true);
    const grid = $('#grid');
    grid.innerHTML = '';
    for (const img of currentSpec.images) {
      const card = makeCard(img);
      grid.appendChild(card);
    }
    // 逐个生成（串行，避免打爆免费额度/离线占位均匀演示）
    for (const img of currentSpec.images) {
      const card = grid.querySelector('[data-id="' + img.id + '"]');
      if (!card) continue;
      const imgEl = card.querySelector('.gen-img');
      const statusEl = card.querySelector('.status');
      const metaEl = card.querySelector('.meta');
      statusEl.textContent = '⏳ 生成中…';
      try {
        const r = await window.ImageProviders.generateImage(img, window.ImageProviders.loadCfg());
        if (r.ok) {
          imgEl.src = r.dataUrl || r.url || '';
          img.status = r.fallback ? 'fallback' : 'generated';
          imgEl.classList.toggle('fallback', !!r.fallback);
          statusEl.textContent = r.fallback
            ? '⚠️ 占位（离线降级） · ' + Math.round(r.latencyMs) + 'ms'
            : '✅ 已生成 · ' + Math.round(r.latencyMs) + 'ms';
        } else {
          img.status = 'failed';
          statusEl.textContent = '❌ ' + (r.error || '失败');
        }
        metaEl.textContent = 'prompt: ' + esc((img.prompt || '').slice(0, 90));
      } catch (e) {
        img.status = 'failed';
        statusEl.textContent = '❌ ' + (e && e.message ? e.message : e);
      }
      renderIntegrateHint();
    }
  }

  function makeCard(img) {
    const div = document.createElement('div');
    div.className = 'card';
    div.dataset.id = img.id;
    const kindLabel = { character: '角色立绘', scene: '场景氛围', clue: '物证图', tile_style: '瓦片风格' }[img.kind] || img.kind;
    div.innerHTML =
      '<div class="card-head"><span class="kind-badge kind-' + img.kind + '">' + kindLabel + '</span>'
      + '<span class="card-title">' + esc(img.name) + '</span>'
      + '<span class="usage">' + esc(img.usage) + '</span></div>'
      + '<div class="img-wrap"><img class="gen-img" alt="' + esc(img.name) + '"/></div>'
      + '<div class="status">待生成</div>'
      + '<div class="meta">' + (img.related ? ('related: ' + esc(img.related) + ' · ') : '') + 'aspect: ' + esc(img.aspect) + '</div>';
    return div;
  }

  function renderGrid(spec) {
    const grid = $('#grid');
    grid.innerHTML = '';
    for (const img of spec.images) {
      grid.appendChild(makeCard(img));
    }
    renderIntegrateHint();
  }

  /* ── 后端接入路径汇总 ─────────────────────────────── */

  function renderIntegrateHint() {
    if (!currentSpec) return;
    const rows = [];
    for (const img of currentSpec.images) {
      const usage = img.usage;
      if (usage === 'role_card_avatar') {
        rows.push('<li><b>' + esc(img.name) + '</b> 立绘 → assets 表 <code>CHARACTER_ANIMATION</code>/<code>ROLE_PORTRAIT</code>，character_name=' + esc(img.related || '') + ' → 角色卡头像 + 2D 精灵</li>');
      } else if (usage === 'scene_background') {
        rows.push('<li><b>' + esc(img.name) + '</b> 场景图 → assets <code>SCENE_BACKGROUND</code>，scene_id/' + esc(img.related || '') + ' → 地图背景层 / 剧本卡封面</li>');
      } else if (usage === 'clue_evidence') {
        rows.push('<li><b>' + esc(img.name) + '</b> 物证图 → assets <code>CLUE_IMAGE</code>，location=' + esc(img.related || '') + ' → 搜证结果卡配图</li>');
      } else if (usage === 'tileset_style') {
        rows.push('<li><b>瓦片风格</b> → 经图生图/ControlNet 出 tileable tileset → assets <code>SCENE_TILESET</code> → Phaser tilemap（瓦片保持程序化，AI 只出概念风格图）</li>');
      }
    }
    $('#integrateList').innerHTML = rows.join('');
  }

  /* ── 配置 ───────────────────────────────────────────── */

  function saveCfg(silent) {
    const cfg = window.ImageProviders.loadCfg();
    cfg.provider = $('#provider').value;
    cfg.url = $('#p_url').value.trim();
    cfg.api_key = $('#p_key').value.trim();
    cfg.model = $('#p_model').value.trim();
    cfg.llm_url = $('#p_llm_url').value.trim();
    cfg.llm_key = $('#p_llm_key').value.trim();
    cfg.llm_model = $('#p_llm_model').value.trim();
    cfg.style_extend = $('#p_style_extend').checked;
    window.ImageProviders.saveCfg(cfg);
    if (!silent) alert('配置已保存（localStorage）');
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  document.addEventListener('DOMContentLoaded', init);
})();
