# 剧本数据模型 Schema v1（剧本-schema-v1.md）

> **文档定位**：剧本杀剧本 JSON 的**契约定义**（字段表 + 示例 + 兼容规则）。实现：`ScriptSchemaV1`（`src/main/java/com/roleplay/engine/service/ScriptSchemaV1.java`）。决策背景见 `DECISION_LOG.md` D-014；对齐范式见 `tmp/同用剧本杀调研.md` §2.2（Chronos Script Schema v2）。
> **版本**：v1（2026-08-01，批次 C1）

---

## 一、背景

- 问题：剧本 JSON 无版本、字段随意；双生成器（`ScriptService.generateScript` 骨架 vs `ScriptGameService.initGame` 内联）schema 漂移；前端消费靠约定而非契约（蓝图 P2 后备 + D-010 风险 5）。
- 目标：**务实对齐通用剧本杀范式（Chronos Script Schema v2）核心**，不追求全量（timeline DAG / visible_to 表达式 / AP 行动点本项目暂无需求）。
- 原则：**兼容优先**——旧 LLM 输出与既有测试 mock 格式（roles 字符串数组 / clues 带 public / 无 metadata / 无 killer_id）经宽容解析归一为 v1，零破坏。

---

## 二、Schema v1 字段表

| 字段 | 类型 | 必填 | 说明 | Chronos v2 对应 |
|---|---|---|---|---|
| `schema_version` | int | ✅ | 恒为 **1**。版本内嵌 JSON，不建表列（见 D-014） | （协议版本号） |
| `metadata.title` | string | ✅ | 剧本名称 | `metadata.title` |
| `metadata.player_min` | int | ✅ | 最少玩家数 | `metadata.player_limits.min` |
| `metadata.player_max` | int | ✅ | 最多玩家数 | `metadata.player_limits.max` |
| `metadata.tags` | string[] | 可选 | 风格标签（本格/变格…），缺省空数组 | `metadata.tags` |
| `background` | string | 可选 | 背景故事（讨论引擎 worldNarration / persona 用） | （CLAUDE.md 叙事块） |
| `roles[]` | array | ✅ | 角色表（**本项目秘密并入角色**） | `roles[]` |
| `roles[].id` | string | ✅ | 角色唯一 id，形如 `role_1`（宽容解析时自动递增生成） | `roles[].id` |
| `roles[].name` | string | ✅ | 角色名（搜证/投票/判定均以角色名消费） | `roles[].name` |
| `roles[].intro` | string | 可选 | 角色介绍，缺省空串 | `roles[].role_intro` |
| `roles[].is_hidden` | bool | 可选 | 是否隐藏角色，缺省 false | `roles[].is_hidden` |
| `roles[].secret` | string | 可选 | **该角色不可告人的秘密**（D5 按角色发放的权威来源） | （Chronos 无，本项目扩展） |
| `roles[].talkativeness` | double | 可选 | **人格化健谈度（批次 D）**：发言门控概率输入 [0,1]，侦探/外向角色给 0.6-0.9、内向寡言角色给 0.2-0.4，**缺省 0.5 中性**；也兼容嵌套写法 `roles[].personality.talkativeness`；被点名/被提问/轮次首句等规则触发时强制发言不受其限制 | （Chronos 无，本项目扩展，见 DECISION_LOG D-022） |
| `roles[].ap_bonus` | int | 可选 | **行动点加成（C2）**：该角色初始 AP 额外加成（侦探类角色给 1-2，行动点多，蓝图 P2 角色差异化搜证），缺省 0 | （Chronos 无，本项目扩展） |
| `clues[]` | array | ✅ | 线索表（缺省兜底 3 条） | `clues[]` |
| `clues[].id` | string | ✅ | 线索 id，形如 `clue_1` | `clues[].clue_id` |
| `clues[].title` | string | 可选 | 线索名（缺省取 content 前 20 字） | `clues[].title` |
| `clues[].location` | string | ✅ | 所属地点（搜证按 location 匹配） | `clues[].location` |
| `clues[].content` | string | ✅ | 线索内容 | `clues[].content_blocks`（裁剪） |
| `clues[].transferable` | bool | 可选 | 可否转交，缺省 false（当前搜证流程未用，预留） | `clues[].transferable` |
| `clues[].visible_to_owner_only` | bool | 可选 | 是否仅持有者可见，缺省 false | `clues[].visible_to_owner_only` |
| `clues[].ap_cost` | int | 可选 | **行动点消耗（C2）**：搜索该线索消耗的 AP，缺省 **1**（搜证按“该地点全部未持有可搜线索 ap_cost 之和”扣 AP，AP 不足整次拒绝） | `CLUE_SEARCH.ap_cost` |
| `clues[].public` | bool | 派生 | **兼容派生键**：`!visible_to_owner_only`，旧消费方（search/toMap）读取 | （旧格式字段） |
| `clues[].related_role` | string | 派生 | 兼容键：关联角色（旧格式透传） | （旧格式字段） |
| `killer_id` | string | 可选 | **凶手角色 id**（D-010：判定实体化，与 truth 文案分离；运行时判定仍走 D6 truth 解析，此字段为元数据/落库） | （Chronos 无，D-010 决策） |
| `truth` | string | ✅ | 真相叙述（50-80 字，须明示"凶手是X"） | `REVEAL.content_blocks`（裁剪） |
| `locations[]` | string[] | ✅ | 可搜证地点（缺省 5 个默认地点） | （Chronos 由 CLUE_SEARCH 节点承担） |
| `secrets` | map | ✅ | **兼容层**：角色名 → 秘密（D5 机制保留，与 `roles[].secret` 冗余一致；LLM 未给任何秘密时兜底保证键集合==roles，A1-3） | （本项目扩展） |

