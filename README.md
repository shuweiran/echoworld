<p align="center">
  <img src="https://img.shields.io/badge/Java-21-%23ED8B00?logo=java" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4-%236DB33F?logo=springboot" alt="Spring Boot 3.4"/>
  <img src="https://img.shields.io/badge/Maven-3.9-%23C71A36?logo=apachemaven" alt="Maven"/>
  <img src="https://img.shields.io/badge/Virtual%20Threads-Parallel-%2300BFFF" alt="Virtual Threads"/>
  <img src="https://img.shields.io/badge/H2-Database-%23007396" alt="H2 Database"/>
  <img src="https://img.shields.io/badge/tests-93%20%E2%9C%93-%2328A745" alt="93 tests"/>
</p>

<h1 align="center">🎭 Roleplay Engine — Java</h1>

<p align="center">
  <b>多 Agent 角色扮演对话引擎</b>：2D 空间模拟 × 铁轨系统（Track System）× 狼人杀 / 剧本杀双游戏<br/>
  当前规模：main 97 个源文件 / 约 11,353 行 · 17 个 Controller / ~110 个 HTTP 端点 · test 15 个测试类 / 93 用例
</p>

---

> ## 🤖 AI 接入指引（AI 助手 / 编码工具必读）
>
> 开工前按顺序阅读：**① `PROJECT_CONTEXT.md`**（项目速览）→ **② `DECISION_LOG.md`**（架构决策史，改码前必查）→ **③ `AGENTS.md` / `CLAUDE.md`**（协作规则与硬性约束）→ 按任务需要追加 **`docs/问题清单-20260731.md`**（缺陷 D1-D24 与状态）/ `TEST_STATUS.md` / `docs/测试方案-全功能覆盖-v2.md`。
>
> ⚠️ **硬性约束**：8000 端口有运行中后端，只准 `mvn compile/test`，**禁止 `spring-boot:run`**；**禁止 git commit**（需主人授权）；对 `D:\roleplay-java` 的任何文件修改必须登记 `docs/修改记录.md`。

---

## 📌 项目简介

Java 实现的多 Agent 角色扮演引擎：AI 角色在 **2D 空间**中移动、感知、对话，由 **铁轨系统** 动态控制角色间的上下文可见性（共享 / 窃听 / 隔离），支持 **狼人杀**、**剧本杀** 两种规则游戏与 **自由角色扮演**。项目由 Python 版（`roleplay-v4/backend`，已全量迁移，保留作参考）迁移而来，遵循四不动原则（前端 / 数据格式 snake_case / SSE 事件格式 / 已迁移 Java 文件）。

核心特性：

- **🚄 铁轨系统**：MERGED（共享上下文）/ WEAK（同步但静默）/ ISOLATED（完全隔离）三模式，LLM Arbiter 每轮动态决策轨道分配；demo 实测信息泄露率 100% → 0%（WEAK 隔离），prompt token ↓14%
- **🗺️ 2D 空间模拟**：200×200 网格、A* 寻路、听觉范围、情绪状态、4 种对话策略 + 双导演（World / Track Director）编排
- **🐺 狼人杀**：完整规则引擎（昼夜循环 / 角色技能 / 胜负判定 / 人类玩家混入 / 视角脱敏）
- **📜 剧本杀**：LLM 生成剧本 → 秘密发放 → 搜证 → 讨论 → 投票 → 揭晓
- **⚡ 中断系统**：三种停止类型（HARD / SOFT / STATE_INVALID）+ 任务状态机 + 事件总线（2026-07-31 落地）
- **✅ 审批门**：Auto-approve / Manual review / Timeout auto-reject
- **🔌 扩展**：MCP（Stdio 客户端）、网页搜索（Brave）、语音（Edge TTS / CosyVoice / Qwen-TTS + Whisper 识别）、私聊
- **🧠 记忆**：短期窗口 + 压缩链摘要（角色指纹 + 结构化摘要）
- **⚙️ 并发**：Java 21 Virtual Threads，多 Agent 并行调用 LLM（耗时 ≈ 最慢者）

