# TEST_STATUS.md — 测试状态台账（AI 必读③，持续更新）

> ⚠️ **规则**：每次执行测试后**必须更新本文件**（追加记录 + 更新汇总）。测试通过就写入，失败也写（含原因），保持诚实。
> 执行命令：`cd D:\roleplay-java && C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd test`

---

## 📊 当前基线（最新汇总，2026-08-01 13:39）

| 指标 | 值 |
|---|---|
| 测试类 | **29** |
| 测试用例 | **214** |
| Failures / Errors | **0 / 0** |
| 最后全量执行 | 2026-08-01 13:39（批次D 收尾：SpeechGateTest 24 用例，214/0） |
| 环境 | H2 mem + mock LLM（application-test.yml） |
| 真机验证 | **merged 正式版 7 项 PASS**（2026-08-01 13:05，台账 #47：模式默认 merged / 玩家广播 / AI 演讲 fallback 升级 / 断线补发 recent / 模式往返切换 / 剧本杀阶段公告 / SSE announcement+script_* 并存） |

> v12 更新：基线从 190 → **214 tests / 29 类**（批次D 收尾：SpeechGateTest 24 用例——触发必发言七类（MENTION/QUESTION/HUMAN_CLUE/EMOTION/ROUND_FIRST/CLUE/COLD_BREAK + 多触发命中本角色，talkativeness=0 仍发言）/ 低分静默（含 SILENCE_MARKER 占位）/ 阈值边界（pri 5→P=0.145 静默·pri 6→P=0.154 发言 + 自定义 floor 包络）/ wait_bias 打折（人类发言中未点名 0.154→0.077 静默，高动机仍发言）/ 动机分映射与高动机突破 / COLD_BREAK 候选与开关 / 静态工具（isMentioning/isQuestioning/scanTurns/reasonOf/null target 契约））；详见 Round 12

> v11 更新：基线从 183 → **190 tests / 28 类**（合并方案正式版：MergedSpeechModeTest 7 用例——声学判定单事实源 HearingSystem.countHearingListeners 近/远、merged 半径内可听→area 演讲、无听众+fallback=true→global 公告、无听众+fallback=false→不升级保持 area、剧本杀阶段 SYSTEM 广播 merged 默认触发、script-phase-broadcast=false 总开关静默、merged 下 SpeechStrategy 不内联推送防双发回归；speech-mode 默认 merged，auto/split 保留回退；SpeechStrategySplitModeTest 默认断言改 merged）；真机验证 7 项 PASS（台账 #47，2026-08-01 13:05）
> v10 更新：基线从 176 → **183 tests / 27 类**（方案B 分步落地：SpeechStrategySplitModeTest 5 用例——split 内联区域广播/auto 静默/运行时切换/HearingSystem 远近判定/无听众仍区域；ScriptGamePhaseAnnouncementTest 2 用例——五处阶段切换 SYSTEM 广播 + 与 script_phase 并存）
> v9 更新：基线从 170 → **176 tests / 25 类**（批次C4 新增 ScriptGameDmTest 6 用例：DM 全量视图（state:dm_dashboard）、未知对局、advance 状态机推进 INVESTIGATION→ENDED、VOTE 步审批门挂起→批准、controller DM key 越权 403/200、未知对局/缺 session_id 报错）
> v7 更新：基线从 153 → **162 tests / 23 类**（批次C2 新增 ScriptGameApTransferTest 9 用例：AP 初始化含 ap_bonus/旧剧本默认、搜证扣减/公开免费/同地点不重复得、AP 不足拒绝、转交成功 ownership 变更+status 可见、转交拒绝 5 类、schema 兼容默认值、端到端）
> v6 更新：基线从 146 → **153 tests / 22 类**（批次C1 新增 ScriptSchemaV1Test 7 用例：旧格式归一 v1 / v1 透传 / 兑底符合 schema / 双生成器一致性 / killer_id 解析）
> v5 更新：基线从 139 → **146 tests / 20 类**（本批次新增 AnnouncementServiceTest 7 用例：优先级/节流/同 key 合并/自动选择/事件总线/SSE/断线补发）
> v4 更新：基线从 135 → **139 tests / 19 类**（批次B 新增 ScriptGameEndedTest 3 用例 + ScriptPersistenceTest 1 用例，覆盖 A4-4/A4-3/GAP-8）
> v3 更新：基线从 131 → **135 tests / 18 类**（GAP-3 批次新增 ScriptGameDiscussionTest 4 用例，覆盖 A3-1~A3-4）

---

## 测试类明细（214 tests / 29 类）

