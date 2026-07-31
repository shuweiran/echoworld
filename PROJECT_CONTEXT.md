# PROJECT_CONTEXT.md — Roleplay-Java 项目速览（AI 必读①）

> ⚠️ **所有 agent 开工前必读**：① 本文件（5 秒速览）→ ② `DECISION_LOG.md`（为什么这么设计）→ ③ 按任务需要读 `TEST_STATUS.md` / `docs/问题清单-20260731.md`。

## 一句话目标
开发一个 **Java 多 Agent 角色扮演引擎**：2D 空间 × 铁轨系统（Track System）融合的实时社会模拟，支持狼人杀/剧本杀双游戏。

## 当前阶段
- ✅ Phase 1-4 完成（Track 融合全链路：SpatialTrackResolver → TrackStrategy → 双导演 → MovementConstraint）
- 🔄 **剧本杀 P0 开发中**（蓝图 `docs/剧本杀差距分析-待办.md` v2.1，Step 1-5，3-6 天）
- 🟠 问题清单 P0 缺陷并行修复中（另一个主会话「全功能覆盖测试方案」在改，**派单前确认不撞车**）

## 技术栈
| 层 | 技术 |
|---|---|
| Backend | Java 21 + Spring Boot 3.4 + Maven + Spring Data JPA |
| DB | H2（生产 file `./data/roleplay`；测试 mem） |
| AI | DeepSeek API（OpenAI 兼容），mock LLM 用于测试 |
| Frontend | React 18 + Vite + Zustand（`roleplay-v4/frontend`，构建后同步 `static/`） |
| 并发 | Java 21 Virtual Threads |

## 核心架构（数据流）
```
每 tick → SimulationOrchestrator
  → WorldDirectorService（想做什么：目标）
  → InteractionDetector（TrackScore：谁知道什么，阈值 40）
  → TrackDirectorService（轨道分配：MERGED/WEAK/ISOLATED）
  → MovementConstraint（怎么移动：聚集/听觉带/避让）
  → ConversationGroup → TrackStrategy（隔离上下文）→ LLM → SSE
```

## 核心模块
- `simulation/track/`：TrackAssignment / SpatialTrackResolver / InteractionDetector / EavesdropSummarizer
- `simulation/director/`：WorldDirectorService（规则目标）/ TrackDirectorService（轨道分配）
- `simulation/conversation/`：ConversationManager / TrackStrategy（统一 GROUP_DISCUSSION+DEBATE）
- `simulation/movement/`：MovementConstraint（Phase 4）
- `service/`：RouterService（身份锁定）/ ArbiterService（审批脱敏）/ ScriptGameService（剧本杀）/ WerewolfService（狼人杀）
- `controller/`：SimulationController（26 端点）/ SessionController / ScriptController 等 17 个

## 已完成
- [x] Phase 1：Track 最小闭环（距离→MERGED/WEAK/ISOLATED）+ Arbiter 输入脱敏（sanitizeSummaryForArbiter）
- [x] Phase 2：TrackStrategy 统一替换（混合轨道上下文隔离）
- [x] Phase 3：双导演（WorldDirector 规则式 + TrackDirector）+ SimulationOrchestrator
- [x] Phase 4：MovementConstraint 社交移动 + secretAgents REST + track/state
- [x] 测试基建：application-test.yml（RANDOM_PORT + H2 mem + mock LLM）
- [x] 93 tests 全绿（含 LONG-01 超长文本 10 万字，需求硬性要求）

## 未完成（按优先级）
- [ ] 剧本杀 P0 蓝图 Step 1-5（`docs/剧本杀差距分析-待办.md`）——Step 1 Part A 已就位（secrets 存储），仅剩 Part B（Track 隔离）
- [ ] 问题清单 P0：D1 中断系统包 / D4 剧本杀前端 / D9 Whisper 端点 / D12 历史加载 / D19 admin 鉴权（部分可能已被并行会话修复，派单前 git diff）
- [ ] G1：LLM 401（application.yml 缺 api-key，重启后运行时注入丢失）
- [ ] 前端 2D 页接 `/track/state`（轨道可视化）

## 当前最大问题
1. **G1 LLM 401**：现网后端 `configured: False`，真实 LLM 用例不可跑 → 修复：api-key 写死 application.yml
2. **并行工作流**：另一主会话在改同一批文件（ScriptGameService 已改 D5 secrets）→ **任何派单前先 git diff 确认基线**
3. **剧本杀前端零接入**：ScenePage 占位符 + 死代码（L494），8 个 API 全部未封装

## 关键文件索引
| 文件 | 内容 |
|---|---|
| `DECISION_LOG.md` | **架构决策史**（为什么这么设计，AI 必读②） |
| `TEST_STATUS.md` | **测试状态台账**（每次测试后更新） |
| `docs/问题清单-20260731.md` | 全量缺陷 A-G + 问题→文档对照表 H |
| `docs/剧本杀差距分析-待办.md` | 剧本杀 P0 开发蓝图 v2.1 |
| `docs/剧本杀调研报告-raw.md` | 剧本杀源码级调研（行号取证） |
| `docs/测试方案-全功能覆盖-v2.md` | 55 项覆盖矩阵 + 110 端点总账 |
| `需求文档-完整需求.md` | 原始需求（Track 融合/中断系统/测试要求） |
| `docs/修改记录.md` | 修改台账（谁改了什么，核查状态） |

## 硬性约束
- 8000 端口有运行中后端：**只准 `mvn compile/test`，禁止 `spring-boot:run`**（测试用 RANDOM_PORT 隔离）
- 系统 mvn：`C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd`
- 不要 git commit（等主人确认）
- 修改代码后**必须登记** `docs/修改记录.md`；测试通过后**必须更新** `TEST_STATUS.md`
- PowerShell 发中文 JSON 会 GBK 乱码 → 用 Python（UTF-8）
- 中文交流