---

## 🧱 技术栈

| 层 | 技术 |
|-----|------|
| 语言 | Java 21（Records / Switch Expressions / Virtual Threads） |
| 框架 | Spring Boot 3.4.0（WebMVC / SseEmitter / DI）+ Spring Data JPA |
| 构建 | Maven 3.9（系统 3.9.8；仓库内置 3.9.16） |
| 数据库 | H2（生产 `./data/roleplay` 文件库；测试 `mem:testdb`） |
| 序列化 | Jackson（snake_case 与 Python 版一致） |
| LLM | DeepSeek API（OpenAI 兼容，可配置任意兼容端点）；Java HttpClient，2 模型 × 2 次重试，超时 60s |
| 语音 | Edge TTS / CosyVoice / Qwen-TTS（TtsService）；WhisperService（语音识别） |
| 搜索 | Brave Search API + 网页正文抓取 |
| 前端 | React 19 + TypeScript + Vite 5 + Zustand 5（`roleplay-v4/frontend`，构建产物同步 `src/main/resources/static/`）；2D 独立页 `simulation.html` |
| 测试 | JUnit 5 + Mockito + AssertJ（mock LLM + RANDOM_PORT + H2 内存库隔离） |

---

## 🚀 快速开始

### 前置条件

- **JDK 21+**（Virtual Threads 必需）、**Maven 3.9+**

### 构建与测试

```bash
# 系统 mvn（推荐路径）
& "C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd" clean compile
& "C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd" test

# 或仓库内置 mvn（3.9.16）
.\maven\bin\mvn.cmd clean compile
```

### 启动（⚠️ 注意端口占用）

```bash
mvn spring-boot:run          # 默认端口 8000；浏览器打开 http://localhost:8000
```

> ⚠️ 当前 8000 端口已有运行中实例，**本地开发禁止 `spring-boot:run`**（测试用 RANDOM_PORT 独立实例，不冲突）。

### 前端开发（可选）

```bash
cd roleplay-v4/frontend
npm run dev                  # 5173 端口，自动代理 /api → localhost:8000
npm run build                # 构建产物同步到 src/main/resources/static/
```

### 配置

**LLM API Key**（当前 `application.yml` 缺 api-key → 所有 LLM 调用 401，见 G1）：

```yaml
roleplay:
  llm:
    api-key: "sk-xxx"                # ← 当前缺失，需配置
    api-base: "https://api.deepseek.com"
    model: "deepseek-v4-flash"
```

或运行时设置（重启后丢失）：`POST /api/config/apikey`，body `{"api_key":"sk-xxx"}`（⚠️ api_base / model 字段当前被忽略，见 D20）。

**管理员密钥**（`/api/auth/admin/*` 端点鉴权，2026-07-31 D19 修复引入）：

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `ROLEPLAY_ADMIN_KEY` | `admin-secret-change-me` | 调用 admin 端点须带请求头 `X-Admin-Key: <值>`，否则 403 |

**其他配置**：语言（`/api/config/language`）、模型推荐列表（`/api/config/models`）、语音（`/api/config/voice`，⚠️ POST 为空操作，见 D20）。

---

## 🎮 功能总览