| 测试类 | 用例 | 状态 | 对应 |
|---|---|---|---|
| ApprovalServiceTest | 11 | ✅ | 审批门（D7） |
| RouterServiceHooksTest | 11 | ✅ | Hook 系统 |
| McpServiceTest | 10 | ✅ | MCP |
| TrackStrategyTest | 7 | ✅ | Phase 2 |
| SimulationOrchestratorTest | 4 | ✅ | Phase 3 |
| TrackDirectorServiceTest | 10 | ✅ | Phase 3 |
| WorldDirectorServiceTest | 7 | ✅ | Phase 3 |
| MovementConstraintTest | 11 | ✅ | Phase 4 |
| InteractionDetectorTest | 7 | ✅ | Phase 1 |
| SpatialTrackResolverTest | 6 | ✅ | Phase 1 |
| **LongTextStabilityTest** | 1 | ✅ | LONG-01（需求硬性） |
| **CompressorChainTest** | 1 | ✅ | LONG-03 |
| **DatabaseServiceTest** | 1 | ✅ | LONG-02 |
| **InteractionDetectorBoundaryTest** | 2 | ✅ | 阈值 40 边界 |
| **TrackDirectorSecretOverrideTest** | 4 | ✅ | 秘密强制 ISOLATED |
| **SpeechGateTest** | 24 | ✅ | **批次D 发言门控（P0-1：触发必发言七类/低分静默含占位/阈值边界 0.15/wait_bias/动机分/COLD_BREAK/isMentioning/isQuestioning/scanTurns/reasonOf）** |
| **ScriptGameServiceTest** | 12 | ✅ | **剧本杀 D6/D7（平票重投/非法票/揭晓审批）** |
| **ScriptGameDiscussionTest** | 4 | ✅ | **剧本杀 GAP-3（讨论接对话引擎 A3-1~A3-4）** |
| **AnnouncementServiceTest** | 7 | ✅ | **演讲+广播合并地基（优先级/节流/合并/自动选择/事件总线/SSE/断线补发）** |
| **ScriptGameEndedTest** | 3 | ✅ | **剧本杀 GAP-4b/4c/8（A4-4 ENDED 终态 / 边界 / SSE 推送+落库调用点）** |
| **ScriptPersistenceTest** | 1 | ✅ | **剧本杀 A4-3（对局结束后 ScriptRepository.findAll() 非空，H2 真实落库）** |
| **ScriptSchemaV1Test** | 7 | ✅ | **批次C1 剧本 schema v1（旧格式归一/v1 透传/兑底/双生成器一致性/killer_id）** |
| **ScriptGameApTransferTest** | 9 | ✅ | **批次C2 搜证增强 AP+转交（AP 初始化/搜证扣减/AP 不足拒绝/转交成功/转交拒绝/schema 兼容/端到端）** |
| **ScriptGameResumeTest** | 8 | ✅ | **批次C3 断线重连与会话恢复（roleKey 生成/校验/内存 resume/快照恢复/脱敏/ENDED 终态/拒绝路径/controller 层认证）** |
| **ScriptGameDmTest** | 6 | ✅ | **批次C4 DM 面板（全量视图 state:dm_dashboard / advance 状态机推进 / 审批门挂起批准 / controller DM key 越权 403/200）** |
| **MergedSpeechModeTest** | 7 | ✅ | **合并方案正式版 merged（声学判定近/远·半径内可听→area·无听众+兜底开→global·兜底关→不升级·剧本杀阶段广播 merged 触发+总开关·merged 防双发）** |
| **SpeechStrategySplitModeTest** | 5 | ✅ | **方案B 演讲内联广播（split 区域广播带坐标半径/merged 静默/运行时切换/HearingSystem 远近判定/无听众仍区域）** |
| **ScriptGamePhaseAnnouncementTest** | 2 | ✅ | **方案B Step 3 剧本杀阶段 SYSTEM 广播（五处阶段切换 + 与 script_phase 并存）** |
| **新增稳定性/中断/DB 测试** | ~26 | ✅ | 并行工作流新增（中断系统/DB/模拟） |

---

## 超长文本稳定性（需求文档第十条硬性要求）

| 用例 | 参数 | 结果 | 耗时 |
|---|---|---|---|
| LONG-01 | 500 轮 × 200 字 = **10 万字上下文**，mock LLM | ✅ 21:28 复跑 PASS：P50=4ms P95=9ms Max=72ms，堆增长 -3.7%，无 OOM/卡死/锚点可检索 | ~5s |

---

## 📝 执行历史（追加式）
### 2026-08-01 13:35–13:39 — 批次D 收尾：SpeechGate 专项测试（Round 12，214 tests）
- **命令**：`mvn test`（全量；新测试类先行单跑 24/0 通过后全量复跑）
- **结果**：214 tests / **0 failures** / 29 类 / BUILD SUCCESS（surefire 报告逐类核对：24 类之和 214，0 失败 0 错误）
- **新增**：SpeechGateTest 24 用例（纯单测，SpeechGate 为确定性组件无 Spring/mock LLM）——①触发必发言：MENTION/QUESTION/HUMAN_CLUE/EMOTION/ROUND_FIRST/CLUE/COLD_BREAK 七类触发 + 多触发并存命中本角色，talkativeness=0 仍发言（不受概率限制）；②低分静默：无触发+低动机(pri 0→0.1)+低健谈(0.1) → P=0.01<0.15 静默，原因含「静默·P=」，占位符 SILENCE_MARKER==「……（沉默）」断言；③阈值边界：默认 0.15 附近 pri 5→P=0.145 静默 / pri 6→P=0.154 发言；自定义 floor 0.1535/0.1545 包络 P=0.154 两侧；④wait_bias：人类发言中未被点名 P×0.5 打折（0.154→0.077 静默，原因含「人类发言中」）；高动机 pri 100 打折后 0.5 仍发言；⑤动机分：motiveScore 0→0.1/5→0.145/50→0.55/100→1.0、负值与超界钳制；高动机(pri 100)+低健谈(0.2)→P=0.2 突破阈值发言；⑥COLD_BREAK：候选全低分仍必发言（原因「触发·冷场破冰」）+ 开关默认 true/显式 false；⑦静态工具：isMentioning（@名/句首/标点后点名，嵌入词「是管家/管家婆」不误判，前字符空格不误判）；isQuestioning（？/怎么/解释/为什么/你说）；scanTurns（QUESTION/MENTION 产出、自点名/空发言/空 speaker/空 map 跳过、null 入参空安全）；reasonOf 七类型映射；null target 触发与 null 列表不强制发言（全员相关事件由调用方转 per-member 触发）
- **回归**：既有 190 基线零破坏（含 LONG-01 10 万字：P50=2ms / P95=4ms / Max=26ms，堆增长 -22.2% 无 OOM——未触发堆测量脆性）；单跑 SpeechGateTest 24/0（0.074s）
- **文档**：docs/剧本-schema-v1.md 同步 roles[].talkativeness（缺省 0.5，兼容 personality.talkativeness 嵌套）；DECISION_LOG 新增 D-022（SpeechGate 决策记录，任务书要求 D-019 已被方案B 占用按序顺延）；docs/修改记录.md 台账 #52；本文件 v12
- **前端**：未动（收尾三评估：SILENCE_MARKER 渲染与 @AI 输入提示——P-0801-B Phaser 批次占用 App.tsx/ScenePage.tsx/appStore/package.json 等前端文件，且改动需 npm run build 重操作；经查讨论区消息渲染走通用消息列表（无静默占位特殊样式），输入框为普通文本框无 @ 提及逻辑，建议后续批次处理，见报告）
- **git**：未 commit（未获授权）

