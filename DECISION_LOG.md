# DECISION_LOG.md — 架构决策史（AI 必读②）

> ⚠️ **所有 agent 开工前必读**：这是"为什么这么设计"的答案。改代码前先查这里，避免推翻历史决策或重复踩坑。
> 格式：日期｜决策｜原因｜放弃了什么｜影响。

---

## 2026-07-31（Phase 1-4 Track 融合）

### D-001 Arbiter 输入脱敏是生死线，不是可选
- **决策**：RouterService 加 `sanitizeSummaryForArbiter()`，Arbiter 永远看不到全轨道全文，只看脱敏摘要
- **原因**：调研发现 Arbiter 若看到 Track 全文即泄露信息源——"谁说了什么"从摘要就能推断，信息隔离失效
- **放弃**：让 Arbiter 看完整轨道上下文的便利性
- **影响**：信息隔离的根基；后续任何接入 Arbiter 的路径（剧本杀审批/狼人杀判定）都必须过脱敏

### D-002 Phase 3 WorldDirector 纯规则，零 LLM 调用
- **决策**：WorldDirectorService 目标生成用规则（情绪异常→平静 / 对话→参与 / 闲置→探索），不用 LLM
- **原因**：成本控制。每组每 tick 调 LLM 的成本估算：组×10 轮×角色数，纯规则省掉大头
- **放弃**：LLM 驱动的更"聪明"目标生成（保留 `generateGoalWithLLM` 作为手动扩展点，失败回退规则）
- **影响**：2D 模拟运行成本可控；目标语义较粗糙（P1 可升级）

### D-003 Phase 3 只加外层 SimulationOrchestrator，不重构 RouterService
- **决策**：新编排层 `SimulationOrchestrator.tick()` 包裹现有对话流，RouterService 一行不动
- **原因**：需求文档第十四条要求；避免影响 SSE / Memory / 游戏模式（狼人杀/剧本杀）
- **放弃**：彻底重构 Router 的架构洁癖
- **影响**：现有 73 tests 零破坏；新功能全部在编排层增量

### D-004 TrackScore 阈值 40（需实测调参，勿 hardcode）
- **决策**：`InteractionDetector.TRACK_THRESHOLD = 40`（≥40 触发 Track 模式），因子：size 30 / bystander 20 / conflict 30 / secret 50 / emotion 25
- **原因**：Demo 实证（真实 LLM：无隔离泄露率 100% → Track WEAK 0% 泄露，token ↓14%）
- **放弃**：动态阈值（当前阶段无数据支撑）
- **影响**：已加 BoundaryTest 锁定 39/40 边界（问题清单 D18 建议配置化，待办）

### D-005 TrackStrategy 统一替换 GROUP_DISCUSSION + DEBATE
- **决策**：新 `TrackStrategy`（17.8KB）统一承担 GROUP_DISCUSSION 与 DEBATE；DYAD/Speech 不动
- **原因**：混合轨道上下文（MERGED 全文 / WEAK 摘要 / ISOLATED 独白 / 无 track 回退 GroupStrategy）
- **放弃**：给 DEBATE 单独写策略的重复工作
- **影响**：GroupStrategy/DebateStrategy 不删除（需求文档第十三条：降级为默认模式）

### D-006 MovementConstraint 纯规则，零 LLM
- **决策**：轨道→运动约束（MERGED 聚集质心±偏移 / WEAK 听觉带 [0.5h, h] / ISOLATED 避让≥60 格），优先级 ISOLATED > WEAK > MERGED；玩家手动目标不被覆盖
- **原因**：需求第八/九条——Track 不直接移动角色，而是生成空间约束；纯规则实时性
- **放弃**：GroupAnchor 完整队形（leader+follow slot，D2 降级项，P2 待补）
- **影响**：约束是边界不是牢笼——角色在带内自由漫游，越界才纠正

### D-007 前端角色详情走 body + 向后兼容 query
- **决策**：`SceneController.startScene` `@RequestBody(required=false)` 收 `{agents, me, characters[]}`，与 query 并存
- **原因**：前端未改时不崩（向后兼容）；characterDetails 需要结构化传递
- **影响**：7 项前端修复清单的一部分（loadState 扁平结构兼容等）

### D-008 测试基建：RANDOM_PORT + H2 mem + mock LLM
- **决策**：`src/test/resources/application-test.yml`：`server.port: 0`（避开 8000 生产）、`jdbc:h2:mem:testdb`（不污染 `./data/roleplay`）、`llm.api-base: http://localhost:9999/mock-llm`（零成本）
- **原因**：G2 禁止 spring-boot:run 直跑 + 数据隔离 + LLM 成本
- **放弃**：测试连生产 H2 文件库 / 真实 LLM
- **影响**：93 tests 可重复、可 CI；LONG-01 10 万字 5.09s 跑完