> **宽容解析规则**（`ScriptSchemaV1.normalize`，旧→新映射）：
> - `name` → `metadata.title`（v1 输出同时保留 `name` 键兼容）
> - `roles: ["管家"]` → `roles[]` 对象（id 递增、intro/is_hidden/secret/ap_bonus/talkativeness 缺省）
> - **`talkativeness` 缺省 0.5**（批次 D：旧剧本/测试 mock 无此字段时发言门控按中性健谈度，向后兼容；`roles[].personality.talkativeness` 嵌套写法归一为顶层 `talkativeness`）
> - `clues[].public=true` → `visible_to_owner_only=false`（反向亦然），`public`/`related_role` 派生保留
> - **`ap_cost` 缺省 1、`ap_bonus` 缺省 0**（C2：旧剧本无此字段时搜证按 1 扣 AP、角色无加成，向后兼容）
> - `killer`（角色名）→ 反查 `roles[].id` 得 `killer_id`
> - roles/clues/locations 缺失 → 按玩家兜底角色 / 默认 3 线索 / 默认 5 地点
> - secrets：`raw.secrets` ∪ `roles[].secret`；**仅当所有角色均无秘密时全量兜底**（部分秘密保持部分，不臆造——保证 A3-2/A3-4 未持秘密角色语义）

---

## 三、示例 JSON（v1 规范输出）

