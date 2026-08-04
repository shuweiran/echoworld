# PROJECT_CONTEXT.md — Roleplay-Java 项目速览（AI 必读①）

> ⚠️ **所有 agent 开工前必读**：① 本文件（5 秒速览）→ ② `DECISION_LOG.md`（为什么这么设计）→ ③ 按任务需要读 `TEST_STATUS.md` / `docs/问题清单-20260731.md`。

## 一句话目标
开发一个 **Java 多 Agent 角色扮演引擎**：2D 空间 × 铁轨系统（Track System）融合的实时社会模拟，支持狼人杀/剧本杀双游戏。

## 当前阶段
- ✅ Phase 1-4 完成（Track 融合全链路：SpatialTrackResolver → TrackStrategy → 双导演 → MovementConstraint）
- ✅ **剧本杀 6/6 Step 完成**（秘密机制/前端主链路/判定加固/审批门已落地并提交；**Step 3v 讨论接对话引擎已落地（GAP-3，2026-08-01 批次 A）**；**Step 4v ENDED 终态 + saveScript 落库已落地（GAP-4b/4c，批次 B）**；**剧本 schema v1 版本化 + 双生成器统一已落地（批次 C1，2026-08-01，见 D-014 / docs/剧本-schema-v1.md）**；**DM 主持人面板 + 重连 UI 已落地（批次 C4，2026-08-01，见 D-018）**；蓝图 `docs/剧本杀差距分析-待办.md` v3）
- 🟠 问题清单 P0 缺陷并行修复中（另一个主会话「全功能覆盖测试方案」在改，**派单前确认不撞车**）
- ✅ **Phaser 3.90 2D 渲染层迁移（D-020，三阶段全闭环完成 2026-08-01，已通过未衡终审）**：接入 Phaser 3.90（锁 v3 稳定线）作为 React 内的 2D 渲染层，渐进式替换自研 Canvas 渲染——阶段 0：验证 demo 完成（瓦片渲染+碰撞 / BSP 分区 / Zone 热点 / Aseprite 动画 / 地图 JSON 契约 v1 定稿，台账 #49）；阶段 1：ScenePage 渲染层换 Phaser，数据流不变（台账 #55）；阶段 2：LLM 生成地图接入（POST /api/script/map：LLM 生成 → 契约 v1 → MapValidator 7 项校验 → 失败降级 BSP；搜证线索绑定热点 zones.clue_location ↔ clues.location）+ 前端 ScenePage 剧本杀 Tab「生成地图」入口（mapData.ts/ScriptMapScene.ts/PhaserScriptMapView.tsx，构建 index-Ccc-CMzG.js 已同步 static）；**8000 实例已重启生效（PID 25760）**；终审遗留 P2 两项见「未完成」——详见 `docs/Phaser迁移计划.md`
- 🟢 **演讲广播合并方案正式版 merged（2026-08-01，台账 #46 / D-021）**：`roleplay.broadcast.speech-mode` 默认 merged——方案A 管线架构 + HearingSystem 声学判定（`HearingSystem.countHearingListeners` 单事实源，判定集中回管线层）+ 可配置兜底 `fallback-to-global`（默认 true=无听众升级全局公告 / false=仅区域演讲）+ 剧本杀阶段 SYSTEM 广播无条件进正式版（总开关 `script-phase-broadcast`）；auto/split 保留回退对比；全量 190 tests / 0 failures；**真机验证 7 项 PASS（台账 #47，2026-08-01 13:05：8000 实例重启加载 merged 正式版 + 模式默认 merged / 玩家广播入队 / AI 演讲 fallback 升级全局公告 / 断线补发 recent 环形缓冲 / merged↔split 往返切换 / 剧本杀阶段 SYSTEM 公告 / SSE announcement+script_* 并存）**

## 技术栈
| 层 | 技术 |
|---|---|
| Backend | Java 21 + Spring Boot 3.4 + Maven + Spring Data JPA |
| DB | H2（生产 file `./data/roleplay`；测试 mem） |
| AI | DeepSeek API（OpenAI 兼容），mock LLM 用于测试 |
| Frontend | React 18 + Vite + Zustand（`roleplay-v4/frontend`，构建后同步 `static/`） |
| 并发 | Java 21 Virtual Threads |

