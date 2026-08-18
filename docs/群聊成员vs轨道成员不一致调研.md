# 群聊成员列表 vs 对话轨道成员不一致——深度调研报告

> 调研人：researcher subagent（P-0815-H，台账 #208，2026-08-15 23:25-23:5x）
> 性质：**只读调研**，零代码改动。已用只读接口（GET /api/simulation/state、/api/simulation/conversation-status）真机取证。
> 主人反馈（2026-08-15 23:07）：「群聊中只有 me（你）、沈墨，但两个轨道四个人都能互相聊天」——苏浅浅、沈墨、白露等 4 个角色在互相聊天（苏浅浅对沈墨说悄悄话、白露在远处站着等）。

---

## 一、结论先行（TL;DR）

**根因 = 前端显示范围不一致（消息流=全世界，成员列表=玩家当前所在群），不是后端轨道范围错误。**

三张清单的实际数据来源：

| 清单 | 数据源 | 实际内容 |
|---|---|---|
| ① 群聊界面成员列表（群头） | `GET /api/simulation/conversation-status` → `currentTrack` 所在群 `participants` | **仅玩家所在群**（实测：me + 沈墨，2 人） |
| ② 2D 世界 AgentState（load-characters 传入） | `POST /api/simulation/load-characters` body `characters` → `initWithPersonas` 逐一注册 | **4 人**（实测：me、沈墨、苏浅浅、白露） |
| ③ 对话轨道/组成员 | ConversationManager 按空间听觉聚类（ModeClassifier） | 4 人分成 3 个组（沈墨+苏浅浅、白露+苏浅浅、me+沈墨），组内成员=世界 AgentState 子集，**来源正确** |

而**面板消息流**（主人看到的"4 人互相聊天"）拍平的是**全世界所有组的 recentConversations**（`ConversationManager.executeRound` 把每个组的每轮发言都写进 `SimulationWorld.recentConversations`），前端 `worldMsgs` 不做群过滤 → 群聊标题下同时出现 3 个组的 4 个角色对话。

**证据强度：高**（代码行号链 + 8000 实例实时数据完全复现主人贴文内容）。

---

## 二、逐项取证

### 2.1 角色加载链路（问题 1）

**前端**：`ScenePage.tsx` `openPhaserSim`（L107-121）组装进入 2D 世界的角色：
```ts
const scenePlayers = Array.from(new Set([...(currentPlayer ? [currentPlayer] : []), ...pool, ...roomPlayers]));
// pool = 点开剧本卡的 default_roles 或当前勾选集合 selectedNames
```
→ `setSimChars(charDetails)` → `<PhaserSimulationView characters={simChars} .../>`（L840）。

**前端发请求**：`PhaserSimulationView.tsx` L339-377 `loadCharacters()`：
```ts
const clean = characters.filter(c => c && c.name && String(c.name).trim());
body: JSON.stringify({
  characters: clean.map(c => ({ name, persona, voice, background })),
  scene: currentScene,
  ...(playerName ? { player_name: playerName } : {}),
  ...(map ? { map } : {}),
})
```
→ `POST /api/simulation/load-characters`。

**后端**：`SimulationController.loadCharacters`（L102-143）从请求体 `characters` 建 Persona 列表 → `simulationService.initWithPersonas(personas, ...)`（L141）→ `SimulationService.initWithPersonas`（L306-340）逐 persona `world.registerAgent(agent, x, y, hearRange, moveSpeed)`（L335），AgentState = `world.getState(p.getName())`（L327）。

**结论**：**② = load-characters 传入的角色 = 前端勾选的角色（含玩家自己）**。后端零额外来源（无"角色库全量"注入路径）。实测 `agentCount=4`（me、沈墨、苏浅浅、白露），与主人贴文 4 人一致——2D 世界**确实加载了 4 人**，不是加载少了。

**群聊界面成员列表（①）为什么只有 me+沈墨**：
- `PhaserSimulationView.tsx` `fetchGroups`（L634-649）：每 4s 拉 `GET /api/simulation/conversation-status` → `currentTrack`（玩家所在群 id）→ `setJoinedGroup(list.find(g => g.id === track))`。
- 面板传参（L942）：`groupInfo={joinedGroup && joinedGroup.id ? { id, mode, participants, topic } : undefined}`。
- `SimGalChatPanel.tsx` L172-184 渲染群头：`👥 群聊 · {topic}` + `participants` 列表（玩家高亮 `me（你）`）。
- **① 的正确语义 = "玩家当前所在群"**，不是"世界全部角色"。玩家只在一个群里（me+沈墨 DYAD），所以列表只有 2 人。

