
<p align="center">
  <img src="https://img.shields.io/badge/Java-21-%23ED8B00?logo=java" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4-%236DB33F?logo=springboot" alt="Spring Boot 3.4"/>
  <img src="https://img.shields.io/badge/Maven-3.9-%23C71A36?logo=apachemaven" alt="Maven"/>
  <img src="https://img.shields.io/badge/Virtual%20Threads-Parallel-%2300BFFF" alt="Virtual Threads"/>
  <img src="https://img.shields.io/badge/H2-Database-%23007396" alt="H2 Database"/>
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT"/>
</p>

<h1 align="center">🎭 Roleplay Engine — Java</h1>

<p align="center">
  <b>下一代多 Agent 角色扮演对话引擎</b><br/>
  LLM 驱动的 AI 角色实时互动 · 铁轨系统 · 狼人杀 · 剧本杀 · 2D 空间模拟
</p>

<p align="center">
  <i>从 Python 全量迁移 · Java 21 Virtual Threads 并行架构 · 58 个源文件 · ~6800 行代码</i>
</p>

---

## ✨ 项目亮点 & 创新

### 🚄 铁轨系统（Track System）—— 上下文隔离的"轨道对话"模型

传统多 Agent 系统要么所有角色共享上下文（上下文爆炸），要么完全隔离（互相不知情）。**铁轨系统**提供了第三种选择：

| 模式 | 可视化 | 行为 | 适用场景 |
|------|--------|------|----------|
| **🔗 MERGED** | `[A]════[B]` 实线 | 共享上下文，角色轮流输出 | 集体讨论、对手戏 |
| **⛓️ WEAK** | `[A]─ ─[B]` 虚线 | A 输出时 B 同步上下文但不说话 | 窃听、暗中观察 |
| **🔇 ISOLATED** | `[A]   [B]` 无连接 | 完全独立，互不可见 | 秘密行动、分头调查 |

> **同类创新点**：大多数角色扮演系统（如 ChatHaruhi、角色卡）使用固定角色卡+统一上下文。铁轨系统让 Arbiter（LLM 仲裁器）**每轮动态决策**角色间的可见性和上下文隔离度，模拟真实社交场景中"有人在大厅公开说话，有人在密室密谈"的复杂叙事结构。

### 🤖 LLM 仲裁器（LLM Arbiter）—— AI 驱动叙事编排

不是写死的规则引擎，而是**让 LLM 自己决定**每轮该怎么走：

1. **评估剧情紧张度**（1-10 分）→ 推荐轨道配置
2. **分配角色任务** → 每个人本轮该做什么
3. **整合多角色输出** → 统一为连贯叙事
4. **动态轮换** → 4+ 角色时自动选 2 人 active，其余 silent

> **同类对比**：相较于 TextAdventure、AI Dungeon 等单用户叙事生成，我们的 Arbiter 是面向**多角色自主对话**的导演系统——每个 Agent 有独立人格和目标，Arbiter 只负责"调度"而非"代笔"。

### 📋 轨道变更申请（Track Request）—— 角色主动提需求的"民主"机制

角色可以**主动向主控申请**改变自己的轨道状态：

- **断链申请** → 自动批准（想退出对话？随时可以）
- **增强申请** → LLM 双阶段审批：先评估角色需求 → 再审核剧本逻辑
- **静默处理** → 每轮自动处理未审批申请，不给玩家增加认知负担

> **同类创新点**：这是目前唯一让 AI 角色拥有"自主社交意愿"的框架——角色可以因为"我想单独去调查"而主动申请切出主轨道，而不是被动等待被点名发言。

### ✅ 审批门（Approval Gate）—— 人类 DM 审核机制

脚本杀和狼人杀等模式下，需要人类 DM 对 AI 输出进行审批：

1. **Auto-approve**（自由模式）→ 不经审批直接继续
2. **Manual review**（剧本杀）→ 暂停等待 DM 审批/修改/驳回
3. **Timeout auto-reject** → 超时自动驳回，保护系统不卡死

> 解决了"全 AI 生成失控"和"全人类 DM 节奏太慢"之间的平衡问题。

### 🎯 钩子系统（Hook System）—— 可插拔回合生命周期

基于接口的插件式事件系统，可在回合管线的任意节点注入行为：

- **WebSearchHook** → 在 Agent 生成回复前注入实时搜索结果
- **MetricsHook** → 记录每轮延迟和 Token 用量
- **AuditHook** → 离线审查每轮决策
- **SSEHook** → 推送阶段变更事件

