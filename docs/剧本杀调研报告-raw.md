> ⚠️ 本文件较大（约 31 KB），agent 请按需搜索读取，勿整体加载

# 剧本杀模式全链路深度调研报告（raw）

> 调研日期：2026-07-31 ｜ 调研方式：源码逐行取证（后端 Java + 前端 TSX/TS + 需求文档原文）
> 本报告为《剧本杀差距分析-待办.md》重写的事实依据，所有结论均带文件路径+行号
> 后端三件套实际行数：ScriptGameService.java = 246 行（非 241）、ScriptService.java = 44 行、ScriptController.java = 83 行

---

## ① 后端现状取证

### 1.1 ScriptGameService.java（246 行）— 完整游戏生命周期

路径：`src/main/java/com/roleplay/engine/service/ScriptGameService.java`

**类声明与依赖**
- L15 `@Service public class ScriptGameService`
- L28 构造注入 `LLMClient llmClient`
- L61 `private final Map<String, ScriptGame> games = new ConcurrentHashMap<>()` —— **纯内存状态，不落库，重启即丢**

**状态机枚举（L32）**
```java
public enum Phase { SETUP, INVESTIGATION, DISCUSSION, VOTE, REVEAL, ENDED }
```

**内部类 ScriptGame（L34–L66）字段**
- `sessionId / phase / name / background / truth`、`roles(List) / players(List) / assignments(Map<String,String> player→role)`、`round(int)`、`clues(List<Map>) / playerClues(Map<String,List<String>> player→clueIds) / votes(Map<String,String> voter→suspect) / locations(List) / winner(String)`
- **没有 secrets 字段** —— 结构层面即未预留秘密存储

**`toMap(String playerName)`（L54–L65）** —— 状态快照输出
- 输出键：`phase / name / background / roles / your_role / round / game_over / winner / clues / locations`
- L56–60 线索过滤：`clues` 仅返回 `public==true` 或该玩家 `playerClues` 中已有的（可见性按玩家隔离，这是唯一已有的"信息隔离"逻辑）

**`initGame(String sessionId, String theme, List<String> playerNames)`（L81–L157）**
- L86–109 LLM prompt（text block）：要求返回纯 JSON，含 `name / background(100-150字) / roles / locations / clues[{id,location,content,public,related_role}] / secrets{角色名:秘密} / truth(50-80字)`
- L104 是 prompt 中 `"secrets": {"角色1": "秘密内容", ...}` 行
- L110 `Map<String, Object> script = llmClient.callJson(prompt, 600);`（token=600）
- L112 失败时 fallback `defaultScript(theme, playerNames)`
- **L113–147 只取 `name / background / truth / roles / locations / clues` 六个键，prompt 要求的 `secrets` 从未被读取** → **secrets 生成后即丢弃，无任何消费方**（grep 全 service 目录无 secrets 相关代码）
- L139–146 角色分配：`Collections.shuffle` 打乱 roles 后按序分给 players；人数超出时补 `"嫌疑人_N"`（L145–146）
- L149 `game.phase = Phase.INVESTIGATION;`（SETUP 由 init 直接跳入 INVESTIGATION）
- L151 `games.put(sessionId, game);`
- L156 返回 `toMap(players.get(0))` —— 响应只含第一个玩家的视角

**`search(String sessionId, String player, String location)`（L160–L197）**
- L166 阶段校验：仅 `INVESTIGATION` 可搜，否则返回 `{"error":"当前不是搜证阶段"}`
- L169–172 取该地点 `public==false` 线索；L176–181 去重写入 `playerClues`；L183–187 同地点公开线索一并返回
- 响应键：`found / clues / public_clues / location`
- **无行动力/次数限制、无角色差异化（如侦探线索多）、无失败概率** —— 搜证是"必得"逻辑

**`castVote(String sessionId, String voter, String suspect)`（L199–L207）**
- L202 阶段校验：仅 `VOTE` 可投；L203 `voter.equals(suspect)` 禁止投自己
- L204 `game.votes.put(voter, suspect);` 返回字符串 `"X 投票给了 Y"`
- **无"已投不可改/未投玩家检查"**

