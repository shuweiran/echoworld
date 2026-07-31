# CLAUDE.md — Claude Code 接入指引

> 本文件是 Claude Code（或其他遵循 CLAUDE.md 约定的 AI 编码助手）的**自动加载入口**。

## ⚠️ 开工必读（按顺序，缺一不可）

1. **`PROJECT_CONTEXT.md`** — 项目速览（目标/阶段/架构/已完成/未完成/当前最大问题/文件索引）
2. **`DECISION_LOG.md`** — 架构决策史（为什么这么设计；改代码前必查，避免推翻历史决策）
3. **`AGENTS.md`** — 项目协作规则（硬性约束：禁 spring-boot:run / 禁 git commit / 改码登记 / 测试更新台账）

按任务需要追加：`TEST_STATUS.md`（测试现状）/ `docs/问题清单-20260731.md`（已知缺陷）/ `docs/剧本杀差距分析-待办.md`（剧本杀蓝图）

## 🔒 硬性约束（违反即失败）

- 8000 端口有运行中后端：**只准 `mvn compile/test`，禁止 `spring-boot:run`**；测试用 RANDOM_PORT 隔离
- **不要 git commit**（等主人确认）
- 修改代码后必须登记 `docs/修改记录.md`；测试通过后必须更新 `TEST_STATUS.md`
- 不要动 RouterService / ArbiterService / 审批 / 狼人杀 / SSE 主链路 / static/（除非任务明确要求）
- 系统 mvn：`C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd`
- 中文交流；PowerShell 发中文 JSON 会 GBK 乱码 → 用 Python（UTF-8）

## ⚠️ 并行工作流预警（2026-07-31）

曾有另一主会话并行修 P0 缺陷（改过 AuthController/VoiceController/HistoryController/ScriptGameService 等）。
→ **动 `src/main/java` 前先 `git diff` 确认基线**。

## 📝 文档维护协议（持续更新，不积压）

| 动作 | 必须更新 |
|---|---|
| 改代码 | `docs/修改记录.md`（修改人/时间/文件/摘要/核查） |
| 跑测试 | `TEST_STATUS.md`（追加历史+汇总；失败也写） |
| 新架构决策 | `DECISION_LOG.md`（日期/决策/原因/放弃/影响） |
| 新缺陷 | `docs/问题清单-20260731.md`（D 编号/优先级/证据行号） |
| **新功能/阶段完成** | **`README.md`（亮点/规模/徽章）+ `PROJECT_CONTEXT.md`（已完成/未完成/最大问题）** |
| 文档过时 | 顶部加 `> ⚠️ 已废弃：被 X 替代，勿读` |

**废弃管理**：临时文件用后即删；`.aiignore` 列默认不读项（target/日志/DB/缓存）；**删除文件需主人确认**。