| 模块 | 说明 |
|---|---|
| **自由对话 / 角色扮演** | 任意角色组合 + 场景，Arbiter 动态轨道调度；聊天命令 `/mode` `/goal` `/protagonist` `/restrict` `/stop` `/end` `/status`；私聊（请求/接受/拒绝）；自动 N 轮、回合回滚（上限 50 快照） |
| **铁轨系统** | 三轨道模式动态切换；角色可提交轨道变更申请（断链自动批准 / 增强 LLM 双阶段评估）；Arbiter 输入脱敏（防信息泄露旁路） |
| **2D 空间模拟** | 6 场景（公园/城市/咖啡馆/森林/教室/海滩）、Agent 移动/目标/情绪/听觉范围配置、世界快照 SSE 流、双导演 + 轨道 → 运动约束（质心聚集/听觉带/避让） |
| **狼人杀** | 默认 9 人局（3 狼 + 3 民 + 预言家 + 女巫 + 猎人），状态机 NIGHT → DAY_DISCUSS → DAY_VOTE → JUDGMENT → ENDED；玩家视角脱敏 |
| **剧本杀** | LLM 生成剧本 + 秘密发放（每角色只见自己秘密）→ 搜证（私有/公开线索）→ 讨论 → 投票 → 揭晓；状态机 SETUP → INVESTIGATION → DISCUSSION → VOTE → REVEAL → ENDED |
| **中断系统** | `/api/stop`、`/api/simulation/stop` 可真正中断进行中的 LLM 生成（HARD 硬中断 / SOFT 协作保存 / STATE_INVALID 状态失效） |
| **审批门** | 待审回合批准 / 驳回回滚 / 状态查询（含耗时）；自动、手动、超时自动驳回三模式 |
| **语音** | TTS（Edge TTS / CosyVoice / Qwen-TTS）、语音输入转写 `POST /api/voice/transcribe`（2026-07-31 修复 D9）；⚠️ TTS 事件依赖 SSE 推送，当前主对话 SSE 为死代码（D8） |
| **SSE / 事件流** | `/api/events` 心跳 15s / 超时 300s；⚠️ 前端监听的 30 个事件（agent_output / round_complete 等）后端从不广播（D8），主对话依赖 HTTP 返回体渲染 |
| **前端（React SPA）** | 登录（邀请码）/ 首页（模式选择+设置）/ 场景设置页（角色场景管理 + 狼人杀 tab + 剧本杀 tab + 2D 复选框）/ 聊天页（四类消息、导演面板、历史抽屉、狼人杀/剧本杀面板、语音输入、自动连播、2s 轮询 state）/ 2D 独立页 `simulation.html`（画布渲染 + SSE 实时刷新，支持从主应用带参打开） |
| **其他** | 网页搜索（Brave + 正文抓取）、MCP 工具调用（Stdio）、邀请码认证、多人房间、角色/场景 AI 生成与 CRUD、SPA 兜底路由 |

---

## 🔌 API 概览（~110 端点，按模块）

### 会话与对话 `/api`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/state`、`/api/init` | GET · POST | 系统状态 / 初始化会话（characters、scene、mode） |
| `/api/send`、`/api/stop`、`/api/auto` | POST | 发送消息（执行一轮）/ 停止 / 自动 N 轮 |
| `/api/mode`、`/api/goals` | GET / POST | 模式切换（free/werewolf/script…）/ 剧情目标 |
| `/api/agents`、`/api/agents/{name}` | POST · DELETE | 添加 / 移除 Agent |
| `/api/private_chat/request` `/reply` `/send` | POST | 私聊请求 / 回复 / 发送 |
| `/api/voice/toggle` | GET / POST | 语音开关（⚠️ 恒 true 假实现） |

### 回合 `/api/round`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/round/start` | POST | 开始回合（⚠️ turns 参数不生效，恒 1 轮，见 D13） |
| `/api/round/rollback` | POST | 回滚到指定回合（上限 50 快照） |
| `/api/round/status` | GET | 运行状态 |

### 角色 `/api/characters`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/characters` | GET / POST | 列表 / 创建 |
| `/api/characters/{name}` | PUT / DELETE | 更新 / 删除（支持改名） |
| `/api/characters/generate`、`/api/characters/batch` | POST | AI 生成角色 / 批量创建 |