### 2026-08-01 11:58–12:40 — 方案B 分步落地 demo（Round 10，183 tests）
- **命令**：`mvn test`（全量）
- **结果**：183 tests / **0 failures** / 27 类 / BUILD SUCCESS
- **新增**：①SpeechStrategySplitModeTest 5 用例——split 模式演讲产出→AnnouncementService 收到 area 广播（NPC/area/speech+speaker 坐标+半径+文案）；auto 模式 SpeechStrategy 静默（由方案A 回调路径接管，不重复推送）；同一实例 setSpeechMode 运行时切换（auto→split 出广播、切回 auto 不再内联）；远近判定复用 HearingSystem（近处 50px 听众=1 / 950px 外=0）；split 无听众仍发区域广播（半径携带范围，消费侧自然无人展示）。②ScriptGamePhaseAnnouncementTest 2 用例——initGame/startVoting/resolveVote/confirmEnded/startDiscussion 五处阶段切换各发 SYSTEM 级 announcement（channel=system，banner 显示阶段切换，含剧本名）；announcement 全局横幅通道与 script_phase SSE 会话面板通道并存（mock SSEController verify 双通道各自收到）
- **首轮 5 失败（本批次引入，已修复）**：①3 用例——单测未手动 flush（@PostConstruct 调度器仅 Spring 容器启动，与 AnnouncementServiceTest 同模式），修复：测试内显式 flush；②2 用例——阶段广播同 coalesceKey「system|system」同窗合并（investigation+discussion 快速连发被并成 ×N），修复：broadcastSystemAnnouncement 改用 phase 级 coalesceKey（script_phase|<phase>），阶段切换是离散横幅事件不应合并
- **回归**：既有 176 基线零破坏（含 LongTextStabilityTest LONG-01 10 万字 6.4s 全绿，本次无堆脆性触发）
- **前端**：npm run build 通过（tsc -b && vite build，55 modules，index-CcvzWufL.js），ScenePage demo 面板新增「方案A（回调判定）/方案B（内联区域广播）」切换 chips + 当前模式展示 + GET/POST /api/announcements/mode 封装（client.ts broadcastModeGet/broadcastModeSet）；产物已同步 static/（index.html → index-CcvzWufL.js）
- **git**：未 commit（等主人确认）

### 2026-08-01 11:30–11:58 — 2D 视觉系统 demo 集成进工程 + git 同步（Node 自测，非 Java 测试）
- **范围**：`demo/vision/` 4 文件迁移 → `src/main/resources/static/simulation/vision/`（vision_core.js / vision_demo.html / vision_core.test.js / vision_sim_smoke.js）；旧 `demo/vision/` 已删除（git 未跟踪，避免双份）；`README.md` demo 行路径已更新。零 Java 改动、零前端构建改动——保持纯静态直引（与既有 `static/simulation.html` 同模式，不纳入 Vite 构建）
- **自测命令与结果（新位置复跑）**：
  - `node src/main/resources/static/simulation/vision/vision_core.test.js` → **28/28 通过**（与迁移前一致：Liang-Barsky 求交 6 / LOS 5 / 可见性全分支 9 / 多边形 3 / 碰撞 2）
  - `node src/main/resources/static/simulation/vision/vision_sim_smoke.js` → **12/12 通过**（初始视角分布 4 / 草丛不对称 3 / 迷雾 2 / 行为闭环 2 / 20s 稳定性 1）
  - vision_demo.html 引用核对：唯一外部引用 `<script src="vision_core.js">`（同目录相对），http 与 file:// 均可用
- **手动验收（demo 如何自测）**：工程内访问 `http://localhost:8000/simulation/vision/vision_demo.html`（后端运行时，静态资源默认挂载）或直接双击 `D:\roleplay-java\src\main\resources\static\simulation\vision\vision_demo.html`（file:// 直开）→ 初始应看到：玩家蓝色视野扇形穿门缝、草丛边「影卫·青」绿圈高亮、墙后「守卫·铁」淡出 + 红虚线 + 「墙后」标签、右上「猎手·风」灰线（太远）、左上「巡游·金」在视野角外；WASD / 点击移动——进迷雾（视野缩小、雾中实体变淡）、躲草丛（右侧 AI 卡片显示「目标藏在草丛中」、AI 不追击）、走出草丛（事件流出现「开始追击」+ 红色感知线）；面板滑块调视野角 / 范围、复选框开关迷雾 / 草丛 / AI 视野锥 / 连线；空格暂停
- **git**：commit `c7f95f4`（feat: 集成 2D 视觉系统 demo——障碍物视线遮挡/迷雾/草丛不对称视觉）——**仅含 4 个新增 demo 文件**（1224 行）；并行批次 A~C4 的代码与文档改动未纳入本次提交（保持未提交状态）；push 状态：origin 存在（github.com/shuweiran/roleplay-java.git），推送结果见交付报告

