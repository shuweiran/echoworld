# Gal 界面替换剧本杀聊天部件调研报告

> 调研人：researcher subagent（2026-08-15）
> 任务：摸清一般模式 gal（Galgame 风格）界面实现，评估将其替换到剧本杀模式聊天部件（聊天 UI）的方案。
> 范围：只读调研 + 方案设计，未改任何业务代码，未启动服务，未 git commit。
> 证据基准：源码现状（`frontend/src/` 前端 + `src/main/java/com/roleplay/engine/` 后端，截至 2026-08-15 工作树）。

---

## 0. 核心结论摘要

1. **一般模式 gal 界面已成熟**：`GalGeneralView.tsx`（P-0810-08 主人拍板"呈现接管"）取代 ChatPage 成为一般模式默认呈现，具备立绘层 + 打字机对话框 + 候选/输入区 + 背景槽位的完整视觉小说式呈现；数据流经 `GalStore`（Zustand 单例）+ `galSseAdapter`（SSE + REST + 轮询）。
2. **gal 界面已有剧本杀数据消费地基（约 70% 就绪）**：`galSseAdapter.liveSay` 已支持剧本杀 DISCUSSION → `scriptDiscussionSay` 路由；`startLiveSync` 已实现剧本杀讨论转录 3s 轮询增量入队；`GalStore.applySseEvent` 已消费 `script_phase`/`script_status`；roleKey 身份已贯穿。**缺口集中在三点**：① `applySseEvent` 无 `script_speech`（后端已推送的讨论实时发言事件）case；② 无剧本杀模式挂载点（GameBridge murder 分支仍走 ChatPage）；③ GalGeneralView 为一般模式定制（hidePlayerBubbles/一问一答门控/场景卡）不能直接套用。
3. **剧本杀聊天部件现状的"聊天"部分实际缺位**：后端 `ScriptGameService` 在讨论引擎逐条回调时推送 `script_speech` SSE（L2033/L2292），但前端 `useGameSse.ts` 无该事件 case，`scriptState.discussion` 转录也无组件渲染 → **ChatPage 经典视图 DISCUSSION 阶段不显示 AI 讨论发言**（讨论发言目前仅在 2D 面板 SimGalChatPanel 与 Gal demo 页可见）。这正解释了主人"把 gal 界面用到剧本杀聊天部件"的动机——现剧本杀聊天体验缺失。
4. **推荐方案 B（改造 gal 兼容剧本杀消息流）**：给 GalStore/galSseAdapter 补 script 分支，在 ChatPage 剧本杀模式下挂载 gal 聊天区（替换中间消息流的对话部分），右侧 ScriptStatePanel（搜证/投票/秘密/DM/地图入口）原样保留。改动小、一般模式零回归、剧本杀面板功能零丢失、可渐进验收。

---

## 1. 一般模式 gal 界面调研

### 1.1 源码文件清单（`frontend/src/gal/`，20 文件）