## 核心架构（数据流）
```
每 tick → SimulationOrchestrator
  → WorldDirectorService（想做什么：目标）
  → InteractionDetector（TrackScore：谁知道什么，阈值 40）
  → TrackDirectorService（轨道分配：MERGED/WEAK/ISOLATED）
  → MovementConstraint（怎么移动：聚集/听觉带/避让）
  → ConversationGroup → TrackStrategy（隔离上下文）→ LLM → SSE
```

## 核心模块
- `simulation/track/`：TrackAssignment / SpatialTrackResolver / InteractionDetector / EavesdropSummarizer
- `simulation/director/`：WorldDirectorService（规则目标）/ TrackDirectorService（轨道分配）
- `simulation/conversation/`：ConversationManager / TrackStrategy（统一 GROUP_DISCUSSION+DEBATE）
- `simulation/movement/`：MovementConstraint（Phase 4）
- `service/`：RouterService（身份锁定）/ ArbiterService（审批脱敏）/ ScriptGameService（剧本杀）/ WerewolfService（狼人杀）
- `controller/`：SimulationController（26 端点）/ SessionController / ScriptController 等 17 个

## 已完成
- [x] Phase 1：Track 最小闭环（距离→MERGED/WEAK/ISOLATED）+ Arbiter 输入脱敏（sanitizeSummaryForArbiter）
- [x] Phase 2：TrackStrategy 统一替换（混合轨道上下文隔离）
- [x] Phase 3：双导演（WorldDirector 规则式 + TrackDirector）+ SimulationOrchestrator
- [x] Phase 4：MovementConstraint 社交移动 + secretAgents REST + track/state
- [x] 测试基建：application-test.yml（RANDOM_PORT + H2 mem + mock LLM）
- [x] **254 tests 全绿**（含 LONG-01 超长文本 10 万字，需求硬性要求；2026-08-01 Phaser 阶段2 全量基线，台账 #56 / TEST_STATUS v14：217 基线 + 地图 4 类 37 用例）
- [x] **剧本杀 Step 3v（GAP-3）**：DISCUSSION 接对话引擎（ConversationManager + TrackStrategy，持秘密角色 WEAK 摘要隐藏秘密 / 未持 MERGED；目标注入隐藏秘密·查明真相；讨论结束自动进 VOTE；轮数可配置默认 2）——见 DECISION_LOG D-012
- [x] **剧本杀 Step 4v（GAP-4b/4c，批次 B）**：ENDED 终态触达（resolveVote 停 REVEAL → confirmEnded / POST /api/script/finish 确认进 ENDED，幂等终态不越界）+ saveScript 落库双点（initGame 落剧本 type=script / confirmEnded 落对局结果 type=result 含玩家·凶手·票型·真相·讨论摘要）——见 DECISION_LOG D-013
- [x] **script SSE 推送 + App.tsx script_* 分支（GAP-8，批次 B）**：SSEController 新增 script_phase/script_status/script_reveal 事件，状态变更点全推；前端 useSSE 注册 + App.tsx 三分支 + ScriptStatePanel 改 store 驱动（SSE 优先 + 3s 轮询兜底），揭晓区「结束对局」按钮进 ENDED
- [x] **方案B 分步落地 demo（2026-08-01）**：演讲接广播走内联路径（SpeechStrategy.processResults 直接 enqueue 区域广播，带 speaker 坐标+半径，远近判定复用 HearingSystem）+ 剧本杀阶段 SYSTEM 广播（五处阶段切换发 announcement 全局横幅，与 script_phase 会话面板通道并存）+ 共存开关 roleplay.broadcast.speech-mode（auto=方案A 回调路径默认 / split=方案B 内联路径，同一实例可经 POST /api/announcements/mode 或 ScenePage 面板运行时切换，两路径互斥不重复推送）；方案A demo 零改动保留；对比报告 research/speech-broadcast-方案对比.md——见 DECISION_LOG D-019
- [x] **演讲+广播合并地基 demo（2026-08-01）**：统一消息管线——`broadcast` 包（BroadcastMessage / AnnouncementService：PriorityQueue SYSTEM>EVENT>PLAYER>NPC + 滑动窗口节流 + 同 key 合并 + 队列上限 + 断线补发环形缓冲）+ 复用 WorldEventBus（TYPE_ANNOUNCEMENT）+ SSEController.broadcast("announcement")；演讲=带半径的 area 广播、公告=global，AI 与玩家共用；AI 演讲产出（PUBLIC_SPEAKING）自动判定形态（ModeClassifier.wouldOthersListen：有听众→演讲/无听众→全局公告），玩家 POST /api/announcements 发公告，AI 演示 POST /api/simulation/speech；前端横幅（打字机）+ 公告栏 + ScenePage demo 触发面板——见 DECISION_LOG D-015
- [x] **演讲广播合并方案正式版 merged（2026-08-01，台账 #46 / D-021）**：`roleplay.broadcast.speech-mode` 新增 merged 并设为默认（auto=方案A 旧行为 / split=方案B 旧行为保留回退对比）——保留方案A 管线架构（ConversationManager 回调 → SimulationService → enqueueAutoSpeech，SpeechStrategy 不加内联广播），听众判定从 wouldOthersListen 硬编码升级为 **HearingSystem 声学判定**（新增 `HearingSystem.countHearingListeners`：computeAudibility/canHear 距离衰减，判定集中回管线层单事实源，split 内联判定委托同一声学方法防双份漂移）；「无听众→全局公告」配置化 `fallback-to-global`（默认 true / false=仅区域演讲）；**剧本杀阶段 SYSTEM 广播转正式**（五处阶段切换无条件启用，总开关 `script-phase-broadcast`）；前端 ScenePage demo 面板「⭐ 正式版（merged）」chip——见 DECISION_LOG D-021；**真机验证 7 项 PASS（台账 #47，2026-08-01 13:05）**
- [x] **剧本杀断线重连与会话恢复（批次 C3，2026-08-01）**：roleKey 令牌（每玩家 UUID，player_key 认证替代“玩家名可被冒充”，无 key 向后兼容）+ 对局快照（ScriptEntity type=snapshot 行，状态变更点全量落库）+ POST /api/script/resume（game_id/room_code/player_key 定位，内存命中直接返回/快照重建/ENDED 终态）+ init 可选 room_code 绑定 + GET /api/script/keys（DM 分发）——见 DECISION_LOG D-017
- [x] **DM（主持人）面板 + 重连 UI（批次 C4，2026-08-01）**：GET /api/script/dm/status（DM 全量仪表盘：所有玩家角色/秘密/AP/线索数/投票/roleKey + 真相/killer_id/判定/审批状态）+ POST /api/script/advance（状态机推进 INVESTIGATION→DISCUSSION→VOTE→REVEAL→ENDED，VOTE 步经 D7 审批门），越权保护 roleplay.game.dm.key（X-DM-Key 头，空=放开与审批门同模式）；前端 ChatPage「🎛 主持人」抽屉（玩家表/秘密/roleKey 复制分发/推进按钮/批准驳回/终态）+「🔄 恢复对局（重连）」入口（对局ID/房间码 + roleKey → resume → 恢复玩家视图，ENDED 终态结果卡）——见 DECISION_LOG D-018
- [x] **剧本 Schema v1 + 双生成器统一（批次 C1，2026-08-01）**：新建 ScriptSchemaV1（schema_version=1 内嵌 JSON：metadata{title,player_min,player_max,tags} / roles[]{id,name,intro,is_hidden,secret} / clues[]{id,title,location,content,transferable,visible_to_owner_only} / killer_id / truth / background / locations / secrets），宽容解析旧格式归一 v1；ScriptService.generateScript 为唯一生成路径（统一 prompt+normalize+defaultScript 兜底），ScriptGameService.initGame 委托之（双生成器输出同 schema）；secrets 纳入 schema（raw.secrets ∪ roles[].secret，仅全无秘密时全量兑底）；落库 contentJson 按 v1 存取（ScriptEntity 不加列）；D6 判定路径零改动（killer_id 为元数据）——见 DECISION_LOG D-014，契约文档 docs/剧本-schema-v1.md
- [x] **Phaser 迁移阶段 2（LLM 生成地图接入，2026-08-01，三阶段全闭环）**：后端 POST /api/script/map（session_id/theme/seed/regenerate → LLM 生成 → 契约 v1 宽容解析 → Java 校验器 MapValidator 7 项检查 → 失败降级 BSP）+ `simulation/map/` 包 3 类（MapContract/MapValidator/BspMapGenerator）+ `service/ScriptMapService.java`（缓存命中/regenerate 强制重生成 + map_data 随对局快照落库）；测试 4 类 37 用例，全量 mvn **254/0**；前端 `phaser/` 地图三件（mapData.ts/ScriptMapScene.ts/PhaserScriptMapView.tsx）+ ScenePage 剧本杀 Tab「生成地图」入口 + 搜证联动（zones.clue_location ↔ clues.location，搜证成功热点变绿 markZoneSearched）；npm build 63 modules → index-Ccc-CMzG.js 已同步 static，**8000 重启生效（PID 25760）**；已通过未衡终审，遗留 P2 两项见「未完成」——见 DECISION_LOG D-020 / docs/Phaser迁移计划.md