### D-009 剧本杀 Step 3 降级预案（未衡审查定案）
- **决策**：若 ConversationManager 适配 SimulationWorld 失败（剧本杀无 2D 世界），降级为"轮次发言"：按 assignments 顺序轮流每人 1 条消息，轮数可配置默认 2，结束自动进 VOTE；秘密隐藏退化为 prompt 禁令
- **原因**：`ConversationManager.init` 需要 `SimulationWorld + Function<String,Agent>`，剧本杀没有 world 实体
- **放弃**：降级版接 Track（P1 再补）
- **影响**：降级版验收 D-3-1~D-3-4 替代 A3-1~A3-4（蓝图第四章）

### D-010 剧本杀 Step 4 killer 与 truth 职责分离
- **决策**：判定用 `mostVoted.equals(killer)`（机器对比例，角色名精确匹配），不再 `truth.contains(mostVoted)`（字符串扫文案）；truth 保留为人类可读叙述文
- **原因**：contains 子串误匹配（投"张三"命中"管家张三在书房…"判中）；无凶手实体概念
- **放弃**：继续用 truth 文案做模糊判定
- **影响**：schema 需加 killer 字段；双生成器（ScriptService/initGame）schema 必须同步（风险 5）

### D-011 剧本杀 Step 1 Part A 已被并行工作流完成
- **决策**：secrets 字段/toMap your_secret/getSecretFor 已由另一主会话的 coder 实现（16:05），本会话仅做 Part B（Track ISOLATED 集成）
- **原因**：并行工作流撞车的事实（sessions_list 发现 4 个 coder 在改同一批文件）
- **影响**：**任何剧本杀派单前先 `git diff` 确认基线**，避免重复实现

---

## 2026-08-01（GAP-3 批次 A）

### D-012 剧本杀讨论引擎：主路径最小适配，未启用降级轮次发言
- **决策**：GAP-3 走主路径（spike 验证可行），ScriptGameService 自建轻量 `SimulationWorld`（仅作 Agent 容器，不起 tick）+ **独立 `ConversationManager` 实例**（非 Spring 单例），通过 ConversationManager 新增的 `createScriptDiscussionGroup`（显式成员+显式轨道）+ `runScriptDiscussionRounds`（同步驱动 maxRounds 轮，复用 TrackStrategy，WEAK=EavesdropSummarizer 摘要）接对话引擎；持秘密角色轨道=WEAK（只给摘要不给明文）、未持=MERGED（全文）；目标按秘密注入（隐藏秘密/查明真相，WorldDirectorService.setGoal）；讨论结束自动进 VOTE
- **原因**：① `new SimulationWorld()` 无参可建，角色仅需 registerAgent，无 2D 寻路/障碍依赖；② Spring 单例 ConversationManager 已被 2D 模拟 init 占用，重 init 会互相覆盖 → 必须独立实例；③ 2D 群组按空间听觉（ModeClassifier）自动生成，无界、无结束回调、不可指定成员 → 无法满足"讨论结束自动进 VOTE"；④ 现有 ScriptController 旧桥 `setSecretAgents(全员)` 走 TrackDirectorService 强制 ISOLATED（内心独白、不入群轮），而剧本杀人人有秘密 → 全员 ISOLATED = 无对话，且语义需要的是 WEAK 不是 ISOLATED
- **放弃**：① 降级轮次发言（D-009 预案，主路径已验证不需要）；② 复用 ScriptController 旧 2D 桥跑讨论（保留其 2D 可视化职责不动）；③ 讨论 persona 写入秘密明文（违反 A3-2）——讨论 persona 只含"你有一个不可告人的秘密"禁令式描述，秘密原文仍经 your_secret 仅发对应玩家
- **影响**：① A3-1~A3-4 全过（ScriptGameDiscussionTest 4 用例）；② 讨论在后台虚拟线程驱动，/api/script/start_discussion 不阻塞，前端轮询 status 见 DISCUSSION→VOTE；③ 轮数可配置 `roleplay.game.discussion.max-rounds`（默认 2）；④ 已知限制：讨论引擎为 service 实例级共享（多局并发讨论会互覆世界，与 2D 单世界同限制，P1 可改 per-game）；真实 LLM 下每轮耗时受最长 agent 调用限制，讨论总时长≈maxRounds×单轮最长，长局建议降轮数或改异步节流；⑤ 旧 2D 桥的"秘密 persona+全员 ISOLATED"旧语义保留未动（2D 可视化路径独立，P1 可对齐）

