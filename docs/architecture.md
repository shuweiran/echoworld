# EchoWorld Architecture

本文面向第一次进入仓库的开发者，目标是在 15 分钟内找到核心实现。内部批次记录、历史测试和调研材料不属于本文范围。

## 设计边界

EchoWorld 将确定性空间规则与非确定性语言生成分开：

```text
┌──────────────────────────────────────────────────────────┐
│ React / Phaser / Babylon / Unity                         │
│ rendering, input, observable state                       │
└───────────────────────┬──────────────────────────────────┘
                        │ REST / SSE
┌───────────────────────▼──────────────────────────────────┐
│ Spring web adapters                                     │
│ controllers, session boundary, authorization            │
└───────────────────────┬──────────────────────────────────┘
                        │
┌───────────────────────▼──────────────────────────────────┐
│ Simulation runtime                                      │
│ world lifecycle → movement → perception → conversation  │
└──────────────┬──────────────────────────┬────────────────┘
               │                          │
┌──────────────▼──────────────┐  ┌────────▼───────────────┐
│ Deterministic domain       │  │ LLM adapters           │
│ grid, collision, hearing,  │  │ dialogue, high-level   │
│ tracks, permissions, state │  │ intent, summarization  │
└──────────────┬──────────────┘  └────────────────────────┘
               │
┌──────────────▼───────────────────────────────────────────┐
│ Persistence / events                                    │
│ H2, repositories, SSE event delivery                    │
└──────────────────────────────────────────────────────────┘
```

前端不能直接改写权威世界状态；LLM 不能直接改写坐标、权限、Track 或游戏状态。

## 核心调用链

`SimulationOrchestrator.tick()` 负责协调，不应拥有具体领域算法：

1. `WorldDirectorService` 提供角色目标；
2. `InteractionDetector` 计算交互强度；
3. `TrackDirectorService` 与 `SpatialTrackResolver` 生成每个 Agent 的 Track；
4. `ConversationManager` 维护对话组；
5. `SpeechGate` 决定本轮发言者；
6. `TrackStrategy` 按 Track 构造完整、摘要或隔离上下文；
7. `LLMClient` 生成被允许的语言内容；
8. `WorldEventBus` 与 `SseBroadcaster` 发布状态变化。

## 包职责

| 包 | 当前职责 | 依赖方向要求 |
|---|---|---|
| `simulation` | 权威世界、tick、移动、听觉、地图与调度 | 不依赖 Web UI |
| `simulation.track` | 空间关系到 Context Track 的纯规则映射 | 只依赖核心状态模型 |
| `simulation.conversation` | 会话组、发言门控、上下文构造 | 消费 Track，不自行复制空间算法 |
| `simulation.director` | 高层目标和轨道编排 | 调用规则组件，不直接输出 Web 响应 |
| `agent` / `core` | Agent、Persona、消息和 Track 基础类型 | 不依赖 controller |
| `service` | 应用用例、会话和场景协调 | 不反向依赖 controller |
| `controller` / simulation controllers | REST/SSE 适配 | 只做输入校验、授权和用例调用 |
| `llm` | OpenAI-compatible 客户端及 mock/fallback | 不拥有世界权威状态 |
| `db` | 实体、仓库和持久化适配 | 不决定仿真规则 |

## 空间单事实源

- 坐标和运动：`SimulationWorld` / `AgentState` / movement components；
- 楼层位置：权威领域使用 `floorId + x + y`；客户端高度只是 projection；
- 邻域：`SpatialGrid`；
- 可听性与声线遮挡：`HearingSystem`；
- Track 分配：`SpatialTrackResolver`；
- 地图结构：`MapContract`；
- 地图合法性：`simulation.map.MapValidator`；
- 导航：`MultiFloorNavigationService` 组合每层 Grid A* 与 connector graph，`MovementSystem` 只消费权威路线。

消费者应调用这些组件，不应各自写一套距离常量或遮挡判断。

跨楼层默认声学隔离，只有开放且标记为 acoustic 的 connector 才传播声音。LLM 生成期间的听众只是候选；发言在 world tick commit 时必须按当时的双方楼层、位置、障碍与 connector 状态重新计算 actual listeners。Phaser、Babylon 与 Unity 均不得直接写 `floorId` 或把本地路径/Transform 作为事实回传。

## 已知技术债

当前最大的职责集中点是：

| 类 | 规模（2026-08-25 审计） | 风险 |
|---|---:|---|
| `ConversationManager` | 约 64 KB | 会话生命周期、Track、策略和事件协调集中 |
| `SimulationService` | 约 52 KB / 995 行 | 世界生命周期、运行时查询和协调职责偏多 |
| `SimulationController` | 约 29 KB | 仿真 API 入口偏集中 |
| `SimulationOrchestrator` | 约 13 KB / 263 行 | 需持续保证只协调、不沉积领域规则 |

治理顺序是“职责分析 → 特征测试 → 小步提取”，不做一次性包名大迁移。

建议拆分目标：`WorldLifecycleService`、`AgentRuntimeService`、`SimulationTickService`、`SimulationQueryService`、`WorldEventPublisher`，控制器再按 world/agent/movement/admin 资源拆分。

## 运行与部署

- Spring Boot 在后端提供 REST/SSE 和静态前端；
- H2 用于当前本地单机运行；
- Maven 测试默认使用内存数据库与 mock LLM；
- React/Phaser 构建产物同步进 Spring 静态资源；
- GitHub Actions 运行后端测试与前端构建。

## 延伸阅读

- [Context Track](concepts/context-routing.md)
- [Testing](testing.md)
- [Glossary](glossary.md)
- [Three-agent demo](demo-three-agent.md)
