# 🎭 Roleplay v4 — 完整架构与功能文档

> 生成日期：2026-07-12  
> 总代码量：后端 ~7500 行 Python，前端 ~4500 行 TypeScript  
> 扫描范围：全部后端 53 个 .py 文件 + 全部前端 16 个 .tsx/.ts 文件

---

## 📖 目录

1. [项目总览](#1-项目总览)
2. [后端整体架构](#2-后端整体架构)
3. [核心引擎 (core/) 详解](#3-核心引擎-core-详解)
4. [API 路由层 (api/) 详解](#4-api-路由层-api-详解)
5. [基础设施服务 (services/) 详解](#5-基础设施服务-services-详解)
6. [数据模型 (models/) 详解](#6-数据模型-models-详解)
7. [游戏系统 (games/) 详解](#7-游戏系统-games-详解)
8. [前端架构](#8-前端架构)
9. [前后端映射关系](#9-前后端映射关系)
10. [死代码与屎山分析](#10-死代码与屎山分析)
11. [功能统一对应表](#11-功能统一对应表)
12. [清理建议与迁移路径](#12-清理建议与迁移路径)

---

## 1. 项目总览

### 1.1 一句话定义

一个**基于 LLM 的多智能体角色扮演系统**，核心是铁轨调度引擎，支持自由角色扮演、狼人杀、剧本杀等模式。

### 1.2 技术栈

| 层面 | 技术 | 版本 |
|------|------|------|
| 后端框架 | FastAPI + uvicorn | Python 3.14+ |
| LLM SDK | OpenAI SDK + httpx | — |
| 前端框架 | React + TypeScript + Vite | v19 / v5 |
| 状态管理 | Zustand | — |
| 数据存储 | JSON 文件系统 | 原子写入 |
| 语音 | Edge TTS / CosyVoice | — |
| 序列化 | Pydantic v2 + dataclasses | — |

### 1.3 核心功能矩阵

| 功能 | 状态 | 说明 |
|------|------|------|
| 自由角色扮演 (free mode) | ✅ 稳定 | 主控自主决定每轮谁说话 |
| 主角模式 (protagonist) | ✅ 稳定 | 指定主角始终活跃 |
| 多线模式 (multi_track) | ✅ 稳定 | 并行多条故事线 |
| 导演模式 (director) | ✅ 可用 | 用户扮演一个角色 |
| 狼人杀模式 (werewolf) | ⚠️ 测试中 | 身份分配/昼夜/投票 |
| 剧本杀模式 (script) | 🚧 开发中 | 搜证推理 |
| 角色 CRUD | ✅ 稳定 | 创建/编辑/删除/AI 生成 |
| 场景 CRUD | ✅ 稳定 | 同上 |
| 铁轨系统 | ✅ 稳定 | 核心创新：轨道隔离上下文 |
| SSE 实时流 | ✅ 稳定 | 事件驱动推送 |
| 语音输入 (Whisper) | ⚠️ 需配置 | 需本地 faster-whisper |
| TTS (Edge/CosyVoice) | ✅ 可用 | 流式语音输出 |
| 邀请码认证 | ✅ 可用 | 简单的 JWT 鉴权 |
| 联机房间 | ✅ 基础可用 | 内存中管理 |

---

## 2. 后端整体架构

### 2.1 目录总览

```
backend/                          # FastAPI 后端
├── main.py                       # 入口：argparse + uvicorn.run
├── config.py                     # AppConfig 所有配置（~200 行）
├── api_key.json                  # 用户保存的 API Key（自动生成）
├── api/                          # HTTP 路由层（13 个路由文件）
├── core/                         # 核心引擎（17 个模块）
├── services/                     # 基础设施服务（9 个模块）
├── models/                       # 数据模型（2 个模块）
├── games/                        # 游戏定义（3 个模块）
├── middleware/                    # 中间件（1 个模块）
├── data/                         # 运行时数据
│   ├── characters/               # 角色 JSON
│   ├── scenes/                   # 场景 JSON
│   └── sessions/                 # 会话 JSON + 归档
├── profiles/                     # 预设角色 YAML
└── scenes/                       # 预设场景 JSON
```

### 2.2 数据流

```
用户浏览器
    │
    ▼
┌─────────────────────────────────────────────────┐
│  api/routes_session.py → POST /api/send          │
│  或 POST /api/round/start                        │
└─────────────────┬───────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────┐
│  core/router.py → handle_user_input()             │
│                   或 run_round() / run_auto_rounds │
│                                                   │
│  ┌─────────────┐   ┌──────────────┐              │
│  │ Arbiter     │ → │ 配置铁轨      │              │
│  │ (LLM 调用)  │   │ (track config)│              │
│  └─────────────┘   └──────┬───────┘              │
│                           ▼                       │
│  ┌──────────────────────────────┐                │
│  │ Agent Executor                │               │
│  │ → 每个 agent 调 LLM          │               │
│  │ → 串行（当前）                │               │
│  └──────────────┬───────────────┘                │
│                 ▼                                 │
│  ┌──────────────────────────────┐                │
│  │ Arbiter integrate_outputs()  │               │
│  │ → LLM 整合所有输出           │               │
│  └──────────────┬───────────────┘                │
│                 ▼                                 │
│  ┌──────────────────────────────┐                │
│  │ Memory: add_message() +      │               │
│  │          autosave() + prune() │               │
│  └──────────────┬───────────────┘                │
└─────────────────┬───────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────┐
│  api/routes_sse.py → broadcast_event()           │
│  → SSE 流式推送到所有连接的浏览器                  │
└─────────────────────────────────────────────────┘
```

### 2.3 启动流程

```python
# main.py → 关键行 60
uvicorn.run(app, host=args.host, port=args.port, log_level="info")
# ⚠️ 没有 workers=N！默认单进程单线程
```

```python
# api/app.py → lifespan
# 1. 创建 Monitor
# 2. 创建 LLMClient（共享 httpx 连接池）
# 3. 创建 CharacterStore、SceneStore、SessionManager
# 4. 挂载到 app.state
# 5. Router 在第一次 GET /api/state 时惰性创建
```

---

## 3. 核心引擎 (core/) 详解

### 3.1 router.py（2200+ 行 ⚠️ 最大屎山）

**核心职责**：回合编排器，串联所有模块。

**类：Router(WerewolfGameMixin)**

| 方法 | 行号 | 用途 | 复杂度 |
|------|------|------|--------|
| `__init__` | ~60 | 初始化所有状态（15+ 属性） | ⚠️ 高 |
| `_get_mode_id` | ~20 | 获取模式 ID | 低 |
| `_switch_mode` | ~25 | 切换模式 + 清空旧状态 | 中 |
| `clear_game_state` | ~15 | 清空游戏状态 | 低 |
| `_load_saved_data` | ~15 | 从磁盘加载 | 中 |
| `init_with_characters` | ~60 | 初始化会话 | ⚠️ 高 |
| `on_event` / `_emit` | ~50 | 事件系统 + 可见性过滤 | 中 |
| `save_character` | ~5 | 保存角色 | 低 |
| `delete_character` | ~25 | 删除角色（多级清理） | ⚠️ 高 |
| `save_scene` | ~8 | 保存场景 | 低 |
| `delete_scene` | ~15 | 删除场景 | 中 |
| `enter_scene` | ~10 | 进入场景 | 低 |
| **`handle_user_input`** | ~100 | ⭐ **用户输入主入口** | ⚠️ 极高 |
| **`run_round`** | ~120 | ⭐ **每轮对话核心流程** | ⚠️ 极高 |
| `_run_round_agents` | ~120 | ⭐ **Agent 串行执行（性能瓶颈）** | ⚠️ 极高 |
| `_build_agent_context` | ~80 | 构建单个 agent 上下文 | ⚠️ 高 |
| `_generate_for_agent` | ~60 | 单个 agent 生成 | 中 |
| `_collect_agent_outputs` | ~50 | 收集输出 | 中 |
| `_integrate_arbiter` | ~40 | 整合输出 | 中 |
| `_is_same_as_previous` | ~15 | 检测重复 | 低 |
| `_handle_track_change_requests` | ~30 | 处理轨道变更 | 中 |
| `_handle_private_chat` | ~20 | 处理私聊 | 中 |
| `_check_werewolf_wait` | ~15 | 检查狼人杀等待 | 低 |
| `run_auto_rounds` | ~40 | 自动多轮 | 中 |
| `stop` | ~5 | 停止对话 | 低 |
| `rollback_to_round` | ~30 | 回退回合 | 中 |
| `auto_generate_character` | ~30 | AI 生成角色 | 中 |
| `auto_generate_scene` | ~30 | AI 生成场景 | 中 |
| `_load_lorebook` | ~15 | 加载知识库 | 低 |
| `set_goals` / `get_goals` | ~5 | 剧情目标 | 低 |
| `get_state` | ~20 | 获取状态 | 中 |
| `get_conversation_history` | ~10 | 获取历史 | 低 |
| `get_round_logs` | ~10 | 获取轮次日志 | 低 |
| 狼人杀方法（~20 个） | ~600 | WerewolfGameMixin 混入 | ⚠️ 极高 |

**🚩 问题**：
1. 单文件 2200+ 行，圈复杂度极高
2. `_run_round_agents` 串行执行 agent → **核心性能瓶颈**
3. 狼人杀逻辑通过 mixin 混入，导致 `self._werewolf_state` 等 10+ 个私有属性散布
4. `_build_agent_context` 每次完整复制消息列表 → 内存浪费
5. DEBUG print 遗留在生产代码中（`[DEBUG _emit]` `[DEBUG]`）

### 3.2 agent.py（~130 行）

**类：Agent**

| 方法 | 用途 | 说明 |
|------|------|------|
| `__init__` | 初始化 | 接收 Persona + LLMClient |
| `name` (property) | 返回角色名 | 代理到 persona.name |
| `client` (property) | 向后兼容 | 访问 _llm._client |
| `build_messages` | ⭐ 构建 LLM 消息列表 | 拼装 system/history/interjection |
| `generate` | 流式生成 | async generator |
| `generate_direct` | 非流式生成 | 调用 generate(stream=False) |

**🚩 问题**：
- `build_messages` 中 system 消息硬编码中文（不兼容英文模式）
- `generate` 中 yield 了两遍 full_content（第 2 遍可能多余）
- `client` 属性暴露了内部 `_llm._client`，破坏封装

### 3.3 arbiter.py（~350 行）

**类：Arbiter**

| 方法 | 用途 | 说明 |
|------|------|------|
| `configure_tracks` | ⭐ 配置本轮轨道 | 调用 LLM JSON 模式 |
| `integrate_outputs` | ⭐ 整合所有输出 | LLM 生成旁白 |
| `classify_user_input` | 分类用户输入 | 补充/切换/命令/新剧情 |
| `process_user_input` | 转换用户输入为主控旁白 | LLM 生成 |
| `generate_scene` | AI 生成场景 | LLM JSON 模式 |
| `generate_character` | AI 生成角色 | LLM JSON 模式 |
| `_default_tracks` | 默认轨道配置 | LLM 失败时的备份 |

**常量**：8 个 prompt 模板字符串（硬编码英文/中文混合）

**🚩 问题**：
- `classify_user_input` 的 LLM 调用结果并未充分利用（只用了字符串包含判断）
- prompt 模板中硬编码角色名，无法动态切换语言
- `_default_tracks` 在所有场景（含狼人杀）中做同样逻辑

### 3.4 memory.py（~250 行）

**类：MemoryStore、ShardedMemory、MemoryShard**

| 方法 | 用途 |
|------|------|
| `create_session` | 创建会话 |
| `add_message` | 添加消息（自动保存+裁剪）|
| `save_session` / `load_session` | 持久化 |
| `get_agent_context` | 获取某个 agent 可见的消息 |
| `get_compressed_context` | 压缩后上下文 |
| `get_short_term_context` | 短期记忆（最近 N 轮）|
| `get_summary_context` | 摘要上下文 |
| `set_current_tracks` | 保存轨道配置 |
| `is_low_information` | 低信息量检测 |

**ShardedMemory**：v4.2 新增的分片记忆，支持 public/private/faction/temp

**🚩 问题**：
- ShardedMemory 定义了大量方法但 Router 中**几乎没有使用**
- `get_agent_context` 使用 `id(m)` 做去重，不可靠
- `is_low_information` 默认 30 字符阈值，硬编码

### 3.5 compressor.py（~130 行）

**类：Compressor**

| 方法 | 用途 |
|------|------|
| `compress` | 压缩一批对话为结构化摘要 |
| `get_compressed_context` | 构建压缩上下文 |
| `extract_open_loops` | 提取未解决线索 |
| `should_compress` | 判断是否需要压缩 |

**🚩 问题**：
- `client` 属性向后兼容，暴露内部对象
- 压缩 prompt 只支持中文

### 3.6 persona.py（~180 行）

**类：Persona**

| 属性/方法 | 用途 |
|-----------|------|
| `system_prompt` (property) | 完整 system prompt |
| `lightweight_prompt` (property) | 精简 prompt（大多数轮次使用）|
| `fingerprint_prompt` (property) | 极简指纹 |
| `get_prompt(style)` | 按风格获取 |
| `get_prompt_by_round(round)` | 按轮次获取（每 6 轮全量校准）|
| `drift_prevention_prompt` | 防漂移 prompt |
| `to_dict` / `from_dict` | 序列化 |

**函数**：`load_personas_from_file`、`save_personas_to_file`、`get_director_prompt`

**🚩 问题**：
- `from_dict` 在类末尾**重复定义了两次**（第二个覆盖了第一个？）→ 仔细看第二个在 `get_director_prompt` 函数外面，是缩进错误导致它成了全局函数？实际上它位于 `get_director_prompt` 内部，因为 `get_director_prompt` 包含了 Persona 的 `from_dict` 方法——这是**严重的缩进错误**！`from_dict` 被错误地嵌套在了 `get_director_prompt` 函数内部。
- `DIRECTOR_CORE_PERSONA` 硬编码中文字符串

### 3.7 其他 core/ 文件

| 文件 | 行数 | 核心功能 | 问题 |
|------|------|---------|------|
| `validator.py` | ~180 | 角色输出验证 + role lock prompt | 硬编码中文 |
| `lorebook.py` | ~200 | 知识库关键词匹配 | 未被 Router 实际调用 |
| `monitor.py` | ~160 | 成本/用量追踪 | 设计良好，无大问题 |
| `scheduler.py` | ~130 | **并行调度器（未使用！）** | 定义了 `run_parallel` 但 Router 未调用 |
| `track_manager.py` | ~180 | 轨道生命周期管理 | **未被 Router 使用** |
| `track_request.py` | ~180 | 轨道变更申请系统 | 定义了 API 但 Router 中只有空壳 |
| `script_runtime.py` | ~50 | 剧本杀运行时 | 🚧 开发中，基本为空 |
| `werewolf_game.py` | ~1100 | **狼人杀全部逻辑（mixin）** | ⚠️ 大而全，与 Router 紧耦合 |
| `werewolf_arbiter.py` | ~120 | 狼人杀 LLM 仲裁 | 设计清晰 |
| `werewolf_api.py` | ~200 | 狼人杀纯函数规则引擎 | 设计良好 |
| `i18n.py` | ~200 | 多语言支持 | 定义了翻译但**未被实际使用** |

### 3.8 🔴 核心发现：大量死代码

| 模块 | 状态 | 说明 |
|------|------|------|
| `scheduler.py` (并行调度器) | ❌ 未使用 | 定义了 `run_parallel` + `AgentTask` + `SchedulerMetrics`，但 Router 的 `_run_round_agents` 仍然是串行实现 |
| `track_manager.py` (TrackManager) | ❌ 未使用 | 定义了完整的轨道生命周期管理，但 Router 只用原生的 `Track` / `TrackConfig` |
| `track_request.py` (TrackRequestManager) | ⚠️ 半使用 | API 路由注册了，但 Router 中 `_handle_track_change_requests` 方法为空 |
| `i18n.py` | ❌ 未使用 | 所有 prompt 模板仍然是硬编码中文，`t()` 函数未被任何模块调用 |
| `core/lorebook.py` 的 Lorebook | ⚠️ 半使用 | Router 初始化了 `self.lorebook` 但 `_load_lorebook` 从未被调用 |
| `ShardedMemory` | ❌ 未使用 | 写了一大套分片记忆，Router 用的还是传统 MemoryStore |

---

## 4. API 路由层 (api/) 详解

### 4.1 路由汇总

| 文件 | 前缀 | 端点数量 | 行数 |
|------|------|---------|------|
| `routes_session.py` | `/api` | 12+ | 700+ |
| `routes_characters.py` | `/api` | 6 | 120 |
| `routes_scenes.py` | `/api` | 7 | 130 |
| `routes_config.py` | `/api/config` | 8 | 150 |
| `routes_history.py` | `/api/history` | 4 | 150 |
| `routes_round.py` | `/api/round` | 3 | 60 |
| `routes_sse.py` | `/api/events` | 1 | 60 |
| `routes_voice.py` | `/api/voice` | 3 | 180 |
| `routes_auth.py` | `/api/auth` | 4 | 120 |
| `routes_room.py` | `/api/rooms` | 6 | 130 |
| `routes_track.py` | `/api/track` | 2 | 60 |
| `routes_voice.py` (额外) | `/api/voice` 内联在 routes_session.py | 3 | — |
| `routes_track.py` 内联 | `/api/track` | — | — |

### 4.2 完整 API 端点表

#### 系统

| 方法 | 路径 | 函数 | 文件 | 用途 |
|------|------|------|------|------|
| GET | `/api/state` | `get_state` | routes_session.py | 获取系统状态，惰性初始化 Router |
| POST | `/api/init` | `initialize` | routes_session.py | 初始化会话 |

#### 会话

| 方法 | 路径 | 函数 | 用途 |
|------|------|------|------|
| POST | `/api/send` | `send_message` | 发送用户消息（用于狼人杀） |
| POST | `/api/stop` | `stop_conversation` | 停止对话 |
| POST | `/api/auto` | `start_auto` | 自动运行多轮 |
| POST | `/api/mode` | `set_mode` | 切换模式 |
| GET | `/api/mode` | `get_mode` | 获取当前模式 |
| POST | `/api/goals` | `set_goals` | 设置剧情目标 |
| GET | `/api/goals` | `get_goals` | 获取剧情目标 |
| POST | `/api/agents` | `add_agent` | 添加 agent |
| DELETE | `/api/agents/{name}` | `remove_agent` | 删除 agent |

#### 角色

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/characters` | 列出所有角色 |
| POST | `/api/characters` | 创建角色 |
| PUT | `/api/characters/{name}` | 更新角色 |
| DELETE | `/api/characters/{name}` | 删除角色 |
| POST | `/api/characters/generate` | AI 生成角色 |
| POST | `/api/characters/batch` | 批量创建角色 |

#### 场景

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/scenes` | 列出所有场景 |
| POST | `/api/scenes` | 创建场景 |
| PUT | `/api/scenes/{id}` | 更新场景 |
| DELETE | `/api/scenes/{id}` | 删除场景 |
| POST | `/api/scenes/generate` | AI 生成场景 |
| POST | `/api/scenes/{id}/start` | 启动场景（⭐ 核心入口）|
| POST | `/api/scenes/{id}/enter` | 进入场景 |

#### 回合

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/round/start` | 启动回合 |
| POST | `/api/round/rollback` | 回退回合 |
| GET | `/api/round/status` | 回合状态 |

#### 历史

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/history` | 获取历史消息 |
| GET | `/api/history/sessions` | 列出所有会话 |
| GET | `/api/history/sessions/{id}` | 获取会话消息 |
| POST | `/api/history/load/{id}` | 加载会话 |

#### SSE

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/events` | SSE 事件流（长连接）|

#### 狼人杀

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/werewolf/night_action` | 夜间行动 |
| POST | `/api/werewolf/vote` | 投票 |
| GET | `/api/werewolf/status` | 游戏状态 |
| POST | `/api/werewolf/init` | 初始化狼人杀 |

#### 配置

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/config/apikey` | 获取 API Key |
| POST | `/api/config/apikey` | 设置 API Key |
| GET | `/api/config/language` | 获取语言 |
| POST | `/api/config/language` | 设置语言 |
| GET | `/api/config/models` | 获取模型推荐 |
| GET | `/api/config/voice` | 获取语音配置 |
| POST | `/api/config/voice` | 设置语音配置 |

#### 语音

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/voice/status` | 语音循环状态 |
| POST | `/api/voice/start` | 启动语音循环 |
| POST | `/api/voice/stop` | 停止语音循环 |

#### 房间

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/rooms` | 创建房间 |
| GET | `/api/rooms/{code}` | 获取房间 |
| POST | `/api/rooms/{code}/join` | 加入房间 |
| POST | `/api/rooms/{code}/leave` | 离开房间 |
| POST | `/api/rooms/{code}/assign` | 分配角色 |

#### 认证

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/auth/verify` | 验证邀请码 |
| GET | `/api/auth/me` | 获取当前用户 |
| POST | `/api/auth/admin/generate` | 生成邀请码 |
| GET | `/api/auth/admin/list` | 列出邀请码 |
| POST | `/api/auth/admin/deactivate` | 停用邀请码 |

#### 轨道请求

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/track/request` | 提交轨道变更申请 |
| GET | `/api/track/requests` | 列出所有申请 |

#### 其他（内联在 routes_session.py）

| 方法 | 路径 | 用途 |
|------|------|------|
| GET | `/api/voice/toggle` | 获取语音开关 |
| POST | `/api/voice/toggle` | 设置语音开关 |
| POST | `/api/script/generate` | 生成剧本 |
| POST | `/api/private_chat/request` | 请求私聊 |
| POST | `/api/private_chat/reply` | 回复私聊请求 |
| POST | `/api/private_chat/send` | 发送私聊消息 |

---

## 5. 基础设施服务 (services/) 详解

| 文件 | 类/函数 | 用途 | 行数 |
|------|---------|------|------|
| `llm_client.py` | `LLMClient` | 共享 LLM HTTP 客户端 | 160 |
| `persistence.py` | `AtomicFileStorage`、`CharacterStore`、`SceneStore` | 原子 JSON 持久化 | 160 |
| `session_manager.py` | `SessionManager` | 会话生命周期 | 120 |
| `private_chat.py` | `PrivateChatManager` | 私聊管理 | 30 |
| `tts_service.py` | `stream_tts`、`_edge_stream`、`_cosyvoice_stream` | TTS 流式合成 | 150 |
| `web_search.py` | `web_search`、`web_fetch_content` | DuckDuckGo 搜索 | 80 |
| `whisper_service.py` | `transcribe_audio` | 语音识别 | — |
| `invite_service.py` | `verify_code`、`use_code` 等 | 邀请码管理 | — |
| `namespace.py` | 命名空间隔离 | 多租户 | — |

### 5.1 LLMClient 核心流程

```
chat_completion(messages, model, ...)
  → for model in [primary, fallback]:        # 模型降级
      for retry in [0, 1]:                    # 重试 2 次
        → await client.chat.completions.create()
        → monitor.record_usage()
        → return response
  → raise RuntimeError                        # 全部失败

call_json(prompt, ...)
  → for attempt in [0, 1, 2]:                 # 3 次尝试
      → chat_completion(...)
      → 提取 JSON（去 markdown fence）
      → json.loads()
      → return dict
  → return {}                                 # 全部失败
```

---

## 6. 数据模型 (models/) 详解

### 6.1 domain.py — 领域模型

| 类 | 用途 | 关键字段 |
|----|------|---------|
| `TrackMode` (Enum) | 轨道模式 | MERGED / WEAK / ISOLATED |
| `Track` | 单个轨道 | id, agents, agent_actions, mode, color |
| `TrackConfig` | 整轮轨道配置 | tracks[], round, description |
| `Message` | 消息 | role, name, content, timestamp, track_id, visible_to |
| `StructuredSummary` | 结构化摘要 | arc, key_events, open_loops, tension |
| `CompressedChunk` | 压缩块 | chunk_id, summary, key_events |
| `Session` | 完整会话 | messages[], summaries, compressed_chunks[] |
| `NightAction` | 狼人杀夜间行动 | action_type, target, source |
| `WerewolfGameState` | 狼人杀游戏状态 | 全部游戏状态 |
| `IMPORTANCE_*` | 重要性常量 | 1/5/7/8 |

### 6.2 schemas.py — Pydantic 请求/响应模型

| 模型 | 用途 |
|------|------|
| `CharacterRequest` / `CharacterUpdateRequest` | 角色 CRUD |
| `SceneRequest` / `SceneGenerateRequest` | 场景 CRUD |
| `InitRequest` | 会话初始化 |
| `SendRequest` | 发送消息 |
| `ModeRequest` | 模式切换 |
| `NightActionRequest` / `VoteRequest` | 狼人杀输入 |
| `HistoryMessageResponse` / `HistoryResponse` | 历史输出 |
| `Script*` 系列 | 剧本杀模型 |
| `RouterStateResponse` / `StateResponse` | 状态输出 |

---

## 7. 游戏系统 (games/) 详解

| 文件 | 类/函数 | 用途 | 状态 |
|------|---------|------|------|
| `engine.py` | `GameEngine` | 游戏引擎基类 | 🚧 基本为空 |
| `schema.py` | `RoleDef` | 角色定义 | ✅ |
| `werewolf_engine.py` | `WerewolfEngine` | 狼人杀引擎 | ✅ 完成 |

---

## 8. 前端架构

### 8.1 文件结构

```
frontend/src/
├── main.tsx                        # 入口
├── App.tsx                         # 根组件：SSE + 条件渲染
├── App.css / index.css             # 样式
│
├── api/
│   ├── client.ts                   # API 客户端（全部端点封装）
│   └── useSSE.ts                   # SSE 自定义 Hook（自动重连）
│
├── store/
│   └── appStore.ts                 # Zustand 全局状态（单一 store）
│
├── types/
│   └── index.ts                    # TypeScript 类型定义
│
├── services/
│   └── ttsPlayer.ts                # TTS 音频播放器
│
├── styles/
│   ├── global.css                  # 全局样式（29KB ⚠️ 庞大）
│   ├── home.css                    # 首页样式
│   ├── login.css                   # 登录样式
│   ├── material.css                # ⚠️ 未使用的样式
│   └── voice.css                   # 语音样式
│
└── components/
    ├── LoginPage/LoginPage.tsx     # 登录页
    ├── HomePage/HomePage.tsx       # 首页（模式选择）
    ├── ScenePage/ScenePage.tsx     # 场景设置（28KB ⚠️ 庞大）
    ├── ChatPage/ChatPage.tsx       # 聊天主界面（29KB ⚠️ 庞大）
    ├── HistoryPanel/HistoryPanel.tsx # 历史面板
    ├── MaterialPage/MaterialPage.tsx # ⚠️ 死代码（未使用）
    ├── SettingsPage/SettingsPage.tsx # 设置页
    ├── SettingsPage/SettingsPage.css # 设置页样式
    └── ChatPage.tsx.bak            # ⚠️ 备份文件
```

### 8.2 页面路由

由 `store.view` 条件渲染控制（非 React Router）：

| view 值 | 组件 | 说明 |
|---------|------|------|
| (未登录) | LoginPage | 显示验证码登录 |
| `'home'` | HomePage | 首页：模式选择 + 最近故事 |
| `'scene'` | ScenePage | 场景选择 + 角色选择 + 狼人杀设置 |
| `'chat'` | ChatPage | 聊天主界面 |
| `'config'` | SettingsPage | 设置 + 素材管理 |

### 8.3 Zustand Store (appStore.ts)

| 状态/方法 | 用途 |
|-----------|------|
| `token` / `setToken` | JWT 认证 |
| `view` / `setView` | 页面路由 |
| `wsUrl` | WebSocket 地址 |
| `scenes` / `characters` | 场景/角色列表 |
| `messages` / `addMessage` | 聊天消息 |
| `mode` / `setMode` | 游戏模式 |
| `protagonist` / `directorCharacter` | 模式配置 |
| `goals` / `setGoals` | 剧情目标 |
| `loadState()` | 加载系统状态 |
| `enterScene()` | 进入场景 |
| `startRound()` | 开始回合 |
| `sendMessage()` | 发送消息 |
| `stop()` | 停止对话 |
| `loadHistory()` | 加载历史 |
| `login()` | 登录 |
| `createRoom()` / `joinRoom()` | 房间操作 |
| `startVoice()` / `stopVoice()` | 语音循环 |

### 8.4 SSE 事件流 (useSSE.ts)

| 事件类型 | 前端处理 | 说明 |
|---------|---------|------|
| `agent_output` | addMessage | 添加 agent 消息 |
| `system_message` | addMessage | 系统消息 |
| `round_complete` | updateStatus | 回合完成 |
| `round_start` | clearMessages? | 回合开始 |
| `werewolf_update` | updateGameStatus | 狼人杀更新 |
| `error` | showError | 错误 |
| `phase_change` | updatePhase | 阶段变化 |

---

## 9. 前后端映射关系

### 9.1 功能-端点-组件映射

| 功能 | 后端端点 | 前端调用者 | 数据流 |
|------|---------|-----------|--------|
| 登录 | POST `/api/auth/verify` | LoginPage → appStore.login | 验证码 → JWT |
| 首页加载 | GET `/api/state` | App 挂载 → appStore.loadState | 状态 + 角色/场景列表 |
| 进入场景 | POST `/api/scenes/{id}/start` | ScenePage → appStore.enterScene | 场景ID → 创建 Router |
| 开始回合 | POST `/api/round/start` | ChatPage → appStore.startRound | 回合数 → SSE 流式推送 |
| 发送消息 | POST `/api/send` | ChatPage → appStore.sendMessage | 文本 → SSE |
| 停止对话 | POST `/api/stop` | ChatPage → appStore.stop | — |
| 切换模式 | POST `/api/mode` | ChatPage → appStore.setMode | 模式名 |
| 设目标 | POST `/api/goals` | ChatPage 设置 → appStore.setGoals | 目标列表 |
| 回退 | POST `/api/round/rollback` | ChatPage | 轮次号 |
| 角色 CRUD | GET/POST/PUT/DELETE `/api/characters` | ScenePage / SettingsPage | JSON |
| 场景 CRUD | GET/POST/PUT/DELETE `/api/scenes` | ScenePage / SettingsPage | JSON |
| AI 生成角色 | POST `/api/characters/generate` | ScenePage / SettingsPage | 关键词 → JSON |
| AI 生成场景 | POST `/api/scenes/generate` | ScenePage / SettingsPage | 关键词 → JSON |
| 历史列表 | GET `/api/history/sessions` | HomePage / HistoryPanel | — |
| 加载历史 | POST `/api/history/load/{id}` | HomePage / HistoryPanel | ID → 重建 Agent |
| 狼人杀初始化 | POST `/api/werewolf/init` | ScenePage | 角色配置 |
| 狼人杀状态 | GET `/api/werewolf/status` | ScenePage (轮询) | — |
| 配置 API Key | GET/POST `/api/config/apikey` | SettingsPage / HomePage | — |
| 配置语言 | GET/POST `/api/config/language` | SettingsPage | — |
| 模型推荐 | GET `/api/config/models` | SettingsPage | — |
| 创建房间 | POST `/api/rooms` | HomePage | — |
| 加入房间 | POST `/api/rooms/{code}/join` | HomePage | — |
| 语音循环 | POST `/api/voice/start\|stop` | ChatPage | — |
| SSE 流 | GET `/api/events` | App.tsx → useSSE hook | 长连接 |

### 9.2 前后端类型对应

| 前端类型 | 后端模型 | 字段对应 |
|---------|---------|---------|
| `Character` | `Persona` / `CharacterResponse` | name, persona, voice, background |
| `Scene` | `SceneResponse` | scene_id, name, description, initial_agent_names |
| `Message` | `Message` | role, name, content, timestamp, track_id, visible_to |
| `TrackConfig` | `TrackConfig` | tracks[], description |
| `Room` | routes_room.py 内联 | code, mode, host, players, assignments |
| `WerewolfState` | `WerewolfStatusResponse` | phase, round, alive, your_role |

---

## 10. 死代码与屎山分析

### 🔴 死代码清单

| # | 位置 | 内容 | 行数 | 说明 |
|---|------|------|------|------|
| 1 | `core/scheduler.py` | 完整文件 | **130** | 定义了 `run_parallel`、`AgentTask`、`SchedulerMetrics`，Router 从未调用 |
| 2 | `core/track_manager.py` | 完整文件 | **180** | TrackManager + TrackInstance，Router 未使用 |
| 3 | `core/i18n.py` | 完整文件 | **200** | 定义了 `t()` 翻译函数 + 完整中英字典，未被任何模块调用 |
| 4 | `core/lorebook.py` 的 `load_from_yaml` | 方法 | 30 | Router 创建了 `self.lorebook` 但 `_load_lorebook` 从未执行 |
| 5 | `core/memory.py` 的 `ShardedMemory` | 完整类 | **120** | 定义了分片记忆，Router 只用传统 MemoryStore |
| 6 | `frontend/MaterialPage.tsx` | 完整文件 | **250** | 独立素材库页面，无人路由可达 |
| 7 | `frontend/styles/material.css` | 完整文件 | 50 | MaterialPage 的样式，随页面成为死代码 |
| 8 | `frontend/App.css` | 完整文件 | 80 | Vite 默认计数器样式，类名无配对 |
| 9 | `frontend/assets/hero.png` | 文件 | — | Vite 默认模板资源 |
| 10 | `frontend/assets/react.svg` | 文件 | — | Vite 默认模板资源 |
| 11 | `frontend/assets/vite.svg` | 文件 | — | Vite 默认模板资源 |
| 12 | `frontend/ChatPage.tsx.bak` | 文件 | **600** | 备份文件不应在仓库中 |
| 13 | `frontend/common/`、`Modals/`、`Sidebar/` | 目录 | 3个 | 空目录 |
| 14 | `core/werewolf_game.py` 中大量重复 | ~200 行 | — | 与 werewolf_api.py + werewolf_arbiter.py 逻辑重叠 |
| 15 | `core/router.py` 的 `_handle_track_change_requests` | 方法 | 30 | 空壳方法，只打印日志 |
| 16 | `core/router.py` 的 `_handle_private_chat` | 方法 | 20 | 空壳方法 |

### 🩻 屎山代码清单

| # | 位置 | 问题 | 严重度 |
|---|------|------|--------|
| 1 | `core/router.py` | 单文件 **2200+ 行**，包含 Router + WerewolfGameMixin + 所有编排逻辑 | 🔴 致命 |
| 2 | `core/router.py` `_run_round_agents` | **串行执行 agent** → 5 个 agent 需 5 倍 LLM 时间 | 🔴 性能瓶颈 |
| 3 | `core/router.py` 全部 | DEBUG print 残留（`[DEBUG _emit]` `[DEBUG]`）| 🟡 中 |
| 4 | `core/router.py` `_emit` | 狼人杀可见性过滤逻辑与核心事件系统紧耦合 | 🟡 中 |
| 5 | `core/werewolf_game.py` | 作为 mixin 混入 Router，导致 **100+ 行 init** + 10+ 属性散布 | 🔴 严重 |
| 6 | `core/persona.py` | `from_dict` **缩进错误**嵌套在 `get_director_prompt` 函数中 | 🔴 BUG |
| 7 | `backend/api/routes_session.py` | 狼人杀 + 私聊 + 剧本杀 + 语音端点全部内联在同一个文件中 | 🟡 中 |
| 8 | 全部 prompt 模板 | 中英文混杂、硬编码 Agent 名、无模板引擎 | 🟡 中 |
| 9 | `core/arbiter.py` `classify_user_input` | 调了 LLM 但只用字符串包含判断结果 | 🟡 中 |
| 10 | 前端 `ChatPage.tsx` | 单文件 800+ 行，内联狼人杀面板 | 🟡 中 |
| 11 | 前端 `ScenePage.tsx` | 单文件 700+ 行，角色/场景/狼人杀配置挤在一起 | 🟡 中 |
| 12 | 前端 `ChatPage.tsx` 中 `startVoice()` | 使用 `document.querySelector` 直接操作 DOM | 🔴 反模式 |
| 13 | `main.py` | `uvicorn.run` 无 `workers=N` 参数 | 🟡 性能问题 |
| 14 | `core/agent.py` `generate` | yield 了两遍 full_content | 🟡 潜在 bug |
| 15 | 前端 appStore.ts | 单一 store 包含所有状态（认证/房间/聊天/语音/素材） | 🟡 中 |

### 📊 代码健康度统计

| 指标 | 数值 |
|------|------|
| 后端总代码行数 | ~7500 |
| 死代码行数 | ~800（10.7%）|
| 屎山代码行数 | ~2500（33%）|
| 核心逻辑（router.py）行数 | 2200+ |
| 后端文件数 | 53 |
| 前端总代码行数 | ~4500 |
| 前端死代码行数 | ~350（7.8%）|
| 空/未使用 API 端点 | ~8 个 |

---

## 11. 功能统一对应表

### 11.1 按功能分类

| 功能域 | 后端文件 | 前端文件 | API 端点 | 数据模型 |
|--------|---------|---------|----------|---------|
| **认证登录** | `middleware/auth.py`、`routes_auth.py`、`invite_service.py`、`namespace.py` | `LoginPage.tsx`、`appStore.ts` | `/api/auth/*` | JWT payload |
| **角色管理** | `routes_characters.py`、`core/persona.py`、`persistence.py`(CharacterStore) | `ScenePage.tsx`、`SettingsPage.tsx`、~~`MaterialPage.tsx`~~ | `/api/characters*` | `Persona` / `CharacterRequest` |
| **场景管理** | `routes_scenes.py`、`persistence.py`(SceneStore) | `ScenePage.tsx`、`SettingsPage.tsx`、~~`MaterialPage.tsx`~~ | `/api/scenes*` | `SceneRequest` |
| **会话/聊天** | `routes_session.py`、`core/router.py`、`services/session_manager.py` | `ChatPage.tsx`、`appStore.ts` | `/api/send`、`/api/start`、`/api/stop` | `Message` / `SendRequest` |
| **回合系统** | `routes_round.py`、`core/router.py`(run_round) | `ChatPage.tsx`、`appStore.ts` | `/api/round/*` | `RoundRequest` |
| **SSE 流** | `routes_sse.py` | `App.tsx`、`useSSE.ts` | `/api/events` | SSE 事件 |
| **铁轨系统** | `core/router.py`、`core/arbiter.py`、`core/track_manager.py`(死) | `ChatPage.tsx`(显示轨道) | — | `Track` / `TrackConfig` |
| **模式切换** | `routes_session.py`(set_mode)、`core/arbiter.py` | `appStore.ts`(setMode) | `/api/mode` | `ModeRequest` |
| **剧情目标** | `routes_session.py`(goals) | `appStore.ts`(setGoals) | `/api/goals` | `GoalsRequest` |
| **狼人杀** | `routes_session.py`(werewolf)、`core/werewolf_game.py`、`core/werewolf_arbiter.py`、`core/werewolf_api.py`、`games/werewolf_engine.py` | `ScenePage.tsx`(设置)、`ChatPage.tsx`(面板) | `/api/werewolf/*` | `WerewolfGameState` |
| **剧本杀** | `core/script_runtime.py`、`routes_session.py`(script) | `ScenePage.tsx` | `/api/script/*` | `ScriptConfig` |
| **联机房间** | `routes_room.py` | `HomePage.tsx`、`appStore.ts` | `/api/rooms/*` | 内存 dict |
| **私聊** | `services/private_chat.py`、`routes_session.py`(private_chat) | 未实现前端 UI | `/api/private_chat/*` | `PrivateChatRequest` |
| **TTS 语音** | `services/tts_service.py`、`routes_session.py`(voice/toggle) | `ttsPlayer.ts`、`ChatPage.tsx` | `/api/voice/toggle` | MP3 流 |
| **语音循环** | `routes_voice.py` | `ChatPage.tsx`(startVoice) | `/api/voice/start\|stop\|status` | — |
| **配置** | `routes_config.py`、`config.py` | `SettingsPage.tsx`、`HomePage.tsx` | `/api/config/*` | `ApiKeyRequest` |
| **历史** | `routes_history.py` | `HomePage.tsx`、`HistoryPanel.tsx`、`appStore.ts` | `/api/history/*` | `HistoryResponse` |
| **轨道请求** | `routes_track.py`、`core/track_request.py` | 未实现前端 UI | `/api/track/*` | `TrackChangeRequest` |
| **素材管理** | — | `MaterialPage.tsx`(死) | 复用角色/场景 API | — |

### 11.2 功能重复/重叠

| 功能 | 实现数量 | 涉及文件 | 建议 |
|------|---------|---------|------|
| 角色 CRUD | 3 套前端 | ScenePage + SettingsPage + ~~MaterialPage~~ | 统一到 SettingsPage |
| 场景 CRUD | 3 套前端 | ScenePage + SettingsPage + ~~MaterialPage~~ | 统一到 SettingsPage |
| AI 生成角色 | 3 套前端 | 同上 | 同上 |
| 狼人杀角色映射 | 3 套 | App.tsx ×2 + ChatPage.tsx ×1 | 统一成一个常量 |
| normalizePhase | 2 套 | App.tsx + ChatPage.tsx | 统一到 types/index.ts |
| LLM 调用 | 1 套但 2 种返回 | `chat_completion` + `call_json` | 合理，保留 |
| 轨道管理 | 3 套 | Router 原生 Track + TrackManager(死) + TrackRequest(半) | 清理死代码，统一 |

---

## 12. 清理建议与迁移路径

### 12.1 立即可以清理的（安全删除）

```
删除：
  core/scheduler.py                    # 130 行死代码，替代方案已存在
  core/track_manager.py                # 180 行死代码
  core/i18n.py                         # 200 行死代码（或等 Java 迁移时再引入）
  frontend/src/components/MaterialPage/ # 250 行死代码 + 样式
  frontend/src/assets/hero.png         # Vite 默认模板
  frontend/src/assets/react.svg        # Vite 默认模板
  frontend/src/assets/vite.svg         # Vite 默认模板
  frontend/src/App.css                 # Vite 默认样式
  frontend/src/styles/material.css     # MaterialPage 样式
  frontend/src/components/ChatPage.tsx.bak  # 备份文件
  frontend/src/components/common/      # 空目录
  frontend/src/components/Modals/      # 空目录
  frontend/src/components/Sidebar/     # 空目录
```

### 12.2 需要重构的

| 优先级 | 内容 | 工作量 | 收益 |
|--------|------|--------|------|
| P0 | router.py 拆分为 Router + RoundOrchestrator + GameState | 2 天 | 🔴 极高 |
| P0 | _run_round_agents 改为并行（asyncio.gather） | 2 小时 | 🔴 极高 |
| P0 | 删除 DEBUG print | 10 分钟 | 🟡 中 |
| P1 | werewolf_game.py 从 mixin 改为独立 Service | 1 天 | 🟡 中 |
| P1 | 前端 ScenePage + SettingsPage 去重 | 1 天 | 🟡 中 |
| P1 | 前端 appStore 拆分为 auth/chat/room/voice store | 1 天 | 🟡 中 |
| P2 | prompt 模板统一 + i18n 接入 | 2 天 | 🟢 低 |
| P2 | 前端 React Router 替换条件渲染 | 1 天 | 🟢 低 |

### 12.3 Java 迁移路径（基于以上分析）

如果决定用 Java 重写，按此顺序：

```
Phase 0（清理）: 删除所有死代码 → 减少 15% 无用代码
Phase 1（引擎）: core/router.py → Java RouterService
                  core/arbiter.py → Java ArbiterService
                  core/agent.py → Java AgentExecutor（并行！）
                  models/domain.py → Java POJO

Phase 2（持久化）: services/persistence.py → Java JPA/文件存储
                    services/session_manager.py → Java SessionRepository

Phase 3（API层）: api/routes_*.py → Java Spring Boot @RestController
                  → 前端 API client.ts 只需改 baseUrl

Phase 4（游戏）: games/ + werewolf_* → Java GameEngine
                  core/track_request.py → Java TrackService

Phase 5（前端）: 保持 TypeScript，只需改 API 地址
```

---

## 附录 A：行数统计

| 目录 | 文件数 | 总行数 |
|------|--------|--------|
| `backend/core/` | 17 | ~4200 |
| `backend/api/` | 13 | ~1800 |
| `backend/services/` | 9 | ~800 |
| `backend/models/` | 2 | ~400 |
| `backend/games/` | 3 | ~200 |
| `backend/` (根目录) | 3 | ~100 |
| **后端总计** | **53** | **~7500** |
| `frontend/src/` | 16 | ~4500 |
| **总计** | **69** | **~12000** |

## 附录 B：全部 Class 清单

```
backend/
├── AppConfig (config.py)
├── LLMConfig / MemoryConfig / ArbiterConfig / ... (config.py)
│
├── Router (core/router.py)           ← 2200+ 行，最大类
├── Agent (core/agent.py)
├── Arbiter (core/arbiter.py)
├── MemoryStore / ShardedMemory / MemoryShard (core/memory.py)
├── Compressor (core/compressor.py)
├── Lorebook / LoreEntry (core/lorebook.py)
├── Monitor / UsageRecord (core/monitor.py)
├── Persona (core/persona.py)
├── WerewolfGameMixin (core/werewolf_game.py)
├── WerewolfArbiter (core/werewolf_arbiter.py)
├── TrackInstance / TrackManager (core/track_manager.py) ← 死代码
├── TrackChangeRequest / TrackRequestManager (core/track_request.py)
├── AgentTask / SchedulerMetrics (core/scheduler.py) ← 死代码
│
├── ValidationResult / Violation (core/validator.py)
│
├── LLMClient (services/llm_client.py)
├── AtomicFileStorage / CharacterStore / SceneStore (services/persistence.py)
├── SessionManager (services/session_manager.py)
├── PrivateChatManager (services/private_chat.py)
│
├── Session / Message / Track / TrackConfig / WerewolfGameState / ... (models/domain.py)
├── CharacterRequest / SendRequest / ModeRequest / ... (models/schemas.py)
│
├── GameEngine (games/engine.py)
├── WerewolfEngine (games/werewolf_engine.py)
│
├── RoleDef (games/schema.py)
│
├── PrivateChatRequest (services/private_chat.py)
```

```
frontend/src/
├── App (App.tsx)
├── LoginPage (components/LoginPage/LoginPage.tsx)
├── HomePage (components/HomePage/HomePage.tsx)
├── ScenePage (components/ScenePage/ScenePage.tsx)
├── ChatPage (components/ChatPage/ChatPage.tsx)
├── HistoryPanel (components/HistoryPanel/HistoryPanel.tsx)
├── SettingsPage (components/SettingsPage/SettingsPage.tsx)
├── MaterialPage (components/MaterialPage/MaterialPage.tsx) ← 死代码
├── TTSPlayer (services/ttsPlayer.ts)
├── useSSE (api/useSSE.ts) ← 自定义 Hook
└── useAppStore (store/appStore.ts) ← Zustand Store
```
