# UI 与 API 现状调研 —「重做进入游戏后的前端 UI + 后端 API 可视化逻辑链」决策依据

> ⚠️ 本文档为**纯调研产出**（2026-08-09 12:45 批次，调研 agent），未修改任何代码。
> 调研范围：前端技术栈 /「进入游戏后」UI 现状 / 后端 Controller API 清单 / 前端调用方式 / API 可视化逻辑链方案建议。
> 前置阅读：`PROJECT_CONTEXT.md` / `DECISION_LOG.md` / `AGENTS.md`。所有结论均经源码核验（行号/文件可回溯），无推测性内容。

---

## 1. 前端技术栈

| 项 | 现状 | 说明 |
|---|---|---|
| 框架 | **React 19.2**（`react`/`react-dom` ^19.2.7） | 函数组件 + Hooks，无 Router 库（视图切换用 Zustand store 的 `view` 字段 + 历史栈，见 `demo2/store.ts`） |
| 状态管理 | **Zustand 5**（`demo2/store.ts` 导航/选择态 + `store/appStore.ts` 对局态） | appStore 持有全部对局运行态（messages/agents/scriptState/werewolfPhase 等），SSE/轮询写入，组件只读 |
| 构建工具 | **Vite 5.4** + TypeScript ~6.0（`tsc -b && vite build`，66-74 modules） | `vite.config.ts`：dev 端口 5173，**代理 `/api` → `http://localhost:8000`**、`/simulation.html` → 8000 |
| 2D 渲染 | **Phaser 3.90**（`src/phaser/`，D-020 三阶段闭环） | PhaserScriptMapView（地图）/ PhaserSimulationView（2D 世界），数据流=后端权威模拟+前端纯渲染 |
| 入口 | `frontend/index.html` → `src/main.tsx` → `src/App.tsx` → **`demo2/App2.tsx`** | **App2 是当前活跃应用壳**（P2-0805 定案架构）；`AppLegacy.tsx`（整机版，含 useSSE 接线）与 `demo/`、`components/ScenePage/ScenePage.tsx` 为**不参与构建的死代码**（但 tsconfig 全量 include，仍被 tsc 检查） |
| 目录结构 | `src/api/`（client.ts 统一请求封装、useSSE.ts SSE 钩子）· `src/store/` · `src/components/`（ChatPage/HomePage/HistoryPanel/ScriptDmPanel/AnnouncementBanner/Ticker…）· `src/demo2/`（新壳 6 页面 + GameBridge）· `src/phaser/` · `src/types/` · `src/utils/` · `src/styles/` | 见下「活跃页面路由」 |
| 静态资源 | **双份**：①开发静态资源 `frontend/public/`（favicon.svg、icons.svg）；②**生产静态资源 `src/main/resources/static/`**（Spring Boot jar 直接服务：`index.html` 464B + `assets/index-*.js|css` + `simulation.html` 30KB 旧 2D 页 + `assets/` 素材库 + `simulation/` 独立 demo） | **构建产物同步为手工流程**：`vite build` 出 `frontend/dist/` → 手动拷到 `static/assets/` → 更新 `static/index.html` 引用。当前 `static/index.html` 引用 `index-DrPjpy7W.js` + `index-laRQDEWy.css`；目录内残留旧产物 `index-C03zxnb0.js`（未清理干净，重做 UI 时应理顺发布链路） |

### 活跃页面路由（App2.tsx NAV）

```
模式选择(HomePage) → 剧本选择(ScriptSelectPage) → 角色选择(RoleSelectPage)
   ├─ 剧本杀 murder → GameBridge → ChatPage（对局 UI）
   ├─ 一般·自由聊天 general+chat → GameBridge → ChatPage
   ├─ 一般·2D探索 general+explore → GameBridge → PhaserSimulationView（整页 2D）
   └─ 狼人杀 werewolf → GameBridge → ChatPage（狼人杀面板）
其余：剧本生成(ScriptGenPage) / 角色库(RoleLibPage) / 角色详情(RoleDetailPage) / 设置(SettingsPage)
```

---

## 2. 「进入游戏后」UI 现状

**入口**：`demo2/pages/GameBridge.tsx`（launcher：按模式调后端 init → 写 store → 挂载对局 UI，launching/ready/error 三态）。
**对局主界面**：`components/ChatPage/ChatPage.tsx`（**1875 行 / 97KB 单文件**，承载 free/director/werewolf/script 四模式）。