| 文件 | 规模 | 职责 | 关键证据 |
|---|---|---|---|
| `GalStore.ts` | 38.3 KB | Zustand 全局状态：demo 状态机（seq/index/typing/log/choiceNode）+ live 状态机（liveQueue/liveStreams/current/activeSpeakerId）+ 立绘状态（portraits）+ SSE 事件总入口 `applySseEvent` | `enterLiveMode`/`exitLiveMode`/`liveEnqueue`/`liveToken`/`liveCompleteAgent`/`applySseEvent` |
| `galSseAdapter.ts` | 14.0 KB | 网络/副作用面：`resolveSessionId`（对局标识解析，含剧本杀 resume 反查）/ `startLiveSync`（类型探测 + **剧本杀讨论转录 3s 轮询增量**）/ `liveSay`（**发言路由：剧本杀 DISCUSSION → scriptDiscussionSay**）/ `pullGeneralHistory` / `refreshSuggestions` | §7.2/7.5 注记 |
| `GalGeneralView.tsx` | 17.4 KB | 一般模式呈现入口：顶栏（返回/标题/mode 标签/成员头像）+ GalGeneralStage + 历史抽屉 + 场景卡；元信息 5s 轮询（/api/state + /api/mode）；SSE 桥（GalGeneralSseBridge）；自动推进/一问一答门控 | P-0810-08 头注释、`useAutoPlaybackDone({enabled: !hasPlayer})` |
| `GalGeneralStage.tsx` | 9.9 KB | 舞台布局：layered 分层（背景 z0 → 立绘 z1 → 点击推进 z1.5 → 前景 z2：候选区+对话框+输入框）/ side 左立绘列变体；`allocateSlots` 立绘槽位规则（1 居中/2 分列/3-4 摊开/>4 只显说话人） | `galg-stage-layered` |
| `GalDialogBox.tsx` | 7.6 KB | 对话框：角色名+小头像+打字机（`▌` 光标）+ 点击推进（打字中=跳过/完成=下一条）；旁白/系统/玩家/choice 四种样式；上屏最近 2-3 条 log | `gal-dialog`/`gal-dialog-narrator` 等 |
| `GalCharacter.tsx` | 5.0 KB | 立绘组件：`GalSprite`（SVG 像素画 12×16 字符模板 + 调色板）/ `GalNamePlate`（未知角色姓名首字占位）/ `imageUrl` 真实 Pony 立绘 `<img>`；说话者放大置前 + 非说话者置灰 | `gal-char-active` |
| `GalChoiceBar.tsx` | 12.1 KB | `GalChoicesArea`（候选区：demo choiceNode + 后端 /api/round/suggest + 前端兜底，isPlayerTurn 门控）+ `GalInputArea`（常驻输入框 + 「✅ 已发送」反馈 + liveSayOverride 发送器注入） | P-0811-G 拆分 |
| `GalHistoryDrawer.tsx` | 9.3 KB | 历史记录抽屉：GET /api/history（limit 200，正/倒序）+ 按轮回滚（POST /api/round/rollback） | P-0810-08/P-0814-G |
| `GalSceneCard.tsx` | 5.3 KB | 场景卡：场景名/描述 + 目标列表（liveGoals：player_goal 明文 + AI 目标 ?? 掩码 + revealed 揭示全文） | P-0810-16 |
| `GalPortraitPanel.tsx` | 7.0 KB | 立绘面板：角色列表 / 生成按钮 / 帧切换 chips / 进度·错误（数据源 aiImage API） | P-0810-03 |
| `galBackground.ts` | 2.9 KB | 背景槽位：scene → POST /api/ai-image/scene-background → url；失败/未就绪 → `sceneGradient` 确定性渐变换色占位 | P-0810-15 |
| `useAutoPlaybackDone.ts` | 6.5 KB | 播完自动推进 hook：队列排空 → POST /api/simulation/playback_done（settleMs 300 / minGapMs 800 / enabled 门控） | P-0814-C/E |
| `GalLivePanel.tsx` | 12.9 KB | demo 页「真实对局」连接面板：输入对局标识 → resolveSessionId → enterLiveMode；含一般模式快速起局 | P-0810-06/07 |
| `GalDemoPage.tsx` | 6.2 KB | Gal demo 组装页（App2 导航 tab；demo 数据 / 真实对局双数据源切换） | App2.tsx L86 |
| `GalTopBar.tsx` / `GalStage.tsx` / `galDemoData.ts` / `galChoices.ts` | — | 顶栏 / demo 舞台（chat/2d 布局）/ demo 角色与序列 + `buildPlaceholderSpeaker`/`backendIdForName` 映射 / 前端候选兜底 | — |
| `gal.css` | 23.9 KB | 像素主题基础样式（CSS 变量：--gal-bg/--gal-accent/--gal-font 等） | §5 变量表 |
| `galGeneral.css` | 35.3 KB | 一般模式 Gal 呈现样式（`.galg-*`：page/topbar/stage/sprite-slot/foreground/drawer 等 100+ 类） | — |

**2D 模式复用先例**（重要参考）：`src/phaser/SimGalChatPanel.tsx`（P-0813-D/G/K）——把 Gal 聊天迁入 2D 模拟视图：复用 GalStore live 消息流 + GalDialogBox/GalChoicesArea/GalInputArea，消息源 = 2D 世界 `recentConversations` 轮询，`setLiveSayOverride` 注入 `POST /api/simulation/send`，`setLiveGameType('general') + setLiveStatus('open')` 手动置态使候选/输入门控生效，卸载时 `exitLiveMode` 清理。**这是"gal 组件复用到另一模式"的已验证范本。**

### 1.2 资源（立绘/背景）

