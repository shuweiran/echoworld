# 改造方案：玩家角色随时改名且不被主控识别为 AI

> 状态：**方案设计稿（未实施，不改任何 src 代码、不 git commit）**
> 日期：2026-08-02
> 依据：PROJECT_CONTEXT.md / DECISION_LOG.md（D-003/D-017/D-021/D-028/D-029）/ AGENTS.md
> 前置：上一轮 coder 调研结论已逐一抽查核实（见 §1 核查表，全部属实并补齐行号）
> 注意：本方案涉及 `RouterService`（禁动文件），实施前需主会话/主人授权（对齐 P-0802-F 先例）；实施批次开工前须登记 `docs/并行作业登记.md`

---

## 0. 问题定义

玩家在角色库把**自己的角色改名**后，运行中的会话仍按**旧名字**索引该角色。由于系统里"玩家身份 == 角色名 == 名字字符串"，四条主控判定链路全部按名字命中：

| 链路 | 判定方式 | 改名后后果 |
|---|---|---|
| 2D 世界 | 开局按「玩家名 == 角色名」一次性标记 `playerControlled` | 新名角色被当成 NPC，主控导演/AI 替玩家角色说话 |
| 一般模式 | `speaker(player_name)` 命中 agent 名单才豁免 LLM | 玩家发言回落主控旁白化（主控代声 = 被识别为 AI） |
| 狼人杀 | `humanPlayers` 名字集合，AI = 不在集合中的存活玩家 | AI 行动器接管玩家角色的夜间行动/投票 |
| 剧本杀 | 名字键结构（players/assignments/playerKeys/playerIsHuman） | 玩家端点鉴权失败、视图错乱 |

**目标**：玩家可在任意时刻改名，且改名后四条链路立即按新名识别该角色为"玩家本人"，主控不再代声/代行动；同时修复角色库改名撞名直接覆盖 persona 的缺陷。

---

## 1. 调研事实核查表（已抽查验证，全部属实 + 行号取证）

| # | 调研结论 | 验证结果（文件:行号） |
|---|---|---|
| 1 | 全系统唯一改名入口 `PUT /api/characters/{name}`，无冷却/次数/状态限制 | ✅ `controller/CharacterController.java:70-86` `@PutMapping("/{name}") update()`；无任何限流/状态检查 |
| 2 | 改名 = 删旧建新，id 必变 | ✅ `CharacterController.java:81` `databaseService.deleteCharacter(name)` + `:83` `saveCharacter(newName,...)`；`db/entity/CharacterEntity.java:15-18` 自增 id |
| 3 | 运行时全部按名字索引角色 | ✅ `service/RouterService.java:68` `Map<String, Agent> agents`；`simulation/SimulationWorld.java:29,30` `states/agents` 两个 ConcurrentHashMap 均按 `agent.getName()` 键（:64-71 registerAgent）；`service/WerewolfService.java:61-96` GameState 的 roles/alive/votes/playerKeys 全部名字键；`service/ScriptGameService.java:140-160` ScriptGame 的 players/assignments/playerAp/playerTalkativeness/playerIsHuman/playerKeys 全部名字键 |
| 4 | `Agent.role`（npc/agent）存在但全工程无读取 | ✅ `agent/Agent.java:24-31` `private final Persona persona; private final String role;`；全工程 grep `getRole()` 仅命中 `Message.Role`（Agent.java:81、LLMClient.java:234 等），**`Agent.getRole()` 零读取** |
| 5 | 2D：`playerControlled` 开局按名字一次性设定 | ✅ `simulation/SimulationService.java:192-211` `initWithPersonas(personas, sceneName, playerName)`，:200-202 `if (explicitPlayer ? playerName.equals(p.getName()) : PLAYER_AGENT_NAME.equals(p.getName())) state.setPlayerControlled(true)`；读点 :450（自动对话排除）、:553（playerAgents）；字段 `simulation/AgentState.java:28,100-101,130` |
| 6 | 一般模式：`speaker(player_name)` 按名字命中才豁免 LLM | ✅ `controller/SessionController.java:100-109` `sendMessage` 读 `body.player_name` → `runRound(message, null, playerName)`；`service/RouterService.java:233` `speakerIsAgent = speaker != null && !speaker.isBlank() && agents.containsKey(speaker)`；:307-314 命中则原文直接入史（Role.AGENT）并跳过 LLM；:325-331 未命中走 arbiter 旁白化 |
| 7 | 狼人杀：`humanPlayers` 名字集合，AI = 不在集合的玩家 | ✅ `service/WerewolfService.java:141` `humanPlayers`；:260-266 setHumanPlayers；:614-619 AI=alive 中不在 humans；:672 aiVotes 排除 humans；`controller/WerewolfController.java:81-82` init 只把 `player_name` 登记为人类 |
| 8 | 剧本杀：`playerIsHuman` 名字 map | ✅ `service/ScriptGameService.java:155` 声明；**只写不读**（:359、:1701 写入，无读取点）→ 潜在链路，现全员人类；实际风险在名字键结构（players/assignments/playerKeys）与 `checkPlayerAccess`（:1428-1451 按 player+roleKey） |
| 9 | 改名撞名无校验，覆盖同名 persona | ✅ `db/service/DatabaseService.java:67-79` `saveCharacter` 是 **upsert-by-name**（`findByName(name).orElse(new CharacterEntity(...))`）→ 新名已存在则覆盖其 persona/voice/background；`CharacterController.java:27` 内存列表无唯一性 → 重名可并存两行 |
| 10 | 前端唯一改名弹窗（场景页/素材库/设置页共用） | ✅ `roleplay-v4/frontend/src/api/client.ts:65-67` `createCharacter/updateCharacter(oldName, data)/deleteCharacter`；:83 `send(text, playerName)` 每次请求传 `player_name` |

