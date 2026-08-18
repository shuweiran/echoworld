# 剧本杀 UI 重设计落地 —— 原项目对比（查重查遗漏）+ API 接入方案

> 版本：V1.1（2026-08-16）｜前置：`docs/ui-prototype/` 三张静态原型（investigation / discussion / vote，V2）+ `设计方案-深化版.md`
> 性质：**只读原项目代码 + 产出文档**，未修改任何生产代码，未 git commit，未 spring-boot:run。
> **第一步盘点复用**：`docs/ui-prototype/原项目功能盘点.md`（另一任务产出，618 行：后端 24 Controller / 163 端点含 2 个 SSE / 无 WebSocket；前端 App2 入口 + ChatTopbar 9 按钮 + ChatDrawers 6 抽屉；7 张 JPA 表；剧本杀六态状态机；约 60+ 项功能；10 项 [需确认] 清单）。本文档盘点章节为**摘要 + 抽查核对结果**（已核实：@RestController 类数 24、ScriptController 23 端点、SessionController 20 端点、SSE 事件清单、前端 9 按钮——与盘点文档一致；以代码为准）。
> 依据：代码事实以 `src/main/java/com/roleplay/`（24 个 Controller / 163 端点）、`roleplay-v4/frontend/src/`（App2 路由 / ChatPage / gal / phaser / api）、`PROJECT_CONTEXT.md`、`DECISION_LOG.md`（D-001~D-061）为准；不确定处均标 **[需确认]**。

---

## 0. 原项目技术栈事实（以代码为准）

| 层 | 事实 | 取证 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.4 + Maven + Spring Data JPA（H2：生产 file `./data/roleplay` / 测试 mem） | `PROJECT_CONTEXT.md`；pom.xml |
| 实时通道 | **SSE（Server-Sent Events），非 WebSocket/STOMP**。`SSEController` 用 `SseEmitter`，`GET /api/events`；会话定向 `broadcastToSession(sessionId, ...)`（P-0802-I/J 已落地，D-028/D-029） | `SSEController.java` L43/72/127 |
| 前端 | React 18 + Vite + Zustand；入口 `main.tsx → App.tsx → demo2/App2.tsx`（7 Tab + GameBridge 对局桥）；对局页 `ChatPage`（含 `ScriptStatePanel` 剧本杀面板 / `ScriptDmPanel` 主持人抽屉 / `ScriptGalChatPanel` VN 式剧本杀聊天区）；`gal/` 全套 VN 组件（GalDialogBox 打字机对话框 / GalChoiceBar 行动选择 / GalHistoryDrawer backlog）；`phaser/` 2D 地图（PhaserScriptMapView / ScriptMapScene / SimulationScene） | `App.tsx`、`demo2/App2.tsx`、`components/ChatPage/*`、`gal/*`、`phaser/*` |
| 剧本杀状态机 | `Phase { SETUP, INVESTIGATION, DISCUSSION, VOTE, REVEAL, ENDED }`；`mode { full（真剧本杀）, chat（简单对话版） }` | `ScriptGameService.java` L243-246 |
| 剧本数据 | Script Schema v1（`docs/剧本-schema-v1.md`）：`metadata / roles[]{id,name,intro,is_hidden,secret,talkativeness,ap_bonus} / clues[]{id,title,location,content,transferable,visible_to_owner_only,ap_cost,public} / killer_id / truth / background / locations / secrets` | `ScriptSchemaV1.java`；D-014/D-016/D-022 |
| 鉴权 | 玩家级：`player_key`（roleKey，C3，`checkPlayerAccess` 校验，无 key 向后兼容）；DM 级：`X-DM-Key` 头（`roleplay.game.dm.key`，空=放开）；玩家身份：`player_id` 绑定（P-0802-P2） | `ScriptController.java` L2908/`dmKeyOk` |
| 房间 | `/api/rooms/*` 纯内存大厅（6 位码）+ 剧本杀 `room_code` 轻量绑定（C3，`roomGames` 映射） | `RoomController.java`、`ScriptController.java` |
| 落库 | `ScriptEntity`（type=script / result / snapshot，contentJson 整包 JSON）；地图/多图注册表/足迹/心锁状态随快照落库 | D-013/D-017/D-032/P-0814-H |

**关键结论（影响后续所有设计）**：
1. 无 WebSocket —— 「实时投票进度」「质询广播」等一律走 **SSE 事件（会话定向）** 或轮询；
2. 剧本杀后端能力已高度完整（搜证/AP/讨论引擎/投票/审批门/私聊/心锁前置的线索归属/多地图/热点交互），**新 UI 的大部分交互可以直接复用现有端点**，真正需要新增的端点集中在「原型新增玩法」（质询/引用/心锁/关系矩阵/行动选择/信任度/目标 HUD/投票进度/弃票）；
3. 前端已有 VN 化聊天区（`ScriptGalChatPanel`）+ gal 资产（打字机对话框/选择条/backlog 抽屉）—— 原型的 VN 元素不是从零开发，而是把现有 gal 资产对齐到三栏布局。

---

## 1. 原项目功能盘点

### 1.1 后端端点总账（24 个 Controller / 163 端点，按域分组）

> 完整端点清单见代码（`Select-String -Pattern '@(Request|Get|Post|Put|Delete)Mapping'` 全量导出）。下表列与本任务相关的核心端点（路径 = 方法）。

**剧本杀（`/api/script`，ScriptController，23 端点）**

| 端点 | 方法 | 用途 / 请求要点 / 响应要点 |
|---|---|---|
| `/api/script/init` | POST | 建局。body: players[]/theme/mode(full\|chat)/outline_only(缺省 true)/room_code/player_id → 响应 toMap + session_id/room_code |
| `/api/script/generate_full` | POST | 两阶段生成后半程（SETUP→完整剧本+地图，异步）。body: session_id? |
| `/api/script/map` | POST | 生成/获取对局地图（LLM→校验→BSP 降级）。body: session_id?/theme/seed/width/height/regenerate/map_id |
| `/api/script/map/switch` | POST | door zone 触发切图（P-0803-K 多地图） |
| `/api/script/map/door` | POST | 布门端点 |
| `/api/script/resume` | POST | 断线重连（game_id/room_code/player_key 三选一） |
| `/api/script/keys` | GET | DM 分发 roleKey 一览 |
| `/api/script/dm/status` | GET | DM 全量仪表盘（X-DM-Key） |
| `/api/script/advance` | POST | DM 状态机推进（X-DM-Key；VOTE 步走审批门） |
| `/api/script/search` | POST | **搜证**。body: player/location/player_key → {location, public_clues, found[], clues[], result, ap, ap_cost, web_results?}；扣 AP=线索 ap_cost 之和，不足整次拒绝 |
| `/api/script/interact` | POST | 地图热点统一交互（decor/tile，P-0814-H） |
| `/api/script/discussion_say` | POST | **讨论发言**。body: player/message/clue(公开线索)/player_key；人类发言权豁免；`@角色名` 点名强回应 |
| `/api/script/transfer_clue` | POST | 线索转交（ownership 变更） |
| `/api/script/private` | POST | **私聊**（玩家↔AI，guardPrivateSecret 拦自曝） |
| `/api/script/private/history` | GET | 私聊历史 |
| `/api/script/start_discussion` | POST | INVESTIGATION→DISCUSSION（接讨论引擎） |
| `/api/script/start_voting` | POST | →VOTE |
| `/api/script/vote` | POST | 投票。body: player/suspect/player_key → {result}（**无弃票参数**） |
| `/api/script/resolve` | POST | 揭晓（D6 判定 + D7 审批门：批准→REVEAL / 驳回→VOTE；投票超时→弃票+托管；quorum；平票重投上限） |
| `/api/script/finish` | POST | REVEAL→ENDED（落对局结果） |
| `/api/script/leave` | POST | 退出→托管 |
| `/api/script/restart` | POST | ENDED 重开（同主题同玩家，复用 sessionId） |
| `/api/script/status` | GET | **玩家视图**。query: player/player_key → toMap（见下） |