| 资源 | 来源 | 说明 |
|---|---|---|
| 立绘三层 | ① SVG 程序化像素画（GalSprite，字符模板+调色板，零图片资源）；② 未知角色占位（GalNamePlate，SVG 姓名首字）；③ 真实 Pony V6 立绘 | ③ 后端 `src/main/java/com/roleplay/engine/aiimage/` 7 文件（ComfyUIClient/ImageGenService/ImageGenController/RmbgRemover/AiImageConfig 等，P-0810-01）；API：GET /api/ai-image/status、POST /api/ai-image/character/{id}/generate、GET /api/ai-image/character/{id}/images；帧键 avatar + 6 表情（happy/angry/sad/surprised/embarrassed/neutral），`_t` 透明版优先（P-0810-03 §6.1） |
| 背景 | POST /api/ai-image/scene-background（LLM 生成）+ sceneGradient 确定性渐变占位 | galBackground.ts；cache + inflight 合并去重 |
| 角色映射 | `galDemoData.ts` 的 SPEAKER_BACKEND_PROFILES（heroine=小铃/knight=凯尔/luna=露娜）+ BACKEND_NAME_TO_ID（中文名→后端 id） | 未知角色 `buildPlaceholderSpeaker`（名字色相哈希 + 首字占位） |

### 1.3 界面元素

| 元素 | 实现 | 说明 |
|---|---|---|
| 顶栏 | GalGeneralView `.galg-topbar` | 返回 + 标题/场景名 + mode 中文标签（自由/主角/多轨/导演）+ 成员首字小头像 + 场景卡/历史/经典视图按钮 |
| 立绘 | GalGeneralStage `.galg-sprites` + GalCharacter | 分层布局：立绘底部对齐、被底部对话框遮下半身（半身像效果）；说话者 scale 1.04 + 发光，非说话者 opacity .45 + grayscale；0.35s 平滑过渡 |
| 对话框 | GalDialogBox | 头像 + 角色名（角色色）+ 打字机逐字（25ms×2 字）+ `▼ 点击继续`/`■ 点击跳过`/`✦ 生成中…` hint；旁白居中斜体；系统金色横幅样式 |
| 文字显示 | 打字机 typing {full, chars, done} | 点击推进 advance：打字中→完成；完成→入 log→下一条；上屏最近 2-3 条 log 淡灰 |
| 选项/候选 | GalChoicesArea | 仅玩家回合显示（isPlayerTurnGate：liveMode && general && open && !sending && !playing && queueLen≤1）；后端候选（/api/round/suggest ≤40 字硬过滤）优先 + 前端兜底互斥 |
| 输入框 | GalInputArea | 常驻；Enter/发送按钮；已发送 4s 反馈；失败红字提示行；发送前先 advance() 弹队（P-0814-G） |
| 背景 | `.galg-bg` + veil 遮罩 | 真实背景 url 或渐变占位；生成中标签 |
| 其他 | GalHistoryDrawer / GalSceneCard / GalPortraitPanel | 历史+回滚 / 场景目标 / 立绘生成管理 |

### 1.4 数据模型（GalStore）

```
GalLiveMessage { id, kind: 'agent'|'player'|'system', speakerId, name, text, streamed?, level?, ts }
GalTyping      { speakerId, full, chars, done }
GalLogLine     { id, speakerId, name, text, isPlayer?, ts }        // log 上限 40 条
GalSpeaker     { id, name, title, color, hue, palette, sprite, isPlayer?, placeholder?, imageUrl? }
GalSceneGoals  { enabled, player_goal?, global_goal?, role_goals?, ai_goal_count?, revealed? }
```

- **播放引擎**：`liveQueue` 队列 → `ensureLivePlay` 空闲取队首开打字机 → 播放完成点击 `advance` 入 log → 弹队首播下一条。流式（streamed）句由 `agent_token` 增量缓冲 `liveStreams[agent]` + typing.full 实时增长，`agent_output` 结算全文替换。
- **去重纪律**（P-0814-G）：同步返回 agent_outputs 与 SSE agent_output 双路径按 (speakerId+text) 对 liveQueue+log 去重；历史补拉按同键去重。
- **玩家隐藏**：hidePlayerBubbles=true 时玩家消息不入队不渲染（一般模式呈现接管语义）；剧本杀若复用需置 false（玩家发言在剧本杀讨论中应可见）。

### 1.5 与后端 API 交互