**`resolveVote(String sessionId)`（L209–L249）** —— 判定核心
- L215–219 计票 `voteCount.merge(s, 1, Integer::sum)`
- L220–225 取最高票 `mostVoted`；L227–229 平票检测（`ties > 1`）
- **L239 真相判定：`boolean correct = game.truth.contains(mostVoted);`** —— 字符串包含判断（truth 文案里出现投票对象名字即判中），脆弱；无凶手实体概念
- L241–243 平票时 `result="平票，无人被定罪"`（**此时 `correct` 键不存在，且 L245–246 仍无条件执行**）
- L245 `game.phase = Phase.REVEAL;` L246 `game.winner = mostVoted;` —— **平票时 winner 也会被设成最高票者（可能是空串或平票对象），`game_over` 语义错误**（L57 toMap 的 game_over 由 `!winner.isEmpty()` 决定）
- **`ENDED` 阶段从未被任何方法写入** —— 终态实际停在 REVEAL

**`startVoting(String sessionId)`（L251–L256）**
- 仅当 phase ∈ {INVESTIGATION, DISCUSSION} 时 `game.phase = Phase.VOTE`；无返回值（void）

**`startDiscussion(String sessionId)`（L259–L264）**
- 仅当 phase == INVESTIGATION 时 `game.phase = Phase.DISCUSSION; game.round++`；**只改状态，不接任何对话/讨论系统**；无返回值

**`getGame(String sessionId)`（L267–L268）**

**`defaultScript(String theme, List<String> players)`（L275–L302）** —— LLM 失败兜底，内容为硬编码剧本

**关键结论**
1. 状态机六阶段名义齐全，但：SETUP 即跳 INVESTIGATION；ENDED 永不触达；DISCUSSION 为空壳
2. secrets 生成后即丢，无字段、无发放、无注入
3. truth 判定是 `contains` 字符串匹配（L239）
4. 全部状态在内存 ConcurrentHashMap，无持久化

### 1.2 ScriptService.java（44 行）— 剧本生成骨架

路径：`src/main/java/com/roleplay/engine/service/ScriptService.java`

- L15 `@Service public class ScriptService`；L19–21 构造注入 `LLMClient`
- **L24 `public Map<String, Object> generateScript(String theme, List<String> characters)`**
  - L25–31 prompt：要求 JSON `{name, background, roles, clues[], truth}` —— **无 secrets、无 locations、无 related_role、无 clue id/public 分级**
  - L33 `llmClient.callJson(prompt, 400)`（token=400，比 ScriptGameService 的 600 少）
  - L34–41 LLM 失败 fallback：硬编码 `{"name":"默认剧本","background":"一个普通的谋杀案","roles":characters,"clues":["现场有脚印","窗户是开的"],"truth":"凶手是管家"}`
- **与 ScriptGameService 功能重复**：两套 LLM 剧本生成器，prompt schema 不一致（init 的 schema 更完整）
- **唯一调用方**：`SessionController.generateScript`（见 1.4）

### 1.3 ScriptController.java（83 行）— 剧本杀端点（主）

路径：`src/main/java/com/roleplay/engine/controller/ScriptController.java`

- L13–14 `@RestController @RequestMapping("/api/script")`
- L18 `Map<String, String> playerSessions`（player→sessionId）+ L19 `String currentSessionId` —— **单机内存态 session 管理，无鉴权、无生命周期清理**

| 行号 | 端点 | 方法签名 | 请求体 | 响应体 |
|---|---|---|---|---|
| L25–34 | `POST /api/script/init` | `init(@RequestBody Map<String,Object> body)` | `{players: string[], theme?: string}` | `initGame` 的 toMap：`{phase,name,background,roles,your_role,round,game_over,winner,clues,locations}`（仅第一玩家视角） |
| L36–42 | `POST /api/script/search` | `search(@RequestBody Map<String,String> body)` | `{player, location}` | `{found:string[], clues:[{id,content}], public_clues:[{id,content}], location}` |
| L44–49 | `POST /api/script/start_discussion` | `startDiscussion(@RequestBody Map<String,String> body)` | `{session_id?}` | `{phase:"discussion"}` |
| L51–56 | `POST /api/script/start_voting` | `startVoting(@RequestBody Map<String,String> body)` | `{session_id?}` | `{phase:"vote"}` |
| L58–65 | `POST /api/script/vote` | `vote(@RequestBody Map<String,String> body)` | `{player, suspect}` | `{result:"X 投票给了 Y"}` |
| L67–71 | `POST /api/script/resolve` | `resolve(@RequestBody Map<String,String> body)` | `{session_id?}` | `{votes, most_voted, vote_count, result, correct?, truth}` |
| L73–82 | `GET /api/script/status?player=` | `getStatus(@RequestParam String player)` | — | toMap 或 `{phase:"idle"}` / `{phase:"not_found"}` |

