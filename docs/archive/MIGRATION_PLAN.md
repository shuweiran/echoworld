# Roleplay v4 → Java 全量迁移计划

基于 `ARCHITECTURE_DOC.md`（924 行架构分析） + 现有 8 个 Java 文件。

---

## 📊 迁移基线

| 指标 | Python | Java 已迁移 | 待迁移 |
|------|--------|------------|--------|
| 后端代码行 | ~7500 | ~800（8 文件） | **~6700** |
| 后端文件数 | 53 | 8 | **~45** |
| 前端代码行 | ~4500 | 不动 | 只改 API baseUrl |
| 有效 API 端点 | ~50 | 0 | **~50** |
| 死代码（跳过） | ~800 | — | — |

---

## 迁移策略

**四不动原则：**
1. 不动前端 — React/Vite 项目保持原样，只改 `api/client.ts` 里的 `baseUrl`
2. 不动数据格式 — JSON 序列化字段名与 Python 一致（snake_case）
3. 不动 SSE 事件流格式 — 前端 useSSE.ts 无需改动
4. 不动 Java 已迁移文件 — Message/Persona/Track/TrackConfig 等 POJO 只补充

**Java 侧用 Virtual Thread 解决 Python 串行 agent 的性能瓶颈**（已在 AgentExecutor 中实现）。

---

## Phase 1 — 核心引擎（Core Engine）
> 目标：替换 Python 的 core/ 目录（~4200 行）

| # | 模块 | Python 源 | Java 目标 | 行数预估 | 依赖 |
|---|------|-----------|-----------|---------|------|
| 1.1 | **RouterService** | `core/router.py`（2200 行，最大屎山） | `service/RouterService.java` | ~800 | 依赖 Phase 1 所有模块 |
| 1.2 | **ArbiterService** | `core/arbiter.py`（350 行） | `service/ArbiterService.java` | ~200 | 依赖 LLMClient + Persona |
| 1.3 | **MemoryStore** | `core/memory.py`（250 行） | `service/MemoryStore.java` | ~200 | 依赖 Message + Track |
| 1.4 | **Compressor** | `core/compressor.py`（130 行） | `service/Compressor.java` | ~100 | 依赖 LLMClient |
| 1.5 | **Monitor** | `core/monitor.py`（160 行） | `service/Monitor.java` | ~100 | 独立 |
| 1.6 | **Validator** | `core/validator.py`（180 行） | `service/Validator.java` | ~100 | 依赖 Persona |
| 1.7 | **AI 生成器** | Arbiter 中抽取 | `service/GeneratorService.java` | ~100 | 依赖 LLMClient |

**跳过（死代码，不迁移）：**
- `core/scheduler.py`（130 行 — 已被 AgentExecutor 替代）
- `core/track_manager.py`（180 行 — 从未使用）
- `core/i18n.py`（200 行 — 从未使用）
- `core/track_request.py`（本质空壳）
- `core/lorebook.py`（半成品，从未实际执行）
- `core/memory.py` 的 `ShardedMemory`（定义但未用）

---

## Phase 2 — API 路由层（REST Controllers）
> 目标：替换 Python 的 api/ 目录（~1800 行，13 个路由文件）

| # | Controller | Python 源 | 端点数 | 行数预估 |
|---|-----------|-----------|--------|---------|
| 2.1 | **SessionController** | `routes_session.py`（最大，700+ 行） | 12+ | ~250 |
| 2.2 | **CharacterController** | `routes_characters.py` | 6 | ~80 |
| 2.3 | **SceneController** | `routes_scenes.py` | 7 | ~80 |
| 2.4 | **RoundController** | `routes_round.py` | 3 | ~50 |
| 2.5 | **HistoryController** | `routes_history.py` | 4 | ~80 |
| 2.6 | **ConfigController** | `routes_config.py` | 8 | ~120 |
| 2.7 | **AuthController** | `routes_auth.py` | 4 | ~80 |
| 2.8 | **RoomController** | `routes_room.py` | 6 | ~100 |
| 2.9 | **VoiceController** | `routes_voice.py` | 3 | ~50 |
| 2.10 | **SSEController** | `routes_sse.py` | 1 | ~80 |
| 2.11 | **TrackController** | `routes_track.py` | 2 | ~30 |
| 2.12 | **WerewolfController** | 从 SessionController 提取 | 4 | ~80 |

