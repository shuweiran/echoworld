# EchoWorld

**Spatial Multi-Agent Simulation Engine**

EchoWorld 是一个 Java 21 / Spring Boot / React / Phaser 项目：多个 AI Agent 位于同一二维世界，位置、距离、听觉和障碍决定它们能否互动，以及各自能够获得哪些上下文。

![EchoWorld overview](docs/social-preview.svg)

## What it demonstrates

1. **Spatial World** — 后端维护坐标、碰撞、地图、移动和会话的权威状态；前端只渲染和提交输入。
2. **Hearing** — `HearingSystem` 结合距离、听觉范围和声学障碍计算可听关系。
3. **SpeechGate** — 先以确定性规则决定“谁应当说话”，再调用 LLM 生成必要语言内容。
4. **Context Isolation** — 空间关系映射为 `MERGED`（完整上下文）、`WEAK`（受限主题摘要）和 `ISOLATED`（无会话信息）。

狼人杀、剧本杀和自由角色扮演仅是验证隐藏信息与社会互动边界的示例场景。

## Architecture

```text
React / Phaser → REST + SSE → Spring adapters → Simulation runtime
                                             ├─ deterministic world rules
                                             └─ LLM adapters for language only
```

核心调用链为：`SimulationOrchestrator` → `SpatialTrackResolver` → `ConversationManager` → `SpeechGate` → `TrackStrategy` → `LLMClient`。详细职责、依赖边界和已知技术债见 [Architecture](docs/architecture.md)。

## Verify the core claims

- 隔离 Agent 不得收到私密会话原文；
- `WEAK` 旁听者不接收秘密关键词；
- 距离与隔音墙会改变 Track；
- 非法地图被拒绝或走确定性降级；
- SSE 重连不泄漏其他会话状态。

对应入口：[Context Track](docs/concepts/context-routing.md)、[Testing](docs/testing.md)、[Evaluation](docs/evaluation.md)。固定三角色演示规范见 [Three-agent demo](docs/demo-three-agent.md)；它目前是可复现规格，公开录屏仍在 Roadmap 中。

## Quick start

Prerequisites: JDK 21、Maven 3.9+；前端开发另需 Node.js 20+。

```bash
git clone https://github.com/shuweiran/roleplay-java.git
cd roleplay-java
mvn test
mvn package -DskipTests
java -jar target/roleplay-engine-0.1.0.jar
```

Open `http://localhost:8000`. LLM credentials are supplied by environment variable, for example `ROLEPLAY_LLM_API_KEY`; normal test runs use mock/fallback clients and do not require a real provider.

```bash
cd roleplay-v4/frontend
npm ci
npm run build
```

## Scope and limitations

- Designed for small-to-medium Agent populations; large-scale capacity still needs profiling.
- Language generation is nondeterministic; movement, permissions, collision, Track and game state are deterministic.
- H2 is the local single-machine persistence choice, not a production database claim.
- This is not a GIS or remote-sensing production system: CRS, GeoJSON/Shapefile, raster processing and hydrological models are not implemented.
- `SimulationService`, `SimulationController` and `ConversationManager` remain planned responsibility-splitting targets.

## Roadmap

- Record the 20–30 second three-agent spatial-context demo.
- Add architecture-dependency tests and focused frontend state tests.
- Publish capacity and latency baselines for small/medium simulations.

## Assets and license

Source code is [MIT](LICENSE). Dependencies and any retained third-party assets keep their own licenses. On 2026-08-25, two audit-confirmed third-party tiles lacking repository attribution were removed rather than relicensed implicitly. New distributable assets must have documented provenance and license terms.