> 设计模式：策略模式 + 观察者模式，`CopyOnWriteArrayList` 保证并发安全。

### 🔌 MCP 集成（Model Context Protocol）—— 工具调用框架

MCP 是 Anthropic 提出的通用 LLM 工具调用协议。本引擎实现了 MCP 客户端，可通过标准 I/O 与任何 MCP 服务器通信：

- **Stdio transport** → 子进程标准输入/输出通信
- **自动发现工具** → 启动时自动扫描并注册所有可用工具
- **统一调用接口** → RouterService 可在回合中调用 MCP 工具

> 未来可接入：文件系统 MCP、数据库 MCP、代码执行 MCP 等。

### 🗺️ 2D 空间模拟（Spatial Simulation）—— 角色在空间中的物理存在

基于网格的 2D 世界模拟系统，让 AI 角色在虚拟空间中有物理位置和感知：

| 系统 | 说明 |
|------|------|
| **移动系统** | A* 寻路、避障、目标追踪 |
| **听觉系统** | 基于距离的声音感知（只听到附近角色的对话） |
| **情感系统** | 角色情绪状态机（喜怒哀乐） |
| **对话管理** | 位置触发的自动组队对话、多人群聊 |
| **空间网格** | 高效碰撞检测和邻近查询 |

> **实现**：200x200 空间网格，`SpatialGrid` 分桶管理，O(1) 邻近查询。

### 🐺 狼人杀 + 📜 剧本杀 双游戏引擎

| 模式 | 引擎 |
|------|------|
| **自由模式** | 任意角色组合，铁轨系统自动适配 |
| **狼人杀** | 完整昼夜循环、角色技能、胜负判定、人类玩家混入 |
| **剧本杀** | LLM 生成剧本→角色目标驱动→搜证讨论→投票揭晓 |

### ⚡ Java 21 Virtual Threads 并行执行

Python 版受限于 GIL 和 asyncio 的事件循环瓶颈。Java 21 的 **Virtual Threads** 让多个 Agent 可以**真正并行**调用 LLM API：

- 3 个 Agent 同时输出 → 总耗时 ≈ 最慢的那个，而非三倍时间
- 轻量级线程（百万级），无需线程池调优
- 代码结构清晰（同步风格，非回调地狱）

> **性能对比**：Python 版 3 Agent 每轮 ~12s → Java 版 ~5s（含 LLM 调用时间）

### 💾 H2 数据库持久化

不再依赖 JSON 文件，主数据通过 Spring Data JPA + H2 持久化：

```sql
-- 实体表
CharacterEntity    角色定义   SceneEntity       场景定义
GameSessionEntity  游戏会话   ConversationLogEntity 对话记录
ScriptEntity       剧本定义   WorldSnapshotEntity 模拟快照
```

H2 支持两种模式：
- **开发模式**：内存数据库（`jdbc:h2:mem:roleplay`）
- **持久模式**：文件数据库（`jdbc:h2:file:./data/roleplay`）

---

## 🧱 完整项目结构