### 2.1 布局（`app-shell` → `topbar` + `workspace` 三列网格）

```
┌─────────────────────────── topbar ───────────────────────────────┐
│ Brand(Roleplay v4+场景描述) | 运行状态pill | 第N轮 | 🎬导演 | ⚙️设置 |
│ [剧本杀时: 🎛主持人 | 💬私聊 | 🎨美术] | 场景 | 角色库 | 📋历史      │
├──────────┬──────────────────┬──────────────────────────────────────┤
│ panel-left│ panel-werewolf   │ chat-main                            │
│ 角色列表  │ (狼人杀/剧本杀    │ ① 内嵌 2D 面板(可折叠,420px)         │
│ (+/-/🔊/×)│  状态面板,按模式  │ ② loading 进度条/状态文字            │
│ 筛选chips│  条件渲染)        │ ③ phase-banner 阶段横幅(含倒计时)     │
│          │                  │ ④ presence-bar 在场状态条             │
│          │                  │ ⑤ conversation 消息流                 │
│          │                  │ ⑥ task-box 本轮任务分配               │
│          │                  │ ⑦ composer 输入区(三轮/结束/输入/🎤/发送)│
└──────────┴──────────────────┴──────────────────────────────────────┘
右侧抽屉(drawer)：🎬导演面板(目标/场景事实/当前轨道/系统状态) · 📋历史
                · 🎛ScriptDmPanel · 💬私聊 · 🎨对局美术 · ⚙️设置(popover)
                · 🔄恢复对局(重连)折叠区(script/werewolf 各一套)
```

### 2.2 模式面板清单（均在 `panel-werewolf` 列，按 store.mode 条件渲染）

**狼人杀**（`WerewolfStatePanel` + `WerewolfActionPanel`，P-0802-F/I）：
- 状态面板：阶段 emoji + 第 N 轮 / 我的身份 / 🟢存活列表 / ❌已出局（roleRevealed 显身份）/ 轮次计数
- 行动面板（按阶段+身份渲染）：夜间——狼人刀杀/预言家查验/女巫「先获知被刀者再决策」（救/不用解药/不用毒/毒药）；白天讨论——AI 讨论流 + 人类发言输入；投票——目标 chips + 投票按钮 + 审批门「✅批准/❌驳回」；出局猎人——反杀开枪
- 折叠区：🔄 恢复狼人杀对局（session_id/房间码 + **roleKey 必填**）

**剧本杀**（`ScriptStatePanel`，props 驱动 + 3s 轮询 store 写入）：
- 阶段头：🎭🔍🗣️🗳️🎬🏁 六态（setup/investigation/discussion/vote/reveal/ended）
- 提示条：⚠️ LLM 降级（llm_degraded）/ 🤖 已托管（trustees）
- 我的身份框 + ⚡行动点（ap/ap_max）+ 🔒我的秘密（your_secret，仅本人可见）
- 搜证区（investigation）：📍地点按钮列表（耗 AP）+ 搜证结果/行动点不足提示 + 本次搜证线索 + 公开线索
- 📋我持有的线索（my_clues）+ 🔁线索转交（select 目标玩家 → transfer_clue）
- 阶段推进按钮：结束搜证→讨论 / 结束讨论→投票
- 🚪退出对局（AI 代管）/ 🗺️ 2D 空间讨论入口（内嵌 PhaserScriptMapView 或 PhaserSimulationView）
- chat 模式（简单对话版）：隐藏搜证区/2D 讨论区；有地图时只读氛围地图入口
- 投票区：嫌疑人 chips（托管者 🤖 标）+ 投票 + 揭晓真相；弃票/低参与度提示
- 揭晓区（reveal）：得票最多/结果/真相/🏁结束对局按钮
- 终局区（ended）：被定罪/真凶/判定/真相 + 🔄再来一局（同剧本）+ 📋回到剧本选择
- 折叠区：🔄 恢复对局（重连）

**一般模式（free/director）**：panel-left 角色管理 + chat-main 消息流（track_id/track_label/track_mode 徽标：强链/弱链/隔离）+ 导演抽屉（剧情目标/场景事实/当前轨道/系统状态）。

