/**
 * image_spec.js — 生图管线契约 + 内置 spec 合成器（阶段一 demo 版）
 *
 * 目标：演示「LLM/规则 产出结构化生图描述（image_spec）→ 生图 provider → 渲染」
 * 整条管线，且可零后端离线跑（provider 缺省走程序化 SVG 占位降级）。
 *
 * 契约 image_spec v1（字段表，后续整合时进文档/后端 schema，对齐 D-014 版本纪律）：
 * {
 *   "image_version": 1,
 *   "theme": 剧本主题（全局风格锚点）,
 *   "images": [
 *     {
 *       "id": 唯一 id,
 *       "kind": "character" | "scene" | "clue" | "tile_style",
 *       "name": 展示名（角色名/场景名/线索名/瓦片名）,
 *       "prompt": 正向描述（LLM 扩写用；占位则用 name+theme 派生）,
 *       "negative": 负向描述（可选）,
 *       "style": 风格提示（统一风格锚点）,
 *       "aspect": "portrait" | "landscape" | "square",
 *       "usage": 消费用途（role_card_avatar / scene_background / clue_evidence / tileset_style）,
 *       "related": 关联对象（角色名/场景名/线索地点，后续映射 assets.characterName/sceneId）,
 *       "status": "pending" | "generated" | "fallback" | "failed"
 *     }
 *   ]
 * }
 *
 * demo 阶段不含真实 LLM 调用（工程 8000 后端只读不新增端点，避免动禁动面）；
 * 但预留 LLM 扩写钩子 genSpecWithLlm（provider.js 可接 OpenAI 兼容 chat/completions 端点）。
 */