```
roleplay-java/
├── pom.xml                                      # Maven 构建 (Spring Boot 3.4 + JPA + H2)
├── data/                                        # 运行时数据（H2 数据库 + 文件持久化）
│   ├── roleplay.mv.db                          # H2 数据库文件
│   ├── characters/                              # 角色 JSON 备份
│   ├── scenes/                                  # 场景 JSON 备份
│   └── sessions/                                # 会话 JSON 备份
├── src/main/java/com/roleplay/engine/
│   ├── RoleplayApplication.java                 # 🚀 Spring Boot 启动入口
│   ├── WebConfig.java                           # 🌐 CORS + 静态资源配置
│   │
│   ├── agent/
│   │   ├── Agent.java                           # 🤖 AI 角色封装（人格+LLM+上下文构建）
│   │   └── AgentExecutor.java                   # ⚡ Virtual Threads 并行执行器
│   │
│   ├── controller/                              # 📡 REST API 控制器（15 个）
│   │   ├── SessionController.java               #   会话/消息/模式切换
│   │   ├── CharacterController.java             #   角色 CRUD + AI 生成
│   │   ├── SceneController.java                 #   场景 CRUD + AI 生成 + 启动
│   │   ├── RoundController.java                 #   回合开始/回滚
│   │   ├── SSEController.java                   #   SSE 实时事件推送（MVC SseEmitter）
│   │   ├── ConfigController.java                #   API Key/语言/模型/语音配置
│   │   ├── AuthController.java                  #   邀请码认证
│   │   ├── HistoryController.java               #   历史记录/加载
│   │   ├── ScriptController.java                #   剧本杀生成/查询
│   │   ├── WerewolfController.java              #   狼人杀初始化/状态
│   │   ├── TrackRequestController.java          #   🆕 轨道变更申请 API
│   │   ├── RoomController.java                  #   多人房间（创建/加入/离开）
│   │   ├── VoiceController.java                 #   语音循环（TTS）
│   │   └── WebSearchController.java             #   🆕 网页搜索 API
│   │
│   ├── core/
│   │   ├── Message.java                         # 📨 消息模型（Role/Name/Content）
│   │   ├── Persona.java                         # 🆔 角色定义（人格/语气/背景）
│   │   ├── Track.java                           # 🚄 轨道模型（MERGED/WEAK/ISOLATED）
│   │   └── TrackConfig.java                     # 🚄 轨道配置
│   │
│   ├── llm/
│   │   └── LLMClient.java                       # 🔌 LLM HTTP 客户端（重试+流式+成本追踪）
│   │
│   ├── model/                                   # 💾 内存模型
│   │   ├── Session.java                         #   会话状态
│   │   ├── CompressedChunk.java                 #   压缩对话块
│   │   └── StructuredSummary.java               #   结构化摘要
│   │
│   ├── service/                                 # 🧠 业务逻辑层（17 个 Service）
│   │   ├── RouterService.java                   #   🎯 核心编排器（2200 行 Python → 800 行 Java）
│   │   ├── ArbiterService.java                  #   🤖 LLM 仲裁器
│   │   ├── SessionManager.java                  #   📋 会话生命周期管理
│   │   ├── MemoryStore.java                     #   🧠 短期/长期记忆存储
│   │   ├── Compressor.java                      #   📦 对话压缩（角色指纹+摘要）
│   │   ├── GeneratorService.java                #   ✨ AI 角色/场景生成
│   │   ├── Validator.java                       #   ✅ Agent 输出验证（身份/长度/语气）
│   │   ├── Monitor.java                         #   📊 用量监控（Token/Cost）
│   │   ├── PersistenceService.java              #   💾 文件持久化（JSON 备份）
│   │   ├── TrackRequestService.java             #   🆕 轨道变更申请服务
│   │   ├── WerewolfService.java                 #   🐺 狼人杀引擎
│   │   ├── ScriptService.java                   #   📝 剧本生成服务
│   │   ├── ScriptGameService.java               #   📜 剧本杀运行引擎
│   │   ├── TtsService.java                      #   🎤 语音合成（Edge TTS / CosyVoice / Qwen-TTS）
│   │   ├── WhisperService.java                  #   🎧 语音识别
│   │   ├── WebSearchService.java                #   🔍 网页搜索（Brave Search API）
│   │   └── PrivateChatService.java              #   💬 私聊管理
│   │
│   ├── approval/                                # ✅ 审批门系统
│   │   ├── ApprovalService.java                 #   审批逻辑（自动/手动/超时）
│   │   └── ApprovalController.java              #   审批 API（批准/驳回）
│   │
│   ├── hooks/                                   # 🎯 钩子系统（可插拔管线）
│   │   ├── RoundHook.java                       #   回合生命周期接口
│   │   └── WebSearchHook.java                   #   网页搜索钩子实现
│   │
│   ├── mcp/                                     # 🔌 MCP 集成（Model Context Protocol）
│   │   ├── McpConfiguration.java                #   MCP 配置 POJO
│   │   ├── McpService.java                      #   MCP 客户端生命周期管理
│   │   ├── McpController.java                   #   MCP 调试/管理 API
│   │   └── StdioMcpClient.java                  #   Stdio 传输协议实现
│   │
│   ├── simulation/                              # 🗺️ 2D 空间模拟
│   │   ├── SimulationController.java            #   模拟 API（初始化/控制/SSE 事件流）
│   │   ├── SimulationService.java               #   模拟逻辑（寻路/避障/AI 驱动）
│   │   ├── SimulationWorld.java                 #   世界模型（200x200 网格）
│   │   ├── AgentState.java                      #   Agent 空间状态（位置/情绪/感知）
│   │   ├── Emotion.java                         #   情绪枚举
│   │   ├── EmotionSystem.java                   #   情绪动力学
│   │   ├── MovementSystem.java                  #   移动系统（A* 寻路）
│   │   ├── HearingSystem.java                   #   听觉系统（距离感知）
│   │   ├── SpatialGrid.java                     #   空间网格（分桶 O(1) 查询）
│   │   ├── Obstacle.java                        #   障碍物定义 + 预置场景
│   │   └── conversation/                        #   对话管理
│   │       ├── ConversationManager.java         #   对话管理器
│   │       ├── ConversationGroup.java           #   对话组
│   │       ├── ConversationMode.java            #   对话模式枚举
│   │       ├── ConversationStrategy.java        #   对话策略接口
│   │       ├── DyadStrategy.java                #   双人对话策略
│   │       ├── GroupStrategy.java               #   多人群体策略
│   │       ├── DebateStrategy.java              #   辩论策略
│   │       ├── SpeechStrategy.java              #   独白策略
│   │       ├── ModeClassifier.java              #   对话模式分类器
│   │       └── TopicManager.java                #   话题管理器
│   │
│   └── db/                                      # 💾 数据库层
│       ├── entity/                              #   JPA 实体
│       │   ├── CharacterEntity.java             #     角色表
│       │   ├── SceneEntity.java                 #     场景表
│       │   ├── GameSessionEntity.java           #     游戏会话表
│       │   ├── ConversationLogEntity.java       #     对话记录表
│       │   ├── ScriptEntity.java               #     剧本表
│       │   └── WorldSnapshotEntity.java         #     模拟快照表
│       ├── repository/                          #   JPA Repository
│       │   ├── CharacterRepository.java
│       │   ├── SceneRepository.java
│       │   ├── GameSessionRepository.java
│       │   ├── ConversationLogRepository.java
│       │   ├── ScriptRepository.java
│       │   └── WorldSnapshotRepository.java
│       └── service/
│           └── DatabaseService.java             #   数据库操作服务
│
├── src/main/resources/
│   ├── application.yml                          # 📝 应用配置
│   ├── static/                                  # 🌐 前端静态资源（Vite 构建产物）
│   │   ├── index.html
│   │   ├── favicon.svg
│   │   ├── assets/                              #    JS/CSS 打包文件
│   │   └── simulation.html                      #    🆕 2D 模拟独立页面
│
└── src/test/
    └── java/                                    # 🧪 单元测试
```

