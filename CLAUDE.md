# CLAUDE.md — EchoWorld Claude Code 接入指引

> 本文件是 Claude Code（或其他遵循 CLAUDE.md 约定的 AI 编码助手）的**自动加载入口**。

## ⚠️ 必读顺序（缺一不可）

1. **`PROJECT_CONTEXT.md`** — 项目速览（5 秒进入状态：目标/阶段/架构/已完成/未完成/最大问题/文件索引）
2. **`@AGENTS.md`** — 项目协作规则（硬性约束 / 文档维护协议 / 并行作业登记，**全部以 AGENTS.md 为唯一事实源**）
3. **`DECISION_LOG.md`** — 架构决策史（为什么这么设计；改代码前必查）

按任务需要追加：`TEST_STATUS.md`（测试现状）/ `docs/问题清单-20260731.md`（已知缺陷）/ `docs/剧本杀差距分析-待办.md`（剧本杀蓝图）

## 🔗 规则唯一来源：@AGENTS.md

> 本文件**不再重复** AGENTS.md 中的硬性规则（禁 spring-boot:run / 禁 git commit / 改码登记 / 测试后更新台账 / 并行作业登记 / 文档维护协议等），直接导入 `@AGENTS.md` 即可获得全部规则，避免规则漂移。

## 📌 Claude 特有提示

- 开工前先读 `docs/INDEX.md`（文档地图，按场景定位要读的文档）；改文件前先读 `docs/并行作业登记.md`（并行占用检查）
- 中文交流；PowerShell 发中文 JSON 会 GBK 乱码 → 用 Python（UTF-8）