**关键架构事实补充**（方案设计前提）：
- `RouterService` 与 2D `SimulationService` 均为**单活动会话**（`RouterService.java:120-146` initSession 先 `agents.clear()` 再填充；`SimulationWorld` 单 world），局中改名只需处理当前活动会话；狼人杀/剧本杀为多局（按 sessionId 键）。
- `Persona.name` 可运行时改（`core/Persona.java:64` `setName`；`Agent.getName()` 委托 `persona.getName()`，`agent/Agent.java:35-36`）→ **Agent 改名无需动 Agent 类**，只需换 agents map 的键。
- `AgentState.agentName` 是 `final`（`simulation/AgentState.java:8,32-38`）→ 需改非 final 加 `rename()` 或重建 state。
- 生产 H2 `ddl-auto: update`（`src/main/resources/application.yml:19`）→ **加列自动迁移，无手工 SQL**；测试 mem `create-drop`（application-test.yml:13）零负担。
- 已有客户端持有 UUID 令牌先例：剧本杀 roleKey（D-017/C3）、狼人杀 roleKey（D-028/D-029）→ 玩家身份令牌设计完全对齐该先例。

---

## 2. 方案总览

三条设计支柱，缺一不可：

1. **玩家身份模型（player_id）**：独立于名字的稳定玩家 UUID，客户端持有，角色库绑定；判定链路改「player_id → 当前角色名」动态解析（§3）。
2. **局中改名链路（同步式）**：新改名端点 + 四个运行时服务的 `renamePlayer/renameAgent` 方法，改名时原子同步四处运行态（§4）。
3. **角色库撞名校验**：controller 层业务校验 + DB unique 兜底（§5）。

> 判定链路改解析式是"防呆"：即使某次改名同步失败/前端漏传，主控判定仍按 player_id 解析到新名，**不会静默 AI 化**（降级为可观测错误而非身份漂移）。
> 同步式是"可用"：运行态名字键必须换成新名，玩家才能用新名互动（sendUserMessage/speaker/night_action/search）。

---

## 3. 玩家身份模型（player_id）

### 3.1 定义

- **player_id**：玩家唯一 UUID。前端首次使用 `crypto.randomUUID()` 生成，`localStorage` 持久化，之后所有玩家相关请求携带（body 字段 `player_id`，与既有 `player_name` 并列；两字段均为可选）。
- **绑定关系**：角色库中一个角色最多绑定一个玩家（`CharacterEntity.playerId` 列，nullable + unique）；一个玩家最多绑定一个角色。绑定即"这是玩家本人的角色"。
- **解析器**：`PlayerIdentityService.resolveCharacterName(playerId)` → 当前角色名（查 `CharacterRepository.findByPlayerId`）；`resolvePlayerIdOf(name)` 反查。改名后无需任何缓存同步——解析永远得到最新名。