`status` 玩家视图 toMap 关键键：`phase/mode/session_id/name/background/roles/players/your_role/your_secret/round/started_at/elapsed_ms/phase_started_at/phase_elapsed_ms/phase_timeout_ms/game_over/winner/clues(公开+本人持有)/ap/ap_max/ap_pool/my_clues/role_key(仅本人)/llm_degraded/trustees/outline/generating/locations/map(契约v1)/searched_locations/current_map_id/map_ids/decor_states/decor_flags/discussion(发言记录)/ENDED 时 murderer/correct`。

**狼人杀（`/api/werewolf`，WerewolfController，11 端点）**：init / resume / keys / night_action / hunter_shoot / discussion_say / resolve_night / vote / resolve_vote / start_voting / status —— 与新 UI 无直接交集（新 UI 仅剧本杀三页），盘点即止。

**一般模式（SessionController `/api` + SimulationController `/api/simulation` 等）**

| 域 | 端点 | 用途 |
|---|---|---|
| 会话 | GET /api/state、POST /api/init、POST /api/send、POST /api/stop、POST /api/interrupt(+tasks)、POST /api/auto、GET/POST /api/mode、GET/POST /api/goals、POST/DELETE /api/agents、POST /api/script/generate、POST /api/private_chat/{request,reply,send} | 一般模式自由对话/导演/多轨；剧本独立生成；通用私聊（空壳） |
| 轮次 | POST /api/round/{start,rollback,suggest}、GET /api/round/status | 轮次控制/候选话术 |
| 历史 | GET /api/history(?session_id/limit/character/round/player_name)、GET /api/history/sessions、GET /api/history/sessions/{id}、POST /api/history/load/{id} | **消息/会话历史（backlog 数据源）** |
| 场景 | GET/POST /api/scenes、PUT/DELETE /api/scenes/{id}、POST /api/scenes/{id}/start、POST /api/scenes/generate、POST /api/scenes/map | 场景 CRUD/起局/LLM 生成/默认地图（BSP\|LLM） |
| 角色 | GET/POST /api/characters、PUT/DELETE /api/characters/{name}、POST /api/characters/{name}/persona、POST /api/characters/generate、POST /api/characters/upgrade(+status)、POST /api/characters/batch | 角色库 CRUD/五层 persona 导入/生成/批量升级 |
| 2D 模拟 | POST /api/simulation/{init,load-characters,start,stop,reset}、GET /api/simulation/state、POST /api/simulation/send|move|target|move-dir|emotion|config|directive|speech|scene/{name}|playback_done、GET /api/simulation/{scenes,conversation-status,conversations,track/state}、POST /api/simulation/group/{id}/{join,leave}、POST /api/simulation/track/{goal,secret} | 2D 世界/轨道/对话组 |
| 轨道 | POST /api/track/request、POST /api/track/requests/{approve,reject}、GET /api/track/requests、POST /api/track/requests/evaluate | 轨道申请/审批（逻辑链玩法） |
| 审批 | POST /api/approval/{approve,reject}、GET /api/approval/{status,status/detail,pending} | D7 审批门（剧本杀揭晓用） |
| 公告/演讲 | POST /api/announcements、GET /api/announcements/recent、GET/POST /api/announcements/mode、POST /api/simulation/speech | 统一消息管线（横幅/公告栏） |
| 房间 | POST /api/rooms、GET /api/rooms/{code}、POST /api/rooms/{code}/{join,leave,assign} | 联机房 |
| 素材 | POST /api/assets/import、GET /api/assets(+/{id})、DELETE /api/assets/{id} | 素材库（登记式） |
| 生图 | POST /api/ai-image/{character,generate}、GET /api/ai-image/{status,character/{id}/images}、POST /api/ai-image/scene-background；POST /api/image/{spec,generate}、GET /api/image/file/{name} | ComfyUI 角色表情集/RMBG 抠图；画像 spec/生图（离线 SVG 占位降级） |
| 语音 | GET/POST /api/voice/{status,start,stop}、POST /api/voice/transcribe；GET/POST /api/voice/toggle | TTS/语音 |
| 搜索 | POST /api/search、GET /api/search、POST /api/search/fetch | WebSearch（搜证增强） |
| 配置 | GET/POST /api/config/{apikey,language,voice}、GET /api/config/models | 设置 |
| 鉴权 | POST /api/auth/verify、GET /api/auth/me、POST /api/auth/admin/{generate,list,deactivate}；POST /api/player/rename | 邀请码/玩家改名 |
| 调试 | GET /api/debug/trace(+/{requestId}) | API 逻辑链追踪（TraceDrawer） |
| SSE | GET /api/events(?session_id=) | 事件流（见 §3.3 事件表） |

### 1.2 前端页面/组件盘点

| 页面/组件 | 功能 | 状态（当前为活代码） |
|---|---|---|
| `demo2/App2.tsx` | 7 Tab 导航：模式选择/剧本选择/角色选择/剧本生成/角色库/狼人杀/Gal Demo/设置 | 活 |
| `demo2/pages/HomePage` | 模式选择入口 | 活 |
| `demo2/pages/ScriptSelectPage` | 剧本卡（分类 chips/默认角色/地图预览）→ 设置页 | 活 |
| `demo2/pages/RoleSelectPage` | 角色选择（剧本默认角色+用户角色置顶） | 活 |
| `demo2/pages/ScriptGenPage` | 剧本生成（真实 LLM + mock 兜底）/一般场景生成 | 活 |
| `demo2/pages/RoleLibPage` / `RoleDetailPage` | 角色库/角色卡详情（五层 persona 表层） | 活 |
| `demo2/pages/SettingsPage` | 设置（API key/语言/语音/模型/角色生成） | 活 |
| `demo2/pages/GameBridge` | 对局桥：murder 分支 init(script)+generate_full 自动触发+进 ChatPage | 活 |
| `ChatPage`（components/ChatPage/） | 对局主界面：ChatTopbar（顶栏）/ChatLeftPanel/ChatRightPanel/ChatComposer/ChatMessageFlow/私聊抽屉/主持人抽屉/恢复对局 | 活 |
| `script/ScriptStatePanel` | 剧本杀状态面板：阶段/我的信息/AP/秘密/搜证地点/线索/转交/投票/揭晓/终局/重开/退出 | 活 |
| `script/ScriptResumePanel` | 重连恢复入口 | 活 |
| `ScriptDmPanel` | 主持人面板（全量视图/推进/审批/roleKey 分发） | 活 |
| `gal/ScriptGalChatPanel` | **剧本杀 VN 化聊天区**（打字机对话框/立绘/旁路条/输入门控） | 活（P-0815-B） |
| `gal/*`（GalDialogBox/GalChoiceBar/GalHistoryDrawer/GalPortraitPanel/GalTopBar/GalLivePanel/GalSceneCard/GalGeneralView/GalStage） | 一般模式 VN 视图全套 | 活 |
| `phaser/*`（PhaserScriptMapView/ScriptMapScene/SimulationScene/MiniMap） | 2D 地图/模拟渲染（瓦片/热点/decor/WASD/搜证联动） | 活 |
| `api/client.ts` | 全量 API 封装（见 1.1） | 活 |
| `api/useSSE.ts` | SSE 事件注册表（37 事件） | 活 |

### 1.3 核心业务流程（剧本杀六态）

```
SETUP ──(generate_full)──▶ INVESTIGATION ──(search 扣 AP / interact / transfer_clue / private)──
  ──(start_discussion)──▶ DISCUSSION ──(discussion_say / 讨论引擎自动轮 / script_speech)──
  ──(start_voting / 讨论结束自动)──▶ VOTE ──(vote ×N / resolve[D6判定+D7审批门] / 超时弃票+托管 / quorum / 平票重投上限)──
  ──(resolve 批准)──▶ REVEAL ──(finish)──▶ ENDED ──(restart 重开 / leave 中途托管)
分支：mode=chat 跳过搜证直接 DISCUSSION；resume 任意态恢复；快照随每状态变更落库
```

角色分配：initGame 生成剧本（Schema v1）→ 随机分配角色 → 每玩家 roleKey + 自己的 secret（只发本人）；AP = 基础值(3) + 角色 ap_bonus；搜证=一次搜一个地点，必得该地点全部未持有可搜线索（扣 ap_cost 之和）；讨论=ConversationManager + TrackStrategy（持秘密 WEAK 摘要/未持 MERGED）+ SpeechGate 发言门控 + 人类发言权豁免；投票=D6 精确判定（killer_id 优先）+ D7 审批门；落库双点（init 剧本 / confirmEnded 结果）。

