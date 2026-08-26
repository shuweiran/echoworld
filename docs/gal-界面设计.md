# Gal 界面设计 — Galgame 视觉小说式前端改造（P-0810-02）

> 批次：P-0810-02（2026-08-10）｜阶段：应用级前端改造**第一阶段（核心组件 + 假数据 demo）**
> 目标：把 EchoWorld 前端从「聊天工具式 UI」改造成「Galgame 视觉小说式界面（像素风）」，本阶段跑通视觉与交互，后续阶段接入真实 SSE 消息流 + Pony 立绘。
> 代码位置：`frontend/src/gal/`（独立新目录，不侵入现有组件）；入口：App2 顶部导航「🎮 Gal Demo」。

---

## 1. 设计目标与原则

| 原则 | 说明 |
|---|---|
| 独立不侵入 | `src/gal/` 全部新文件；现有 ChatPage/ScenePage/phaser/ 零改动（App2.tsx/store.ts 仅加一个入口 tab） |
| 假数据驱动 | demo 用内置消息序列（galDemoData.ts），不依赖后端；后续接 SSE 只替换数据源 |
| 离线可构建 | 字体用系统等宽栈（Courier New/等宽），不引入 Google Fonts 等外网资源 |
| 像素风 | 暗色底 + 霓虹/柔和对比色 + 像素边框（box-shadow 阶梯描边）+ image-rendering: pixelated |
| 说话者驱动 | 谁说话谁立绘放大置前，其余角色半透明置灰；新角色切换时淡入 |

---

## 2. 组件规格（src/gal/）

### 2.1 GalStore.ts — 全局状态（Zustand）

单例 store，承载整个演示的运行时状态：

| 字段 | 类型 | 说明 |
|---|---|---|
| `mode` | `'chat' \| '2d'` | 当前模式：聊天（gal 典型布置）/ 2D（背景保留 2D 占位界面） |
| `seq` | `GalMessage[]` | 消息序列（来自 galDemoData） |
| `index` | `number` | 当前推进到的消息索引（下一跳位置） |
| `current` | `GalMessage \| null` | 当前正在展示的消息 |
| `typing` | `{ speakerId, full, chars, done } \| null` | 打字机状态（逐字进度） |
| `log` | `GalLogLine[]` | 已完成的发言记录（供上屏历史） |
| `choiceNode` | `GalMessage \| null` | 当前等待玩家选择的选项节点 |
| `speakers` | `GalSpeaker[]` | 在场角色（demo 固定 3 名 + 玩家） |
| `activeSpeakerId` | `string \| null` | 当前说话者 id（驱动立绘放大/置灰） |
| `finished` | `boolean` | 序列是否播完 |

| 动作 | 说明 |
|---|---|
| `start()` | 重置并开始播放序列 |
| `advance()` | 主推进：打字中→完成打字；已完成→推下一条（下一条是选项节点则停住等选择） |
| `skipTyping()` | 点击跳过：直接把打字机置为完成 |
| `choose(choice)` | 玩家选中选项：记录玩家发言→按 goto 跳转或顺延 |
| `submitText(text)` | 玩家自选输入：随时可发，作为玩家发言入 log 并推进 |
| `tick(n)` | 打字机逐字推进（由组件定时器调用） |
| `setMode(m)` | 切换 chat/2d |

**推进规则（核心数据流）**：`seq[index]` → 若 `type==='choice'` 设为 choiceNode 等待玩家（立绘全灰、对话框显示提问）→ 否则开始打字机（`typing` 置为进行中，`activeSpeakerId` 设为该条 speakerId）→ 打字完成后再点击 `advance()` → 本条入 log → `index++` 推下一条。玩家发言（选项或自由输入）立即入 log 并顺延/跳转（选项支持 `goto` 绝对索引跳转）。

### 2.2 galDemoData.ts — 假数据