- session 解析逻辑：`playerSessions.getOrDefault(player, currentSessionId)`（L40/L62/L75）—— 未 init 过的人会落到全局 currentSessionId

### 1.4 SessionController.java（额外发现）— 第三个剧本杀端点

路径：`src/main/java/com/roleplay/engine/controller/SessionController.java`

- **L164–172 `POST /api/script/generate`**：`@RequestBody {theme?, characters: string[]}` → 调 `scriptService.generateScript(theme, characters)` → 返回 ScriptService 的 JSON
- 与 ScriptController 的 `/api/script/init` **路径不冲突**（完整路径不同），但同前缀 `/api/script/*` 分属两个 Controller
- **前端 `scriptJson` 占位 UI 本应接这个端点（生成剧本预览），但前端完全没调**

### 1.5 持久化取证 — 剧本不落库

- `ScriptEntity.java`（L8）：`@Entity @Table(name="scripts")`，字段 `id / name / contentJson(TEXT) / createdAt`，构造 `ScriptEntity(String name, String contentJson)`（L23–27）
- `ScriptRepository.java`（L8）：`interface ScriptRepository extends JpaRepository<ScriptEntity, Long> {}` —— 无自定义方法
- `DatabaseService.java`：L196–207 `saveScript(String name, Map<String,Object> content)`（Jackson 序列化落库）、L209–213 `getAllScripts()`
- **grep 全源码：`saveScript/getAllScripts` 仅在 DatabaseService 定义，无任何 Controller/Service 调用** → 落库能力存在但剧本杀流程完全未接线，**剧本杀对局不落库**（狼人杀/自由模式的会话历史走 RouterService + GameSessionRepository，剧本杀是独立内存 map，天然没有历史）

---

## ② 前端现状取证

路径前缀：`D:\echoworld\frontend\src`

### 2.1 ScenePage.tsx（568 行）— 剧本杀占位位置确认

- **L34** `const [scriptPrompt, setScriptPrompt] = useState('');` —— 有 setter，可用
- **L35** `const [scriptJson] = useState('');` —— **只有 getter 没有 setter，恒为空字符串**
- **L227–230 `genScript`**：
  ```ts
  const genScript = async () => {
    setStatus('剧本杀模式正在开发中，敬请期待');
  };
  ```
  纯占位 —— **不调用任何 API，不 set scriptJson**
- **L232–234 `startScript`**：同样 `setStatus('剧本杀模式正在开发中，敬请期待')` 纯占位
- **L480–503 `rulesTab === 'script'` 的 UI 区块**：
  - L482–488 textarea（剧本描述输入，绑定 scriptPrompt）
  - L490–492「AI 生成剧本」按钮 → `onClick={genScript}`（disabled 条件 `!scriptPrompt || generating`）
  - **L494 `{scriptJson && (...)}`**：因 scriptJson 恒为 ''，L496 textarea 预览 + L497–499「加载剧本并开始」按钮（onClick=startScript）**是永远不会渲染的死代码**
- L22 `rulesTab: 'ww' | 'script'` 切换 chips：L393 狼人杀 / L394 剧本杀
- **结论：剧本杀前端 = 一个可输入描述的 textarea + 两个占位按钮 + 一段死代码；零 API 调用**

### 2.2 api/client.ts（109 行）— 无剧本杀封装

- `api` 对象 L47–109：角色/场景/回合/历史/房间/狼人杀/语音/配置等封装齐全
- 狼人杀仅两条：**L95 `werewolfInit`**、**L96 `werewolfStatus`**
- **grep 全 frontend/src：`api/script`、`scriptInit`、`scriptSearch`、`scriptStatus`、`scriptVote`、`scriptResolve` 零命中**（仅有 ScenePage L35/L494/L496 的 `scriptJson` 变量名）
- **结论：client.ts 完全没有剧本杀 API 封装，一条都没有**

### 2.3 store/appStore.ts — 无剧本杀状态

- **无任何 script 相关字段/方法**（grep 无 script 命中）
- 狼人杀状态字段 L31–35：`werewolfPhase / werewolfRound / werewolfPlayers / werewolfMyRole / werewolfWaitHuman`；对应 setter L85–89、L401–409
- L254–255 `loadHistory` 中 `wwModes = ['werewolf','rules']` 注释写明 "script mode filtering handled on backend" —— **后端没有做 script 过滤**（剧本杀不在 RouterService 会话体系内，无历史可过滤）

### 2.4 App.tsx（268 行）— SSE 事件无剧本杀分支