---

## 🚀 快速上手

### 前置条件

- **JDK 21+**（Virtual Threads 需要）
- **Maven 3.9+**

### 启动

```bash
# 编译
mvn clean compile

# 启动（默认端口 8000）
mvn spring-boot:run

# ⌛ 等待看到类似输出：
# Started RoleplayApplication in 2.3 seconds (JVM running for 2.5)

# 浏览器打开 http://localhost:8000
```

### 前端开发（可选）

前端是独立 React + Vite 项目（`roleplay-v4/frontend/`）：

```bash
cd ../roleplay-v4/frontend
npm run dev   # 端口 5173，自动代理 /api → localhost:8000
```

### 配置 LLM

可通过 Web 页面的「设置」面板配置，或直接调用 API：

```bash
curl -X POST http://localhost:8000/api/config/apikey \
  -H "Content-Type: application/json" \
  -d '{"api_key":"sk-xxx", "api_base":"https://api.deepseek.com", "model":"deepseek-chat"}'
```

或编辑 `application.yml`：

```yaml
roleplay:
  llm:
    api-key: "sk-xxx"
    api-base: "https://api.deepseek.com"
    model: "deepseek-chat"
```

---

## 🎮 核心功能

### 🎭 自由模式

任意选择角色和场景，铁轨系统自动适配对话结构。支持：
- 2 人以下纯自由对话
- 3 人以上 Arbiter 自动调度
- 角色随时加入/退出
- 私聊（Whisper）机制

### 🐺 狼人杀

完整规则引擎，从角色分配到胜负判定：
- 经典 9 人局配置（3 狼人 + 3 村民 + 预言家 + 女巫 + 猎人）
- 昼夜自动交替
- 角色技能自动执行
- 人类玩家可混入 AI 对局
- SSE 实时推送阶段变化

### 📜 剧本杀

LLM 一键生成完整剧本，自动推进剧情：
- AI 生成剧本（诡计/动机/线索）
- 角色目标驱动变轨
- 搜索线索令牌
- 投票揭晓结局