### 3.2 数据模型改动

| 位置 | 改动 |
|---|---|
| `db/entity/CharacterEntity.java` | 加字段 `private String playerId;`（`@Column(unique = true)`，nullable）+ getter/setter；构造函数重载（保留旧构造，兼容既有测试/调用点） |
| `db/repository/CharacterRepository.java` | 加 `Optional<CharacterEntity> findByPlayerId(String playerId)` |
| `db/service/DatabaseService.java` | `saveCharacter` 增加可选 `playerId` 参数重载（旧签名保留委托 null）；`entityToMap` 输出 `player_id` 键 |
| `controller/CharacterController.java` | `create/update/batch` 从 body 透传 `player_id` 落库；`update` 改名时保留绑定迁移 |

**绑定生命周期**：
- 建角色（POST /api/characters）：body 带 `player_id` → 落绑定。
- 改名（新端点）：绑定随角色迁移（playerId 不变）。
- 换绑/解绑：本期不做（一个玩家一个角色，绑定即身份；解绑留后续）。

### 3.3 与现有 `player_name` 字符串体系的兼容过渡

**一期原则：不改任何既有协议字段、不删任何旧路径。** `player_id` 全部为可选参数，缺省时全链路行为与现状逐字节一致（旧测试零破坏）：

| 过渡阶段 | player_id 状态 | 行为 |
|---|---|---|
| 过渡期（Phase 1-2 落地后） | 前端未升级、请求无 player_id | 全走现有名字逻辑（现状行为） |
| 过渡期 | 前端已升级、有 player_id | 判定优先解析 player_id，`player_name` 仍随请求保留（兜底 + 兼容） |
| 长期 | 全量前端升级 | player_id 为主键，player_name 降为展示名 |

**判定优先级**（所有链路统一）：`player_id` 存在且能解析 → 用解析出的当前角色名；否则用 `player_name` 字符串（现状逻辑）。

---

## 4. 局中改名链路

### 4.1 新端点设计

```
POST /api/player/rename
Content-Type: application/json
Body（推荐）: { "player_id": "<UUID>", "new_name": "新名字" }
Body（兼容，无 player_id 时）: { "old_name": "旧名字", "new_name": "新名字" }
```

**鉴权**：
- 带 `player_id`：必须命中角色库某角色的 `playerId` 绑定，否则 403（"未绑定玩家角色"）；命中后只允许改**自己绑定**的角色。
- 无 `player_id` 用 `old_name`：角色库内按名定位，且该名未被其他绑定占用；向后兼容老客户端（对齐 D-017"无 key 向后兼容"先例）。

**响应**：`200 { "new_name": "...", "old_name": "...", "synced_sessions": ["router","2d","werewolf:<sessionId>","script:<sessionId>"], "collision": false }`；撞名 `409 { "error": "角色名已存在: xxx" }`；鉴权失败 `403`；部分同步失败 `500 { "error": "...", "rolled_back": true }`（见 §6 回滚）。

**处理流程（`PlayerIdentityService.renamePlayerCharacter` 编排，单事务语义 + per-session 锁）**：

```
1. 定位：player_id → CharacterEntity（或 old_name → 实体）；鉴权
2. 撞名校验（§5）：库内同名（排除自身）→ 409；活跃会话内同名角色 → 409
3. 角色库改名：CharacterController 内存列表改名 + DatabaseService 删旧建新
   （playerId 绑定随新行保留）
4. 收集活跃会话：RouterService（当前会话绑定该玩家？）、SimulationService（2D 世界含旧名？）、
   WerewolfService/controller playerSessions（玩家所在局）、ScriptGameService/controller playerSessions
5. 逐个同步：router.renameAgent / simulation.renamePlayerCharacter / werewolf.renamePlayer / script.renamePlayer
6. 任一失败 → 回滚已改项（角色库改回旧名 + 已同步会话改回旧名），返回 500
7. 返回 synced_sessions 清单
```