(function (global) {
  'use strict';

  /** 全局风格锚点：不同主题自动派生统一风格串（演示风格统一 > 单图质量）。 */
  const THEME_STYLES = [
    { match: ['民国', '宅邸', '庄园', '老宅', '民国宅邸'], style: '民国 noir 复古手绘，低饱和，胶片颗粒，油画笔触' },
    { match: ['古风', '仙侠', '江湖', '宫廷', '武侠'], style: '新国风水墨，工笔线稿，宣纸质感，淡彩' },
    { match: ['科幻', '未来', '赛博', '太空', '星际'], style: '赛博霓虹，硬边光效，冷色调，高对比' },
    { match: ['校园', '青春', '教室', '高中'], style: '清新动漫风，明亮柔光，日系厚涂' },
    { match: ['恐怖', '怪谈', '诡秘', '惊悚'], style: '暗黑哥特，低光高对比，雾感，电影感' },
    { match: ['童话', '奇幻', '魔法', '森林'], style: '手绘绘本风，暖色，圆润造型，细节丰富' },
  ];
  const DEFAULT_STYLE = '商业插画风，电影级打光，细节丰富';

  /** 主题 → 风格（子串匹配，取首个命中）。 */
  function styleForTheme(theme) {
    if (!theme) return DEFAULT_STYLE;
    const t = String(theme);
    for (const s of THEME_STYLES) {
      if (s.match.some((k) => t.includes(k))) return s.style;
    }
    return DEFAULT_STYLE;
  }

  /** 由剧本 schema v1 数据合成 image_spec（演示入口：粘贴剧本 JSON 或内置样例）。 */
  function synthesizeFromScript(script, opts) {
    const theme = (opts && opts.theme) || (script && (script.theme || (script.metadata && script.metadata.title))) || '民国宅邸凶案';
    const style = (opts && opts.style) || styleForTheme(theme);
    const images = [];

    // 角色 → 立绘
    const roles = script && Array.isArray(script.roles) ? script.roles : [];
    roles.forEach((r, i) => {
      images.push({
        id: 'char_' + (r.id || ('role_' + i)),
        kind: 'character',
        name: r.name || ('角色' + (i + 1)),
        prompt: (r.intro || r.secret || r.name || '').slice(0, 120),
        negative: '文字水印, 低质量, 变形, 多只手, 多余手指',
        style: style,
        aspect: 'portrait',
        usage: 'role_card_avatar',
        related: r.name,
        status: 'pending',
      });
    });

    // 背景 → 场景氛围图
    const bg = script && (script.background || (script.metadata && script.metadata.background));
    if (bg) {
      images.push({
        id: 'scene_main',
        kind: 'scene',
        name: '主场景',
        prompt: bg.slice(0, 120),
        negative: '文字水印, 低质量',
        style: style,
        aspect: 'landscape',
        usage: 'scene_background',
        related: theme,
        status: 'pending',
      });
    }

    // 地点 → 房间氛围图（地图背景层）
    const locations = script && Array.isArray(script.locations) ? script.locations : [];
    locations.forEach((loc, i) => {
      images.push({
        id: 'scene_' + (i + 1),
        kind: 'scene',
        name: String(loc),
        prompt: String(loc) + ' 的环境细节，符合主题氛围',
        negative: '文字水印, 低质量',
        style: style,
        aspect: 'landscape',
        usage: 'scene_background',
        related: String(loc),
        status: 'pending',
      });
    });

    // 线索 → 物证图
    const clues = script && Array.isArray(script.clues) ? script.clues : [];
    clues.forEach((c, i) => {
      images.push({
        id: 'clue_' + (c.id || ('clue_' + i)),
        kind: 'clue',
        name: (c.title || ('线索' + (i + 1))),
        prompt: (c.content || '').slice(0, 120),
        negative: '文字水印, 低质量',
        style: style,
        aspect: 'square',
        usage: 'clue_evidence',
        related: c.location,
        status: 'pending',
      });
    });

    // 瓦片风格（tile_style）→ SCENE_TILESET 风格锚点
    images.push({
      id: 'tile_style',
      kind: 'tile_style',
      name: theme + ' 瓦片风格',
      prompt: '2D 俯视 tilemap 瓦片图集，32px，' + style,
      negative: '文字水印, 接缝错位',
      style: style,
      aspect: 'square',
      usage: 'tileset_style',
      related: theme,
      status: 'pending',
    });

    return {
      image_version: 1,
      theme: theme,
      style: style,
      images: images,
    };
  }

  /** 内置演示剧本（与 docs/剧本-schema-v1.md 结构对齐，供无后端离线演示）。 */
  const DEMO_SCRIPT = {
    theme: '民国宅邸凶案',
    metadata: { title: '民国宅邸凶案', player_min: 3, player_max: 6, tags: ['民国', '宅邸'] },
    background: '民国二十年的秋夜，雨雾笼罩的沈家大宅。家宴散后，老爷沈万堂死于书房，现场没有打斗痕迹，只有一杯未喝完的龙井。',
    locations: ['书房', '客厅', '花园', '厨房'],
    roles: [
      { id: 'r1', name: '白司迁', intro: '留洋归来的私家侦探，与沈家私交甚笃，受邀赴宴。', secret: '我在书房地板下发现一封信，信里写沈万堂打算改遗嘱，把家产留给私生女。' },
      { id: 'r2', name: '沈夫人', intro: '沈万堂的续弦，年轻时是戏班名角。', secret: '我当晚在花园见过二少爷沈青川与管家争执，他手里攥着一把水果刀。' },
      { id: 'r3', name: '沈青川', intro: '沈家二少爷，留学东洋，欠下巨额赌债。', secret: '我半夜去书房找爹要钱，听到里面有人说话，我害怕就先走了——桌上的龙井是我端去的。' },
    ],
    clues: [
      { id: 'c1', title: '未喝完的龙井', location: '书房', content: '杯中残茶有淡淡苦杏仁味，是氰化物。', transferable: true },
      { id: 'c2', title: '地板下的信', location: '书房', content: '沈万堂写给私生女的信，提及要改遗嘱。', transferable: false },
      { id: 'c3', title: '染血的手帕', location: '花园', content: '绣着「青」字的手帕，角落沾有暗红血迹。', transferable: true },
    ],
    killer_id: 'r3',
  };

  global.ImageSpec = {
    styleForTheme: styleForTheme,
    synthesizeFromScript: synthesizeFromScript,
    DEMO_SCRIPT: DEMO_SCRIPT,
    THEME_STYLES: THEME_STYLES,
    DEFAULT_STYLE: DEFAULT_STYLE,
  };
})(window);