### D-013 剧本杀 ENDED 终态走显式确认（confirmEnded），落库双点 + script SSE 走既有广播机制（批次B）
- **决策**：① ENDED 不并入 resolveVote 自动流转——resolveVote 批准后仍停 REVEAL（保留 D7 既有验收与揭晓展示语义），新增 `confirmEnded`（REVEAL→ENDED，幂等；非 REVEAL 拒绝）并暴露 `POST /api/script/finish`，由前端“🏁 结束对局”按钮/调用方确认收尾；终态不越界由既有 phase 守卫天然承担（search/castVote/resolveVote/startDiscussion/startVoting 均按 phase 拒绝）；② 落库双点：initGame 落剧本（type=script）+ confirmEnded 落对局结果（type=result，含 players/assignments/killer/winner/votes/correct/truth/讨论记录摘要）——saveScript 从 0 调用变 2 个业务调用点；ScriptEntity 字段够用（contentJson 整包存 JSON），未扩展表结构；③ script SSE 复用 `SSEController.broadcast`（Jackson JSON 序列化）新增 3 个 typed 事件：script_phase/script_status/script_reveal，状态变更点全推；**调研发现后端此前并无 werewolf_* 实际推送（前端监听器是先行建的死监听）**，故无既有“werewolf 推送范例”可抄，按 RouterService→SSEController 广播模式新建；④ ScriptGameService 加 `@Autowired` 四参构造（LLMClient/ApprovalService/DatabaseService/SSEController），保留二参构造供既有测试，落库/推送均 null 守卫
- **原因**：① 直接改 resolveVote 自动 ENDED 会破坏 D7 既有验收（断言 phase==REVEAL）且前端会错过 REVEAL 展示；② 蓝图 A4-4/A4-3 分别要求“判定流程结束后 phase==ENDED”与“对局结束后 findAll 非空”，显式确认 + 双点落库同时满足且语义清晰；③ SSE 推送走既有广播通道零新依赖，前端参照 werewolf_* 分支写法接线
- **放弃**：① 审批通过后 resolveVote 内部自动 REVEAL→ENDED 一步到位（破坏既有测试/展示）；② 给 ScriptEntity 加专用字段（killer/votes 等列）——contentJson 已够用，避免表结构膨胀；③ 新建独立 script SSE 通道（与既有 emitter 管理重复）
- **影响**：① A4-4/A4-3 全过（ScriptGameEndedTest 3 用例 + ScriptPersistenceTest 1 用例，@SpringBootTest+H2 mem 真落库）；② 前端 ScriptStatePanel 由轮询改 store 驱动（SSE 优先 + 3s 轮询兜底），appStore 新增 scriptState/scriptPhase/scriptReveal；③ 全量 139 tests 全绿；④ 已知限制：SSE 推送为全局广播（无 session 过滤，多局并发时各局事件会串到同一连接，前端按 session_id 字段可区分，P1 可做 per-session emitter）；⑤ 剧本杀对局结果已可持久化，但暂无查询/回放入口（ScriptRepository 自定义查询 P2 后备）

### D-014 剧本数据模型 Schema v1：JSON 内嵌版本 + 双生成器统一（批次 C1，2026-08-01）
- **决策**：① 剧本 JSON 新增 `schema_version: 1`，结构化字段 metadata{title,player_min,player_max,tags} / roles[]{id,name,intro,is_hidden,secret} / clues[]{id,title,location,content,transferable,visible_to_owner_only} / killer_id / truth / background / locations / secrets——务实对齐通用剧本杀范式 Chronos Script Schema v2 核心子集（调研报告 §2.2），契约文档 `docs/剧本-schema-v1.md`；② **双生成器统一**：ScriptService.generateScript 为唯一生成路径（统一 prompt + 宽容 normalize + defaultScript 兜底），ScriptGameService.initGame 委托之（内部 new ScriptService(llmClient)，二/四参构造均生效，Spring 无需改线），两路径输出同一 schema；③ secrets 纳入 schema：raw.secrets ∪ roles[].secret，**仅当所有角色均无秘密时全量兜底**（A1-3 键集合==roles），部分秘密保持部分（不破坏 A3-2/A3-4 未持秘密角色走 MERGED 的讨论轨道语义）；④ ScriptEntity 不加 schema_version 列——版本内嵌 contentJson（schema 版本化即 JSON 版本化），避免 H2 file 库迁移；⑤ killer_id 为元数据/落库字段，**运行时判定仍走 D6 truth 精确解析**（killer_id 与判定解耦，判定路径零改动）
- **原因**：① 蓝图 P2 后备首项（双生成器 schema 漂移）+ D-010 风险 5（Schema 需加 killer 字段且双生成器必须同步）——ScriptService 骨架无 secrets/killer，initGame 内联生成含 secrets，同一剧本两条生成路径字段不一致；② 前端消费靠约定而非契约——schema 化后生成/落库/读取有统一契约；③ 宽容解析保证既有 LLM 输出与测试 mock（roles 字符串数组 / clues 带 public / 无 metadata / 无 killer_id）零破坏归一
- **放弃**：① Chronos 全量（timeline DAG 节点图 / visible_to 表达式 / ap_cost 行动点 / assets）——本项目为线性六态状态机 + 按地点搜证，DAG 化留 P2；② 给 ScriptEntity 加 schema_version 列——contentJson 已内嵌版本，且 D-013 已定“不扩展表结构”；③ 改造 ScriptGameService 改注入 Spring ScriptService bean——new 实例即可，避免构造链与测试改动
- **影响**：① 全量 153 tests / 0 failures（146 基线含并行 broadcast 7 用例 + 新增 ScriptSchemaV1Test 7 用例：旧格式归一/新格式透传/兜底符合 schema/双生成器一致性/兜底与 killer_id 解析）；② 落库 type=script 的 contentJson 升级为 v1（含 schema_version/metadata/roles[]/killer_id），既有 type=result 不变；③ toMap/SSE 状态新增 schema_version 键（附加，前端契约不破坏）；④ POST /api/session/script/generate 响应升级为 v1 schema（前端实际走 /api/script/init，无旧形状消费，零破坏）；⑤ 双生成器一致性由测试锁定（同输入 → generateScript 与 initGame.getScriptSchema 输出一致）

