/* regen_p0816u_alignment.mjs — 用已存 probe JSON 重生成 alignment.md（增强比较器：color(srgb..) ≡ rgba 归一化）
 * 说明：浏览器对 color-mix() 计算值序列化为 color(srgb r g b / a)，与原型字面量 rgba(r,g,b,a) 数值等价；
 *       此脚本将两者归一化后比较，消除「等价但序列化不同」的假阴性。
 */
import { readFileSync, writeFileSync } from 'node:fs';

const OUT = 'D:/echoworld/tmp/p0816u';
const real = JSON.parse(readFileSync(`${OUT}/probe_real.json`, 'utf-8'));
const proto = JSON.parse(readFileSync(`${OUT}/probe_proto.json`, 'utf-8'));

/* color(srgb r g b / a) → rgba(r,g,b,a)（等价归一化，整串内替换）；rgba/rgb 保留 */
function normColor(s) {
  return String(s).replace(
    /color\(srgb\s+([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*\/\s*([\d.]+)\)/g,
    (_, r, g, b, a) => `rgba(${Math.round(parseFloat(r) * 255)},${Math.round(parseFloat(g) * 255)},${Math.round(parseFloat(b) * 255)},${parseFloat(a)})`,
  );
}
function cmp(a, b, tol = 0.8) {
  if (a == null || b == null) return { ok: a == null && b == null, a, b };
  const na = parseFloat(a), nb = parseFloat(b);
  if (!isNaN(na) && !isNaN(nb)) return { ok: Math.abs(na - nb) <= tol, a, b };
  const norm = (s) => normColor(String(s)).replace(/\s+/g, '').toLowerCase();
  const sa = norm(a), sb = norm(b);
  if (sa === 'none' || sb === 'none') return { ok: sa === sb, a, b };
  return { ok: sa === sb, a, b };
}
function compareObj(pObj, rObj) {
  const rows = [];
  if (pObj == null || rObj == null) return rows;
  if (typeof pObj === 'object' && pObj && typeof rObj === 'object' && rObj) {
    for (const prop of Object.keys(pObj)) {
      if (String(prop).startsWith('-webkit-') || String(prop).startsWith('background')) continue;
      const res = cmp(pObj[prop], rObj[prop]);
      rows.push({ key: prop, ...res });
    }
  } else {
    rows.push({ key: '_', ...cmp(pObj, rObj) });
  }
  return rows;
}
function bgSem(a, b) {
  if (!a || !b) return { ok: a === b, a, b };
  return { ok: normColor(a).replace(/\s+/g, '') === normColor(b).replace(/\s+/g, ''), a, b };
}

const varPairs = [
  ['--bg', '--proto-bg'], ['--panel', '--proto-panel'], ['--panel-2', '--proto-panel-2'],
  ['--line', '--proto-line'], ['--text', '--proto-text'], ['--dim', '--proto-dim'],
  ['--dim2', '--proto-dim2'], ['--green', '--proto-green'],
  ['--phase', '--proto-phase'], ['--phase-strong', '--proto-phase-strong'],
  ['--phase-soft', '--proto-soft'], ['--phase-glow', '--proto-glow'],
];
const elemMap = {
  topbar: 'topbar', logo: 'logo', badgePhase: 'badgePhase', badgeRound: 'badgeRound', badgeGoal: 'badgeGoal',
  stepActive: 'stepActive', stepDotActive: 'stepDotActive', stepDotDone: 'stepDotDone', stepDotWait: 'stepDotWait',
  stepName: 'stepName', char: 'char', avatar: 'avatar', statusOn: 'statusOn',
  choiceBar: 'choiceBar', choice: 'choice', choiceSmall: 'choiceSmall',
  locUnsearched: 'locUnsearched', locSearched: 'locSearched', locSearchedBefore: 'locSearchedBefore',
  locCount: 'locCount', locCta: 'locCta',
  vnDialog: 'vnDialog', vnAvatar: 'vnAvatar', vnNameplate: 'vnNameplate', vnTextbox: 'vnTextbox', vnLine: 'vnLine',
  sysLine: 'sysLine', vnTag: 'vnTag', bubble: 'bubble', contra: 'contra',
  talkBar: 'talkBar', tBtn: 'tBtn', ammo: 'ammo', ammoCount: 'ammoCount',
  hsInput: 'hsInput', chip: 'chip', chipOn: 'chipOn',
  clue: 'clue', clueId: 'clueId', locTag: 'locTag', matrix: 'matrix', mkDirect: 'mkDirect', roCard: 'roleCard',
  trust: 'trust', trustNum: 'trustNum', tcOn: 'tcOn',
  sus: 'sus', susSel: 'susSel', susAvatar: 'susAvatar', susName: 'susName', selMark: 'selMark',
  voteBar: 'voteBar', voteBtn: 'voteBtn', abstainBtn: 'abstainBtn', vpNum: 'vpNum', vpCellDone: 'vpCellDone',
  stat: 'stat', barTrack: 'barTrack', slapTxt: 'slapTxt',
  tabs: 'tabs', tabOn: 'tabOn', floatBtn: 'floatBtn', toast: 'toast',
  h1: 'investTitle', sub: 'investSub', secTitle: 'sectionLabel',
};

const lines = [];
lines.push('# P-0816-U 视觉对齐数值对比（CDP 计算样式，增强归一化：color(srgb)≡rgba）\n');
lines.push('> 蓝本 = docs/ui-prototype 三张原型页；实际 = 8000 后端 + 4399 新 bundle 对局页（真实对局）。\n');
let total = 0, aligned = 0;
const fails = [];
for (const ph of ['investigation', 'discussion', 'vote']) {
  const p = proto[ph], r = real[ph];
  lines.push(`\n## ${ph}（${ph}.html ↔ 实际对局）\n`);
  lines.push('| 项 | 原型 | 实际 | 判定 |');
  lines.push('|---|---|---|---|');
  for (const [pv, rv] of varPairs) {
    const res = cmp(p.vars[pv], r.vars[rv]);
    total++; if (res.ok) aligned++; else fails.push(`${ph} var ${pv}`);
    lines.push(`| var ${pv.replace('--', '')} | ${p.vars[pv] || '-'} | ${r.vars[rv] || '-'} | ${res.ok ? '✅' : '❌'} |`);
  }
  const res = bgSem(p.bodyBg?.bg, r.wsBg?.bg);
  total++; if (res.ok) aligned++; else fails.push(`${ph} bodyBg`);
  lines.push(`| 氛围背景 | ${String(p.bodyBg?.bg ?? '-').slice(0, 110)} | ${String(r.wsBg?.bg ?? '-').slice(0, 110)} | ${res.ok ? '✅' : '❌（结构近似）'} |`);
  for (const [pk, rk] of Object.entries(elemMap)) {
    const rows = compareObj(p[pk], r[rk]);
    for (const row of rows) {
      total++; if (row.ok) aligned++; else fails.push(`${ph} ${pk}.${row.key}`);
      lines.push(`| ${pk} ${row.key} | ${String(row.a ?? '-').slice(0, 90)} | ${String(row.b ?? '-').slice(0, 90)} | ${row.ok ? '✅' : '❌'} |`);
    }
    const pa = p[pk]?.background ? p[pk].background : p[pk]?.['background-image'];
    const rb = r[rk]?.background ? r[rk].background : r[rk]?.['background-image'];
    if (pa && rb) {
      const r2 = bgSem(pa, rb);
      total++; if (r2.ok) aligned++; else fails.push(`${ph} ${pk} bg`);
      lines.push(`| ${pk} bg | ${String(pa).slice(0, 90)} | ${String(rb).slice(0, 90)} | ${r2.ok ? '✅' : '❌（渐变结构，人工复核截图）'} |`);
    }
  }
  lines.push(`\nDOM 探针：\`${JSON.stringify(r.dom)}\``);
}
lines.push(`\n## 汇总`);
lines.push(`- 对比项：${total}，完全对齐：${aligned}（${(aligned / Math.max(1, total) * 100).toFixed(2)}%）`);
lines.push(`- ❌ 清单（${fails.length}）：${fails.slice(0, 30).join('、') || '无'}`);
lines.push('- 截图留证：tmp/p0816u/proto-*.png（原型）↔ real2-*.png（实际）↔ cmp-*.png（并排）供人工复核。');
writeFileSync(`${OUT}/alignment.md`, lines.join('\n'), 'utf-8');
console.log(`REGEN total=${total} aligned=${aligned} fails=${fails.length}`);