## 未完成（按优先级）
- [x] ~~演讲/广播断线补发前端接线（GET /api/announcements/recent 已就绪，useSSE 重连后自动补拉未接，P3）~~（已解决 2026-08-04，P-0804-A：useSSE 重连成功后续拉 announcementRecent(since) 重放）
- [x] ~~**Phaser 阶段2 终审遗留 P2（非阻塞）①**：BSP 降级 seed 硬编码 DEFAULT_BSP_SEED=20260801~~（已解决 2026-08-04，P-0804-A：ScriptMapService @Value 注入 roleplay.game.map.bsp-seed，yml 双份）
- [ ] **Phaser 阶段2 终审遗留 P2（非阻塞）②**：地图数据不在对局 `toMap` 的 `your_secret` 同级暴露（地图经 POST /api/script/map 生成响应 + 快照 map_data 获取，前端已消费；实现选择，非缺陷，如需随对局状态下发可后续补充）
- [x] ~~演讲 demo 需服务端重启生效~~（已解决 2026-08-01，台账 #47：mvn package 重新打包 + 8000 实例重启（java -jar，pid 22664）加载 merged 正式版，static 产物随 jar 生效）
- [x] ~~D1 中断系统包（InterruptManager/AgentTaskManager 缺失）~~（已实现 2026-07-31，台账 #18：interrupt 包 11 文件——InterruptManager 335 行 / AgentTaskManager / CancellationToken / AgentTask / AgentTaskStatus / WorldEventBus / GameEvent / TrackChangeEvent / StopType / TaskType / TaskCancelledException + 全链路接线 AgentExecutor/Agent/LLMClient/RouterService/SessionController/ConversationManager/SimulationService/SimulationOrchestrator，29 项逻辑自测 PASS；D22 FAILED 终态补充见台账 #23）
- [x] ~~G1 根治~~（已解除 2026-07-31 21:35，见台账 #28）：AppConfig `@ConfigurationProperties` 绑定后启动自动读环境变量 `ROLEPLAY_LLM_API_KEY`，`configured=True`，无需运行时注入
- [x] ~~剧本杀重连 UI（POST /api/script/resume + roleKey 分发）前端接线~~（已完成 2026-08-01 批次 C4：ChatPage「🔄 恢复对局」入口 + 🎛 主持人面板 roleKey 复制分发）

