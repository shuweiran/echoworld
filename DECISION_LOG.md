# DECISION_LOG.md — 架构决策史（AI 必读②）

> ⚠️ **所有 agent 开工前必读**：这是"为什么这么设计"的答案。改代码前先查这里，避免推翻历史决策或重复踩坑。
> 格式：日期｜决策｜原因｜放弃了什么｜影响。

---

## 2026-07-31（Phase 1-4 Track 融合）

### D-001 Arbiter 输入脱敏是生死线，不是可选
- **决策**：RouterService 加 `sanitizeSummaryForArbiter()`，Arbiter 永远看不到全轨道全文，只看脱敏摘要
- **原因**：调研发现 Arbiter 若看到 Track 全文即泄露信息源——"谁说了什么"从摘要就能推断，信息隔离失效
- **放弃**：让 Arbiter 看完整轨道上下文的便利性
- **影响**：信息隔离的根基；后续任何接入 Arbiter 的路径（剧本杀审批/狼人杀判定）都必须过脱敏

### D-002 Phase 3 WorldDirector 纯规则，零 LLM 调用
- **决策**：WorldDirectorService 目标生成用规则（情绪异常→平静 / 对话→参与 / 闲置→探索），不用 LLM
- **原因**：成本控制。每组每 tick 调 LLM 的成本估算：组×10 轮×角色数，纯规则省掉大头
- **放弃**：LLM 驱动的更"聪明"目标生成（保留 `generateGoalWithLLM` 作为手动扩展点，失败回退规则）
- **影响**：2D 模拟运行成本可控；目标语义较粗糙（P1 可升级）

### D-003 Phase 3 只加外层 SimulationOrchestrator，不重构 RouterService
- **决策**：新编排层 `SimulationOrchestrator.tick()` 包裹现有对话流，RouterService 一行不动
- **原因**：需求文档第十四条要求；避免影响 SSE / Memory / 游戏模式（狼人杀/剧本杀）
- **放弃**：彻底重构 Router 的架构洁癖
- **影响**：现有 73 tests 零破坏；新功能全部在编排层增量

### D-004 TrackScore 阈值 40（需实测调参，勿 hardcode）
- **决策**：`InteractionDetector.TRACK_THRESHOLD = 40`（≥40 触发 Track 模式），因子：size 30 / bystander 20 / conflict 30 / secret 50 / emotion 25
- **原因**：Demo 实证（真实 LLM：无隔离泄露率 100% → Track WEAK 0% 泄露，token ↓14%）
- **放弃**：动态阈值（当前阶段无数据支撑）
- **影响**：已加 BoundaryTest 锁定 39/40 边界（问题清单 D18 建议配置化，待办）

### D-005 TrackStrategy 统一替换 GROUP_DISCUSSION + DEBATE
- **决策**：新 `TrackStrategy`（17.8KB）统一承担 GROUP_DISCUSSION 与 DEBATE；DYAD/Speech 不动
- **原因**：混合轨道上下文（MERGED 全文 / WEAK 摘要 / ISOLATED 独白 / 无 track 回退 GroupStrategy）
- **放弃**：给 DEBATE 单独写策略的重复工作
- **影响**：GroupStrategy/DebateStrategy 不删除（需求文档第十三条：降级为默认模式）

### D-006 MovementConstraint 纯规则，零 LLM
- **决策**：轨道→运动约束（MERGED 聚集质心±偏移 / WEAK 听觉带 [0.5h, h] / ISOLATED 避让≥60 格），优先级 ISOLATED > WEAK > MERGED；玩家手动目标不被覆盖
- **原因**：需求第八/九条——Track 不直接移动角色，而是生成空间约束；纯规则实时性
- **放弃**：GroupAnchor 完整队形（leader+follow slot，D2 降级项，P2 待补）
- **影响**：约束是边界不是牢笼——角色在带内自由漫游，越界才纠正

### D-007 前端角色详情走 body + 向后兼容 query
- **决策**：`SceneController.startScene` `@RequestBody(required=false)` 收 `{agents, me, characters[]}`，与 query 并存
- **原因**：前端未改时不崩（向后兼容）；characterDetails 需要结构化传递
- **影响**：7 项前端修复清单的一部分（loadState 扁平结构兼容等）

### D-008 测试基建：RANDOM_PORT + H2 mem + mock LLM
- **决策**：`src/test/resources/application-test.yml`：`server.port: 0`（避开 8000 生产）、`jdbc:h2:mem:testdb`（不污染 `./data/roleplay`）、`llm.api-base: http://localhost:9999/mock-llm`（零成本）
- **原因**：G2 禁止 spring-boot:run 直跑 + 数据隔离 + LLM 成本
- **放弃**：测试连生产 H2 文件库 / 真实 LLM
- **影响**：93 tests 可重复、可 CI；LONG-01 10 万字 5.09s 跑完

### D-009 剧本杀 Step 3 降级预案（未衡审查定案）
- **决策**：若 ConversationManager 适配 SimulationWorld 失败（剧本杀无 2D 世界），降级为"轮次发言"：按 assignments 顺序轮流每人 1 条消息，轮数可配置默认 2，结束自动进 VOTE；秘密隐藏退化为 prompt 禁令
- **原因**：`ConversationManager.init` 需要 `SimulationWorld + Function<String,Agent>`，剧本杀没有 world 实体
- **放弃**：降级版接 Track（P1 再补）
- **影响**：降级版验收 D-3-1~D-3-4 替代 A3-1~A3-4（蓝图第四章）

### D-010 剧本杀 Step 4 killer 与 truth 职责分离
- **决策**：判定用 `mostVoted.equals(killer)`（机器对比例，角色名精确匹配），不再 `truth.contains(mostVoted)`（字符串扫文案）；truth 保留为人类可读叙述文
- **原因**：contains 子串误匹配（投"张三"命中"管家张三在书房…"判中）；无凶手实体概念
- **放弃**：继续用 truth 文案做模糊判定
- **影响**：schema 需加 killer 字段；双生成器（ScriptService/initGame）schema 必须同步（风险 5）

### D-011 剧本杀 Step 1 Part A 已被并行工作流完成
- **决策**：secrets 字段/toMap your_secret/getSecretFor 已由另一主会话的 coder 实现（16:05），本会话仅做 Part B（Track ISOLATED 集成）
- **原因**：并行工作流撞车的事实（sessions_list 发现 4 个 coder 在改同一批文件）
- **影响**：**任何剧本杀派单前先 `git diff` 确认基线**，避免重复实现

---

## 更早的决策（迁移期，MIGRATION_PLAN.md 补充）
- **Virtual Threads** 并行 Agent 执行（LLM 大量 IO 等待）；放弃 Reactive WebFlux（代码复杂度）
- Python → Java 全量迁移（v4 前端保留 React，后端重写）
- H2 双模式：开发 mem / 持久 file（README 声明，注意 D14 缺陷：角色/场景实际未落库）