### 2026-08-01 09:50–10:05 — 批次C4 DM面板+重连UI（Round 9，176 tests）
- **命令**：`mvn test`（全量，两轮：首轮 1 失败为既有堆测量脆性 → 单独复跑通过 → 全量复跑全绿）
- **结果**：176 tests / **0 failures** / 25 类 BUILD SUCCESS
- **新增**：ScriptGameDmTest 6 用例——C4-1 dmStatus 全量视图（3 玩家全量：角色/秘密（全部 3 条可见）/AP/线索数（搜证后实时反映）/投票状态/roleKey 与服务层一致 + truth/killer_id/approval_status）；C4-2 dmStatus 未知对局 → error；C4-3 advance 状态机推进 INVESTIGATION→DISCUSSION（接讨论引擎）→VOTE（C3 已知限制“恢复后 DM 手动推进”入口）；C4-4 advance VOTE→REVEAL 经 D7 审批门（挂起 pending → 批准 → REVEAL）→REVEAL→ENDED → ENDED 幂等终态不越界 + ENDED 下 dmStatus 可读；C4-5 controller 层 DM key 越权（未配置放开 200；配置后 无/错 X-DM-Key → 403、正确 → 200，advance 同门）；C4-6 advance 未知对局 / 缺 session_id → error
- **首轮 1 失败（既有堆测量脆性，非本次引入）**：`LongTextStabilityTest.longContextStability` 堆增长 34.8% > 30% —— 与批次广播 Round 5 同性质（37.4% 单独复跑 11.2% 通过）；本轮单独复跑 1/1 通过，全量复跑 176/0 全绿，判定为 GC 时机受全量用例同 JVM 影响的测量脆性（未触碰 memory/compressor 链）
- **回归**：既有 170 基线（批次 C3 8 用例、C2 9 用例、C1 7 用例、批次 B 4 用例、GAP-3 4 用例、D6/D7 12 用例）零破坏；LONG-01 10 万字 3.3s 全绿
- **验证细节**：dmStatus 为 DM 专用全量视图（不脱敏，越权由 controller X-DM-Key 门承担，roleplay.game.dm.key 空=放开与审批门同模式）；advancePhase VOTE 步复用既有 resolveVote（含审批门/平票回滚语义），REVEAL 步复用 confirmEnded；advance 响应附加 advanced 键 + phase 键（resolveVote 响应无 phase 键，已补）；前端 npm run build 通过（55 modules，index-DFAHiAkk.js 已同步 static/），bundle grep 确认 dm/status、script/advance、script/resume、主持人面板、恢复对局、roleKey、批准揭晓、X-DM-Key 均在产物；mvn compile 已刷新 target/classes
- **git**：未 commit（等主人确认）

### 2026-08-01 09:15–09:45 — 批次C3 断线重连与会话恢复（Round 8，170 tests）
- **命令**：`mvn test`（全量，两轮：首轮 1 失败 → 修复后复跑全绿）
- **结果**：170 tests / **0 failures** / 24 类 BUILD SUCCESS
- **新增**：ScriptGameResumeTest 8 用例——C3-1 roleKey 生成（每玩家唯一非空；toMap 仅向本人暴露 role_key，他人/匿名视图不含）；C3-2 player_key 校验（匹配通过/错误 key 拒绝「身份校验失败」/跨玩家 key 拒绝/空 key 向后兼容）；C3-3 内存对局 resume 直接返回（restored=false，带本人 role_key）；C3-4 快照恢复（模拟清内存后 resume 重建：phase/AP 3→2/线索 c1/票型与清内存前完全一致，恢复后可继续投票）；C3-5 恢复视图脱敏（只见自己的 secret，不泄露他人；非公开线索仅持有者可见，公开线索全员可见）；C3-6 ENDED 终态恢复（内存与快照两路径均返回 terminal/murderer/correct/truth/votes/winner）；C3-7 resume 拒绝（未知对局无快照/错误 key/空标识）；C3-8 controller 层（init 绑定 room_code 回显 → resume 按房间码定位；status/search 错误 key → 403、正确 key → 200；无 key 兼容；DM /keys 端点全员令牌）
- **首轮 1 失败（共享库暴露的既有测试假设，已修复）**：ScriptPersistenceTest.scriptPersistedAfterGameEnds——`findFirst()` 取到共享 H2 内存库（DB_CLOSE_DELAY=-1，全量测试类同库）中批次 C3 新增的 type=result 行（killer 不同）→ 断言失败；修复：result 行过滤补 `session_id == 本对局` 条件（此前依赖“唯一 result 行”假设，批次 C3 成为第二个落 result 行的 @SpringBootTest 类后暴露）；断言语义不变（players/killer/votes/correct/truth 仍对本局结果行校验）
- **回归**：既有 162 基线零破坏（批次 C2 9 用例、批次 C1 7 用例、批次 B 4 用例、GAP-3 4 用例、D6/D7 12 用例）；LONG-01 10 万字 3.2s 全绿
- **验证细节**：快照走 ScriptEntity（type=snapshot 行，name 前缀「对局快照:<sessionId>」鉴别，无表结构变更）；roleKey 为 UUID 随机串；玩家级端点均向后兼容（无 key 按玩家名）；未动狼人杀/中断/对话引擎/AP 转交链路；ScriptController 新增 resume/keys 端点 + init 可选 room_code（附加键，前端契约不破坏）；RoomController 未动
- **git**：未 commit（等主人确认）

### 2026-08-01 09:30–09:55 — 批次C2 搜证增强 AP+转交（Round 7，162 tests）
- **命令**：`mvn test`（全量；新测试类先行单测 9/9，首轮 1 失败 → 修复后全量复跑）
- **结果**：162 tests / **0 failures** / 23 类 BUILD SUCCESS
- **新增**：ScriptGameApTransferTest 9 用例——C2-1 AP 初始化（v1 侦探 ap_bonus=2 → 初始 5 AP，管家 0 → 3 AP；toMap 暴露 ap/ap_max/ap_pool）+ 旧剧本无 ap_bonus → 全员 3 AP 兼容；C2-2 搜证扣 AP（客厅 c1(1AP) 后 3→2，书房 c2(2AP) 后 2→0，公开线索不扣 AP，同地点不重复得）；C2-3 AP 不足拒绝（需要 1 AP 当前 0：整次拒绝、不部分授予、AP 不变、线索不发放）；C2-4 转交成功（ownership 变更：源移除/目标加入，接收方 status/my_clues 可见转入线索，原持有者不可见）；C2-5 转交拒绝 5 类（非持有者归属校验/目标不存在/转给自己/transferable=false/投票阶段守卫）；C2-6 schema 兼容（旧剧本 ap_cost→1、ap_bonus→0；v1 字段透传）；C2-7 端到端（搜证→AP 变化→转交→status 可见 + ap_pool 反映）
- **首轮 1 失败（本次改动引入，已修复）**：searchDeductsApAndGrantsClues——搜证“无线索”分支（公开线索-only 地点）响应未带 `ap` 键，断言公开线索不扣 AP 时拿 null；修复：该分支补 ap/ap_cost=0 返回（前端展示一致性）
- **回归**：既有 153 基线（含批次 C1 7 用例、批次 B 4 用例、GAP-3 4 用例、D6/D7 12 用例）零破坏；LONG-01 10 万字 3.5s 全绿；ScriptGameEndedTest 的 search 拒绝断言（ENDED 后“当前不是搜证阶段”）保留通过
- **验证细节**：D6 判定/讨论引擎/投票/结算链路零改动（transferClue 仅状态变更不经过对话引擎）；schema 契约文档 docs/剧本-schema-v1.md 已同步 ap_cost/ap_bonus 字段表+兼容规则+Chronos 差异表（ap_cost/transferable 由“裁剪”转“已支持”）；AP 基础值可配 roleplay.game.ap.base（默认 3）
- **前端**：npm run build 通过（tsc -b && vite build，54 modules）；bundle grep 确认 行动点/线索转交/我持有的线索/transfer_clue/my_clues/transferable 均在产物；dist 已同步 static/（index.html → index-DkvIhlhJ.js），mvn compile 已刷新 target/classes
- **git**：未 commit（等主人确认）