| 导出 | 说明 |
|---|---|
| `GalSpeaker` | 角色：`id/name/title/color/hue/palette/sprite`（像素立绘 12×16 字符画调色板） |
| `GalMessage` | 消息：`id/speakerId/text/type('line'|'choice'|'narrator')/choices?/goto?` |
| `GAL_SPEAKERS` | 3 名像素风角色（如：雾岛 绫·元气少女 / 月读 凛·冷静天才 / 星野 露娜·神秘占卜师）+ 玩家 |
| `GAL_SEQUENCE` | demo 序列：多个角色轮流说话 + 2 个玩家选项节点（3-4 选项）+ 旁白节点 + 结局 |

角色立绘为**程序化像素画**：每个角色一个 12×16 字符模板（`h` 头发 / `s` 皮肤 / `e` 眼 / `m` 嘴 / `o` 衣 / `a` 点缀色 / `.` 透明）+ 调色板 → 渲染成 SVG（`shape-rendering: crispEdges`），无任何图片资源。

### 2.3 GalStage.tsx — 舞台（布局 + 模式切换）

根据 `mode` 渲染两种布局：
- **chat 模式**：左右分栏——左侧角色立绘区（3 名角色并排，active 放大置前 z-index 提升，其余 opacity 0.45 + grayscale），右侧对话框列（上屏 log + GalDialogBox + GalChoiceBar）。
- **2d 模式**：整屏 2D 占位背景（CSS 渐变夜空 + 像素山丘/星点/站台占位，标注「2D 地图区域（Phaser 集成后续）」），底部浮层对话框（半透明黑底像素边框），角色以小尺寸立在背景两侧。

### 2.4 GalCharacter.tsx — 立绘组件

| Prop | 说明 |
|---|---|
| `speaker` | GalSpeaker |
| `active` | 是否当前说话者 |
| `size` | 像素放大倍数（默认 4） |

行为：说话者放大（scale 1.15）+ 置前（z-index 高）；非说话者 opacity 0.45 + grayscale；**说话者切换时新角色淡入**（React key 绑定 `speaker.id + active` 触发 CSS 淡入动画）；名字牌跟随（说话者高亮色）。

### 2.5 GalDialogBox.tsx — 对话框

| 区域 | 内容 |
|---|---|
| 顶部 | 说话者名（角色色）+ 头像（复用立绘缩小版） |
| 中部 | 打字机文本（逐字 + `▌` 光标；点击对话框→跳过/完成） |
| 底部 | `▼ 点击继续`（完成态）/ `点击跳过`（打字中）/ 选项提示（choice 节点） |
| 上屏 | 最近 2-3 条 log 淡灰显示在对话框上方 |

点击对话框 = `advance()`（打字中完成、完成态推进）；旁白节点居中斜体无头像；choice 节点显示提问文本并高亮「轮到你了」。

### 2.6 GalChoiceBar.tsx — 选项条 + 自选输入

| 区域 | 内容 |
|---|---|
| 选项条 | 仅当 `choiceNode` 存在时显示 3-4 个预设选项按钮（`▶ 选项`），点击 → `choose()` |
| 自选聊天框 | **常驻**：输入框 + 发送按钮（Enter 发送）→ `submitText()`；choice 节点时与选项条并存 |

### 2.7 GalTopBar.tsx — 顶栏

| 元素 | 说明 |
|---|---|
| 标题 | 「🎮 Gal Demo · 像素视觉小说」 |
| 模式切换 | `💬 聊天` / `🕹 2D` 两键（setMode） |
| 操作 | `🔄 重新开始`（start） |
| 提示 | 「点击对话框推进 · 说话者切换自动淡入 · 随时可打字」 |

### 2.8 GalDemoPage.tsx — 组装页

顶栏 + 舞台 + 打字机定时器（`setInterval` 每 25ms 调 `tick(2)`）+ 底部说明条（阶段标注）。页面独立样式 `gal.css`。

---

## 3. 数据流（消息 → 立绘/对话框）