**与既有 `PUT /api/characters/{name}` 的关系**：原端点**保留**（不破坏现有契约，前端未升级时仍可改名，但不做局中同步）；升级后的角色库改名弹窗改调新端点。两路径都先过撞名校验（§5 的 controller 层校验同时加到原端点）。

### 4.2 四处运行时状态的同步改动点

> 标注格式：文件｜方法（行号）｜改动内容。所有 rename 方法均为**新增方法**，不改既有方法签名（除 §4.2.1 的判定行外，既有逻辑零改动）。

#### 4.2.1 一般模式 — `RouterService`（⚠️ 禁动文件，实施需授权）

| 项 | 内容 |
|---|---|
| 文件 | `service/RouterService.java` |
| 新增方法 | `public void renameAgent(String oldName, String newName)`：`Agent a = agents.remove(oldName); if (a == null) return; a.getPersona().setName(newName); agents.put(newName, a);`（agents map 在 :68；Persona.setName 在 `core/Persona.java:64`；Agent.getName 委托 persona，`agent/Agent.java:35-36`）。同时处理引用名：若 `protagonist`/`directorCharacter`（initSession :126-127）或 `restrictedAgents`（:80）等于 oldName → 同步替换 |
| 判定点改造 | `runRound` :233 `speakerIsAgent = speaker != null && !speaker.isBlank() && agents.containsKey(speaker)` → 加 `\|\| (playerId != null && agents.containsKey(identityService.resolveCharacterName(playerId)))`；runRound 增加重载 `runRound(userInput, userInterjection, speaker, playerId)`（旧三参保留委托，`SessionController.java:109` 调用点传新参） |
| 入史逻辑 | :307-314（命中原文入史）与 :325-331（旁白）不动——speaker 由前端传新名，历史 Message 保留旧名属**正确行为**（历史不可篡改，新轮次上下文经 `Agent.getName()` 自然用新名） |
| 历史/内存 | memory Session 消息不改名（不可变历史）；快照 `snapshotRound`（:1031）按新名存后续轮次 |
| 注意 | 本文件为 AGENTS.md 硬性约束 5 禁动文件 → **实施前必须主会话/主人授权**（对齐 P-0802-F：RouterService 串行调度先例） |

#### 4.2.2 2D 世界 — `SimulationService` / `SimulationWorld` / `AgentState`

| 项 | 内容 |
|---|---|
| 文件 | `simulation/SimulationWorld.java` |
| 新增方法 | `public void renameAgent(String oldName, String newName)`：`Agent a = agents.remove(oldName); a.getPersona().setName(newName); agents.put(newName, a);`（agents map :30，:64-71 registerAgent）；`AgentState st = states.remove(oldName); st.rename(newName); states.put(newName, st);`（states map :29） |
| 文件 | `simulation/AgentState.java` |
| 字段改动 | :8 `private final String agentName` → 去 final，新增 `public void rename(String newName) { this.agentName = newName; }`（最小改动；备选方案：重建 AgentState 拷贝全字段——不推荐，字段多易漏拷，且 toMap :130 依赖对象引用一致性） |
| 文件 | `simulation/SimulationService.java` |
| 新增方法 | `public void renamePlayerCharacter(String oldName, String newName)`：`world.renameAgent(oldName, newName)` + **重新断言标记** `world.getState(newName).setPlayerControlled(true)`（playerControlled 是"玩家本人"标记，必须随绑定迁移；判定读点 :450/:553 无需改动，标记随 state 走） |
| 开局判定改造 | `initWithPersonas` :192-211 加重载 `initWithPersonas(personas, sceneName, playerName, playerId)`：:200-202 判定改为 `name.equals(playerName) \|\| (playerId != null && name.equals(identityService.resolveCharacterName(playerId)))`（三参旧版保留委托，兼容 `SimulationController.java:86` 与既有测试） |
| 文件 | `simulation/SimulationController.java` |
| 参数透传 | :74 加读 `body.get("player_id")` → :86 传新重载（旧请求零破坏） |

#### 4.2.3 狼人杀 — `WerewolfService` + `WerewolfController`