### 2026-08-01 09:30 — 2D 视觉系统 demo（Node 自测，非 Java 测试）
- **范围**：新增 `demo/vision/`（vision_core.js / vision_demo.html / vision_core.test.js / vision_sim_smoke.js）——纯前端 demo，**零 Java 改动，不影响 139 个 JUnit 测试基线**（未运行 mvn，未动 src/、static/、roleplay-v4/）
- **自测命令与结果**：
  - `node demo/vision/vision_core.test.js` → **28/28 通过**（Liang-Barsky 线段-AABB 求交 6 项、LOS 视线通畅 5 项、可见性判定全分支 9 项：VISIBLE / OUT_OF_RANGE / OUT_OF_FOV / BLOCKED / IN_GRASS / FOG_DIM、视野多边形 3 项、圆-矩形碰撞 2 项）
  - `node demo/vision/vision_sim_smoke.js` → **12/12 通过**（初始视角分布 4 项：门缝可见 / 墙后遮挡淡出 / 太远 / 视野角外；草丛不对称视觉 3 项；迷雾 2 项；AI 行为闭环 2 项：暴露→追击·躲草→丢失目标、听觉→搜寻；20s×400 帧连续模拟无 NaN / 不出界 / 状态机不抛错）
  - HTML 内联脚本 `node --check` 语法通过；控件 id 与 getElementById 引用一一对应核对
- **手动验收（demo 如何自测）**：浏览器双击打开 `D:\roleplay-java\demo\vision\vision_demo.html`（file:// 直开，无后端依赖）→ 初始应看到：玩家蓝色视野扇形穿门缝、草丛边「影卫·青」绿圈高亮、墙后「守卫·铁」淡出 + 红虚线 + 「墙后」标签、右上「猎手·风」灰线（太远）、左上「巡游·金」在视野角外；WASD / 点击移动——进迷雾（视野缩小、雾中实体变淡）、躲草丛（右侧 AI 卡片显示「目标藏在草丛中」、AI 不追击）、走出草丛（事件流出现「开始追击」+ 红色感知线）；面板滑块调视野角 / 范围、复选框开关迷雾 / 草丛 / AI 视野锥 / 连线；空格暂停
- **git**：未 commit（等主人确认）

### 2026-08-01 08:55 — 批次B ENDED/落库/SSE（Round 5，139 tests）
- **命令**：`mvn test`（全量）
- **结果**：139 tests / **0 failures** / 19 类 BUILD SUCCESS
- **新增**：ScriptGameEndedTest 3 用例（A4-4 判定流程结束 confirmEnded→phase==ENDED 且终态不越界 / A4-4 边界非 REVEAL 拒绝 / GAP-8 script_phase·script_status·script_reveal 推送 + saveScript 双落库调用点）+ ScriptPersistenceTest 1 用例（A4-3 @SpringBootTest + H2 mem 真实落库：对局结束后 ScriptRepository.findAll() 非空，结果含 players/killer/votes/correct/truth，剧本条目与结果条目双存在）
- **回归**：既有 135 用例零破坏（含 ScriptGameServiceTest 12 用例 D6/D7 断言 phase==REVEAL 语义保留、ScriptGameDiscussionTest 4 用例、LONG-01 10 万字）
- **验证细节**：A4-3 走 Spring 注入路径（真实 DatabaseService/SSEController + @MockBean LLMClient），审批门后台揭晓+批准；LONG-01 P50=4ms 仍全绿；前端 npm run build 通过并同步 static/（index.html hash → index-DaY9PB9Y.js）
- **git**：未 commit（等主人确认）

### 2026-08-01 09:05–09:35 — 演讲+广播合并地基批次（Round 5，146 tests）
- **命令**：`mvn test`（全量，两轮）
- **结果**：146 tests / **0 failures** / 20 类 BUILD SUCCESS
- **新增**：AnnouncementServiceTest 7 用例（优先级 SYSTEM>EVENT>PLAYER>NPC 乱序入队按序出队 / 滑动窗口节流第 6 条丢弃 / 同 key 合并×N / AI 自动选择演讲(area) vs 全局公告 / WorldEventBus TYPE_ANNOUNCEMENT 进程内分发 / SSE 载荷完整字段 / recentSince 断线补发）
- **首轮 1 失败（偶发）**：`LongTextStabilityTest.longContextStability` 堆增长 37.4% > 30% 阈值——**单独复跑 11.2% 通过，全量复跑再次 0 失败**，判定为堆测量脆性（GC 时机受全量 146 用例同 JVM 影响），非本次改动引入（本次未触碰 memory/compressor 链）；与 Round 2 ApprovalServiceTest 偶发同性质
- **回归**：既有 139 基线（含并行批次 B 4 用例）零破坏
- **前端**：npm run build 通过（tsc -b && vite build，54 modules）；产物 CSS 含 ann-banner 样式 grep 确认；dist 已同步 static/（index-DtxShSfZ.js / index-B4JvPABx.css），mvn compile 已刷新 target/classes
- **git**：未 commit（等主人确认）

