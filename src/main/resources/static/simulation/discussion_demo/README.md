# 💬 剧本杀讨论双通道合并 Demo（阶段一 · 独立验证页）

> **性质**：纯静态独立验证页，**零后端零禁动**。
> **访问**：`src/main/resources/static/simulation/discussion_demo/index.html`（file:// 双击即开）。
> **自测**：`node src/main/resources/static/simulation/discussion_demo/js/self_test.js`。

## 一句话

验证 B1 修复的目标行为契约：**剧本杀讨论阶段人类发言并入 `discussion_say` 单一讨论组**，
消除当前"人类走 /api/send、AI 走讨论引擎"两条互不互通线程导致的"讨论对不上"。

## 目标行为（后端整合时按此契约）

1. `client.ts` 封装 `scriptDiscussionSay(player, message, player_key?)` → `POST /api/script/discussion_say`
2. ChatPage composer：`store.mode==='script' && scriptState?.phase==='DISCUSSION'` → 路由 discussion_say；其余阶段仍走 /api/send
3. 人类发言权豁免：不过 SpeechGate，直接注入讨论组（后端 `ScriptController.discussion_say` L310 已实现）
4. 点名语义：人类发言含 `@角色名` → 该 AI 强制发言
5. 秘密双层防护：持秘密角色 WEAK 摘要（无明文）+ 发言守卫（修订机器人思想，认罪句改写）

## 本 demo 覆盖

- 前后对比说明（现状双通道 vs 合并后单线程）
- 交互：发言 → 注入讨论组 → AI（点名强回应 / 轮次发言 + 秘密守卫 / SpeechGate 静默概率）→ 单一线程渲染
- 自测：秘密守卫 / 点名 / WEAK·MERGED 差异

## 风险与取舍

- demo 用 mock 引擎模拟行为，未接真实后端（避免触碰剧本杀 Service 禁动面）；整合阶段按上述契约在
  `client.ts` + `ChatPage.tsx` + `ScriptGameService` 讨论引擎注入点落地
- 人类发言经 discussion_say 注入后，AI 回应是否接话依赖讨论引擎对 transcript 的读取（整合时验证）
