# Testing Strategy

EchoWorld 的测试目标不是追求数字，而是保护空间仿真和信息隔离不变量。

## 测试分层

```text
Unit
  deterministic domain rules; no network or real LLM

Integration
  Spring context + H2 memory database + fake/mock LLM

Contract
  API payloads, map schema, SSE/session boundaries

Optional E2E
  local packaged runtime; real provider only when explicitly enabled
```

默认 `mvn test` 不应依赖真实模型服务或 API Key。

## 核心不变量

| 不变量 | 主要测试 |
|---|---|
| 距离产生 `MERGED/WEAK/ISOLATED` | `SpatialTrackResolverTest` |
| `WEAK` 只有摘要、`ISOLATED` 无原文 | `TrackStrategyTest` |
| 隔音墙阻断互听 | `HearingSystemObstacleTest` |
| 封闭楼层隔离、楼梯声学 hop 与秘密 Track | `FloorAwareWorldTest` |
| 发言门控规则确定且可配置 | `SpeechGateTest` |
| 固定负载下生成候选削减量可复现 | `SpeechGateEvaluationTest` |
| 地图契约非法数据被拒绝 | `MapValidatorTest`, `MapContractTest` |
| 移动遵守障碍和世界边界 | `ObstacleCollisionGridTest`, `MovementSystemNavigationTest` |
| 跨层路线只经合法 connector 且失效重规划 | `MultiFloorNavigationServiceTest`, `MultiFloorMovementIntegrationTest` |
| Speech Commit 使用落地时楼层/connector 状态 | `SpeechDecisionAndPerceptionTest` |
| 2D/3D 投影不修改权威位置、路径或 Track | `frontend/tools/floor-projection.test.mjs` |
| 秘密角色的 Track 不被意外升级 | `TrackDirectorSecretOverrideTest` |
| 多会话数据互不泄漏 | `ScriptGameMultiSessionIsolationTest` |
| 空间/核心规则不反向依赖 Web controller | `ArchitectureTest` |

## 推荐命令

```bash
mvn test
```

核心空间链路的快速验证：

```bash
mvn -Dtest=SpatialTrackResolverTest,TrackStrategyTest,HearingSystemObstacleTest,SpeechGateTest test
```

前端：

```bash
cd frontend
npm ci
npm run test:projection
npm run build
npm run lint
```

## LLM 隔离原则

- 距离、碰撞、Track、权限和状态机使用确定性测试；
- 对话策略测试通过 fake/mock 返回固定内容；
- Provider 合约通过 mock HTTP 响应验证；
- 真实 LLM 只作为可选人工/E2E 验证，不进入常规 CI 成败条件。

## 当前缺口

- SpeechGate 已有固定候选数评测，但尚缺真实模型 token、延迟和质量对比；
- 前端以类型检查和构建为主，核心 store/component 测试不足；
- 已有 3 floors / 30 agents / 10 connectors / 1000 ticks 的确定性趋势基线；生产规模、长时在线与多机性能边界仍待建立；
- PostgreSQL 仅是未来可选 profile，当前不宣称生产数据库验证。