### 2026-08-01 09:00–09:10 — 批次C1 剧本 schema v1（Round 6，153 tests）
- **命令**：`mvn test`（全量，两轮：首轮 2 失败 → 修复后复跑）
- **结果**：153 tests / **0 failures** / 22 类 BUILD SUCCESS
- **新增**：ScriptSchemaV1Test 7 用例——C1-1a 旧格式（roles 字符串/clues 带 public/无 metadata）归一 v1：schema_version==1、metadata/roles[]/clues[]/secrets/killer_id 字段齐全、secrets 键集合==roles、clue 兼容派生键 public/related_role 保留；C1-2 v1 格式透传（killer_id/is_hidden/intro/tags/transferable/visible_to_owner_only 保留）；C1-3 LLM 空输出 → defaultScript 兜底符合 schema（A1-3 回归：secrets 键集合==roles）；C1-4a 双生成器一致性（同输入 generateScript 与 initGame.getScriptSchema 输出 title/roles/secrets/clues/locations/truth 全一致）；C1-4b initGame 状态与 schema 对齐；C1-5a roles/clues 缺失兑底；C1-5b killer_id 落入 game.killerId + 旧 killer 角色名反查 id
- **首轮 2 失败（本次改动引入，已修复）**：ScriptGameDiscussionTest.secretContextDoesNotContainSecretPlaintext / secretHolderGetsInjectedGoal——根因：normalize 对 secrets 全量兑底，导致 mock 中本无秘密的女仆/园丁被塞入兑底秘密 → 全员变持秘密 → 破坏 A3-2/A3-4 的 WEAK/MERGED 轨道语义；修复：secrets 兑底策略改为**仅当所有角色均无秘密时全量兑底**（部分秘密保持部分，不臆造），回归通过
- **回归**：ScriptGameServiceTest 6 / ScriptGameEndedTest 3 / ScriptPersistenceTest 1 / ScriptGameDiscussionTest 4 / AnnouncementServiceTest 7 全过；LONG-01 10 万字 3.4s 全绿；既有 139 基线（含并行批次 B 4 用例）零破坏
- **验证细节**：与并行批次（演讲+广播，#37）零文件重叠（我方：ScriptSchemaV1/ScriptService/ScriptGameService/新测试/文档）；ScriptEntity 未加列（schema_version 内嵌 contentJson）；D6 判定路径零改动
- **git**：未 commit（等主人确认）

### 2026-08-01 08:45 — GAP-3 讨论引擎批次（Round 4，135 tests）
- **命令**：`mvn test`（全量）
- **结果**：135 tests / **0 failures** / 18 类 BUILD SUCCESS
- **新增**：ScriptGameDiscussionTest 4 用例（A3-1 建组+phase==DISCUSSION / A3-2 持秘密角色上下文无秘密明文+WEAK 摘要 / A3-3 讨论结束自动进 VOTE / A3-4 目标注入隐藏秘密·查明真相），对应蓝图 Step 3v 验收标准
- **回归**：既有 131 用例零破坏（含 ScriptGameServiceTest 12 用例、TrackStrategyTest 7 用例、SimulationOrchestratorTest 等）
- **验证细节**：讨论引擎 3 成员×2 轮=6 turns 正常落盘；A3-1 断言窗口用 mock callSync 50ms/次保障（与既有 D7 测试 sleep(150) 同风格）；LONG-01 10 万字 6.9s 仍全绿
- **git**：未 commit（等主人确认）

### 2026-07-31 14:42 — 全量回归（Round 0-1，93 tests）
- 新增 5 类 9 用例（LONG-01/02/03、阈值边界、secret override）全绿

### 2026-07-31 21:28-21:29 — 全量回归（Round 2，131 tests）
- **命令**：`mvn test`（全量）
- **结果**：131 tests / **0 failures** / 17 类
- **首跑 1 失败**：`ApprovalServiceTest.testGetPendingResult`（expected not null）——**确认偶发时序**：测试用 `CompletableFuture.supplyAsync` + `Thread.sleep(100)`，慢机器上 future 未在 100ms 内执行到 put；单独复跑 11/11 通过，全量重跑 0 失败
- **记录**：该测试时序脆弱（sleep(100) 依赖调度），建议后续改 CountDownLatch 同步等待（P2 测试加固）
- **LONG-01**：P50=4ms P95=9ms 堆稳定 ✅

### 2026-07-31 21:35–21:47 — D25-D27 修复批次（Round 3）
- **修复**：D25（21:35，#28）AppConfig 加 @ConfigurationProperties(prefix=roleplay) + 补 25 setter，启动即读 ROLEPLAY_LLM_API_KEY（configured=True）；D26（21:47，#29）fallback-model/timeout-seconds 迁移 roleplay.monitor.*（仅动 yml）；D27（21:47，#30）GameConfig/ApprovalConfig 嵌套类 + ApprovalService 双轨构造注入（enabled/timeout 配置生效）
- **G1 解除**：key 配置于用户环境变量 ROLEPLAY_LLM_API_KEY，D25 后启动自动读取，不再需运行时注入；真实对话/多会话/turns/SSE 真机验证全 PASS（18:50），真实对话 14.3s 验证（21:35）
- **回归**：全量 131 tests / 0 failures / 17 类 BUILD SUCCESS（含 ApprovalServiceTest 11 用例回归，见台账 #28 核查）
- **git**：e732397（D25-D27），今日累计 9 commit

### 2026-07-31 17:56 — 并行工作流提交（未在本台账登记，补记）
- 剧本杀 D6/D7 测试（ScriptGameServiceTest 12 用例）+ D15 stress 脚本 + 中断系统测试（提交 bdf0d59/17941da）

---

## 测试方案对照（覆盖矩阵状态）

| 区域 | 覆盖 | 缺口 |
|---|---|---|
| 核心引擎 | 审批/Hook/MCP/压缩链/DB | SessionController/AuthController/ConfigController 集成测试待补 |
| 2D 模拟 | Track 6 类 + Movement + 编排 | SimulationWorld/SpatialGrid/HearingSystem 单元测试待补 |
| 铁轨 | ✅ 全覆盖 | EavesdropSummarizer LLM 分支 |
| 剧本杀 | ✅ D6/D7 覆盖（12 用例）+ GAP-3 讨论（4 用例）+ Step 4v/GAP-8（4 用例） | 狼人杀全流程/前端 Vitest 待补 |
| 狼人杀 | 无 | WerewolfService 状态机全流程测试待落地 |
| 前端 | 手动验证（2D 页实测通过） | Vitest 基建缺失 |

