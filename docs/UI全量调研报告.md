# UI 全量调研报告（P-0815-F · 前端 UI 系统性调研）

> 调研人：researcher subagent（2026-08-15 23:1x-23:5x）
> 任务：主人反馈当前 UI 存在系统性问题（① 新旧版本遗留混乱不统一 ② 功能复杂无引导 ③ 剧本杀 gal 聊天区收不到消息卡在「已连接 · 等待对局消息」④ 对局信息杂乱/超出屏幕），全量调研并产出问题清单 + 重构方向建议。
> 范围：**只读调研**。未改任何业务代码、未启动服务、未 git commit。
> 证据基准：工作树源码现状（`roleplay-v4/frontend/src/` + `src/main/java/com/roleplay/engine/`，截至 2026-08-15 23:1x）。全部行号均实读取证；未标注 [NEEDS CHECK] 处为已核实事实。
> ⚠️ 批次标记说明：任务书指定 P-0815-F，但该标记已被 2026-08-15 并行 coder/worker 批次（修改记录 #202/#203/#204/#206/#207：前端卡顿调研、SSE 节流、双人死锁、部署等）占用。本批为纯文档产出（台账 #208），与既有 P-0815-F 批次零文件重叠，按 D-019/D-022 撞标顺延先例说明。

---

## 0. 结论摘要

1. **前端入口是单链的**：`main.tsx → App.tsx → App2（demo2/App2.tsx）`，7 个导航 tab + 对局桥（GameBridge）。旧版整机应用（AppLegacy.tsx）与旧页面（ScenePage/MaterialPage/LoginPage/旧 HomePage/旧 SettingsPage/FreeCharsPage）全部是**不参与构建的死代码**，但仍在目录中占用并让代码库/文档出现「新旧并存」假象；`App.tsx` 注释声称的 `src/demo/` 目录**已不存在**（注释过期）。
2. **剧本杀 gal 聊天区「无法启动」根因已定位（三层）**：①【致命】后端 `POST /api/script/init` 自 2026-08-10（P-0810-17，台账 #140）起默认 `outline_only=true`，只生成概略并把对局停在 **SETUP**；而前端**全仓没有任何 `generate_full` 调用点**，DM 的 advance 也被后端拒绝（SETUP 不可推进）→ **对局永久停在 SETUP，无任何可播放消息**。②【放大】SETUP/INVESTIGATION 阶段本身没有消息源：`script_phase/script_status` 事件在 GalStore 只刷新类型不入队，初始化时的 setup 公告在 SSE 连接建立前就已广播（错过无补发）。③【误导】「◉ 已连接」标签只代表 liveSessionId 非空，不代表 SSE 已连/对局在推进；且非 DISCUSSION 阶段输入会路由到一般对话 `/api/send`，**不回显到 gal 聊天区**（P-0815-B 实测 #200 佐证）。
4. **对局信息杂乱**集中在三处：ChatMessageFlow 垂直堆叠 6-7 层信息块（2D 地图/公告横幅/氛围横幅/loading/阶段横幅/在场条/gal 聊天区）；同一阶段信息在 4 处重复展示（阶段横幅 / GameAtmosphereBanner / ScriptStatePanel header / gal 旁路条）；顶栏 9 个按钮 + 6 个抽屉 + 2 个死按钮（「场景」「角色库」写 appStore 死字段）。剧本杀专属：抽屉 360px 固定宽（TraceDrawer 480px）+ 全屏遮罩一开即覆盖右栏与中列大半，DM 面板与玩家操作面板互斥；2D 地图面板固定 420px 高把 gal 立绘舞台压到极小。
5. **播放门控已逐项排除**（主会话指定核查）：isPlayerTurnGate 只门控候选区（GalChoiceBar L60-75/L153）；awaiting_playback/playback_done 链仅存在于经典视图/一般模式 gal/2D，**ScriptGalChatPanel 无此门控**；ensureLivePlay 纯队列驱动（GalStore L266）——「收不到消息」= 队列无消息（根因 A/B）+ 讨论期 SSE 错过窗口（根因 D），与 #199/#200「script_phase 公告能显示但发言不播放」完全一致（公告文本来自 announcement 全局横幅入队，script_phase 只刷类型不入队）。
6. **无引导**：SETUP 阶段右侧 ScriptStatePanel 无任何操作按钮；氛围横幅 setup 文案「选择剧本开始对局」与实际（已在对局中）矛盾；gal 等待态提示「点击输入框可发言」在 SETUP 实为输入死胡同；六阶段中 SETUP/搜证/投票/揭晓均有引导缺口（无生成入口、无 AP 消耗预提示、无超时/审批门玩家侧提示）；DM 面板有 key 输入与 roleKey 说明但无「DM key 由谁配置/空则放开」首屏解释。
7. **重构方向**：P0 两条（打通 generate_full 开局链路 / gal 消息源与状态语义修正），P1 三条（信息分层与引导 / 死代码与入口清理 / 三模式 gal 呈现统一），详见 §5。

| 主人反馈 | 根因定位 | 严重度 |
|---|---|---|
| ① UI 混乱新旧遗留 | §1 入口/路由 + §2 死代码/并存清单 | 中（结构债，需清理） |
| ② 复杂无引导 | §4.3 无引导清单 | 中 |
| ③ 无法启动（gal 区收不到消息） | §3 根因 A/B/C/D | **P0（致命）** |
| ④ 信息杂乱/超屏 | §4.1/§4.2 布局清单 | 中 |

---

## 1. 入口与路由全景图（含新旧遗留标注）

### 1.1 构建入口链（唯一活跃链）

```
frontend/index.html
  └─ src/main.tsx（L1-11：import styles/*.css + createRoot 渲染 <App/>）
       └─ src/App.tsx（L1-11：唯一职责 return <App2/>；头注释声称旧应用在 AppLegacy.tsx / src/demo/）
            └─ src/demo2/App2.tsx（L1-90：全新 demo 应用壳，唯一路由表）
```

`App2.tsx` 导航（L13-20 NAV 数组 + L73-86 视图分发）：