### 场景 `/api/scenes`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/scenes` | GET / POST | 列表 / 创建 |
| `/api/scenes/{id}` | PUT / DELETE | 更新 / 删除 |
| `/api/scenes/{id}/start` | POST | 进入场景开新会话（Persona 三级回退：body → 角色库 → 占位） |
| `/api/scenes/generate` | POST | AI 生成场景 |

### 历史 `/api/history`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/history`、`/api/history/sessions` | GET | 历史会话列表 |
| `/api/history/sessions/{id}` | GET | 会话消息详情 |
| `/api/history/load/{id}` | POST | 加载会话（2026-07-31 修复 D12：真实写回单例 router） |

### 配置 `/api/config`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/config/apikey` | GET / POST | 读写 API Key（脱敏显示；⚠️ 忽略 api_base/model 字段，见 D20） |
| `/api/config/language`、`/api/config/models` | GET/POST · GET | 语言（zh/en）/ 推荐模型列表 |
| `/api/config/voice` | GET / POST | 语音配置（⚠️ POST 为空操作，见 D20） |

### 认证 `/api/auth`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/auth/verify`、`/api/auth/me` | POST · GET | 邀请码登录（预置 `DEFAULT2024`）/ 当前用户（Bearer token） |
| `/api/auth/admin/generate` `/list` `/deactivate` | POST/GET/POST | 邀请码管理（需 `X-Admin-Key`，2026-07-31 修复 D19） |

### 多人房间 `/api/rooms`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/rooms` | POST | 创建房间（⚠️ 返回无 room 包装，前端取不到，见 F-CTR-02） |
| `/api/rooms/{code}` | GET | 房间信息 |
| `/api/rooms/{code}/join` `/leave` `/assign` | POST | 加入 / 离开 / 分配角色（⚠️ assign 字段与前端不匹配，见 F-CTR-03） |

### 狼人杀 `/api/werewolf`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/werewolf/init`、`/api/werewolf/status` | POST · GET | 开局（默认 9 人）/ 状态（按玩家视角脱敏） |
| `/api/werewolf/night_action` | POST | 夜间行动（狼刀/预言/女巫救/毒） |
| `/api/werewolf/hunter_shoot` | POST | 猎人开枪 |
| `/api/werewolf/resolve_night`、`/api/werewolf/start_voting`、`/api/werewolf/vote`、`/api/werewolf/resolve_vote` | POST | 阶段结算与投票 |

### 剧本杀 `/api/script`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/script/generate`、`/api/script/init` | POST | 生成剧本 / 生成并开局（2026-07-31 修复 D4+D5：前端链路 + 秘密发放） |
| `/api/script/search` | POST | 搜证（私有/公开线索分级） |
| `/api/script/start_discussion`、`/api/script/start_voting` | POST | 进入讨论 / 投票阶段 |
| `/api/script/vote`、`/api/script/resolve`、`/api/script/status` | POST/POST/GET | 投票 / 揭晓（⚠️ truth.contains 判定，见 D6）/ 状态 |

### 轨道申请 `/api/track`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/track/request` | POST | 提交轨道变更申请 |
| `/api/track/requests`、`/approve`、`/reject` | GET/POST/POST | 申请列表 / 批准 / 驳回 |
| `/api/track/requests/evaluate` | POST | LLM 评估角色需求（双阶段审批） |

### 审批门 `/api/approval`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/approval/approve`、`/reject` | POST | 批准 / 驳回并回滚 |
| `/api/approval/status`、`/status/detail` | GET | 审批状态 / 详细状态（含耗时） |

### SSE `/api/events`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/events` | GET | SSE 事件流（心跳 15s；⚠️ broadcast 无调用方，主对话事件为死代码，见 D8） |

### 语音 `/api/voice`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/voice/status`、`/start`、`/stop` | GET/POST/POST | 语音循环状态 / 启停 |
| `/api/voice/transcribe` | POST | 语音转写（multipart `audio`，2026-07-31 修复 D9） |