## 已知测试环境注意
- **G1 LLM key（已解除 2026-07-31 21:35，台账 #28）**：用户级环境变量 `ROLEPLAY_LLM_API_KEY`（len=35）；D25 修复后 AppConfig 加 @ConfigurationProperties 绑定 roleplay.*，启动自动读取（configured=True），重启无需重新注入；运行时 POST /api/config/apikey 仍可覆盖（重启后由环境变量恢复）
- **G2 8000 端口**：禁止 spring-boot:run；测试 RANDOM_PORT 隔离
- **PowerShell 中文 JSON → GBK 乱码**：用 Python UTF-8 发请求
- 测试必须串行；`ApprovalServiceTest.testGetPendingResult` 偶发时序脆弱（P2 加固）

### 2026-08-01 12:27–13:10 — 合并方案正式版 merged 落地（Round 11，190 tests）
- **命令**：`mvn test`（全量）
- **结果**：190 tests / **0 failures** / 28 类 / BUILD SUCCESS
- **新增**：MergedSpeechModeTest 7 用例——①声学判定正确性：HearingSystem.countHearingListeners 单事实源（近处 50px 可听=1 / 950px 外=0）；merged 半径内可听→区域演讲（area+坐标 100,100+半径 200+形态 speech）；无听众+fallback=true（默认）→升级全局公告（channel=global）；无听众+fallback=false（AppConfig 改值接线验证）→不升级仅区域演讲（area，纯空间语义）；②剧本杀阶段 SYSTEM 广播：merged 默认（speechMode==merged 断言）下 initGame 即发 SYSTEM/system 广播（无条件启用不再依赖 split）；script-phase-broadcast=false 总开关静默；③防双发回归：merged 下 SpeechStrategy.processResults 不内联推送（内联只属于 split，回调是唯一通道）
- **既有测试适配**：SpeechStrategySplitModeTest 默认断言 auto→merged（merged 静默语义同 auto）、运行时切换用例改 merged↔split（3 处）；其余 183 基线零改动零破坏（含 LongTextStabilityTest LONG-01 10 万字 6.3s 全绿，无堆脆性触发）
- **配置接线验证**：AppConfig.BroadcastConfig 新增 fallbackToGlobal/scriptPhaseBroadcast 字段+setter（Spring @ConfigurationProperties 绑定 roleplay.broadcast.*），yml 双键（speech-mode: merged / fallback-to-global: true / script-phase-broadcast: true），AnnouncementService 构造读取；测试直构 AppConfig 改值即生效（fallback=false、script-phase-broadcast=false 两用例）
- **前端**：npm run build 通过（tsc -b && vite build，55 modules，index-OlgI0-rr.js），ScenePage demo 面板新增「⭐ 正式版（merged）」chip（标注正式版默认选中）+ 方案A/B chips 标注回退对比 + 模式说明更新；client.ts broadcastModeGet/broadcastModeSet 复用（注释更新三值）；产物已同步 static/（index.html → index-OlgI0-rr.js），bundle 字节级校验：正式版（merged）/方案A（回调判定）/方案B（内联区域广播）/broadcastModeGet/broadcastModeSet 均在产物
- **git**：未 commit（未获授权）

### 2026-08-01 12:49–13:05 — merged 正式版真机验证（台账 #47，7 项 PASS，非 JUnit）
- **范围**：无源码改动；`target/roleplay-engine-1.0.0-SNAPSHOT.jar` 重新打包（mvn package -DskipTests，12:51:42）；8000 实例重启（java -jar，pid 22664，Tomcat 8.8s 启动，4 角色/3 场景加载）；测试辅助脚本 `target/test-merged.ps1` / `test-merged2.ps1` / `test-script-phase.ps1`（target 构建目录，不入源码）
- **结果**：**7 项全 PASS**——①GET /api/announcements/mode=merged（正式版默认）②玩家广播 POST /api/announcements（PLAYER/global + SYSTEM/system）入队成功、coalesce_key 正确 ③AI 演讲 POST /api/simulation/speech（2D 模拟 init 2 角色后）has_audience=false → fallback 升级全局公告（声学判定 + 兜底配置真实生效）④断线补发 GET /api/announcements/recent 环形缓冲 3→5 条（含 AI 演讲 + 两条 SYSTEM 阶段公告）⑤模式切换 round-trip merged→split→merged 互斥生效 ⑥剧本杀阶段广播：script/init→【搜证阶段】、start_discussion→【讨论阶段】SYSTEM 公告（speaker=system/channel=system）⑦SSE /api/events 抓流确认 event:announcement 两条完整推送（UTF-8 正文正确）+ script_phase/script_status 并存正常
- **备注**：recent 首测为空系 flush 100ms 周期时序假象（sleep 2s 后正常），非缺陷；AI 演讲在 2D 模拟未 init 时报业务错误（合理前置校验）
- **git**：未 commit（未获授权）


### 2026-08-01 12:43-13:40 — Phaser 阶段0 验证 demo（Node/Python/Edge headless 自测，非 JUnit；台账 #49）
- **范围**：新增 src/main/resources/static/simulation/phaser_validate/（纯静态 demo，零 Java/前端构建改动）+ docs/地图JSON契约-draft.md（未衡审核通过后已更名 `地图JSON契约-v1.md`）；Java 测试基线不变（190/0，未运行 mvn）
- **自测命令与结果**（demo 目录下执行）：
  - python tools/self_test.py http://127.0.0.1:8899（需先 python -m http.server 8899）→ **http 模式 5/5 页签 ALL PASS**：①瓦片渲染+碰撞（stats 面板：老宅 20×14 / ground 行数 14×列数 20 / 碰撞格 160 / 1 玩家+3 AI / canvas 已建）②BSP 分区（生成器/校验器 ✅ 通过/map_version 1/json-bsp 预览/canvas）③Zone 热点（热点数/互动方式/线索绑定/clue-box/canvas）④Aseprite 动画（「4 个动画已创建」（createFromAseprite）+ load.aseprite + spritesheet 管线/canvas）⑤地图 JSON 契约（schema-table/校验器输入输出/contract-json 样例）；每页签 boot-errors 为空（无 JS 异常）
  - python tools/self_test_file.py → **file:// 模式 5/5 ALL PASS**（内嵌 base64 资源兜底：浏览器禁 file:// XHR，检测 protocol 后自动切换 js/assets_embedded.js 的 data URI）
  - python tools/self_test_lifecycle.py → **生命周期轮巡 ALL PASS**：?selftest=cycle 自动切换 5 页签，每个 Phaser Game 在切换时 destroy(true) + 重建，prev destroyed=Y ×4、current created=Y、无 JS 异常——对应迁移计划风险表「React 内嵌 Phaser 生命周期冲突」的缓解实证（阶段 1 React 集成采用 Ref 挂载 + 显式 destroy 同模式）
  - Node 侧（
