> ⚠️ 本文件较大（约 52 KB），agent 请按需搜索读取，勿整体加载

# TEST_STATUS.md — 测试状态台账（AI 必读③，持续更新）

> ⚠️ **规则**：每次执行测试后**必须更新本文件**（追加记录 + 更新汇总）。测试通过就写入，失败也写（含原因），保持诚实。
> 执行命令：`cd D:\roleplay-java && C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd test`

---

## 📊 当前基线（最新汇总，2026-08-04 13:5x）

| 指标 | 值 |
|---|---|
| 测试类 | **58** |
| 测试用例 | **412（407 基线零破坏 + MovementConstraintTest GroupAnchor 新增 2 + LongSimulationConcurrentTest LONG-04 新增 3）** |
| Failures / Errors | **0 / 0** |
| 最后全量执行 | 2026-08-04 13:4x（**P-0804-A 授权批次：全量 412/0 BUILD SUCCESS**，58 类 surefire 汇总；首轮 LONG-01 堆 32.3%>30% 为既有脆性 #37/#40/#41 同款，单跑 PASS 后复跑全绿；GroupAnchor leader+follow slot 完整实现 + BSP seed 配置化 + LONG-04 并发稳定性；详见 v41） |
| 环境 | H2 mem + mock LLM（application-test.yml） |
| 真机验证 | **merged 正式版 7 项 PASS**（2026-08-01 13:05，台账 #47）；**P1 剧本生成 maxTokens 修复 4/4 完整生成 PASS**（2026-08-01 18:45，台账 #57：真实 LLM 生成完整 schema v1 剧本 4/4 次，不再走 defaultScript 兜底；讨论自动进 VOTE 且发言多样）；**Phaser 阶段2 已通过未衡终审（2026-08-01 20:4x，三阶段全闭环，8000 重启生效 PID 25760，见 v15）** |

> v41 更新（2026-08-04 13:5x，**P-0804-A 授权批次**），基线 407 → **412 tests / 58 类**：①**后端**——`MovementConstraint` GroupAnchor 完整实现（MERGED 组 leader=字典序最小名 + follow slot 直线队形 SLOT_SPACING=16，leader 收敛组质心、移动后槽位重算；MovementConstraintTest 11→13 用例改写/新增，WEAK/ISOLATED 零改动）；`ScriptMapService` bsp-seed @Value 注入（yml 双份 roleplay.game.map.bsp-seed，缺省回退 DEFAULT_BSP_SEED=20260801 零行为变化）；新增 `stability/LongSimulationConcurrentTest`（LONG-04 并发模拟稳定性 3 用例：2D 世界定时 tick + 8 线程并发 compute/apply 1000 迭代，断言坐标有限有界/无死锁/无丢任务）。②**前端**——useSSE 断线补发（重连成功后 announcementRecent(since) 拉取重放，P3 关闭）、scriptInit +roomCode 联机房剧本杀接线、scriptMap +width/height + 剧本杀设置页地图区 宽/高/seed UI 暴露（D-031/D-035 关闭）、SILENCE_MARKER 静默渲染（utils/silenceMarker.tsx + 狼人杀讨论面板/2D 侧列表灰色斜体，D-022 关闭）、删除 ChatPage.tsx.bak。③**⚠️ 事故修复**——本批误用 PowerShell `Set-Content -NoNewline` 处理导致 ChatPage.tsx / PhaserSimulationView.tsx UTF-8 中文编码损坏（非法 GBK 对→`?` 有损，丢失中文字符尾字节+后续 ASCII 引号/`$`/`<`/换行），已从 static bundle（index-StVXWN_F.js，含全部原始 UI 串）逐处匹配恢复全部中文字符串 + 逐行修复结构（缺失引号/`}`/`</`/`$`/换行 40+ 处）→ **tsc -b 全绿 exit 0**；npm run build 66 modules → static 同步 index-Bdi8t8bk.js + index-CpI7G5E6.css（SHA256 dist↔static 一致，index.html 引用更新，删除旧产物）。④**验证**——全量 mvn 412/0（首轮 LONG-01 堆 32.3%>30% 为既有脆性 #37/#40/#41 同款，单跑 PASS 后复跑全绿）；禁动文件（RouterService/ArbiterService/审批/狼人杀/剧本杀 Service/SSE 主链路）零改动；详见 Round 41 / 台账 #101 / DECISION_LOG D-036

> v40 更新（2026-08-03 24:5x，**P-0803-O 两条地图链路 LLM 全量生成批次**（主人需求「都加上 llm全量生成」），基线 403 → **407 tests / 58 类**）：①**后端 SceneController.generateDefaultMap 双模式**——POST /api/scenes/map body 可选 theme：非空 → ScriptMapService LLM 全量生成统一路径（LLM 完整输出 ground+collision 双层数组 + rooms/zones/spawns → 契约 v1 校验 → 失败/超预算 BSP 兜底），响应附加 mode/generator/validation/fallback 溯源键；空/缺省 → P-0803-H BSP 确定性零回归。注入可行性已核实：ScriptMapService 为 @Service 仅依赖 LLMClient，SceneController→ScriptMapService→LLMClient 无环，5 参 @Autowired 构造 + 4 参旧构造委托 null（防御回落 BSP）。②**新增 SceneMapLlmModeTest 4 用例**——O1 带 theme → LLM 全量（kind=llm/mode=llm/契约 v1 全量元素/校验通过/无兜底）；O2 无 theme（null/空串/空白）→ BSP 确定性零回归（kind=bsp + 同 seed 同输出）；O3 LLM 空输出 → bsp-fallback + fallback 原因含「输出为空」+ 兜底地图契约 v1 自洽；O4 4 参构造（mapService=null）带 theme → 防御回落 BSP 不崩。单跑 SceneMapLlmModeTest 4/0 + SceneBindingTest 5/0（BSP 零回归复验）。③**前端**——剧本编辑弹窗「生成方式」选择（✨ LLM 全量生成需主题 / BSP 默认无主题）+ LLM 主题输入（占位「民国宅邸凶案」），生成后绑定 default_map 逻辑不变，既有 BSP「生成默认地图」按钮保留为 BSP 模式入口；剧本杀设置页地图区（gameSetup 双版本 + rules script tab 两处）theme 暴露为可编辑输入（mapTheme 状态默认剧本名、可改，genScriptMap/genScript chat 自动地图/doResumeScript 均消费）；client.ts sceneMap 改收 body{seed?,theme?}（无参调用向后兼容）。④**构建**——npm run build 通过（tsc 0 错误 + vite 65 modules，index-StVXWN_F.js 1,863.64 kB）+ static 同步（SHA256 dist?static 一致 + index.html 引用更新 + 删除 index-Cx-YssBi.js，CSS 未变 index-BC-6X7pW.css）+ bundle grep 命中（生成方式：/LLM 全量生成/BSP 默认/民国宅邸凶案/LLM 全量输出（契约 v1 校验）/失败自动 BSP 兜底/BSP 确定性生成（契约 v1）/默认剧本名可改/sceneMap body 签名）。⑤**兼容性**——BSP 模式零回归（SceneBindingTest 5/0 全绿 + O2 复验）；禁动文件（RouterService/ArbiterService/审批/狼人杀/剧本杀 Service/SSE 主链路）零改动；ChatPage 零改动；seed 参数双模式均透传。⑥**遗留**——剧本杀设置页 seed/尺寸参数后端已支持（P-0803-J/K），UI 空间不允许未暴露（保持现状，报告说明）；LLM 模式为真实 LLM 调用（成本/延迟与对局地图同档，测试走 mock）；详见 Round 40 / 台账 #100 / DECISION_LOG D-035