### 1.4 原顶部 9 功能的前后端落点（任务必查项）

| 功能 | 后端落点 | 前端落点 | 现状 |
|---|---|---|---|
| 导演 | `WorldDirectorService`（规则目标）/ `TrackDirectorService`（轨道）；`POST /api/simulation/directive`（用户指令）；一般模式 `mode=director`（RouterService 串行+主控旁白 D-043）；`/api/simulation/track/goal` | Gal 导演视图（GalDemoPage/2D 视图聊天框）；demo2 无独立导演页 | 完整可用 |
| 设置 | `/api/config/{apikey,language,voice,models}` + `/api/voice/*` + `/api/auth/verify` | demo2 `SettingsPage` + ChatTopbar | 完整可用 |
| 主持人 | `/api/script/dm/status`、`/api/script/advance`、`/api/script/keys`、`/api/approval/*`（X-DM-Key 门） | `ScriptDmPanel` 抽屉（ChatPage 🎛） | 完整可用 |
| 私聊 | 剧本杀：`/api/script/private` + `/api/script/private/history`（AI 应答+防自曝+SSE script_private，D-041/042）；一般模式：`/api/private_chat/{request,reply,send}`（PrivateChatService 空壳） | ChatPage 💬 私聊抽屉（目标 chips/历史/气泡） | 剧本杀完整；一般模式空壳 |
| 美术 | `/api/ai-image/*`（ComfyUI+Pony V6 表情集+RMBG 抠图）、`/api/image/{spec,generate,file}`（生图/画像）、`/api/assets/*`（素材） | gal 立绘（GalPortraitPanel）、aiImage.ts；素材 2D 内消费 | 完整可用 |
| 逻辑链 | `/api/track/*`（轨道申请/审批）、`/api/simulation/track/state`、`/api/debug/trace`（API 逻辑链追踪 TraceFilter） | TraceDrawer（ChatTopbar 🧭）；2D 轨道可视化 | 完整可用 |
| 场景 | `/api/scenes/*`（CRUD/start/generate/map）+ `SceneEntity`（category/default_roles/default_map/goals） | 剧本选择页（ScriptSelectPage）+ Gal 场景卡 | 完整可用 |
| 角色库 | `/api/characters/*`（CRUD/persona 五层/generate/upgrade/batch）+ `CharacterEntity` + 五层卡持久化 data/persona | RoleLibPage / RoleDetailPage / 角色选择页 | 完整可用 |
| 历史 | `/api/history/*`（messages/sessions/load）+ `round_logs` + 快照落库 | HistoryPanel + GalHistoryDrawer（VN backlog） | 完整可用 |

---

## 2. 查重查遗漏（原项目 ↔ 新 UI）

### 2.1 功能映射表（原项目用户可见功能 → 新 UI 落点 → 状态）

> 落点格式：`阶段/区域`（三页 = investigation / discussion / vote；区域 = 顶栏 / 左栏 / 主区 / 右栏 / 设置菜单 / 浮动）。状态四类：**直接保留**（现有端点+数据即可）/ **改造后保留**（需换渲染或微调）/ **遗漏**（新 UI 未覆盖，需补入口）/ **新增**（新 UI 有而原项目无，见 2.3）。

| # | 原项目功能（前端→后端） | 新 UI 落点 | 状态 | 说明 |
|---|---|---|---|---|
| 1 | 开局建局（剧本选择/生成 → /api/script/init + generate_full） | 局外流程（三页原型不含） | 改造后保留 | 三页原型仅局内；开局沿用 demo2 页面，入口对齐原型风格即可 |
| 2 | 地点列表+搜证（ScriptStatePanel 地点 chips → /api/script/search） | 搜证·主区「地点探索」网格 | 直接保留 | 数据源 status.locations + searched_locations；原型 3×2 网格即列表渲染 |
| 3 | AP 行动点（面板 ⚡ → status.ap/ap_max/ap_pool） | 搜证·右上 AP 池 + 行动选择条联动 | 直接保留 | 数据齐全；「搜证 1 次消耗 1 点」文案与后端 ap_cost 求和语义需对齐（原型简化） |
| 4 | 搜证结果/线索明细（foundClues/publicClues） | 搜证·VN 发现演出 + 「记录线索」 | 改造后保留 | search 响应已有线索全文；VN 演出为新增渲染层（见 2.3-1） |
| 5 | 线索列表（ScriptStatePanel my_clues / 右栏） | 右栏·线索 Tab（证据袋，含 NEW/锁定占位） | 直接保留 | 数据源 status.clues（公开+本人持有）+ my_clues |
| 6 | 线索转交（transfer UI → /api/script/transfer_clue） | **无对应** | 遗漏 | 建议收进右栏线索卡操作（出示/转交 并列按钮） |
| 7 | 地图搜证（PhaserScriptMapView 热点 → /api/script/interact + map） | **无对应**（原型主区是地点网格） | 遗漏 | 建议保留「🗺 地图」入口（investigation 主区 Tab 切换：网格/地图）；interact/map/switch 全复用 |
| 8 | 搜证足迹（searched_locations 绿点） | 搜证·地点卡 ✓已搜证 | 直接保留 | 同源数据 |
| 9 | 讨论对话流（gal ScriptGalChatPanel 打字机 → discussion transcript + script_speech） | 讨论·VN 对话流（铭牌气泡+打字机） | 直接保留 | 数据源 status.discussion + script_speech SSE；原型样式可直接移植 |
| 10 | 发言输入（composer → /api/script/discussion_say） | 讨论·底部输入框 + 快捷动作条 | 直接保留 | 端点零改动；快捷动作条为新增（见 2.3-2 行动条） |
| 11 | 出示证据公开线索（discussion_say clue=true） | 讨论·「📎 出示证据」面板 → 对话流插入 | 改造后保留 | clue 参数已存在；「出示」插入对话流建议新增轻量端点（见 §3.2-API9） |
| 12 | 私聊（ChatPage 💬 → /api/script/private + history） | 顶栏 ⚙️ 菜单「私聊」 | 遗漏（入口） | 功能完整，原型仅给入口占位；挂现有抽屉即可 |
| 13 | 讨论记录回看（历史/round_logs/GalHistoryDrawer） | 讨论·顶栏 📜 + 右栏历史 Tab（backlog） | 直接保留 | 数据源 status.discussion + /api/history?session_id |
| 14 | 倒计时（phase_timeout_ms 展示，D-042） | 讨论·顶栏 ⏱ 徽章 | 直接保留 | status.phase_elapsed_ms/phase_timeout_ms；**注意 phase-timeout-ms 默认 0=禁用**，倒计时默认只展示不推进 [需确认] |
| 15 | 阶段进度（ScriptStatePanel 阶段标/横幅） | 左栏·阶段进度（完成✓/当前发光/未激活灰） | 直接保留 | 数据源 status.phase |
| 16 | 角色列表+在线点（左栏 chars） | 左栏·角色列表（头像/角色名/状态点） | 改造后保留 | 状态点目前无心跳数据源，前端本地推断（最近活动）[需确认] |
| 17 | 投票（ScriptStatePanel chips → /api/script/vote） | 投票·嫌疑人卡（Among Us 会议心智） | 改造后保留 | 候选人=players−自己−托管；弃票为新增（见 2.3-8） |
| 18 | 投票进度（现状：仅 resolve 后可见全量票型） | 投票·「已投票 2/4」+ 右栏条形统计 | 新增（后端聚合） | 见 §3.2-API11；SSE script_vote_progress（见 §3.3） |
| 19 | 揭晓（resolve+审批门 → REVEAL 区/ScriptStatePanel） | **无对应**（三页原型到投票止） | 遗漏 | 建议补第 4 页或沿用现 ScriptStatePanel 揭晓/终局区 [需确认] |
| 20 | 终局/重开/退出（finish/restart/leave） | **无对应** | 遗漏 | 建议：终局沿用现状；退出收进 ⚙️ 设置菜单；重开在终局页 |
| 21 | 主持人面板（ScriptDmPanel → dm/status/advance/keys/approval） | 顶栏 ⚙️ 菜单「主持人」 | 遗漏（入口） | 功能完整，原型仅入口占位 |
| 22 | 2D 空间讨论（simulation_started + Phaser） | **无对应** | 遗漏 | P1 保留（discussion 页可选切换 2D 视图）；chat 模式无此功能 |
| 23 | 恢复对局（ScriptResumePanel → /api/script/resume） | **无对应** | 遗漏 | 建议收进 ⚙️ 设置菜单或登录后入口 |
| 24 | 联机房（/api/rooms + room_code 绑定） | **无对应** | 遗漏 | 保留在开局流程（剧本选择页）；原型局内无房间概念 |
| 25 | LLM 降级/托管提示条（llm_degraded/trustees） | **无对应** | 遗漏 | 建议保留阶段横幅/顶栏小字（信息重要） |
| 26 | 角色库 Tab（现状：角色卡在 ChatRightPanel/角色选择页） | 右栏·角色库 Tab（ro-card 秘密计数） | 直接保留 | 数据源 status.players/roles/your_role；秘密计数=局内角色 secret 有无 |
| 27 | 逻辑链（现状：Track 可视化在 2D/调试） | 右栏·逻辑链 Tab（Obra Dinn 矩阵） | 改造后保留 | 矩阵数据源需新增关系端点或前端推导（见 2.3-6） |
| 28 | 历史 Tab（HistoryPanel 会话级） | 右栏·历史 Tab（VN backlog） | 改造后保留 | 局内 backlog=status.discussion；会话级历史收折叠区（P2） |
| 29 | 目标（一般模式 scene_goals 有；剧本杀无目标模型） | 顶栏·🎯 当前目标徽章 | 新增（后端） | 见 §3.2-API14；剧本杀侧需组合 outline/killer_hint 或新建目标模型 [需确认] |
| 30 | 心锁（**无**；D-016 transferClue 的「出示破锁」语义未建机制） | 搜证·左栏角色 🔒 + 破锁提示 | 新增 | 见 2.3-3 |
| 31 | 质询（**无**；SpeechGate 有点名/被质疑注入但无玩家 press 交互） | 讨论·气泡右缘 🔍 质询 + 矛盾点角标 | 新增 | 见 2.3-4 |
| 32 | 引用反驳言弹（**无**） | 讨论·气泡右缘 📌 引用 + 弹药条 | 新增 | 见 2.3-5 |
| 33 | 证据检索（**无**；my_clues 前端全量渲染） | 讨论·右栏检索框 + chips | 新增 | 见 2.3-6（可纯前端） |
| 34 | 行动选择条（**无**；有 private/search/interact 但无「行动建议」聚合） | 搜证·主区顶部行动选择条 | 新增 | 见 2.3-2 |
| 35 | 信任度条（**无**） | 投票·主区顶部团队信任度 | 新增 | 见 2.3-9 |
| 36 | 拍案演出（**无**） | 投票·全屏红光+震屏 | 新增（纯前端） | 零后端 |
| 37 | 侧边栏可隐藏（**无**；现 ChatPage 为固定左右栏） | 三页通用·左栏 icon rail / 右栏抽屉+悬浮按钮 | 新增（纯前端） | 见 2.3-11 |
| 38 | 设置菜单（现散落 ChatTopbar 按钮） | 顶栏·⚙️ 下拉（导演/主持人/美术/私聊/历史） | 改造后保留 | 原按钮收敛进菜单；9 功能全部已有落点（§1.4） |
| 39 | 帮助（**无**） | 顶栏·❓ | 新增（占位） | 纯前端 |