- L29–255 SSE handler：`round_start / arbiter_task / agent_output / user_input / werewolf_phase(L109) / werewolf_player_update(L132) / werewolf_my_role(L143) / werewolf_player_eliminated(L153) / werewolf_wait_human(L164) / werewolf_game_over(L170) / werewolf_witch_info(L177) / tts_*` 等
- **无任何 `script_*` 事件分支**（后端 ScriptGameService 目前也不发 SSE 事件——它没有 SSE 推送逻辑，狼人杀的 SSE 推送来自 WerewolfService/RouterService 体系）

### 2.5 types/index.ts — 无剧本杀类型

- L57 `WerewolfPhase = 'night' | 'day_discussion' | 'day_vote' | 'ended' | 'game_over'`；L59 `WerewolfPlayer`
- **无 ScriptPhase / ScriptGame / ScriptClue 等任何剧本杀类型**

### 2.6 可复用前端组件

- **ChatPage.tsx（634 行）**：`export function ChatPage()` 无 props（L267）；内置 **`WerewolfStatePanel`（L150 起，props: {phase, round, players, myRole}）** —— 狼人杀状态侧栏面板，是剧本杀"阶段状态面板"的直接参照模板；L523–528 阶段横幅、L596–600 真人等待输入区、L309–311 按可见性过滤消息（狼人杀模式下）
- **HistoryPanel.tsx（168 行）**：`export function HistoryPanel({ onClose })`（L23）；调用 `api.getHistorySessions / getHistorySessionMessages / loadHistorySession`；**L129 已对 `script_` 前缀会话 id 做显示剥离**（`.replace('roleplay_','').replace('script_','').replace('werewolf_','')`）—— 说明历史系统设计上预期存在 script_ 会话，但剧本杀当前不产生此类会话
- **HomePage / SettingsPage / MaterialPage / LoginPage**：通用页面

---

## ③ 需求文档原文摘录（含行号）

路径：`D:\echoworld\需求文档-完整需求.md`（全文无"剧本杀"专章）

**"剧本杀"全部出现位置（仅 1 处）**
- L477–484（第五章 Level 3 剧情 Track 用途）：
  ```
  477: 狼人杀
  478: 剧本杀
  479: 谍战
  480: 推理
  482: 需要：
  484: Arbiter参与。
  ```

**秘密机制相关原文**
- L336–340（Track Director 管理范围）：
  ```
  336: 信息可见性
  337: 对话关系
  338: 秘密
  339: 监听
  340: 私聊
  ```
- L462–465（Level 2 Track 触发条件）：
  ```
  462: 多人
  463: 信息差
  464: 秘密
  465: 目标冲突
  ```
- L522–524（TrackScore 评分）：
  ```
  522: 秘密任务:
  524: +50
  ```
- L672–674（ISOLATED 定义）：
  ```
  672: #### 3. ISOLATED
  674: 秘密关系。
  ```
- L1410–1418（Track 与私聊结合示例）：
  ```
  1410: A+B秘密聊天：
  1412: Track1:
  1414: A ===== B
  1416: 突然：
  1418: C加入。
  ```

**角色目标相关原文**
- L279（架构图）：`角色目标/事件` 属 World Director 职责
- L289–300（World Director 职责）：
  ```
  289: #### 1. World Director（世界导演）
  293: 角色想做什么
  297: 目标
  298: 任务
  299: 事件
  300: 行为倾向
  ```
- L951（完整运行流程）：`更新角色目标`（玩家输入 → World Director → 更新角色目标 → Interaction Detector → 计算 TrackScore）

**三级社交状态模型（L400–484）**
- L408–421：Level 0 普通交流 → Level 1 空间监听 → Level 2 Track 模式 → **Level 3 复杂剧情 Track**
- L433 `TrackStrategy(mode=DYAD)`；L458–471 Level 2 触发后进入 `MERGED / WEAK / ISOLATED`

**普通监听 vs Track 监听（L542–585）**
- L546–566 普通监听：HearingSystem 负责 距离→声音→摘要，成本接近 0，如"`C听见：A和B正在讨论案件`"
- L568–584 Track 监听：负责长期信息关系，如"`C知道：A和B在调查`，但不知道具体线索"

**需求定位结论**
1. 需求文档对剧本杀**只有一句定位**：属于 Level 3 复杂剧情 Track，需要 Arbiter 参与（L477–484）
2. 搜证、投票、真相判定等剧本杀玩法规格**需求文档未定义** —— 需求侧空白，实现自由度在开发侧
3. 秘密（L338/L464/L674）、角色目标（L279/L293/L951）是文档明确要求的两大机制，当前后端均未实现到剧本杀里