```
GalStore.start()
  └─ revealNext(): seq[index]
       ├─ type==='choice' → choiceNode=本条（等待 GalChoiceBar）
       └─ type==='line'/'narrator' → typing={...} + activeSpeakerId=speakerId
GalDialogBox 点击 → advance()
  ├─ typing 未完成 → 置为完成（跳过）
  └─ typing 完成 → 本条入 log → index++ → revealNext()
GalChoiceBar 选项点击 → choose(c)
  ├─ 玩家发言入 log（speakerId='player'）
  └─ c.goto ? index=c.goto : index++ → revealNext()
GalChoiceBar 自由输入 → submitText(text)
  ├─ 玩家发言入 log
  └─ 若 choiceNode：index++ → revealNext()；否则当前条完成 → 顺延
```

**立绘响应**：`activeSpeakerId` 变化 → GalCharacter 重渲染（新说话者淡入放大置前、旧说话者置灰）；玩家发言/选项节点时 `activeSpeakerId='player'` → 全场立绘置灰、对话框显示「你」。

---

## 4. 两模式布局

### 4.1 聊天模式（gal 典型布置）

```
┌────────────────────────────────────────────────────────┐
│ GalTopBar：🎮 Gal Demo  💬聊天 🕹2D  🔄重开              │
├──────────────────────────┬─────────────────────────────┤
│ 立绘区（左侧）            │ 对话区（右侧）               │
│  ┌────┐ ┌────┐ ┌────┐   │  上屏 log（最近 2-3 条灰字）   │
│  │绫   │ │凛   │ │露娜 │   │ ┌───────────────────────┐ │
│  │放大置前│ │置灰 │ │置灰 │   │ │ [名] 打字机文本▌      │ │
│  │(说话者)│ │    │ │    │   │ │ ▼ 点击继续            │ │
│  └────┘ └────┘ └────┘   │ └───────────────────────┘ │
│                          │ ▶ 选项1 选项2 选项3 选项4    │
│                          │ [输入框……] [发送]（常驻）    │
└──────────────────────────┴─────────────────────────────┘
```

### 4.2 2D 模式（背景保留 2D 占位，底部浮层对话框）

```
┌────────────────────────────────────────────────────────┐
│ GalTopBar（同左，居顶浮层）                               │
│  ⠀2D 背景占位（CSS 渐变夜空+像素山丘+星点+站台标牌）        │
│  ⠀「2D 地图区域 · Phaser 集成后续」标签                     │
│  ⠀[小立绘] ⠀⠀[小立绘] ⠀⠀[小立绘]（背景两侧）             │
│  ┌────────────── 底部浮层对话框 ──────────────┐          │
│  │ [名] 打字机文本▌    ▼ 点击继续             │          │
│  └────────────────────────────────────────────┘          │
│  ▶ 选项条（choice 时）＋ [输入框][发送]（常驻）              │
└────────────────────────────────────────────────────────┘
```

---

## 5. 像素主题规范（gal.css CSS 变量）

| 变量 | 值 | 用途 |
|---|---|---|
| `--gal-bg` | `#0d0a1a` | 暗色底 |
| `--gal-panel` | `#171230` | 面板底 |
| `--gal-border` | `#3b2d63` | 像素描边主色 |
| `--gal-accent` | `#ff6ad5` | 霓虹粉（主强调） |
| `--gal-accent2` | `#4de1ff` | 霓虹青（次强调） |
| `--gal-gold` | `#ffd166` | 柔和金（玩家/选项） |
| `--gal-text` | `#ece6ff` | 主文本 |
| `--gal-dim` | `#6b6390` | 置灰文本/非说话者 |
| `--gal-font` | `'Courier New','NSimSun',monospace` | 本地等宽（零外网） |

| 技法 | 实现 |
|---|---|
| 像素边框 | `box-shadow: 0 0 0 3px var(--gal-border), 0 0 0 6px rgba(0,0,0,.5)` 阶梯描边 + `border-radius: 0` |
| 像素化渲染 | 立绘 SVG `shape-rendering: crispEdges` + `image-rendering: pixelated` |
| 霓虹 | `text-shadow: 0 0 6px var(--gal-accent)`（标题/说话者名） |
| 置灰 | `filter: grayscale(1) opacity(.45)`（非说话者） |
| 淡入 | `@keyframes galFadeIn { from{opacity:0; transform:scale(.8)} to{opacity:1; transform:scale(1)} }` |
| 光标 | `▌` 字符 + `@keyframes blink` |

