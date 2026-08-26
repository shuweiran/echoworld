# 前端 UI 风格替换报告（P-0816-V 调研）

> 任务：把「剧本杀对局页」的 UI 风格与理念（三栏布局 + 阶段驱动 + 深色精致风，对齐 docs/ui-prototype/*.html 原型）统一到整个软件。
> 本报告为 **只读调研 + 可执行替换方案**，未修改任何生产代码；代码事实均带文件+行号。
> 调研范围：`D:\echoworld\frontend\src`（2026-08-16 23:0x 快照）。
> 批次：P-0816-V（修改记录 #239）。状态：待核查。

---

## 1. 现状摘要

### 1.1 页面/路由全清单（10 路由 + 对局内 4 模式）

路由壳 `demo2/App2.tsx`（NAV 8 项 + 子视图），状态驱动 `demo2/store.ts` `View` 类型（L14-25）。

| # | 路由/入口 | 页面组件 | 一句话职责 | 风格现状 |
|---|---|---|---|---|
| 1 | `home` | demo2/pages/HomePage.tsx | 模式选择（剧本选择/角色库/剧本生成/狼人杀/设置五入口卡，四感氛围） | 新大厅风（demo2 styles.css；`.home-page` 与旧 home.css L2 撞名层叠，残留 height:100vh） |
| 2 | `scripts` | demo2/pages/ScriptSelectPage.tsx | 剧本选择（一般/剧本杀分类页签；预设+localStorage+后端场景三源合并，卡片删除） | 新大厅风（demo2） |
| 3 | `roles` | demo2/pages/RoleSelectPage.tsx（27.8KB，最大页） | 角色选择（murder/general/werewolf 三变体；角色卡增删/编辑/置顶；2D 设置） | 新大厅风（demo2，27.8KB） |
| 4 | `gen` | demo2/pages/ScriptGenPage.tsx | 剧本生成（AI 生成/导入解析/同步角色） | 新大厅风（demo2） |
| 5 | `settings` | demo2/pages/SettingsPage.tsx | 设置（LLM/语音/地图生成/素材/偏好） | 新大厅风（demo2） |
| 6 | `roles-lib` / `free-chars` | demo2/pages/RoleLibPage.tsx | 角色库（角色卡统一管理，剧本杀/一般互通） | 新大厅风（demo2） |
| 7 | `role-detail` | demo2/pages/RoleDetailPage.tsx | 角色卡详情 | 新大厅风（demo2） |
| 8 | `game` | demo2/pages/GameBridge.tsx → ChatPage | 真实对局启动器，按 gameMode/runMode 分流 4 模式 | 混合（见下） |
| 8a | 剧本杀 `script` | ChatPage（`protoV2` 分支） | 剧本杀对局（三栏 + 阶段驱动 + 阶段色，ui-proto-v2） | **新风（proto-v2，P-0816-H/M/I/R/T）** |
| 8b | 狼人杀 `werewolf` | ChatPage 旧布局 + werewolf/Werewolf*Panel | 狼人杀对局（夜间/讨论/投票/开枪/审批） | 旧风（global.css 旧段，`.workspace.werewolf-mode` L383 三列 grid） |
| 8c | 一般模式 chat | gal/GalGeneralView.tsx（呈现接管，默认） | 一般模式对话（VN 视觉小说式，P-0810-08 拍板；经典 ChatPage 视图保留右上角回退） | Gal 像素风（gal.css/galGeneral.css） |
| 8d | 一般模式 explore / 经典 | phaser/PhaserSimulationView.tsx / ChatPage | 2D 探索（Phaser canvas）/ 经典聊天视图 | 2D canvas 原生渲染 + 旧风外围容器 |
| 9 | `gal` | gal/GalDemoPage.tsx | Gal demo（演示数据 + 真实对局 SSE 桥 + Pony 立绘） | Gal 像素风（gal.css） |

> 补充：对局内（8a-8d）共用 `ChatMessageFlow.tsx`（`protoMain = mode==='script' && UI_PROTO_V2_ENABLED && scriptState`，L44 起主区阶段驱动切换 setup/investigation/discussion/vote/reveal·ended，L231-299）。狼人杀与一般模式经典视图不走 protoMain。

### 1.2 样式体系盘点（12 个 CSS + 2 个未引用）

| 文件 | 规模 | 职责 | 设计变量 | 新旧占比 | 硬编码色 |
|---|---|---|---|---|---|
| styles/global.css | 101.4KB / 3931 行 | **对局主样式**（ChatPage 全家：顶栏/面板/消息流/狼人杀/剧本杀旧交互 + proto 新段） | :root 21 个变量（旧体系，L1-21）| 旧段 L1-1568 ≈40% ＋ **proto 新段 L1569-3931 ≈60%**（P-0816-H 起） | ≈346 处 hex |
| demo2/styles.css | 24.2KB / 505 行 | 大厅 6 页 + App2 壳（星尘背景/卡片/btn2 体系） | :root 18 个变量（**独立体系**：`--bg0/--bg1/--bg2/--gold/--cyan/--violet/--radius:14px`，与 global 不互通） | 全新（P2-0805 定案） | ≈26 处 hex |
| gal/gal.css | 23.8KB / 885 行 | Gal 像素/视觉小说风（舞台/对话框/立绘/选择条） | `--gal-*` 变量体系（L8-18，独立） | 独立像素风 | ≈54 处 hex |
| gal/galGeneral.css | 36KB / 1293 行 | 一般模式 VN 视图（GalGeneralView/GalGeneralStage/聊天面板） | 同 gal.css `--gal-*` 体系 | 独立像素风 | ≈39 处 hex |
| styles/home.css | 7.3KB / 409 行 | 旧首页样式 | 无变量 | **死代码 40/41 类**（仅 `.home-page` 被 HomePage.tsx 复用，且与 demo2 撞名层叠） | 33 处 |
| styles/login.css | 2.1KB / 116 行 | 旧登录页 | 无 | **全死**（LoginPage 已删，main.tsx 仍导入） | 9 处 |
| styles/material.css | 2KB / 111 行 | 旧素材库页 | 无 | **全死**（MaterialPage 已删，main.tsx 仍导入） | 6 处 |
| styles/voice.css | 0.7KB / 34 行 | `.mic-btn`（ChatComposer.tsx:100 使用） | 无 | 存活 1 类 | 1 处 |
| index.css | 2.1KB / 112 行 | Vite 模板残留（浅色紫主题） | :root 2 块 | **未被任何文件 import** | 12 处 |
| App.css | 2.8KB / 184 行 | Vite 模板残留 | 无 | **未被任何文件 import** | 0 |

> 组件内联样式：硬编码色主要散落在 Phaser 视图（PhaserScriptMapView.tsx 18 行、PhaserSimulationView.tsx 13 行）、TraceDrawer.tsx 7 行、ScriptStatePanel.tsx 5 行、ChatDrawers.tsx 4 行等。Phaser canvas 内渲染色（Scene 内 Graphics/Text）不属 web 换肤范围。

### 1.3 风格差异实证（8 处，≥5 达标）

1. **主色 token 四套并行、互不相通**：global.css L10 `--accent:#49c16d`（绿）｜demo2/styles.css L17-20 `--gold:#e8c15a` / `--violet:#8b7bff`｜gal/gal.css L13 `--gal-accent:#ff6ad5`（粉）｜proto 段 global.css L2653-2681 `--proto-phase` 四阶段色（#7c4dff/#0ea5e9/#f59e0b/#a855f7）｜index.css L8 `--accent:#aa3bff`（未用）。同一语义「主色」五套取值，组件各认各的。
2. **圆角值不统一**：global.css L16 `--radius:8px`（L41 `.btn` 7px）｜demo2 L24 `--radius:14px`｜proto `.proto-vn-dialog` 14px（L2129）、`.proto-vote-panel` `var(--radius,10px)` 回退 10px（L1814）｜徽章 pill 999px。
3. **字体体系分裂**：global.css L17 `Segoe UI/Noto Sans SC`｜demo2 L34 `PingFang SC`｜gal L18 `'Courier New', 'NSimSun', 'SimSun', monospace`（等宽像素风）。
4. **背景层次模型不同**：global.css L2 平色 `#0f1216` + 1px border 分隔｜demo2 `.app2-bg`（L42-52）三层径向渐变 + 星尘动画｜原型/proto 用 glow 光晕体系（`box-shadow:0 0 12px var(--phase-glow)`）。
5. **新段不消费旧 token，fallback 自成一套**：proto 段 14 处 `var(--bg-2, fallback)`、15 处 `var(--bg-3, fallback)` **全部未在 :root 定义** → 恒走 fallback（`#1e293b/#262c44/#334155/#c3c9d8` 等，与旧 :root 定义值不同）；旧四变量 `--panel-2/--panel-3/--accent/--accent-2` 34 处使用**全部在旧段（<L1569）**。两套体系完全解耦。
6. **硬编码色散落且不属任何 token**：global.css 约 346 处 hex；组件内联 style 见 1.2；代表性：global.css L110 `.archive-tab.active { border-bottom-color:#667eea }`、L128 `.presence-chip.active { border-color:#667eea }` —— 硬编码靛蓝非任何变量。
7. **阴影/动效梯度分明**：旧风 `.btn` 仅 `transition:.15s` 微动效；新风 proto 徽章渐变底 + glow 阴影（L2721-2723）、VN 弹层 fade-in（L2119 `protoMaskIn`）、投票页全屏红光+震屏「拍案！」、打字机光标（L1202 `.typewriter-caret #7dd3fc`）。
8. **死样式残留 + 类名层叠冲突**：home.css 40/41 类死代码仍经 main.tsx 全局加载；`.home-page` 双定义（home.css L2 `display:flex;height:100vh;background:#0f0f1a` vs demo2 L161 `text-align:center`）——demo2 后加载覆盖展示属性，但旧 `height:100vh/flex` 仍生效，属真实混合层叠残留。

---

## 2. 替换目标（与剧本杀原型页一致的视觉语言）

以 `docs/ui-prototype/investigation.html / discussion.html / vote.html`（P-0816-E/M 产物）为视觉源，tokens 规范：

| 维度 | 目标值（原型 :root 实证） | 现旧值（global.css） | 差异 |
|---|---|---|---|
| 背景/面板 | `--bg:#0c1322 / --panel:#141e33 / --panel-2:#1b2944` | `#0f1216 / #171b21 / #1f252d` | 蓝黑深色系 vs 灰黑 |
| 文字 | `--text:#e8eef9 / --dim:#93a1bd / --dim2:#5b6b8c` | `#eef2f6 / #aab4c0 / #747f8c` | 微调 |
| 阶段色 | `--phase/--phase-strong/--phase-soft/--phase-glow`：青蓝 #0ea5e9（搜证）/ 暖橙 #f59e0b（讨论）/ 红紫 #dc2626→#9333ea（投票）/ 默认紫 #7c4dff | 单一绿 accent #49c16d | **阶段驱动色板**是核心理念 |
| 圆角 | 卡片 10-14px、徽章 pill 999px、按钮 8-10px | `--radius:8px`、btn 7px | 统一上浮 |
| 阴影 | glow 光晕（0 0 12-22px phase-glow）+ 深投影 | 1px border 为主 | 增加光晕层 |
| 动效 | 打字机 / VN 淡入 / 拍案震屏 / hover 抬升 | transition .15s | 演出化 |
| 布局理念 | 三栏可折叠（左 240⇄56 rail / 右 280⇄FAB 抽屉）、阶段驱动主区、功能收纳（⚙️ 菜单化）、目标 HUD、右栏四 Tab | 固定 grid 三列、按钮堆顶栏 | 见阶段 D |

---

## 3. 分阶段替换方案

### 阶段 A：design tokens 全局化（地基）

- **范围**：global.css `:root` 重构为三层 token（①基础色板层 ②语义层 `--color-bg/--color-panel/--color-accent/--radius/--shadow/--font` ③阶段色层 `--phase*` 四阶段），demo2 与 gal 以「别名映射」收敛到同一套（`--gold → 语义 accent`、`--gal-accent → 语义 accent`，避免一次性大改组件）；删除/归档 index.css、App.css；清理 home/login/material.css 死类（**删除需主人确认**，AGENTS.md 纪律）。
- **涉及文件**：styles/global.css、demo2/styles.css、gal/gal.css、gal/galGeneral.css、main.tsx（去掉死 import）、index.css/App.css（删或归档）。
- **工作量粗估**：1.5-2.5 人日。
- **风险**：中。global.css 被 ChatPage 全量消费，token 别名改值即全站变色，需全模式视觉回归。
- **验证方式**：npm build + 四模式（剧本杀 proto/狼人杀/一般 gal/2D 外围）截图对比 + CDP 真机走查 + 死样式 grep 0 命中。

### 阶段 B：大厅流 6 页换肤

- **范围**：home/scripts/roles/roles-lib/gen/settings 6 页 + App2 壳（顶栏/背景），把 demo2/styles.css 从「独立 :root」改为「消费全局 tokens」（删 demo2 :root，改引用语义变量，色值微调对齐新深色蓝黑 + 阶段色点缀）。
- **涉及文件**：demo2/styles.css + 6 页 tsx 类名/内联色微调（RoleSelectPage 42 行内联 style 需抽查收敛）。
- **工作量粗估**：1-2 人日。
- **风险**：低。页面独立、无对局联动；唯一注意 `.home-page` 撞名清理。
- **验证方式**：逐页 CDP 截图对比 + 交互冒烟（选剧本→选角色→进对局链路完整）。

### 阶段 C：玩法页对齐（布局交互不动，只视觉 tokens + 组件风格）

- **范围**：
  - 一般模式 GalGeneralView/经典 ChatPage 视图 → 消费全局 tokens（gal 像素字体是否保留见 [需确认]）；
  - 狼人杀 ChatPage 旧布局 → tokens + 组件风格（面板/按钮/徽章换新皮肤），昼夜/投票阶段可映射阶段色；
  - Gal 对局页（gal.css/galGeneral.css）→ tokens 收敛；
  - **2D Phaser canvas 不套 web 风**：Scene 内渲染（瓦片/气泡/角色）保持引擎原生，只统一外围容器/顶栏/操作条。
- **涉及文件**：global.css 旧段重构（最大头）、gal/gal.css、gal/galGeneral.css、ChatPage/ChatLeftPanel/ChatRightPanel/ChatMessageFlow/Werewolf*Panel、Phaser 视图外围壳。
- **工作量粗估**：2-3 人日。
- **风险**：高。对局页是主战场、回归面最大（SSE 流/打字机/投票/2D 联动均不可破坏）。
- **验证方式**：全模式真机对局冒烟（沿用 tools/smoke_*.mjs 模式）+ 截图对比 + 3s 轮询/SSE 链路零回归。

### 阶段 D：共享组件库沉淀 + 理念推广

- **范围**：
  1. **组件抽取**：GoalBadge（目标 HUD）、useCollapsibleSidebars + collapseState（折叠侧边栏）、阶段徽章（.proto-badge-* + scriptPhaseThemeClass）、检索框/chips（.proto-hs-* + actionUtils.filterEvidence）、VN 面板（ScriptVnReveal/.proto-vn-*）、ScriptProtoTopbar 收纳模式 → 抽到共享层（如 `components/ui/`）供 2+ 场景消费；
  2. **理念推广**：功能收纳（顶栏按钮 → ⚙️ 菜单，狼人杀/一般模式顶栏同款）、目标 HUD（GoalBadge 规则模板扩展到狼人杀/一般模式 [需确认数据源]）、三栏可折叠布局、右栏四 Tab。
- **涉及文件**：新增共享组件目录 + 各模式消费点。
- **工作量粗估**：1-2 人日（抽组件），推广另计。
- **风险**：低-中。抽组件不改视觉，只搬代码；推广按需。
- **验证方式**：复用组件在 ≥2 场景消费 + Node 纯函数冒烟（voteUtils/actionUtils 已有先例）。

---

## 4. 风险与依赖

1. **并行批次冲突（最高优先）**：global.css 是 P-0816-* 高频改动文件（P-0816-R 刚登记 `styles/global.css` 占用、P-0816-T 进行中）。阶段 A 动 `:root` 前必须先读 `docs/并行作业登记.md`，与在改批次协调（本报告为只读调研，未触碰）。
2. **回归面**：global.css 3931 行被对局页全量消费；token 化 + 旧段重构后必须做 4 模式全量回归（含 SSE 流式打字机、投票审批门、2D 联动）。
3. **uiProtoV2 开关策略**：现有开关语义 =「剧本杀 + 三栏 proto 布局」（`UI_PROTO_V2_ENABLED && mode==='script'`，ChatPage L63）。换肤是**视觉层**改动，建议：布局开关语义不动，另设全局视觉开关（如 `UI_THEME_V2` 或复用 UI_PROTO_V2_ENABLED 做全局皮肤）——**具体口径 [需确认]**；同时注意决策记录 U12「新 UI 不覆盖一般模式/狼人杀」——本任务即 U12 所述「另起方案」的落地，建议在 `docs/ui-prototype/决策记录.md` 补记一条范围扩展决策。
4. **死样式删除**：home/login/material.css 与 index.css/App.css 的删除需主人确认（AGENTS.md「删除文件需主人确认」）。
5. **内联 style 硬编码**：Phaser 视图与部分面板内联色需逐处收敛；canvas 内色值不动（2D 渲染不套 web 风）。
6. **Gal 像素风定位**：gal.css 等宽字体/霓虹粉是 `docs/gal-界面设计.md`（P-0810-02）明确的「像素主题规范」——替换时保留为 Gal 专属变体还是并入统一语言，**需主人拍板 [需确认]**。

---

## 5. 建议执行顺序（结合剧本杀局内视觉对齐包 P-0816-U）

```
① 阶段 A（tokens 全局化）      ← 与 P-0816-U（局内视觉对齐包）共享地基，先行
② P-0816-U（剧本杀局内视觉对齐包，收尾）
③ 阶段 B（大厅流 6 页换肤）     ← 独立可并行
④ 阶段 C（玩法页对齐）          ← 依赖 A + U；狼人杀/一般模式/Gal 对局页
⑤ 阶段 D（共享组件库 + 理念推广）← 可与 C 并行后置，沉淀复用
```

理由：A 是唯一地基（所有页面最终消费同一套 tokens）；U 包先收尾剧本杀局内（当前主战场、避免与 A 双改同文件冲突）；B 独立可插空；C 依赖 A；D 是沉淀层，越晚做越能覆盖真实复用面。

---

## 附：关键文件索引（调研取证）

| 文件 | 要点 |
|---|---|
| frontend/src/uiProtoV2.ts | ui-proto-v2 特性开关（全局常量 + VITE_UI_PROTO_V2 env） |
| frontend/src/demo2/App2.tsx | 路由壳/NAV 8 项 + 10 视图分发 |
| frontend/src/components/ChatPage/ChatPage.tsx | protoV2 判定（L63）/ 三栏装配（L228-268）/ 旧布局并存 |
| frontend/src/components/ChatPage/ChatMessageFlow.tsx | protoMain 阶段驱动主区（L44/L231-299） |
| frontend/src/styles/global.css | L1-21 旧 :root tokens / L1569+ proto 段 / L2653-2681 阶段色 / L2115+ VN / L2230+ 检索 chips |
| frontend/src/demo2/styles.css | 大厅独立 :root（L8-25） |
| frontend/src/gal/gal.css + galGeneral.css | Gal 像素风 --gal-* 体系 |
| docs/ui-prototype/决策记录.md | U1-U14 决策（U11 渐进嵌入/U12 不覆盖一般·狼人杀/U13 VN 前端拼装） |
| docs/ui-prototype/investigation.html / discussion.html / vote.html | 新视觉语言源头（:root 阶段色/glow/圆角） |

> 不确定处已标 [需确认]；本报告基于代码事实，未修改任何生产文件。