| 通道 | 端点/事件 | 用途 |
|---|---|---|
| SSE | `agent_output`/`agent_token`/`round_complete`/`user_input`/`announcement`/`script_phase`/`script_status`/`scene_target_update`/`ai_image_ready`/`ai_image_error` | GalStore.applySseEvent 全量消费；session_id 过滤（P-0811-G B-2） |
| REST | GET /api/state?session_id=（scene/agents/round/awaiting_playback）；GET /api/mode?session_id=（一般 4 分类）；GET /api/history?session_id=（历史补拉）；POST /api/send（一般发言）；POST /api/round/suggest（候选）；POST /api/simulation/playback_done（推进）；POST /api/ai-image/scene-background（背景） | — |
| 剧本杀 | `api.scriptStatus(player)`（类型探测+讨论转录）；`api.scriptDiscussionSay(player, text, playerKey)`（liveSay 路由）；`api.scriptResume`（resolveSessionId 反查）；`api.scriptPrivateSay/History`（私聊，[NEEDS CHECK] gal 前端未接 UI） | galSseAdapter |

---

## 2. 剧本杀模式聊天部件现状

### 2.1 源码文件清单（`src/components/ChatPage/`，15 文件）

| 文件 | 规模 | 职责 |
|---|---|---|
| `ChatPage.tsx` | 12.5 KB | 编排壳：剧本杀/狼人杀 3s 轮询兜底 + useGameSse + 剧本杀动作处理器（search/transferClue/startDiscussion/startVoting/vote/resolve/finish/restart/leave）+ 布局（ChatTopbar + workspace{ChatLeftPanel, ChatRightPanel, ChatMessageFlow} + ChatDrawers） |
| `ChatMessageFlow.tsx` | 15.0 KB | 中间消息流：内嵌 2D/地图面板（PhaserScriptMapView/PhaserSimulationView）+ AnnouncementBanner + GameAtmosphereBanner + 阶段横幅 + presence-bar + conversation 消息列表（MessageView 流式渲染）+ 任务块 + ChatComposer |
| `ChatComposer.tsx` | 4.9 KB | 底部输入区：剧本杀 DISCUSSION → `api.scriptDiscussionSay`（失败降级 /api/send）；其他 → `store.sendMessage`；三轮/结束按钮 + 语音输入 |
| `ChatRightPanel.tsx` | 3.6 KB | 右侧操作面板（狼人杀/剧本杀状态列，透传 ScriptStatePanel） |
| `script/ScriptStatePanel.tsx` | 15.9 KB | **剧本杀状态面板**：阶段 header / LLM 降级提示 / 托管提示 / 我的角色 / AP / 我的秘密 / 搜证地点+结果 / 我的线索 / 线索转交 / 阶段推进按钮 / 退出对局 / 2D 讨论入口 / 投票 / 揭晓 / 终局（重开/回剧本选择） |
| `werewolf/*` | — | 狼人杀面板（ActionPanel/StatePanel/ResumePanel） |
| `useGameSse.ts` | 14.5 KB | SSE 桥：38 事件 → appStore（含 script_phase/script_status/script_reveal/script_private；**无 script_speech**） |
| 其他 | ChatTopbar/ChatLeftPanel/ChatDrawers/MessageView/chatUtils/GameAtmosphereBanner/ScriptResumePanel/aiAvatar | 顶栏/左角色面板/抽屉集合（导演·历史·DM·私聊·美术·逻辑链）/消息气泡/工具/氛围横幅/重连面板 |

### 2.2 当前 UI 结构与数据流（剧本杀模式）

```
GameBridge（murder 分支，L95-102）
  └─ POST /api/script/init {theme, players, mode:'full'} → mode:'script' + currentPlayer
      └─ <ChatPage />
          ├─ ChatTopbar（顶栏）
          ├─ ChatLeftPanel（角色面板）
          ├─ ChatRightPanel → ScriptStatePanel（右侧：秘密/搜证/线索/投票/揭晓/终局）★核心操作区
          ├─ ChatMessageFlow（中间）
          │    ├─ 2D/地图面板（PhaserScriptMapView / PhaserSimulationView）
          │    ├─ AnnouncementBanner + GameAtmosphereBanner + 阶段横幅
          │    ├─ presence-bar + conversation 消息列表（MessageView）★聊天区（见下方缺口）
          │    └─ ChatComposer（输入区：DISCUSSION → scriptDiscussionSay）
          └─ ChatDrawers（DM 面板/私聊/历史等抽屉）
数据流：3s 轮询 GET /api/script/status?player= → store.setScriptState（SSE script_status 优先）
       SSE：useGameSse（script_* 事件写 store）
       发言：ChatComposer → api.scriptDiscussionSay（DISCUSSION）/ store.sendMessage（其他）
```