---

## ④ 可复用资产清单（Phase 1–4 已就位，带方法签名）

### 4.1 轨道/信息隔离层（simulation/track/）

| 类 | 位置 | 公开 API | 用途 / 剧本杀复用点 |
|---|---|---|---|
| `Track.Mode` 枚举 | `core/Track.java` L21–25 | `MERGED / WEAK / ISOLATED` | 规范轨道模式，无重复枚举（TrackAssignment 注释 L18–19 明示复用） |
| `TrackAssignment` record | `simulation/track/TrackAssignment.java` L27–46 | 字段 `(String agentId, Track.Mode type, List<String> visibleAgents, String contextNote)`；`static TrackAssignment of(agentId, type, visibleAgents, contextNote)` L38；**`static TrackAssignment isolated(agentId, reason)` L44（完全隔离工厂）** | 秘密发放载体：每个角色一个 ISOLATED 或受限可见 assignment |
| `SpatialTrackResolver` | `simulation/track/SpatialTrackResolver.java` L28–57 | 构造 `()` / `(double conversationDistance)` / `(double, Set<String> privateRoomAgents)`；**`Map<String, TrackAssignment> resolve(List<AgentState> agents)` L57** | 按空间距离产出 TrackAssignment；剧本杀可改为"按秘密关系"产出 |
| `InteractionDetector` | `simulation/track/InteractionDetector.java` L24–107 | 常量 `TRACK_THRESHOLD=40 / SECRET_TASK=50 / TARGET_CONFLICT=40 / BYSTANDER=20 / ABNORMAL_EMOTION=15`；`record TrackScore(...)` L36（`score()` L43 / `triggered()` L47 / `toMap()` L51）；**`TrackScore evaluate(List<AgentState>)` L65 / `evaluate(List<AgentState>, Set<String> secretTaskAgents)` L73**；`shouldUseTrack(...)` L103/L107 | 秘密任务=+50 直接对应剧本杀秘密机制；`secretTaskAgents` 参数天然支持"持秘密角色集合" |
| `EavesdropSummarizer` | `simulation/track/EavesdropSummarizer.java` L25–56 | 构造 `()` / `(LLMClient)`；**`String summarize(List<Map<String,String>> messages)` L46 / `String summarizeLines(List<String> lines)` L56** | 搜证/监听摘要：讨论内容摘要化后注入未在场角色（L568–584 文档要求） |

### 4.2 对话层（simulation/conversation/）

| 类 | 位置 | 公开 API | 用途 / 复用点 |
|---|---|---|---|
| `ConversationMode` 枚举 | `simulation/conversation/ConversationMode.java` L3–8 | `DYAD / GROUP_DISCUSSION / PUBLIC_SPEAKING / DEBATE` | 讨论阶段会话模式 |
| `ConversationManager` | `simulation/conversation/ConversationManager.java` L18–86 | `init(SimulationWorld, LLMClient, Function<String,Agent> agentLookup, Supplier<String> narrationSupplier)` L57；`setTrackDirector(TrackDirectorService)` L48；`setGoalSupplier(Supplier<Map<String,String>>)` L53；`getOrCreateTopicManager(String groupId)` L80；`tick(long now)` L86；`getActiveGroups()` L311；`getStatus()` L324 | 讨论阶段核心引擎：已把 TrackStrategy 绑定到 GROUP_DISCUSSION/DEBATE（L71–75） |
| `ConversationGroup` | `simulation/conversation/ConversationGroup.java` L8–110 | 全量 getter/setter：`getParticipants/getTurnCount/getRoundCount/getTopic/setTopic`、`freeze(name) L80 / unfreeze L81 / isFrozen L82`、`recordTurn(speaker,message) L86`、`getMessageHistory() L100`、`getTrackAssignments()/setTrackAssignments() L71–72`、`getTrackSummary() L76`、`allFrozen() L106`、`idleMs() L110` | 讨论组状态容器，可直接承载剧本杀讨论阶段 |
| `ModeClassifier` | `simulation/conversation/ModeClassifier.java` L9–13 | **`List<GroupCandidate> classify(List<HearingSystem.HearingResult>, ...)` L13**；`record GroupCandidate(List<AgentState> members, ConversationMode mode)` L132 | 从听觉结果聚类出会话组 |
| `TrackStrategy` | `simulation/conversation/TrackStrategy.java` L43–185 | 构造 3 个重载（L59/64/75）；`supportedMode() = GROUP_DISCUSSION` L87；`prepareContext(group, agentContexts)` L92；`processResults(group, agentResponses, llmClient)` L137；`shouldContinue(group)` L185 | 讨论变轨执行器：MERGED=全量历史 / WEAK=只给摘要（类注释 L26–27 写明），**WEAK 分支正是秘密隐藏的实现点** |
| `TopicManager` | `simulation/conversation/TopicManager.java` | 经 `ConversationManager.getOrCreateTopicManager` 获取 | 讨论主题管理 |