**规模**：上表共 39 行（不含 2.3 独立新增清单），其中 直接保留 ≈ **17**、改造后保留 ≈ **8**、遗漏 ≈ **9**（6/7/12/19/20/21/22/23/24/25，共 10 项算 9-10，取 9 项：其中 12/21 属「功能已有、仅缺入口」）、新增（并入 2.3）≈ **12**。

### 2.2 查漏清单（原项目有、新 UI 未覆盖 → 处理建议）

| # | 遗漏功能 | 处理建议 | 优先级 |
|---|---|---|---|
| L1 | 2D 地图/热点搜证（map/interact/switch + Phaser） | investigation 主区加「🗺 地图 / 📍 地点」Tab 切换；复用 PhaserScriptMapView + /api/script/map + interact，零后端改动 | P0（真剧本杀核心卖点，原型是纯网格是简化） |
| L2 | 线索转交（transfer_clue） | 右栏线索卡操作行加「🔁 转交」（选目标玩家），复用现有端点与选择器 | P1 |
| L3 | 私聊入口 | ⚙️ 菜单「私聊」→ 现有 ChatPage 私聊抽屉（脚本杀完整） | P0（已有功能挂入口） |
| L4 | REVEAL/ENDED 揭晓与终局 | 补第 4 张原型页或沿用 ScriptStatePanel 揭晓区；「拍案」演出可复用投票页 CSS | P0 |
| L5 | 退出对局/重开（leave/restart） | ⚙️ 设置菜单「退出对局」；终局页「🔄 再来一局 / 📋 回到剧本选择」沿用现状 | P1 |
| L6 | 主持人面板入口 | ⚙️ 菜单「主持人」→ 现有 ScriptDmPanel 抽屉 | P0（已有功能挂入口） |
| L7 | 恢复对局（resume） | ⚙️ 菜单「恢复对局」或登录后入口 | P1 |
| L8 | 联机房 | 保留在开局流程（剧本选择页）；局内无需 | P2 |
| L9 | LLM 降级/托管提示 | 顶栏小字/阶段横幅保留（信息影响决策） | P2 |
| L10 | 2D 空间讨论（full 模式） | discussion 页「🗺 2D 视图」切换按钮（P1）；chat 模式无 | P1 |
| L11 | 历史会话加载（HistoryPanel /load） | backlog Tab 顶部折叠「已存会话」（P2） | P2 |
| L12 | 一般模式/狼人杀套用新 UI | 原型仅剧本杀三页；范围外，明确不做（如需套用另起方案） | — |

### 2.3 新增清单（新 UI 有、原项目无 → API 新增主要来源）

| # | 新功能 | 来源 | 后端需要 | 前端需要 |
|---|---|---|---|---|
| N1 | VN 发现演出（打字机+记录线索） | 搜证地点卡点击 | 可选：search 响应加演出文本（或前端用线索 content 拼装） | 复用 gal GalDialogBox 打字机资产 |
| N2 | 行动选择条（去问人/重勘/出示） | 搜证主区 | **新端点**：行动建议列表 + 执行（§3.2-API1/2） | choice 条组件（gal GalChoiceBar 资产） |
| N3 | 心锁 psyche-lock（🔒/破锁） | 搜证左栏 | **新端点**：心锁状态 + 出示证据破锁（§3.2-API5） | 左栏锁标记 + 破锁弹窗 |
| N4 | 质询 press（矛盾点） | 讨论气泡 | **新端点**：press 标记+被质询角色状态（§3.2-API6） | 气泡右缘按钮 + 红色角标 |
| N5 | 引用反驳（言弹） | 讨论气泡 | 可选轻量端点（本地收藏 + 打出时发消息即可，§3.2-API7） | 弹药条（ammo bar） |
| N6 | 证据检索（Her Story） | 讨论右栏 | 可选后端检索（§3.2-API8）；前端本地过滤已够 MVP | 检索框 + chips |
| N7 | 关系矩阵（Obra Dinn） | 右栏逻辑链 Tab | **新端点**：线索×角色关联（§3.2-API10）或前端文本推导 | 矩阵组件 |
| N8 | Among Us 投票（候选卡+弃票+实时进度） | 投票主区/右栏 | **新端点**：vote/status 聚合 + vote 支持 abstain（§3.2-API11/12） | 候选卡/弃票按钮/统计条 |
| N9 | 信任度条 | 投票主区 | **新端点**：信任度模型（§3.2-API13）[需确认是否本期] | 信任度条 |
| N10 | 当前目标 HUD | 顶栏 | **新端点**：goal 查询（§3.2-API14） | 目标徽章 |
| N11 | 侧边栏可隐藏 | 全局 | 无 | 三栏布局 + localStorage |
| N12 | 拍案演出/backlog/倒计时徽章 | 投票/讨论/全局 | 倒计时已可（status）；演出零后端 | CSS/组件 |