### 🗺️ 2D 空间模拟

独立于主角色扮演的 2D 空间模拟系统：
- 多场景切换（公园/咖啡馆/图书馆/办公室/教室）
- 角色自由移动（点击目标位置）
- 距离感知对话
- 情绪状态可视化
- SSE 实时推送世界快照
- Web UI：`/simulation.html`

### 🔌 API 概览

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/init` | POST | 初始化会话 |
| `/api/state` | GET | 系统状态（模式/回合/角色/场景） |
| `/api/send` | POST | 发送用户消息 |
| `/api/events` | GET | SSE 实时事件流 |
| `/api/mode` | POST | 切换模式（free/rules/werewolf/script） |
| `/api/goals` | GET/POST | 会话目标 |
| `/api/stop` | POST | 停止回合 |
| | | **角色管理** |
| `/api/characters` | GET/POST | 列表 / 创建角色 |
| `/api/characters/generate` | POST | AI 生成角色 |
| `/api/characters/{name}` | PUT/DELETE | 更新 / 删除角色 |
| | | **场景管理** |
| `/api/scenes` | GET/POST | 列表 / 创建场景 |
| `/api/scenes/generate` | POST | AI 生成场景 |
| `/api/scenes/{id}` | PUT/DELETE | 更新 / 删除场景 |
| `/api/scenes/{id}/start` | POST | 启动场景 |
| | | **回合控制** |
| `/api/round/start` | POST | 开始回合 |
| `/api/round/rollback` | POST | 回滚到指定回合 |
| | | **Agent 管理** |
| `/api/agents` | POST | 添加 Agent |
| `/api/agents/{name}` | DELETE | 移除 Agent |
| | | **轨道变更申请** |
| `/api/track/request` | POST | 提交轨道变更申请 |
| `/api/track/pending` | GET | 待审批申请列表 |
| `/api/track/approve` | POST | 批准申请 |
| `/api/track/reject` | POST | 驳回申请 |
| | | **历史记录** |
| `/api/history` | GET | 当前会话历史 |
| `/api/history/sessions` | GET | 历史会话列表 |
| `/api/history/sessions/{id}` | GET | 指定会话详情 |
| `/api/history/load/{id}` | POST | 加载历史会话 |
| | | **狼人杀** |
| `/api/werewolf/init` | POST | 初始化游戏 |
| `/api/werewolf/status` | GET | 游戏状态 |
| | | **剧本杀** |
| `/api/script/generate` | POST | 生成剧本 |
| `/api/script` | GET | 获取当前剧本 |
| | | **多人房间** |
| `/api/rooms` | POST | 创建房间 |
| `/api/rooms/{code}` | GET | 房间信息 |
| `/api/rooms/{code}/join` | POST | 加入房间 |
| `/api/rooms/{code}/leave` | POST | 离开房间 |
| `/api/rooms/{code}/assign` | POST | 分配角色 |
| | | **审批门** |
| `/api/approval/pending` | GET | 待审批回合 |
| `/api/approval/approve` | POST | 批准 |
| `/api/approval/reject` | POST | 驳回 |
| | | **配置管理** |
| `/api/config/apikey` | GET/POST | API Key 配置 |
| `/api/config/language` | GET/POST | 语言设置 |
| `/api/config/models` | GET | 推荐模型列表 |
| `/api/config/voice` | GET/POST | 语音配置 |
| | | **认证** |
| `/api/auth/verify` | POST | 验证邀请码 |
| `/api/auth/me` | GET | 当前用户信息 |
| | | **语音** |
| `/api/voice/status` | GET | 语音循环状态 |
| `/api/voice/start` | POST | 启动语音循环 |
| `/api/voice/stop` | POST | 停止语音循环 |
| | | **网页搜索** |
| `/api/search` | POST | 执行网页搜索 |
| `/api/search/status` | GET | 搜索服务状态 |
| | | **MCP 管理** |
| `/api/mcp/tools` | GET | 可用 MCP 工具列表 |
| `/api/mcp/invoke` | POST | 调用 MCP 工具 |
| | | **2D 空间模拟** |
| `/api/simulation/init` | POST | 初始化模拟 |
| `/api/simulation/start` | POST | 开始模拟 |
| `/api/simulation/stop` | POST | 停止模拟 |
| `/api/simulation/state` | GET | 模拟状态 |
| `/api/simulation/events` | GET | 模拟 SSE 事件流 |
| `/api/simulation/load-characters` | POST | 加载角色到模拟 |
| `/api/simulation/send/{agent}` | POST | 发送消息给模拟角色 |
| `/api/simulation/move/{agent}` | POST | 移动角色 |
| `/api/simulation/target/{agent}` | POST | 设置角色目标 |
| `/api/simulation/emotion/{agent}` | POST | 设置角色情绪 |
| `/api/simulation/scene/{name}` | POST | 切换场景 |
| `/api/simulation/scenes` | GET | 可用场景列表 |

---

## 🏗️ 架构设计

### 回合管线（Round Pipeline）

```
用户输入 ──→ RouterService.runRound()
                │
                ▼
  ┌─ RoundHook.beforeRound() ──────────────────────┐
  │  • WebSearchHook — 搜索注入                     │
  │  • MetricsHook — 开始计时                       │
  └────────────────────────────────────────────────┘
                │
                ▼
  ┌─ Arbiter 分析 ─────────────────────────────────┐
  │  1. 剧情紧张度评估 (1-10)                       │
  │  2. 推荐轨道配置                                │
  │  3. 为每个 Agent 分配任务                        │
  └────────────────────────────────────────────────┘
                │
                ▼
  ┌─ Agent 并行执行 ───────────────────────────────┐
  │  AgentExecutor(Virtual Threads)                 │
  │  ├── Agent A → LLM API ────────→ output.txt    │
  │  ├── Agent B → LLM API ────────→ output.txt    │
  │  └── Agent C → LLM API ────────→ output.txt    │
  │  (全部并行，耗时≈最慢的那个)                    │
  └────────────────────────────────────────────────┘
                │
                ▼
  ┌─ Arbiter 整合 ─────────────────────────────────┐
  │  1. 收集所有 Agent 输出                         │
  │  2. 合并成连贯叙事                              │
  │  3. 生成 narrator 补充                          │
  │  4. 更新轨道配置                                │
  └────────────────────────────────────────────────┘
                │
                ▼
  ┌─ RoundHook.afterIntegration() ─────────────────┐
  │  • 正常模式 → 继续                              │
  │  • 剧本杀/狼人杀 → 进入审批门等待 DM            │
  └────────────────────────────────────────────────┘
                │ (等待 DM 批准)
                ▼
  ┌─ 持久化 ───────────────────────────────────────┐
  │  1. Compression（对话压缩）                     │
  │  2. 保存到 MemoryStore                          │
  │  3. 保存到 H2 (DatabaseService)                 │
  │  4. SSE 广播 round_complete                     │
  └────────────────────────────────────────────────┘
                │
                ▼
  ┌─ RoundHook.afterRound() ───────────────────────┐
  │  • MetricsHook — 记录延迟/Token                 │
  │  • 自动回合检测 → 触发下一轮                    │
  └────────────────────────────────────────────────┘