### D-015 演讲与广播合并地基：统一消息管线，不建两套系统（2026-08-01 coder demo 批次）
- **决策**：不分开建「演讲系统」和「广播系统」——统一一条消息管线：`BroadcastMessage`（level SYSTEM>EVENT>PLAYER>NPC + channel global/area/system + mode speech/announcement + 坐标/半径）+ `AnnouncementService`（PriorityQueue + 滑动窗口节流 + 同 key 合并 + 队列上限 + 断线补发环形缓冲，节流参数 `roleplay.broadcast.*` 可配，对齐 D-004「阈值勿 hardcode」）→ 每 100ms flush → 复用 WorldEventBus 进程内分发（新事件常量 TYPE_ANNOUNCEMENT）+ SSEController.broadcast("announcement") 推前端。演讲=带空间范围(radius)+听众模型的广播（channel=area），公告=无范围/全局广播（channel=global）；前端统一 AnnouncementBanner（打字机+队列）+ AnnouncementTicker
- **原因**：① 调研报告结论——项目已有 80% 地基（WorldEventBus/GameEvent/SSEController/SpeechStrategy/HearingSystem），缺统一领域类 + 优先级/节流机制、演讲产出不落广播通道、前端无横幅/公告栏；② 需求：AI 与玩家都可发起、形态自动选择
- **AI 自动选择**：AI 发言（PUBLIC_SPEAKING 轮次产出，经 ConversationManager 新增 speechBroadcastListener 回调，SpeechStrategy 本体零改动）→ SimulationService 复用 ModeClassifier.wouldOthersListen（原 private 改 public）判定听众（2.5×hearRange 内距离>50 未入群角色≥2）→ enqueueAutoSpeech：有听众→演讲(area+半径) / 无听众→全局公告。玩家侧 POST /api/announcements（默认 PLAYER/global，可显式 level/channel/mode）；AI 演示端点 POST /api/simulation/speech（自动选 NPC+默认文案，形态自动判定，同一条管线）
- **放弃**：① 引 Guava EventBus / Spring ApplicationEventPublisher（项目自研 WorldEventBus 已够用，Guava 官方已不推荐）；② 演讲单独建一套队列系统（与广播并行走两套）；③ 节流参数硬编码
- **影响**：① 新增 broadcast 独立包（4 类）+ 2 个 REST 端点 + 前端 2 组件/1 SSE 事件/1 demo 面板，未触碰 RouterService/ArbiterService/狼人杀/剧本杀主链路；② PUBLIC_SPEAKING 才触发回调，剧本杀讨论（GROUP_DISCUSSION）不受影响；③ 演示闭环：AI 自动演讲→有听众区域广播/无听众全局公告 + 玩家发广播→全员横幅；④ 遗留：断线补发 REST（GET /api/announcements/recent）已就绪但前端重连自动补拉未接（P3）；⑤ 全量 146 tests 全绿 + npm run build 通过，产物已同步 static/