| 项 | 内容 |
|---|---|
| 文件 | `service/WerewolfService.java` |
| 新增方法 | `public void renamePlayer(String sessionId, String oldName, String newName)`：GameState 名字键全量迁移——`roles`（:61）、`alive`（:62 列表元素）、`votes`（:86 voter→target 双向）、`playerKeys`（:89）、`eliminated`（:87 内 player 字段）、`discussionTranscript`（:94 内 speaker 字段）；`humanPlayers`（:141）集合换名；`pendingHumanEvents`（:96-97）队列内已有事件不做追溯（事件已在途，语义可接受） |
| 新增方法 | `public void renameHumanPlayer(String sessionId, String oldName, String newName)`：仅同步 `humanPlayers` 集合（供仅登记人类改名的场景复用） |
| 判定逻辑 | :614-619（AI=alive∉humans）、:672（aiVotes）、:383（planNight）、:773 均**无需改动**——名字键同步后自动正确 |
| 文件 | `controller/WerewolfController.java` |
| init 改造 | :49-82 加收 `player_id`（query 或 body）：`werewolfService.setHumanPlayers(sessionId, humans)` 前，若有 player_id 则 `humans = Set.of(identityService.resolveCharacterName(playerId))`（无则现状 `Set.of(player_name)`）；`playerSessions`（:29）键仍用名字 |
| rename 接线 | 新端点同步时调用 `renamePlayer`；`playerSessions`（:29）键 oldName→newName 同步 |

#### 4.2.4 剧本杀 — `ScriptGameService` + `ScriptController`

| 项 | 内容 |
|---|---|
| 文件 | `service/ScriptGameService.java` |
| 新增方法 | `public void renamePlayer(String sessionId, String oldName, String newName)`：ScriptGame 名字键全量迁移——`players`（:140 列表）、`assignments`（:141）、`playerAp`（:149）、`playerApMax`（:151）、`playerTalkativeness`（:153）、`playerIsHuman`（:155，虽只写不读仍同步保持一致）、`playerKeys`（:160）、线索归属（`my_clues`/ownership 结构）、投票（若 votes 结构存在）；`checkPlayerAccess`（:1428-1451）无需改（键同步后自然通过） |
| 快照兼容 | `saveSnapshot`（:1652 附近）content 增补 `player_id_bindings: {playerId → characterName}`（随快照落库）；`restoreFromSnapshot`/resumeGame 恢复时：若绑定存在且角色名与快照不一致 → 按绑定重映射（旧存档含旧名 → 恢复到新名，解决"改完名再重连"场景）；无绑定回退旧名逻辑 |
| 文件 | `controller/ScriptController.java` |
| init 改造 | :45-55 加收可选 `player_id`（body）；`playerSessions`（:27）键仍用名字 |
| rename 接线 | 新端点同步时调用 `renamePlayer`；`playerSessions`（:27）键 oldName→newName 同步 |

#### 4.2.5 判定链路解析式改造汇总（§3.3 优先级落地）

| 链路 | 判定点（现状） | 改造后 |
|---|---|---|
| 一般模式 | RouterService:233 `agents.containsKey(speaker)` | `agents.containsKey(speaker) \|\| agents.containsKey(resolve(playerId))` |
| 2D | SimulationService:200-202 `playerName.equals(p.getName())` | 加 `\|\| name.equals(resolve(playerId))` |
| 狼人杀 | WerewolfController:81-82 `Set.of(player_name)` | 有 player_id 时 `Set.of(resolve(playerId))` |
| 剧本杀 | （playerIsHuman 潜在链路，未来 AI 玩家用） | 有 player_id 时按解析名登记 |

---

## 5. 角色库改名撞名校验

**现状缺陷**（§1 #9）：`DatabaseService.saveCharacter` upsert-by-name（:67-79）→ 改名撞名时同名角色 persona 被静默覆盖；`CharacterController` 内存列表（:27）无唯一性。

**校验位置与方式（三层）**：