### 2.2 轨道成员来源（问题 2）

- `TrackDirectorService.assign(List<AgentState> agents)`（L117/127）的 agents 来自 `ConversationManager.executeRound`（L442-445）：
  ```java
  assignments = trackDirector.assign(group.getParticipantList(), goals);
  ```
  即 **轨道成员 = 组内成员 = 世界 AgentState 的空间聚类**。
- 组从哪来：`ConversationManager.tick`（L495-520）用 `ModeClassifier.classify(hearing, available)` 按听力/距离聚类 → `startGroup(gid, mode, members)`（L538）→ `new ConversationGroup(groupId, mode, members, ...)`（L553）。L828 的 `new ConversationGroup` 是剧本杀讨论组（显式成员，独立实例，与本问题无关）。
- AgentState 创建：仅 `initWithPersonas`（load-characters 链路）与 `initDemo` 两条路径。**不存在第三条"角色库全量"来源**。

**结论**：③ 的来源正确——轨道成员 = 世界在场角色的空间分组。4 人分 3 组是正常模拟结果（苏浅浅与沈墨近 → 一组；白露与苏浅浅近 → 另一组；玩家与沈墨近 → DYAD）。

### 2.3 不一致根因（问题 3）——核心

**不是**①（前端传了 4 人但 UI 只显示 2 人显示过滤）——UI 显示的是"玩家所在群"，语义本就如此；
**不是**②（后端用了会话全部角色）——轨道成员=世界 AgentState 空间分组，正确；
**是**④（新结论）：**消息流未按群过滤**。

证据链：
1. `ConversationManager.executeRound` 每轮把**本组**发言写进 `world.addConversationEntry(convEntry)`（L779-786），convEntry 含 `group/mode/tick/round` 元数据 + 各发言者文本（每条截断 80 字符）。
2. `SimulationWorld.recentConversations`（L43/L128-132）**全局单列表**，所有组的发言都进同一个队列（上限 MAX_RECENT）。
3. `GET /api/simulation/state` 与 SSE world_snapshot 都下发 `recentConversations`（`SimulationService.getState` L450）。
4. 前端 `PhaserSimulationView` `conversations` state = SSE + 3s 轮询的 recentConversations（L279-330）。
5. `worldMsgs` memo（L436-460）拍平**全部** conversations：`for (const c of conversations) for (const [k, v] of Object.entries(c))`，只跳过 `SKIP_CONV_KEYS = new Set(['pair','group','mode','tick','elapsedMs','round'])`（L73）——**group 键被跳过，消息不带组归属**，无从过滤。
6. `SimGalChatPanel` 入队 effect（L129-145）对全部 worldMsgs `liveEnqueue`——**无群过滤**。
7. 面板最终显示：群头（当前群 me+沈墨）+ 消息流（全世界 3 个组 4 个角色）→ 主人看到的"4 人互相聊天"。

**8000 实例实时取证（2026-08-15 23:3x）**，与主人贴文逐字吻合：
- `GET /api/simulation/state`：`agentCount=4`（me、沈墨、苏浅浅、白露）；`recentConversations` 含 4 条：
  - `沈墨+苏浅浅` DYAD：苏浅浅「你倒是来得巧，这湖边风景不错，适合说些悄悄话」/ 沈墨「浅浅，好久不见…」
  - `白露+苏浅浅` DYAD：苏浅浅「…她倒是沉得住气，站在那儿看了这么久」/ 白露「天色不早了，湖边风大，你们聊完早些回去」
  - `me+沈墨` DYAD：沈墨「往东走三百米，穿过那片银槐林…」
  - `白露+苏浅浅` DYAD：苏浅浅「白家娘有心了…」/ 白露「看风景而已，不必多想」
- `GET /api/simulation/conversation-status`：`activeGroups=1`，`groups[0] = {id:"me+沈墨", mode:"DYAD", participants:["me","沈墨"]}`，`currentTrack="me+沈墨"`。

### 2.4 「白」单独一行（问题 4）

**是占位立绘/成员 chip 显示姓名首字，不是截断 bug**：
- `GalCharacter.tsx` L57 `GalNamePlate`：`const ch = (speaker.name || '?').slice(0, 1);` —— 未知角色占位立绘（像素风 SVG）只画**姓名首字**，完整名在 `aria-label`（L55 `aria-label={speaker.name}`）。
- `GalGeneralView.tsx` L300 成员 chips：`{(name || '?').slice(0, 1)}` + `title={name}`（悬停显全名）。
- 所以「白露」→ 显示「白」。这是 P-0810-06 的设计（真实 SSE 流角色名无法预知像素模板 → 深色底+首字占位），**功能正常**；若主人观感不佳，可在占位图/成员 chip 旁补全名小字（见修复建议 C）。