**2D 探索模式**：`PhaserSimulationView` 整页（560px 高，WASD 漫游/缩放/全屏/小地图/气泡打字机/公告横幅+公告栏/群组加入离开入口）。

### 2.3 交互数据流要点

- **剧本杀/狼人杀状态 = 3s 轮询**（`api.scriptStatus` / `api.werewolfStatus`），SSE 本应优先但**当前活跃构建未接线**（见 §4.3 关键发现①）
- 所有对局动作按钮 = REST 调用 → 成功后**立即本地 refreshScript() 拉一次状态**（不等轮询），失败大部分 catch 静默或 toast（`store.addSystemMsg`）
- 消息渲染：SSE agent_token 流式逐字（仅 AppLegacy 接线，活跃构建下退化为轮询/无流式）；`SilenceTurn` 静默占位（utils/silenceMarker.tsx）

---

## 3. 后端 API 清单（22 个 Controller，约 150 端点）

> 数据源：`src/main/java/com/roleplay/engine/` 全部 `*Controller.java` 的 `@*Mapping` 实扫。以下按「游戏内交互」与「外围管理」分组；游戏内交互为 UI 重做的核心消费面。

### 3.1 游戏内交互核心（进入游戏后）

**SessionController（`/api`，一般模式主链路）**

| 方法 | 路径 | 用途 | 请求/响应要点 |
|---|---|---|---|
| GET | /api/state | 全量会话状态（前端 loadState 入口） | ?session_id 可选；返回 mode/session_id/agents/characters/场景描述/goals/messages/round/running 等 |
| POST | /api/init | 初始化会话（角色列表） | body `{characters[]}` |
| POST | /api/send | 人类发言（主控/角色身份） | body `{text, player_name}`；SSE user_input + agent 回显 |
| POST | /api/stop | 停止本轮 | → `{status:"stopped"}` |
| POST | /api/interrupt | D1 中断 | body `{task_id?, reason?}` |
| GET | /api/interrupt/tasks[/{taskId}] | 中断任务列表/详情 | |
| POST | /api/auto | 自动推进 N 轮 | body `{rounds}` |
| POST/GET | /api/mode | 设置/读取模式 | body `{mode, protagonist, director_character}`；free/director/werewolf/script |
| POST/GET | /api/goals | 剧情目标增删查 | |
| POST | /api/agents | 添加角色 | body `{name}` |
| DELETE | /api/agents/{name} | 移除角色 | |
| GET/POST | /api/voice/toggle | 角色语音开关 | |
| POST | /api/script/generate | 剧本生成（旧桥） | body `{theme, ...}` → schema v1 |
| POST | /api/private_chat/{request,reply,send} | 私聊（旧版，未接前端） | |

**RoundController（`/api/round`）**

| 方法 | 路径 | 用途 | 要点 |
|---|---|---|---|
| POST | /api/round/start | 推进一轮/多轮 | body `{turns}`；一般模式（serial 已默认开启 D-027） |
| POST | /api/round/rollback | 回滚到第 N 轮 | body `{round}` |
| GET | /api/round/status | 轮次状态 | |

**ScriptController（`/api/script`，剧本杀 21 端点，最核心）**