## 当前最大问题
1. **G1 LLM 401 — ✅ 已解除（2026-07-31 21:35，台账 #28/#32）**：根因 D25（AppConfig 无配置绑定注解，yml 环境变量占位符从不生效）已修复——AppConfig 加 `@ConfigurationProperties(prefix="roleplay")` 绑定 + 补全 setter 后，启动自动读环境变量 `ROLEPLAY_LLM_API_KEY`，`configured=True`，不再需要运行时注入；131/0 测试全绿 + 启动 configured=True + 真实对话 14.3s 真机验证 PASS。依赖真实 LLM 的用例现可运行（问题清单 G1 已标 ✅ 已解除）
2. **并行工作流**：另一主会话在改同一批文件（ScriptGameService 已改 D5 secrets）→ **任何派单前先 git diff 确认基线**
3. **剧本杀约 95% 完成**：前端已实装——ScenePage 剧本杀 Tab + 状态面板、script API 全部封装、14 项玩家功能完整可玩（AI 开局/搜证/讨论/投票/揭晓/2D 模拟，3 秒轮询刷新）；后端已闭环——ENDED 终态 + saveScript 双点落库（批次B）、script SSE 推送（批次B）、剧本 Schema v1 + 双生成器统一（批次C1）、AP 行动点 + 线索转交（批次C2）、断线重连与会话恢复 roleKey+快照（批次C3）、**DM 主持人面板 + 重连 UI（批次C4）**；剩余缺口：无退出/重开对局、私聊后端有前端无、ScenePage.tsx.bak 残留、联机房（/api/rooms/*）仅狼人杀 Tab 接入剧本杀未接（room_code 绑定入口已备，前端接续）、剧本杀历史体系未接 RouterService（P2）

## 关键文件索引
| 文件 | 内容 |
|---|---|
| `DECISION_LOG.md` | **架构决策史**（为什么这么设计，AI 必读②） |
| `TEST_STATUS.md` | **测试状态台账**（每次测试后更新） |
| `docs/问题清单-20260731.md` | 全量缺陷 A-G + 问题→文档对照表 H |
| `docs/剧本杀差距分析-待办.md` | 剧本杀 P0 开发蓝图 v3 |
| `docs/剧本-schema-v1.md` | **剧本数据模型 Schema v1 契约**（字段表+示例+兼容规则，批次 C1，见 D-014） |
| `docs/剧本杀调研报告-raw.md` | 剧本杀源码级调研（行号取证） |
| `docs/测试方案-全功能覆盖-v2.md` | 55 项覆盖矩阵 + 110 端点总账 |
| `需求文档-完整需求.md` | 原始需求（Track 融合/中断系统/测试要求） |
| `docs/修改记录.md` | 修改台账（谁改了什么，核查状态） |
| `src/main/java/com/roleplay/engine/broadcast/` | **演讲+广播合并地基**（BroadcastMessage / AnnouncementService / AnnouncementController / SseBroadcaster，见 DECISION_LOG D-015） |
| `docs/Phaser迁移计划.md` | **Phaser 3.90 渐进式迁移计划**（阶段 0/1/2 全部完成，三阶段全闭环已通过未衡终审 2026-08-01；资产复用/作废清单 + 风险回滚，见 DECISION_LOG D-020） |
| `src/main/java/com/roleplay/engine/simulation/map/` + `service/ScriptMapService.java` | **Phaser 阶段2 地图生成**（MapContract / MapValidator / BspMapGenerator / ScriptMapService：LLM 生成 → 契约 v1 校验 7 项 → 失败降级 BSP，POST /api/script/map，2026-08-01） |
| `roleplay-v4/frontend/src/phaser/`（mapData.ts / ScriptMapScene.ts / PhaserScriptMapView.tsx） | **Phaser 阶段2 前端地图渲染**（瓦片+碰撞+热点+出生点 WASD 漫游 + 热点搜证联动 markZoneSearched，2026-08-01） |

## 硬性约束
- 8000 端口有运行中后端：**只准 `mvn compile/test`，禁止 `spring-boot:run`**（测试用 RANDOM_PORT 隔离）
- 系统 mvn：`C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd`
- 不要 git commit（等主人确认）
- 修改代码后**必须登记** `docs/修改记录.md`；测试通过后**必须更新** `TEST_STATUS.md`
- PowerShell 发中文 JSON 会 GBK 乱码 → 用 Python（UTF-8）
- 中文交流