**关键缺口（取证）**：
- 后端 `ScriptGameService.java` L2033（AI 讨论发言逐条回调）与 L2292（人类发言）均 `sse.broadcastScriptSpeech(...)` 推送 `script_speech` SSE（SSEController L353-360，会话定向）。
- 前端 `useGameSse.ts` 的 script 分支只处理 `script_phase`/`script_status`/`script_reveal`/`script_private`，**无 `script_speech` case**；`ScriptStatePanel`/`ChatMessageFlow` 均不渲染 `scriptState.discussion` 转录 → **DISCUSSION 阶段 AI 讨论发言在经典视图无消息列表展示**（玩家发言经 discussion_say 后也无回显，仅在 2D 面板/私聊等通道可见）。
- 讨论消息目前可见的两条路径：① 2D 面板（PhaserSimulationView → SimGalChatPanel 消费 recentConversations）；② Gal demo 页（startLiveSync 轮询 discussion 转录入 Gal 队列）。

### 2.3 后端数据模型（剧本杀）

- 23 个 REST 端点（ScriptController）：init / generate_full / map / map/switch / map/door / resume / keys / dm/status / advance / search / interact / discussion_say / transfer_clue / private / private/history / start_discussion / start_voting / vote / resolve / finish / leave / restart / status。
- 六态状态机：SETUP → INVESTIGATION → DISCUSSION → VOTE → REVEAL → ENDED；mode = full（真剧本杀）/ chat（简单对话版，P-0803-K）。
- `GET /api/script/status` toMap 键（ScriptGameService.toMap）：phase / players / your_role / your_secret / clues / ap / ap_max / ap_pool / my_clues / role_key / llm_degraded / trustees / locations / map（契约 v1）/ searched_locations / current_map_id / map_ids / decor_states / decor_flags / **discussion（List<{speaker, message}> 转录）** / murderer / correct / outline / generating / winner / simulation_started 等。
- SSE 事件（SSEController，会话定向）：`script_phase` / `script_status` / `script_reveal` / `script_private` / **`script_speech` {session_id, speaker, message, round, human?}** / `script_ready`。

### 2.4 与 gal 界面的差异对比

| 维度 | Gal 界面（一般模式） | 剧本杀聊天部件（现状） |
|---|---|---|
| 呈现 | 立绘层 + 打字机对话框 + 点击推进 + 候选/输入区 | 列表消息流（MessageView 气泡）+ 阶段横幅 + 右侧状态面板 |
| 消息模型 | GalLiveMessage{kind/speakerId/name/text/streamed} | appStore.messages{role/name/content/track_id/visible_to} + scriptState.discussion{speaker,message} 转录 |
| 讨论消息源 | startLiveSync 3s 轮询 discussion 转录（无 script_speech 消费） | **无渲染**（后端 script_speech 已推送、前端无 case 无组件） |
| 玩家身份 | livePlayerName + livePlayerKey（roleKey） | currentPlayer + scriptRoleKey |
| 发言路由 | liveSay：script+DISCUSSION → scriptDiscussionSay | ChatComposer：script+DISCUSSION → scriptDiscussionSay |
| 状态管理 | GalStore（独立 Zustand 单例） | appStore（全局）+ scriptState |
| 阶段面板 | 无（场景卡/历史/立绘面板替代） | ScriptStatePanel 全量（秘密/搜证/AP/线索/投票/DM/地图入口） |
| 推进机制 | 打字机点击推进 + playback_done（有玩家一问一答停等） | 轮次驱动 + 3s 轮询 |
| 立绘 | 像素/占位/Pony 真实立绘三层 | MessageView 首字头像（aiAvatar 可选图片） |

---

## 3. 方案对比

### 方案 A：整体替换（剧本杀入口直接挂 Gal 全屏视图）