| 方法 | 路径 | 用途 | 请求/响应要点 |
|---|---|---|---|
| POST | /init | 开局（剧本生成+角色分发+落库+SSE） | body `{theme, players[], mode?('full'\|'chat'), room_code?}`；超时 600s（LLM 串行）；→ session_id/phase/players/your_role/role_key/mode |
| POST | /map | 生成/获取对局地图 | body `{session_id?, theme?, seed?, width?, height?, regenerate?}`；LLM→契约 v1 校验→BSP 降级；缓存命中 |
| POST | /map/switch | door zone 触发切图（多图注册表） | body `{session_id?, door_zone_id, x?, y?, target_map_id?}`；全员同步，足迹按图隔离 |
| POST | /map/door | 布门端点 | body `{session_id?, x?, y?, target?}` |
| POST | /resume | 断线重连（roleKey 认证） | body `{game_id \| room_code, player_key}`；内存命中/快照重建/ENDED 终态 |
| GET | /keys | DM 分发 roleKey 一览 | ?session_id= |
| GET | /dm/status | DM 全量仪表盘 | ?session_id= + `X-DM-Key` 头（可配，空=放开）；不脱敏 |
| POST | /advance | DM 状态机推进 | body `{session_id}` + X-DM-Key；INVESTIGATION→DISCUSSION→VOTE(审批门)→REVEAL→ENDED |
| POST | /search | 搜证（耗 AP） | body `{player, location}`；→ clues/public_clues/error/result |
| POST | /discussion_say | 讨论人类发言（双通道合并 B1） | body `{player, message, player_key?}`；发言权豁免不过 SpeechGate |
| POST | /transfer_clue | 线索转交 | body `{player, target_player, clue_id}`；ownership 变更 |
| POST | /private | 私聊（P-0805-B） | body `{player, target, message, player_key?}`；AI 应答+认罪守卫 |
| GET | /private/history | 私聊历史双向查询 | ?player=&other=&player_key= |
| POST | /start_discussion | 讨论引擎启动 | body `{session_id}`；后台虚拟线程驱动，自动进 VOTE |
| POST | /start_voting | 进入投票 | body `{session_id}` |
| POST | /vote | 投票 | body `{player, suspect}` |
| POST | /resolve | 揭晓（D6 判定+审批门+超时弃票+quorum） | body `{session_id}`；→ most_voted/result/truth/murderer/correct/abstained/low_participation |
| POST | /finish | 确认进 ENDED（落库 type=result） | body `{session_id}`；幂等 |
| POST | /leave | 玩家退出→AI 托管 | body `{player, player_key?}` |
| POST | /restart | ENDED 重开（复用 sessionId） | body `{session_id}` |
| GET | /status | 玩家视角全量状态（toMap，3s 轮询主数据源） | ?player=&player_key=；脱敏：your_role/your_secret/role_key 仅本人 |

**WerewolfController（`/api/werewolf`，11 端点）**

| 方法 | 路径 | 用途 | 要点 |
|---|---|---|---|
| POST | /init | 开局（AI 补满 8 人，autoPlay 自动推进） | body `{players[], roles{}, room_code?}` + ?player_name= |
| POST | /resume | 重连（roleKey 必填防冒充） | body `{session_id\|room_code, player, player_key}` |
| GET | /keys | roleKey 分发 | ?session_id= |
| POST | /night_action | 夜间行动 | body `{player, action(kill/check/save/poison/nosave/nopoison), target}` |
| POST | /hunter_shoot | 猎人反杀 | body `{player, target}` |
| POST | /discussion_say | 白天讨论发言 | body `{player, message}` |
| POST | /resolve_night | 夜间结算 | body `{session_id}` |
| POST | /start_voting | 进入投票 | body `{session_id}` |
| POST | /vote | 投票 | body `{player, target}` |
| POST | /resolve_vote | 投票结算（审批门） | body `{session_id}` |
| GET | /status | 玩家视角状态（3s 轮询） | ?player_name=&session_id=；含 role_key/witch_victim/visible 狼人互认 |

**SimulationController（`/api/simulation`，2D 世界，26 端点）**

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | /init | 初始化 demo 世界（body 可选） |
| POST | /load-characters | 装载角色（body `{characters[]}`） |
| POST | /start /stop /reset | 启停/重置 tick |
| GET | /state | 世界全量状态（agents/tick/坐标/轨道…） |
| POST | /send/{agentName} /move/{agentName} /target/{agentName} /emotion/{agentName} /config/{agentName} | 角色指令：发言/移动/目标/情绪/配置 |
| GET | /events | **SSE（world_snapshot 增量）——PhaserSimulationView 唯一数据通道** |
| POST | /directive | 世界级指令 |
| POST | /speech | AI 演讲 demo（形态自动判定 merged/auto/split） |
| POST | /scene/{sceneName} | 切换场景（park 等） |
| GET | /scenes | 场景列表 |
| GET | /conversation-status | 群组状态（**4s 轮询**，用户在场判定数据源） |
| POST | /group/{groupId}/join \| /leave | 玩家加入/离开对话组（P-0803-G） |
| GET | /conversations | 世界对话列表 |
| POST | /track/goal \| /track/secret | 轨道目标注入 / 秘密角色设置（剧本杀旧 2D 桥） |
| GET | /track/state | Track 状态（MERGED/WEAK/ISOLATED） |