### 2.5 对比意图（问题 5）：所见即所得缺口

预期：群聊界面显示谁，消息就是谁。当前缺口：
1. **群头成员（当前群）≠ 消息流角色（全世界）**——同一面板内两个数据源范围不一致，主人感知为"列表 2 人、聊天 4 人"。
2. 消息流中其他组的消息**没有组归属标识**（SKIP_CONV_KEYS 丢弃了 group 键），用户无法区分"这是哪个组的对话"。
3. 自由对话模式（未入群）与群聊模式的展示边界没有区分——入群后消息流仍是全世界。

---

## 三、根因结论（证据强度排序）

| # | 结论 | 证据强度 |
|---|---|---|
| 1 | **前端消息流未按当前群过滤**：`worldMsgs` 拍平全世界 recentConversations（PhaserSimulationView L436-460 / SKIP_CONV_KEYS L73 丢 group 键），SimGalChatPanel 全量入队（L129-145）；群头成员列表却只来自 currentTrack 群（L634-649 / L942） | **强**（行号链 + 8000 实时数据逐字复现） |
| 2 | 后端轨道/组成员来源正确：= 世界 AgentState 空间聚类（ConversationManager L442-445 / L553），成员是 4 人的正确分组 | **强**（行号链） |
| 3 | 2D 世界确实加载 4 人：load-characters = 前端勾选（ScenePage openPhaserSim L107-121 → PhaserSimulationView L339-377 → SimulationController L102-143 → initWithPersonas L306-340），实测 agentCount=4 | **强**（行号链 + 实时数据） |
| 4 | 「白」= 占位立绘/成员 chip 姓名首字（GalCharacter L57 / GalGeneralView L300），非截断 bug | 中（代码即证据，需主人确认观感） |

**排除项**：不存在"前端只传 2 人""后端用了会话全部角色""白露未注册进 UI"三种假设。

---

## 四、最小修复建议（按影响面排序，供主会话/未衡决策）

### 方案 A（推荐）：前端按当前群过滤消息流（2D 群聊面板）
- **改动点**：`PhaserSimulationView.tsx` worldMsgs memo 保留 `group` 归属（SKIP_CONV_KEYS 不再丢 group，或另存 group 字段）；`SimGalChatPanel.tsx` 入队 effect 增加过滤——`groupInfo` 非空时仅入队 `m.group === groupInfo.id` 的消息；`groupInfo` 为空（自由对话模式）保持现状（显示全世界消息）。
- **影响面**：仅 2D 群聊面板消息流；后端零改动；2D 世界气泡（SimulationScene）与"世界活着"的观感不受影响（气泡数据源独立）；自由对话模式行为不变。风险低（1-2 文件）。
- **语义**：所见即所得——群聊面板 = 当前群成员 + 当前群消息。

### 方案 B（不建议）：后端把 recentConversations 过滤为当前组
- **影响面**：`SimulationWorld.recentConversations` 被 GET /api/state、SSE、2D 气泡、群聊面板多方消费；过滤会破坏 2D 世界"其他组也在聊"的氛围展示。**不推荐**。

### 方案 C（可选增强）：消息带组标识 / 成员列表补全名
- 消息条目加组名角标（如「白露+苏浅浅 组」小字），或群头下方显示"世界其他对话：苏浅浅×白露…"提示行，让"4 人聊天"可解释。
- 「白」观感问题：GalNamePlate / GalGeneralView 成员 chip 补全名小字（aria-label/title 已有，视觉层补一行）。

### 验收建议
- 复现路径：ScenePage 勾选 ≥4 角色（含 1 玩家）→ 进入 2D → 点击任意 NPC 入群（或靠近 AI 群加入）→ 观察群头成员 vs 消息流角色一致（方案 A 后应只显示当前群消息）。
- 回归检查：未入群（自由对话）时仍能看到世界其他组消息（2D 氛围保留）。

---

## 五、调研过程说明

- 只读操作：阅读源码（SimulationController / SimulationService / SimulationWorld / ConversationManager / TrackDirectorService / ScenePage / PhaserSimulationView / SimGalChatPanel / GalGeneralView / GalCharacter / GalLivePanel / simulationData.ts）+ 2 个只读 GET 接口（/api/simulation/state、/api/simulation/conversation-status）。
- 未启动服务、未改代码、未创建测试场景、未污染世界（GET 无副作用）；临时抓包文件已删除。
- 禁动文件（RouterService/ArbiterService/审批/狼人杀/剧本杀 Service/SSE 主链路/static）零改动。
- 未 git commit。