| 层 | 位置 | 方式 |
|---|---|---|
| ① 业务层（主） | `CharacterController.create`（:36-46）、`update`（:70-86）、`batch`（:95-110）入口 | 检查内存列表是否已有同名（`update` 排除自身）：命中 → `409 { "error": "角色名已存在: xxx" }`，不落库不覆盖 |
| ② 改名端点 | `PlayerIdentityService.renamePlayerCharacter` 第 2 步 | 库内同名（排除自身）→ 409；**活跃会话内同名角色**（四类会话的 agent/玩家名单）→ 409（防"改到与会话内 NPC 同名"造成身份混淆） |
| ③ DB 兜底 | `CharacterEntity.name` `@Column(unique = true)`（:22） | 并发窗口兜底：绕过错检时 DB unique 约束拒绝插入（现有约束已存在，无需改动；配合 ddl-auto: update 自动迁移新列） |

> 不推荐改 `DatabaseService.saveCharacter` 的 upsert 语义（它会破坏 `update` 路径"同 name 再保存"的正常流程，且调用点众多）；①层校验已挡住全部业务路径，③层防并发即可。

---

## 6. 实施步骤（分阶段）

> 每阶段完成 → 登记 `docs/修改记录.md` + 跑测试更新 `TEST_STATUS.md` → 交未衡审查 → 主会话统一 commit（AGENTS.md 纪律）。
> 每阶段开工前登记 `docs/并行作业登记.md`（标记 `P-0802-xx`）。

### Phase 1：身份字段落地（不改协议，零行为变化）

| 项 | 内容 |
|---|---|
| 改动文件 | `CharacterEntity.java`（+playerId 列）、`CharacterRepository.java`（+findByPlayerId）、`DatabaseService.java`（saveCharacter 重载 + entityToMap）、`CharacterController.java`（透传 + **撞名校验 ①** 一并落地）、`PlayerIdentityService.java`（新，含 resolve 双方法） |
| 前端 | `api/client.ts`（create/update 带 player_id）、`store/appStore.ts`（playerId 生成/持久化 localStorage） |
| 验证 | `mvn test` 全量回归（旧测试零破坏，player_id 缺省即现状）；新增 `CharacterRenameValidationTest`（create/update/batch 撞名 409 + 原数据未覆盖 + playerId 绑定落库/反查）；`npm run build` |

### Phase 2：判定链路切换（解析式）

| 项 | 内容 |
|---|---|
| 改动文件 | `RouterService.java`（⚠️ 授权）、`SessionController.java`（send 收 player_id + 传新重载）、`SimulationService.java`（initWithPersonas 重载）、`SimulationController.java`（2D start 收 player_id）、`WerewolfController.java`（init 收 player_id 解析登记）、`ScriptController.java`（init 收可选 player_id） |
| 验证 | 新增四组判定测试（§8 用例 1-4）+ 旧测试全量回归（**无 player_id 请求行为与现状逐字节一致**——重点回归断言）；`npm run build` |
| 授权 | RouterService 属禁动文件 → 本阶段改动前必须主会话/主人授权 |

### Phase 3：局中改名端点（同步式）

| 项 | 内容 |
|---|---|
| 改动文件 | `PlayerController.java`（新，POST /api/player/rename）、`PlayerIdentityService.java`（renamePlayerCharacter 编排 + 回滚）、`RouterService.renameAgent`、`SimulationWorld.renameAgent`、`AgentState.rename`、`SimulationService.renamePlayerCharacter`、`WerewolfService.renamePlayer`、`ScriptGameService.renamePlayer`（+快照 player_id_bindings）、两 controller 的 playerSessions 同步 |
| 验证 | 新增 `PlayerRenameE2ETest`（四链路同步断言）+ `ScriptRenameResumeTest`（改名后重连恢复）；真机验证：改名 → 2D/一般/狼人杀/剧本杀四处即时生效 |
| 风险点 | 并发 tick 竞争：rename 与对话轮/夜间结算并发 → rename 方法内对目标 session 对象 `synchronized`（Wolf/Script 已有多局，per-session 锁；Router/2D 单会话用方法锁） |

### Phase 4：前端收尾（可选）

| 项 | 内容 |
|---|---|
| 改动文件 | `ScenePage.tsx` / `ChatPage.tsx`（角色库改名弹窗改调 `/api/player/rename`；改名成功后本地 playerName 状态、SSE 展示名更新）、`api/client.ts`（+playerRename） |
| 验证 | 真机走查：任意时刻改名 → 会话内立即可用新名互动、主控不再代声 |

---

## 7. 风险与回滚

