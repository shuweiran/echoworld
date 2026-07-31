# AGENTS.md — Roleplay-Java 项目协作规则（所有 agent 强制）

> ⚠️ **本文件优先级高于一般任务描述**。任何 agent（主 agent / coder / researcher / writer / analyst / weixun）在此项目工作，必须遵守。

## 🔒 开工必读（硬性门禁，未读不得动手）

每次在 `D:\roleplay-java` 开工前，**第一步必须先读**：

1. **`PROJECT_CONTEXT.md`** —— 项目速览（5 秒进入状态：目标/阶段/架构/已完成/未完成/最大问题/文件索引）
2. **`DECISION_LOG.md`** —— 架构决策史（为什么这么设计；改代码前查这里，避免推翻历史决策）
3. 按任务需要：**`TEST_STATUS.md`**（测试现状/历史）/ **`docs/问题清单-20260731.md`**（已知缺陷）/ **`docs/剧本杀差距分析-待办.md`**（剧本杀蓝图）

**判断标准**：任务涉及"改什么/为什么/会不会破坏已有功能"任何一个问题 → 上面三件套必读。

## 🔒 硬性约束（违反即失败）

1. **8000 端口有运行中后端（pid 19848）**：只准 `mvn compile/test`，**禁止 `spring-boot:run`**；测试用 RANDOM_PORT 隔离
2. **不要 git commit**（等主人确认）
3. **修改代码后必须登记** `docs/修改记录.md`（修改人/时间/文件/内容摘要/核查状态）
4. **测试通过后必须更新** `TEST_STATUS.md`（追加执行历史 + 更新汇总；失败也如实写）
5. 不要动 `RouterService` / `ArbiterService` / 审批 / 狼人杀 / SSE 主链路 / `static/`（除非任务明确要求）
6. 不要删除 GroupStrategy/DebateStrategy（需求文档第十三条：降级为默认模式）
7. 中文交流；PowerShell 发中文 JSON 会 GBK 乱码 → 用 Python（UTF-8）

## 🔒 并行工作流预警（2026-07-31 实锤）

**存在另一个主会话「全功能覆盖测试方案」在并行修 P0 缺陷**（已改：AuthController=D19 / VoiceController=D9 / HistoryController=D12 / ScriptGameService=D5 secrets 等 19 文件）。
→ **任何涉及 `src/main/java` 的派单前，先 `git diff` 确认基线**，避免同文件并发冲突。

## 📐 决策纪律

- 新决策（放弃某方案/选型/阈值调整）→ 追加到 `DECISION_LOG.md`（日期/决策/原因/放弃/影响）
- 派单给子 agent 时，任务描述里**必须包含**："先读 D:\roleplay-java\PROJECT_CONTEXT.md 和 DECISION_LOG.md"
- 阶段完成 → 关键节点过未衡审查（agentId: weiheng）→ 通过后汇报主人

## 📝 文档维护协议（持续更新，不积压）

**什么时候更新什么（一一对应，做完即写，不攒）：**

| 动作 | 必须更新 | 内容 |
|---|---|---|
| 改代码 | `docs/修改记录.md` | 修改人/时间/文件/内容摘要/核查状态 |
| 跑测试 | `TEST_STATUS.md` | 追加执行历史 + 更新汇总；失败也写（含原因） |
| 新架构决策 | `DECISION_LOG.md` | 日期/决策/原因/放弃/影响（追加） |
| 新缺陷发现 | `docs/问题清单-20260731.md` | 登记 D 编号/优先级/证据（行号） |
| **新功能/阶段完成** | **`README.md` + `PROJECT_CONTEXT.md`** | **README：更新项目亮点/功能清单/源文件数/测试数/API 数量；PROJECT_CONTEXT：更新已完成/未完成/最大问题/文件索引** |
| 文档过时 | 原文档顶部标废弃 + 指向替代 | 见下方废弃规则 |

**废弃管理规则（如何提醒 AI 跳过不需要的内容）：**
1. **过时文档**：顶部加 `> ⚠️ 已废弃（YYYY-MM-DD）：内容已被 X 替代，勿读`——AI 看到标记即跳过（例：`测试方案-全功能覆盖.md` v1 已被 `-v2.md` 替代）
2. **临时文件**（一次性验证脚本/日志）用后即删，不留 archive；确需留证 → 移入 `docs/archive/` 并加废弃标记
3. **`.aiignore`** 已列 AI 默认不读项（target/日志/DB/缓存）；新增大噪音目录时同步更新
4. **删除文件需主人确认**（roleplay-v4 曾从回收站恢复，差点删没）
5. AI 接手工期：先读 `PROJECT_CONTEXT.md` 的文件索引——只有索引内的文件是"活的"，索引外默认废弃

## 🧭 路由（沿用 workspace AGENTS.md）

- 复杂任务 spawn 特化子 agent（coder/researcher/writer/analyst/weixun），不自己硬扛
- 同一方向失败 ≥3 次 → 转 researcher 搜方案
- 每轮工具调用 ≥3 次前评估是否该路由