### 搜索 `/api/search`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/search` | GET / POST | 网页搜索（max ≤ 10） |
| `/api/search/fetch` | POST | 抓取网页正文 |

### MCP `/api/mcp`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/mcp/servers` | GET | 服务器 + 工具列表 |
| `/api/mcp/call` | POST | 调用工具（server/tool/args） |
| `/api/mcp/status/{serverName}`、`/reconnect/{serverName}` | GET/POST | 连通性 / 重连 |

### 2D 空间模拟 `/api/simulation`
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/simulation/init`、`/load-characters` | POST | 初始化演示世界（2-8 人）/ 加载自定义角色 |
| `/api/simulation/start` `/stop` `/reset` | POST | 开始 / 停止（存快照）/ 清空 |
| `/api/simulation/state` | GET | 世界状态（agents / 对话 / 旁白 / 导演活动） |
| `/api/simulation/send/{agent}` `/move/{agent}` `/target/{agent}` `/emotion/{agent}` `/config/{agent}` | POST | 消息 / 移动 / 目标 / 情绪 / 感知与速度配置 |
| `/api/simulation/events` | GET | SSE 世界快照流（300s 超时，每 tick `world_snapshot`） |
| `/api/simulation/directive` | POST | 主控指令（立即触发导演轮） |
| `/api/simulation/scene/{name}`、`/scenes` | POST · GET | 切换场景（6 个）/ 场景列表 |
| `/api/simulation/conversation-status`、`/conversations` | GET | 对话组状态 / 最近对话 |
| `/api/simulation/track/goal`、`/track/secret`、`/track/state` | POST/POST/GET | World Director 目标 / 秘密任务（强制 ISOLATED）/ 轨道状态汇总 |

### 中断 `/api/interrupt`（2026-07-31 新增，对应 D1）
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/interrupt` | POST | 按任务类型中断（⚠️ 无参数时默认按 GENERATION 过滤，见 D21） |
| `/api/interrupt/tasks`、`/tasks/{id}` | GET | 任务列表 / 详情（⚠️ LLM 失败被记为 CANCELLED，与主动中断难区分，见 D22） |

---

## 🏗️ 架构说明

### 回合管线（自由对话主链路）

```
POST /api/send → RouterService.runRound()
  → RoundHook.beforeRound()（WebSearchHook 搜索注入 / MetricsHook 计时）
  → Arbiter 分析（紧张度 1-10 → 推荐轨道 → 分配角色任务）
  → AgentExecutor 并行执行（Virtual Threads；每 Agent 独立上下文 + 中断 token）
  → Arbiter 整合（合并叙事 + narrator + 更新轨道）
  → 压缩 + MemoryStore 摘要链 → HTTP 返回体回前端
  （SSE 广播环节当前为死代码，见 D8）
```

### RouterService 单例会话语义

后端为**单例 RouterService**：`/api/send` 不按 session_id 路由，多会话不隔离（D11，已列入缺陷）。历史加载（D12 修复后）会把历史消息/角色/场景/轮次**写回该单例 router**。角色/场景 CRUD、狼人杀/剧本杀对局状态与之相互独立。

### 2D × 铁轨：双导演架构（Phase 1-4 已落地）

```
每 tick → SimulationOrchestrator
  → WorldDirectorService（规则式：角色"想做什么"目标）
  → InteractionDetector（TrackScore 打分：人数 3-4:+20 / 5+:40、旁观者+20、目标冲突+40、
     秘密任务+50、情绪异常+15，阈值 40 判定谁知道什么）
  → TrackDirectorService（轨道分配 MERGED / WEAK / ISOLATED）
  → MovementConstraint（怎么移动：质心聚集 / 听觉带 / 避让 60 格；手动目标不被覆盖）
  → ConversationGroup → TrackStrategy（隔离上下文）→ LLM → SSE world_snapshot
```

Tick 策略：移动 10FPS / 社交 5s / 事件立即。轨道变化会发布 `TrackChangeEvent`，中断管理器自动取消不属于新轨道的任务。

