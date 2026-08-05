/**
 * self_test.js — imagegen demo 冒烟自测（Node 直跑，零依赖）
 *
 * 用法：node js/self_test.js
 * 覆盖：①spec 合成（演示剧本→4 类 image_spec）②风格锚点派生 ③离线占位 SVG 输出
 * ④offlineSvg 生成 dataUrl 可被 <img> 解码（base64 合法性）
 */
'use strict';

const fs = require('fs');
const path = require('path');

function loadJs(file) {
  // 浏览器 js 用 window 全局；此处用最小 shim 后在独立沙箱执行
  const code = fs.readFileSync(path.join(__dirname, file), 'utf8');
  const vm = require('vm');
  const sandbox = {
    window: {},
    btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
    unescape: (s) => s,
    performance: { now: () => Date.now() },
    TextEncoder: require('util').TextEncoder,
  };
  sandbox.window.window = sandbox.window;
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);
  return sandbox.window;
}

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond, detail: detail || '' });
  console.log((cond ? '✅ ' : '❌ ') + name + (detail ? '  ' + detail : ''));
}

const ImageSpec = loadJs('image_spec.js').ImageSpec;
const Providers = loadJs('providers.js').ImageProviders;

// ① 演示剧本 → spec 合成
const spec = ImageSpec.synthesizeFromScript(ImageSpec.DEMO_SCRIPT, {});
check('spec 合成：image_version=1', spec.image_version === 1);
check('spec 合成：含 4 类图片', ['character', 'scene', 'clue', 'tile_style'].every(k => spec.images.some(i => i.kind === k)),
  'images=' + spec.images.map(i => i.kind).join(','));
check('spec 合成：角色数=3', spec.images.filter(i => i.kind === 'character').length === 3);
check('spec 合成：线索数=3', spec.images.filter(i => i.kind === 'clue').length === 3);
check('spec 合成：场景数>=2（主场景+地点）', spec.images.filter(i => i.kind === 'scene').length >= 2);
check('spec 合成：usage 映射完整', spec.images.every(i => i.usage && i.aspect && i.status === 'pending'));

// ② 风格锚点派生
check('风格锚点：民国→民国 noir', ImageSpec.styleForTheme('民国宅邸凶案').includes('民国'));
check('风格锚点：科幻→赛博', ImageSpec.styleForTheme('赛博公寓').includes('赛博'));
check('风格锚点：未知→默认', ImageSpec.styleForTheme('随便什么').length > 0);

// ③ 离线占位 SVG
const img = spec.images[0];
const dataUrl = Providers.offlineSvg(img, img.prompt);
check('离线占位：产出 dataURL', dataUrl.startsWith('data:image/svg+xml;base64,'));
const b64 = dataUrl.split(',')[1];
const svg = Buffer.from(b64, 'base64').toString('utf8');
check('离线占位：SVG 可解码且含 <svg', svg.startsWith('<svg') && svg.includes('</svg>'));
check('离线占位：SVG 含标题', svg.includes(img.name));

// ④ generateImage 走离线路径（默认 cfg）
(async () => {
  Providers.saveCfg({ provider: 'offline' });
  const r = await Providers.generateImage(img, { provider: 'offline' });
  check('generateImage：离线返回 ok', r.ok === true && r.fallback === true);
  check('generateImage：返回 dataUrl', typeof r.dataUrl === 'string' && r.dataUrl.startsWith('data:image'));
  check('generateImage：含 latency', typeof r.latencyMs === 'number');

  const failed = results.filter(r => !r.ok);
  console.log('\n' + (failed.length === 0 ? 'ALL PASS' : failed.length + ' FAILED') + '  (' + results.length + ' 项)');
  process.exit(failed.length === 0 ? 0 : 1);
})();
