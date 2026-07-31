# TEST_STATUS.md — 测试状态台账（AI 必读③，持续更新）

> ⚠️ **规则**：每次执行测试后**必须更新本文件**（追加记录 + 更新汇总）。测试通过就写入，失败也写（含原因），保持诚实。
> 执行命令：`cd D:\roleplay-java && C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd test`

---

## 📊 当前基线（最新汇总，2026-07-31 21:29）

| 指标 | 值 |
|---|---|
| 测试类 | **17** |
| 测试用例 | **131** |
| Failures / Errors | **0 / 0** |
| 最后全量执行 | 2026-07-31 21:29 |
| 环境 | H2 mem + mock LLM（application-test.yml） |

> v2 更新：基线从 93 → **131 tests / 17 类**（并行工作流新增 38 用例：剧本杀 D6/D7、中断系统、DB、模拟、稳定性等）

---

## 测试类明细（131 tests / 17 类）

| 测试类 | 用例 | 状态 | 对应 |
|---|---|---|---|
| ApprovalServiceTest | 11 | ✅ | 审批门（D7） |
| RouterServiceHooksTest | 11 | ✅ | Hook 系统 |
| McpServiceTest | 10 | ✅ | MCP |
| TrackStrategyTest | 7 | ✅ | Phase 2 |
| SimulationOrchestratorTest | 4 | ✅ | Phase 3 |
| TrackDirectorServiceTest | 10 | ✅ | Phase 3 |
| WorldDirectorServiceTest | 7 | ✅ | Phase 3 |
| MovementConstraintTest | 11 | ✅ | Phase 4 |
| InteractionDetectorTest | 7 | ✅ | Phase 1 |
| SpatialTrackResolverTest | 6 | ✅ | Phase 1 |
| **LongTextStabilityTest** | 1 | ✅ | LONG-01（需求硬性） |
| **CompressorChainTest** | 1 | ✅ | LONG-03 |
| **DatabaseServiceTest** | 1 | ✅ | LONG-02 |
| **InteractionDetectorBoundaryTest** | 2 | ✅ | 阈值 40 边界 |
| **TrackDirectorSecretOverrideTest** | 4 | ✅ | 秘密强制 ISOLATED |
| **ScriptGameServiceTest** | 12 | ✅ | **剧本杀 D6/D7（平票重投/非法票/揭晓审批）** |
| **新增稳定性/中断/DB 测试** | ~26 | ✅ | 并行工作流新增（中断系统/DB/模拟） |

---

## 超长文本稳定性（需求文档第十条硬性要求）

| 用例 | 参数 | 结果 | 耗时 |
|---|---|---|---|
| LONG-01 | 500 轮 × 200 字 = **10 万字上下文**，mock LLM | ✅ 21:28 复跑 PASS：P50=4ms P95=9ms Max=72ms，堆增长 -3.7%，无 OOM/卡死/锚点可检索 | ~5s |

---

## 📝 执行历史（追加式）

### 2026-07-31 14:42 — 全量回归（Round 0-1，93 tests）
- 新增 5 类 9 用例（LONG-01/02/03、阈值边界、secret override）全绿

### 2026-07-31 21:28-21:29 — 全量回归（Round 2，131 tests）
- **命令**：`mvn test`（全量）
- **结果**：131 tests / **0 failures** / 17 类
- **首跑 1 失败**：`ApprovalServiceTest.testGetPendingResult`（expected not null）——**确认偶发时序**：测试用 `CompletableFuture.supplyAsync` + `Thread.sleep(100)`，慢机器上 future 未在 100ms 内执行到 put；单独复跑 11/11 通过，全量重跑 0 失败
- **记录**：该测试时序脆弱（sleep(100) 依赖调度），建议后续改 CountDownLatch 同步等待（P2 测试加固）
- **LONG-01**：P50=4ms P95=9ms 堆稳定 ✅

### 2026-07-31 17:56 — 并行工作流提交（未在本台账登记，补记）
- 剧本杀 D6/D7 测试（ScriptGameServiceTest 12 用例）+ D15 stress 脚本 + 中断系统测试（提交 bdf0d59/17941da）

---

## 测试方案对照（覆盖矩阵状态）

| 区域 | 覆盖 | 缺口 |
|---|---|---|
| 核心引擎 | 审批/Hook/MCP/压缩链/DB | SessionController/AuthController/ConfigController 集成测试待补 |
| 2D 模拟 | Track 6 类 + Movement + 编排 | SimulationWorld/SpatialGrid/HearingSystem 单元测试待补 |
| 铁轨 | ✅ 全覆盖 | EavesdropSummarizer LLM 分支 |
| 剧本杀 | ✅ D6/D7 覆盖（12 用例） | 讨论接对话引擎（Step 3v）测试待补 |
| 狼人杀 | 无 | WerewolfService 状态机全流程测试待落地 |
| 前端 | 手动验证（2D 页实测通过） | Vitest 基建缺失 |

## 已知测试环境注意
- **G1 LLM key**：用户级环境变量 `ROLEPLAY_LLM_API_KEY`（len=35）+ 运行时 POST /api/config/apikey 注入；**AppConfig 不读 yml（D20/G1 根治待办）**，重启后端需重新注入
- **G2 8000 端口**：禁止 spring-boot:run；测试 RANDOM_PORT 隔离
- **PowerShell 中文 JSON → GBK 乱码**：用 Python UTF-8 发请求
- 测试必须串行；`ApprovalServiceTest.testGetPendingResult` 偶发时序脆弱（P2 加固）