### 中断系统（2026-07-31 落地，对应需求第八条）

- **三种停止类型**：`HARD`（立即中断虚拟线程）/ `SOFT`（协作式，保存未完成部分输出）/ `STATE_INVALID`（意图失效 → 任务标记 INTERRUPTED）
- **任务状态机**：IDLE → PLANNING → RUNNING → DONE / CANCELLED / INTERRUPTED
- **事件驱动**：WorldEventBus + GameEvent + TrackChangeEvent，订阅者自动响应
- **接线**：`/api/stop`、`/api/simulation/stop` 现在能真正中断进行中的 LLM 生成（token 检查点 + future.cancel）

### 记忆：压缩链

短期窗口（`short-term-rounds: 20`）+ 摘要链（每 `summary-interval: 10` 轮压缩，角色指纹 + 结构化摘要），控制长对话 token 成本；无检索打分（D10，与 Stanford memory stream 有差距）。

---

## 💾 数据持久化（H2 现状，如实说明）

- **生产库**：`jdbc:h2:file:./data/roleplay`（文件库，ddl-auto: update）；测试库 `jdbc:h2:mem:testdb`（create-drop）
- **JPA 实体 6 张表**：characters / scenes / game_sessions / conversation_logs / scripts / world_snapshots
- ✅ **已落库**：
  - 角色 / 场景：内存镜像 + H2 双写，启动时从库加载（2026-07-31 修复 D14，重启不再丢失）
  - 2D 世界快照：每 50 tick + stop 时保存；模拟对话写入 conversation_logs
- ⚠️ **仍为内存态（重启即丢）**：历史会话（savedSessions）、多人房间、邀请码与 token、剧本 / 狼人杀对局状态、运行时 API Key
- `data/characters|scenes|sessions/` 为 JSON 备份占位目录（当前未使用）

---

## 🧪 测试

- **JUnit 基线（2026-07-31）**：15 个测试类 / 93 用例全绿（ApprovalService 11、RouterServiceHooks 11、McpService 10、TrackDirectorService 10、MovementConstraint 11、TrackStrategy 7、WorldDirectorService 7、InteractionDetector 7、SpatialTrackResolver 6、SimulationOrchestrator 4、LongTextStability 1，以及 CompressorChain / DatabaseService / TrackDirectorSecretOverride / InteractionDetectorBoundary 新增类）
- **LONG-01 超长文本**（10 万字 / 500 轮）：3 次复跑全 PASS，P95 6-7ms，无 OOM
- **E2E 冒烟**：`scripts/smoke/smoke_basic.py`（S1-S8 覆盖初始化/状态/角色/场景/历史等；S3 对话用例因 LLM 401 恒失败）+ `observe_track.py`（秘密任务轨道观察）
- **隔离策略**：`application-test.yml` 使用 RANDOM_PORT + H2 内存库 + mock LLM（localhost:9999），不触碰现网
- **已知覆盖缺口**：Controller 层 0 个 @SpringBootTest 集成测试（SessionController / SimulationController / HistoryController / AuthController / 剧本杀 / 狼人杀状态机均无测试）；stress 脚本未落地（D15）
- **环境阻塞 G1**：现网实例 `configured: False`，真实 LLM 用例（E2E S3、剧本杀/狼人杀真局）需先配置 api-key 方可运行，当前如实记录为环境阻塞

---

## ⚠️ 已知问题（问题清单 D1-D24，2026-07-31 快照）

### ✅ 已修复（2026-07-31）