### 4.3 导演层（simulation/director/）

| 类 | 位置 | 公开 API | 用途 / 复用点 |
|---|---|---|---|
| `WorldDirectorService` | `simulation/director/WorldDirectorService.java` L36– | 常量 **`GOAL_CALM="平静情绪"` L40 / `GOAL_JOIN_DISCUSSION="参与讨论"` L41 / `GOAL_EXPLORE="探索周围"` L42 / `GOAL_WANDER="闲逛"` L43**；`record AgentGoal(String agentId, String goal, int priority)` L74；`setGoal(agent, goal)` L77；`clearGoal` L84；`getGoal` L90；`getAllGoals` L95；`getGoalDetails` L100；`updateGoals(world, agents)` L104 / `(world, agents, now)` L115；`generateGoalWithLLM(agent, context)` L161 | 角色目标驱动：剧本杀可在 setGoal 注入"查明真相/隐藏秘密"等目标；`generateGoalWithLLM` 可按角色秘密生成个性化目标 |
| `TrackDirectorService` | `simulation/director/TrackDirectorService.java` | 注入 `ConversationManager.setTrackDirector`（ConversationManager L48） | 轨道导演（文档 L328–341 Track Director 职责） |

### 4.4 审批门（approval/）

| 类 | 位置 | 公开 API | 用途 / 复用点 |
|---|---|---|---|
| `ApprovalService` | `approval/ApprovalService.java` L30– | **`RouterService.RoundResult submitForApproval(RoundResult, String sessionId)` L49 / `(result, sessionId, long timeoutSeconds)` L57（阻塞等待，超时自动拒 L72–79）**；`approve(sessionId)` L98；`reject(sessionId, reason)` L116；`getStatus(sessionId)` L134；`getDetailedStatus(sessionId)` L147；`getPendingResult(sessionId)` L165 | DM 人工审批：剧本杀搜证发放/关键判定可走审批门；**注意输入类型是 RouterService.RoundResult（轮结果），剧本杀要用需包一层适配** |

### 4.5 狼人杀后端（对照参照）

- `WerewolfService.java`：L19 `enum Role {WEREWOLF, SEER, WITCH, VILLAGER, HUNTER}`；L20 `enum Phase {NIGHT, DAY_DISCUSS, DAY_VOTE, JUDGMENT, ENDED}`；`initGame(sessionId, players, customRoles)` L83；`recordNightAction` L136；`resolveNight` L179；`castVote` L239；`resolveVote` L250；`startVoting` L302；`hunterShoot` L338；`endGame` L364
- `WerewolfController.java`：`/api/werewolf/init`（query+body 双格式，L25–53）、`/night_action` L55、`/hunter_shoot` L65、`/resolve_night` L75、`/vote` L81、`/resolve_vote` L91、`/start_voting` L97、`/status` L104 —— **与 ScriptController 结构几乎同构**（同样 playerSessions+currentSessionId 模式）

---

## ⑤ 前后端契约对照表（端点 → 前端调用现状）