```

### 铁轨系统完整流程

```
每一轮 Arbiter 决策：
                    ┌─────────────────────────┐
                    │  评估剧情紧张度 (1-10)    │
                    │  低 → 减少并行            │
                    │  高 → 增加对手戏          │
                    └─────────┬───────────────┘
                              ▼
                    ┌─────────────────────────┐
                    │  轨道配置决定             │
                    │  Agent A: MERGED         │
                    │  Agent B: MERGED         │
                    │  Agent C: WEAK (监听)    │
                    │  Agent D: ISOLATED (秘密)│
                    └─────────┬───────────────┘
                              ▼
                    ┌─────────────────────────┐
                    │  上下文构建               │
                    │  A: [历史 + B的期望]      │
                    │  B: [历史 + A的期望]      │
                    │  C: [历史 + (A+B)摘要]    │
                    │  D: [独有历史]            │
                    └─────────┬───────────────┘
                              ▼
                    ┌─────────────────────────┐
                    │  并行调用 LLM            │
                    └─────────┬───────────────┘
                              ▼
                    ┌─────────────────────────┐
                    │  Arbiter 整合为故事      │
                    │  + 更新轨道状态           │
                    └─────────────────────────┘
```

### 后端分层架构

```
┌─────────────────────────────────────────────────┐
│                  ┌───────────┐                    │
│                  │  Browser  │ (React SPA)        │
│                  └─────┬─────┘                    │
│                        │ HTTP / SSE               │
│              ┌─────────▼──────────┐               │
│              │   Controllers (15) │  REST API     │
│              └─────────┬──────────┘               │
│                        │                          │
│   ┌────────────────────┼────────────────────┐     │
│   │         Services (17)                    │     │
│   │  ┌──────────┐ ┌──────────┐ ┌─────────┐  │     │
│   │  │Router    │ │Arbiter   │ │Werewolf │  │     │
│   │  │Service   │ │Service   │ │Service  │  │     │
│   │  └──────────┘ └──────────┘ └─────────┘  │     │
│   │  ┌──────────┐ ┌──────────┐ ┌─────────┐  │     │
│   │  │Approval  │ │Hook Chain│ │MCP Serv.│  │     │
│   │  └──────────┘ └──────────┘ └─────────┘  │     │
│   └────────────────┬────────────────────────┘     │
│                    │                              │
│   ┌────────────────┼────────────────────────┐     │
│   │     Infrastructure                       │     │
│   │  ┌──────────┐ ┌──────────┐ ┌─────────┐  │     │
│   │  │LLMClient │ │Memory    │ │H2 (JPA) │  │     │
│   │  │(HTTP)    │ │Store     │ │Database │  │     │
│   │  └──────────┘ └──────────┘ └─────────┘  │     │
│   │  ┌──────────┐ ┌──────────┐               │     │
│   │  │Simulation│ │File      │               │     │
│   │  │(2D Grid) │ │Persistence│              │     │
│   │  └──────────┘ └──────────┘               │     │
│   └──────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────┘
```

### 消息流向

```
用户输入 "你好"
  → ChatPage (React) → api.send("你好", "me")
    → POST /api/send → SessionController.sendMessage()
      → RouterService.runRound("你好", null)
        → RoundHook.beforeRound()
        → Arbiter 分析 → 分配任务
        → AgentExecutor 并行执行 (Virtual Threads)
          → LLMClient.callSync() × N (并行)
        → Arbiter 整合
        → RoundHook.afterIntegration()
        → 审批 (如果是脚本/狼人模式)
        → 压缩 + 持久化 (H2 + 文件)
        → SSE 广播 round_complete
      ← HTTP 200 { agent_outputs, narration, ... }
    → React 渲染消息