- **思路**：GameBridge murder 分支 → 新建 `ScriptGalView.tsx`（仿 GalGeneralView 全屏 Gal 布局），剧本杀全部操作（秘密/搜证/投票/DM）收纳进 Gal 风格抽屉/面板，彻底替换 ChatPage。
- **改动范围（文件级）**：新增 `src/gal/ScriptGalView.tsx`（或 GalGeneralView 加 script 模式分支）；`GalStore.ts`（+script_speech/script_ready case、script 模式开关）；`galSseAdapter.ts`（复用 liveSay/startLiveSync，补双通道去重）；`demo2/pages/GameBridge.tsx`（murder 分支改路由）；`components/ChatPage/script/ScriptStatePanel.tsx`（改造为 Gal 抽屉/浮层或整体搬迁）；`galGeneral.css` 扩展；`ChatPage.tsx` 剧本杀分支裁撤。
- **工作量**：3-5 人日（前端为主，后端零改动）。
- **风险**：**中-高**。① GalStore 为模块级单例，与一般模式共享，script 模式逻辑侵入核心状态机，一般模式回归风险大；② ScriptStatePanel 功能面大（秘密/搜证/AP/线索转交/投票/揭晓/终局/托管/DM），全部收纳进 Gal 布局改造成本高且易破坏既有验收；③ 2D 地图/搜证联动（PhaserScriptMapView + decor 交互）与 Gal 全屏布局整合复杂。
- **对现有功能影响**：剧本杀全部面板功能需重做呈现层；经典 ChatPage 剧本杀路径废弃（可保留回退开关）；一般模式受 GalStore 改动波及。

### 方案 B：改造 gal 兼容剧本杀消息流（推荐，详见 §4）

- **思路**：保留 ChatPage 编排壳与右侧 ScriptStatePanel 不动，仅把中间消息流的对话部分替换为 gal 聊天渲染区（立绘 + 对话框打字机 + 输入区）；GalStore/galSseAdapter 补剧本杀数据源分支（script_speech SSE + discussion 转录轮询双通道）。
- **改动范围（文件级）**：`GalStore.ts`（applySseEvent + `script_speech`/`script_ready` case，复用 liveEnqueue/去重）；`galSseAdapter.ts`（startLiveSync 与 script_speech 双通道去重；liveSay 已就绪）；**新增 `src/phaser/` 或 `src/gal/ScriptGalChatPanel.tsx`**（仿 SimGalChatPanel 范本：enterLiveMode(sessionId,{playerName,playerKey}) + setLiveGameType('script') + hidePlayerBubbles=false + 组合 GalDialogBox/GalCharacter/GalInputArea + 阶段/秘密/线索旁路展示）；`components/ChatPage/ChatMessageFlow.tsx`（script 模式挂载 gal 聊天区替换 conversation 列表，保留横幅/阶段条）；`components/ChatPage/ChatPage.tsx`（透传 scriptSessionId/playerName/scriptRoleKey）；`demo2/pages/GameBridge.tsx`（murder 分支把 session_id/playerName 传给 ChatPage，或直接传面板）；`galGeneral.css` 少量扩展。
- **工作量**：1.5-3 人日。
- **风险**：**低-中**。① GalStore 单例与一般模式共存：GameBridge 分支路由下剧本杀与一般模式不同屏同时渲染，enter/exitLiveMode 互踩风险可控（SimGalChatPanel 已证）；若未来同屏并存需 per-instance 化 [NEEDS CHECK]；② script_speech 与轮询转录双通道需去重（复用 P-0814-G 的 (speaker,text) 去重键）；③ 候选区 isPlayerTurn 门控写死 liveGameType==='general'，剧本杀模式需另设门控（或剧本杀不显示候选，仅输入框）。
- **对现有功能影响**：剧本杀面板零改动、后端零改动、一般模式零改动（script 分支纯增量）；经典视图可保留切换开关。

### 方案 C：叠加独立 gal 渲染层（新组件自管队列，不碰 GalStore）

- **思路**：新建 `ScriptGalChatPanel.tsx` 自建消息队列与打字机（不依赖 GalStore live 状态机），直接消费 `scriptState.discussion` 转录 + 订阅 `script_speech` SSE；展示层复用 GalDialogBox/GalCharacter/GalInputArea。
- **改动范围**：新增 1-2 组件 + ChatMessageFlow 挂载 + css；GalStore/galSseAdapter 零改动。
- **工作量**：2-4 人日。
- **风险**：**中**。① 展示组件（GalDialogBox/GalInputArea）深度绑定 `useGalStore`（读 store 的 current/typing/log/choiceNode 等），自管队列要么改组件解耦（动 gal 核心组件）要么自实现打字机/推进逻辑（重复造轮子）；② 与方案 B 相比多出一套队列实现，长期维护双份状态机。
- **对现有功能影响**：隔离最干净（一般模式零影响），但成本高于 B 且与 gal 组件内部耦合矛盾。

### 方案 D：数据层最小补全（不换 UI，仅补讨论消息渲染）