---

## 3. API 接入方案

### 3.0 复用优先原则

新 UI 交互逐项对照后，**约 60% 交互可零改动复用现有端点**（§2.1 直接保留项）。前端改造以「换渲染层、不动数据契约」为准则（对齐「后端 Java 权威模拟 + 前端纯渲染」结构性前提，D-020）。新增端点全部挂在 `/api/script/*` 下，沿用 `player_key` 鉴权与 SSE 定向广播机制，零新基础设施。

### 3.1 复用映射表（新 UI 交互 → 现有端点）

| 新 UI 交互 | 现有端点（路径） | 复用方式 | 需微调点 |
|---|---|---|---|
| 地点列表（已搜/线索数） | `GET /api/script/status`（locations/searched_locations/map.zones.clue_location） | 直接 | 无；「线索数」可由 clues 按 location 统计 |
| 执行搜证（扣 AP） | `POST /api/script/search` | 直接 | 无（返回 ap/ap_cost/found/clues） |
| 已搜地点回看演出 | `POST /api/script/search`（同地点返回「无更多线索」）| 复用+新字段 | 建议 search 响应加 `replayed:true` 或新增轻量 `GET /api/script/location/{loc}` [需确认] |
| 线索列表/我的线索 | `GET /api/script/status`（clues/my_clues） | 直接 | 无 |
| 出示证据到对话（公开线索） | `POST /api/script/discussion_say`（clue=true） | 直接 | 原型「🃏 出示：CL-01」插入对话流 → 建议 message 传「出示了线索 CL-01」模板；严格语义可用新端点 API9 |
| 发言 | `POST /api/script/discussion_say` | 直接 | 无 |
| 私聊 | `POST /api/script/private` + `GET /api/script/private/history` | 直接 | 无 |
| 投票（投某人） | `POST /api/script/vote` | 直接 | 无 |
| 弃票 | `POST /api/script/vote` | **微调** | castVote 加 abstain 分支（§3.2-API12） |
| 揭晓 | `POST /api/script/resolve` + `/api/approval/{approve,reject}` | 直接 | 无 |
| 阶段推进（主持人） | `POST /api/script/advance`（X-DM-Key）；start_discussion/start_voting | 直接 | 无 |
| 阶段/轮次查询 | `GET /api/script/status`（phase/round/phase_elapsed_ms） | 直接 | 无 |
| 倒计时 | status.phase_elapsed_ms / phase_timeout_ms | 直接 | phase-timeout-ms 默认 0；展示型倒计时可直接算 [需确认] |
| 角色列表（含秘密计数） | `GET /api/script/status`（roles/players/your_role/your_secret） | 直接 | 无 |
| backlog 历史 | status.discussion（本局）+ `GET /api/history?session_id=`（一般模式消息） | 直接 | 无 |
| 地图（生成/查看） | `POST /api/script/map` + status.map | 直接 | 无 |
| 地图热点交互 | `POST /api/script/interact` | 直接 | 无 |
| 线索转交 | `POST /api/script/transfer_clue` | 直接 | 无 |
| 退出/重开/恢复 | `/api/script/leave` / `restart` / `resume` | 直接 | 无 |
| DM 面板 | `GET /api/script/dm/status`、`POST /api/script/advance`、`GET /api/script/keys` | 直接 | 无 |
| 主持人审批 | `/api/approval/*` | 直接 | 无 |
| 美术/生图 | `/api/ai-image/*`、`/api/image/{spec,generate}` | 直接 | 无 |
| 逻辑链（TraceDrawer） | `/api/debug/trace` | 直接 | 无 |

### 3.2 新增 API 设计（含复用扩展）

> 通用约定：错误统一 `{error: string}` + HTTP 200（对齐既有 ScriptController 风格）；鉴权失败 HTTP 403 `{error}`；鉴权=player_key（C3 机制）或 X-DM-Key；阶段不符返回 `{error, phase}` 由前端引导。

---

**API-1 `GET /api/script/actions` — 行动选择条建议列表**（N2）
- 方法/路径：GET `/api/script/actions`；query: `player`、`player_key?`
- 用途：搜证阶段主区「行动选择」数据源（去问人 / 重勘地点 / 出示线索）
- 响应：
```json
{
  "ok": true,
  "phase": "investigation",
  "actions": [
    {"id": "ask|苏晚", "type": "ask", "target": "苏晚", "label": "去问苏晚", "ap_cost": 1, "enabled": true, "reason": ""},
    {"id": "research|书房", "type": "research", "target": "书房", "label": "去搜书房", "ap_cost": 1, "enabled": true, "reason": ""},
    {"id": "present|CL-03", "type": "present", "target": "CL-03", "label": "出示怀表链", "ap_cost": 1, "enabled": true, "reason": ""}
  ],
  "ap": 3, "ap_max": 5
}
```
- 生成规则（服务端权威）：`ask` = 未托管且未私聊过的其他玩家（去重最近目标）；`research` = 已搜证地点（回看）优先 + 未搜地点（引导搜证）；`present` = 本人持有的可出示线索（前 3 条）；`enabled=false` 时给 reason（AP 不足/已问过/不在本阶段）。阈值类建议配置化（对齐 D-004 纪律，`roleplay.game.script.action-*`）[需确认：行动集是否需要配置化]
- 错误码：403 鉴权 / `{error:"当前不是搜证阶段"}` / 未知对局

**API-2 `POST /api/script/action` — 执行行动**（N2）
- 方法/路径：POST `/api/script/action`；body: `{player, action_id, player_key?}`
- 语义分派（服务端复用现有能力，不重复造轮子）：
  - `ask|<target>` → 内部调 `privateSay(player, target, "（行动：主动搭话）")`，响应含 reply；扣 AP 1
  - `research|<location>` → 若未搜过：转 `search`（原路径扣 AP）；已搜过：返回该地点线索回看（`{replayed:true, clues:[...]}`）扣 AP 1（或 0 [需确认]）
  - `present|<clue_id>` → 内部调 `discussionSay(player, "出示了线索 <title>", clue=false)` + 广播 `script_present`；扣 AP 1
- 响应：
```json
{
  "ok": true,
  "action_id": "ask|苏晚",
  "ap": 2, "ap_cost": 1,
  "result": "你去找苏晚搭话…",
  "reply": "苏晚：……（AI 应答）",
  "clues": [], "replayed": false,
  "presented": false
}
```
- 错误码：403 / `{error:"行动点不足"}` / `{error:"未知行动"}`
- 依赖：复用 `search`/`privateSay`/`discussionSay` 内部方法；`playerAp` 扣减 + `saveSnapshot`（沿用 C2 纪律）；SSE：ask→`script_private`、present→`script_present`（新，§3.3）

**API-3 `GET /api/script/locks` — 心锁列表**（N3）
- 方法/路径：GET `/api/script/locks`；query: `player`、`player_key?`
- 用途：左栏角色 🔒 标记数据源（哪个角色几把锁、解锁线索）
- 数据来源 [需确认]：schema `roles[]` 无 lock 字段 —— 建议 ① LLM 生成时提示输出 `clues[].unlock_role`（两阶段生成 prompt 加约束，宽容解析缺省无锁）；或 ② 规则推导（线索 content 提及角色名 ↔ 该角色 1 锁）。方案①语义最准，需改 generateScript prompt + normalize（宽容）。
- 响应：
```json
{
  "ok": true,
  "locks": [
    {"role": "顾言", "lock_count": 2, "unlock_clue_ids": ["CL-01", "CL-04"], "unlocked": false},
    {"role": "苏晚", "lock_count": 1, "unlock_clue_ids": ["CL-02"], "unlocked": false}
  ]
}
```
- 错误码：403 / 未知对局