ode -e / 
ode --check）：bsp.js 多 seed（20260801/1/42/999）生成+validateMap 全过、BFS 玩家出生点全房间可达；坏 JSON 检出（热点埋墙/出生点越界/碰撞值非 0/1）；gen_assets.js 生成 PNG 解码验证（尺寸/像素/帧键）；全部 js 语法检查通过
- **手动验收（demo 如何自测）**：
  - 打开：双击 D:\roleplay-java\src\main\resources\static\simulation\phaser_validate\index.html（file:// 直开）或 http://localhost:8000/simulation/phaser_validate/index.html（**运行中 8000 实例需下一轮打包重启后生效**，见 README；本地 python -m http.server 即时可用）
  - ①瓦片渲染+碰撞：WASD 移动，3 个红色 AI 漫游——玩家/AI 均被墙体阻挡（墙体验证）
  - ②BSP 分区：打开即见生成地图（固定 seed 可复现），右侧 JSON 预览 + 校验器结果；「重新生成」随机 seed 再跑
  - ③Zone 热点：金色区域=搜证点；走近出现提示条（onEnter），点击区域或按 E 弹出线索文本并计入已搜证（onInteract）
  - ④Aseprite 动画：WASD 移动角色1（aseprite 管线）、方向键移动角色2（spritesheet 管线）；走动切方向动画、静止回 idle 帧；底部状态行显示当前动画
  - ⑤地图 JSON 契约：字段表 + manor/bsp 样例切换 + 校验器（粘贴 JSON → 校验 → 错误/警告列表）
- **技术取舍**：本地 vendor 直引 Phaser 3.90（不依赖 CDN；CDN 备选 jsdelivr 已注明）；file:// 内嵌资源兜底 vs http 真实文件管线；素材为程序生成占位（零第三方版权，tools/gen_assets.js 可复现）；Aseprite JSON 帧键须为 0..15（Phaser 3.90 createFromAseprite 按索引串解析，源码核实）
- **Java 测试基线**：未触碰，保持 190/0（28 类）
- **git**：未 commit（未获授权）


### 2026-08-01 13:36-14:00 — Phaser 阶段1 ScenePage 渲染层换 Phaser（Node/Python/Edge headless 自测，非 JUnit；台账 #55）
- **范围**：前端 Only，后端 Java 零改动：新增 `roleplay-v4/frontend/src/phaser/`（SimulationScene.ts 渲染层 / PhaserSimulationView.tsx React 组件 / simulationData.ts 数据适配）；改 `ScenePage.tsx`（新增「2D 模拟（Phaser 内嵌）」按钮 + 内嵌渲染区域 + 回退按钮，原 window.open 保留）；新增 tools/ phaser_smoke.html + phaser_integration_smoke.html + self_test_stage1.py（冒烟）；构建产物同步 static/（index.html → index-B2ueyU_u.js）
- **自测命令与结果**（前端目录 frontend 下执行）：
  - ① `npm run build` 通过（tsc -b && vite build，60 modules，含 Phaser 1.79MB bundle——对齐迁移计划风险表「Phaser 体积 ~1MB 级低风险」）
  - ② `python tools/self_test_stage1.py http://localhost:5173` → **纯渲染冒烟 10/10 ALL PASS**（phaser-version 3.90.0 / game-create / agents-render=3 / obstacles-render=2 / agents-removal=2 / game-destroy 收敛（pendingDestroy→runDestroy→canvas 移除）/ game-recreate（StrictMode double-mount 模拟）/ data-smoke-ok=1 无 JS 异常）
  - ③ Edge headless → **真实后端集成冒烟 9/9 PASS**（phaser_integration_smoke.html 全部 data-smoke-ok=1：load-characters 2 角色 200 / start 200 / state agents=2 / Phaser 渲染 agents=2 / SSE world_snapshot 增量 / stop 200 / destroy 收敛 / React 组件层 PhaserSimulationView StrictMode 双挂载 canvas=1 + 数据流 agents≥2 + unmount 后画布移除）
  - ④ **数据流不变**：ScenePage 新入口和 PhaserSimulationView 消费与原 simulation.html 相同的 /api/simulation/* REST+SSE 端点，appStore/useSSE/App.tsx 零改动（git diff 仅前端 phaser/ + ScenePage.tsx + tools/）
- **阐述说明**：渐进式路线——ScenePage 新增「2D 模拟（Phaser 内嵌）」按钮 → PhaserSimulationView 内嵌渲染（默认 Phaser 3.90）→ 回退通道：原「进入 2D 模拟」checkbox + window.open('/simulation.html') 保留（新界面提供「原版窗口（回退）」按钮）；切换开关 = ScenePage 上的两个入口并存（渐进式原则）
- **Java 测试基线**：未触碰，保持 214/0（29 类）（并行批次 D 已在 13:39 报 214/0）；本次无 mvn 重跑（前端测试）
- **git**：未 commit（未获授权）；并行登记 P-0801-B 与台账 #52 相关，不动并行批次 D 的 8 个 Java 文件
- **残留**：原 window.open 自研 Canvas 渲染（simulation.html 791 行）保留不删（回退通道）；任何前端改动都经 tsc 检查 + 构建冒烟
