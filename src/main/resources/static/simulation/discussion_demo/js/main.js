/**
 * main.js — discussion_demo 控制器
 *
 * 前后对比：
 *   【现状】composer → /api/send → RouterService（独立线程）；AI → 讨论引擎（另一线程）
 *   【合并后】composer → POST /api/script/discussion_say（人类发言权豁免）→ 同一讨论组
 *
 * 演示交互：输入发言 → 走"合并后"通道注入讨论组 → AI（点名强回应 / 轮次发言 + 秘密守卫）回复，
 * 形成单一连贯线程；右侧显示"现状双通道"对照说明。
 */

(function () {
  'use strict';

  const $ = (sel) => document.querySelector(sel);
  const D = window.DiscussionDemo;

  let round = 0;
  let history = [];

  function init() {
    $('#humanName').value = '你';
    renderRoles();
    $('#btnSend').addEventListener('click', send);
    $('#input').addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });
    $('#btnDemo').addEventListener('click', demoRun);
  }

  function renderRoles() {
    const box = $('#roles');
    box.innerHTML = '';
    D.ROLES.forEach((r) => {
      const div = document.createElement('div');
      div.className = 'role-chip';
      div.innerHTML = '<span class="role-name">' + esc(r.name) + '</span>'
        + (r.hasSecret ? '<span class="secret-badge">持秘密 · WEAK</span>' : '<span class="secret-badge ok">未持 · MERGED</span>')
        + '<div class="secret-text">' + esc(r.secret || '（无秘密）') + '</div>';
      box.appendChild(div);
    });
  }

  function logLine(kind, who, text, extra) {
    const item = { kind, who, text, extra: extra || '' };
    history.push(item);
    const box = $('#transcript');
    const div = document.createElement('div');
    div.className = 'line ' + kind;
    div.innerHTML =
      '<span class="who">' + esc(who) + '</span>'
      + '<span class="text">' + esc(text) + '</span>'
      + (extra ? '<span class="extra">' + esc(extra) + '</span>' : '');
    box.appendChild(div);
    box.scrollTop = box.scrollHeight;
  }

  /** 合并后：人类发言 → discussion_say 通道注入讨论组 */
  function send() {
    const text = $('#input').value.trim();
    if (!text) return;
    const who = $('#humanName').value.trim() || '你';
    $('#input').value = '';
    logLine('human', who, text, '走 POST /api/script/discussion_say（人类发言权豁免，直接注入讨论组）');

    // 点名检测：@角色名 / 句首角色名
    let mentioned = '';
    for (const r of D.ROLES) {
      if (text.includes('@' + r.name) || text.startsWith(r.name + ' ') || text.startsWith(r.name + '，') || text.startsWith(r.name + ':') || text.startsWith(r.name + '：')) {
        mentioned = r.name;
        break;
      }
    }

    // AI 按门控回应（模拟：点名强回应 + 轮次发言）
    for (const r of D.ROLES) {
      const res = D.aiReply(r, r.name === mentioned, round, text);
      const extra = res.guarded ? '🔒 秘密守卫拦截（修订机器人思想：不直接认罪）' : (r.hasSecret ? '（WEAK 摘要，未含秘密明文）' : '（MERGED 全文可见）');
      // 模拟 SpeechGate：低动机可能静默
      const silent = !mentioned && Math.random() < 0.18;
      if (silent) {
        logLine('ai', r.name, '……（沉默）', 'SpeechGate 静默占位，不参与本轮回合');
      } else {
        logLine('ai', r.name, res.text, extra);
      }
    }
    round++;
  }

  function demoRun() {
    $('#input').value = '@沈青川 有人说看见你半夜去过书房，手里的茶是你端的吧？';
    $('#btnSend').click();
    setTimeout(() => {
      $('#input').value = '管家，你半夜看到沈夫人和沈青川在花园争执，刀是谁的？';
      $('#btnSend').click();
    }, 600);
    setTimeout(() => {
      $('#input').value = '（用推理腔）沈青川，如果你不是凶手，为什么不敢说清那晚的时间线？';
      $('#btnSend').click();
    }, 1400);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  document.addEventListener('DOMContentLoaded', init);
})();