**ApprovalController（`/api/approval`，D7 审批门）**

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | /approve \| /reject | 批准/驳回（body `{session_id, reason?}`） |
| GET | /status \| /status/detail | 审批状态（DM 面板 3s 轮询） |
| GET | /pending | 待审批列表 |

**AnnouncementController（`/api/announcements`，演讲+广播合并管线 D-021）**

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | / | 玩家发广播（body `{text, level, channel, mode, speaker}`） |
| GET | /recent | 断线补发（?since=，环形缓冲） |
| GET/POST | /mode | 广播模式查看/切换（merged/auto/split） |

**SSEController（`/api/events`）** — 主 SSE 通道（GET，text/event-stream；?session_id= 会话定向过滤，P-0802-I/J）：
事件清单：`round_start, arbiter_task, agent_output, agent_silent, agent_token(流式), arbiter_integrate, round_complete, compression, user_input, auto_complete, stopped, error, saved, track_created, track_closed, announcement, werewolf_wait_human/phase/player_update/my_role/player_eliminated/witch_info/game_over/night_result/vote_update/speech/status, script_phase/status/reveal/private, tts_start/chunk/end/error`

**TrackRequestController（`/api/track`）** — 轨道请求/审批/评估（`POST /request`、`POST /requests/approve|reject|evaluate`、`GET /requests`）。

### 3.2 外围/管理类（重做 UI 时次要消费）

| Controller | 端点 | 用途 |
|---|---|---|
| AuthController `/api/auth` | POST /verify、GET /me、POST /admin/generate、GET /admin/list、POST /admin/deactivate | 验证码登录/管理员 |
| CharacterController `/api/characters` | GET、POST、PUT /{name}、DELETE /{name}、POST /generate、POST /batch | 角色 CRUD + LLM 生成 + 批量 |
| SceneController `/api/scenes` | GET、POST、PUT /{id}、DELETE /{id}、POST /{id}/start、POST /generate、POST /map | 剧本（场景）CRUD + 开局 + LLM 生成 + 默认地图（BSP/LLM 双模式） |
| RoomController `/api/rooms` | POST、GET /{code}、POST /{code}/join\|leave\|assign | 联机房大厅（狼人杀接入，剧本杀 room_code 绑定已备未接） |
| HistoryController `/api/history` | GET、GET /sessions、GET /sessions/{id}、POST /load/{id} | 历史会话 |
| ConfigController `/api/config` | apikey、language、models、voice（GET/POST） | 配置 |
| VoiceController `/api/voice` | status、start、stop、transcribe | 语音（Whisper 转写） |
| AssetController `/api/assets` | POST /import、GET、GET /{id}、DELETE /{id} | 素材库（2D 消费链：assetList 必须保留） |
| ImageController `/api/image` | POST /spec、POST /generate、GET /file/{fileName} | 生图（image_spec 契约 v1 → provider 三层降级 → 落 assets 登记） |
| WebSearchController `/api/search` | POST、GET、POST /fetch | 搜证联网增强 |
| PlayerController `/api/player` | POST /rename | 绑定角色局中改名 |
| McpController `/api/mcp` | servers、call、status、reconnect | MCP 工具（未接前端） |
| FrontendController `/error` | — | SPA 兜底 |

---

## 4. 前端调用后端的方式

### 4.1 统一请求封装（`api/client.ts`，372 行）

- **原生 fetch 封装 `request<T>()`，无 axios**；`BASE = ''` → 同源相对路径（dev 下由 Vite 代理 `/api` → 8000；生产由 Spring Boot 同源服务）
- 默认 **timeout 60s**（AbortController 实现；`scriptInit`/`scriptMap` 覆盖为 **600s**——剧本+地图两次 LLM 串行）
- 请求头：`Content-Type: application/json` + 可选 `Authorization: Bearer <localStorage token>`
- 错误处理：非 2xx → 尝试 `res.json()` 取 `detail` 抛 Error；200 空 body/非 JSON → 返回 null（P1-6 修复）；**abort → 转中文提示**「请求超时：AI 生成耗时较长…请勿重复点击」（P-0803-F）
- 全部端点收敛为 `api.*` 方法对象（约 70 个封装）；另有 `cancelAllRequests` 全量中止
- 特殊头：`scriptDmStatus`/`scriptAdvance` 可带 `X-DM-Key`；`player_id`（localStorage 持久化，P-0802-P1-demo）；roleKey 走 body `player_key`

