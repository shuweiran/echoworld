/**
 * discussion_demo.js — 剧本杀讨论双通道合并 demo 逻辑（阶段一 demo 版）
 *
 * 现状问题（B1）：人类在剧本杀讨论阶段的发言走 /api/send → RouterService 一条通道，
 * AI 发言走 discussion 引擎另一条通道，两条线程互不互通 → "讨论内容对不上"。
 *
 * 目标行为（合并后）：
 *   - script 模式 phase=DISCUSSION 时，composer 发言 → POST /api/script/discussion_say
 *     （后端 ScriptController.discussion_say 已存在，人类发言权豁免：不过 SpeechGate 直接注入讨论组）
 *   - AI 发言来自同一讨论组（持秘密角色 WEAK 摘要隐藏秘密 / 未持 MERGED 全文）
 *   - 人类发言注入后，相关 AI 按 SpeechGate 门控回应 → 单一连贯讨论线程
 *
 * 本 demo 用 mock 引擎模拟合并后的行为（含秘密隐藏校验），零后端离线可跑；
 * 展示 前后对比 与 单一讨论线程 的完整交互流。
 */

(function (global) {
  'use strict';

  /** 讨论角色：持秘密 / 未持（对齐 D-012：持秘密 WEAK 摘要 / 未持 MERGED 全文） */
  const ROLES = [
    { name: '白司迁', secret: '我在书房地板下发现一封信，信里写沈万堂打算改遗嘱，把家产留给私生女。', hasSecret: true },
    { name: '沈夫人', secret: '我当晚在花园见过二少爷与管家争执，他手里攥着一把水果刀。', hasSecret: true },
    { name: '沈青川', secret: '桌上的龙井是我端去的，我半夜去书房要钱，听到里面有人说话就先走了。', hasSecret: true },
    { name: '管家', secret: '', hasSecret: false },
  ];

  /** 秘密守卫：发言若泄露他人/自己秘密关键词则打码（demo 简化：模拟 DPF/修订机器人思想） */
  const FORBIDDEN_HINTS = ['凶手是我', '我杀了', '我下毒', '我是凶手', '刀是我捅的'];

  function hasLeak(text) {
    return FORBIDDEN_HINTS.some((h) => text.includes(h));
  }

  function guardSecret(text, role) {
    // 简化守卫：如果 AI 自己爆出"我是凶手"类 → 改写为掩饰
    if (role.hasSecret && hasLeak(text)) {
      return { text: '（按捺住心里的秘密，缓缓开口）我只知道那晚宅子里很乱，其他的……我不能多说。', guarded: true };
    }
    return { text: text, guarded: false };
  }

  /** 模拟 AI 讨论发言：点名 → 强回应；否则按轮次发言（含 SpeechGate 静默概率） */
  function aiReply(role, mentioned, round, lastHuman) {
    if (mentioned) {
      const base = mentioned.includes('凶手') || mentioned.includes('你')
        ? `被点到名的${role.name}微微一顿：我只说实话——${role.hasSecret ? '我确实知道一些事，但和命案无关。' : '那晚我和大家一起，没单独行动过。'}`
        : `${role.name}抬头看向说话的人：${lastHuman ? '你说的这个，我倒是可以补充一点。' : '嗯，我正好也想说说这个。'}`;
      return guardSecret(base, role);
    }
    // 未点名：按轮次发言，秘密角色表达含糊
    const lines = [
      `${role.name}：${role.hasSecret ? '我总觉得有些细节不对劲……但一时说不上来。' : '大家先别急，把线索理一理。'}`,
      `${role.name}：${role.hasSecret ? '（目光闪躲）那晚……我其实听到了一些声音。' : '我这边没什么特别的，都在客厅待着。'}`,
    ];
    const base = lines[round % lines.length];
    return guardSecret(base, role);
  }

  global.DiscussionDemo = {
    ROLES: ROLES,
    hasLeak: hasLeak,
    guardSecret: guardSecret,
    aiReply: aiReply,
  };
})(window);
