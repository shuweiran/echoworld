/**
 * providers.js — 生图 provider 适配层（阶段一 demo 版）
 *
 * 三层降级：
 *   1. 真实 provider（用户可配置 OpenAI 兼容 /images/generations 或 SD WebUI /txt2img）
 *   2. LLM 扩写钩子（可选：把 prompt 喂给兼容 chat/completions 端点做风格扩写）
 *   3. 程序化 SVG 占位（零依赖零成本，离线可跑，管线仍完整可演示）
 *
 * provider 配置（index.html 顶栏可填，localStorage 持久化）：
 *   {
 *     provider: "offline" | "openai_image" | "sd_webui",
 *     url, api_key, model,
 *     style_extend: false,
 *     llm_url, llm_key, llm_model,   // 可选 LLM 扩写
 *   }
 */

(function (global) {
  'use strict';

  const LS_KEY = 'imagegen_provider_cfg';

  // Node 沙箱（self_test）无 localStorage → 内存兜底
  const storage = (typeof localStorage !== 'undefined') ? localStorage : (() => {
    const m = {};
    return {
      getItem: (k) => (k in m ? m[k] : null),
      setItem: (k, v) => { m[k] = String(v); },
      removeItem: (k) => { delete m[k]; },
    };
  })();

  const DEFAULT_CFG = {
    provider: 'offline',
    url: '',
    api_key: '',
    model: '',
    style_extend: false,
    llm_url: '',
    llm_key: '',
    llm_model: '',
  };

  function loadCfg() {
    try {
      const raw = storage.getItem(LS_KEY);
      if (raw) return Object.assign({}, DEFAULT_CFG, JSON.parse(raw));
    } catch (e) { /* ignore */ }
    return Object.assign({}, DEFAULT_CFG);
  }

  function saveCfg(cfg) {
    storage.setItem(LS_KEY, JSON.stringify(cfg));
  }

  /** 统一返回 {ok:boolean, dataUrl?:string, url?:string, error?:string, latencyMs:number} */
  async function generateImage(spec, cfg) {
    const c = cfg || loadCfg();
    const t0 = performance.now();

    // 1. 可选 LLM 扩写 prompt（风格统一锚点注入）
    let prompt = spec.prompt || '';
    if (c.style_extend && c.llm_url) {
      try {
        prompt = await extendPrompt(prompt, spec, c);
      } catch (e) { /* 扩写失败用原始 prompt */ }
    }

    // 2. 按 provider 分发
    if (c.provider === 'openai_image' && c.url && c.api_key) {
      try {
        const r = await openaiImage(prompt, spec, c);
        return Object.assign({ ok: true, latencyMs: performance.now() - t0 }, r);
      } catch (e) {
        return { ok: false, error: '真实 provider 失败，降级占位：' + (e && e.message ? e.message : e), latencyMs: performance.now() - t0 };
      }
    }
    if (c.provider === 'sd_webui' && c.url) {
      try {
        const r = await sdWebui(prompt, spec, c);
        return Object.assign({ ok: true, latencyMs: performance.now() - t0 }, r);
      } catch (e) {
        return { ok: false, error: 'SD WebUI 失败，降级占位：' + (e && e.message ? e.message : e), latencyMs: performance.now() - t0 };
      }
    }

    // 3. 离线占位
    return {
      ok: true,
      fallback: true,
      dataUrl: offlineSvg(spec, prompt),
      latencyMs: performance.now() - t0,
    };
  }

  /** OpenAI 兼容 /images/generations（DALL-E / 通义万相 / 即梦等若走兼容接口可接）。 */
  async function openaiImage(prompt, spec, c) {
    const body = {
      model: c.model || 'gpt-image-1',
      prompt: prompt,
      n: 1,
      size: aspectSize(spec.aspect),
      response_format: 'b64_json',
    };
    const resp = await fetch(c.url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + c.api_key,
      },
      body: JSON.stringify(body),
    });
    if (!resp.ok) throw new Error('HTTP ' + resp.status + ' ' + (await safeText(resp)));
    const json = await resp.json();
    const item = (json.data && json.data[0]) || {};
    if (item.b64_json) return { dataUrl: 'data:image/png;base64,' + item.b64_json };
    if (item.url) return { url: item.url };
    throw new Error('响应无图片数据');
  }

  /** SD WebUI /txt2img。 */
  async function sdWebui(prompt, spec, c) {
    const body = {
      prompt: prompt,
      negative_prompt: spec.negative || '',
      steps: 20,
      width: aspectW(spec.aspect),
      height: aspectH(spec.aspect),
      cfg_scale: 7,
      seed: -1,
    };
    const resp = await fetch(c.url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!resp.ok) throw new Error('HTTP ' + resp.status + ' ' + (await safeText(resp)));
    const json = await resp.json();
    if (json.images && json.images[0]) return { dataUrl: 'data:image/png;base64,' + json.images[0] };
    throw new Error('响应无图片数据');
  }

  /** 可选 LLM 扩写：把朴素 prompt + 风格锚点喂给兼容 chat/completions 端点。 */
  async function extendPrompt(prompt, spec, c) {
    const sys = '你是游戏美术总监。请把下面这条生图描述扩写为 3-4 句高质量英文/中文提示词，'
      + '保持给定风格统一，不要加引号或换行。只输出提示词本身。';
    const user = '风格：' + (spec.style || '') + '；用途：' + (spec.usage || '') + '；描述：' + (prompt || spec.name);
    const resp = await fetch(c.llm_url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + c.llm_key,
      },
      body: JSON.stringify({
        model: c.llm_model || 'deepseek-chat',
        messages: [
          { role: 'system', content: sys },
          { role: 'user', content: user },
        ],
        max_tokens: 300,
      }),
    });
    if (!resp.ok) throw new Error('LLM 扩写 HTTP ' + resp.status);
    const json = await resp.json();
    const out = (json.choices && json.choices[0] && json.choices[0].message && json.choices[0].message.content) || '';
    return (out || prompt).trim();
  }

  /* ── 工具 ─────────────────────────────────────────────── */

  function aspectSize(aspect) {
    return aspect === 'portrait' ? '1024x1536' : (aspect === 'landscape' ? '1536x1024' : '1024x1024');
  }
  function aspectW(aspect) { return aspect === 'portrait' ? 512 : (aspect === 'landscape' ? 768 : 512); }
  function aspectH(aspect) { return aspect === 'portrait' ? 768 : (aspect === 'landscape' ? 512 : 512); }

  async function safeText(resp) {
    try { return (await resp.text()).slice(0, 200); } catch (e) { return ''; }
  }

  /** kind → 配色（占位图也带语义区分，便于验证管线正确性）。 */
  const KIND_COLORS = {
    character: ['#7c3aed', '#2563eb'],
    scene: ['#0ea5e9', '#14b8a6'],
    clue: ['#f59e0b', '#ef4444'],
    tile_style: ['#64748b', '#334155'],
  };

  /** 程序化 SVG 占位（零依赖）。 */
  function offlineSvg(spec, prompt) {
    const w = spec.aspect === 'portrait' ? 400 : (spec.aspect === 'landscape' ? 640 : 400);
    const h = spec.aspect === 'portrait' ? 600 : (spec.aspect === 'landscape' ? 360 : 400);
    const colors = KIND_COLORS[spec.kind] || KIND_COLORS.scene;
    const id = 'g' + Math.random().toString(36).slice(2, 8);
    const label = (spec.kind === 'character' ? '👤 ' : spec.kind === 'clue' ? '🔎 ' : spec.kind === 'tile_style' ? '🧱 ' : '🖼️ ') + (spec.name || spec.kind);
    const desc = (prompt || '').slice(0, 80);
    const svg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '">'
      + '<defs>'
      + '<linearGradient id="' + id + '" x1="0" y1="0" x2="1" y2="1">'
      + '<stop offset="0" stop-color="' + colors[0] + '"/>'
      + '<stop offset="1" stop-color="' + colors[1] + '"/>'
      + '</linearGradient>'
      + '</defs>'
      + '<rect width="' + w + '" height="' + h + '" fill="url(#' + id + ')"/>'
      + '<rect x="8" y="8" width="' + (w - 16) + '" height="' + (h - 16) + '" fill="none" stroke="rgba(255,255,255,0.55)" stroke-width="2" rx="12"/>'
      + '<text x="' + (w / 2) + '" y="' + (h / 2 - 26) + '" text-anchor="middle" font-size="30" font-family="sans-serif">' + label + '</text>'
      + '<text x="' + (w / 2) + '" y="' + (h / 2 + 16) + '" text-anchor="middle" font-size="15" fill="rgba(255,255,255,0.95)" font-family="sans-serif">占位渲染（离线降级）</text>'
      + '<text x="16" y="' + (h - 16) + '" font-size="11" fill="rgba(255,255,255,0.85)" font-family="sans-serif" text-anchor="start">' + desc + '</text>'
      + '</svg>';
    return 'data:image/svg+xml;base64,' + b64EncodeUtf8(svg);
  }

  /** UTF-8 安全的 base64 编码（SVG 含中文，规避 btoa 对 >0xFF 字符抛异常）。 */
  function b64EncodeUtf8(s) {
    if (typeof TextEncoder !== 'undefined') {
      const bytes = new TextEncoder().encode(s);
      let bin = '';
      for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
      return btoa(bin);
    }
    // 兜底：encodeURIComponent + unescape
    return btoa(unescape(encodeURIComponent(s)));
  }

  global.ImageProviders = {
    DEFAULT_CFG: DEFAULT_CFG,
    loadCfg: loadCfg,
    saveCfg: saveCfg,
    generateImage: generateImage,
    offlineSvg: offlineSvg,
    b64EncodeUtf8: b64EncodeUtf8,
  };
})(window);