```json
{
  "schema_version": 1,
  "metadata": {
    "title": "庄园疑云",
    "player_min": 3,
    "player_max": 5,
    "tags": ["本格推理"]
  },
  "name": "庄园疑云",
  "background": "风雨交加的夜晚，庄园主人倒在血泊中，在场宾客皆有嫌疑……",
  "roles": [
    {"id": "role_1", "name": "管家", "intro": "服侍庄园三代人", "is_hidden": false, "secret": "你贪图遗产，案发当晚去过书房", "ap_bonus": 0, "talkativeness": 0.4},
    {"id": "role_2", "name": "女仆", "intro": "新来的女仆，沉默寡言", "is_hidden": false, "secret": "你知道主人有私生子", "ap_bonus": 0, "talkativeness": 0.3},
    {"id": "role_3", "name": "侦探", "intro": "退休警探，受邀查案", "is_hidden": false, "secret": "你隐瞒了案发时在场的事实", "ap_bonus": 2, "talkativeness": 0.8}
  ],
  "locations": ["客厅", "书房", "花园", "厨房", "地下室"],
  "clues": [
    {"id": "clue_1", "title": "碎玻璃", "location": "客厅", "content": "地毯上的碎玻璃与酒柜缺口吻合", "transferable": false, "visible_to_owner_only": false, "public": true, "ap_cost": 1},
    {"id": "clue_2", "title": "威胁信", "location": "书房", "content": "桌上有一封威胁信，落款是园丁", "transferable": true, "visible_to_owner_only": true, "public": false, "related_role": "园丁", "ap_cost": 2}
  ],
  "secrets": {
    "管家": "你贪图遗产，案发当晚去过书房",
    "女仆": "你知道主人有私生子",
    "园丁": "你曾与主人争吵并扬言报复"
  },
  "killer_id": "role_1",
  "truth": "凶手是管家，因为管家贪图遗产，用酒瓶砸死了主人。"
}
```

---

## 四、消费与落库

| 消费方 | 读取点 | 状态 |
|---|---|---|
| `ScriptGameService.initGame` | `ScriptSchemaV1.title/background/truth/killerId/roleNames/locations/clueList/secretsByRole` 装载 `game` 字段 | ✅ 批次 C1 |
| `ScriptService.generateScript`（`POST /api/session/script/generate`） | 唯一生成路径，输出 v1 | ✅ 批次 C1 |
| `ScriptGameService.persistScript`（落库 type=script） | `contentJson` 按 v1 存取：`schema_version`/`metadata`/`roles[]`/`clues[]`/`killer_id`/`secrets` + `assignments`/`players` | ✅ 批次 C1 |
| `ScriptEntity` | **不加 schema_version 列**：版本内嵌 `contentJson`（D-014：避免 H2 迁移，schema 版本化即 JSON 版本化） | ✅ 批次 C1 |
| 运行时判定（D6 resolveMurderer） | 仍从 `truth` 精确解析凶手玩家名（与 killer_id 解耦，见 D-010） | 不动 |
| SSE / toMap | `schema_version` 附加进状态推送（前端可见版本） | ✅ 批次 C1 |

---

## 五、与 Chronos v2 差异（刻意裁剪）

| Chronos v2 | 本项目 v1 | 原因 |
|---|---|---|
| `timeline` DAG（entry_node + nodes + advance_condition） | 无，流程为六态状态机（SETUP→INVESTIGATION→DISCUSSION→VOTE→REVEAL→ENDED） | 线性流程 + 状态机已满足需求；DAG 节点化留 P2 |
| `visible_to` 表达式（roles/others/if_clue） | 收敛为 `clues[].visible_to_owner_only` + 服务端按玩家过滤 toMap | 本项目无多节点内容块 |
| `ap_cost` / CLUE_SEARCH 节点 | ✅ **已支持（批次 C2）**：`clues[].ap_cost`（缺省 1）+ 搜证扣 AP（基础值 `roleplay.game.ap.base` 默认 3 + 角色 `ap_bonus`）；AP 不足整次拒绝 | 行动力限制（蓝图 P2 后备）已落地，见 DECISION_LOG D-016 |
| `roles[].talkativeness` | ✅ **已支持（批次 D）**：`roles[].talkativeness`（缺省 0.5，兼容 `personality.talkativeness` 嵌套）→ `ScriptGameService` 按角色名装载 `playerTalkativeness`，作为发言门控 `P = 动机分 × talkativeness` 的概率输入（被点名等规则触发不受限） | 人格化发言概率（P0-1 发言门控）已落地，见 DECISION_LOG D-022 |
| `assets.bgm/images` | 无 | 前端无此消费 |
| `transferable` | ✅ **已启用（批次 C2）**：`clues[].transferable=true` 的线索可经 `POST /api/script/transfer_clue` 转交（仅持有者可转交，转交后 ownership 变更，接收方 status/my_clues 可见） | 线索转交（蓝图 P2 后备）已落地，见 DECISION_LOG D-016 |
