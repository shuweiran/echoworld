# TEST_STATUS.md — 测试状态台账（AI 必读③，持续更新）

> ⚠️ **规则**：每次执行测试后**必须更新本文件**（追加记录 + 更新汇总）。测试通过就写入，失败也写（含原因），保持诚实。
> 执行命令：`cd D:\roleplay-java && C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd test`

---

## 📊 当前基线（最新汇总）

| 指标 | 值 |
|---|---|
| 测试类 | **15** |
| 测试用例 | **93** |
| Failures / Errors | **0 / 0** |
| 最后全量执行 | 2026-07-31 14:42 |
| 环境 | H2 mem + mock LLM（application-test.yml） |

---

## 测试类明细（93 tests / 15 类）

| 测试类 | 用例 | 状态 | 对应 Phase/需求 |
|---|---|---|---|
| ApprovalServiceTest | 11 | ✅ | 审批门 |
| RouterServiceHooksTest | 11 | ✅ | Hook 系统 |
| McpServiceTest | 10 | ✅ | MCP |
| TrackStrategyTest | 7 | ✅ | Phase 2 |
| SimulationOrchestratorTest | 4 | ✅ | Phase 3 |
| TrackDirectorServiceTest | 10 | ✅ | Phase 3 |
| WorldDirectorServiceTest | 7 | ✅ | Phase 3 |
| MovementConstraintTest | 11 | ✅ | Phase 4 |
| InteractionDetectorTest | 7 | ✅ | Phase 1 |
| SpatialTrackResolverTest | 6 | ✅ | Phase 1 |
| **LongTextStabilityTest** | 1 | ✅ | **LONG-01（需求硬性）** |
| **CompressorChainTest** | 1 | ✅ | LONG-03 |
| **DatabaseServiceTest** | 1 | ✅ | LONG-02 |
| **InteractionDetectorBoundaryTest** | 2 | ✅ | R-1 阈值 40 边界 |
| **TrackDirectorSecretOverrideTest** | 4 | ✅ | #45 秘密强制 ISOLATED |

---

## 超长文本稳定性（需求文档第十条硬性要求）

| 用例 | 参数 | 结果 | 耗时 |
|---|---|---|---|
| LONG-01 | 500 轮 × 200 字 = **10 万字上下文**，mock LLM | ✅ 无 OOM/无卡死/无丢失 | 5.09s |
| LONG-02 | 5000 条日志批量插入（H2 mem） | ✅ | 15.63s |
| LONG-03 | 50 轮摘要链 + 10 万字降级 | ✅ 三要素保留/压缩率≥50% | 0.77s |

---

## 📝 执行历史（追加式）

### 2026-07-31 16:50 — 运行时验证：D14 重启恢复 + D1 中断系统 + 回归冒烟（非 mvn test）
- **方式**：java -jar 启动生产实例（H2 file 库，端口 8000），HTTP 实测，未改 src/main 代码
- **D14 重启恢复**：✅ PASS——标记角色/场景落 H2，重启后完整恢复（characters=4 含标记，scenes=3 含标记）
- **D1 中断系统**：✅ 端点存在性/即时返回/状态机/硬中断链路 PASS；⚠️ 真实 LLM 生成中软停止路径受 G1（LLM 401）阻塞，如实记录未伪装
- **回归冒烟**：24/25 PASS（唯一偏离：/api/auth/me 无 token 返 400 非 401，见 D23）
- **新发现缺陷**：D21（interrupt/tasks 无参默认按 GENERATION 过滤）/ D22（LLM 失败误报「生成已中断」）/ D23 / D24，已登记问题清单 H.6，待主 agent 决策
- **报告**：`docs/test-results/verify-d14-d1-20260731-1650.md`（含全部请求/响应证据）
- **实例**：验证完成后已停止，8000 端口释放

### 2026-07-31 14:42 — 全量回归（Round 0-1）
- **命令**：`mvn test`（application-test.yml 生效）
- **结果**：93 tests / 0 failures / 0 errors / 15 类
- **新增**：LongTextStabilityTest / CompressorChainTest / DatabaseServiceTest / InteractionDetectorBoundaryTest / TrackDirectorSecretOverrideTest（5 类 9 用例）
- **验证**：surefire 报告逐类核对（F:0 E:0），LONG-01 源码头注释核对参数（ROUNDS=500、断言 <10s）
- **报告**：`docs/test-results/测试报告-全功能覆盖.md`

---

## 测试方案对照（55 项覆盖矩阵状态）

| 区域 | 覆盖 | 缺口（P0 待补） |
|---|---|---|
| 核心引擎（#1-27） | 审批/Hook/MCP/压缩链 | **SessionController / AuthController / ConfigController** 零 @SpringBootTest |
| 2D 模拟（#28-38） | Track 6 类 + Movement | **SimulationWorld / SpatialGrid / HearingSystem / ConversationManager** 零单元测试 |
| 铁轨（#39-48） | ✅ 全覆盖（Track/导演/编排/约束） | EavesdropSummarizer LLM 分支待补 |
| 前端（#49-55） | 手动验证（2D 页实测通过） | Vitest 基建缺失 |
| 游戏状态机 | 无 | **WerewolfService / ScriptGameService** 全流程测试待落地 |

## 已知测试环境注意
- **G1 LLM 401**：真实 LLM 用例不可跑（现网 key 无效），验收一律 mock；真实对局人工验收延后
- **G2 8000 端口**：禁止 spring-boot:run；测试用 RANDOM_PORT 隔离
- **PowerShell 中文 JSON → GBK 乱码**：用 Python UTF-8 发请求
- 测试必须串行（并发互相干扰，Python 版项目教训同样适用）