| Nav/视图 | 目标组件 | 功能归属 |
|---|---|---|
| 🌌 模式选择 (`home`) | `demo2/pages/HomePage.tsx` | 5 个入口卡：剧本选择/角色库/剧本生成/狼人杀/设置 |
| 📜 剧本选择 (`scripts`) | `demo2/pages/ScriptSelectPage.tsx`（4.4KB） | 一般模式/剧本杀模式两类页签 → 选中进 RoleSelectPage |
| 🎭 角色库 (`roles-lib`) | `demo2/pages/RoleLibPage.tsx`（12KB） | 角色卡管理（含 `free-chars` 视图复用本页） |
| 🪄 剧本生成 (`gen`) | `demo2/pages/ScriptGenPage.tsx`（14KB） | AI 生成/导入剧本 |
| 🐺 狼人杀 (`werewolf`，L63 `enterRoles({kind:'werewolf'})`) | `demo2/pages/RoleSelectPage.tsx`（28KB） | 狼人杀选角变体 |
| 🎮 **Gal Demo** (`gal`，L25/L86) | `gal/GalDemoPage.tsx` | **demo/调试页出现在生产主导航**（新旧并存/杂乱证据） |
| ⚙️ 设置 (`settings`) | `demo2/pages/SettingsPage.tsx`（15KB） | 新设置页 |
| 对局 (`game`，L85) | `demo2/pages/GameBridge.tsx` | 对局桥（见 1.2） |

角色选择页（`demo2/pages/RoleSelectPage.tsx`）按 `selectCtx.kind` 分 **murder（剧本杀）/ general（一般）/ werewolf（狼人杀）** 三变体，选角后 `go('game')` → GameBridge。另有 `RoleDetailPage.tsx`（角色详情，子视图）。

### 1.2 对局桥 GameBridge 的四条启动路径（`demo2/pages/GameBridge.tsx`）

| 模式 | 启动调用 | 挂载 UI | 备注 |
|---|---|---|---|
| 剧本杀 (`murder`) | L99 `api.scriptInit(script.title, players, 'full')` → setState mode='script' | **ChatPage**（L238 区域） | ⚠️ **不调 generate_full → 停在 SETUP（§3 根因 A）** |
| 狼人杀 (`werewolf`) | `api.werewolfInit(...)` | ChatPage | AI 补满 8 人 |
| 一般·自由聊天 (`general`+chat) | `api.startScene(...)` | **GalGeneralView**（L250，默认）\| galClassic 开关回退 **ChatPage**（L238-248） | **新旧并存**：gal 呈现与经典视图同会话并存可切换 |
| 一般·2D 探索 (`general`+explore) | LLM 地图 → `/api/simulation` | **PhaserSimulationView**（内嵌 SimGalChatPanel gal 聊天） | — |

### 1.3 死代码/过期引用（新 UI 里「消失」的功能都在这）

| 文件 | 状态证据 | 说明 |
|---|---|---|
| `src/AppLegacy.tsx`（15.9KB） | 全仓无 import（仅 App.tsx L5 / useGameSse.ts L4 注释提及） | 旧整机版入口：登录门禁 + 38 事件 SSE + ScenePage/ChatPage 分发，**不参与构建** |
| `src/components/ScenePage/ScenePage.tsx`（**104KB**） | 仅 AppLegacy L11 引用 | 旧主页面：场景管理 + 剧本杀 Tab + 2D 模拟 + 公告面板 + 方案A/B chips + 剧本编辑弹窗——**全部不在活跃 UI 中**；其能力一部分由 demo2 三页 + GameBridge 承接，另一部分（方案切换 demo 面板等）**功能消失** |
| `src/components/MaterialPage/MaterialPage.tsx` | 0 引用 | 素材页（角色/场景 AI 生成）→ demo2 角色库承接 |
| `src/components/LoginPage/LoginPage.tsx` | 仅 AppLegacy L14 | 登录门禁已被 demo2 去掉 |
| `src/components/HomePage/HomePage.tsx` | 仅 AppLegacy L15（App2 L8 引用的是 `demo2/pages/HomePage`） | 旧主页 |
| `src/components/SettingsPage/SettingsPage.tsx` + `.css` | 仅 AppLegacy L16（App2 L13 引用的是 `demo2/pages/SettingsPage`） | 旧设置页 |
| `src/demo2/pages/FreeCharsPage.tsx` | export 但 0 import；App2 L80 把 `free-chars` 视图映射到 `RoleLibPage` | 孤儿组件 |
| `src/components/common/`、`Modals/`、`Sidebar/` | 空目录（0 文件） | 遗留空目录 |
| `src/demo/` | **不存在**（git ls-files 0 命中、目录不存在） | `App.tsx` L4-5 注释声称「旧 demo 保留于 src/demo/」——**注释过期** |

### 1.4 「新旧并存」重复点（同一功能 ≥2 套实现）

1. **一般模式聊天**：GalGeneralView（P-0810-08 默认呈现） vs ChatPage 经典视图（galClassic 回退开关，GameBridge L49/L238-248）——同会话双呈现。
2. **剧本杀聊天**：ChatMessageFlow 中 `store.mode==='script'` 分支渲染 ScriptGalChatPanel（P-0815-B），非 script 走经典 MessageView 列表（ChatMessageFlow L238-291）——分支并存，ChatComposer 在 script 模式隐藏（L292-294）。
3. **剧本杀入口**：demo2 RoleSelectPage→GameBridge（活跃） vs ScenePage 剧本杀 Tab（死代码）——同一个后端能力两条前端入口，一条已死。
4. **设置/主页/角色管理**：demo2 三页 vs 旧 components 三页（全死）。
5. **顶栏死按钮**：ChatTopbar L87-88「场景」「角色库」→ `store.goToView('scene'/'config')` → appStore L516 只写 `view` 字段，而活跃路由用 **demoStore.view**（App2 L29-34）→ **点击无任何效果（死按钮）**。
6. **2D 渲染**：Phaser 三件套（mapData/ScriptMapScene/PhaserScriptMapView + SimulationScene/PhaserSimulationView，活跃） vs 自研 simulation.html（D-020 作废）。
7. **GalStore 单例共享**：GalGeneralView / ScriptGalChatPanel / SimGalChatPanel / GalDemoPage 全部共享 `useGalStore` 模块级单例，靠 enter/exitLiveMode 切换——不同屏共存时互踩风险（已知限制，SimGalChatPanel 先例可证）。

---

## 2. 组件树：活跃路径 vs 孤儿清单

### 2.1 活跃渲染路径（按模式）