### D-016 搜证机制增强：AP 行动点 + 线索转交（批次 C2，2026-08-01）
- **决策**：① **schema 扩展**——ScriptSchemaV1 的 `clues[]` 补 `ap_cost`（搜索该线索消耗的行动点，缺省 1，normalize 恒填默认）、`roles[]` 补 `ap_bonus`（角色行动点加成，侦探类角色给 1-2，缺省 0），宽容解析旧剧本（无字段→默认值）保持向后兼容；② **AP 池**——initGame 按玩家分配初始 AP = 基础值（`roleplay.game.ap.base`，默认 3）+ 角色 ap_bonus，存入 game 状态（playerAp/playerApMax）；③ **搜证扣 AP**——search 保持“一次搜索=探索一个地点，必得该地点全部未持有可搜线索”语义，消耗 AP = 各线索 ap_cost 之和；AP 不足 → 整次拒绝、不部分授予、提示“行动点不足”；公开线索（public=true）不耗 AP 始终可见；④ **线索转交**——新增 `transferClue` + `POST /api/script/transfer_clue`（body: player/target_player/clue_id）：仅搜证/讨论阶段可转交（讨论交换线索是剧本杀常规玩法，讨论引擎本体零改动）、转交方必须持有（归属校验：visible_to_owner_only 的线索持有即归属，只有所有者能转交）、目标玩家必须在本局且非自己、`transferable=false` 拒绝、成功后 ownership 变更（源移除/目标加入）；⑤ **toMap 暴露**——新增附加键 ap/ap_max/ap_pool（全员剩余 AP 一览）/my_clues（请求者持有的全部线索含转入的），均附加键不破坏前端契约；⑥ **前端**——搜证面板显示 AP 余额、搜证结果/行动点不足提示、我持有的线索列表、可转交线索的转交 UI（选目标玩家），client.ts 加 scriptTransferClue；构建产物已同步 static/（index-DkvIhlhJ.js）
- **原因**：① 蓝图 P2 后备“行动力限制；角色差异化搜证（侦探线索多）”正式落地，对齐通用剧本杀范式 Chronos CLUE_SEARCH 节点设计（ap_cost/transferable/default_owner）；② schema v1 已预留 transferable/visible_to_owner_only 字段但搜证流程未用（docs/剧本-schema-v1.md 明示），本批次把预留字段投入使用；③ AP 不足“整次拒绝不部分授予”保证行动力限制语义清晰、无部分发放的边界状态；④ 讨论阶段允许转交是因为线索交换是讨论的核心玩法，且转交是状态变更不经过对话引擎，不影响 D-012 讨论链路
- **放弃**：① 按 Chronos 做 timeline DAG / visible_to 表达式 / 每线索独立 default_owner 字段（线性六态状态机 + 持有即归属已满足需求，DAG 化仍留 P2）；② 搜证改为“每次只搜一条线索”的逐条消耗（破坏既有“一次搜一个地点得全部线索”语义且无既有测试依赖，保持必得语义只加资源限制）；③ AP 池做成每玩家独立秘密值（AP 余额透明可见，棋盘游戏惯例，且可观察性更好）
- **影响**：① 新增 ScriptGameApTransferTest 9 用例（AP 初始化含 ap_bonus/旧剧本默认、搜证扣减/公开线索免费/同地点不重复得、AP 不足拒绝、转交成功 ownership 变更/status 可见、转交拒绝 5 类、schema 兼容默认值、端到端）；② 全量 162 tests / 0 failures（153 基线含并行批次 C1 零破坏；LONG-01 3.5s 全绿）；③ 旧剧本（无 ap_cost/ap_bonus）与旧测试 mock 零破坏（默认 1/0）；④ 转交后接收方 status/my_clues 立即可见、原持有者不可见（ownership 变更即信息变更，无 SSE 专门事件——script_status 轮询/SSE 已有状态推送覆盖）；⑤ AP 基础值可配 `roleplay.game.ap.base`（D-004 阈值勿 hardcode 纪律）

### D-017 剧本杀断线重连与会话恢复：roleKey 认证 + 全量快照恢复（批次 C3，2026-08-01）
- **决策**：① **roleKey 令牌**——initGame 为每玩家生成唯一 UUID 存 playerKeys；玩家级端点（status/search/transfer_clue/vote）支持可选 `player_key` 认证（有 key 校验匹配→错误 403；无 key 向后兼容按玩家名）；toMap 仅向本人暴露 `role_key`（匿名/广播视图不含）；**顶号语义落地为“key 校验替代玩家名可被任意冒充”**（本项目无 WebSocket，无“新连接顶旧连接”概念，防冒充与重连一体化由 key 承担）；② **全量快照恢复**——ScriptEntity 落 `type=snapshot` 行（name 前缀「对局快照:<sessionId>」作鉴别器，避免 JSON 内容查询；不扩展表结构，遵循 D-013/D-014 纪律），每次状态变更全量快照（对齐 Chronos“内存 Room 仅缓存，每次状态变更写持久化，崩溃后 restore 重建”）；新增 `POST /api/script/resume`（game_id/room_code/player_key 三选一定位 + player_key 认证）：内存对局存在直接返回视图（restored=false）、不存在从快照重建（restored=true，重启后可用）、ENDED 返回终态（terminal/murderer/correct/truth/votes/winner）；③ **房间绑定（轻量）**——init 可选 `room_code` 存 ScriptController 内存映射（roomCode→sessionId），resume 可按房间码定位；RoomController 本体未动；④ **DM 分发**——新增 `GET /api/script/keys`（全员令牌一览，DM 面板批次用）
- **原因**：① 调研报告 §2.4 结论——本项目断线重连与防冒充双缺失，roleKey 顶号是 Chronos 的鉴权+重连一体范式；② 批次 B 已落 ScriptEntity（type=script/type=result），快照复用同一实体零新表；③ 适配 SSE+轮询现实——无 socket 可顶，改为“凭 key 恢复个人视图”
- **放弃**：① 新表存快照（ScriptEntity contentJson 已够用，避免表结构膨胀）；② 增量快照/事件溯源（全量 JSON 每次写入已满足规模，diff 逻辑过度设计）；③ 双向房间绑定（/api/rooms 为纯内存大厅、无 gameId 字段，双向绑定需改 RoomController 结构——跳过并在报告说明）
- **影响**：① 全量 170 tests / 0 failures（162 基线 + ScriptGameResumeTest 8 用例）；② 玩家级端点契约向后兼容（无 key 行为不变）；③ 已知限制——DISCUSSION 阶段的后台讨论线程随重启丢失（恢复后 phase 停 DISCUSSION，DM 可调 /api/script/start_voting 手动推进）；player_key 反查对局仅限内存对局（重启后需 game_id/room_code）；SSE 推送仍全局广播（D-013 既有限制）；④ 前端本轮未动（重连 UI 归 DM 面板批次），API 契约已在报告写明 |

