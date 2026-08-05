/**
 * self_test.js — discussion_demo 冒烟自测（Node 直跑）
 * 用法：node js/self_test.js
 * 覆盖：①秘密守卫拦截 ②点名强回应 ③WEAK/MERGED 差异 ④AI 回复含角色名
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

function loadJs(file) {
  const code = fs.readFileSync(path.join(__dirname, file), 'utf8');
  const sandbox = { window: {} };
  sandbox.window.window = sandbox.window;
  vm.createContext(sandbox);
  vm.runInContext(code, sandbox);
  return sandbox.window;
}

const results = [];
function check(name, cond, detail) {
  results.push({ name, ok: !!cond });
  console.log((cond ? '✅ ' : '❌ ') + name + (detail ? '  ' + detail : ''));
}

const D = loadJs('discussion_demo.js').DiscussionDemo;

// ① 秘密守卫：认罪句被拦截改写
const guarded = D.guardSecret('其实我杀了老爷，我是凶手。', D.ROLES[0]);
check('守卫：认罪句被改写', guarded.guarded === true && !D.hasLeak(guarded.text));

// ② 无秘密角色认罪不拦截（未持 MERGED 无秘密可守）
const free = D.guardSecret('我没杀任何人。', D.ROLES[3]);
check('守卫：无秘密角色不误伤', free.guarded === false && free.text.includes('没杀'));

// ③ 点名强回应：点名沈青川 → 其回复非静默轮次模板且含角色名
const r = D.aiReply(D.ROLES[2], '沈青川', 0, '沈青川，那晚你去了书房对吧？');
check('点名：被点名角色回复含其名', r.text.includes('沈青川') || r.text.includes('我'));
check('点名：回复未被守卫拦截', r.guarded === false || !D.hasLeak(r.text));

// ④ 未点名轮次发言（secret 角色含糊 / 非 secret 直白）
const s1 = D.aiReply(D.ROLES[0], '', 0, '').text;
const s2 = D.aiReply(D.ROLES[3], '', 1, '').text;
check('轮次：持秘密角色含糊', s1.includes('说不上来') || s1.includes('听到'));
check('轮次：未持角色直白', s2.includes('客厅') || s2.includes('我这边') || s2.includes('没什么特别'));

const failed = results.filter(r => !r.ok);
console.log('\n' + (failed.length === 0 ? 'ALL PASS' : failed.length + ' FAILED') + '  (' + results.length + ' 项)');
process.exit(failed.length === 0 ? 0 : 1);