```
一般模式 chat:  GameBridge → GalGeneralView → GalGeneralStage/GalDialogBox/GalChoiceBar/GalCharacter/
                GalSceneCard/GalHistoryDrawer + useAutoPlaybackDone（GalStore live 状态机 + galSseAdapter）
                └─ galClassic=true → ChatPage（经典消息流）
一般模式 explore: GameBridge → PhaserSimulationView →（PhaserSimulationView 内嵌）SimGalChatPanel
                → GalDialogBox/GalChoicesArea/GalInputArea（liveSayOverride 注入 /api/simulation/send）
狼人杀:         GameBridge → ChatPage → ChatTopbar/ChatLeftPanel/ChatRightPanel(WerewolfStatePanel/
                WerewolfActionPanel/WerewolfResumePanel)/ChatMessageFlow(经典 MessageView 列表 + 阶段横幅)/
                ChatDrawers；SSE 桥 useGameSse（werewolf_* 定向）
剧本杀:         GameBridge → ChatPage → ChatRightPanel(ScriptResumePanel+ScriptStatePanel)/
                ChatMessageFlow →（script 分支）ScriptGalChatPanel（P-0815-B gal 聊天区，立绘+打字机+输入区）
                + 上方 2D/地图面板(PhaserScriptMapView)/AnnouncementBanner/GameAtmosphereBanner/阶段横幅
                + useGameSse（script_*）+ 3s 轮询 scriptStatus
Gal Demo tab:   App2 → GalDemoPage → GalTopBar/GalStage/GalPortraitPanel/GalLivePanel（+ GalLiveBridge useSSE）
```

### 2.2 活跃组件清单（gal/）

| 组件 | 引用方 | 状态 |
|---|---|---|
| GalStore.ts（43KB 状态机+SSE 总入口 applySseEvent L595） | 全 gal 组件 | 活跃（单例共享） |
| galSseAdapter.ts（startLiveSync L94-107 / liveSay L257 路由） | GalGeneralView/ScriptGalChatPanel/GalDemoPage | 活跃 |
| GalGeneralView.tsx / GalGeneralStage.tsx | GameBridge L250 | 活跃（一般模式默认） |
| ScriptGalChatPanel.tsx（P-0815-B 新增） | ChatMessageFlow L241 | 活跃（剧本杀聊天区） |
| GalDialogBox.tsx / GalChoiceBar.tsx / GalCharacter.tsx | 上述三者 + GalStage | 活跃 |
| GalDemoPage.tsx / GalTopBar.tsx / GalStage.tsx / GalLivePanel.tsx / GalPortraitPanel.tsx | App2 L86（Gal Demo tab） | 活跃（demo 页） |
| GalHistoryDrawer.tsx / GalSceneCard.tsx / useAutoPlaybackDone.ts | GalGeneralView | 活跃 |
| galBackground.ts / galChoices.ts / galDemoData.ts | 多组件 | 活跃 |

### 2.3 活跃组件清单（components/ChatPage/ 等）

ChatPage.tsx（编排壳）/ ChatTopbar / ChatLeftPanel / ChatRightPanel / ChatMessageFlow / ChatComposer / MessageView / GameAtmosphereBanner / useGameSse / chatUtils / aiAvatar / script/ScriptStatePanel / script/ScriptResumePanel / werewolf/{StatePanel,ActionPanel,ResumePanel} / ChatDrawers（含 ScriptDmPanel、HistoryPanel、TraceDrawer 抽屉）/ AnnouncementBanner（ChatMessageFlow L167 + PhaserSimulationView）/ AnnouncementTicker（PhaserSimulationView）——**全部活跃**。

### 2.4 活跃组件清单（phaser/）

PhaserSimulationView（GameBridge explore + ChatMessageFlow L182-207 内嵌）/ PhaserScriptMapView（ChatMessageFlow + RoleSelectPage 地图预览）/ SimGalChatPanel（PhaserSimulationView 内嵌 gal 聊天）/ ScriptMapScene / SimulationScene / mapData / decorData / interactData / simulationData / simChatConfig / MiniMap——**全部活跃**（P-0815-F 渲染优化批次刚改过 SimulationScene/PhaserSimulationView）。

### 2.5 孤儿/死代码清单（汇总）

| 类别 | 文件 | 判定依据 |
|---|---|---|
| 死入口 | AppLegacy.tsx | 无 import |
| 死页面 | components/ScenePage/ScenePage.tsx、MaterialPage、LoginPage、HomePage、SettingsPage(+css) | 仅 AppLegacy 引用 / 0 引用 |
| 孤儿组件 | demo2/pages/FreeCharsPage.tsx | export 无 import |
| 空目录 | components/{common,Modals,Sidebar} | 0 文件 |
| 过期引用 | src/demo/（不存在）；App.tsx 注释 | git ls-files 0 命中 |
| 死按钮 | ChatTopbar「场景」「角色库」→ appStore.goToView（死字段） | App2 路由不消费 appStore.view |

### 2.6 剧本杀模式 UI 完整结构（主会话指定重点）

进入剧本杀对局后（GameBridge L99 → ChatPage），屏幕由 5 个信息层组成：

```
┌ ChatTopbar（顶栏，ChatTopbar.tsx L65-88）────────────────────────────────────┐
│ 品牌 + 状态 pill×2（运行/轮次） + 9 按钮：🎬导演 ⚙️设置 🎛主持人 💬私聊 🎨美术 │
│ 🧭逻辑链 场景 角色库 📋历史（script 模式多显示 主持人/私聊/美术 三按钮）       │
├ workspace 三列（global.css L397-400：140px | 260px | minmax(420px,1fr)）─────┤
│ ChatLeftPanel  │ ChatRightPanel           │ ChatMessageFlow（中间消息流）      │
│ （140px 角色    │  ScriptResumePanel（🔄恢复│  ① 2D/地图面板（PhaserScriptMapView│
│  列表/添加/语音 │  对局·折叠，常驻面板顶部）│     height=420，L150-160）          │
│  过滤）         │  ScriptStatePanel（阶段   │  ② AnnouncementBanner（L167）      │
│                 │  header/降级提示/托管提示│  ③ GameAtmosphereBanner（L170）    │
│                 │  /我的角色/AP/我的秘密/   │  ④ loading 条 + ⑤ 阶段横幅(含倒计时)│
│                 │  搜证地点/线索/转交/投票/ │  ⑥ presence-bar 在场条            │
│                 │  推进按钮/退出对局/2D入口)│  ⑦ ScriptGalChatPanel（L241：旁路  │
│                 │                          │     条+立绘舞台+GalDialogBox+输入框）│
│                 │                          │  （ChatComposer 已隐藏 L292-294）   │
├ ChatDrawers（抽屉集合，ChatDrawers.tsx L295-324）────────────────────────────┤
│ 历史会话(302)/导演(307)/DM 主持人(310-316)/私聊(318)/美术(321)/逻辑链(324)    │
│ 任一抽屉：固定 360px 右滑面板（TraceDrawer 480px）+ 全屏遮罩（global.css L60-67│
└───────────────────────────────────────────────────────────────────────────┘
```