> v22 更新（2026-08-02 21:1x，**P-0802-H 狼人杀后端授权批次**（主会话派单指定 P-0802-E 被 C-2 占用 → 顺延 F 与 C-2-A 撞标 → 改登 H），基线 276 → **293 tests / 39 类**）：新增 WerewolfAiPlannerTest 9 用例（狼刀不刀狼/狼队共刀/人类狼不代刀、预言家查验、女巫首夜救被刀者/概率毒（1.0 必毒/0.0 不毒同种子对照）、夜间完成判定、猎人反杀目标（有目标/只剩自己空串）、白天投票（村民随机非己/狼队共投非狼/已投不重复））+ WerewolfGameFixTest 8 用例（parseRole 宽容解析中英文别名/非法回退村民、initGame 别名 customRoles 不抛异常、controller init 返回 session_id+verify router.setWerewolfGame、SSE 事件流（init 玩家·角色→夜间结算→白天讨论→投票→等待真人→真人投票后结算推送，含讨论发言 werewolf_speech 与 transcript）、autoPlay 全 AI 局自动打到 ENDED（winner 非空+game_over 推送）、人类白天发言入讨论引擎 transcript、AI 猎人夜间死亡自动反杀→狼胜终局）；改 WerewolfGameSmokeTest W-5/W-10（原锁定旧 bug 断言改为修复后行为：猎人夜间死亡后可开枪反杀一次、toMap 输出 visible 狼人互认）；后端 8 文件 + 前端 8 文件 + static（index-Bu8YksJU.js + index-aWRy_sYe.css SHA256 一致）；npm run build 通过（tsc 0 错误 + vite 64 modules）；详见 Round 22 / 台账 #71 / DECISION_LOG D-024
> v23 更新（2026-08-02 21:26-22:0x，**P-0802-G 批次：串行调度开启 + 2D 视图遮盖修复 + 用户在场判定自查**）：mvn 基线 **302/0 全绿（41 类 surefire 汇总）**——serial=true 对测试零影响（测试走 application-test.yml 显式配置 + setSerialRound 显式设值，RouterServiceSerialRoundTest 4/4 仍全绿，LONG-01 PASS 2.858s）；`application.yml` `roleplay.round.serial: false → true`（主人反馈「前端还是并联输出」根因确认即该开关默认 false，现按需求开启串行，注释保留可切回）；2D 视图修复——ScenePage showPhaserSim=true 时整页折叠场景设置区，2D 视图（PhaserSimulationView height=640）占主体，退出恢复原布局，构建产物 index-4rQ391AJ.js 已同步 static （SHA256 dist↔static 一致，取代 index-Bu8YksJU.js）；用户在场判定自查结论：链路正确无需改码（前端 4s 轮询 conversation-status 群组成员含玩家名→在场单轨气泡；玩家仅在主动发言时进 DYAD 组=在场判定真实生效），「晕」主因即串行未开（问题 1 修复后应改善）；详见 Round 23 / 台账 #74 / DECISION_LOG D-027
> v30 更新（2026-08-03 00:1x，**P-0802-P4 改造方案 Phase 4：前端收尾**，纯前端，mvn 基线 **352/0 不动**）：①**改名弹窗接线**——client.ts +playerRename（POST /api/player/rename，body player_id+old_name+new_name）；MaterialPage/ScenePage/SettingsPage 三处角色库改名弹窗：绑定角色（玩家本人角色）改名改调新端点（局中同步，四处判定链路即时认新名），非绑定角色仍走 PUT /api/characters/{name}（无局中同步，降级可接受）；②**消除「一玩家一角色」409 副作用（Phase 1 遗留）**——createCharacter 仅当无绑定角色时携带 player_id（第一个创建的角色自动绑定为「玩家本人角色」），之后新建角色不携带（普通 NPC 不受唯一约束）；updateCharacter 仅编辑已绑定角色时携带（保留绑定）；appStore +boundCharacterName（localStorage 镜像，对齐 getPlayerId 先例，loadState 按 player_id 推导绑定）；③**构建验证**——npm run build 通过（tsc 0 错误 + vite 64 modules）+ static 同步（index-CeYBwjvf.js 1,844,500B + index-ByvBQn5e.css，SHA256 dist↔static 一致，index.html 引用已更新，旧 index-Cc-70P4p.js 已删）+ bundle grep 命中；后端 Java 零改动；详见 Round 30 / 台账 #84
> v33 更新（2026-08-03 14:1x，**P-0803-G 轨道系统用户加入前端 2D 加入入口（方案A 前端部分）**，纯前端+static 同步，后端基线 **372/53 不动**，注明纯前端）：①**api/client.ts**——+joinConversation/leaveConversation（POST /api/simulation/group/{groupId}/join·leave，body player_name，对齐既有 request 错误处理）。②**SimulationScene 群组框叠加加入/离开入口**——可加入判定：玩家角色在场（worldAgents 含玩家名）+ 未在组内 + 非 DYAD（后端 DYAD 上限 2 必满）+ 组有 id；玩家在组 → 「🚪 离开对话」；群组框右上角悬浮按钮（Container + Rectangle hitArea + hover 高亮），点击经 SceneCallbacks.onGroupAction 上抛，画布 pointerdown hitTestPointer 命中检查防误触移动目标。③**PhaserSimulationView 交互闭环**——join/leave 成功/失败可见提示（聊天面板系统消息 + 地图左下角 toast 4.5s 自消，后端错误 message 原样展示：组满/重复/已在组/组不存在等）+ 手动触发一次 conversation-status 刷新（4s 轮询兜底）。④**simulationData.ts**——SimGroup +id/idleMs（此前组 id 未消费）。⑤构建验证——npm run build 通过（tsc 0 错误 + vite 65 modules，index-CD_FWSaH.js 1,840.40 kB）+ static 同步（SHA256 dist↔static 一致 + index.html 引用更新 + 删除旧 index-lbgMwQKU.js）+ bundle grep 命中（joinConversation/leaveConversation 各 2、加入对话/离开对话 各 5）；后端 Java 零改动；详见 Round 33 / 台账 #91
> v34 更新（2026-08-03 18:15，**P-0803-H 剧本选择与角色卡功能改造批次**，基线 373 → **378 tests / 54 类**）：①**后端剧本绑定三字段**——SceneEntity +category/defaultRoles/defaultMap 三列（ddl-auto=update 自动加列，旧行默认 general/空组/无地图）；DatabaseService saveScene 8 参重载（旧 5 参委托默认值零破坏）+ entityToMap 新键（parseRoleList/parseJsonMap 宽容解析）；SceneController create/update/persistScene 全链路透传 + default_map 空串=清除语义 + **新端点 POST /api/scenes/map**（BspMapGenerator 契约 v1 确定性生成默认地图，零 LLM 零成本）。②**测试**——新增 SceneBindingTest 5 用例（创建带绑定回显+GET 列表透传 / 旧式创建默认值向后兼容 / PUT 更新分类+角色组+空串清地图 / map 端点契约 v1（map_version/width/height/zones/spawn_points/generator.kind=bsp）/ 同 seed 同输出确定性）。③**前端**——ScenePage 场景选择→剧本选择全量改造（主人需求 1-8）：角色卡 hover 编辑/删除按钮（DELETE /api/characters/{name}）；剧本卡分类 chips（一般模式/狼人杀模式）；点开剧本卡显示角色卡栏+地图预览（default_map → PhaserScriptMapView；无则占位+编辑弹窗内「生成默认地图」）；角色库按所属剧本分组页签+自由角色卡页；用户角色卡每栏置顶+不默认勾选（自动带上默认角色与狼人杀默认角色均排除用户角色卡）；2D 规则——狼人杀「默认 2D」checkbox 默认勾选（开局自动开 2D 视图 + 2D 内「游戏面板（聊天页）」直达），一般模式「是否 2D」自选；剧本编辑弹窗加分类/默认角色组多选/默认地图生成预览清除。④**构建**——npm run build 通过（tsc 0 错误 + vite 65 modules，index-BvHFWNFM.js 1,850.31 kB）+ static 同步（SHA256 dist↔static 一致 + index.html 更新 + 删除 index-Bci-_Ru2.js）+ bundle grep 命中（剧本选择/自由角色卡/生成默认地图/默认 2D/是否 2D/每栏置顶/sceneMap 等）。⑤**并行注记**：⚠️ 本批标记 P-0803-H 与 2026-08-03 18:12 并行「scripts.name 修复批次」同名撞标（我方 18:0x 先登记并行作业登记表；改动区域不同——对方 ScriptEntity/saveScript/saveCharacter 截断，我方 SceneEntity/saveScene/SceneController，全量编译 378/0 零冲突），提请主会话协调；详见 Round 34 / 台账 #93 / DECISION_LOG D-030
> v29 更新（2026-08-02 23:5x，**P-0802-P3 改造方案 Phase 3：局中改名端点（同步式）**，⚠️ RouterService.renameAgent 新增经主人授权（2026-08-02 23:16 主会话「继续」确认，沿用 P-0802 授权链），基线 335 → **352 tests / 51 类**）：①**新端点**——`controller/PlayerController.java`（新）POST /api/player/rename（body: player_id/old_name→new_name，新名必填；status 映射 200=成功含 synced_sessions 清单 / 403=未绑定或 old_name 与绑定角色不符 / 409=撞名（库内同名或活跃会话同名）/ 500=同步失败含 rolled_back=true）。②**编排**——`PlayerIdentityService.renamePlayerCharacter`：定位鉴权（old_name 缺省时按 player_id 解析当前角色名；player_id 不存在或角色未绑定玩家 → 403；旧名路径若该角色已绑定玩家必须走 player_id）→ 撞名校验②（角色库同名 + 活跃会话同名 → 409）→ 角色库改名（CharacterController.renameCharacterInMemory：内存列表改名 + DB 删旧建新，playerId 绑定随新行保留）→ 四服务同步（RouterService.renameAgent / SimulationService.renamePlayerCharacter / WerewolfService.renamePlayer / ScriptGameService.renamePlayer，每步收集 synced_sessions）→ 任一失败回滚（已同步会话逆操作 + 角色库改回旧名，响应 500 rolled_back=true）。③**四处同步新增方法**（只新增不改既有签名）——`RouterService.renameAgent`（**授权**：agents map 换键 + persona 改名 + protagonist/directorCharacter/restrictedAgents 引用替换，+hasAgent/isProtagonist/getRestrictedAgents/setRestrictedAgents 钩子）；`SimulationWorld.renameAgent`（agents/states 换键 + persona 改名，synchronized）+ `AgentState.rename`（agentName 去 final 改 volatile）；`SimulationService.renamePlayerCharacter`（world.renameAgent + 重新断言 playerControlled，+hasAgent/isPlayerControlled 钩子）；`WerewolfService.renamePlayer`（roles/alive/votes/playerKeys/eliminated/discussionTranscript/night 目标/humanPlayers 全量名字键迁移 + 讨论引擎 world 同步 + saveSnapshot，per-session synchronized）；`ScriptGameService.renamePlayer`（players/assignments/playerAp/playerApMax/playerTalkativeness/playerIsHuman/playerKeys/playerClues/votes/discussionTranscript/discussionContexts 全量迁移 + 绑定值同步，per-session synchronized）+ saveSnapshot 增补 `player_id_bindings` + restoreFromSnapshot/resumeGame 按绑定重映射旧名→新名。④**两 controller playerSessions 键同步**——WerewolfController.renamePlayerSessionKey / ScriptController.renamePlayerSessionKey（playerSessions+playerIdBindings 双键）。⑤**测试**——`controller/PlayerRenameE2ETest` 6 用例（①四链路同步+synced_sessions 清单 ②撞名 409 ③鉴权 403+兼容路径 ④端点契约 200/400/403/409 ⑤无 player_id 零变化 ⑥**回滚路径**：手工装配真实 CharacterController+mock 依赖，剧本杀 renamePlayer 抛异常 → 500+rolled_back=true+角色库回滚为旧名+router/2d 被逆操作 verify）；`service/ScriptRenameResumeTest` 4 用例（①内存命中 resume 新名 ②重启快照重建含新名 ③旧快照+player_id_bindings 重映射 ④无绑定回退旧名）；**无 player_id / 未改名请求行为零变化（既有 335 基线零破坏，含并行 P-0802-M LLMClientStreamTest 7 用例共存）**；⑥零改动 ArbiterService/审批/SSE/static/前端（npm build 跳过）；详见 Round 29 / 台账 #83
> v28 更新（2026-08-02 23:0x，**P-0802-P2 改造方案 Phase 2：判定链路切换（解析式）**，⚠️ RouterService 经主人授权，基线 319 → **335 tests / 48 类**）：①**一般模式（RouterService，授权文件）**——runRound 判定点 :233 加 playerId 解析式豁免：`agents.containsKey(speaker) || (playerId!=null && !playerId.isBlank() && identityService!=null && agents.containsKey(identityService.resolveCharacterName(playerId).orElse("")))`（角色库改名后旧名 speaker + player_id 仍豁免主控代声）；新增四参重载 runRound(userInput, userInterjection, speaker, playerId)（旧三参委托 null 零破坏）；**:340 排除逻辑同步改解析名**（agentMap.remove 优先 speaker 命中名、否则 playerId 解析名——否则改名场景解析名 agent 仍参与 LLM 生成=同一句双声）；构造 +PlayerIdentityService 参（SessionRegistry 构造链透传，RouterServiceSerialRoundTest 补 null 参）。②**SessionController.send**——收 body.player_id 传新重载。③**2D（SimulationService）**——initWithPersonas 四参重载（:200-202 判定加 `name.equals(resolve(playerId))`，三参委托 null），构造 +PlayerIdentityService 参（MergedSpeechModeTest 补 null 参）；SimulationController.loadCharacters 读 body.player_id 传新重载。④**狼人杀（WerewolfController）**——init 加收 player_id（query+body 双通道）：有且解析命中 → `humans = Set.of(解析名)`（角色改名后旧名 player_name 不再被 AI 行动器接管），无/未绑定 → 现状 `Set.of(player_name)`；playerSessions 键不变；+三参 @Autowired 构造（一二参保留委托，4 个既有测试 init 调用补空串参）。⑤**剧本杀（ScriptController）**——init 收可选 body.player_id：解析命中 → 按解析名登记 controller 级 playerIdBindings（resolvedName→playerId，Phase 3 局中改名/重连恢复用），无/未绑定 → 不登记零变化；+四参 @Autowired 构造（三参委托 null 兼容旧测试）。⑥**四组判定测试**——RouterRenameTest 5（①旧名 speaker+player_id 豁免：原文入史 AGENT/该角色排除 LLM（仅 1 次调用）/无旁白 ②无 player_id 旧名 speaker 走主控旁白 ③无 player_id speaker 名单内豁免旧行为 ④player_id 未绑定回退 speaker 字符串 ⑤全程无 player_id 零变化）；Test2dPlayerRenameTest 4（①旧名 playerName+player_id 解析名被标记 playerControlled ②三参显式 playerName 回归 ③未传 playerName 旧规则 me 回归 ④player_id 未绑定回退）；WerewolfRenameTest 4（①init 带 player_id humanPlayers 含解析名不含旧名 ②无 player_id Set.of(player_name) 零变化 ③未绑定回退 ④query 通道同样解析）；ScriptRenameTest 3（①init 带 player_id 按解析名登记 ②无 player_id 不登记零变化 ③未绑定不登记）；**无 player_id 请求行为与现状逐字节一致（四组零变化回归断言 + 全量旧测试零破坏）**；⑦零改动 ArbiterService/审批/狼人杀与剧本杀 Service 内部判定/WerewolfService/ScriptGameService/SSE/static（前端零改动 → npm build 可跳过）；详见 Round 28 / 台账 #82
> v27 更新（2026-08-02 23:0x，**P-0802-L 2D 视图 UI 遮挡修复批次**，纯前端零后端改动，mvn 基线 **319/0 不动**）：①**剧本杀模式 ChatPage 工作区 grid 修复**——`.workspace` 默认 2 列 grid，script 模式渲染 3 children（panel-left + 剧本杀状态面板 + chat-main）无专属类 → chat-main 被自动放置到第 2 行第 1 列 140px 窄条（内嵌 2D 面板被挤压几乎不可见）；修复：workspace 加 `script-mode` 类 + `.workspace.script-mode{grid-template-columns:140px 260px minmax(420px,1fr)}`（与 werewolf-mode 同款）+ ≤1080/≤900 响应式对齐，chat-main 恢复正常宽度、2D 面板完整可见。②**ScenePage 2D 全屏 46px 底部溢出修复**——PhaserSimulationView height 固定 640 → `max(480px, calc(100vh - 210px))`（非 host 开销实测 198px，留 12px 余量，480px 保底）。③**浏览器实测（Edge headless + CDP，经本地代理 8099→8000 加载新产物，零触碰运行中后端）**——scene2d 三档视口 1440×900/792/700 overflowY 全 0（修复前 792 视口 overflowY=14、内容底 806 超出）；chat2d 剧本杀完整链路（AI 生成剧本 72s 真机）script 模式三列几何正确（chat-main ≈1040px，修复前 140px 窄条）+ 内嵌 2D 无遮挡。④**Bug 记录（只记录不改）**——client.ts L149-158 scriptStartDiscussion/StartVoting/Resolve/Finish 硬编码 `session_id:''`（P-0802-J 已实现 script_* 会话定向，四调用点应回写 store.scriptSessionId，归后续批次）。⑤**顺带修复 scriptInit 超时**——`api/client.ts` scriptInit 补 `timeout: 120000`（实测真实 LLM 剧本生成 72.4s，60s 默认超时 abort 导致剧本杀一步式进局失败，与 scriptMap 已用 120s 对齐；验证阻塞点）。⑥**浏览器实测终态**——scene2d 三档视口 900/792/700 overflowY 全 0；chat2d 剧本杀完整链路（AI 生成剧本 72.4s → 进对局 → 注入真实 session_id 推进讨论 → 查看 2D 模拟）script 模式三列 grid `140px 260px 1040px`（chat-main 1040px，修复前 140px 窄条）、内嵌 2D 面板 1040×503 完整可见、无页面滚动；auditOverlay 唯一提示为 2D 内部公告栏（P1-8 设计内组件非遮挡）。⑦构建：npm run build 通过（本批 64 modules，index-Ct8E7agO.js 1,874,964B）；**最终 dist/static 产物为并行批次 P-0802-P1-demo 基于含本批改动的完整源码重建的 index-DXQ1IdZ-.js（含双方改动已核实），本批后续又以含 scriptInit 超时修复的重构建 index-BFSCKpeC.js 1,841,952B 重新同步 static**（SHA256 dist?static 一致）；详见 Round 27 / 台账 #81
> v26 更新（2026-08-02 22:4x，**P-0802-P1-demo 改造方案 Phase 1：身份字段落地（最小 demo）**，基线 309 → **319 tests / 44 类**）：①**数据模型**——CharacterEntity +playerId 列（`@Column(unique=true)` nullable，一角色最多绑一玩家）+ 5 参构造重载（旧构造委托 null 兼容）；CharacterRepository +findByPlayerId；DatabaseService saveCharacter 5 参重载（旧 4 参委托 null，update 时 playerId 为空保留既有绑定=改名迁移绑定不解绑）+ saveAllCharacters 透传 player_id + entityToMap 输出 player_id 键。②**Controller**——create/update/batch 透传 player_id 落库 + 撞名校验 ① 一并落地（同名 → 409「角色名已存在: xxx」，update 排除自身；playerId 已被其他角色占用 → 409；batch 整批预校验任一撞名整批不落库；DataIntegrityViolationException 兜底 409 防 500）。③**新服务**——PlayerIdentityService（resolveCharacterName(playerId)→当前角色名 / resolvePlayerId(name) 反查，纯 DB 零缓存=解析式支柱，Phase 2 判定链路统一调用点）。④**前端**——client.ts 导出 getPlayerId()（crypto.randomUUID 生成 + localStorage 'playerId' 持久化）+ createCharacter/updateCharacter body 自动携带 player_id（data 显式提供时以 data 为准）；appStore +playerId 字段（getPlayerId 初始化）；types Character +player_id?。⑤**验证**——新增 CharacterRenameValidationTest 10 用例（①a PUT 改名撞名 409 且原 persona 未被覆盖 / ①b 同名自更新排除自身 200 / ②a create 撞名 409 / ②b batch 撞名整批不落库 / ②c batch 批内重复 409 / ③a create 带 player_id 落库+findByPlayerId 反查+解析器双向 resolve / ③b update 改名绑定随角色迁移 / ③c batch 带 player_id 落库 / ⑤ 无 player_id 行为与现状一致（无键、反查空）/ ④ 连续 409 后内存列表与 DB 行数一致）；无 player_id 请求行为零变化（旧协议零破坏）；npm run build 通过（64 modules，index-DXQ1IdZ-.js 1,831.32 kB，bundle 命中 player_id/randomUUID/pid-；**static 未同步**——任务范围仅 build 验证，8000 重启由主会话负责）；详见 Round 26 / 台账 #80
> v25 更新（2026-08-02 22:1x，**P-0802-J 狼人杀范围外遗留项批次**，基线 302 → **309 tests / 43 类**）：①**剧本杀讨论引擎 per-game 隔离**——ScriptGameService 三 Map（discussionWorlds/discussionConversations/discussionDirectors）替代 service 实例级共享（D-012 已知限制剧本杀侧落地，对齐狼人杀 P-0802-I），ensureDiscussionEngine(sessionId) 懒创建，runDiscussionEngine/buildRoundGate/pickIceBreaker/getDiscussionGoal 全部改取本局实例；新增 ScriptGamePerGameIsolationTest 2 用例（同 service 两局并发引擎/世界/导演实例隔离 + 发言记录只含本局成员/A 局人类发言不串 B 局/目标不串扰）。②**script_* SSE 会话定向**——SSEController 三个 script helper 内部改走 broadcastToSession（全局 broadcast 不变），前端 App.tsx 按当前模式选会话连接（script→scriptSessionId / werewolf→werewolfSessionId / 其他无过滤），script session_id 由 init/resume/轮询回写 store；SSEControllerSessionTest +P3 用例（script_phase/status/reveal 只送达匹配会话，无过滤连接零接收，全局广播仍全量）。③**狼人杀 resume roleKey 防冒充**——WerewolfService +playerKeys（initGame 每玩家发放唯一 UUID，toMap 仅本人可见 role_key），resumeGame 改三参强制 player_key 校验（缺/错/他人 key/名 key 不匹配全拒，仅凭 key 可反查玩家），快照落 player_keys+恢复，+GET /api/werewolf/keys 分发端点；WerewolfRoleKeyTest 4 用例（R-1 发放+仅本人可见 / R-2 resume 正反例 / R-3 快照跨实例恢复后原 key 仍有效 / R-4 keys 端点）；WerewolfStage1Test S6/S7 resume 三参适配（7/0 仍全绿）；前端狼人杀恢复入口 roleKey 必填 +「我的 roleKey」展示。详见 Round 25 / 台账 #78 / DECISION_LOG D-029

> v14 更新：基线从 217 → **254 tests / 34 类**（**Phaser 阶段2 LLM 地图生成接入**：MapValidatorTest 18 用例——契约 v1 校验（尺寸/层一致性/热点越界/出生点越界/碰撞值 0-1/房间边界/走廊连通等）/ BspMapGeneratorTest 7 用例——BSP 递归二分生成（房间数/走廊连通 BFS 全可达/种子可复现/降级输出契约 v1）/ ScriptMapServiceTest 10 用例——LLM 生成路径（正常/空输出兜底 BSP/宽容解析/缓存命中 regenerate 强制重生成/校验失败降级/快照落库恢复）/ ScriptMapPersistenceTest 2 用例——mapData 随对局快照持久化+重启恢复；详见 Round 14；**前端部分：文件已落地（mapData.ts/PhaserScriptMapView.tsx/ScriptMapScene.ts/client.ts scriptMap）但 ScenePage 接线未完成 + build 失败 23 处 TS 错误，未同步 static，见台账 #56**（该前端缺口已由台账 #58/#59 修复，见 v15）

> v16 更新（2026-08-01 22:47，P-0801-G 邀请码功能显式开关，基线 254 → **261 tests / 35 类**）：AuthControllerInviteSwitchTest 7 用例——启用路径（application-test.yml invite-enabled=true + invite-code=B3283A78）：配置码 B3283A78 验证通过 200+token / 错误码 401 / DEFAULT2024 兼容保留可验证 / token 过 /api/auth/me；关闭路径（直构 AuthController(false,...)）：/verify 一律 403「邀请码功能未启用」（正确配置码也不放行，不暴露码是否正确）；直构 AuthController(true,...) 验证配置码（重启不丢语义）；详见 Round 15
> v17 更新（2026-08-02 13:3x，P-0802-B P1 批次修复（P1-6/P1-8/P1-9），纯前端零后端改动，mvn 基线 261/0 不动）：P1-6 删除报错根治——client.ts request() 不再无条件 res.json()：200 空 body（DELETE /api/scenes|/api/characters 等）返回 null 视为成功，非 JSON 亦不崩（SettingsPage/ScenePage 删除入口同坑一并修复）；P1-9 狼人杀文案门控——ChatPage 加载文案仅 werewolf 模式走 getLoadingText（白天讨论中...），free/director 显示「⏳ 运行中...」；P1-8 公告栏改 2D 内触发——AnnouncementBanner/Ticker 从 App.tsx 全局挂载移除，改挂 PhaserSimulationView（2D 游戏视图）内（inline 绝对定位跟随面板，Ticker 空态不占位 + 「×」收起），新增「📢」开关与 ChatPage「⚙️ 设置」面板（聊天模式切换 free/director + 公告显示开关，localStorage roleplay_ann_show 联动）；npm run build 通过（tsc 0 错误 + vite 63 modules），产物 index-DYdLFONY.js + index-BLkB2f6f.css 已同步 static/（SHA256 dist↔static 一致），index.html 引用已更新；详见 Round 17 / 台账 #65
> v18 更新（2026-08-02 19:4x，P-0802-C C-1 批次（P3-10/P3-11/2D UI 重构/2D 入口合并），纯前端零后端改动，mvn 基线 261/0 不动）：P3-10 演讲+广播 demo 面板从 ScenePage 场景设置移除迁入 PhaserSimulationView 2D 视图（精简版默认折叠：AI 自动演讲/玩家发广播/模式 select）；P3-11 场景设置外层 maxWidth 960→'none' 不限宽 + 角色/场景列表分类排序（前端 useMemo 本地分组，角色：全部/已选/未选×默认/A-Z/Z-A，场景：全部/剧本杀对局（scene_id 前缀 script_）/普通×默认/A-Z/Z-A，后端零改动）；2D UI 重构——左地图（flex:1）+ 右侧聊天面板（320px 可折叠，控制条「💬 聊天」按钮+面板 ✕，收起=地图全宽），P0-3 内嵌聊天整合进右面板不双份；C-2 衔接——消息结构统一 SimChatMsg 带 status:'pending'|'playing'|'done' 播放状态（新世界对话先 pending 再翻 done 演示状态机制，渲染按 status 样式区分，C-2 打字机队列直接消费）；2D 入口合并——删 ScenePage use2D checkbox 及 roleplay_2d_inline 写库，主入口=场景页单一「🎮 2D 模拟」按钮，ChatPage 剧本杀「查看 2D 模拟」联动保留但无独立开启按钮、不再自动展开；npm run build 通过（tsc 0 错误 + vite 63 modules），产物 index-BX3fEiRB.js + index-BANIh__s.css 已同步 static/（SHA256 dist↔static 一致），index.html 引用已更新；详见 Round 18 / 台账 #66
> v18 并行协调注记：并行批次 P-0802-D（狼人杀重构，subagent 15a56863）同窗作业，双方改动共存于最终 ScenePage.tsx（C-1 只改 use2D/2D 入口与场景设置区，P-0802-D 只改狼人杀区）；C-1 最终交付基于含 P-0802-D 前端改动的完整源码重构建（index-BX3fEiRB.js），部署包同时含两批前端改动；P-0802-D 将 mvn 基线提至 **272/0（36 类）**（WerewolfGameSmokeTest 11 用例），见其 v19 条目
> v21 更新（2026-08-02 20:5x，P-0802-F C-2 批次 A 部分实施：一般模式串行调度）：新增配置开关 `roleplay.round.serial`（默认 false=并行零破坏；true=串行+同轮即时入史），RouterService.runRound 串行分支 + executeRoundSerial（每 agent 输出完成立即入史，后发言者上下文含前面角色本轮发言）；新增 RouterServiceSerialRoundTest 4 用例（同轮上下文共享/默认并行行为不变/并行不共享同轮上下文/配置默认 false+切换）；本批 4/4 全绿 + 排除并发未登记批次失败用例后全量 284/0 BUILD SUCCESS；⚠️ 并发未登记批次（狼人杀 AI 行动器 G0-2，代码注释同用 P-0802-F 撞标）在改 WerewolfService/WerewolfController/WerewolfAiPlanner/RouterService（werewolfGame 角色卡，与串行调度不同区域共存），其 WerewolfAiPlannerTest.humanWolfWaits 单跑失败与本批无关，已按规则 5 登记占位 P-0802-未知；详见 Round 21 / 台账 #70
> v20 更新（2026-08-02 19:5x，P-0802-E C-2 批次：输出机制重构——前端打字机流式播放队列 + 一般模式串行调度调研，mvn 基线 **272/0 不变**（无新增测试，TrackStrategy/GroupStrategy prompt 改动后全量复跑全绿，TrackStrategyTest 7/0））：**B 前端打字机（核心）**——PhaserSimulationView 打字机队列引擎：消费 C-1 SimChatMsg.status 驱动 pending→playing（3字/秒逐字）→done，严格串行（上一段播完+3s 句间停顿→下一段）；参数集中 `phaser/simChatConfig.ts`（typingCharsPerSec=3 / interSentencePauseMs=3000 / pauseTimeoutMs=60000 / maxSentenceChars=60 / typingTickMs=333）；暂停/恢复=聊天输入框有字冻结播放进度、发送后恢复；60s 暂停超时看门狗跳过当前句；用户在场判定=conversation-status 群组成员含玩家名（在场→单轨：世界内只显示当前播放者气泡单例 setBubbleFilter；不在场→多气泡 + SimulationScene computeBubbleLanes 锚定避让层，硬约束不重叠）；渲染硬截断（超长省略号）+ cleanWorldText 过滤非语言噪音（emoji/特殊符号/零宽字符，保留中文标点）；**A 一般模式串行调度=调研报方案未改码**（最小改动点在 RouterService.runRound=禁动，方案见 docs/串行调度方案-20260802.md 交主会话决策）；后端非禁动轻提示——TrackStrategy.MERGED/WEAK + GroupStrategy.fallback 角色发言 prompt 补「每句话不超过60字」（MAX_SENTENCE_CHARS=60 常量，与前端 maxSentenceChars 对齐）；验证：mvn 272/0 + npm run build 通过（tsc 0 错误 + vite 64 modules）+ static 同步（index-4KH0B1ke.js + index-D1bQ2MoB.css，SHA256 dist↔static 一致，index.html 引用已更新）+ bundle grep 10 项全命中；未跑 headless 冒烟（无浏览器工具+8000 旧 jar 产物，build+grep+源码审查替代）；详见 Round 20 / 台账 #69
> v19 更新（2026-08-02 19:1x，P-0802-D 狼人杀重构非禁动批次（前端 init 根因修复 + 6 个游戏 API 封装 + 后端测试骨架），基线 261 → **272 tests / 36 类**）：WerewolfGameSmokeTest 11 用例——init 全量进局（6 人默认角色分布 2狼/预言家/女巫/猎人/村民）/ controller body{players,roles} 自定义职业生效（前端修复后路径）/ 夜间行动+phase 守卫 / resolveNight 狼刀+毒双亡结算 / 猎人开枪现状 bug 锁定（G1-1 升级证据：resolveNight 置 hunterCanShoot=false 后 hunterShoot 永久被拒，禁动未修）/ 投票全流程含 D7 审批门挂起→批准→放逐→回 NIGHT round++ / 平票无人放逐 / 两狼出局 villager 胜 ENDED / 4 人局狼刀后 werewolf 胜 / toMap 视角现状锁定（visible 键构造后从未 put，狼人互认 API 层缺失，G1-3 证据）/ ENDED 终态 phase 守卫；另锁定现状：customRoles 值 "wolf" → Role.valueOf 抛 IllegalArgumentException（后端只认枚举名 werewolf，前端已规避传枚举名）。前端：ScenePage.startWWGame 改 body{players: 全量, roles: 职业映射}（AI 进 GameState、职业配置生效、res.ok 检查不再静默吞掉）、start() werewolf 分支同步、client.ts 补 6 个游戏端点封装（原前端零调用）。⚠️ static 未同步（禁动）：并行批次 C-1 同步的 index-BX3fEiRB.js 早于本批编辑不含狼人杀修复，最终 dist index-BX3fEiRB.js 含 C-1+本批全部改动（bundle grep 双向验证），需主会话重同步 static + 8000 重启；详见 Round 19 / 台账 #67
> v15 更新（2026-08-01 20:4x，状态同步注记，基线不变 **254/0**）：**Phaser 阶段2 已通过未衡终审，三阶段全闭环**——前端缺口已闭合：台账 #58（ScriptMapScene.ts 20 处 TS 修复 + ScenePage 剧本杀 Tab「生成地图」接线 + npm run build 63 modules 通过，index-Ccc-CMzG.js 1,814,610 B 已同步 static）+ #59 独立复核（SHA256 dist↔static 三方一致 + 冒烟 stage2 16/16 + 阶段1 回归 10/10）；**8000 实例已随新打包重启生效（PID 25760）**；终审遗留 P2（非阻塞）：①BSP 降级 seed 硬编码 DEFAULT_BSP_SEED=20260801（未 @Value 注入，建议配置化）②地图不在对局 toMap 的 your_secret 同级暴露（实现选择，非缺陷）