---

## 6. 后续接入真实 SSE + Pony 立绘的接口约定

本 demo 的组件与数据层**解耦**，后续只替换数据源、不改组件：

1. **消息流接 SSE**（后端已有 `agent_output` / `script_*` SSE 事件）：
   - 约定：`GalStore` 新增 `pushExternalMessage(msg)`（外部消息 → 排队入 seq 尾部，自动 reveal）；现有 `seq` 只读 demo 序列，接 SSE 后改为「首条 demo + 后续 SSE 追加」或纯 SSE 追加模式（开关 `GalStore.useSse=true`）。
   - SSE 事件映射：`agent_output`（speaker=agentName）→ `GalMessage{ speakerId: agentName, type:'line' }`；玩家 `/api/send` 回显 → `GalMessage{ speakerId:'player' }`；`script_phase` → 旁白节点。
2. **Pony 立绘接入**：
   - 约定：`GalSpeaker` 增加可选 `imageUrl?: string`（Pony V6 生成图 URL，来自 P-0810-01 的 `GET /api/ai-image/character/{id}/images` 或 assets 登记图）；`GalCharacter` 检测到 `imageUrl` 时渲染 `<img src={imageUrl}>`（外层保留像素边框与置灰/放大逻辑），无图回退当前 SVG 像素占位。
   - 表情切换：`GalMessage` 增加可选 `emotion?: string`（如 `happy/sad/surprised`），映射立绘表情集 URL 规则 `${imageUrl}?emotion=`（约定后续批次）。
3. **组件契约不变**：`GalDialogBox/GalChoiceBar/GalStage` 只消费 `GalStore`，不感知数据来源；新数据源只需产出 `GalMessage[]` 形状（`speakerId/text/type/choices/goto/emotion`）。

---

## 6.1 P-0810-03 落地注记（2026-08-10，前端联调已完成）

> 本批次已按 §6 接口约定实现 Pony 立绘接入 + SSE 事件订阅，现状与约定差异如下：

1. **数据层落地**：`src/api/aiImage.ts`（4 API 封装 + `subscribeAiImageEvents` SSE helper，独立 EventSource 可在 store 外使用）；`GalStore` 扩展 `backendCharacters`/`portraits`（backendId → `{frames, selectedFrame, generating, progress, error, registered}`）+ `refreshImageStatus/generatePortrait/selectFrame/applyImageEvent/initImageEvents/disposeImageEvents`；`GalCharacter` 支持 `imageUrl` prop（有真实立绘渲染 `<img>` 像素化，无则回退 SVG 占位）；`GalStage` 经 `portraitUrlFor`（选中帧→回退 avatar）两模式接线；新增 `GalPortraitPanel`（🎨 立绘面板：角色列表/生成按钮/帧切换 chips/进度·错误）。
2. **角色映射**：绫→heroine（小铃）、凛→knight（凯尔）、露娜→luna（前端按 `SPEAKER_BACKEND_PROFILES` 档案自动注册并生成）——映射集中在 `galDemoData.ts` 单处可改。
3. **⚠️ SSE 现状（实证）**：本批次核验时 8000 后端（源码 + 运行 jar）**尚未实现** `ai_image_ready/ai_image_error` 推送（P-0810-01 仍在作业中）；订阅 helper 已按约定契约（payload 含 characterId/类型/URL）就绪，后端事件上线即生效。当前「生成→立绘显示」由 **5s 轮询兜底** 保证（真机闭环已验证：generate→~110s avatar 出图→URL 200 image/png；**浏览器真机走查 8/8 PASS**——Edge headless+CDP + tools/static_proxy.mjs（服务 dist + /api 代理 8000）跑 tools/cdp_gal_p0810.mjs：面板渲染/真实立绘/帧切换即时换图/生成 loading/console 0 错误）。
4. **帧约定**：后端帧键 = `avatar` + 6 表情（happy/angry/sad/surprised/embarrassed/neutral，bust 构图）；面板 chips 切换即 `selectFrame`，立绘区即时换图。
5. **构建**：npm run build 通过（vite 105 modules），产物在 dist/ **未同步 static**（沿用 P-0810-02 约定，待主会话确认）。
6. **测试环境 CORS 注记**：后端 CORS 白名单仅 `localhost:5173/8000`，用其他端口跑前端时浏览器 POST 会带 Origin 被 403——static_proxy 已剥离 Origin 适配；生产同源 8000 无此问题，应用代码零改动。