逐块职责与数据源：

| 块 | 组件（文件:行） | 数据源 | 备注 |
|---|---|---|---|
| 顶栏 | ChatTopbar.tsx L65-88 | appStore（isRunning/currentRound/mode） | 9 按钮无 wrap；「场景/角色库」死按钮（§1.4-5） |
| 左栏 | ChatLeftPanel.tsx | appStore.agents/characters | 140px 固定 |
| 右栏·重连 | script/ScriptResumePanel.tsx L53-60（折叠头） | 无（手动输入 game_id/room_code+roleKey → POST /api/script/resume） | 常驻右栏顶部，默认折叠 |
| 右栏·状态 | script/ScriptStatePanel.tsx L50-160+ | scriptState（3s 轮询 + script_status SSE） | SETUP 阶段无任何操作按钮（§4.3） |
| 中间·地图 | ChatMessageFlow.tsx L150-160 | scriptState.map | 固定 height=420，压榨下方 gal 区 |
| 中间·横幅 | AnnouncementBanner（L167）/ GameAtmosphereBanner（L170） | announcement SSE / appStore | 阶段信息与阶段横幅/旁路条重复（§4.2） |
| 中间·gal 聊天 | gal/ScriptGalChatPanel.tsx（旁路条 L97-108 / 立绘舞台 L112-134 / GalDialogBox / GalInputArea / SSE 桥 L186） | GalStore live 队列（script_speech SSE + 3s 轮询转录双通道） | 「已连接·等待对局消息」问题载体（§3） |
| 抽屉 | ChatDrawers.tsx L295-324（DM=ScriptDmPanel 整体） | 各抽屉独立 REST/轮询 | DM 抽屉 360px 覆盖右栏+部分中列（§4.4） |

---

## 3. 问题 3 根因结论：gal 聊天区「已连接 · 等待对局消息」收不到消息

### 3.1 现象与界面定位

- 等待态文案出自 `gal/GalDialogBox.tsx` L42-48：
  `if (liveMode && !choiceNode && !current) → '◉ 已连接 · 等待对局消息…' / 'AI 发言 / 公告 / 阶段变化将在这里播放（点击输入框可发言）'`
- 该态 = **live 队列为空且无正在播放消息**时恒显（与 SSE 连接状态无关，liveSessionId 非空即显示「已连接」）。

### 3.2 根因 A（致命）：剧本杀对局永久停在 SETUP → 无任何可播放消息

证据链（文件:行号）：

1. `controller/ScriptController.java` L89-90：`POST /api/script/init` 的 `outline_only` **缺省 true**（P-0810-17 两阶段生成改造，2026-08-10，台账 #140）——init 只生成概略、对局停在 SETUP。
2. `api/client.ts` L268-275：`scriptInit(theme, players, mode?, roomCode?)` **不传 outline_only** → 恒走默认 true。
3. `demo2/pages/GameBridge.tsx` L99：murder 分支只调 `api.scriptInit(script.title, players, 'full')`，**之后无任何 generate_full 调用**。
4. 前端全仓 grep（`generate_full|generateFull|outlineOnly`）：**0 命中**（仅无关的本地 `generating` 状态）。
5. 后端 `ScriptGameService.java` L624（outlineOnly 分支）：`game.phase = Phase.SETUP`；L644 `broadcastPhase(game,"setup")` + L645 `broadcastStatus(game)` 后返回——**无后台自动触发完整生成**。
6. 唯一出口 `POST /api/script/generate_full`（ScriptController L133-139 → ScriptGameService L786）**前端从未调用**。
7. DM 手动推进也被拒：`ScriptGameService.advancePhase` L3001 的 switch 无 SETUP 分支 → L3041 `"当前阶段不可推进: setup"`（ScriptDmPanel 按钮文案 `setup→investigation`（ScriptDmPanel.tsx L285-288）实际永远报错）。
8. 结论：**任何经活跃前端进入的剧本杀对局（full 与 chat 均如此，chat 模式 outlineOnly 同样落 SETUP）永久停在 SETUP**。SETUP 期间 gal 聊天区没有任何消息来源（见根因 B）→ 恒显等待态。此为该问题主因，且为 **2026-08-10 起的前端集成回归**（此前 init 同步完整生成直接进 INVESTIGATION）。

### 3.3 根因 B（放大）：SETUP/INVESTIGATION 阶段本身无消息源 + 初始公告错过 + script_phase 不入队

1. GalStore `applySseEvent` 的可见消息源只有 4 个：`announcement`（L638，system 入队）、`arbiter_integrate`（L652）、`user_input`（L668）、`script_speech`（L686）。而 **`script_phase`（L676）/ `script_status`（L681）/ `script_ready` 只 `setLiveGameType` 刷新类型/标题，不入队**——阶段切换本身在 gal 聊天区**不会**产生消息（P-0815-B 实测 #200 中看到的「阶段切换」文案实际来自 announcement 全局横幅通道，非 script_phase）。
2. SETUP/INVESTIGATION 阶段无 announcement/script_speech 事件：阶段公告只在阶段切换点发（`broadcastPhase` → `broadcastSystemAnnouncement`，ScriptGameService L2828/L2844，五处切换点 init/startDiscussion/startVoting/resolveVote/confirmEnded + generateFull）；搜证/投票等动作**无 SSE 消息**（REST 响应仅写右侧面板本地状态）。
3. 唯一的 setup 公告在 SSE 连接建立**之前**广播：initGame L644 在 HTTP 响应返回前调用 → ScriptGalChatPanel 的 SSE 桥（`ScriptGalSseBridge` L51，`useSSE` 连接发生在 ChatPage 挂载后，L80 `enterLiveMode` → L186 桥挂载）→ 错过且**无补发**（`useSSE.ts` L29-43 的 `pullRecentAnnouncements` 仅在 `wasReconnect` 时触发，首连不补拉；script_* 事件本无断线补拉）。
4. 轮询兜底 `startLiveSync`（galSseAdapter L94-107）只在 `sc.phase !== 'idle' && sc.session_id === sessionId` 时把 **discussion 转录**入队——SETUP/INVESTIGATION 无转录 → 兜底也静默。