- **思路**：只给 `useGameSse.ts` 补 `script_speech` case（addAgentMsg 进 conversation 列表），`ChatComposer` 发言后本地回显；聊天区仍为 MessageView 气泡列表。
- **改动范围**：useGameSse.ts + ChatComposer.tsx（各几行）。
- **工作量**：0.5-1 人日。
- **风险**：低。
- **对现有功能影响**：修复"剧本杀讨论消息不可见"缺陷，但呈现仍是聊天工具式 UI，**不满足主人"更好看的 gal 界面"诉求**——仅作过渡/兜底项。

### 对比总表

| 维度 | A 整体替换 | B 改造 gal 兼容（推荐） | C 叠加独立渲染层 | D 数据层补全 |
|---|---|---|---|---|
| 工作量 | 3-5 人日 | 1.5-3 人日 | 2-4 人日 | 0.5-1 人日 |
| 一般模式回归风险 | 高 | 低 | 低 | 无 |
| 剧本杀面板功能影响 | 需重做呈现 | 零改动保留 | 零改动保留 | 零改动 |
| 后端改动 | 零 | 零 | 零 | 零 |
| Gal 组件复用度 | 高 | 高（复用 live 链路） | 中（展示层耦合受限） | 无 |
| 体验提升 | 最高（全屏） | 高（聊天区 Gal 化） | 高 | 低（仅补消息） |
| 渐进可验收 | 差（一次大改） | 好（先 chat 版后 full 版） | 中 | — |

---

## 4. 推荐方案：B（改造 gal 兼容剧本杀消息流）

### 4.1 理由

1. **地基已备 70%**：`galSseAdapter.liveSay` 已有剧本杀 DISCUSSION 发言路由；`startLiveSync` 已有剧本杀讨论转录 3s 轮询增量入队；`GalStore.applySseEvent` 已消费 script_phase/script_status；roleKey 身份（livePlayerKey）已贯穿发言与 resume。缺的只是 `script_speech` 消费 + 挂载点 + 模式门控。
2. **一般模式零回归**：现有 GalGeneralView 链路（hidePlayerBubbles/一问一答门控/候选/自动推进）不动，script 分支纯增量（liveGameType 已有 'script' 取值，事件过滤与发言路由机制现成）。
3. **剧本杀功能零丢失**：右侧 ScriptStatePanel（秘密/搜证/AP/线索转交/投票/揭晓/终局/DM 抽屉/地图入口）原样保留，只替换中间消息流的"聊天呈现"——符合主人"把更好看的 gal 界面用到聊天部件上"的精确诉求，不碰玩法面板。
4. **范本已验证**：SimGalChatPanel（P-0813-D/G/K）已证明"GalStore live 链路复用到另一模式 + setLiveGameType/setLiveStatus 手动置态 + 外部消息源注入 + 卸载清理"模式可行。
5. **可渐进验收**：第一阶段只接 chat 简单对话版（无搜证无地图，纯讨论），第二阶段 full 版（配合 2D 地图/搜证面板并存），风险逐级释放。

### 4.2 实施步骤概要（建议作为后续批次任务书）

1. **数据层**（GalStore.ts + galSseAdapter.ts，纯增量）：
   - `applySseEvent` 增加 `case 'script_speech'`：{speaker, message} → liveEnsureSpeaker + liveEnqueue（kind='agent'；human 发言带 human? 标记可加 player 样式）；`script_ready` 可忽略或仅刷新状态。
   - `startLiveSync` 的讨论转录增量与 script_speech 双通道去重：入队前按 (speakerId, text) 对 liveQueue+log 去重（复用 pullGeneralHistory 的 seen 键模式），转录游标继续推进防止重复。
   - 候选门控：剧本杀模式不显示候选区（isPlayerTurnGate 已限定 general；剧本杀天然无 /api/round/suggest 消费），仅输入框可用——确认 GalChoicesArea 在 liveGameType==='script' 下自然隐藏（现有条件已满足，验证即可）。