| 风险 | 场景 | 缓解 |
|---|---|---|
| **改名不同步 → 角色分裂** | 同步失败/前端未调新端点 | ① 解析式判定兜底：即使运行态未同步，主控判定仍按 player_id 解析新名 → **不静默 AI 化**；② 未同步时玩家用新名互动会得到明确错误（sendUserMessage 找不到 agent / checkPlayerAccess 403）→ 可观测而非身份漂移；③ 改名端点原子执行 + 失败回滚（§4.1 第 6 步） |
| **旧存档兼容** | 快照（ScriptEntity type=snapshot / WorldSnapshotEntity / ConversationLogEntity / GameSessionEntity）存旧名 | 快照新增 `player_id_bindings`（Phase 3），resume/loadSession 时按绑定重映射到新名；无绑定（老数据）回退名字逻辑零破坏；历史对话消息保留旧名属正确（不可篡改） |
| **前端未更新降级** | 旧前端无 player_id、仍调 PUT /api/characters/{name} | 原端点保留且行为不变（仅加撞名 409）；改名不做局中同步 → 玩家手动重开会话后按新名重新开局（解析式保证新会话正确标记）——降级可接受 |
| **撞名覆盖数据丢失** | 现状缺陷 | Phase 1 三层校验（§5）根治；已发生的覆盖不可恢复（无历史表），登记为已知遗留 |
| **并发竞争** | rename 与 tick/夜间结算/投票并发 | per-session 锁（§6 Phase 3 风险点）；rename 编排先锁后改，防半同步状态被 tick 读取 |
| **禁动文件** | RouterService / SSE 主链路 | 实施前授权；SSE 主链路本期零改动（改名事件推送可后置 Phase 4，复用既有 broadcast 机制，不新增通道——对齐 D-013/D-021 纪律） |
| **回滚预案** | 任一阶段出问题 | 各 Phase 均为增量叠加：Phase 1 可单独回滚（删列 + 还原 controller 校验）；Phase 2 开关化可逆（判定优先 player_id，回退即不传 player_id）；Phase 3 端点可整体下线（原 PUT 路径仍在）；无 DB 迁移脚本（ddl-auto 自动加列，删列即回滚） |

---

## 8. 测试方案（针对四处判定链路）

> 新增测试类全部放 `src/test/java/com/roleplay/engine/` 对应包，走 application-test.yml（H2 mem + mock LLM + RANDOM_PORT）。

| # | 测试类（新增） | 用例设计 |
|---|---|---|
| 1 | `Test2dPlayerRenameTest` | ① `initWithPersonas(..., playerId)` 正确标记 playerControlled；② `renamePlayerCharacter` 后新名 state `isPlayerControlled()==true`、旧名 state 不存在、位置/情绪保留；③ 无 playerId 三参旧路径行为不变（回归） |
| 2 | `RouterRenameTest` | ① 带 player_id 发言 + 角色已改名 → `speakerIsAgent` 命中（原文入史 Role.AGENT、本轮该角色排除 LLM 生成、无旁白）；② 改名后旧名 speaker 回落旁白（预期行为，防回归）；③ `renameAgent` 后 agents map 键正确、protagonist/restrictedAgents 引用同步；④ 无 playerId 全旧行为 |
| 3 | `WerewolfRenameTest` | ① init 带 player_id → `getHumanPlayers` 含解析出的当前名；② `renamePlayer` 后 roles/alive/votes/playerKeys/humanPlayers 全键同步；③ AI planner 不驱动改名后的玩家（planNight/planVotes 输出不含该玩家）；④ nightComplete 判定正确 |
| 4 | `ScriptRenameTest` | ① `renamePlayer` 后 checkPlayerAccess（新名+roleKey）通过、toMap 视图含新名；② assignments/playerAp/playerKeys/playerIsHuman 同步；③ 快照含 player_id_bindings → resume 改名后按绑定恢复新名；④ 无绑定旧快照恢复走名字逻辑 |
| 5 | `CharacterRenameValidationTest`（Phase 1） | ① PUT 改名撞名 → 409 且原同名角色 persona 未被覆盖；② create/batch 撞名 → 409；③ playerId 绑定落库 + findByPlayerId 反查；④ 撞名 409 后内存列表与 DB 一致 |
| 6 | `PlayerRenameE2ETest`（Phase 3） | 一次改名 → 四链路同步断言：RouterService agents 键 / 2D states 键+标记 / WerewolfService humanPlayers+GameState / ScriptGameService 全键 + checkPlayerAccess；响应含 synced_sessions 清单；撞名 409 场景 |
| 7 | 回归 | 全量 `mvn test` 基线（当前 309/0，D-029）+ `npm run build`；重点盯：无 player_id 路径全部旧断言（SessionController send / werewolf init 无 player / script init 无 player） |