**API-4 `POST /api/script/unlock` — 出示证据破锁**（N3）
- 方法/路径：POST `/api/script/unlock`；body: `{player, target_role, clue_id, player_key?}`
- 校验链：阶段（investigation/discussion）→ 玩家持有 clue_id（my_clues 归属）→ clue 是否该角色的解锁线索（未匹配→`{error:"这张线索解不开 TA 的心锁"}`）→ 破锁（lock_count 归零，广播 `script_locks`）
- 响应：`{"ok": true, "role": "顾言", "unlocked": true, "unlock_clue_id": "CL-01", "message": "顾言的心锁解开了！", "locks": [...]}`
- 错误码：403 / `{error:"线索不存在或未持有"}` / `{error:"当前阶段不可破锁"}`
- 依赖：新状态 `roleLocks`（Map<role, {lockCount, unlockClueIds}>）随快照落库（对齐 decorStates 范式）；破锁为一次性（幂等，重复出示提示已解锁）

**API-5 `POST /api/script/press` — 质询发言**（N4）
- 方法/路径：POST `/api/script/press`；body: `{player, target, message_id?, player_key?}`
- 用途：对某角色发言打「质询」标记 + 触发该角色辩解（对齐 SpeechGate 被质疑注入辩解目标的既有语义，D-022）
- 实现：① 讨论记录（discussionTranscript）标记 `pressed:true`（或按 target 角色标记 `pressedBy`）；② 若讨论引擎运行中，向该角色注入「辩解」临时目标（复用 D-022 被点名注入路径 `pri=100 衰减`）；③ 广播 `script_press`（含被质询角色，前端左栏标红）
- 响应：`{"ok": true, "target": "顾言", "pressed": true, "message_id": "…", "note": "已标记矛盾点并催促其辩解"}`
- 错误码：403 / `{error:"当前不是讨论阶段"}` / `{error:"目标不在本局"}`
- 依赖：discussionTranscript 是 `CopyOnWriteArrayList<Map<String,String>>`，可直接改字段；质询状态随快照落库（讨论记录本身已随快照）

**API-6 `POST /api/script/quote` — 引用发言收为言弹**（N5）
- 方法/路径：POST `/api/script/quote`；body: `{player, target, message_id?, player_key?}`
- 用途：把某人发言收为「我的反驳弹药」（原型 ammo）；**MVP 可纯前端本地**（弹药条不跨端同步）；本端点提供可选后端记录（言弹可在投票时随 vote 附注打出）
- 响应：`{"ok": true, "quote": {"target": "苏晚", "text": "花园的泥土里有皮鞋印…", "quoted_by": "林深"}, "ammo_count": 2}`
- 错误码：403 / 未知对局；`{error:"该发言不存在"}`（message_id 无效时）

**API-7 `GET /api/script/evidence/search` — 证据检索**（N6，可选）
- 方法/路径：GET `/api/script/evidence/search`；query: `player`、`q`、`category`（person/location/time）、`player_key?`
- 用途：右栏证据袋检索（原型 Her Story 检索框）；**MVP 前端本地过滤即可**（my_clues/clues 已全量在 status），本端点用于大线索库/跨持有检索
- 响应：`{"ok": true, "q": "顾言", "category": "person", "hits": [{"id":"CL-01","title":"烧毁的信","location":"书房","content":"…","holder":"林深","tags":["person","location"]}]}`
- 错误码：403；检索无命中返回空数组

**API-8 `GET /api/script/relations` — 关系矩阵**（N7）
- 方法/路径：GET `/api/script/relations`；query: `player`、`player_key?`
- 用途：右栏逻辑链 Tab 的「人物×线索×关系」矩阵（★直接/▲疑似/◯持有/–无关联）
- 数据来源 [需确认]：方案① 服务端推导——线索 content 提及角色名（name 匹配/同义词）→ ★直接；clues.location 或发现者 → ◯持有；其余 –；方案② LLM 生成剧本时输出 `clues[].related_roles[]`（prompt 约束，宽容解析缺省走方案①推导）。建议先方案①（零生成器改动），二期切方案②。
- 响应：
```json
{
  "ok": true,
  "roles": ["苏晚","陈默","顾言","阿岚"],
  "clues": ["CL-01","CL-02","CL-03"],
  "matrix": {"苏晚":{"CL-01":"-","CL-02":"◯","CL-03":"-"}, "…":"…"},
  "relations": [
    {"from":"顾言","clue":"CL-01","type":"direct","reason":"署名当事人"},
    {"from":"陈默","clue":"CL-02","type":"suspect","reason":"鞋码吻合"}
  ]
}
```
- 错误码：403 / 未知对局；phase 无关（三阶段均可用）

**API-9 `POST /api/script/present` — 出示证据到对话流**（N2 出示分支 + 讨论「📎 出示」）
- 方法/路径：POST `/api/script/present`；body: `{player, clue_id, target?, player_key?}`（target 可选：出示给某人/全场）
- 用途：讨论阶段「出示证据」面板选中线索 → 以 `🃏 出示：CL-01 烧毁的信` 系统行插入对话流 + 全员可见（区别于 discussion_say clue=true 的「公开线索」——那是把线索转为公开；出示是展示动作）
- 实现：写 discussionTranscript（`{speaker:"system", message:"🃏 <player> 出示了线索 <title>"}`）+ 广播 `script_present`（§3.3）+ 按目标触发相关 AI 回应（复用 SpeechGate HUMAN_CLUE 触发，D-022 已支持「人类公开线索相关→必发言」）
- 响应：`{"ok": true, "presented": {"clue_id":"CL-01","title":"烧毁的信"}, "target": "", "transcript_id": "…"}`
- 错误码：403 / `{error:"当前不是讨论阶段"}` / `{error:"未持有该线索"}` / 投票后拒绝

**API-10 `GET /api/script/vote/status` — 投票进度聚合**（N8）
- 方法/路径：GET `/api/script/vote/status`；query: `player`、`player_key?`
- 用途：投票页「已投票 2/4」+ 右栏统计条（实时，不揭晓票面去向——只给票数聚合与已投名单，**不泄露谁投了谁**）
- 响应：
```json
{
  "ok": true,
  "phase": "vote",
  "total": 4,
  "voted": 2,
  "abstained": 0,
  "pending": ["顾言","阿岚"],
  "candidates": [
    {"name":"苏晚","votes":1,"point":"动机：遗产继承"},
    {"name":"陈默","votes":1,"point":"时间线：无人能证明行踪"}
  ],
  "trustees": []
}
```
- 说明：`candidates[].point`（原型嫌疑人卡动机/时间线文案）= 由该角色相关线索/讨论摘要推导（P2，MVP 可空）；**candidates[].votes 只出聚合不出投票人**（隐私对齐：票型揭晓时才全量公开）
- 错误码：403；非 VOTE 阶段返回 `{phase}`（前端可隐藏统计区）

**API-11 `POST /api/script/vote`（扩展）— 弃票**（N8）
- 现状：`castVote` 拒绝空 suspect、不能投自己；**扩展** body 增加 `abstain: true`（suspect 可空）：写 `votes.put(voter, "弃票")` 或独立 `abstainedVoters` 集合 [需确认：弃票记入票型统计与否——建议独立集合，quorum 在线数仍计、不参与 mostVoted 统计，对齐 D-037 超时弃票语义]
- 响应：`{"result": "你已弃票（跳过本轮表决）"}`；重复投票返回既有「已投票」拒绝语义
- 错误码：403 / `{error:"当前不是投票阶段"}` / 托管玩家拒绝

**API-12 `GET /api/script/trust` — 团队信任度**（N9）
- 方法/路径：GET `/api/script/trust`；query: `player`、`player_key?`
- 用途：投票页顶部信任度条（原型 ⚖️ 4/5，错误指控扣信任度）
- 模型 [需确认]：MVP 建议**服务端轻量状态机**——初始 5；弃票/误判（resolve 后 correct=false 且本人投错）−1；正确指认 +0（不回收）；下限 0。状态 `trust` 随快照落库；**纯展示版可前端本地近似（零后端）**，二选一 [需确认]
- 响应：`{"ok": true, "trust": 4, "max": 5, "history": [{"round":1,"delta":-1,"reason":"错误指控"}], "hint": "错误指控会扣信任度"}`
- 错误码：403