---

## Phase 3 — 基础设施服务（Services）
> 目标：替换 Python 的 services/ 目录（~800 行）

| # | Service | Python 源 | 行数预估 |
|---|---------|-----------|---------|
| 3.1 | **PersistenceService** | `services/persistence.py`（AtomicFileStorage） | ~120 |
| 3.2 | **SessionManager** | `services/session_manager.py` | ~80 |
| 3.3 | **TtsService** | `services/tts_service.py`（Edge TTS + CosyVoice） | ~150 |
| 3.4 | **PrivateChatService** | `services/private_chat.py` | ~30 |
| 3.5 | **WhisperService** | `services/whisper_service.py` | ~50 |
| 3.6 | **InviteService** | `services/invite_service.py` | ~60 |
| 3.7 | **WebSearchService** | `services/web_search.py` | ~50 |

---

## Phase 4 — 游戏系统（Games）
> 目标：替换 Python 的 games/ + werewolf_*（~1500 行）

| # | 模块 | Python 源 | 行数预估 | 说明 |
|---|------|-----------|---------|------|
| 4.1 | **WerewolfEngine** | `games/werewolf_engine.py` + `games/schema.py` | ~300 | 规则引擎，纯函数 |
| 4.2 | **WerewolfArbiter** | `core/werewolf_arbiter.py` | ~80 | LLM 仲裁 |
| 4.3 | **WerewolfApi** | `core/werewolf_api.py` | ~150 | 规则 API |
| 4.4 | **WerewolfGame** | `core/werewolf_game.py`（1100 行，mixin） | ~400 | 拆为独立 Service |
| 4.5 | **ScriptRuntime** | `core/script_runtime.py` | ~30 | 🚧 基本为空 |

---

## Phase 5 — 配置 & 启动（Config & Bootstrap）
> 目标：Spring Boot 配置 + 前端静态资源服务

| # | 模块 | 说明 |
|---|------|------|
| 5.1 | **Spring Boot Application** | 主入口 + CORS + JSON 配置 |
| 5.2 | **application.yml** | 端口、数据目录、LLM 默认参数 |
| 5.3 | **静态资源映射** | 开发代理到 Vite dev server，生产 serve dist/ |
| 5.4 | **异常处理** | @ControllerAdvice 统一错误格式 |

---

## 总计工作量

| Phase | 文件数 | 行数预估 |
|-------|-------|---------|
| Phase 1 — 核心引擎 | 7 | ~1500 |
| Phase 2 — API 层 | 12 | ~1000 |
| Phase 3 — 服务层 | 7 | ~540 |
| Phase 4 — 游戏系统 | 5 | ~960 |
| Phase 5 — 配置启动 | 4 | ~200 |
| **总计** | **35** | **~4200** |

迁移行数约原 Python 代码的 56%（因为跳过死代码 + Java 比 Python 表达力相当但类型更冗长）。

---

## 执行顺序

```
Phase 1 (引擎) ─→ Phase 3 (服务) ─→ Phase 2 (API) ─→ Phase 4 (游戏) ─→ Phase 5 (配置)
                                                          │
                     ┌────────────────────────────────────┘
                     ▼
               前端只改 client.ts 里的 baseUrl
```

**原因：** 引擎是心脏，服务是血管，API 是皮肤——先让心脏跳起来。

---

## 关于 OpenClaw

不需要。这次是全量迁移，一致性要求高——35 个文件相互依赖，风格需要统一，拆给 OpenClaw 做反而增加 merge 成本。
但如果某个阶段有大量机械性的代码（比如重复的 CRUD Controller），可以交给它做。