**测试数据构造注意**：所有测试走 mock LLM（D-008 基建）；狼人杀/剧本杀用既有 test helper（D-025/D-028 先例）构造对局，不引入真实 LLM 调用。

---

## 9. 涉及文件清单（实施时总账）

**后端核心（16）**：`db/entity/CharacterEntity.java`、`db/repository/CharacterRepository.java`、`db/service/DatabaseService.java`、`controller/CharacterController.java`、`service/PlayerIdentityService.java`（新）、`controller/PlayerController.java`（新）、`service/RouterService.java`（禁动，需授权）、`simulation/SimulationService.java`、`simulation/SimulationWorld.java`、`simulation/AgentState.java`、`simulation/SimulationController.java`、`controller/SessionController.java`、`service/WerewolfService.java`、`controller/WerewolfController.java`、`service/ScriptGameService.java`、`controller/ScriptController.java`

**前端（4）**：`roleplay-v4/frontend/src/api/client.ts`、`store/appStore.ts`、`components/ScenePage/ScenePage.tsx`、`components/ChatPage/ChatPage.tsx`

**新增测试（7 类）**：`CharacterRenameValidationTest`、`Test2dPlayerRenameTest`、`RouterRenameTest`、`WerewolfRenameTest`、`ScriptRenameTest`、`PlayerRenameE2ETest`、`ScriptRenameResumeTest`

**配置（可选，2）**：`application.yml` / `application-test.yml`（如需 `roleplay.player.rename-enabled` 开关——默认 true，对齐 D-004 可配纪律；本期可先不加）

**文档（6）**：`docs/修改记录.md`、`TEST_STATUS.md`、`DECISION_LOG.md`（新决策登记）、`docs/INDEX.md`、`PROJECT_CONTEXT.md`、`docs/并行作业登记.md`

**合计：核心改动 20 文件（16 后端 + 4 前端）+ 新增测试 7 类 + 配置 2 + 文档 6 = 35 个文件条目**；其中仅 `RouterService.java` 为禁动文件需授权，`SSEController`/`ArbiterService`/审批链路零改动。

---

## 10. 方案决策记录（供实施后登记 DECISION_LOG 用）

| 决策 | 内容 | 原因 | 放弃 |
|---|---|---|---|
| 玩家身份 = 客户端持有 player_id UUID | 前端生成持久化，后端角色库绑定，判定解析式 | 对齐 roleKey 先例（D-017/D-028/D-029）；无需服务端账号体系；一期不改协议 | 服务端账号/登录体系（超范围）；会话内绑定表（单会话架构用不上） |
| 判定链路改造 = 解析式 + 同步式结合 | 判定点解析 player_id，改名端点同步运行态 | 解析防"静默 AI 化"，同步保"立即可用"，两者互补 | 只做解析（运行态键不换无法互动）；只做同步（同步失败即身份漂移） |
| 改名端点独立 `POST /api/player/rename` | 保留原 PUT 兼容 | 原 PUT 是角色库 CRUD，改名同步是跨服务编排，职责分离 | 在 PUT 上加 propagate 标志（耦合 CRUD 与编排，回滚复杂） |
| 撞名三层校验 | controller 业务校验 + 改名端点会话内校验 + DB unique 兜底 | 覆盖全部业务路径；不动 upsert 语义避免破坏既有调用点 | 改 DatabaseService upsert 语义（风险面大） |
| Agent 改名走 persona.setName + 换 map 键 | Agent 类零改动 | Agent.getName 委托 persona（:35-36），Persona.setName 已存在 | 改 Agent 类加新字段/构造（多余） |