### 3.4 根因 C（误导）：「已连接」语义失真 + 非 DISCUSSION 输入是死胡同

1. `GalDialogBox` L45 的「◉ 已连接」仅表示 `liveSessionId` 非空；`ScriptGalChatPanel` 未把 `onStatus`（connecting/open/reconnecting）接到任何可见 UI（GalTopBar 的连接状态显示只在 GalDemoPage 用）→ **SSE 断连/重连、对局无推进用户都看不到**。
2. `galSseAdapter.liveSay` L257：`liveGameType==='script' && livePhase==='DISCUSSION'` 才走 `scriptDiscussionSay`；**SETUP/INVESTIGATION/VOTE 阶段输入落入 `api.send`（一般对话管线），不回显到 gal 聊天区**（P-0815-B 端到端实测 #200 明确记录）——玩家在等待态点击输入框发言 = 石沉大海，强化「卡死」感知。

### 3.5 播放门控链验证（排除性证据——门控不卡，队列无消息才是问题）

主会话要求核查 `isPlayerTurnGate / awaiting_playback / ensureLivePlay` 是否卡住播放，逐项排除：

1. **isPlayerTurnGate 只门控候选区，不门控播放**：`gal/GalChoiceBar.tsx` L60-75（`queueLen===0 || (roundComplete && queueLen<=1)` + P-0815-D 的 round_complete 约束）、L153 `const isPlayerTurn = usePlayerTurn()` 仅控制 GalChoicesArea 是否渲染候选条；对 liveQueue 播放引擎零影响。
2. **awaiting_playback / playback_done 链在剧本杀 gal 路径不存在**：全仓 grep——`awaiting_playback` 消费点仅 3 处且全部模式门控在一般/经典/2D：ChatMessageFlow L52/L106（经典视图，`mode!=='script'` 守卫 L106）、GalGeneralView L166/L250（一般模式 + useAutoPlaybackDone）、SimGalChatPanel L155-160（2D，group_id 路径）；**ScriptGalChatPanel 不使用 useAutoPlaybackDone（grep 0 命中）、无 awaiting_playback 消费** → 剧本杀 gal 路径零播放推进门控。
3. **ensureLivePlay 纯队列驱动**：`gal/GalStore.ts` L266：`liveMode && started && !finished && !choiceNode && 无进行中打字 && 无待点击当前条` → 取队首开播。唯一等待态：①队列空（=无消息）；②AI 消息打字完待用户点击「▼ 点击继续」（Gal 式交互设计，非卡死）。
4. **结论**：播放引擎在剧本杀路径无任何门控可卡（无 isPlayerTurn/awaiting_playback/livePlaybackArmed 依赖）；**「收不到消息」= 队列为空（根因 A/B）+ 消息错过窗口（根因 D）**，与 #199/#200 实测「script_phase 公告能显示」不矛盾——公告文本来自 `announcement` 全局横幅事件（GalStore L638 入队 system 样式），而 `script_phase`（L676）只刷新类型从不入队；「发言不播放」= 讨论期 script_speech 在 SSE 连接建立前广播被丢弃（D）+ 讨论自动推进快（#200 记录）。

### 3.6 次因 D：讨论期消息也常不可见（#200 实测佐证）

1. chat 模式 init 内即启动讨论引擎（ScriptGameService L686 `runDiscussionEngine(game)` 于 initGame 内 submit 到 discussionExecutor）——讨论期间广播的 `script_speech`（L2022-2033 逐条回调）若发生在前端 SSE 连接建立前，`SSEController.broadcastToSession` 无匹配 emitter 时**静默丢弃**（SSEController.java L140-155 `if (!delivered) log.debug(...dropped...)`）。
2. 讨论自动推进快（`roleplay.game.discussion.max-rounds` 默认 2；结束自动进 VOTE）——#200 实测「讨论阶段被后端自动推进到 VOTE，未观察到真实 LLM 讨论发言的逐字播放」。
3. 兜底依赖 3s 轮询转录（§3.3-4），存在 ≤3s 延迟且需会话精确匹配。

### 3.7 根因结论汇总

| 层级 | 根因 | 关键证据 | 修复归属 |
|---|---|---|---|
| A 致命 | outline_only 默认 + 前端零 generate_full 接线 → SETUP 死局 | ScriptController L89-90 / client.ts L268 / GameBridge L99 / 全仓 grep 0 命中 / advancePhase L3041 | 前端接线（后端已就绪）或改默认 |
| B 放大 | SETUP/INVESTIGATION 无消息源；script_phase 不入队；初始公告错过无补发 | GalStore L676/L681 vs L638/L686 / initGame L644 时序 / useSSE L29-43 | GalStore + 补拉 |
| C 误导 | 「已连接」=liveSessionId 非空；非 DISCUSSION 输入无回显 | GalDialogBox L45 / galSseAdapter L257 / #200 | 状态语义 + 输入门控 |
| D 次因 | 讨论 script_speech 连接前广播被丢弃 + 自动推进快 | SSEController L140-155 / #200 | 轮询兜底已存在，评估入队时机 |

> 判定：问题 3 **不是**「SSE 会话定向失败」也不是「播放门控（isPlayerTurn/awaiting_playback）卡住」——两条链路（useSSE→applySseEvent→liveEnqueue→ensureLivePlay 与 startLiveSync 轮询）代码正确，P-0815-B 冒烟 9/9 与端到端 #200 均验证过 script_speech 消费路径；真正的断点在上游「**对局根本没有推进到会产生消息的阶段**」（根因 A），叠加「即使推进，多数阶段也没有可入队的消息」（根因 B/C）。

---

## 4. 布局溢出 / 杂乱 / 无引导位置清单

### 4.1 信息超出屏幕 / 被挤压