**API-13 `GET /api/script/goal` — 当前目标 HUD**（N10）
- 方法/路径：GET `/api/script/goal`；query: `player`、`player_key?`
- 用途：顶栏 🎯 目标徽章（原型三阶段目标：集齐 6 处地点线索 / 找出矛盾发言 / 指认真凶）
- 数据来源 [需确认]：剧本杀**无目标模型**（一般模式有 SceneGoalService，D-047；剧本杀只有 outline.killer_hint/storyline + 讨论目标 getDiscussionGoal）。建议 MVP **规则模板**（按 phase 返回 + 进度统计）：
  - INVESTIGATION：`{title:"集齐线索", progress:{searched:2,total:6}, detail:"已搜证 2/6 处地点"}`
  - DISCUSSION：`{title:"找出矛盾发言", detail:"质询可疑发言，指出矛盾"}`（质询数可作 progress）
  - VOTE：`{title:"指认真凶", progress:{voted:2,total:4}}`
- 响应：`{"ok": true, "phase": "investigation", "goal": {"title":"集齐线索","progress":{"searched":2,"total":6},"detail":"已搜证 2/6 处地点"}}`
- 错误码：403；未知对局
- 依赖：searchedLocations.size()/locations.size()、vote/status 数据、press 计数 —— 全部已有或 API-5 新增，规则模板零新状态

**API-14（可选）`POST /api/script/action_playback` — 回看发现演出**（N1 兜底）
- 若 VN 演出文本由后端提供而非前端拼装：body `{player, location, player_key?}` → 返回该地点线索的演出文本序列 `{lines:[{speaker, name, text}]}`；数据源=clues content 模板化（`……就是这封烧了一半的信。` 类文案由前端模板或 LLM 生成 [需确认]）。**MVP 建议前端由线索 content 拼装，零新端点。**

**统计**：新增端点 **13 个**（API-1~10、API-12~14），其中 **5 个可选降级**（API-6 引用可纯前端 / API-7 检索可纯前端 / API-8 矩阵可前端推导 / API-12 信任度可前端近似 / API-14 演出可前端拼装）；**1 个既有端点扩展**（API-11 vote 支持弃票）。核心必须 9 个：API-1/2（行动条）、API-3/4（心锁）、API-5（质询）、API-9（出示）、API-10（投票进度）、API-13（目标）+ API-11（弃票扩展）。

### 3.3 WebSocket/推送事件表（SSE，非 WS）

> 通道：`GET /api/events?session_id=<对局>` → `broadcastToSession(sessionId, event, payload)`（P-0802-I/J 既有；无 session 过滤连接收全局广播，前端 3s 轮询兜底）。

**现有事件（直接复用）**

| 事件名 | 方向 | 载荷 | 触发时机 | 新 UI 消费 |
|---|---|---|---|---|
| script_phase | 后端→前端 | {session_id, phase} | 状态机每次流转（SETUP/INVESTIGATION/DISCUSSION/VOTE/REVEAL/ENDED） | 左栏阶段进度 + 阶段横幅 |
| script_status | 后端→前端 | {session_id, ...toMap 脱敏} | 状态变更点 | 全面板轮询替代 |
| script_reveal | 后端→前端 | {session_id, votes, most_voted, vote_count, murderer, correct, result, truth, approval} | resolveVote 批准进 REVEAL | 揭晓区 |
| script_speech | 后端→前端 | {session_id, speaker, message, round, human?} | 讨论引擎每轮发言逐条（B1） | 讨论对话流实时回显 |
| script_private | 后端→前端 | {session_id, from, to, message, reply, guarded, ts} | 私聊消息 | 私聊抽屉 |
| script_ready | 后端→前端 | {session_id, ready, phase, name, map_ready, generated?} | generate_full 完成（两段） | SETUP 进度 |
| announcement | 后端→前端 | BroadcastMessage（level/channel/text/…） | 阶段切换 SYSTEM 横幅/玩家公告 | 顶栏/横幅 |
| agent_output / round_start / round_complete 等 | 后端→前端 | 一般模式事件 | 一般模式轮次 | 一般模式视图（范围外） |
| werewolf_*（11 类） | 后端→前端 | 狼人杀事件 | 狼人杀 | 范围外 |

**新增事件（配合 §3.2 新端点）**

| 事件名 | 方向 | 载荷 | 触发时机 | 消费端 |
|---|---|---|---|---|
| script_vote_progress | 后端→前端 | {session_id, total, voted, abstained, pending[], candidates[{name,votes}]} | 任一玩家 vote/abstain/leave/托管变更 | 投票页「已投票 x/y」+ 右栏统计条（替代轮询 API-10） |
| script_press | 后端→前端 | {session_id, target, pressed_by, message_id?, contradiction:true} | API-5 质询成功 | 讨论气泡红色角标 + 左栏被质询标红 |
| script_present | 后端→前端 | {session_id, player, clue_id, title, target} | API-9 出示证据 | 对话流「🃏 出示」系统行 |
| script_locks | 后端→前端 | {session_id, role, lock_count, unlocked, unlock_clue_id?} | API-4 破锁 / init 后首次 | 左栏 🔒 标记刷新 |
| script_goal | 后端→前端 | {session_id, phase, goal} | 阶段切换/进度变化（搜证/投票/质询） | 顶栏 🎯 目标徽章 |
| script_trust（可选） | 后端→前端 | {session_id, trust, max, delta, reason} | API-12 模型变化（resolve 后） | 信任度条 |

倒计时：**不走事件**——前端由 status.phase_elapsed_ms/phase_timeout_ms 本地计时（轮询兜底），避免高频推送。

### 3.4 鉴权与房间隔离（新增接口接入方式）

| 机制 | 现状 | 新增接口接入 |
|---|---|---|
| 玩家级鉴权 | `checkPlayerAccess(sessionId, player, player_key)`：有 key 严格校验（403 防冒充），无 key 向后兼容按名 | API-1~13 全部在 Controller 入口调用同一方法（与 search/vote 同款三行样板）；`sessionId` 解析沿用 `playerSessions.getOrDefault(player, currentSessionId)` + `findSessionByPlayerKey` 优先（P-0810-17 B3） |
| DM 级鉴权 | `X-DM-Key`（roleplay.game.dm.key，空=放开） | 仅新增 DM 类操作需要时沿用（本方案无新增 DM 端点——advance/keys/dm/status 已够） |
| SSE 会话定向 | `broadcastToSession(sessionId, ...)`（无匹配连接静默丢弃，回退全局广播） | 新事件全部走定向；前端 useSSE 连接已按 script 模式带 session_id（D-029） |
| 房间隔离 | init 可选 room_code → `roomGames` 映射；resume 可按房间码定位 | 新增端点不触碰房间概念；多局并发由 sessionId 天然隔离（讨论引擎已 per-game 隔离 D-029） |
| 防越权数据 | toMap 脱敏（your_secret 仅本人 / role_key 仅本人 / clues 过滤公开+持有） | 新端点响应遵循同一脱敏纪律（vote/status 只出聚合不出投票人；relations 不泄 secret） |

### 3.5 落地阶段划分（接口清单 + 工作量粗估）

> 工作量口径：人日（后端 1 端点 ≈ 0.5-1 人日含测试；前端组件按复杂度）。所有阶段遵守禁动清单（RouterService/ArbiterService/审批/狼人杀/SSE 主链路/static 不动——SSE 主链路指 broadcast() 本身，新增 helper 沿用既有模式）。

**阶段一 MVP（三阶段核心闭环：搜证→讨论→投票 + 阶段流转 + 鉴权）**
- 目标：三栏布局落地 + 现有功能全部搬进新 UI + 投票闭环补齐 + 目标 HUD + 侧边栏可隐藏
- 后端：API-1/2（行动条）、API-10（vote/status 聚合）、API-11（vote 弃票扩展）、API-13（goal）；SSE：script_vote_progress、script_goal；测试 6-8 用例
- 前端：ChatPage 重构为三栏（左 240/中 flex/右 280 + 可折叠 localStorage）；investigation 页（地点网格+AP 池+行动条+VN 演出首版=前端拼装）；discussion 页（对话流换肤+快捷动作条+出示证据走 discussion_say clue + backlog Tab）；vote 页（候选卡+弃票+进度+统计条）；顶栏（阶段徽章/目标徽章/⚙️ 菜单挂现有 9 功能入口/📜）；左栏（阶段进度+角色列表+icon rail）
- 复用不重写：ScriptStatePanel 保留为「高级/兜底面板」或逐步拆解；ScriptGalChatPanel 打字机资产复用
- 工作量：后端 3-4 人日；前端 8-12 人日；测试 2 人日 —— **合计约 13-18 人日**