### D-018 剧本杀 DM（主持人）面板：全量仪表盘 + 状态机推进端点，前端面板与重连 UI 落地（批次 C4，2026-08-01）
- **决策**：① **DM 全量视图**——新增 `GET /api/script/dm/status`（service 层 dmStatus）：对齐 Chronos state:dm_dashboard，DM 可见全部玩家角色/秘密/AP/线索数/投票状态/roleKey + 对局元数据（truth/killer_id/判定/winner/审批门状态）；与玩家级 toMap（脱敏）分道——DM 视图不脱敏，越权由 controller 层 DM key 门承担；② **DM 手动推进**——新增 `POST /api/script/advance`（service 层 advancePhase）：对齐 Chronos dm:advance，状态机逐级推进 INVESTIGATION→DISCUSSION（复用 startDiscussion 接讨论引擎）→VOTE（复用 startVoting，即 C3 已知限制“恢复后 DM 手动推进”的入口）→REVEAL（复用 resolveVote，天然继承 D6 精确判定/平票回滚 + D7 审批门）→ENDED（复用 confirmEnded 落库）→ENDED 幂等终态不越界；advance 响应补 phase 键（resolveVote 响应无 phase 键，属既有 /resolve 契约，advance 统一补全）；③ **DM key 越权保护**——新增配置 `roleplay.game.dm.key`（@Value 读，空=放开，非空时 DM 端点要求 X-DM-Key 请求头匹配否则 403），放开默认与审批门 D7 同模式（项目无强鉴权传统），前端 DM 面板提供密码输入框（存 localStorage）；④ **前端**——ChatPage 顶部「🎛 主持人」按钮（仅剧本杀模式）开 DM 抽屉：对局概览/玩家表（秘密可切换显示）/roleKey 分发复制（GET /api/script/keys 数据源）/「推进→下一阶段」/审批门 pending 时「✅批准/驳回重投」/ENDED 终态；「🔄 恢复对局（重连）」折叠面板：对局ID 或 房间码 + roleKey → POST /api/script/resume → 恢复玩家视图写入 store，ENDED 显示终态结果卡；⑤ 本轮零改动狼人杀/中断/对话引擎/AP 转交/重连后端逻辑
- **原因**：① 调研报告 §2.4 结论——通用范式 DM 拥有 dmToken 可推进流程（dm:advance）、强制查线索、改 AP、禁言，DM 看全量仪表盘（state:dm_dashboard），本项目此前**无 DM 专用控制台**（审批门 D7 仅手动审批单点，剧本杀流程控制散落多个端点）；② C3 已提供 keys/resume 但无面板 UI，重连入口 UI 缺失（蓝图 P2 最后一项）；③ VOTE 步复用 resolveVote 而非另写判定——避免双套判定路径（D6/D7 语义单一事实源），advance 只是编排器
- **放弃**：① 单独实现 dm:override_ap / dm:force_view / dm:mute_player（Chronos 其余 DM 能力）——本项目 AP 透明可见、无私聊私密视图推送、无 WebSocket 顶号概念，三项在本架构下无明确消费场景，留 P2；② 给 DM 端点做完整登录/角色体系鉴权（项目全局无此体系，审批门亦开放；用可配置 key 门 + 放开默认保持开发体验一致）；③ advance VOTE 步改为“不阻塞直接返回 pending”（既有 /resolve 与审批门 submitForApproval 就是阻塞语义，改为异步需动审批门机制，超范围）
- **影响**：① 全量 176 tests / 0 failures（170 基线 + ScriptGameDmTest 6 用例：DM 全量视图/未知对局/advance 全状态机/审批门挂起批准/controller DM key 403/200/未知对局报错）；② 新增 2 端点 + 1 配置键（roleplay.game.dm.key），前端新增 ScriptDmPanel 组件 + ChatPage 主持人抽屉 + 恢复对局入口，构建产物同步 static/（index-DFAHiAkk.js）；③ advance 是纯编排不新增状态（无新 phase/无表结构变更/无快照格式变更——各步内部已有快照点）；④ 已知限制——advance VOTE 步阻塞等待审批（最长 roleplay.game.approval.timeout-seconds=60s，超时自动驳回回滚，与 /resolve 同语义）；SSE 仍全局广播（D-013 既有限制）；DM 面板 3s 轮询 dm/status（无专属 SSE 事件，面板自己拉）
- **Virtual Threads** 并行 Agent 执行（LLM 大量 IO 等待）；放弃 Reactive WebFlux（代码复杂度）
- Python → Java 全量迁移（v4 前端保留 React，后端重写）
- H2 双模式：开发 mem / 持久 file（README 声明，注意 D14 缺陷：角色/场景实际未落库）