| 位置 | 证据 | 问题 |
|---|---|---|
| workspace 三列布局 | `global.css` L55 `.workspace { grid-template-columns: 140px minmax(420px,1fr) }`；L397-400 script/werewolf 模式 `140px 260px minmax(420px,1fr)`；L682-685（<1080px 中列 360px）、L686-690（<900px 隐藏右栏） | 三列最小宽 140+260+420=820px，<900px 才隐藏右栏；中等窗口下中列信息被极限压缩 |
| 中列垂直堆叠 | ChatMessageFlow L150-294：2D/地图面板（`height:420`）+ AnnouncementBanner + GameAtmosphereBanner + loading 条 + 阶段横幅 + presence-bar + gal 聊天区（ScriptGalChatPanel）依次排布 | 6-7 层信息块；100vh 下 gal 立绘舞台被上方挤压到很小（`.galg-stage-layered` flex:1 但可被压缩，galGeneral.css L729-733） |
| 顶栏按钮溢出 | ChatTopbar L65-88：品牌 + 2 个状态 pill + 9 个按钮（导演/设置/主持人/私聊/美术/逻辑链/场景/角色库/历史），无 flex-wrap（`.topbar` global.css L34） | 窄窗口按钮溢出/遮挡 |
| 长文本撑高 | `.game-atmo-title`（global.css L1381-1383）font 16px bold 无省略号；`.game-atmo-atmo`（L1397-1401）背景描述无 line-clamp | 长剧本名/长背景把氛围横幅撑得很高 |
| 秘密全文 | ScriptStatePanel.tsx L84-90 秘密卡全文渲染无截断（260px 右栏） | 长秘密在窄栏大段换行（右侧面板可滚动但观感杂乱）；对比 ScriptGalChatPanel L105-108 旁路条截 24 字——两处不一致 |
| 2D 面板固定高 | ChatMessageFlow L155 `height={420}` 地图/2D 面板 | 打开后 gal 聊天区被进一步压缩 |

### 4.2 对局信息杂乱（叠加/重复）

| 位置 | 证据 | 问题 |
|---|---|---|
| 阶段信息 4 处重复 | ① ChatMessageFlow L225-235 阶段横幅（含倒计时）；② GameAtmosphereBanner 主题行（阶段+轮次+玩家，L68-88）；③ ScriptStatePanel L58-61 header；④ ScriptGalChatPanel L97-108 旁路条 chips | 同一阶段在中间列+右栏+聊天区三处同时展示，视觉噪音 |
| 抽屉泛滥 | ChatTopbar 6 个抽屉入口（导演/历史/DM/私聊/美术/逻辑链）+ 设置 popover | 功能入口密集，无分组/无引导 |
| 左栏过窄 | `.workspace` 左列 140px | 角色名/状态在 140px 内拥挤（presence 相关 chip 在中间列另有存在） |
| 消息与系统线混杂 | 经典视图 system-line/arbiter-box/MessageView 混合（ChatMessageFlow L270-290） | 一般模式列表样式多，层级不清 |
| 双输入并存风险 | gal 视图输入区 vs ChatComposer（script 模式已隐藏 L292-294；一般模式 galClassic 切换时 composer 在经典视图、gal 输入区在 gal 视图，互斥但两套输入 UI 并存） | 同一产品两套输入范式 |

### 4.3 无引导（空状态/首次提示缺失）

| 位置 | 证据 | 问题 |
|---|---|---|
| SETUP 阶段右侧面板 | ScriptStatePanel L50-58：SETUP 只显示 header/角色/AP/秘密，**无任何操作按钮/下一步提示**（后端 generate_full 唯一出口前端未接线） | 玩家不知道要「生成完整剧本」，也无按钮可点 → 卡死 |
| 氛围横幅文案误导 | GameAtmosphereBanner L44 `setup: '选择剧本开始对局'`；L141 `atmosphere: '对局尚未开始，等待主持人…'` | 已在对局内却提示「选择剧本开始」，指引错误 |
| gal 等待态提示 | GalDialogBox L46 `'AI 发言 / 公告 / 阶段变化将在这里播放（点击输入框可发言）'` | SETUP 下输入无回显（§3.4-2），提示与实际行为矛盾 |
| 全应用无 onboarding | 前端 grep 无引导/教程/新手相关实现 | 首次用户面对 7 tab + 6 抽屉 + 多面板无从下手 |
| 经典视图有、gal 视图无 | 经典空态「从一轮对话开始…」（ChatMessageFlow L296-300） | gal 等待态无等价的操作引导 |
| 阶段操作引导分散 | GameAtmosphereBanner guide 已有机制（L45-51 SCRIPT_GUIDES）但仅此一处、且与阶段横幅/面板重复 | 引导未形成「当前能做的一步」共识 |

**剧本杀六阶段引导现状逐阶段核对（主会话指定重点）：**

| 阶段 | 现状引导 | 缺口 |
|---|---|---|
| SETUP 准备 | 氛围横幅「对局尚未开始，等待主持人…」+ guide「选择剧本开始对局」（GameAtmosphereBanner L44/L141）；右栏无操作按钮 | **无「生成完整剧本」入口与进度**（后端 generate_full 唯一出口前端未接线，§3 根因 A）；文案与实际矛盾 |
| INVESTIGATION 搜证 | 右栏「🔍 搜证地点」按钮组 + AP 余额（ScriptStatePanel L96-113）；guide「点击地图地点搜证」 | 无「搜证消耗 AP 多少/不足后果」预提示；搜证动作无 SSE 消息（gal 区静默） |
| DISCUSSION 讨论 | gal 输入框 + guide「在下方输入框发言」；ChatComposer 已隐藏 | 输入是否成功无反馈（无回显路径已在 §3.4-2 说明）；无「@某角色可点名/公开线索可触发」提示 |
| VOTE 投票 | 右栏投票选择 + 推进按钮（ScriptStatePanel 投票区） | 无「超时弃票/quorum 低参与」文案提示（后端已有 vote_timeout/lowParticipation 机制，前端未展示） |
| REVEAL 揭晓 | 揭晓结果卡（script_reveal） | 无「审批门 pending 等待 DM 批准」的玩家侧提示（玩家视角不知在等谁） |
| ENDED 终局 | 「🔄 再来一局 / 📋 回到剧本选择」（P-0804-B） | 基本齐全 |

**DM 面板权限提示现状**（ScriptDmPanel.tsx）：有 DM key 输入框（L140，存 localStorage）+ roleKey 分发区带说明（L223-238「玩家断线后凭对局ID+roleKey 重连；错误 key 将被 403 拒绝」）+ 审批门说明（L261「投票阶段推进=揭晓判定，将进入 D7 审批门…」）——但**无「DM key 是什么/由谁配置/空则放开」的首屏说明**（后端 `roleplay.game.dm.key` 空=放开语义未向使用者传达），且角色令牌区把「🔑 角色令牌」与「🎛 DM key」两个概念并列展示，易混淆。