| 后端端点 | 请求 JSON | 响应 JSON | 前端调用现状 | 断点说明 |
|---|---|---|---|---|
| `POST /api/script/init`（ScriptController L25） | `{"players":[...], "theme":"..."}` | phase/name/background/roles/your_role/round/game_over/winner/clues/locations | **无调用**（genScript 占位） | 前端要写：client.ts `scriptInit` + ScenePage genScript 真实实现 |
| `POST /api/script/search`（L36） | `{"player":"...","location":"..."}` | found/clues/public_clues/location | **无调用** | 前端要写：client.ts `scriptSearch` + 搜证交互 UI |
| `POST /api/script/start_discussion`（L44） | `{"session_id":"..."}` | `{"phase":"discussion"}` | **无调用** | 前端要写：client.ts + 讨论阶段流转 |
| `POST /api/script/start_voting`（L51） | `{"session_id":"..."}` | `{"phase":"vote"}` | **无调用** | 前端要写：client.ts + 投票阶段流转 |
| `POST /api/script/vote`（L58） | `{"player":"...","suspect":"..."}` | `{"result":"..."}` | **无调用** | 前端要写：client.ts + 投票 UI |
| `POST /api/script/resolve`（L67） | `{"session_id":"..."}` | votes/most_voted/vote_count/result/correct?/truth | **无调用** | 前端要写：client.ts + 揭晓 UI |
| `GET /api/script/status?player=`（L73） | — | toMap 或 idle/not_found | **无调用** | 前端要写：client.ts `scriptStatus` + 轮询/刷新 |
| `POST /api/script/generate`（SessionController L164） | `{"theme":"...","characters":[...]}` | ScriptService 的 name/background/roles/clues/truth | **无调用** | 前端要写：client.ts `scriptGenerate`（占位"AI 生成剧本"按钮本应接它，且返回的剧本无 secrets，需后端升级 schema） |

**前端补齐清单（写什么才能对上后端）**
1. `api/client.ts`：新增 `scriptInit / scriptGenerate / scriptSearch / scriptStartDiscussion / scriptStartVoting / scriptVote / scriptResolve / scriptStatus` 8 个方法
2. `ScenePage.tsx`：L227–234 genScript/startScript 从占位改为真实调用（genScript → scriptGenerate 或 scriptInit；startScript → scriptInit+scriptStatus）
3. 新增剧本杀游戏界面组件（搜证面板/线索面板/讨论入口/投票面板/揭晓面板）—— 参照 ChatPage 的 WerewolfStatePanel（L150）模式
4. `appStore.ts`：新增 script 状态字段（phase/players/roles/yourRole/clues/locations/votes 等）+ setter，参照 werewolf 字段（L31–35）模式
5. `App.tsx` SSE handler：新增 script_* 事件分支（当前后端不发 SSE，若后端补推送则前端照抄 werewolf_* 模式 L109–181）
6. `types/index.ts`：新增 `ScriptPhase` 等类型，参照 WerewolfPhase（L57）

---

## ⑥ 剧本杀 vs 狼人杀前端链路对照（差距 = 剧本杀要补什么）

**狼人杀完整链路（已通，5 环节）**
1. 发起：`ScenePage.startWWGame`（L170–188）= enterScene('werewolf_default') → setMode('werewolf') → `POST /api/werewolf/init`（query 带角色配置）→ `werewolfStatus` → 状态栏显示身份
2. 状态：`appStore` werewolf 五字段（L31–35）+ 七组 setter（L85–89/L401–409）
3. API：`client.ts` werewolfInit/werewolfStatus（L95–96）
4. 事件：`App.tsx` SSE 七个 werewolf_* 分支（L109–181）
5. 界面：`ChatPage` WerewolfStatePanel（L150）+ 阶段横幅（L523）+ 真人等待（L596）

**剧本杀现状（5 环节全断）**
| 环节 | 狼人杀 | 剧本杀现状 | 要补什么 |
|---|---|---|---|
| 发起 | startWWGame 真实调用 | genScript/startScript 纯占位（L227–234），scriptJson 无 setter（L35） | 真实 init 调用 + scriptJson setter 解除死代码（L494） |
| 状态 | appStore werewolf 字段 | **零字段** | script phase/players/roles/yourRole/clues/locations 状态 + setter |
| API | werewolfInit/werewolfStatus | **零封装** | client.ts 8 个方法（见⑤） |
| 事件 | SSE werewolf_* 7 分支 | **零分支**；后端也无 script SSE 推送 | 后端发事件 + App.tsx 加分支（P1 级，轮询可先顶） |
| 界面 | WerewolfStatePanel | **无任何游戏界面**（只有设置页 textarea） | 搜证/讨论/投票/揭晓面板，参照 WerewolfStatePanel 结构 |

**可照抄的资产**：WerewolfStatePanel（ChatPage L150–）、阶段横幅（L523–528）、真人等待输入（L596–600）、按可见性过滤消息（L309–311）、HistoryPanel 的 script_ 前缀兼容（L129）。

---

## ⑦ P0 开发技术路径建议（基于事实）

**前置事实约束**
- 后端状态机/7+1 端点已存在且可跑（唯一可玩性缺陷是 secrets 丢弃、truth.contains、无讨论实体）
- 前端链路 5 环节全空
- 需求文档只要求：秘密 + 角色目标 + Level 3 Track（Arbiter 参与）
- 可复用资产全部就位（TrackAssignment.isolated / InteractionDetector.SECRET_TASK / EavesdropSummarizer / ConversationManager+TrackStrategy / WorldDirectorService.setGoal / ApprovalService）