---

---

## 7. 本阶段范围与验收

| 项 | 验收 |
|---|---|
| 聊天模式 | 左侧立绘区 + 右侧对话框；点击对话框推进；说话角色切换立绘淡入/放大置前/其余置灰 |
| 2D 模式 | 背景保留 2D 占位界面（CSS 渐变/山丘/星点），底部浮层对话框 |
| 打字机 | 逐字显示，点击跳过/直接完成；完成后再点击推进下一条 |
| 说话者驱动 | demo 序列多角色轮流说话 + 玩家节点；说话者立绘放大置前 |
| 玩家交互 | 选项条（3-4 个预设）+ 常驻自选输入框并存；玩家发言入 log |
| 像素风 | 像素字体（本地等宽）/像素边框/暗色底+霓虹 |
| 集成 | App2 导航新增「🎮 Gal Demo」tab，不影响现有页面；npm run build 通过（产物不同步 static，待确认） |

---

## 8. 文件清单

| 文件 | 说明 |
|---|---|
| `frontend/src/gal/galDemoData.ts` | demo 消息序列 + 角色定义 |
| `frontend/src/gal/GalStore.ts` | Zustand store |
| `frontend/src/gal/GalStage.tsx` | 舞台（chat/2d 布局） |
| `frontend/src/gal/GalCharacter.tsx` | 立绘（SVG 像素占位/放大/置灰/淡入） |
| `frontend/src/gal/GalDialogBox.tsx` | 对话框（打字机/点击推进） |
| `frontend/src/gal/GalChoiceBar.tsx` | 选项条 + 自选输入框 |
| `frontend/src/gal/GalTopBar.tsx` | 顶栏（模式切换/重开/提示） |
| `frontend/src/gal/GalDemoPage.tsx` | 组装页 |
| `frontend/src/gal/gal.css` | 像素主题样式 |
| `frontend/src/demo2/App2.tsx` | 新增「🎮 Gal Demo」NAV 入口 + 视图分支 |
| `frontend/src/demo2/store.ts` | View 类型 + `'gal'` |

## §7 P-0810-06 落地注记：真实对局 SSE 接入（阶段 B）

> 2026-08-10 落地（P-0810-06，本设计 §6「后续接真实 SSE」正式实现）。本节记录与 §1-§6 的差异与扩展。

### 7.1 数据源双模式（顶部切换）
- GalDemoPage 顶部新增数据源切换：**🎬 Demo 数据**（内置 GAL_SEQUENCE 剧情）/ **🔌 真实对局**（SSE 直播）。两者并存互不干扰：store 持有 `liveMode` 标志，demo 状态机（seq/index/revealNext）与 live 状态机（liveQueue/ensureLivePlay）互斥切换。
- 真实对局连接流程：GalLivePanel 输入对局标识（session_id / 房间码 / 对局 ID）+ 玩家名 + 剧本杀 roleKey（可选）→ `galSseAdapter.resolveSessionId`（含连字符=session_id 直连；否则依次试 script resume（game_id/room_code，需 player_key）→ werewolf resume（room_code+player）→ 兜底直连）→ `enterLiveMode` → GalLiveBridge 子组件挂载 `useSSE(sessionId, onStatus)`。
- 连接状态显示：已连接（●，SSE onopen 触发；注意后端 SSE 头随首次心跳/事件提交，最长 ~15s 才显示）/ 重连中（⟳ 闪烁）/ 事件计数 / 对局类型（剧本杀/狼人杀/一般/未探测）/ 阶段（阶段名统一大写：剧本杀枚举大写、狼人杀小写 day_discuss 归一为大写）。