### 4.4 剧本杀专用布局冲突（DM 抽屉 / 右栏 / 中列同时在场）

| 冲突 | 证据 | 影响 |
|---|---|---|
| 抽屉覆盖右栏+中列 | `.drawer { position:fixed; right:-380px; width:360px; height:100vh }`（global.css L61-67）；TraceDrawer 480px（L1418）；遮罩全屏 rgba(0,0,0,.4)（L60） | DM/私聊/美术/历史抽屉一开即遮住右侧 260px 面板 + 中列右侧大半（≤1280px 屏几乎全遮）；关闭才能操作面板 → 玩家查 DM 面板与操作面板互斥 |
| 三处信息层并存 | 顶栏 9 按钮 + 右栏 ScriptStatePanel + 抽屉 360px 面板 | 屏幕被信息层瓜分，中列 gal 区（对局主呈现）只剩窄条；抽屉内信息（DM 全量）与右栏（玩家视角）重复展示同类字段（角色/秘密/AP/线索） |
| 2D 面板压 gal | ChatMessageFlow L155 `height={420}` 地图面板 + 上方 3 横幅 + 阶段横幅 + 在场条 → gal 立绘舞台被压缩到极小（100vh 下） | 打开地图后 gal 区高度不足，立绘/对话框拥挤（`.galg-stage-layered` flex:1 可缩，galGeneral.css L729-733） |
| 右栏自身 | ScriptResumePanel（折叠头）+ ScriptStatePanel 全量（秘密/AP/线索/转交/投票/推进/退出）纵向堆叠 | 260px 窄栏长内容滚动，长秘密/长线索撑高（§4.1） |

---

## 5. 重构方向建议（5 个候选）

> 共同前置：任何重构前先跑 `npm run build`（tsc）确认基线；改动后登记修改记录 + 更新 TEST_STATUS；不 git commit。

### 方向 1（P0 · 必做）：打通剧本杀开局链路（根因 A）

- **方案 1a（推荐，后端零改动）**：GameBridge murder 分支 `scriptInit` 成功后自动调新增的 `client.ts scriptGenerateFull(sid)`（POST /api/script/generate_full 已就绪，ScriptController L133）；ScriptStatePanel SETUP 阶段加「🔄 生成完整剧本」按钮 + 进度（消费 `script_ready`/`toMap.generating`）；阶段横幅/氛围文案改「完整剧本生成中…」。
- **方案 1b（更小，行为回退）**：`client.ts scriptInit` 增 `outline_only` 参数并在 GameBridge 传 `false` → 恢复 2026-08-10 前的同步完整生成（代价：init 阻塞 30-90s，两阶段设计的价值丢失）。
- **改动范围**：`client.ts` + `GameBridge.tsx` + `ScriptStatePanel.tsx`（+文案）。
- **风险**：低。后端契约已备；需验证 600s 超时覆盖完整生成+地图（P-0803-F 已调过）。

### 方向 2（P0 · 必做）：gal 聊天区消息源与状态语义修正（根因 B/C）

- GalStore `script_phase/script_status/script_ready` 入队为 system 消息（阶段切换在 gal 聊天区可见，复用 announcement 样式与去重键）——替代「错过公告=全程静默」；
- ScriptGalChatPanel 接入 `onStatus` 并把 liveStatus（connecting/open/reconnecting）显示在旁路条（「已连接」不再只代表 liveSessionId 非空）；SSE 断连时显式提示 + 重连后补拉 `scriptStatus`；
- 非 DISCUSSION 阶段输入禁用并提示「当前阶段不可发言（请用右侧面板搜证/投票）」——消除输入死胡同；
- （可选）对局 connect 后补拉一次 `announcementRecent` + `scriptStatus`，覆盖初始公告错过窗口。
- **改动范围**：`GalStore.ts` + `ScriptGalChatPanel.tsx` + `useSSE.ts`/`galSseAdapter.ts` + `galGeneral.css`。
- **风险**：低-中（script_phase 入队需与轮询/SSE 去重一致；建议复用 P-0814-G 的 (speaker,text) 去重）。

### 方向 3（P1）：对局信息分层与引导（问题 ②④）

- 阶段信息单点化：保留 1 处阶段展示（建议 ChatMessageFlow 阶段横幅或 Gal 旁路条），收敛 GameAtmosphereBanner 主题行 / ScriptStatePanel header 的重复字段；横幅区可折叠；
- 引导共识化：复用 GameAtmosphereBanner guide 机制，按「当前阶段唯一可做动作」给文案（SETUP=生成完整剧本 / INVESTIGATION=搜证 / DISCUSSION=发言 / VOTE=投票 / REVEAL=等揭晓 / ENDED=重开或返回）；gal 等待态文案与阶段联动；六阶段补齐缺口——SETUP 生成进度、搜证 AP 消耗预提示、投票超时/低参与提示、揭晓审批门等待提示（玩家侧）；DM 面板补「DM key 由谁配置/空则放开」首屏说明，区分「🔑 角色令牌」与「🎛 DM key」两个概念；
- 文本约束：`.game-atmo-title`/atmosphere 加 line-clamp、ScriptStatePanel 秘密卡加展开/收起（与旁路条 24 字截断对齐）；顶栏按钮窄屏 flex-wrap；抽屉宽度降级（<900px 抽屉改 88vw 内）。
- **改动范围**：`ChatMessageFlow.tsx` + `GameAtmosphereBanner.tsx` + `ScriptStatePanel.tsx` + `ScriptGalChatPanel.tsx` + `global.css`/`galGeneral.css`。
- **风险**：低。

### 方向 4（P1）：死代码与入口清理（问题 ①）

- 归档/删除：`AppLegacy.tsx`、`components/ScenePage/`（104KB）、`MaterialPage`、`LoginPage`、旧 `HomePage`、旧 `SettingsPage(+css)`、`demo2/pages/FreeCharsPage.tsx`、空目录 `common/Modals/Sidebar`、`App.tsx` 过期注释（src/demo 引用）；清理 `ChatTopbar` 死按钮（场景/角色库）或接到 App2 路由；
- 导航收敛：Gal Demo 移出主导航（改为设置内「开发者/调试」入口或保留但标注 demo）；
- 评估 `galClassic` 回退开关去留（一般模式 gal 成熟后可移除经典视图，或保留为高级选项）。
- **改动范围**：前端文件删除 + 构建/类型检查确认；**删除需主人确认**（AGENTS.md 规则 4）。
- **风险**：低（死代码不参与构建），但删除前需 tsc/构建全绿 + grep 确认无隐藏引用（本次调研已做引用普查，§2.5）。