| # | 问题 | 修复 |
|---|---|---|
| D1 | 中断系统缺失（需求第八条 P0） | 完整落地：11 个新类 + 三停止类型 + 事件总线 + `/api/interrupt`（台账 #18） |
| D4 | 剧本杀前端链路（占位符"敬请期待"） | ScenePage 真实交互 + ChatPage 剧本杀面板 + client.ts 封装（#16） |
| D5 | 剧本杀 secrets 发放缺失 | 生成 → 按角色注入上下文，每角色只见自己秘密（#16） |
| D9 | Whisper 无端点，`/api/voice/transcribe` 404 | 新增转写端点（multipart audio）（#13） |
| D12 | 历史加载假成功 | 真实写回单例 router + 恢复 mode/agents（#15） |
| D14 | 角色/场景不落库（重启即丢） | Controller 内存镜像 + H2 双写（#17） |
| D16 | LONG-01 无产出 | 根因定位 + 3 次复跑全 PASS |
| D19 | admin 端点无鉴权 | X-Admin-Key 校验（ROLEPLAY_ADMIN_KEY 环境变量）（#14） |
| A1 | 测试文件静默修改（流程违规） | 台账拆分登记 #1/#1b + 注释加固 + 复跑验证 |

### 🟠 待修复（P1）

| # | 问题 | 说明 |
|---|---|---|
| D6 | 剧本杀揭晓判定粗糙 | `truth.contains(mostVoted)` 字符串包含，无平票重投 |
| D7 | 审批门未接入剧本杀/狼人杀 | ApprovalService 存在但游戏管线未调用 |
| D8 | SSE 主对话事件流死代码 | broadcast* 无调用方；前端 30 事件监听静默，依赖 HTTP 返回体 |
| D11 | 多会话不隔离 | `/api/send` 用单例 router |
| D13 | `/api/round/start` turns 参数无效 | 恒跑 1 轮，"三轮"按钮名不副实 |
| D17/G1 | LLM 401（环境阻塞） | application.yml 缺 api-key，运行时注入重启丢失 |
| D20 | ConfigController 空操作 | setVoiceConfig no-op；apikey 忽略 api_base/model |
| D21 | 中断任务列表默认过滤 | 无 type 参数时恒空（默认按 GENERATION 过滤） |
| D22 | LLM 失败误报"生成已中断" | 任务失败映射为 CANCELLED，与主动中断难区分 |
| N1 | 历史列表契约偏离 | 前端期望 messages 字段，后端返回 sessions 结构 |

### 🟡 待修复（P2）/ 契约断点

| # | 问题 |
|---|---|
| D2/D3 | GroupAnchor 群体锚点降级为 MovementConstraint；ContextVisibility 由 visibleAgents 等效承担 |
| D10 | MemoryStore 无检索打分（recency/relevance/importance） |
| D15 | stress 测试脚本未落地（scripts/stress 空） |
| D18 | InteractionDetector 阈值 40 hardcode 未配置化 |
| D23 | GET /api/auth/me 无 Authorization 头返回 400 而非 401 |
| D24 | AgentExecutor 日志占位符 Python 风格 `{:.0f}`，SLF4J 输出字面量 |
| F-CTR-02/03 | 房间接口无 room 包装；assign 字段与前端不匹配 |

---

## 🤝 开发协作

- **修改登记**：对 `D:\roleplay-java` 的任何文件修改（含子 agent）必须追加登记到 `docs/修改记录.md`（编号/时间/修改人/文件/摘要/核查状态），未登记未核查视为无效
- **禁止 `spring-boot:run`**：8000 端口有运行实例；只准 `mvn compile/test`（测试 RANDOM_PORT 隔离）
- **禁止 git commit**：需主人明确授权
- **测试通过后**：更新 `TEST_STATUS.md`
- **中文 HTTP 测试**：PowerShell 发中文 JSON 会 GBK 乱码，用 Python（UTF-8）脚本
- **并行工作流**：另一主会话可能同改文件，派单前先 `git diff` 确认基线

---

## 📄 许可

MIT License

---

<p align="center"><sub>🎭 让每个角色都有自己的声音 — 2D 空间 × 铁轨系统 × 双游戏引擎</sub></p>