> v13 更新：基线从 214 → **217 tests / 30 类**（P1 缺陷修复：ScriptServiceMaxTokensTest 3 用例——mock 返回 2000+ token 完整剧本 JSON（5 角色×长 intro/secret + 5 线索 + secrets + background + truth）→ generateScript 解析成功且 roles/secrets/clues/killer_id/truth 字段齐全、长字段不截断；verify generateScript 必须以 maxTokens=4000 调用 callJson（600 旧值回归即失败）；LLM 空输出仍走 defaultScript 兜底符合 schema A1-3 不回归）；详见 Round 13

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

## 测试类明细（335 tests / 48 类）

| 测试类 | 用例 | 状态 | 说明 |
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
| **MapValidatorTest** | 18 | ✅ | **Phaser 阶段2 地图契约 v1 校验（尺寸/层一致性/热点越界/出生点越界/碰撞值/房间/走廊/宽容解析）** |
| **BspMapGeneratorTest** | 7 | ✅ | **Phaser 阶段2 BSP 降级生成器（房间数/走廊 BFS 连通/种子复现/契约 v1 输出）** |
| **ScriptMapServiceTest** | 10 | ✅ | **Phaser 阶段2 LLM 地图生成（正常/空输出兜底/宽容解析/缓存与 regenerate/校验降级/快照）** |
| **ScriptMapPersistenceTest** | 2 | ✅ | **Phaser 阶段2 地图随对局快照落库+重启恢复** |
| **ScriptServiceMaxTokensTest** | 3 | ✅ | **P1 缺陷修复：LLM 剧本生成 JSON 截断（600→4000 maxTokens，长字段不截断）** |
| **AuthControllerInviteSwitchTest** | 7 | ✅ | **P-0801-G 邀请码功能显式开关（启用：配置码 B3283A78 200+token/错误码 401/DEFAULT2024 兼容/token 过 me；关闭：403 未启用不暴露码；直构验证配置码重启不丢）** |
| **CharacterRenameValidationTest** | 10 | ✅ | **P-0802-P1-demo 改造方案 Phase 1（撞名 409 原数据未覆盖×4 / playerId 绑定落库+findByPlayerId 反查+解析器双向×3 / 无 player_id 零变化 / 409 后内存与 DB 一致）** |
| **WerewolfGameSmokeTest** | 11 | ✅ | **P-0802-D 狼人杀状态机冒烟（W-1~W-11：init 全量进局/自定义职业/夜间行动/结算/猎人开枪现状锁定/投票全流程含审批门/平票/双胜判定/toMap 现状锁定/终态守卫）** |
| **WerewolfAiPlannerTest** | 9 | ✅ | **P-0802-H 狼人杀 AI 夜间行动器（P1 狼刀不刀狼+决策标记 / P2 狼队共刀 / P3 单狼局人类狼不代刀 / P4 预言家查验随机存活 / P5 女巫首夜救被刀者 / P6 后续夜概率毒 1.0 vs 0.0 同种子对照 / P7 夜间完成判定 / P8 猎人反杀目标 / P9 白天投票村民随机非己+狼队共投非狼）** |
| **WerewolfGameFixTest** | 8 | ✅ | **P-0802-H 狼人杀修复（F1 parseRole 宽容解析中英文别名 / F2 initGame 别名 customRoles 不抛异常 / F3 init 返回 session_id+router 注册 / F4 SSE 事件流全链路 / F5 autoPlay 全 AI 局自动 ENDED / F6 人类白天发言入讨论 transcript / F7 AI 猎人夜间死亡自动反杀）** |
| **RouterServiceSerialRoundTest** | 4 | ✅ | **P-0802-F/C-2-A 一般模式串行调度（serial=true 同轮上下文共享 / serial=false 默认并行行为不变 / 并行不共享同轮上下文 / 配置默认 false+切换）** |
| **WerewolfStage1Test** | 7 | ✅ | **P-0802-I 狼人杀阶段1遗留项（S1/S2 女巫获知被刀者机制 / S3 witch_info 定向推送 / S4 狼人杀讨论引擎 per-game 隔离 / S5 werewolf_* SSE 定向 / S6 快照落库+跨实例恢复 / S7 联机房绑定+controller resume（P-0802-J 适配 roleKey 三参））** |
| **SSEControllerSessionTest** | 3 | ✅ | **P-0802-I/P-0802-J SSE 会话定向（P1 定向推送只送达匹配会话/无匹配静默丢弃/全局广播仍全量 / P2 空 session 回退全局+complete 清理 / P3 script_* 三事件定向（P-0802-J 新增））** |
| **ScriptGamePerGameIsolationTest** | 2 | ✅ | **P-0802-J 剧本杀讨论引擎 per-game 隔离（I-1 同 service 两局并发引擎/世界/导演实例隔离 / I-2 发言记录只含本局成员+人类发言不串局+目标不串扰）** |
| **WerewolfRoleKeyTest** | 4 | ✅ | **P-0802-J 狼人杀 resume roleKey 防冒充（R-1 init 发放唯一 key 仅本人可见 / R-2 resume 正反例：缺/错/他人 key/名 key 不匹配全拒+仅凭 key 反查 / R-3 快照跨实例恢复后原 key 仍有效 / R-4 GET /api/werewolf/keys 分发端点）** |
| **RouterRenameTest** | 5 | ✅ | **P-0802-P2 一般模式 speaker 豁免（①旧名 speaker+player_id 解析式豁免：原文入史 AGENT/该角色排除 LLM/无旁白 ②无 player_id 旧名走主控旁白 ③无 player_id 名单内豁免旧行为 ④player_id 未绑定回退 ⑤全程无 player_id 零变化）** |
| **Test2dPlayerRenameTest** | 4 | ✅ | **P-0802-P2 2D playerControlled 解析式（①旧名 playerName+player_id 解析名被标记 ②三参显式 playerName 回归 ③未传 playerName 旧规则 me 回归 ④player_id 未绑定回退）** |
| **WerewolfRenameTest** | 4 | ✅ | **P-0802-P2 狼人杀 humans 解析登记（①init 带 player_id humanPlayers 含解析名不含旧名 ②无 player_id Set.of(player_name) 零变化 ③未绑定回退 ④query 通道同样解析）** |
| **ScriptRenameTest** | 3 | ✅ | **P-0802-P2 剧本杀 init 登记（①带 player_id 按解析名登记 playerIdBindings ②无 player_id 不登记零变化 ③未绑定不登记）** |
| **PlayerRenameE2ETest** | 6 | ✅ | **P-0802-P3 局中改名四链路 E2E（①四链路同步+synced_sessions 清单 ②撞名 409 ③鉴权 403+兼容路径 ④端点契约 ⑤无 player_id 零变化 ⑥回滚路径：剧本杀同步失败→500+rolled_back+角色库/已同步会话全部回滚）** |
| **ScriptRenameResumeTest** | 4 | ✅ | **P-0802-P3 剧本杀改名后重连快照恢复（①内存命中 resume 新名 ②重启快照重建含新名 ③旧快照+player_id_bindings 重映射 ④无绑定回退旧名）** |
| **新增稳定性/中断/DB 测试** | ~26 | ✅ | 并行工作流新增（中断系统/DB/模拟） |

---

## 超长文本稳定性（需求文档第十条硬性要求）

| 用例 | 参数 | 结果 | 耗时 |
|---|---|---|---|
| LONG-01 | 500 轮 × 200 字 = **10 万字上下文**，mock LLM | ✅ 21:28 复跑 PASS：P50=4ms P95=9ms Max=72ms，堆增长 -3.7%，无 OOM/卡死/锚点可检索 | ~5s |

---

## 📝 执行历史（追加式）

### 2026-08-02 23:5x-00:1x — 改造方案 Phase 4 前端收尾（Round 30，纯前端；P-0802-P4，台账 #84）
- **命令**：npm run build（roleplay-v4/frontend/，tsc -b 0 错误 + vite 64 modules）→ dist→static 同步 → SHA256 字节比对 → bundle grep
- **结果**：构建通过，static 已同步生效（index-CeYBwjvf.js 1,844,500B + index-ByvBQn5e.css 39,820B，SHA256 dist↔static 一致，index.html 引用已更新，旧 index-Cc-70P4p.js 已删除）；mvn 基线 352/0 不动（后端 Java 零改动）
- **改动**：前端 `api/client.ts`（+playerRename / +getBoundCharacterName / createCharacter·updateCharacter 的 player_id 携带策略修正）、`store/appStore.ts`（+boundCharacterName/setBoundCharacterName）、三处改名弹窗（MaterialPage/ScenePage/SettingsPage）
- **回归**：无后端改动；bundle grep 命中 playerRename/player/rename/boundCharacterName；无 player_id 请求行为零变化（前端仅携带策略调整，后端契约不变）
- **git**：未 commit（统一 gate，未获授权）；并行登记 P-0802-P4


### 2026-08-02 23:1x-23:5x — 改造方案 Phase 3 局中改名端点·同步式（Round 29，352 tests；P-0802-P3，台账 #83）
- **命令**：mvn test -Dtest=PlayerRenameE2ETest（单跑 6/0）→ mvn test -Dtest=ScriptRenameResumeTest（单跑 4/0）→ mvn test（全量 surefire 跑批 23:5x 汇总 **51 类**）
- **结果**：**352 tests / 0 failures / 0 errors / 51 类 BUILD SUCCESS**（335 基线 + 并行 P-0802-M LLMClientStreamTest 7 + 本批 2 类 10 用例；LONG-01 10 万字 2.758s PASS）
- **新增（新端点 + 编排 + 四链路同步）**：`controller/PlayerRenameE2ETest` 6 用例——①四链路同步：真实 Spring 上下文跑通 RouterService.renameAgent（agents 换键+protagonist 引用替换）+ SimulationService.renamePlayerCharacter（states 换键+playerControlled 保留）+ WerewolfService.renamePlayer（humanPlayers 换名+GameState roles/alive/playerKeys 迁移）+ ScriptGameService.renamePlayer（ScriptGame 全键迁移+checkPlayerAccess 新名通过），响应 synced_sessions 含四类会话；②撞名 409（库内同名 + 活跃会话同名双路径）；③鉴权 403（无 player_id / 未绑定 / old_name 与绑定角色不符）+ 兼容路径（省略 old_name 按 player_id 解析）；④端点契约（200 成功 / 400 缺 new_name / 403 鉴权 / 409 撞名）；⑤无 player_id 零变化（角色库与会话均无改动）；⑥**回滚路径**：手工装配真实 CharacterController（经 create() 写入小明）+ mock Router/Simulation/（真实可验证逆操作）+ mock ScriptGameService.sessionsOfPlayer 返回 sc-rollback 且 renamePlayer 抛异常 → 500 + rolled_back=true + 角色库回滚为小明（cc.getAll 断言无大明）+ verify router/sim 被 renameAgent(大明,小明)/renamePlayerCharacter(大明,小明) 逆操作。`service/ScriptRenameResumeTest` 4 用例——①内存命中：改名后 resume 返回新名（playerSessions 键同步）；②重启快照：saveSnapshot 含 player_id_bindings，新实例 restore 后对局含新名；③旧快照+绑定重映射：快照无新名但有绑定 → resumeGame 按绑定把旧名映射为新名（bindings 优先于快照名字）；④无绑定回退旧名（零变化）。
- **改动**（均增量兼容，只新增不改既有签名）：`controller/PlayerController.java`（**新**，POST /api/player/rename）；`service/PlayerIdentityService.java`（重写：保留 1 参构造 + 新增 9 参 @Autowired 构造（@Lazy 断开 RouterService/SimulationService/两 controller/ScriptGameService 构造循环）；renamePlayerCharacter 编排 + rollback + 旧名路径新增「该角色已绑定玩家须走 player_id」鉴权）；`service/RouterService.java`（**主人授权 P-0802**：+renameAgent/hasAgent/isProtagonist/getRestrictedAgents/setRestrictedAgents）；`simulation/AgentState.java`（agentName 去 final 改 volatile + rename）；`simulation/SimulationWorld.java`（+renameAgent synchronized）；`simulation/SimulationService.java`（+renamePlayerCharacter/hasAgent/isPlayerControlled）；`service/WerewolfService.java`（+renamePlayer/renameHumanPlayer/sessionsOfPlayer/anyGameHasPlayer + GameState getRoles/getAlive 测试钩子，per-session synchronized）；`service/ScriptGameService.java`（6 参 @Autowired 构造（旧构造委托 null）+ renamePlayer/registerPlayerBinding/sessionsOfPlayer/anyGameHasPlayer + saveSnapshot 增补 player_id_bindings + restoreFromSnapshot/resumeGame 绑定重映射 + ScriptGame getAssignments/getPlayerKeys 钩子，per-session synchronized）；`controller/CharacterController.java`（+renameCharacterInMemory）；`controller/WerewolfController.java`（+renamePlayerSessionKey/playerSessions 钩子）；`controller/ScriptController.java`（+renamePlayerSessionKey/playerSessions 钩子，init 在 initGame 前 registerPlayerBinding）
- **回归**：既有 335 基线零破坏（含 P-0802-P2 四组判定测试 16/0、CharacterRenameValidationTest 10/0、狼人杀 7 类、剧本杀 13 类、LONG-01 全绿）；并行 P-0802-M（LLMClientStreamTest 7 用例）共存通过；**无 player_id / 未改名请求行为零变化（⑤用例 + 全量旧测试零破坏）**；零改动 ArbiterService/审批/SSE/static/前端
- **npm build**：跳过（本批纯后端新增，前端零改动，任务允许注明跳过）
- **git**：未 commit（统一 gate，未获授权）；并行登记 P-0802-P3（RouterService 授权注明）