### 7.2 SSE → store 事件映射（GalStore.applySseEvent）
| 事件 | 处理 |
|---|---|
| agent_output | speaker=agent_name 消息入队/流式结算；**script/werewolf 局过滤**（这两类局不经一般对话管线，防跨局全局广播串扰） |
| agent_token | 流式增量缓冲（liveStreams[agent]）+ 打字机 full 实时增长（逐字重放） |
| werewolf_speech | 同 agent_output 入队 + 探测类型 werewolf |
| announcement | 旁白消息（kind=system，📢系统名 + level 保留） |
| user_input | 玩家消息回显（右对齐玩家样式）+ 本地回显去重（10s 窗口同文本） |
| script_phase / script_status | 类型=script + 阶段 + 剧本名（非本局 session_id 过滤） |
| werewolf_phase | 类型=werewolf + 阶段 |
| ai_image_ready / ai_image_error | 转发既有立绘处理（P-0810-03） |

### 7.3 播放引擎（live）
- 消息队列 liveQueue 顺序播放：入队 → ensureLivePlay 空闲即开播（打字机 chars 从 0 逐字重放）；点击推进（advance live 分支：完成→入 log→弹队首→播下一条）。
- 流式句（streamed）：agent_token 实时增长 typing.full；agent_output 到达即完成当前句（全文替换 + 立即 done）；未播放的流式句在 agent_output 结算后以全文替换。
- 剧本杀讨论发言：后端无讨论 SSE 推送（D-012 讨论引擎独立实例限制）→ `startLiveSync` 3s 轮询 GET /api/script/status 的 discussion 转录增量入队（跳过 SILENCE_MARKER 静默占位；speaker=系统 → system 旁白）。

### 7.4 说话者 → 立绘映射（扩展）
- SPEAKER_BACKEND_PROFILES 补 heroine（小铃）/knight（凯尔）档案；BACKEND_NAME_TO_ID 中文角色名 → 后端角色 id（小铃/绫→heroine、凯尔/凛→knight、露娜→luna）。
- 未知角色名 → `buildPlaceholderSpeaker`（名字色相哈希 + 通用轮廓 + **SVG 姓名首字**占位立绘 GalNamePlate），面板显示「对局角色」+「可注册生成」（名字派生通用档案出图）。
- 有立绘优先 `{frame}_t` 透明版（RMBG 抠背景产物）：selectedFrame_t → selectedFrame → avatar_t → avatar。

### 7.5 玩家交互
- 底部输入框常驻（GalChoiceBar，live 模式隐藏选项条）；`liveSay` 路由：剧本杀 DISCUSSION → scriptDiscussionSay(player, text, playerKey)；狼人杀 DAY_DISCUSS → werewolfDiscussionSay；其他 → api.send。成功本地回显（api.send 的 user_input 回显经去重）；失败 → 输入区红字 + 系统提示行。
- ⚠️ 既有后端缺陷（未改，建议后续批次）：WerewolfController.discussionSay 失败分支不可变 Map.put → 500。

### 7.6 新增文件
| 文件 | 职责 |
|---|---|
| `src/gal/galSseAdapter.ts` | resolveSessionId / startLiveSync（类型探测+转录增量轮询）/ liveSay（发言路由） |
| `src/gal/GalLivePanel.tsx` | 连接面板（标识输入/状态 chips/断开） |
| `src/api/useSSE.ts`（改） | +可选第三参 onStatus(connecting/open/reconnecting)，旧调用方零影响 |

### 7.7 验证（2026-08-10）
- npm run build 通过（107 modules，产物 dist/ 未同步 static）；store 冒烟 40/40 + adapter 冒烟 16/16。
- 真机：狼人杀 SSE 实流（werewolf_phase + werewolf_speech×5）+ 浏览器真机走查 10/10（剧本杀 chat 模式：连接→阶段显示→讨论转录打字机播放→玩家发言回显→占位立绘→console 0 错误）。