### D-019 方案B（分步/内联接线）演讲广播落地 + 方案A/B 共存开关（2026-08-01 coder 方案B 批次）
- **决策**：按调研报告 §4 落地计划 Step 2-3 实现第二套 demo，**保留方案A demo 不动**：① Step 2 演讲接广播走**内联路径**——`SpeechStrategy.processResults`（L76）内 `speakerState.setCurrentMessage` 后追加 `broadcastSpeechInline`（L124），split 模式下直接 `AnnouncementService.enqueue(NPC/area/speech+坐标+半径)`，演讲即刻变区域广播；远近判定复用 `HearingSystem`（`countHearingListeners` L147：computeAudibility+canHear）；② Step 3 剧本杀阶段广播——`ScriptGameService.broadcastPhase` 统一漏斗处追加 `broadcastSystemAnnouncement`（L1048），五处阶段切换（initGame/startDiscussion/startVoting/resolveVote/confirmEnded）发 SYSTEM 级 announcement（channel=system）到全局横幅通道，与 script_phase SSE（会话面板通道，台账 #35）并存不冲突，前端轮询保留兜底；③ **共存开关** `roleplay.broadcast.speech-mode`（AppConfig.BroadcastConfig.speechMode，默认 **auto=方案A**；split=方案B），`AnnouncementService` 为单事实源（get/set），两路径各自 gate（ConversationManager.executeRound 回调块判 auto；SpeechStrategy 判 split）互斥不重复推送，同一运行实例可经 POST /api/announcements/mode 或前端 ScenePage 面板运行时切换
- **原因**：① 主人要求"保留方案A demo 不动，按调研报告实现第二套 demo 后对比两方案稳定性/灵活性/真实游戏性"；② 报告建议 SpeechStrategy 经构造参数注入 AnnouncementService（非 Spring 类，与 agentLookup/narrationSupplier 同模式）；③ Step 3 的 announcement SYSTEM 广播与台账 #35 script_* SSE 是**两个通道**（会话面板 vs 全局横幅），需并存而非替换；④ 两路径都触发会重复推送 → 必须配置化互斥开关，且要支持同一实例演示切换
- **放弃**：① 改方案A 既有接线（onSpeechBroadcast 回调路径原样保留，SpeechStrategy 旧三参构造/ConversationManager 旧四参 init 均保留委托）；② 给阶段广播用默认 coalesceKey（speaker|channel=system|system）——investigation+discussion 同窗快速连发会被合并成 ×N，阶段切换是离散横幅事件，改 phase 级 key（script_phase|<phase>）；③ Step 3 前端新组件——announcement 全局横幅（AnnouncementBanner/Ticker，台账 #37）已渲染 SYSTEM 金色横幅，剧本杀阶段横幅自动出现，仅补 demo 面板方案切换 chips
- **影响**：① 全量 183 tests / 0 failures（176 基线 + SpeechStrategySplitModeTest 5 用例 + ScriptGamePhaseAnnouncementTest 2 用例）；② 方案A 行为逐字节不变（auto 默认，SpeechStrategy gate 直接 return，ConversationManager 回调照旧）；③ 新增 2 端点（GET/POST /api/announcements/mode）+ 1 配置键（roleplay.broadcast.speech-mode）+ SpeechStrategy/ConversationManager 构造重载（旧构造保留）；④ ScriptGameService 5 参 @Autowired 构造（4 参/2 参保留委托，既有测试零改动）；⑤ 前端 ScenePage demo 面板方案A/B 切换 chips + client.ts 2 个 API 封装，构建产物 index-CcvzWufL.js 已同步 static/；⑥ 对比报告《speech-broadcast-方案对比.md》：稳定性 A 胜（广播外挂于对话链路）、灵活性各有所长、真实游戏性 B 胜（空间声学+AI 自主+玩家空间感知），正式版建议合并两方案优点（A 的管线 + B 的 HearingSystem 声学判定），无听众兜底做成可配置；⑦ 遗留：wouldOthersListen 阈值硬编码未配置化（D-004 纪律欠账）、前端断线补发未接线（P3）、8000 实例未重启新前端产物未生效

---

## 2026-08-01（Phaser 3.90 2D 渲染层迁移决策）