### 4.2 实时通道

- **useSSE 钩子**（`api/useSSE.ts`）：`/api/events`（?session_id= 定向），38 种事件注册，指数退避重连（1s→30s），**重连成功后 `GET /api/announcements/recent?since=` 补拉**（P-0804-A）
- **PhaserSimulationView**：独立 `EventSource('/api/simulation/events')` 消费 `world_snapshot` + `conversation-status` 4s 轮询
- **轮询节奏**：剧本杀/狼人杀状态 3s；DM 面板 3s；私聊历史 3s（抽屉打开时）；群组在场 4s

### 4.3 ⚠️ 关键发现（对 UI 重做决策影响大）

1. **主 SSE 通道在当前活跃构建中未接线**：`useSSE` 只在 `AppLegacy.tsx`（死代码）被调用；新壳 App2→GameBridge→ChatPage **没有任何 /api/events 消费**。后果：① 剧本杀/狼人杀状态全靠 3s 轮询（SSE script_*/werewolf_* 事件全部浪费）；② `AnnouncementBanner`/`AnnouncementTicker` 读 `store.bannerQueue`，而该队列只有 AppLegacy 的 `announcement` handler 填充 → **活跃构建下公告横幅/公告栏无数据源，2D 视图内不显示公告**；③ agent_token 流式逐字渲染失效（无流式体验）。
2. **ChatPage.tsx 单文件 1875 行**承载四模式（free/director/werewolf/script），重做应拆分（按模式拆组件 + 复用 ScriptStatePanel/WerewolfActionPanel 等既有面板）。
3. **构建→static 同步为手工流程**且残留旧产物（index-C03zxnb0.js），应脚本化（`npm run build && sync`）+ 产物清理。
4. `ScenePage.tsx`（1520 行）仍是"剧本选择/设置"的另一套实现但已死代码，重做时注意**两套入口并存的历史包袱**（新壳 demo2 页面才是活入口）。

---

## 5. API 可视化逻辑链方案建议

目标：满足「做一个后端 API 在前端的可视化逻辑链」——让玩家/演示/调试者看到「前端操作 → API → 后端逻辑 → 状态回推」的完整链路。

### 方案 A：纯前端请求链路调试面板（最快 MVP，零后端改动）

**做法**：在 `client.ts` 的 `request()` 单点（全项目唯一出口）加拦截记录：`{ts, method, path, 请求体摘要, 耗时ms, 状态, 错误}`，写入环形缓冲（内存 + localStorage 可选持久化）；新 UI 加一个「🧭 链路」抽屉组件（dev 构建或 localStorage 开关启用），按 session 分组时间线渲染，可展开请求/响应体，标红失败项与超时项。

- ✅ 优点：零后端改动（不碰禁动文件）；真实数据；半天可交付；天然覆盖「进入游戏后」每一步（init→轮询→search→discussion→vote→resolve 时序与耗时一目了然）
- ❌ 缺点：只看到 REST 半边，看不到后端内部编排（Track 决策/LLM 调用/SSE 推送）；刷新即丢；对「后端 API 逻辑」的展示深度有限
- 适用：UI 重做期间的调试工具；方案 C 落地前的过渡

### 方案 B：静态逻辑链图（文档型，零运行成本）

**做法**：Mermaid 时序图/状态机图，覆盖三模式逻辑链：剧本杀六态状态机（init→investigation→discussion→vote→reveal→ended + 各态 API 与 SSE 事件标注）、狼人杀昼夜循环、一般模式轮次管线（含 Track 全链路：WorldDirector→InteractionDetector→TrackDirector→MovementConstraint→TrackStrategy→LLM→SSE）；入 `docs/` 并在新 UI 帮助页/README 引用。

- ✅ 优点：零运行时成本；可完整解释后端编排（运行时面板看不到的层）；符合项目文档文化；对主人/未衡审查友好
- ❌ 缺点：静态，实现漂移需人工维护；无耗时/失败等真实运行信息
- 适用：作为方案 C 的伴随交付物（配图+说明），不建议单独作为「可视化逻辑链」主体