### 2026-08-02 23:3x-23:0x — 改造方案 Phase 2 判定链路切换（Round 28，335 tests；P-0802-P2，台账 #82）
- **命令**：mvn test-compile（0 错误）→ mvn test -Dtest=RouterRenameTest,Test2dPlayerRenameTest,WerewolfRenameTest,ScriptRenameTest（单跑 16/0 BUILD SUCCESS）→ mvn test（全量 surefire 跑批 23:0x 汇总 48 类）
- **结果**：**335 tests / 0 failures / 0 errors / 48 类 BUILD SUCCESS**（319 基线 + 本批 4 类 16 用例；LONG-01 10 万字 2.503s PASS）
- **新增（四组判定测试）**：`service/RouterRenameTest` 5 用例——①旧名 speaker + player_id（角色库已改名，解析名=新名在 agents）→ 原文入史 Role.AGENT + 该角色排除 LLM（仅 1 次 callSync）+ 无主控旁白 + agentOutputs 只含另一角色；②无 player_id 旧名 speaker 不在名单 → 主控旁白（USER 消息）+ 两 agent 均生成（现状防回归）；③无 player_id speaker 在名单 → 豁免（旧行为不变）；④player_id 未绑定（解析空）→ 回退 speaker 字符串逻辑（名单内豁免/名单外旁白）；⑤全程无 player_id → 与 Phase 1 之前逐字节一致。`simulation/Test2dPlayerRenameTest` 4 用例——①旧名 playerName + player_id → 解析名 state 被标记 playerControlled（其他角色不标记）；②三参显式 playerName 行为不变；③未传 playerName → 旧规则名字 me 标记；④player_id 未绑定 → 回退 playerName 标记。`service/WerewolfRenameTest` 4 用例——①init 带 player_id → humanPlayers 含解析名不含旧名（AI 行动器不接管玩家角色）；②无 player_id → Set.of(player_name) 零变化；③未绑定 → 回退 player_name；④query 参数通道同样解析。`controller/ScriptRenameTest` 3 用例——①init 带 player_id → 按解析名登记 playerIdBindings（旧名不登记）；②无 player_id → 不登记零变化；③未绑定 → 不登记。
- **改动**（均增量兼容，无 player_id 请求行为与现状逐字节一致）：`service/RouterService.java`（**主人授权 P-0802**：runRound :233 判定加 `agents.containsKey(identityService.resolveCharacterName(playerId).orElse(""))` 豁免；四参重载 runRound(...,playerId) 旧三参委托；:340 agentMap.remove 改解析名防双声；构造 +PlayerIdentityService）；`service/SessionRegistry.java`（构造链透传 identityService）；`controller/SessionController.java`（send 读 body.player_id 传新重载）；`simulation/SimulationService.java`（initWithPersonas 四参重载 + 构造 +PlayerIdentityService）；`simulation/SimulationController.java`（loadCharacters 读 body.player_id）；`controller/WerewolfController.java`（init 加收 player_id query+body 双通道，有且解析命中 humans=Set.of(解析名)；+三参 @Autowired 构造，一二参保留）；`controller/ScriptController.java`（init 收可选 player_id 登记 playerIdBindings；+四参 @Autowired 构造，三参委托）；既有测试适配：RouterServiceSerialRoundTest +null 参、MergedSpeechModeTest +null 参、WerewolfGameSmokeTest/FixTest/RoleKeyTest/Stage1Test init 调用补 "" 参
- **回归**：既有 319 基线零破坏（含 LONG-01 全绿、RouterServiceSerialRoundTest 4/4、CharacterRenameValidationTest 10/10、狼人杀 7 类、剧本杀 13 类）；零改动 ArbiterService/审批/WerewolfService/ScriptGameService 内部判定/SSE/static
- **npm build**：跳过（本批前端零改动，任务允许注明跳过）
- **git**：未 commit（统一 gate，未获授权）；并行登记 P-0802-P2