### 方向 5（P2 · 可选）：三模式 gal 呈现统一 + GalStore per-instance

- 抽公共 gal 聊天布局核心（ScriptGalChatPanel / SimGalChatPanel / GalGeneralView 共用 stage/dialog/input 组合），剧本杀旁路条与阶段横幅统一为一种「对局信息条」；
- GalStore per-instance 化（当前模块级单例，enter/exitLiveMode 互踩是 P-0815-B 报告已知限制）——未来同屏并存（如 2D 内嵌 + 侧栏 gal）时必需；
- 狼人杀聊天区是否 Gal 化（现状经典列表）作为单独议题评估。
- **改动范围**：`gal/` 组件重构 + `GalStore` 实例化改造。
- **风险**：中-高（动核心状态机，一般模式回归风险最大）；建议作为 P1 后期独立批次。

### 候选方向速评

| 方向 | 优先级 | 工作量 | 风险 | 解决 |
|---|---|---|---|---|
| 1 打通 generate_full | P0 | 0.5-1 人日 | 低 | 问题③ 根因 A |
| 2 gal 消息源+状态语义 | P0 | 1-1.5 人日 | 低-中 | 问题③ 根因 B/C |
| 3 信息分层与引导 | P1 | 1-2 人日 | 低 | 问题②④ |
| 4 死代码清理 | P1 | 0.5-1 人日 | 低 | 问题① |
| 5 gal 统一+per-instance | P2 | 2-4 人日 | 中-高 | 问题①② 长期 |

---

## 6. 附：证据索引（文件:行号速查）

| 证据 | 位置 |
|---|---|
| 入口链 main→App→App2 | src/main.tsx L1-11 / src/App.tsx L1-11 / demo2/App2.tsx L13-20,L73-86 |
| src/demo 不存在 + 过期注释 | App.tsx L4-5；git ls-files `src/demo` 0 命中 |
| GameBridge 四条启动路径 | demo2/pages/GameBridge.tsx L93-107（murder scriptInit L99）、L238-250（galClassic/GalGeneralView） |
| 剧本杀 outline_only 默认 true | controller/ScriptController.java L89-90；L133-139 generate_full |
| 前端零 generate_full | api/client.ts L268-275（scriptInit 无 outline_only）；全仓 grep 0 命中 |
| init 概略→SETUP + setup 公告时序 | service/ScriptGameService.java L624（outlineOnly）、L644-645（broadcastPhase/broadcastStatus 于响应前） |
| advancePhase SETUP 拒绝 | ScriptGameService.java L3001、L3041 |
| script_speech 推送 | ScriptGameService.java L2022-2033（AI 逐条）、L2292（人类立即回显）；SSEController.java L140-155（定向无匹配静默丢弃） |
| GalStore 事件消费 | gal/GalStore.ts：applySseEvent L595；announcement L638 / arbiter_integrate L652 / user_input L668 / **script_phase L676（只刷类型）** / script_speech L686（入队+去重）；enterLiveMode L515；ensureLivePlay L266；liveEnqueue L877 |
| 等待态标签 | gal/GalDialogBox.tsx L42-48（L45「已连接·等待对局消息」、L46 提示文案） |
| 轮询转录兜底 | gal/galSseAdapter.ts L94-107（sc.session_id===sessionId 门控） |
| liveSay 路由（非 DISCUSSION → /api/send） | gal/galSseAdapter.ts L257 |
| ScriptGalChatPanel 挂载时序 | gal/ScriptGalChatPanel.tsx L51（bridge）、L80（enterLiveMode）、L88（startLiveSync）、L186（桥挂载） |
| 剧本杀聊天区替换 | components/ChatPage/ChatMessageFlow.tsx L241（ScriptGalChatPanel）、L292-294（composer 隐藏） |
| 布局与溢出 | styles/global.css L55（workspace 2 列）、L397-400（script 三列 140/260/420）、L682-690（响应式）、L34（topbar 无 wrap）、L1381-1403（game-atmo 无截断） |
| 阶段信息多处重复 | ChatMessageFlow L225-235（阶段横幅）；GameAtmosphereBanner L68-88（主题行）；ScriptStatePanel L58-61（header）；ScriptGalChatPanel L97-108（旁路条） |
| 无引导 | ScriptStatePanel L50-58（SETUP 无操作）；GameAtmosphereBanner L44（setup 文案）；GalDialogBox L46（等待提示） |
| 播放门控排除 | gal/GalChoiceBar.tsx L60-75/L153（isPlayerTurnGate 仅候选区）；GalStore.ts L266（ensureLivePlay 纯队列驱动）；ScriptGalChatPanel 无 useAutoPlaybackDone/awaiting_playback（grep 0 命中）；awaiting_playback 消费仅 ChatMessageFlow L52/L106（经典，mode 门控）/GalGeneralView L166/L250/SimGalChatPanel L155-160 |
| 剧本杀 UI 结构 | ChatTopbar L65-88；ChatMessageFlow L150-241（地图/横幅/阶段条/gal 区）；ChatRightPanel L99-103（Resume+StatePanel）；ScriptResumePanel L53-60；ChatDrawers L295-324（6 抽屉）；drawer CSS global.css L60-67/L1418；DM 面板 ScriptDmPanel L140/L223-238/L261 |
| 死按钮 | components/ChatPage/ChatTopbar.tsx L87-88 → store/appStore.ts L516（appStore.view 死字段，App2 用 demoStore.view） |
| 死代码引用普查 | §2.5（AppLegacy 无 import；ScenePage 仅 AppLegacy；MaterialPage/LoginPage/旧 HomePage/旧 SettingsPage 仅 AppLegacy；FreeCharsPage export 无 import；common/Modals/Sidebar 空目录） |
| P-0810-17 两阶段生成改造 | docs/修改记录.md #140（后端只改，前端未接 generate_full） |
| P-0815-B 端到端实测 | docs/修改记录.md #199/#200（SETUP 卡住 + 讨论自动推进快 + 非 DISCUSSION 发言不回显） |

> 注：本报告全部文件/类名/行号均来自工作树实读取证（Python UTF-8 扫描）；标注 [NEEDS CHECK] 处未在本报告出现，均属已核实事实。未启动任何服务、未修改任何业务代码。