### 方案 C：后端调用链追踪 + 前端追踪面板（完整版，**推荐**）

**后端**（横切，不触碰禁动业务文件）：
- 新增 `OncePerRequestFilter`（或 HandlerInterceptor）在 controller 层打点：`{ts, requestId, method, path, session_id(从 query/body 提取), status, ms, error}` → 内存环形缓冲（如 500 条，可配 `roleplay.debug.trace.enabled` 默认 false、`trace.size`）；
- 新增 `GET /api/debug/trace?session_id=&limit=`（dev 化端点，开关关闭时 404）；
- SSE 打点：`SSEController.broadcast*` 处顺手记录事件名/ts（同缓冲，带 `type:"sse"`）；
- 关联键：前端 `request()` 加 `X-Request-Id`（UUID）请求头 → 后端响应回显 → 前端日志与后端 trace 按 requestId 合并成一条完整链路（可选再在 LLMClient 打 LLM 调用耗时点，标注「🧠 LLM 生成中」段）。

**前端**：新 UI 的「🧭 逻辑链」抽屉——按 session 渲染时间线：前端点击（按钮名/输入摘要）→ REST 请求卡片（method/path/status/耗时）→ 展开响应体 → 穿插 SSE 事件流（script_phase/agent_output/announcement…）→ 失败/超时红色标记；可导出 JSON。

- ✅ 优点：**全链路真实数据**（REST+SSE+LLM 耗时+失败），正是「后端 API 在前端的可视化逻辑链」字面诉求；开关默认关闭零影响；数据可复用于验收演示与回归测试；SSE 定向（session_id）已就绪可直接按对局过滤
- ❌ 缺点：后端小改动（1 过滤器 + 1 端点 + 打点，虽不碰禁动文件但需走批次登记与测试）；requestId 约定需前端同步；环形缓冲内存占用（500 条 × ~200B ≈ 100KB，可控）
- 注意：与 AGENTS.md「禁动文件」约束的边界——过滤器/端点/打点均为**新增横切**，不动 RouterService/ArbiterService/审批/狼人杀/剧本杀 Service 主逻辑，符合「除非任务明确要求」的例外前提（本任务即明确要求后端 API 可视化）

### 推荐结论

**主方案 C（后端追踪 + 前端面板），方案 B 作为文档交付伴随物，方案 A 可作为 C 落地前的临时调试工具**。理由：① 主人诉求字面即「后端 API 在前端的可视化逻辑链」，C 数据最真实完整；② C 的横切实现与禁动约束兼容，可配置开关保证默认零影响；③ 前端重做时 C 的面板天然并入新 UI，且 §4.3 发现①（SSE 未接线）应一并修复——重做后的 UI 应恢复 `useSSE`（会话定向已就绪），让 script_*/werewolf_*/announcement 事件真正驱动界面，轮询降为兜底，链路面板由此能展示「SSE 实时回推」段。

---

## 附：本次调研文件清单（证据链）

| 主题 | 文件 |
|---|---|
| 前端入口/壳 | `frontend/index.html`、`src/main.tsx`、`src/App.tsx`、`src/demo2/App2.tsx`、`src/demo2/store.ts` |
| 对局启动 | `src/demo2/pages/GameBridge.tsx` |
| 对局主 UI | `src/components/ChatPage/ChatPage.tsx`（1875 行）、`src/components/ScriptDmPanel.tsx`、`src/components/HistoryPanel/HistoryPanel.tsx`、`src/components/AnnouncementBanner.tsx`、`src/components/AnnouncementTicker.tsx` |
| 2D 视图 | `src/phaser/PhaserSimulationView.tsx`、`PhaserScriptMapView.tsx`、`ScriptMapScene.ts`、`SimulationScene.ts` |
| API 封装 | `src/api/client.ts`（372 行）、`src/api/useSSE.ts`（85 行） |
| 状态管理 | `src/store/appStore.ts`（658 行） |
| 构建/静态 | `vite.config.ts`、`package.json`、`src/main/resources/static/index.html`（引 index-DrPjpy7W.js）、`static/assets/` |
| 后端端点 | `src/main/java/com/roleplay/engine/controller/` 22 个 `*Controller.java` 全量映射实扫 |
| SSE 事件 | `SSEController.java` broadcast 实扫 + `useSSE.ts` 事件注册表 |