**P0-1 秘密机制（后端，1 天级）**
- `ScriptGameService.ScriptGame` 增加 `Map<String,String> secrets` 字段；`initGame` L113–147 补取 prompt 返回的 secrets 键
- 秘密发放：在 `toMap(playerName)`（L54–65）中把 `your_secret` 加入该玩家视角（对齐狼人杀"身份只进自己 prompt"模式）
- 复用 `TrackAssignment.isolated(agentId, reason)`（L44）为秘密持有者构造 ISOLATED 可见性；`InteractionDetector.evaluate(agents, secretTaskAgents)`（L73）的 `secretTaskAgents` 参数直接传持秘密角色集合

**P0-2 前端主链路打通（前端，1–2 天级）**
- client.ts 加 8 个封装（⑤ 清单）；appStore 加 script 状态（照抄 werewolf L31–35 模式）；types 加 ScriptPhase
- ScenePage L227–234 换真实实现：genScript → `scriptInit`（或先 `scriptGenerate` 预览）；scriptJson 加 setter，激活 L494 死代码区
- 新增剧本杀游戏页：搜证（地点列表 → scriptSearch）、投票（scriptVote）、揭晓（scriptResolve），状态刷新走 scriptStatus；参照 ChatPage WerewolfStatePanel 结构

**P0-3 讨论阶段接对话（后端，1–2 天级）**
- `startDiscussion`（L259–264）从"只改 phase"升级为创建 `ConversationGroup`（L32 构造）+ 走 `ConversationManager.tick`（L86）
- 秘密隐藏由 `TrackStrategy` 的 WEAK 分支天然承担（TrackStrategy L26–27：WEAK = 只给摘要）；`EavesdropSummarizer.summarize`（L46）做搜证摘要
- 目标驱动：`WorldDirectorService.setGoal(agent, GOAL_*)`（L77）按角色秘密/角色注入"查明真相/隐藏秘密"目标

**P0-4 判定与落库修补（后端，0.5–1 天级）**
- `resolveVote` L239 的 `truth.contains` 改为比对真凶实体（剧本生成时输出 `killer` 字段与 secrets 关联）
- L245–246 平票时无条件设 winner 的缺陷修复（平票不设 winner、phase 转 REVEAL 或重投）
- 剧本落库：接入 `DatabaseService.saveScript`（L196）——该能力已存在，只差调用；对局会话可复用 `createGameSession`（L218）
- ENDED 阶段补写入路径（当前终态停在 REVEAL）

**P1 可选（不阻塞可玩）**：`ScriptService` 与 `ScriptGameService` 双生成器合并（统一 schema 含 secrets/killer）；`ApprovalService.submitForApproval`（L49）接搜证/判定审批（需包一层 RoundResult 适配）；SSE 事件推送（script_phase/script_secret 等，照抄 werewolf_* 模式）；角色差异化搜证、行动力限制、复盘演出。

**工程量判断（基于以上事实）**：P0 四项 = 前端链路 + 秘密机制 + 讨论接对话 + 判定/落库修补，合计约 4–6 天单人工作量；完成后即可完整游玩（生成→搜证→讨论→投票→揭晓）。

---

## 自查清单（任务要求逐项核验）

- ✅ 后端三件套每个方法都取证：ScriptGameService 全部 8 个公开方法 + 内部类 + defaultScript（① 1.1）；ScriptService.generateScript（① 1.2）；ScriptController 7 端点签名 + 请求/响应（① 1.3）；额外发现 SessionController `/api/script/generate`（① 1.4）；ScriptEntity/ScriptRepository/DatabaseService 落库取证（① 1.5）
- ✅ 前端剧本杀占位代码位置确认：ScenePage L35（scriptJson 无 setter）、L227–234（genScript/startScript 占位）、L480–503（UI 区块）、L494（死代码）
- ✅ 需求文档摘录带行号：L477–484（剧本杀定位）、L336–340/L462–465/L522–524/L672–674（秘密）、L279/L289–300/L951（角色目标）、L400–484（三级社交状态）
- ✅ 可复用资产都有方法签名：4.1–4.4 全部带类名+行号+签名
- ✅ 前后端契约对照：⑤ 表列全 8 端点与前端"无调用"现状 + 补齐清单
- ✅ 剧本杀 vs 狼人杀链路对照：⑥ 五环节对照表