### 2026-08-01 22:42-22:47 — 邀请码功能显式开关（Round 15，261 tests；P-0801-G，台账 #63）
- **命令**：`mvn compile`（0 错误）→ `mvn test -Dtest=AuthControllerInviteSwitchTest`（单跑 7/0 BUILD SUCCESS）→ `mvn test`（全量 surefire 跑批 22:47 汇总 35 类）
- **结果**：**261 tests / 0 failures / 0 errors / 35 类 BUILD SUCCESS**（254 基线 + AuthControllerInviteSwitchTest 7 用例）
- **新增**：`controller/AuthControllerInviteSwitchTest` 7 用例——①G-1a 启用路径（Spring 上下文 + application-test.yml invite-enabled=true/invite-code=B3283A78）：POST /api/auth/verify code=B3283A78 → 200 + token + user=player + message=验证成功；②G-1b 错误码 WRONG-CODE → 401 无效的邀请码；③G-1c DEFAULT2024 兼容保留仍可验证 200；④G-1d 验证所得 token 过 GET /api/auth/me → 200 authenticated=true；⑤G-2a 关闭路径（直构 `new AuthController(false, "B3283A78")` 无 Spring）：verify 正确配置码 → 403 邀请码功能未启用；⑥G-2b 空码同样 403（不暴露邀请码是否正确）；⑦G-2c 直构 `new AuthController(true, "B3283A78")`：配置码可验证 200（构造时入映射，重启不丢语义）
- **改动**：AuthController（+@Value roleplay.auth.invite-enabled 默认 false / roleplay.auth.invite-code 默认 DEFAULT2024，构造入映射保留 DEFAULT2024 兼容；verify 关闭时 403）；application.yml/application-test.yml（+roleplay.auth.* 两键，主 false / 测试 true，UTF-8 无 BOM）；其余端点（me/admin/*）零改动
- **回归**：既有 254 基线零破坏（含 LONG-01 10 万字全绿、ScriptGameDmTest/ResumeTest/ApTransferTest、MapValidator/Bsp/ScriptMap 四类、SpeechGateTest 24 用例）
- **git**：未 commit（统一 gate，未获授权）；并行登记 P-0801-G


### 2026-08-01 18:36-18:50 — Phaser 阶段2 LLM 地图生成接入（Round 14，254 tests；台账 #56）
- **命令**：mvn test（全量，surefire 跑批 18:47 汇总 34 类）
- **结果**：254 tests / **0 failures** / **0 errors** / 34 类 BUILD SUCCESS（217 基线 + 4 类 37 用例）
- **新增**：`simulation/map/MapValidatorTest` 18 用例——契约 v1 校验器：宽度/高度/瓦片尺寸合法性、ground/collision 层行列一致、collision 值域 0-1、热点坐标越界、出生点越界/落碰撞格、房间矩形边界、rooms/corridors 结构、宽容解析（旧格式缺字段归一）、校验通过/警告/错误分类；`simulation/map/BspMapGeneratorTest` 7 用例——BSP 递归二分：房间数符合预期、房间不重叠、走廊 L 形连通且 BFS 全可达、固定 seed 输出可复现、非法参数降级、输出符合契约 v1（map_version/layers/zones/spawn_points）；`service/ScriptMapServiceTest` 10 用例——LLM 生成路径（mock 返回契约 v1 JSON 透传）、LLM 空/非法输出 → BSP 兜底降级（fallback 原因记录）、宽容解析归一、缓存命中（不重复调 LLM）、regenerate=true 强制重生成、校验失败降级、快照落库/恢复 map_data 往返；`service/ScriptMapPersistenceTest` 2 用例——generateMap 后快照含 map_data、重启恢复后 getMap 取回一致
- **回归**：既有 217 基线零破坏（含 P-0801-D maxTokens 3 用例、SpeechGateTest 24 用例、LONG-01 10 万字全绿）；surefire 逐类核验 34 个 txt 报告 0 失败 0 错误
- **验证细节**：后端路径完整——ScriptController POST /api/script/map（L74-88）→ ScriptGameService.generateMap（L475-520，mapData 字段+快照持久化 L1631/1690）→ ScriptMapService（LLM 生成+宽容解析+MapValidator 校验+BspMapGenerator 降级兜底）；前端文件已落地（mapData.ts/PhaserScriptMapView.tsx/ScriptMapScene.ts/client.ts scriptMap）但 **ScenePage 接线未完成（仅状态声明 L43-45）+ npm run build 失败 23 处 TS 错误 → static 未同步**（详见台账 #56，待 coder 修复后重跑构建）
- **git**：未 commit（统一 gate，未获授权）
### 2026-08-01 18:58-19:40 — Phaser 阶段2 前端补接线+构建+冒烟（前端自测，非 JUnit；基线 254/0 不动；台账 #58）
- **范围**：前端 Only，后端 Java 零改动——①`ScriptMapScene.ts` 20 处 TS 错误修复（Tileset|null 传参 null 收窄、collLayer possibly null 收窄、player 类型 GameObject Image → Physics.Arcade.Image、arcade body 非空守卫、keyboard 插件可空守卫、未使用变量 2 处删除/使用）；②`ScenePage.tsx` 阶段2 接线（「🗺️ 生成地图」按钮 → api.scriptMap({session_id, theme}) → setScriptMap/setScriptMapMeta → PhaserScriptMapView 渲染 + 热点搜证回调接剧本杀搜证流程，6 个未使用 state 全部用起来）；③`tools/` 补 `phaser_map_smoke.html` + `self_test_stage2.py`（worker 台账 #56 报告磁盘缺失）
- **命令与结果**：
  - ① `npm run build`（tsc -b && vite build）**通过**：63 modules，产物 `dist/assets/index-Ccc-CMzG.js`（1,814,610 B）+ `index-B4JvPABx.css`（与上批次同 hash）；修复前 20 处 TS 错误清零
  - ② static 同步：`dist/assets/index-Ccc-CMzG.js` → `src/main/resources/static/assets/`，`static/index.html` script 引用更新（index-B2ueyU_u.js → index-Ccc-CMzG.js）；bundle grep 确认阶段2 内容（/api/script/map、生成地图、对局地图、重新生成、剧本杀地图（Phaser 渲染）、clue_location、spawn_points、map_version、BSP（降级、热点、搜证中、已搜证过）均在产物）+ 阶段1 内容（/api/simulation/state、2D 模拟（Phaser 内嵌）、原版窗口（回退））回归保留
  - ③ `python tools/self_test_stage2.py http://127.0.0.1:5199`（vite dev --host 127.0.0.1；Edge headless + --no-proxy-server 绕过本机 HTTP_PROXY 回环 502）→ **纯渲染冒烟 16/16 ALL PASS**：phaser-version 3.90.0 / normalizeMap 宽容解析（缺 map_version/tile_size → 1/32）/ game-create / map-stats（zones=2 spawns=2 rooms=1 10×8）/ tilemap+player（Arcade.Image setVelocity 修复验证）/ collider 注册（Phaser 3.90 ProcessQueue pending→active 需等帧）/ player body+circle / setCollideWorldBounds 生效（body.collideWorldBounds=true）/ WASD 按住 A → velocity.x=-150 / onSearch 回调命中 zone / markZoneSearched 热点变绿 / 重复搜证拦截 / 瓦片双层 / destroy 收敛+重建
  - ④ 阶段1 回归：`python tools/self_test_stage1.py http://127.0.0.1:5199` → **10/10 ALL PASS**（SimulationScene 零改动不受影响）
- **验证细节**：Phaser 3.90 ProcessQueue 的 collider 先入 `_pending`、world.step 后才转 `_active`（冒烟等帧后断言）；headless virtual-time 下 RAF 不驱动场景 update/destroy（手动驱动验证 WASD 逻辑与 runDestroy 收敛，阶段 1 同语义）；vite dev 需 `--host 127.0.0.1`（默认绑 ::1 时 127.0.0.1 拒连）；Edge headless 需 `--no-proxy-server`（本机 HTTP_PROXY=127.0.0.1:7897 对回环 502）
- **Java 测试基线**：未触碰，保持 254/0（34 类）不动（无 mvn 重跑，纯前端测试）
- **git**：未 commit（统一 gate，未获授权）；并行登记 P-0801-E

### 2026-08-01 19:13-19:30 — Phaser 阶段2 前端构建独立复核（P-0801-F，台账 #59）
- **独立复核**（非新测试，验证 P-0801-E 声称）：① `npm run build` 重跑通过（tsc -b 0 错误 + vite build 63 modules 16.07s，产物 index-Ccc-CMzG.js 1,814,610 B 与 static 一致）；② dist↔static SHA256 字节比对三方一致（JS/CSS/index.html）；③ bundle grep 阶段2 字符串全命中（/api/script/map、生成地图、重新生成、对局地图、clue_location、spawn_points、map_version、BSP、scriptMap、markZoneSearched、已搜证）+ 阶段1 回归字符串；④ 冒烟复跑 `self_test_stage2.py` 16/16 + `self_test_stage1.py` 10/10 ALL PASS（vite dev --host 127.0.0.1:5199 + Edge headless --no-proxy-server）；⑤ ScenePage.tsx 接线与 ScriptMapScene.ts 修复抽查确认；后端 Java 零改动（git diff src/main/java 为空）
- **Java 测试基线**：不动，保持 254/0（34 类）
- **git**：未 commit（统一 gate，未获授权）；并行登记 P-0801-F


### 2026-08-01 18:39–18:45 — P1 缺陷修复：LLM 剧本生成 JSON 截断（Round 13，217 tests）
- **命令**：mvn test（全量；新测试类先行单跑 3/0 通过后全量复跑）
- **结果**：217 tests / **0 failures** / 30 类 / BUILD SUCCESS（surefire 报告逐类核对：30 类之和 217，0 失败 0 错误）
- **修复（D-023，见 DECISION_LOG）**：ScriptService.generateScript 的 llmClient.callJson(prompt, 600) → **4000**（根因：4 角色剧本 schema v1 JSON 真实输出需 2000-4000 tokens，600 硬截断 → LLMClient 日志 Unexpected end-of-input → 真机 3/3 走 defaultScript 兜底，且 SpeechGate 静默分支不可观测）。全局 maxTokens 排查同步修正 6 处同类大 JSON 调用（均多角色/多条目结构化输出）：ArbiterService 轨道配置 400→600、主持整合 800→1000、TrackRequestService 需求评估 300→600、审批 200→400、Compressor 压缩摘要 150→300、SimulationService 主控轮次 600→1000；短回答类保留（分类 20 / 旁白 120 / 窃听摘要 120 / 单目标 300 / 单场景 300 / 单角色 400）
- **新增**：ScriptServiceMaxTokensTest 3 用例——①mock 返回 2000+ token 完整剧本 JSON（5 角色 × 200 字 intro/secret + 5 线索 + secrets + background 300 字 + truth）→ generateScript 解析成功且 roles/secrets/clues/killer_id/truth 字段齐全、长字段不截断（角色/线索数完整保留）②verify generateScript 必须以 maxTokens=4000 调用 callJson（600 旧值回归即失败）③LLM 空输出仍走 defaultScript 兜底符合 schema（A1-3：secrets 键集合==roles 不回归）
- **回归**：既有 214 基线零破坏（含 LONG-01 10 万字：P50=3ms / P95=5ms / Max=40ms，堆增长 -11.5% 无 OOM——未触发堆测量脆性）；单跑 ScriptServiceMaxTokensTest 3/0（0.007s）
- **文档**：DECISION_LOG 新增 D-023；docs/修改记录.md 台账 #57；本文件 v13
- **git**：未 commit（未获授权）

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


### 2026-08-02 12:50-13:45 �� P0 �����޸���P-0802-A��subagent 035a7279�����Ự�ɵ������� ��˸Ķ���ȫ������
- **��Χ**����� `service/RouterService.java`��runRound �������� + speaker ֧�� + ֹͣ�Զ��ָ� + runTurns/runAutoRounds �ָ����û���Ϣ��ʷ�����˲���Ӳ���� "me"����`controller/SessionController.java`��/api/send ��ȡ player_name����`simulation/SimulationService.java` + `simulation/SimulationController.java`��load-characters ֧����ʽ player_name �����ҿ��ƣ����Ӳ�������� "me"����ǰ�� `store/appStore.ts`����ѯ����ж��������޸�����`components/ScenePage/ScenePage.tsx`��hasMe �ų���ɫͬ�� / me ��ͬ����ʾ / use2D ���� window.open ����Ƕ / ɾ��ԭ�洰�ڡ���ť / �籾ɱ���ɺ��Զ����֣���`components/ChatPage/ChatPage.tsx`��send ȥӲ���� "me"��ȥ�ֹ�˫��չʾ��2D �Ÿ���Ƕ���ء�����ҳ��Ƕ 2D ��壩��`phaser/PhaserSimulationView.tsx`����Ƕ���죺��Ϣչʾ + �������� + player_name ͸������`static/simulation.html`��autoLoadFromApp ���Զ� start + ͸�� player_name��
- **���**��**mvn test ȫ�� 261/0 BUILD SUCCESS**�����߲��䣬������������RouterService ����/���ظĶ����ƻ����� 261 ��������npm run build ͨ����tsc -b 0 ���� + vite 63 modules������ index-CGo4Hc0R.js ��ͬ�� static/��index.html �����Ѹ��£���bundle grep 8 �� P0 �Ķ��ַ��� 7/8 ֱ������ + 1 ��ģ���ַ��������֤���У���ݷ��ԣ�
- **��ע**��ǰ��δ�� headless �����ð�̣�ʱ��Լ�� 14:00 ǰ�չ����Ķ����� tsc + ���� + bundle ��֤����8000 ʵ�����������Ự���𣬺�� java �Ķ����������Ч��δ git commit��δ����Ȩ��
- **git**��δ commit


### 2026-08-02 13:2x-13:5x — P1 批次修复（P1-6 / P1-8 / P1-9，P-0802-B，subagent 70f8a090，主会话重派）—— 纯前端改动
- **范围**：① pi/client.ts（P1-6：request() 空 body 兜底——200 空响应返回 null，不再抛 Unexpected end of JSON input）；② components/ChatPage/ChatPage.tsx（P1-9：加载文案模式门控 werewolf 专属；P1-8：「⚙️ 设置」面板——聊天模式切换（自由对话/导演模式）+ 公告栏显示开关，localStorage roleplay_ann_show）；③ App.tsx（P1-8：移除 AnnouncementBanner/Ticker 全局挂载 + 相关 import）；④ components/AnnouncementBanner.tsx / components/AnnouncementTicker.tsx（P1-8：inline prop 支持 2D 面板内定位；Ticker 空态不渲染 + 「×」收起按钮）；⑤ styles/global.css（P1-8：.ann-banner.inline / .ann-ticker.inline 绝对定位跟随 2D 面板 + .ann-ticker-close 样式）；⑥ phaser/PhaserSimulationView.tsx（P1-8：2D 视图内挂载横幅+公告栏 + 控制条「📢」显示开关）
- **结果**：**npm run build 通过**（tsc -b 0 错误 + vite 63 modules，产物 index-DYdLFONY.js 1,811,160 B + index-BLkB2f6f.css 37.83 kB）；**static 同步完成**（dist↔static SHA256 一致；index.html 引用已更新为 index-DYdLFONY.js/index-BLkB2f6f.css）；bundle grep 确认 P1-8（roleplay_ann_show/聊天设置）与 P1-9（白天讨论中/运行中）字符串均在产物中
- **后端**：零改动（P1-6 优先前端方案；SceneController/CharacterController 未动，mvn 基线 261/0 不变，未重跑）
- **备注**：未跑 headless 浏览器冒烟（时间约束 14:00 前收工，改动均经 tsc + 构建 + bundle 验证）；8000 重启由主会话负责，新 static 产物需重启后生效；未 git commit（未获授权）
- **git**：未 commit

### 2026-08-02 18:5x-19:4x — C-1 批次（P3-10 / P3-11 / 2D UI 重构 / 2D 入口合并，P-0802-C，subagent 6e7d626f）—— 纯前端改动，后端 Java 零改动
- **范围**：① phaser/PhaserSimulationView.tsx（C-1 重构：左地图+右侧聊天面板可折叠（控制条「💬 聊天」按钮 + 面板 ✕，收起=地图全宽）；P0-3 内嵌聊天整合进右面板不双份；消息统一 SimChatMsg 结构带播放状态 status:'pending'|'playing'|'done'（C-2 打字机队列衔接：新世界对话先置 pending 再翻 done 演示状态机制，渲染按 status 样式区分 pending=半透明「⏳ 待播放」/playing=高亮闪烁「▶ 播放中」/done=正常）；P3-10 演讲+广播 demo 迁入（精简版默认折叠：AI 自动演讲/玩家发广播/模式 select merged|auto|split））；② components/ScenePage/ScenePage.tsx（P3-10：删除 demo 面板 section + demo 状态/函数/useEffect；P3-11：maxWidth 960→'none' + 角色列表分类（全部/已选/未选）+排序（默认/A-Z/Z-A）+ 场景列表分类（全部/剧本杀对局（scene_id 前缀 script_）/普通）+排序（useMemo 本地分组）；2D 入口合并：删 use2D checkbox 与 start() 内 roleplay_2d_inline 写库，保留单一「🎮 2D 模拟」按钮）；③ components/ChatPage/ChatPage.tsx（showSimPanel 不再 localStorage 自动展开、toggleSimPanel 纯开关，剧本杀「查看 2D 模拟（内嵌）」onOpen2D 联动保留，面板标题改「左地图 · 右聊天」）；④ styles/global.css（+ .sim-chat-panel/.sim-chat-list/.sim-chat-msg kind/status 样式 + status-tag + sim-blink 动画）
- **结果**：**npm run build 通过**（tsc -b 0 错误 + vite 63 modules，产物 index-BX3fEiRB.js 1,824,095 B + index-BANIh__s.css 38.61 kB）；**static 同步完成**（dist↔static SHA256 字节一致；index.html 引用已更新为 index-BX3fEiRB.js/index-BANIh__s.css）；bundle grep 验证关键字符串全命中（sim-chat-panel/sim-chat-msg、CSS 规则 .sim-chat-msg.status-pending/.status-playing、待播放/播放中/AI 自动演讲/玩家发广播/正式版 merged/💬 聊天/排序：名称/分类：已选/剧本杀对局/2D 模拟（左地图 · 右聊天）/🎮 2D 模拟）；ScenePage 中「合并地基 demo」字符串确认已不存在（demo 面板移除成功）
- **后端**：零改动（git diff src/main/java 无新增；mvn 基线 261/0 不变，未重跑）
- **给 C-2 的接口**：PhaserSimulationView 导出 SimChatMsg 类型（id/who/text/kind/status/ts），status: 'pending'|'playing'|'done' 为播放状态字段，历史列表渲染已按 status 样式区分；世界消息签名状态存 worldSigRef（Map<sig, status>），C-2 打字机队列可直接驱动 pending→playing→done，无需改消息结构
- **备注**：未跑 headless 浏览器冒烟（PhaserSimulationView 为 React 组件，真实后端需登录 token，时间约束内以 build + bundle grep + 源码审查替代）；8000 重启由主会话负责，新 static 产物需重启后生效；未 git commit（未获授权）
- **git**：未 commit

### 2026-08-02 18:53-19:2x — 狼人杀重构非禁动批次（P-0802-D，subagent 15a56863，主会话派单）
- **范围**：① 新增 `src/test/java/com/roleplay/engine/service/WerewolfGameSmokeTest.java`（11 用例 W-1~W-11）；② 前端 `roleplay-v4/frontend/src/api/client.ts`（werewolfInit 改 JSON body `{players, roles}` 全量进局 + 新增 werewolfNightAction/werewolfHunterShoot/werewolfVote/werewolfResolveNight/werewolfStartVoting/werewolfResolveVote 6 个 API 封装）；③ `components/ScenePage/ScenePage.tsx`（startWWGame：roleConfig 计数→player→role map（值用后端枚举名 werewolf/seer/witch/hunter/villager，规避 valueOf("WOLF") 抛异常）+ api.werewolfInit 全量进局（修复 AI 从未进 GameState 根因）+ res.ok 检查（不再静默吞掉）+ 状态展示 alive 数；start() werewolf 分支同步全量场景玩家）
- **结果**：**mvn test 全量 272/0 BUILD SUCCESS**（261 基线 + 11 新用例，35.4s，LONG-01 3.1s 全绿）；单跑 WerewolfGameSmokeTest 11/0；npm run build 通过（tsc -b 0 错误 + vite 63 modules，产物 index-BX3fEiRB.js 1,814.62 kB）
- **现状 bug 证据（禁动未修）**：① customRoles 值 "wolf" → Role.valueOf 抛 IllegalArgumentException（前端已规避传枚举名）；② toMap 构造 visible 但从未 put 进返回 map（狼人互认 API 层缺失，G1-3）；③ 猎人夜间死亡后 hunterCanShoot=false 且 hunterShoot 要求 true → 开枪永久被拒（G1-1 升级）；④ init 不返回 session_id、不注册 router（G0-1）
- **并行冲突**：另一并行批次 C-1（subagent 6e7d626f，2D UI 重构）同时占用 P-0802-C 标记并同改 ScenePage.tsx —— 本批标记顺延改 **P-0802-D**；双方改动共存于最终文件（C-1 只改 use2D/2D 入口区域，本批改狼人杀 init 区域），npm build 产物 index-BX3fEiRB.js 经 bundle grep 双向验证（狼人杀 6 API + '2D 模拟' 入口 + roleplay_2d_inline 已移除）
- **static**：未同步（禁动）。⚠️ 并行批次 C-1 于本批编辑前完成 build 并同步 static `index-BX3fEiRB.js`，该产物**不含**本批狼人杀修复（grep night_action/resolve_vote/hunter_shoot = 0）；本批最终 dist `index-BX3fEiRB.js` 含 C-1 + 本批全部改动，**需主会话核查后重同步 static（index.html 引用更新）+ 8000 重启生效**
- **备注**：禁动文件（WerewolfService/WerewolfController/SSEController/RouterService/ArbiterService/static）零改动；未跑 headless 冒烟（狼人杀前端 UI 面板未做——缺后端 AI 行动器/SSE，留待主人授权批次）；未 git commit（未获授权）
- **git**：未 commit

### 2026-08-02 19:08-19:5x — C-2 批次（P-0802-E，subagent 8aecb2bc）—— 前端打字机流式播放队列（B）+ 一般模式串行调度调研报方案（A）
- **范围**：① 新增 `roleplay-v4/frontend/src/phaser/simChatConfig.ts`（打字机参数集中配置：typingCharsPerSec=3 / typingTickMs=333 / interSentencePauseMs=3000 / pauseTimeoutMs=60000 / maxSentenceChars=60；cleanWorldText 过滤非语言噪音（emoji/特殊符号/零宽字符，保留中文标点）；truncateText 渲染硬截断省略号）；② 改 `phaser/PhaserSimulationView.tsx`（C-2 打字机队列引擎——消费 C-1 SimChatMsg.status 驱动 pending→playing→done 不改消息结构；严格串行：上一段播完+3s 句间停顿→下一段；暂停/恢复=聊天输入框有字冻结播放进度、发送后恢复；60s 暂停超时看门狗跳过当前句（标 done 继续）；用户在场判定=conversation-status 群组成员含玩家名；世界气泡单例=在场只显示当前播放者（lastSpeakerRef→setBubbleFilter）；消息拍平时即清洗+截断（揭示字数与显示文本一致）；智能滚动（新消息或已在底部才滚，打字机逐字不打断上翻））；③ 改 `phaser/SimulationScene.ts`（setBubbleFilter 单例 + computeBubbleLanes 锚定避让层（重叠向上抬最多 4 层）硬约束气泡不重叠）；④ 改 `styles/global.css`（+.typewriter-caret 光标样式）；⑤ 后端非禁动：改 `simulation/conversation/TrackStrategy.java`（+MAX_SENTENCE_CHARS=60 常量，MERGED/WEAK prompt 轻提示每句话不超过60字，与前端 maxSentenceChars 对齐）、`simulation/conversation/GroupStrategy.java`（legacy fallback 两行同提示）；⑥ static 同步（index-4KH0B1ke.js + index-D1bQ2MoB.css，SHA256 dist↔static 一致，index.html 引用已更新）
- **结果**：**mvn test 全量 272/0 BUILD SUCCESS**（基线不变，TrackStrategy/GroupStrategy prompt 改动零破坏：TrackStrategyTest 7/0、SpeechGateTest 24/0、ScriptGameDiscussionTest 4/0 等全回归；LONG-01 全绿）；**npm run build 通过**（tsc -b 0 错误 + vite 64 modules，index-4KH0B1ke.js 1,819,120 B）；static 同步完成（SHA256 一致）；bundle grep 10 项字符串全命中（待播放/播放中/输入时暂停播放/typewriter-caret/字/秒/句间停顿/暂停超时/对话与发言/setBubbleFilter/bubbleLanes）
- **A 部分（一般模式串行调度）结论**：未改码，报方案——执行链 RouterService.runRound（**禁动**）→ AgentExecutor.executeRound（全部角色虚拟线程并行）；上下文 buildAgentContext 只读 memory（仅上一轮及以前消息）；同轮各角色输出在并行完成后的 Step 4 才批量入 memory →「同轮上下文不共享」断点=AgentExecutor 并行执行 + RouterService Step3→Step4 顺序；串行调度最小改动点在 RouterService.runRound（并行 executeRound 改串行循环 + 每角色输出即时入 memory 再构建下一角色上下文）；触及禁动文件按纪律停手，完整方案（改法/风险/测试建议）见 `docs/串行调度方案-20260802.md`，交主会话决策
- **备注**：未跑 headless 冒烟（无浏览器工具可用 + 8000 运行旧 jar 产物无法验证新 bundle，以 build + grep + 源码审查替代）；8000 重启由主会话负责（新 static 产物需重启生效）；未 git commit（未获授权）
- **git**：未 commit

### 2026-08-02 20:1x-20:5x — C-2 批次 A 部分实施：一般模式串行调度（P-0802-F，subagent b938dfb7，主人已授权动 RouterService.runRound）
- **命令**：`mvn compile`（0 错误）→ `mvn test -Dtest=RouterServiceSerialRoundTest`（单跑 4/0 BUILD SUCCESS）→ `mvn test`（全量 surefire 跑批 20:3x 汇总；并发未登记批次失败用例用 `-Dtest=!WerewolfAiPlannerTest` 复核 284/0）
- **结果**：**本批 RouterServiceSerialRoundTest 4/0 全绿**；全量 285 用例中 **284 通过 / 1 失败**——唯一失败 `WerewolfAiPlannerTest.humanWolfWaits`（第 73 行「人类狼未行动→狼刀决策未完成」）属**并发未登记批次**（狼人杀 AI 行动器 G0-2，代码注释同用 P-0802-F 撞标，20:22-20:31 正在改 WerewolfService/WerewolfController/WerewolfAiPlanner/RouterService），**单跑复现失败（`-Dtest=WerewolfAiPlannerTest` 9 中 1 失败），与本批改动无关**（本批零触碰狼人杀后端=禁动）；排除该用例后 **284/0 BUILD SUCCESS**（272 基线 + 本批 4 + 并发批次 WerewolfAiPlannerTest 8 个通过用例）
- **新增**：`service/RouterServiceSerialRoundTest` 4 用例——①serial=true 同轮上下文共享：mock LLM 捕获 USER 上下文，断言后发言者 B 的上下文包含 A 本轮发言「A发言：我看到了碎玻璃。」且内存顺序 A→B、输出顺序 A→B；②serial=false 默认并行：两角色均有输出且入史（行为不变）；③serial=false 不共享同轮上下文：并行路径 LLM 收到的上下文不含任一角色本轮发言；④配置开关：AppConfig.RoundConfig.serial 默认 false + setSerialRound(true) 生效
- **改动**：RouterService（runRound Step3-4 重排：`serialRound` @Value（roleplay.round.serial 默认 false）分支——false 走既有并行 executeRound 逐字节不变；true 走新增 `executeRoundSerial`：按轨道顺序×轨道内 agent 顺序（PLAYER>DM>NPC，computeSerialPriority）逐个生成，每 agent 输出完成**立即** memory.addMessage + SSE broadcastAgentOutput + 收集，后发言者 buildAgentContext 读 memory.getAgentContext 即含前面角色本轮已完成的发言=同轮上下文共享；上下文经 `agent.generateWithContext(context, token)` 显式传入 LLM（并行路径构建的 context 实际未传给 generateSync 属既有行为，串行路径按方案文档显式传入）；D1 中断语义保留：每步注册 AgentTask+CancellationToken、检查 running、TaskCancelledException 中断循环返回 cancelled、失败 agent 输出占位不入史；新增 setSerialRound/isSerialRound）；AppConfig（RoundConfig 补 serial 字段+getter/setter）；application.yml + application-test.yml（`roleplay.round.serial: false` 双份）
- **回归**：272 基线零破坏（本批仅在 RouterService 增分支，默认 false 路径逐字节不变；TrackStrategyTest/SpeechGateTest/ScriptGameDiscussionTest/LONG-01 等全绿）；⚠️ 并发未登记批次在 RouterService 的 werewolfGame 角色卡改动与本批 serialRound 改动**不同区域共存无冲突**，本批测试在其合并后重跑仍 4/4 绿
- **git**：未 commit（统一 gate，未获授权）；并行登记 P-0802-F（本批）+ P-0802-未知占位（并发批次，按规则 5 登记待溯源）

### 2026-08-02 20:13-21:1x — 狼人杀后端授权批次（P-0802-H，subagent 53b74666，主人已授权解禁 WerewolfService/WerewolfController/RouterService）
- **命令**：`mvn compile`（0 错误 ×3）→ `mvn test -Dtest=WerewolfAiPlannerTest,WerewolfGameFixTest,WerewolfGameSmokeTest`（28/0）→ `mvn test`（全量首跑 293 中 1 失败）→ `-Dtest=LongTextStabilityTest`（单独复跑 1/0 通过）→ `mvn test`（全量复跑 **293/0 BUILD SUCCESS**，LONG-01 2.85s）
- **结果**：**全量 293/0**（272 基线 + 本批 WerewolfAiPlannerTest 9 + WerewolfGameFixTest 8 + 并行 C-2-A RouterServiceSerialRoundTest 4）。首轮全量唯一失败=LongTextStabilityTest 堆增长 49.6%>30%——台账多次记录的既有堆测量脆性（#37 37.4%/#40 34.8%/#41 34.8% 同款，单跑即过），单独复跑 1/0 通过，全量复跑全绿，与本次改动无关（本批零触碰 Compressor/记忆链路）
- **新增**：`WerewolfAiPlannerTest` 9 用例（P1 狼刀不刀狼+决策标记 / P2 狼队共刀单目标 / P3 单狼局人类狼不代刀 / P4 预言家查验随机存活+结果=目标角色 / P5 女巫首夜救被刀者+毒决策放行不消耗 / P6 女巫后续夜概率毒 1.0 必毒 vs 0.0 不毒（同种子对照）/ P7 全员 AI 夜间完成后 nightComplete=true / P8 猎人反杀目标（有存活/只剩自己空串）/ P9 白天投票村民随机非己+狼队共投非狼+已投不重复）；`WerewolfGameFixTest` 8 用例（F1 parseRole 宽容解析中英文别名+非法 null / F2 initGame 别名 customRoles 不抛异常+正确解析 / F2b 非法角色回退村民 / F3 controller init 返回 session_id+verify router.setWerewolfGame / F4 SSE 事件流：init 玩家·人类角色→夜间结算→白天讨论→讨论发言（werewolf_speech+transcript）→自动投票→等待真人→真人投票后结算推送 / F5 autoPlay 全 AI 局自动打完到 ENDED（winner 非空+game_over 推送+讨论 transcript 非空） / F6 人类白天发言 discussionSay 入讨论引擎 transcript / F7 AI 猎人夜间死亡自动反杀（狼刀猎人→反杀一名→狼胜终局））；改 `WerewolfGameSmokeTest` W-5（原锁定「夜间死亡后开枪被拒」旧 bug 断言→修复后：可开枪反杀一次+机会仅一次）、W-10（原锁定「toMap 无 visible 键」→修复后：狼人互见+村民仅见自己）
- **改动**：后端 8 文件——WerewolfService（autoPlay 自动推进闭环：startNight/runAiNightActions/advanceIfNightComplete/isNightComplete/nightComplete 静态/startDayDiscussion/runDiscussionEngine/finishDayDiscussion/discussionSay/resolveVoteAuto（D7 审批门保留+auto-approve-ms 自动批准）/scheduleAutoApprove/十类 werewolf_* SSE 推送（SseBroadcaster 接口注入，null 守卫）/parseRole 宽容解析/toMap visible 修复/猎人开枪修复/autoShootDeadHunter/autoShootExiledHunter/statusMap；GameState +nightDecisions/discussionTranscript/pendingHumanEvents/discussionActive/autoPlay）、新增 WerewolfAiPlanner（纯规则零 LLM：狼共刀/预言家验/女巫救毒/猎人反杀/白天投票，种子可固定）、WerewolfController（init 返回 session_id+router.setWerewolfGame+setHumanPlayers+setAutoPlay+notifyGameInit+startNight、新增 POST /api/werewolf/discussion_say、status 附加 session_id/waiting_human、@Value auto-play 直构测试=off）、RouterService（+setWerewolfGame/clearWerewolfGame+狼人杀角色卡（本人身份+狼人互认+阶段）注入 buildAgentContext，与并行 C-2-A serialRound 改动不同区域共存）、application.yml/application-test.yml（+roleplay.game.werewolf.{ai-night-actions,auto-play,auto-approve-ms,witch-poison-probability}）；前端 8 文件——client.ts（+werewolfDiscussionSay）、useSSE.ts（+4 事件注册）、appStore.ts（+werewolfSessionId/Alive/Visible/Discussion/Winner/VoteCount/Approval 9 状态）、App.tsx（+4 SSE 分支：night_result/vote_update（含 pending 审批态）/speech/status + player_eliminated 支持无角色公告 + normalizeWerewolfPhase 模块级）、ChatPage.tsx（+WerewolfActionPanel：夜间刀/验/救/毒、白天讨论发言+记录、投票+审批批准驳回、已出局猎人开枪；+狼人杀 3s 轮询；autoPlay 分支改后端驱动不再 startRound（原=「仍按一般模式交流」根因⑭））、ScenePage.tsx（两处 werewolfInit 存 session_id+过时注释更新）、global.css（+ww-action-box/ww-target-chip/ww-discussion 等样式）；static 同步 index-Bu8YksJU.js + index-aWRy_sYe.css（SHA256 dist↔static 一致，index.html 引用已更新）
- **验证**：npm run build 通过（tsc -b 0 错误 + vite 64 modules）；bundle grep 关键字符串全命中（werewolfDiscussionSay/werewolf_night_result/werewolf_vote_update/werewolf_speech/ww-target-chip/ww-action-box/btn-small）；改动文件 UTF-8 无 BOM；未跑 headless 冒烟（无浏览器工具+8000 旧 jar 产物，build+grep+源码审查替代）
- **禁动边界**：本批仅动 WerewolfService/WerewolfController/RouterService（setWerewolfGame+角色卡，与并行 C-2-A 共存）/yml/前端/static；SSEController/ArbiterService/审批主链路零改动（werewolf_* 经 SseBroadcaster 接口复用既有 broadcast 管线）；未 git commit（统一 gate 未获授权）


### 2026-08-02 21:26-22:0x — P-0802-G 批次：串行调度开启 + 2D 视图被场景设置 UI 遮盖修复 + 用户在场判定自查（subagent ca028cac，主人反馈三件事）
- **命令**：`npm run build`（tsc -b 0 错误 + vite 64 modules，index-4rQ391AJ.js 1,835,577 B）→ dist↔static SHA256 三方一致 → `mvn test` 全量（surefire 41 类汇总 **302/0 BUILD SUCCESS**，LONG-01 PASS 2.858s）
- **①串行调度开启（一般模式）**：`src/main/resources/application.yml` `roleplay.round.serial: false → true`（保留开关注释说明可随时切回 false；application-test.yml 不动——测试用显式配置）。自查 RouterService.runRound serial 分支（P-0802-F 既有授权实现）逻辑正确：executeRoundSerial 按轨道顺序×轨道内 agent 顺序（PLAYER>DM>NPC，computeSerialPriority）逐个生成，每 agent 输出完成立即 memory.addMessage + SSE 推送 + 收集，后发言者 buildAgentContext 读 memory.getAgentContext 含前面角色本轮已完成的发言（同轮上下文共享）；上下文经 agent.generateWithContext(context, token) 显式传入 LLM；D1 中断语义保留（注册 AgentTask+CancellationToken、检查 running、TaskCancelledException 中断循环返回 cancelled、失败输出占位不入史与并行一致）；SSE visibleTo 传 List.of() 与并行路径 AgentExecutor 构造一致无漂移——**无 bug 需修**。serial=true 后全量 302/0 全绿（RouterServiceSerialRoundTest 4/4 含「serial=true 同轮上下文共享」「serial=false 默认并行行为不变」全部通过，测试不依赖主 yml）。
- **②2D 视图被场景设置 UI 遮盖修复**：根因——ScenePage 进入 2D（showPhaserSim=true）时角色选择面板+场景选择面板仍全量渲染在 2D 视图上方（2D 视图被挤在页面底部），2D 视图下方 UI（聊天/输入）被推出版心。修复——showPhaserSim=true 时整页折叠场景设置区：只渲染顶部小标题行（2D 模拟 + 角色数 pill + 「✕ 退出 2D（返回场景设置）」按钮）+ PhaserSimulationView（height 480→640 占主体）；角色/场景面板、进入按钮区、状态行全部隐藏；退出恢复原布局。与 C-1 可折叠右聊天面板、公告栏（2D 内触发，P1-8）天然兼容（均在 PhaserSimulationView 内部，未受影响）。npm run build 通过 + static 同步（index-4rQ391AJ.js + index-aWRy_sYe.css，SHA256 dist↔static 一致，index.html 引用已更新，被取代的 index-Bu8YksJU.js 已删除）+ bundle grep 命中「退出 2D/返回场景设置/2D 模拟」。
- **③用户在场判定自查（结论：链路存在且正确，不强行改码）**：前端判定（C-2 实现）——PhaserSimulationView `playerPresent = groups.some(g => g.participants.includes(playerName))`，groups 来自每 4s 轮询 GET /api/simulation/conversation-status；在场 → setBubbleFilter(当前播放者)=世界内只显示当前播放者气泡单例（单轨）；不在场 → 多气泡并行+SimulationScene 锚定避让层。后端链路——SimulationController→SimulationService.getConversationStatus→ConversationManager.getStatus（groups[].participants=AgentState.getAgentName()）。**关键确认**：玩家角色（playerControlled）在 2D 世界**不会**进自动生成的邻近对话组（ConversationManager.tick 对 playerControlled 跳过自动建组，仅当玩家发消息时以 DYAD 组纳入 player+nearest）——即「用户在场」=玩家主动发言进对话组时才成立，判定真实生效（4s 轮询可见）；依赖的后端字段（participants）已完整提供，无缺失。「晕」主因=串行开关未开（问题 1 已修复：一般模式后发言者含前者发言后观感应显著改善），2D 侧在场判定本身无 bug。
- **禁动边界**：RouterService 仅自查零改动（serial 分支为 P-0802-F 既有授权实现）；未动 ArbiterService/审批/狼人杀/SSE 主链路；⚠️ 与 P-0802-H（待核查）/P-0802-I（进行中）共用 static/前端源码，本批构建基于含其改动的完整源码，产物已含全部前端改动
- **git**：未 commit（统一 gate 未获授权）

### 2026-08-02 21:2x-22:1x — 狼人杀阶段1遗留项批次（P-0802-I，subagent 114daec4）—— 女巫获知被刀者机制 / 讨论引擎 per-game 隔离 / werewolf_* SSE 会话定向 / 快照落库+重连+联机房绑定
- **命令**：\mvn test\（本批 9 新用例单跑 9/0 通过 → 全量 surefire 41 类汇总 **302/0 BUILD SUCCESS**，LONG-01 PASS；首轮全量 1 失败为 LongTextStabilityTest 堆增长 49.4%>30%（台账多次记录的既有堆测量脆性 #37/#40/#41 同款），单独复跑 1/0 通过后全量复跑 302/0 全绿）→ \
pm run build\（tsc -b 0 错误 + vite 64 modules，index-DEFyKJ5G.js 1,840,208B）→ dist?static SHA256 一致 + index.html 引用更新
- **新增**：\service/WerewolfStage1Test\ 7 用例——①S1 AI 女巫获知后再决策（saveProbability=0 不救记 nosave 保留解药被刀者死亡 / =1 必救平安夜）；②S2 人类女巫先获知再行动（未获知救/不救均拒、获知后只能救被刀者、nosave+nopoison 决策放行夜间完成判定、toMap 女巫视角 witch_victim 仅本人可见）；③S3 werewolf_witch_info 获知事件（victim=狼刀目标+session_id+定向，女巫已死不推送）；④S4 讨论引擎 per-game 隔离（同玩家名两局并发讨论：引擎实例 assertNotSame、transcript 只含本局成员、werewolf_speech 定向各自对局）；⑤S5 werewolf_* SSE 定向（两局事件各回各局，payload session_id 与定向一致，无全局广播残留）；⑥S6 快照落库+跨实例恢复（mock ScriptRepository 装配真实 DatabaseService：新实例 resumeGame restored=true 阶段/存活/角色一致、终局 terminal+winner、不存在对局报错）；⑦S7 联机房绑定（init 带 room_code 回显大写、resume 按 session_id/room_code 定位同局、未绑定/缺标识报错、resume 后 status 定位）；\controller/SSEControllerSessionTest\ 2 用例——①定向推送只送达匹配会话连接（A 会话事件 B 连接零接收、无过滤连接零接收、无匹配静默丢弃、全局 broadcast 仍全量送达向后兼容）；②空 session_id 定向回退全局、complete 后不再接收
- **改动**：后端 7 文件——WerewolfService（G1-2：GameState +witchInformed/witchDeclinedSave/witchInfoSent、recordNightAction save 限被刀者+新增 nosave/nopoison、nightComplete 放行两决策、notifyWitchVictim 定向推送、toMap 女巫视角 witch_victim；讨论引擎 per-game 三 Map 隔离替代实例级共享；快照 saveSnapshot/restoreFromSnapshot/resumeGame+宽容转换辅助（快照名「对局快照:ww:<sid>」复用 getLatestScriptSnapshot 零新表）、4 参 @Autowired 构造注入 DatabaseService（3/2 参保留委托）；werewolf_* 全改 sse(sid,event,payload) 定向）、WerewolfAiPlanner（狼刀后置 witchInformed、女巫获知后按 saveProbability 决策救/不救、4 参 planNight 重载默认 1.0）、WerewolfController（+POST /api/werewolf/resume、init 可选 room_code 绑定 roomGames 并回显、getStatus 支持显式 session_id+2 参重载）、SseBroadcaster（+broadcastToSession 默认回退全局零破坏）、SSEController（stream ?session_id= 过滤+broadcastToSession 定向送达+getConnectionCount(sid)）、application.yml/application-test.yml（+roleplay.game.werewolf.witch-save-probability: 1.0）；前端 5 文件——client.ts（+werewolfResume、werewolfStatus 支持 session_id、werewolfInit 可选 room_code）、useSSE.ts（连接带 ?session_id=，sessionId 变化重连）、appStore.ts（+werewolfWitchVictim 三处 reset）、App.tsx（witch_info 消费 victim、night 清空、useSSE 传 werewolfSessionId）、ChatPage.tsx（女巫获知面板：被刀者展示+救被刀者/不使用解药/不用毒/毒药选目标；狼人杀恢复对局入口；轮询+resume 消费 session_id/witch_victim）；static 同步 index-DEFyKJ5G.js（SHA256 一致，index.html 引用已更新）
- **回归**：既有 293 基线零破坏（WerewolfGameSmokeTest W-3 女巫救被刀者兼容获知约束、WerewolfGameFixTest F4/F5 autoPlay 闭环全绿、WerewolfAiPlannerTest P5/P6 默认救概率 1.0 兼容）；与并行批次 P-0802-G（application.yml serial:true）/C-2-A（RouterService 串行区）共存零冲突（本批零改动 RouterService/ArbiterService/审批；SSEController 仅新增定向方法，全局 broadcast 保留）；⚠️ static/assets 现存孤儿 index-4rQ391AJ.js（P-0802-G 21:35 产物，已被本批更完整产物取代）+ index-Dn8o7YSC.js（本批第一版中间产物），删除需主人确认
- **git**：未 commit（统一 gate 未获授权）；并行登记 P-0802-I ✅；DECISION_LOG D-028（任务书指定 #74/D-0xx 被并行批次 P-0802-G 占用，按撞号顺延先例登记 #75/D-028）


### 2026-08-02 22:0x-23:0x — 狼人杀范围外遗留项批次（P-0802-J，subagent c40c6318）—— 剧本杀讨论引擎 per-game 隔离 / script_* SSE 会话定向 / 狼人杀 resume roleKey 防冒充
- **命令**：`mvn test -Dtest=ScriptGamePerGameIsolationTest,WerewolfRoleKeyTest,SSEControllerSessionTest,WerewolfStage1Test,ScriptGameDiscussionTest,ScriptGameEndedTest,ScriptGameResumeTest,ScriptGamePhaseAnnouncementTest`（8 类定向 33/0 BUILD SUCCESS）→ `mvn test` 全量（surefire 43 类汇总 **309/0 BUILD SUCCESS**，LONG-01 6.783s PASS，无堆测量脆性波动）→ `npm run build`（tsc -b 0 错误 + vite 64 modules，index-Bp4ixzCe.js 1,841,576B）→ dist↔static SHA256 三方一致 + index.html 引用更新 + bundle grep 全命中
- **①剧本杀讨论引擎 per-game 隔离**：根因=P-0802-I 仅隔离狼人杀侧，ScriptGameService 讨论引擎仍 service 实例级共享（D-012 已知限制「多局并发讨论互覆世界」剧本杀侧未修）；修复=ScriptGameService 三 Map（discussionWorlds/discussionConversations/discussionDirectors）替代原实例级字段（worldDirector/discussionWorld/discussionConversation），ensureDiscussionEngine(sessionId) computeIfAbsent 懒创建，runDiscussionEngine/buildRoundGate（门控内按 game.sessionId 取本局 director/world）/pickIceBreaker(game,director)/getDiscussionGoal 全部改取本局实例，构造器不再预建共享 worldDirector；新增测试钩子 getDiscussionConversation/World/Director(sessionId)。新增 ScriptGamePerGameIsolationTest 2 用例——I-1 同 service 两局并发：三件套实例 assertNotSame；I-2 两局并发讨论：发言记录只含本局成员、A 局人类发言入 A 局记录且不串入 B 局、B 局导演不含 A 局玩家目标
- **②script_* SSE 会话定向**：根因=D-013 已知限制「script_* 仍全局广播，多局并发各局事件串到同一连接，前端按 session_id 字段区分」；修复=SSEController broadcastScriptPhase/Status/Reveal 三 helper 内部改走 broadcastToSession（P-0802-I 已就绪的定向通道；sessionId 为空回退全局零破坏，全局 broadcast 不变），前端 App.tsx SSE 连接按当前模式选会话（script→scriptSessionId / werewolf→werewolfSessionId / 其他→无过滤），scriptSessionId 由 ScenePage init / ChatPage resume / 轮询回写 store（appStore +scriptSessionId 字段+setter+reset）。SSEControllerSessionTest +P3 用例：script_phase/status/reveal 只送达注册该 session 的连接、其他会话与无过滤连接零接收、全局广播（announcement）仍全量送达
- **③狼人杀 resume roleKey 防冒充**：根因=P-0802-I 的 /api/werewolf/resume 按 session_id/room_code 定位无 roleKey 校验（D-028 明确「后续可对齐 C3 补 roleKey」），任何客户端拿到 session_id 即可恢复任意角色；修复=对齐剧本杀 C3 roleKey 体系——WerewolfService.GameState +playerKeys，initGame 每玩家发放唯一 UUID，toMap 仅向本人暴露 role_key（匿名/他人视图不含防泄露），resumeGame 改三参 (sessionId, player, playerKey) 强制校验（缺 key / 错 key / 他人 key 冒充 / 玩家名与 key 不匹配 全部拒绝「身份校验失败」；仅凭 key 可反查玩家恢复），快照落 player_keys + 恢复（跨实例重启后原 key 仍有效），+isPlayerKeyValid/getRoleKey/findPlayerByKey/findSessionByPlayerKey/getPlayerKeys；WerewolfController resume 收 player_key 校验通过才登记玩家会话 + GET /api/werewolf/keys 全员令牌分发端点；前端 client.ts werewolfResume 类型 +player_key + werewolfKeys 封装、ChatPage 恢复入口 roleKey 必填输入 +「我的 roleKey」展示（init/轮询/resume 回写 store.werewolfRoleKey）、ScenePage 两处 werewolfInit 存 role_key。新增 WerewolfRoleKeyTest 4 用例（R-1 init 发放唯一 key 仅本人可见 / R-2 resume 正反例含仅凭 key 反查 / R-3 快照跨实例恢复后原 key 仍有效 / R-4 keys 端点）；WerewolfStage1Test S6/S7 resume 调用适配三参（7/0 仍全绿）
- **回归**：既有 302 基线零破坏——ScriptGameDiscussionTest 4/0（per-game 改造后 A3-1~A3-4 不回归）、ScriptGameEndedTest 3/0（mock SSEController verify broadcastScriptPhase 不受内部改定向影响）、ScriptGameResumeTest 8/0、ScriptGamePhaseAnnouncementTest 2/0、WerewolfGameFixTest/WerewolfGameSmokeTest/WerewolfAiPlannerTest 零改动
- **禁动边界**：RouterService/ArbiterService/审批/狼人杀主状态机（autoPlay/AI 行动器）零改动——本批仅动 ScriptGameService（讨论引擎隔离）/SSEController（仅 helper 内部改定向，全局 broadcast 保留）/WerewolfService（+roleKey 相关，P-0802-I 已授权改动面上增量）/WerewolfController（resume+keys）；与并行批次共存：C-2/C-2-A（RouterService 串行区）零触碰，P-0802-G/I 前端改动全保留（本批构建基于含其改动的完整源码）
- **git**：未 commit（统一 gate 未获授权）；并行登记 P-0802-J；DECISION_LOG D-029

### 2026-08-02 22:28-23:0x — 改造方案 Phase 1：身份字段落地（P-0802-P1-demo，subagent a5d39dde）
- **命令**：`mvn compile`（0 错误）→ `mvn test -Dtest=CharacterRenameValidationTest`（单跑 10/0 BUILD SUCCESS）→ `mvn test`（全量 surefire 44 类汇总 **319/0 BUILD SUCCESS**，LONG-01 2.879s PASS）→ `npm run build`（tsc -b 0 错误 + vite 64 modules，index-DXQ1IdZ-.js 1,831.32 kB）
- **①数据模型**：`db/entity/CharacterEntity.java` +playerId 列（`@Column(unique = true, nullable = true)`，一角色最多绑一玩家，方案 §3.2）+ 5 参构造重载（旧 4 参构造委托 null 兼容既有调用点）；`db/repository/CharacterRepository.java` +`findByPlayerId`；`db/service/DatabaseService.java` saveCharacter 5 参重载（旧 4 参签名委托 null 保留零破坏；playerId 非空显式写入、为空保留既有绑定=改名迁移绑定不解绑）+ saveAllCharacters 透传 player_id + entityToMap(CharacterEntity) 输出 player_id 键
- **②Controller（撞名校验 ① 一并落地，方案 §5 层 ①）**：`controller/CharacterController.java` create/update/batch 透传 player_id 落库；同名 → `409 {error, detail: 角色名已存在: xxx}`（update 排除自身：newName != 旧名 才查撞名）；playerId 已被其他角色占用 → 409「该玩家已绑定角色」；batch 整批预校验（库内已有 ∪ 批内重复）任一撞名整批不落库；DataIntegrityViolationException 兜底（DB unique ③层并发窗口）→ 409 + 回滚内存列表，不 500
- **③新服务**：`service/PlayerIdentityService.java`（新建）——`resolveCharacterName(playerId)` → 当前绑定角色名（CharacterRepository.findByPlayerId 反查，纯 DB 零缓存=解析式支柱，改名无需同步缓存）；`resolvePlayerId(characterName)` 反查；入参空白/未绑定 → Optional.empty（调用方回退 player_name 字符串，方案 §3.3 优先级）
- **④前端**：`api/client.ts` 导出 `getPlayerId()`（crypto.randomUUID 生成 + localStorage 'playerId' 持久化，同浏览器身份稳定；非安全上下文回退 'pid-'+Date.now 前缀）+ createCharacter/updateCharacter body 自动携带 player_id（data 显式提供时以 data 为准透传）；`store/appStore.ts` +playerId 字段（getPlayerId 初始化）；`types/index.ts` Character +player_id?（可选，既有消费零破坏）
- **⑤新增测试 `controller/CharacterRenameValidationTest` 10 用例（方案 §8 用例 5）**：①a PUT 改名撞名 → 409 且原同名角色 persona 未被覆盖、被改名角色仍在 / ①b PUT 同名自更新（排除自身）→ 200 正常更新 / ②a create 撞名 → 409 原数据未覆盖 / ②b batch 撞名（批内一项撞库内已有）→ 409 整批不落库 / ②c batch 批内重复名 → 409 任何一项不落库 / ③a create 带 player_id → 落库 + findByPlayerId 反查命中 + 解析器双向 resolve + GET 列表透传 / ③b update 改名 → 绑定随角色迁移（findByPlayerId 反查到新名、旧名行已删）/ ③c batch 带 player_id → 透传落库反查命中 / ⑤ 无 player_id 创建 → 200 响应无 player_id 键、反查为空（现状行为零变化）/ ④ 连续撞名 409（PUT/POST/batch）后内存列表（GET size）与 DB（count）一致、原数据完好
- **回归**：既有 309 基线零破坏——无 player_id 路径全走旧逻辑（create/update/batch 行为逐字节不变）；RouterService/ArbiterService/审批/狼人杀/剧本杀/SSE 主链路/static 零改动；本批前端构建基于含 P-0802-J 改动的完整源码
- **已知限制（demo 预期行为）**：前端 createCharacter/updateCharacter 现自动携带 player_id——已绑定一个角色后，在角色库再创建/编辑第二个角色会命中唯一约束 409（身份模型「一个玩家最多绑定一个角色」约束生效，属 Phase 1 demo 预期；Phase 4 前端收尾时将改为仅玩家本人角色创建携带 player_id / 角色库改名弹窗改调 POST /api/player/rename）
- **static**：未同步（任务范围仅 `npm run build` 验证；AGENTS.md 硬性约束 5 static 禁动——8000 重启与 static 同步由主会话负责）
- **git**：未 commit（统一 gate 未获授权）；并行登记 P-0802-P1-demo

### 2026-08-02 22:26-23:0x — 2D 视图 UI 遮挡修复（P-0802-L，subagent 7777c377）—— 纯前端批次（后端 Java 零改动，mvn 基线 319/0 不动）
- **命令**：`npm run build`（本批构建 tsc -b 0 错误 + vite 64 modules，产物 index-Ct8E7agO.js 1,874,964B + index-ByvBQn5e.css；**最终 dist/static 产物为并行批次 P-0802-P1-demo 基于含本批改动的完整源码重建的 index-DXQ1IdZ-.js 1,841,939B**——本批原构建被其取代，内容已逐一核实含本批全部改动（script-mode/max(480px/calc(100vh - 210px)））→ static 同步（SHA256 dist?static 三方一致，index.html 引用更新）→ bundle grep 全命中 → Edge headless + CDP 浏览器实测（本地代理 8099→8000，剥离 Origin 绕过后端 CORS 白名单校验；cdp_driver_L.mjs 复用 G2 调研场景；**重构建后 static 同步 index-BFSCKpeC.js**）
- **①剧本杀模式 ChatPage 工作区 grid 修复（主因）**：根因=`.workspace` 默认 2 列 grid（140px minmax(420px,1fr)），script 模式渲染 3 个 children（panel-left + 剧本杀状态面板 panel-werewolf + chat-main）却无专属类 → grid 自动放置 chat-main 到第 2 行第 1 列（140px 窄条），内嵌 2D 面板被挤压几乎不可见、剧本杀状态面板被拉成整行全宽；修复=ChatPage workspace 容器按模式加 `script-mode` 类 + global.css 新增 `.workspace.script-mode{grid-template-columns:140px 260px minmax(420px,1fr)}`（与 werewolf-mode 同款三列）+ 响应式（≤1080px 同三列 / ≤900px 收两列且 panel-werewolf 隐藏（既有通用规则）/ ≤760px 单列面板全隐）
- **②ScenePage 2D 全屏 46px 底部溢出（次要）**：根因=PhaserSimulationView 固定 height=640 + 非 host 开销 198px（面板 padding 64 + 标题行 51 + Phaser 工具条/折叠条 83），较矮视口（innerHeight≈792 时内容底 838）底部溢出产生滚动；修复=height 改 `max(480px, calc(100vh - 210px))`（CSS 实时自适应 + 12px 余量 + 480px 保底；Phaser Scale.FIT 缩放不破渲染）
- **③Bug 记录（只记录不改，后端归 P-0802-J/后续）**：client.ts L149-158 `scriptStartDiscussion/scriptStartVoting/scriptResolve/scriptFinish` 硬编码 `{ session_id: '' }`——P-0802-J 已实现 script_* SSE 会话定向 + 前端按 mode 选会话连接，但四动作端点仍传空 session_id；**实测确认该 Bug 在 P-0802-J per-game 隔离后端下已实际阻断流程**（body session_id="" 非 null → `getOrDefault` 返回空串 → ensureDiscussionEngine("") 定位不到对局 → startDiscussion 不推进，前端静默 catch 后 phase 仍 INVESTIGATION，2D 按钮永不出现）；修复建议：四调用点 body 改 `{ session_id: store.scriptSessionId || '' }`（前端改，归后续批次；后端 ScriptController 亦可改 getOrDefault 语义：空串回退 currentSessionId）
- **③b 顺带修复 scriptInit 超时（非任务书 Bug，验证阻塞点）**：client.ts scriptInit 默认 60s 超时 < 真实 LLM 生成 72.4s → AbortError（bundle 内 `signal is aborted without reason`）→ genScript 静默失败停在场景页；补 `timeout: 120000`（与 scriptMap 对齐）后一步式进局成功
- **④浏览器实测结果（Edge headless + CDP，加载新产物经代理 8099→8000，后端为运行中 P-0802-J jar 零触碰）**：scene2d 三档视口 1440×900 / 792 / 700 → overflowY 全 0（修复前 792 视口：bodyScrollH=806 overflowY=14；900 视口修复后 host 690 内容底 888 有 12px 余量）；chat2d 剧本杀完整链路（选 2 角 → AI 生成剧本 72.4s 真机 → 自动进对局 → 结束搜证 → 注入真实 session_id 推进讨论 → 查看 2D 模拟）→ script 模式 workspace 几何：grid `140px 260px 1040px`（panel-left 140px + panel-werewolf 260px + **chat-main 1040px，修复前 chat-main=140px 窄条**）；内嵌 2D 面板（phaser-sim-view 1040×503，canvas 700×420 渲染正常）完整可见，bodyScrollH=900 overflowY=0；auditOverlay 唯一提示项=2D 视图内部公告栏 `.ann-ticker.inline`（P1-8 设计内组件，z=30 绝对定位于 2D 面板内可开关），非外来 UI 遮挡
- **改动**：前端 3 文件（ChatPage.tsx +1 类名、global.css +7 行规则、ScenePage.tsx +1 高度表达式）+ static 同步 + tools 新增 3 个实测工具（dev_proxy.mjs / cdp_driver_L.mjs / probe_scene.mjs，纯测试工具不入生产）；后端 Java 零改动
- **禁动边界**：本批零改动任何 .java（P-0802-P1-demo 后端 Java 改动由该批次自管）；P-0802-A~K 改动全保留（本批源码修改基于已提交工作区，构建基于含全部既有改动的完整源码）
- **git**：未 commit（统一 gate 未获授权）；并行登记 P-0802-L

---

## Round 31 / v31（2026-08-03 12:54，P-0803-D 地图增强批次，主会话实施）

- 全量 **359/0 BUILD SUCCESS**（52 类；352 基线 + ScriptMapCoverageTest 7 新用例，LONG-01 PASS，无堆测量波动）
- 相关类单跑：ScriptMapCoverageTest 7/0 + ScriptMapServiceTest 10/0 + ScriptMapPersistenceTest 2/0（19/0）
- 改动：ScriptMapService（token 800→4000 + 覆盖 pass）、ScriptGameService（initGame 自动 generateMap）；适配 M5/M5b/M6/M6b（自动串联语义 + eq 4000）
- 日志实证：BSP coverage pass 3→6 zones（BSP 兜底地图补齐线索 zone）；LLM coverage +1（缺 zone 自动补）
- 零改动禁动文件（RouterService/ArbiterService/审批/狼人杀/SSE/static）；未 git commit

## Round 32 / v32（2026-08-03 13:1x，P-0803-F 轨道系统用户加入后端原语批次，coder subagent 4b06e1b3）

- 全量 **372/0 BUILD SUCCESS**（53 类；359 基线 + ConversationJoinTest 13 新用例，LONG-01 PASS）
- 新增 ConversationJoinTest 13 用例：①加入成功（成员表/在场 inConversation+冻结/轨道重算含玩家 MERGED）②b status participants 反映加入（A、B、P 插入序）③重复加入拒绝 ④目标组不存在 ⑤玩家角色不存在/不在场 ⑥已在组 ⑦加入后 tick 保持不被踢出 + 不新建多余 DYAD 双路径（isBusy 防护）⑧加入后玩家发言复用 executeRound 玩家分支（runScriptDiscussionRounds 消费 currentMessage 入轮次）⑨离开状态还原+组存活+轨道重算 ⑩离开非成员/不存在组拒绝 ⑪最后成员离开自动解散 ⑫ConversationGroup 上限原语（满员/重复/移除后可再加）⑬DYAD 组上限 2 接线（真实 tick 建组后第三方加入被拒）
- 回归：既有 ConversationManager/SimulationOrchestrator/2D 相关测试全部通过（零破坏）；单跑 ConversationJoinTest 13/0
- 改动：ConversationGroup（+addParticipant/removeParticipant/上限字段 maxParticipants/成员访问器同步化）、ConversationManager（+joinGroup/leaveGroup JoinResult 原语 + recomputeTrackAssignments 抽取复用 + tick DYAD 双路径 isBusy 防护 + startGroup DYAD 上限 2）、SimulationService（+joinGroup/leaveGroup 委托）、SimulationController（+POST /api/simulation/group/{groupId}/join·leave 端点）
- 零改动禁动文件（RouterService/ArbiterService/审批/狼人杀/SSE/static/前端）；未 git commit；8000 重启由主会话负责

- 前端注记（2026-08-03 13:08，P-0803-E 地图增强前端批次）：npm run build 通过（65 modules，index-DOgX25L2.js 已同步 static，index.html 引用更新）；后端 Java 基线 **359/0 不动**（本批纯前端）；8000 重启生效 PID 4064；改动=ScriptMapScene（相机跟随+滚轮缩放）/MiniMap.tsx（新增小地图）/PhaserScriptMapView（全屏）/global.css

- 2026-08-03 13:2x 全量复跑：**373/0 BUILD SUCCESS**（359 基线 + P-0803-E M8 搜证足迹 1 用例 + 并行批次未登记新增 13 用例；类数以并行批次登记为准）——P-0803-E 方案 B（ScriptGameService searchedLocations 快照持久化 + search 足迹双分支 + toMap 暴露 + restore 恢复；前端 restoreSearched 绿点恢复 + ScenePage init 自动地图消费）全量回归无破坏；LONG-01 PASS

## Round 33 / v33（2026-08-03 14:1x，P-0803-G 轨道系统用户加入前端 2D 加入入口批次，coder subagent 27742020，**纯前端+static 同步，后端基线 372/53 不动**）

- **mvn**：未跑（零后端改动，前端批次惯例——后端基线 372/0 BUILD SUCCESS / 53 类不动，LONG-01 PASS 见 Round 32）
- **npm run build**：通过（tsc -b 0 错误 + vite 65 modules；index-CD_FWSaH.js 1,840.40 kB + index-BFSSpZ7r.css 40.16 kB）
- **static 同步**：index-CD_FWSaH.js 已同步 static/assets（SHA256 dist↔static 一致）；index.html 引用已更新（CD_FWSaH.js + BFSSpZ7r.css）；被取代的旧产物 index-lbgMwQKU.js 已删除；CSS 未变（hash 一致）
- **bundle grep 命中**：joinConversation 2 处 / leaveConversation 2 处 / 加入对话 5 处 / 离开对话 5 处 / conversation-status 1 处（index-CD_FWSaH.js）
- 改动：api/client.ts（+joinConversation/leaveConversation）、phaser/SimulationScene.ts（群组框加入/离开悬浮按钮 + SceneCallbacks.onGroupAction + applyGroups opts{playerName,playerInWorld} + hitTestPointer 防误触）、phaser/PhaserSimulationView.tsx（handleGroupAction 交互闭环：成功/失败可见提示 + 手动触发 conversation-status 刷新；worldAgentsRef 玩家在场判定）、phaser/simulationData.ts（SimGroup +id/idleMs）
- 交互闭环：可加入判定=玩家在场（worldAgents 含玩家名）+ 未在组内 + 非 DYAD（后端上限 2 必满）+ 组有 id；加入成功 → participants 含玩家名 → 4s 轮询自动变「离开对话」入口；后端错误 message（组满/重复/已在组/组不存在等）聊天面板系统消息 + 地图 toast 可见
- 零后端改动（不动 RouterService/ArbiterService/审批/狼人杀/剧本杀/SSE 主链路/P-0803-E 地图增强功能本身）；未 git commit（统一 gate 未获授权）；8000 重启由主会话负责

- 2026-08-03 16:2x（P-0803-F 超时修复批次）：相关 4 类测试 BUILD SUCCESS（LLMClient 超时重载 + 地图 45s + mock 3 参 stub）；排除 LONG-01 全量 BUILD SUCCESS；LONG-01 单独复跑 PASS（堆增长 -12.6%，全量环境 53.1% 为既有堆测量脆性 #37/#71 同款，非回归）；前端 npm build 65 modules

- 2026-08-03 16:55（P-0803-G 剧本名超长修复批次）：排除 LONG-01 全量 372/0 BUILD SUCCESS（SceneEntity 列宽 2000 + saveScene 截断 500，无回归）；LONG-01 既有堆测量脆性另行单独复跑 PASS

- 2026-08-03 18:12（P-0803-H scripts.name 修复批次）：排除 LONG-01 全量 372/0 BUILD SUCCESS（ScriptEntity.name 2000 + saveScript/saveCharacter 截断 + ScriptSchemaV1.title 源头规约 100，无回归）；H2 直查 SCRIPTS.NAME=2000

## Round 34 / v34（2026-08-03 18:15，P-0803-H 剧本选择与角色卡功能改造批次，coder subagent 3ec9d490）

- 全量 **378/0 BUILD SUCCESS**（54 类；373 基线 + SceneBindingTest 5 新用例，LONG-01 PASS 2.510s）
- 新增 SceneBindingTest 5 用例：①创建剧本带 category/default_roles/default_map → 响应回显 + GET /api/scenes 列表透传 ②旧式创建（不带新字段）→ 默认 general/空角色组/无地图（向后兼容）③PUT 更新分类/角色组 + default_map 空串清除地图 → 生效且可回读 ④POST /api/scenes/map → BSP 契约 v1 地图（map_version≥1/width/height/zones/spawn_points/generator.kind=bsp）⑤同 seed 同输出（map_id/width/zones 一致，确定性）
- 后端改动：SceneEntity（+category/defaultRoles/defaultMap 三列，ddl-auto=update 自动加列）、DatabaseService（saveScene 8 参重载旧 5 参委托 + entityToMap 新键 parseRoleList/parseJsonMap）、SceneController（create/update/persistScene 透传 + default_map 空串=清除 + POST /api/scenes/map）
- 前端改动（npm run build 通过：tsc 0 错误 + vite 65 modules，index-BvHFWNFM.js 1,850.31 kB + index-Cm-4mX-J.css 40.93 kB；static 同步 SHA256 dist↔static 一致 + index.html 更新 + 删 index-Bci-_Ru2.js）：types（Scene+3 字段）、client.ts（+sceneMap）、ScenePage.tsx（剧本选择改造 8 项需求）、global.css（char-card-wrap/script-card 样式）
- bundle grep 命中：剧本选择 2 / 角色库 4 / 自由角色卡 1 / 生成默认地图 2 / 默认 2D 3 / 是否 2D 1 / 每栏置顶 1 / 已绑地图 1 / sceneMap 2
- 零改动禁动文件（RouterService/ArbiterService/审批/狼人杀/剧本杀 Service/SSE 主链路）；不打包 jar、不重启 8000、未 git commit（统一 gate 未获授权）
- ⚠️ 并行注记：本批标记 P-0803-H 与 2026-08-03 18:12 并行「scripts.name 修复批次」同名撞标（我方先登记），改动区域不同零冲突，提请主会话协调

- 2026-08-03 20:0x（P-0803-H2 对局找回 + 超时 600s 批次）：mvn compile 0 错（后端仅 ScriptController 1 行 currentSessionId 赋值）；API 闭环验证 resume→status=investigation；前端 build 通过（恢复对局入口 + 6e5 超时）；未跑全量（改前端为主 + 后端 1 行无逻辑分支，待主人确认后补全量）

- 2026-08-03 20:3x（P-0803-H3 2D 模拟对局地图批次）：npm build 通过（ChatPage 渲染分支 + import）；浏览器截图验证对局地图渲染（24×16 BSP + 线索点）；后端未改动

## Round 35 / v35（2026-08-03 22:4x，P-0803-J 剧本杀地图容量扩展批次，coder subagent a45f6830）

- 全量 **390/0 BUILD SUCCESS**（55 类；378 基线 + 本批 12 新用例：ScriptMapSizeExpansionTest 11 + ScriptMapPersistenceTest M9 1，LONG-01 PASS 2.337s）
- 新增 ScriptMapSizeExpansionTest 11 用例（参数化 + 单元）：
  - S1 参数化（4 尺寸：64×64 / 48×48 / 100×60 / 128×64）：BSP 大图生成成功 + 尺寸精确 + MapValidator 7 项校验通过 + 热点/出生点全部可通行 + 热点数按面积缩放（≥默认 3、不超房间数、64×64+ ≥5 不空旷）
  - S1b：scaledZonesCount 缩放曲线（24×16→3 旧行为不变 / 小图下限 3 / 64×64≈10 / 面积更大热点更多）
  - S2a：ScriptMapService 显式 64×64 + LLM 空输出 → BSP 降级精确按尺寸 + 校验通过
  - S2b：大图超 LLM token 预算（40×24）→ 跳过 LLM 直接 BSP（fallback 原因含预算说明 + verify LLM 零调用）
  - S3a：预算内显式尺寸（32×20）仍走 LLM 路径；S3b：buildPrompt 含本次要求尺寸 + 示例 JSON 尺寸同步（旧签名默认提示不变）
  - S4：ScriptGameService.generateMap 显式 64×64 落对局；regenerate 无显式尺寸保持 64×64（不回落默认）；新对局默认 24×16（对局间尺寸独立）
  - S5：controller POST /api/script/map 透传 width/height；非法尺寸字符串不崩回落对局已定尺寸
- ScriptMapPersistenceTest 增 M9：64×64 BSP 大图尺寸随快照持久化（map_width/map_height 落库）→ 新实例 resumeGame 恢复后尺寸保持 + regenerate 无显式尺寸保持 64×64
- 后端改动：BspMapGenerator（+scaledZonesCount 面积缩放，Options.of zonesCount<0 语义=按面积自动缩放，默认 24×16 下仍=3 旧行为不变）、ScriptMapService（+LLM_MAX_WIDTH/HEIGHT=40/24 预算闸 + generateMap/buildPrompt 尺寸重载，旧签名委托零破坏）、ScriptGameService（+@Value roleplay.game.map.default-width/height + ScriptGame.mapWidth/Height + generateMap 重载 + 快照 map_width/map_height 保存/恢复）、ScriptController（map 端点 body 透传 width/height）、application.yml + application-test.yml（+roleplay.game.map.default-width: 24 / default-height: 16）
- **大图与 LLM token 预算方案**：LLM 路径上限约 40×24（ground+collision ≈1920 数字 ≈2500-3000 tokens 逼近 4000 上限）；显式尺寸超上限直接走 BSP 确定性路径（精确尺寸、零截断风险）；预算内显式尺寸仍走 LLM（prompt 内嵌本次要求尺寸）
- 零改动禁动文件（RouterService/ArbiterService/审批/狼人杀/SSE 主链路/static/前端）；未改 BspMapGeneratorTest.structureContract 等锁定默认 24×16 的旧断言；不打包 jar、不重启 8000、未 git commit（统一 gate 未获授权）；8000 重启由主会话负责

## Round 36 / v36（2026-08-03 23:4x，P-0803-K 剧本杀模式多地图切换批次，coder subagent 1c283f43；上一子 agent 断连未验证，本批修复+验证+补台账）

- 全量 **403/0 BUILD SUCCESS**（58 类；390 基线 + 本批 8 新用例：ScriptMapSwitchTest 7（K1-K7）+ ScriptMapSwitchPersistenceTest 1（K8）+ 并行未登记批次 ScriptChatModeTest 5 共存；LONG-01 首轮堆测量 32.5%>30% 为既有脆性（#37/#40/#41 同款），单跑 PASS 后全量复跑 403/0 全绿）
- 新增 ScriptMapSwitchTest 7 用例（K1-K7 验收）：
  - K1：多图注册表（init 自动图 map_1 设为当前 / 显式与自动 map_id 注册多图 / 每图独立尺寸 / toMap+status 暴露 current_map_id+map_ids）
  - K2：door zone 触发切换 happy path（addDoorZone 布门 → 靠近 switchMap → 当前图切换 + zone target 字段解析 + door zone 持久于注册表）
  - K3：靠近校验（远离 radius+2 拒绝）/ 直切模式（无 door_zone_id 显式 target）/ body target 覆盖 zone target（未注册目标自动生成 map_new）
  - K4：非法 door 目标容错 8 类（不存在/非 door 型/无目标/目标=当前图/非本局玩家/缺玩家/阶段不符/双缺）
  - K5：状态迁移（线索/AP/秘密对局级保留；足迹按图隔离：切走暂存、切回恢复；searchedByMap 不互污染）
  - K6：尺寸联动（24×16 ↔ 64×64 切换后 mapWidth/mapHeight 随目标图更新）
  - K7：未知目标自动生成（door 可选 width/height → BSP 按尺寸生成）+ controller map/switch/door 端点透传容错
- ScriptMapSwitchPersistenceTest K8：多图注册表/当前图/每图足迹随快照落库 → 新实例 resumeGame 重启恢复后注册表完好、当前图正确、切图后足迹按图恢复、door 触发链路仍可用
- 修复 4 失败（2 实现 bug + 2 测试 bug，详见 docs/修改记录.md #97 / DECISION_LOG D-032）：generateMap 补足迹迁移；K7 缺 session_id 用例改显式空串（currentSessionId 兜底设计）；K4 h)双缺用例移至搜证阶段（阶段守卫先于参数校验）；K8 恢复后 Bob 用本人 roleKey
- 后端改动：ScriptGameService（+maps/mapFallbacks/searchedByMap/currentMapId +switchMap +addDoorZone +generateMap 足迹迁移 +快照 maps/current_map_id/searched_by_map +toMap 新键）、ScriptController（+map/switch +map/door +map 透传 map_id）、ScriptMapService（buildPrompt 预留 door 可选输出）
- 零改动禁动文件（RouterService/ArbiterService/审批/狼人杀/SSE 主链路/static/前端）；不打包 jar、不重启 8000、未 git commit（统一 gate 未获授权）

## Round 37 / v37（2026-08-03 24:0x，P-0803-L 剧本杀双版本前端批次（原顺延 K 撞标，主会话裁决改签 L）：剧本选择净化 + 剧本杀双版本，coder subagent 5e96fc0e；⚠️ 批标与多地图批次 1c283f43 共用 P-0803-K，已提请主会话知悉）

- 全量 **403/0 BUILD SUCCESS**（58 类；390 基线零破坏 + 并行批次 ScriptChatModeTest 5 / ScriptMapSwitch 8 共存复跑全绿；LONG-01 PASS）——本轮前端改造无新增后端用例，全量复跑验证后端 mode 能力与并行批次共存
- ScriptChatModeTest 5 用例（本批前端所依赖的简单对话版验收，后端已就位随批验证）：
  - C-1：initGame(mode=chat) 后 phase==DISCUSSION、mode=="chat"、discussionActive==true、mapData==null、toMap 暴露 mode=chat 且无 map 键、init 响应含 session_id
  - C-2：简单对话版搜证被阶段守卫拦截（「当前不是搜证阶段」，不下发线索）
  - C-3：讨论引擎自动驱动 → 结束自动进 VOTE（蓝图 Step3v 降级路径收束），讨论发言记录非空且 toMap 暴露 discussion
  - C-4：缺省/显式 full 模式零变化（phase==INVESTIGATION、mode=="full"、不自动启动讨论、toMap phase=investigation）
  - C-5：mode 随快照落库，新实例 resumeGame 从快照恢复仍为 chat（重连后前端仍按简单版渲染）
- 前端改造（npm run build 通过：tsc 0 错误 + vite 65 modules，index-NSMdVek6.js 1,859.93 kB + index-BC-6X7pW.css）：
  - client.ts：scriptInit 加可选 mode 参数（body 透传，缺省 full 零破坏，chat 模式无地图 LLM 正常 60s 内返回）
  - ScenePage.tsx：净化——内嵌角色库栏移除（收敛为仅规则模式显示），剧本选择页只保留「一般模式 / 剧本杀模式」两类页签；一般模式页签 = 一般+狼人杀剧本卡（带 chip）；选中剧本 → 独立设置页（非弹窗内嵌）：角色选择（跟剧本 default_roles 走，charTab 默认切到该剧本，用户角色卡置顶不默认勾选，增删/编辑/新建复用 renderCharGrid）+ 2D 设置（一般「是否 2D」/ 狼人杀「默认 2D」）+ 启动按钮 + 地图预览；剧本杀模式页签 → 双版本设置页（真剧本杀 full / 简单对话版 chat 版本卡 + 提示词 + 角色选择 + 启动分流 genScript(mode) + 恢复对局入口 + 地图区 full 专属）；狼人杀剧本卡归入一般模式页签展示
  - ChatPage.tsx：ScriptStatePanel 按 scriptState.mode==='chat' 隐藏搜证区/2D 空间讨论区；主 2D 面板 chat 模式不渲染（无地图无 2D）；phase banner 文案区分（简单对话版：直接多人对话讨论，无取证）
  - global.css：+.script-version-card 双版本卡样式
- static 同步：SHA256 dist↔static 一致（index-NSMdVek6.js / index-BC-6X7pW.css / index.html 三方），删除被取代旧产物 index-CBCAur3N.js / index-Cm-4mX-J.css / index-Ctpf4Fmo.js；bundle grep 命中（简单对话版 9 / 真剧本杀 4 / 剧本杀模式 2 / 游戏模式 1 / script-version-card 2 / 自由角色卡 2 / 恢复对局 7 / AI 生成剧本 5 / 狼人杀 18 等）
- 兼容说明：P-0803-H 全部能力保留（角色卡增删/置顶/不默认勾选、剧本绑定默认角色/地图、狼人杀默认 2D、rules 模式角色库零改动）；零改动禁动文件（RouterService/ArbiterService/审批/狼人杀/SSE 主链路）；不打包 jar、不重启 8000、未 git commit（统一 gate 未获授权）；8000 重启由主会话负责

## Round 38 / v38（2026-08-03 23:3x，P-0803-M 简单对话版可选配置地图批次：chat 版「🗺️ 配置地图」开关 + 启动自动生成 + ChatPage 只读地图查看，coder subagent 3b067206；纯前端+static 同步，后端零改动）

- 全量 **403/0 BUILD SUCCESS**（58 类 surefire 汇总；基线零破坏 + 并行批次用例共存复跑全绿；LONG-01 PASS 2.520s）——本批后端零改动（已核实 POST /api/script/map 无 phase/mode 守卫 chat 模式可用），全量复跑为验证性
- 前端改造（npm run build 通过：tsc 0 错误 + vite 65 modules，index-ChF7_wXr.js 1,862.20 kB + index-BC-6X7pW.css 未变 hash 一致）：
  - ScriptMapScene.ts：+readOnly 只读模式（构造三参；create 不注册 pointerdown 搜证、E 键搜证 gated、interact 防御性拦截、update 只读提示「🔭 地图浏览（只读 · 氛围展示）」）
  - PhaserScriptMapView.tsx：+readOnly prop（透传 scene + 头部提示「🔭 只读浏览（氛围展示，无搜证）」+ effect deps 补 readOnly）；缺省 false 全版本行为不变
  - ScenePage.tsx：+chatMapEnabled 状态（默认 false=保持 P-0803-L chat 无地图基线）；剧本杀设置页 chat 版「🗺️ 配置地图（可选）」开关卡；chat 开启后地图区可用（生成地图/重新生成按钮 + PhaserScriptMapView readOnly 预览）；genScript('chat') 启动链路开启时 init 后自动调 api.scriptMap 生成并携带地图（失败不阻塞进入对局，可手动重试）
  - ChatPage.tsx：ScriptStatePanel chat 模式有 map 时增「🗺️ 对局地图（氛围展示）」入口（尺寸/热点信息 + 查看按钮 → toggleSimPanel）；chat-main 2D 面板守卫改「chat 且有 map 放行 / 无 map 隐藏」；chat 渲染 PhaserScriptMapView readOnly（只读无搜证）；面板标题区分「对局地图（氛围展示 · 只读）」
- static 同步：本批首构建 index-ChF7_wXr.js（SHA256 dist?static 一致 + index.html 引用更新），删除被取代旧产物 index-NSMdVek6.js；**并行批次 P-0803-N（subagent fb715635）23:31:31 基于含本批改动的完整源码重构建 index-Cx-YssBi.js**（同改 ScenePage.tsx 不同区域零冲突），最终部署产物 = 本批改动 + P-0803-N 修复，SHA256 dist?static 一致（JS 5D78…C09E9 / CSS E048…C0DB），index.html 已指向新产物；bundle grep 复核命中（配置地图 5 / 氛围展示 10 / 只读浏览 1 / 对局地图（氛围展示 2 / readOnly 10 / 简单对话版 12 / script-version-card 2 / 无取证 4 / 无地图 2 / 生成地图 14）
- 兼容说明：full 版搜证/地图/2D 讨论区零改动（readOnly 缺省 false 行为逐字节不变）；chat 无地图维持纯对话现状（面板隐藏）；恢复对局（resume）后 chat 地图随快照 map_data 恢复前端轮询可见；已知限制——chat 地图生成与后台讨论引擎并发，saveSnapshot 对 discussionTranscript 的拷贝存在极小概率 CME（失败仅地图请求 500 不损坏对局，前端可重试，P2 可改 CopyOnWriteArrayList）；零改动禁动文件（RouterService/ArbiterService/审批/狼人杀/SSE 主链路/后端）；不打包 jar、不重启 8000、未 git commit（统一 gate 未获授权）；8000 重启由主会话负责


## Round 39 / v39（2026-08-03 23:30-24:xx，P-0803-N 点开剧本卡后角色区只剩 me 的修复批次：selectScript/tabChars 空 default_roles 回退 all，coder subagent fb715635；纯前端+static 同步，后端零改动）

- 全量 **403/0 BUILD SUCCESS**（58 类 surefire 汇总；首轮 LONG-01 堆测量 31.1%>30% 为既有堆测量脆性（台账 #37/#40/#41 同款），单跑 PASS（堆增长 10.5%）后全量复跑 403/0 + MAVEN_EXIT_CODE=0 确认；本批后端零改动，403 用例组成不变）
- 根因复核（与主会话定位一致）：selectScript 点开剧本卡后 `setCharTab(scene.scene_id)` 把角色区切到该剧本 default_roles 分组；tabChars 对某剧本 scene_id 只返回该剧本 default_roles 中的角色；实测 GET /api/scenes 9 个剧本 default_roles 全空 → 分组返回空列表；renderCharGrid 顶部固定渲染 me-char-card（你的角色卡）+ 下面空列表 → 视觉上只剩 me 一张卡，无法自选
- 前端修复（npm run build 通过：tsc 0 错误 + vite 65 modules，index-Cx-YssBi.js 1,862.23 kB + index-BC-6X7pW.css hash 未变）：
  - ScenePage.tsx selectScript（约 208-225 行）：`const roles = (scene.default_roles||[]).filter(Boolean)` 后 `setCharTab(roles.length > 0 ? scene.scene_id : 'all')`——剧本无默认角色 → 角色页签保持/回退 'all'（默认展示全部角色卡可自选任意角色）；有默认角色仍切到该剧本分组（分类跟着剧本走语义不变）；自动预选（roles 入 selected 排除 myCharName）原样保留
  - ScenePage.tsx tabChars（约 150-160 行）：剧本 default_roles 为空（names.size===0）→ 返回全部角色卡——覆盖手动点击空分组 chips 的同类空网格路径（两路径统一回退 'all'），不重构 renderCharGrid
  - gameSetup 视图复核无同类问题：openGameSetup 已 setCharTab('all')；rules 模式角色库（约 790-830 行区）独立 chips 不受 selectScript 影响
- static 同步：index-Cx-YssBi.js 已同步（SHA256 dist↔static 一致，JS 5D78…C09E9 / CSS E048…C0DB）+ index.html 引用更新 + 删除被取代旧产物 index-ChF7_wXr.js（P-0803-M 首构建产物）；static/assets 仅剩 2 个生效产物
- bundle grep 复核：新产物含修复逻辑（selectScript 区 minified `Oi(ot.length>0?$.scene_id:"all")` vs 旧产物 `Oi($.scene_id)`；tabChars 区 `ot.size===0?tr([...g]):tr(g.filter(...))` 回退全部）；关键 UI 串全命中（自由角色卡/全部/已选择剧本/已自动带上/配置地图/氛围展示/剧本杀模式/简单对话版/真剧本杀）；自动预选排除逻辑（St!==ai&&St.add(Rt)）与旧产物逐字一致=既有功能零改动
- 兼容说明：与 P-0803-M（chat 地图开关）同文件 ScenePage.tsx 不同区域共存（本批 selectScript/tabChars 区 vs M 的 chat 地图开关/启动链路区），构建基于含双方改动的完整源码；置顶/不默认勾选/分类 chips/恢复对局等既有能力零改动；零改动禁动文件（RouterService/ArbiterService/审批/狼人杀/剧本杀 Service/SSE 主链路/后端）；不打包 jar、不重启 8000、未 git commit（统一 gate 未获授权）；8000 重启由主会话负责

## Round 40 / v40（2026-08-03 23:5x-25:0x，P-0803-O 两条地图链路 LLM 全量生成批次：SceneController.generateDefaultMap 双模式 + 剧本编辑弹窗生成方式选择 + 剧本杀设置页地图区 theme 暴露，coder subagent 3e144626）

- 全量 **407/0 BUILD SUCCESS**（58 类 surefire 汇总；403 基线零破坏 + 新增 SceneMapLlmModeTest 4 用例；LONG-01 PASS 2.529s；MAVEN_EXIT_CODE=0）
- 新增 SceneMapLlmModeTest 4 用例（剧本卡默认地图端点双模式验收）：
  - O1：body 带 theme → LLM 全量生成（mode=llm / generator.kind=llm / 契约 v1 全量元素：ground+collision 双层数组 + rooms/zones/spawns / validation.ok / fallback 空）
  - O2：无 theme（null/空串/空白）→ BSP 确定性零回归（generator.kind=bsp + 同 seed 同输出，P-0803-H ⑤ 语义在 controller 层复验）
  - O3：LLM 空输出（失败路径）→ mode=bsp-fallback + fallback 原因含「输出为空」+ 兜底地图契约 v1 自洽（BspMapGenerator 输出可过校验）
  - O4：4 参旧构造（mapService=null）带 theme → 防御回落 BSP 确定性（不崩）
- 单跑 SceneMapLlmModeTest 4/0 + SceneBindingTest 5/0（既有 BSP 端点验收零回归复验）
- 后端改动（注入可行性核实：ScriptMapService 为 @Service 仅依赖 LLMClient，SceneController→ScriptMapService→LLMClient 无环依赖；5 参 @Autowired 构造 + 4 参旧构造委托 null 供既有调用/测试零破坏）：
  - SceneController.java：+ScriptMapService 注入；generateDefaultMap 双模式——无 theme → BspMapGenerator 确定性（原行为逐字节不变）；带 theme → mapService.generateMap(theme, [], [], seed)（LLM → MapContract.normalize → MapValidator 契约 v1 校验 → 失败/超预算 BSP 兜底），响应附加 mode（llm / bsp-fallback）+ generator + validation{ok,errors,warnings} + fallback 溯源键
- 前端改动（npm run build 通过：tsc 0 错误 + vite 65 modules，index-StVXWN_F.js 1,863.64 kB + index-BC-6X7pW.css 未变 hash 一致）：
  - client.ts：sceneMap 改收 body{seed?,theme?}（无参调用向后兼容，body 恒发 JSON）
  - ScenePage.tsx：+formMapMode（'bsp'|'llm'）+formMapTheme +mapTheme 状态；剧本编辑弹窗「生成方式」chips（✨ LLM 全量生成 / BSP 默认）+ LLM 主题输入（占位「民国宅邸凶案」，空主题禁用生成）+ genDefaultMap 双模式（LLM 模式响应溯源展示 kind/兜底），生成后绑定 formDefaultMap 逻辑不变，既有 BSP「🗺️ 生成默认地图」按钮保留为 BSP 模式入口；剧本杀设置页地图区（gameSetup 双版本 + rules script tab 两处）theme 暴露为可编辑输入（mapTheme 默认剧本名、可改），genScriptMap / genScript('chat') 自动氛围地图 / doResumeScript 均消费 mapTheme（空回退剧本名）
- static 同步：index-StVXWN_F.js（SHA256 dist?static 一致，JS + CSS 双哈希）+ index.html 引用更新 + 删除被取代旧产物 index-Cx-YssBi.js（P-0803-N 最终产物）；static/assets 仅剩 2 个生效产物（index-StVXWN_F.js + index-BC-6X7pW.css）
- bundle grep 命中：生成方式：/LLM 全量生成/BSP 默认/地图主题（LLM 全量生成/民国宅邸凶案/LLM 全量输出（契约 v1 校验）/失败自动 BSP 兜底/BSP 确定性生成（契约 v1）/默认剧本名可改/BSP（兜底）/LLM 全量：/sceneMap body 签名（sceneMap:c=>mt("/api/scenes/map",{...,body:JSON.stringify(c||{})})）
- 兼容说明：BSP 模式零回归（SceneBindingTest 5/0 全绿 + O2 复验）；链路 2（POST /api/script/map）后端零改动（LLM 全量生成路径本已就绪），仅前端暴露 theme；seed 参数双模式均透传；ChatPage 零改动；遗留——剧本杀设置页 seed/尺寸参数后端已支持（P-0803-J/K）但 UI 空间不允许未暴露（保持现状，报告说明）；零改动禁动文件（RouterService/ArbiterService/审批/狼人杀/剧本杀 Service/SSE 主链路）；不打包 jar、不重启 8000、未 git commit（统一 gate 未获授权）；8000 重启由主会话负责