### D-020 接入 Phaser 3.90（锁 v3 稳定线）作为 2D 渲染层，渐进式替换自研 Canvas 渲染
- **决策**：前端接入 Phaser 3.90（锁定 v3 稳定线，不追 v4 重构线）作为 React 内的 2D 渲染层，**渐进式迁移**替换自研 Canvas 渲染：阶段 0 验证 demo（瓦片渲染+碰撞 / BSP 分区 / Zone 热点 / Aseprite 动画 / 地图 JSON 契约草案）→ 阶段 1 ScenePage 渲染层换 Phaser（数据流不变）→ 阶段 2 LLM 生成地图 JSON 接入、搜证线索绑定热点、schema 版本化；完整迁移计划见 `docs/Phaser迁移计划.md`
- **原因**：工作量对比——自研 2D 渲染能力 10–16 人日 vs Phaser 引擎 2.5–4.5 人日（引擎已含瓦片/碰撞/动画/输入/摄像机/粒子等），回本点 3–5 个 2D 渲染功能点，当前路线已确定 5 个（瓦片地图+碰撞、BSP 分区、Zone 热点、Aseprite 动画、地图 JSON 契约）→ 越过回本点后引擎路线净收益
- **结构性前提**：后端 Java 权威模拟 + 前端纯渲染（数据流：后端 SSE/REST 推送状态 → 前端渲染），引擎只换渲染层，**后端零改动**；Track 数据管线 / 演讲广播 / 剧本杀链路均不感知渲染层替换
- **资产保值**：自研资产约 70% 保值——vision_core.js（视线/迷雾，纯函数逻辑）可移植或算法参照、Track 数据管线（SpatialTrackResolver→TrackStrategy→MovementConstraint 的产出即渲染数据源）、演讲广播地基（AnnouncementService/WorldEventBus 驱动横幅与提示）、React 组件（ScenePage/ChatPage/各面板）全复用；仅 simulation.html 手绘渲染（约 450 行）作废
- **放弃**：① 继续在自研 Canvas 上堆渲染能力（10–16 人日且后续功能点边际成本不降）；② 一次性整体重写渲染层替换（破坏渐进迁移「每阶段可验收」原则）；③ Phaser v4（重构线，生态与资料成熟度不及 v3 稳定线）
- **影响**：① 后端零改动、数据流契约不变；② 前端渲染层从自研 Canvas 过渡到引擎，瓦片地图/碰撞/BSP/热点/动画由引擎能力承载；③ 阶段 0 验证 demo 先行，若验证失败则回滚保持自研渲染（风险预案见迁移计划 §3）；④ 5 个已确定功能点对应迁移路线与验收标准见迁移计划 §2

---

## 2026-08-01（演讲广播合并方案正式版落地）

### D-021 merged 合并方案转正式：A 的管线 + B 的声学判定 + 可配置兜底（主人拍板，台账 #46）
- **决策**：按对比报告 4.2 建议，`roleplay.broadcast.speech-mode` 新增 **merged** 并设为默认（正式版），auto/split 保留供回退对比：①**保留方案A 管线架构**——ConversationManager 回调（SpeechTurn）→ SimulationService 判定 → AnnouncementService.enqueueAutoSpeech，SpeechStrategy 不加内联广播（内联仅 split 启用）；②**听众判定升级为 B 的 HearingSystem 声学判定并集中回管线层**——SimulationService 不再用 ModeClassifier.wouldOthersListen（2.5×/50/≥2 硬编码，仅留 auto 回退路径），改用新增 `HearingSystem.countHearingListeners(speaker, allStates)`（computeAudibility/canHear 距离衰减、半径内可听听众计数）作**判定单事实源**，split 的 SpeechStrategy 内联判定也委托同一声学方法（删除原各自实现，杜绝双份漂移）；③**「无听众→全局公告」配置化**——新增 `roleplay.broadcast.fallback-to-global`（默认 true=自动升级全局公告保持现行为；false=不升级仅区域演讲/纯空间语义），AppConfig.BroadcastConfig 补字段+setter、yml 双键、AnnouncementService 构造真实读取；④**剧本杀阶段 SYSTEM 广播转正式**——#44 的 broadcastPhase→broadcastSystemAnnouncement 五处阶段切换（initGame/startDiscussion/startVoting/resolveVote/confirmEnded）无条件启用（不再依赖 split 模式），总开关 `roleplay.broadcast.script-phase-broadcast`（默认 true）
- **原因**：①对比报告结论——A 稳定性胜（广播外挂对话链路）、B 真实游戏性胜（空间声学+AI 自主+玩家空间感知）、灵活性各有所长，正式版合并两方案优点；②wouldOthersListen 阈值硬编码是 D-004 纪律欠账，声学判定集中后正式路径不再有硬编码阈值；③「无听众→全局公告」是游戏性便利（信息可达）与真实性（空间感）之争，用配置吸收，不再需要双路径共存做形态取舍；④剧本杀阶段广播已是统一管线红利（SYSTEM 级横幅通道），无理由继续留在 demo 开关后
- **放弃**：①继续双路径共存默认 auto（正式版形态决策含糊）；②把判定留在策略层（B 模式，判定分散易漂移）；③无听众恒全局公告（方案A 语义，牺牲空间感）或无听众恒区域（方案B 语义，信息易哑火）二选一
- **影响**：①默认行为=merged：AI 演讲产出经回调，HearingSystem 判定半径内可听听众≥1→区域演讲，否则按 fallback-to-global 决定升级全局公告或保持区域；②auto/split 行为逐字节不变（回退对比可用，POST /api/announcements/mode 运行时切换，三值合法）；③剧本杀五处阶段切换无条件发 SYSTEM 横幅（总开关可关）；④全量 190 tests / 0 failures（183 基线 + MergedSpeechModeTest 7 用例：声学判定正确性三分支/剧本杀阶段广播 merged 触发+开关/merged 防双发回归）；npm run build 通过并同步 static/（index-OlgI0-rr.js）；⑤遗留：前端断线补发接线（P3，台账 #37）、8000 实例重启后新产物生效