**阶段二（质询/引用/心锁/检索/关系矩阵）**
- 后端：API-3/4（心锁，含 generateScript prompt 扩展+宽容解析）、API-5（质询）、API-6（引用，可选）、API-7（检索，可选）、API-8（关系矩阵，方案①推导）；SSE：script_press、script_locks、script_present（若 API-9 提前）；测试 8-10 用例
- 前端：质询按钮+矛盾点角标+被质询标红；言弹条；心锁标记+破锁弹窗；检索框+chips；矩阵组件
- 工作量：后端 5-6 人日；前端 5-7 人日 —— **合计约 10-13 人日**

**阶段三（VN 演出/信任度/backlog/目标细化）**
- 后端：API-9（present，若未提前）、API-12（trust 模型）、API-14（演出文本，可选）、goal 从规则模板升级（可选 LLM 目标集，对齐 SceneGoalService 范式）；SSE：script_present、script_trust；测试 5-6 用例
- 前端：VN 发现演出完整版（后端文本+打字机）、信任度条、backlog 会话级折叠、目标徽章进度动画、拍案演出
- 工作量：后端 4-5 人日；前端 4-6 人日 —— **合计约 8-11 人日**

**总量粗估：约 31-42 人日（MVP 占 ~45%）**。未含：2D 地图 Tab 切换（L1，若做 +2-3 人日）、揭晓页补第 4 张（L4，+2-3 人日）、一般模式/狼人杀套用（范围外）。

### 3.6 风险与依赖

**数据模型（是否需要新表/新字段）**
| 项 | 现状 | 需求 | 结论 |
|---|---|---|---|
| 线索-地点-角色关联 | schema clues 有 location；角色关联无 | relations/unlock 需角色关联 | **不加表**：relations 用内容推导（方案①）；unlock 用 `clues[].unlock_role`（改 generateScript prompt + 宽容解析，随 contentJson 落库，零表结构变更，对齐 D-013/014 纪律）[需确认] |
| 心锁状态 | 无 | roleLocks | 内存字段 + 随快照落库（快照为整包 JSON，加键零迁移，对齐 decorStates 范式） |
| 行动点 | playerAp/playerApMax 已有（D-016） | 行动条消耗 | **零改动**，复用 |
| 投票记录 | votes map（voter→suspect）已有 | 弃票/进度聚合 | 弃票建议独立 `abstainedVoters` 集合（不污染票型统计，对齐 D-037 超时弃票语义）；快照加键 |
| 信任度 | 无 | trust | 内存字段 + 快照加键；或纯前端近似（[需确认]） |
| 质询/引用标记 | discussionTranscript（随快照） | pressed 标记 | 记录字段内加键，零新表 |
| 目标 HUD | 剧本杀无目标模型 | goal | 规则模板零新状态；LLM 目标集（可选）需对齐 SceneGoalService 范式另议 [需确认] |

**前端改造方式（模板替换 vs 渐进嵌入）——建议渐进嵌入**
1. 第一步：ChatPage 外层改三栏布局（左栏阶段/角色、中栏主区、右栏 Tab 抽屉），**面板内容先原样嵌入**（ScriptStatePanel 进右栏/主区），行为零变化；
2. 第二步：按阶段一/二/三逐块替换面板内容（先投票页——数据契约最小；再搜证页——加行动条/VN 演出；最后讨论页——对话流换肤+质询/引用）；
3. 原型 HTML 资产移植：三页 CSS（深色主题/铭牌/气泡/矩阵/统计条）抽为 React 组件样式；`localStorage` 折叠逻辑抽为 hook（`useCollapsibleSidebars`）；
4. 每步保留旧面板兜底开关（`ui-proto-v2` 特性开关 [需确认]），可回退。

**其他风险**
| 风险 | 说明 | 缓解 |
|---|---|---|
| 三页原型未含 REVEAL/ENDED | 揭晓交互缺设计 | 沿用 ScriptStatePanel 揭晓区（L4），二期补原型页 |
| 多玩家并发投票进度 | script_vote_progress 高频推送 | 仅 vote/abstain/leave 时推送；前端 3s 轮询 API-10 兜底（对齐 script_status 双通道先例） |
| 讨论引擎单线程写 transcript 与快照拷贝 | 既有 CME 风险已修（CopyOnWriteArrayList，D-048 B5） | 新增 pressed 标记写操作沿用并发安全容器 |
| phase-timeout-ms 默认 0 | 新 UI 倒计时若带强制推进语义需改配置 | 展示型倒计时直接由 elapsed 计算；强制推进需开启配置并验证 D-042 惰性推进路径 |
| 行动条与既有入口重复 | search/private/interact 已有入口 | 行动条是聚合引导层（服务端权威生成建议），不新增第二套执行逻辑——内部委托既有方法 |
| 前端重构回归面大 | ChatPage 是主对局页 | 渐进嵌入 + 每阶段 npm build + 真机冒烟（沿用 CDP 验证流程）；禁动文件零改动 |

---

## 4. 不确定点清单（[需确认] 汇总）

| # | 问题 | 影响面 | 建议 |
|---|---|---|---|
| U1 | 心锁数据来源：LLM 生成 `clues[].unlock_role` vs 规则推导 | 阶段二 API-3/4 | 建议 LLM 标注（改 prompt+宽容解析），二期前先规则推导 |
| U2 | 关系矩阵数据来源：内容推导 vs LLM 标注 | 阶段二 API-8 | MVP 内容推导，二期 LLM 标注 |
| U3 | 信任度是否本期做：服务端模型 vs 前端近似 | 阶段三 API-12 | 建议前端近似先行，模型 P2 |
| U4 | 当前目标 HUD 数据模型：规则模板 vs 剧本杀目标模型（对齐 SceneGoalService） | 阶段一 API-13 | 建议规则模板（按 phase+进度统计），LLM 目标集另议 |
| U5 | REVEAL/ENDED 揭晓交互是否沿用现有 ScriptStatePanel 区 | 阶段一前端 | 建议沿用，二期补原型页 |
| U6 | 行动集是否需要配置化（roleplay.game.script.action-*） | API-1 | 对齐 D-004 纪律建议配置化，MVP 可常量 |
| U7 | 已搜地点回看是否扣 AP（0 或 1） | API-2 research 分支 | 建议 0（回看不消耗，搜证消耗已在首次发生） |
| U8 | 弃票记入统计方式：独立集合 vs votes 特殊值 | API-11 | 建议独立 abstainedVoters，quorum 在线数仍计、不参与 mostVoted |
| U9 | 倒计时语义：展示型 vs 强制推进（phase-timeout-ms 默认 0） | 阶段一前端 | 默认展示型；强制推进需改配置+验证 D-042 |
| U10 | 左栏在线状态点数据源（无心跳） | 阶段一前端 | 前端本地推断（最近发言/轮询活跃）或新增心跳（P2） |
| U11 | 前端对局页改造方式：渐进嵌入 vs 重写 | 全阶段 | 建议渐进嵌入 + 特性开关可回退 |
| U12 | 新 UI 是否覆盖一般模式/狼人杀 | 范围 | 建议仅剧本杀三页，其余模式保持现状 |
| U13 | VN 演出文本来源：前端拼装 vs 后端模板/LLM | 阶段三 | MVP 前端拼装（线索 content），后端模板二期 |
| U14 | 剧本杀目标模型与一般模式 SceneGoalService 是否统一 | 阶段三 | 建议不统一（语义不同），剧本杀侧独立轻量规则 |

---

## 附：核查自检

- [x] 全部结论以代码取证（Controller 端点全量导出 / toMap 键 / SSE 事件表 / client.ts / App2 路由 / 修改记录与决策史）
- [x] 未修改任何生产代码；未 git commit；未 spring-boot:run
- [x] 复用优先：60% 交互零改动复用现有端点；新增 13 端点（5 个可选）+ 1 个既有端点扩展（vote 弃票），全部沿用既有鉴权/SSE/快照机制
- [x] 三阶段落地划分与风险/依赖齐备；14 项不确定点显式标注
- [ ] 待主会话/未衡核查（docs/修改记录.md #220，核查状态：待核查）