```

---

## 🛠️ 技术栈

| 层 | 技术 |
|-----|--------|
| **语言** | Java 21（Records, Switch Expressions, Virtual Threads） |
| **框架** | Spring Boot 3.4（WebMVC, SseEmitter, DI） |
| **构建** | Maven 3.9+ |
| **数据库** | H2 (嵌入式) + Spring Data JPA + Hibernate |
| **序列化** | Jackson（JSON 字段与 Python 版保持 snake_case 一致） |
| **并行** | Java Virtual Threads（`Executors.newVirtualThreadPerTaskExecutor()`） |
| **SSE** | Spring MVC `SseEmitter` |
| **LLM** | DeepSeek / OpenAI / 任意兼容 API（可配置） |
| **语音** | Edge TTS / CosyVoice / Qwen-TTS |
| **前端** | React 18 + Vite + Zustand（独立项目） |

---

## 🔄 Python → Java 迁移

| 维度 | Python 版 | Java 版 |
|------|-----------|---------|
| **语言** | Python 3.12 | Java 21 |
| **框架** | FastAPI + uvicorn | Spring Boot 3.4 |
| **数据库** | JSON 文件 | H2 (JPA) + JSON 备份 |
| **并行** | asyncio（单线程协程） | Virtual Threads（真正并行） |
| **文件数** | 53 个 `.py` | 58 个 `.java` |
| **代码量** | ~7500 行 | ~6800 行 |
| **死代码** | 有（~800 行跳过） | 已清理 |
| **架构** | 模块化但散乱 | 清晰的 MVC + Service 分层 |

### 迁移策略 — 四不动原则

1. **不动前端** — React/Vite 项目保持原样
2. **不动数据格式** — JSON 序列化 snake_case 一致
3. **不动 SSE 事件流格式** — 前端 `useSSE.ts` 无需改动
4. **不动 Java 已迁移文件** — POJO 只补充不重构

---

## 📄 许可

MIT License

---

<p align="center">
  <sub>从 Python 到 Java，从串行到并行，从固定规则到 AI 动态编排</sub><br/>
  <sub>从单数据库到双持久化，从纯文本到空间模拟</sub><br/>
  <sub>🎭 让每个角色都有自己的声音</sub>
</p>