2. **挂载点**（新增 ScriptGalChatPanel.tsx，仿 SimGalChatPanel）：
   - props：sessionId / playerName / playerKey / scriptState（阶段/秘密/线索旁路展示用，可选）。
   - 挂载：enterLiveMode(sessionId, {playerName, playerKey}) + setLiveGameType('script', phase, title) + setLiveStatus('open') + setHidePlayerBubbles(false)（剧本杀玩家发言应可见，与一般模式相反）。
   - 组合：GalDialogBox + GalInputArea（+ 可选 GalCharacter 立绘舞台：说话者驱动立绘放大置前，复用 allocateSlots 或简化版）。
   - 卸载：exitLiveMode 清理（对照 SimGalChatPanel useEffect 清理顺序）。
   - 立绘映射：roles → SPEAKER_BACKEND_PROFILES / backendIdForName / buildPlaceholderSpeaker 复用；Pony 立绘经 GET /api/ai-image/status 消费（GalPortraitPanel 可选接入）。
3. **接入**（ChatMessageFlow.tsx + ChatPage.tsx + GameBridge.tsx）：
   - ChatMessageFlow script 分支：conversation 列表区替换为 `<ScriptGalChatPanel .../>`（保留 AnnouncementBanner/阶段横幅/2D 面板/地图面板不变）；ChatComposer 在 script 模式可隐藏（gal 输入区承担发言）或保留兜底（双输入并存需防双发，建议隐藏 composer）。
   - ChatPage：把 scriptSessionId/currentPlayer/scriptRoleKey 透传。
   - GameBridge murder 分支：起局后已 setScriptSessionId（现有代码已做），把 playerRole 名/roleKey 传入（确认 store.scriptRoleKey 是否已在 init 响应回写 [NEEDS CHECK]——client.ts scriptInit 响应含 role_key 需回写 store）。
4. **验证**：npm run build（tsc 0）+ esbuild 冒烟（双通道去重/挂载卸载/发言路由/立绘映射）+ CDP 端到端（chat 版先验：讨论消息打字机播放、玩家发言回显、阶段横幅共存、console 0 错误）+ 全量 mvn 回归（后端零改动，基线 828/2 不变）+ static 同步 SHA256。
5. **收尾**：改码登记 docs/修改记录.md、更新 TEST_STATUS.md、DECISION_LOG.md（若涉及新决策）；不 git commit 交主会话统一提交。

### 4.3 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| GalStore 单例与一般模式共存互踩 | 中 | GameBridge 分支路由下不同屏渲染；参照 SimGalChatPanel 的 enter/exit 清理纪律；若未来同屏并存再 per-instance 化（P2） |
| script_speech SSE 与轮询转录双播 | 中 | 入队前 (speakerId,text) 去重（机制已存在，直接复用） |
| 讨论轮询与 SSE 实时性差异（轮询 3s 延迟） | 低 | SSE 为主、轮询兜底（与 script_status 同策略）；script_speech 已实时推送，轮询仅补 SSE 丢失窗口 |
| ChatComposer 与 gal 输入区双输入冲突 | 低 | script 模式隐藏 ChatComposer，发言统一走 gal 输入区（liveSay 路由已覆盖） |
| 立绘缺失（未生成 Pony 图） | 低 | 占位立绘（GalNamePlate）+ 像素画兜底三层机制天然覆盖 |
| 候选区误显示 | 低 | isPlayerTurnGate 限定 liveGameType==='general'，script 模式自动不显示（验证锁定） |

---

## 5. 附：关键文件索引与证据

| 证据 | 位置 |
|---|---|
| Gal 呈现接管决策 | DECISION_LOG P-0810-08（台账 #145/#146 区间批次）、docs/gal-界面设计.md §6-§7 |
| Gal 真实对局 SSE 接入（剧本杀路由/轮询） | docs/gal-界面设计.md §7（P-0810-06）+ galSseAdapter.ts |
| 2D 复用范本 | src/phaser/SimGalChatPanel.tsx（P-0813-D/G/K，台账 #176/#179/#183） |
| 剧本杀后端 script_speech 推送 | src/main/java/com/roleplay/engine/service/ScriptGameService.java L2019-2033 / L2282-2292；SSEController.java L353-360 |
| 剧本杀状态模型 | ScriptGameService.toMap（L331 discussionTranscript / L380-460 状态键）；ScriptController.java 23 端点 |
| 前端剧本杀现状缺口 | useGameSse.ts（script 分支无 script_speech）；ChatMessageFlow.tsx（conversation 区不渲染 discussion）；ScriptStatePanel.tsx（无转录展示） |
| 剧本杀模式路由 | demo2/pages/GameBridge.tsx L95-102（murder → scriptInit → ChatPage） |

> 注：本报告全部文件/类名/行号均来自工作树实读取证；未标注 [NEEDS CHECK] 处为已核实事实。
