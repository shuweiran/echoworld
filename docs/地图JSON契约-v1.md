# 地图 JSON 契约 v1（定稿）— LLM 生成地图与 Phaser 渲染的对接面

> **状态**：✅ **已定稿 v1**（2026-08-01 未衡审核通过，由 `地图JSON契约-draft.md` 更名升级；字段表冻结，阶段 2 按本契约落地）
> **v0.2 扩展**（2026-08-14，P-0814-F）：新增可选扩展键（layers.objects/overlay + tileProps/decor/spawnMarkers/warps），map_version 保持 1（扩展兼容，v1 数据零破坏）——见 §7
> **对应**：`docs/Phaser迁移计划.md` 阶段 0 验证点 ⑤ / 阶段 2 对接面；DECISION_LOG **D-020**
> **版本纪律**：对齐剧本 schema v1（D-014）——**JSON 内嵌版本号**、**宽容解析归一**、**不扩展表结构**（阶段 2 落库时沿用）
> **demo 实现**：`src/main/resources/static/simulation/phaser_validate/`（样例 `maps/manor.json`、`maps/bsp-sample.json`；渲染管线 `js/common.js`；校验器 `js/bsp.js validateMap`）

---

## 1. 一句话

LLM（或 BSP 备选生成器）输出一份地图 JSON，前端 Phaser 按本契约渲染：瓦片层画底、碰撞层做物理、房间/走廊做语义标注、热点做交互、出生点放实体。**后端零改动**（结构性前提：后端 Java 权威模拟 + 前端纯渲染）。

## 2. 字段表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `map_version` | int | ✅ | 内嵌版本号（本草案 = 1）。宽容解析：缺失按 1 处理（D-014 纪律） |
| `map_id` | string | 建议 | 地图唯一标识（LLM 生成批次/种子可作后缀） |
| `name` / `theme` | string | 可选 | 地图名/主题描述（LLM 可自由发挥的叙事字段，前端展示用） |
| `width` / `height` | int | ✅ | 地图尺寸（**格数**，非像素）；行数=height、列数=width |
| `tile_size` | int | 可选 | 瓦片像素边长（缺省 32） |
| `tileset` | object | 可选 | `{src, first_gid, tile_count}` 素材引用；demo 固定 `assets/tiles.png`（5 格：1=木地板 2=墙 3=草地 4=地毯 5=石板） |
| `layers.ground` | int[][] | ✅ | **瓦片层**：瓦片 id 二维数组（height 行 × width 列），渲染可见 |
| `layers.collision` | int[][] | ✅ | **碰撞层**：`1`=不可通行、`0`=可通行；**显式给出，不依赖瓦片 id 推断**（同一瓦片 id 在不同地图可通行性不同） |
| `rooms[]` | array | 可选 | **房间区域**：`{id, name, x, y, w, h, tags?}`（x/y 为左上角格坐标）；阶段 2 可与剧本杀 `locations[]` 对应 |
| `corridors[]` | array | 可选 | **走廊**：`{id, from, to, points[]}`，points 为四邻接连通路径 `[[x,y],...]` |
| `zones[]` | array | 可选 | **热点列表**：`{id, name, type, x, y, radius, clue_location?, prompt?}`；`type` ∈ `search`(搜证点) / `door`(房间入口) / `broadcast`(广播位)；阶段 2 以 `clue_location` 绑定剧本杀 `clues[].location` |
| `spawn_points[]` | array | 可选 | **实体出生点**：`{id, type(player/npc), x, y}` |
| `generator` | object | 可选 | 生成器元信息（BSP：`{kind:'bsp', seed, leaf_count, note}`；LLM 路径可记 `{kind:'llm', model, prompt_version}`，便于溯源） |

### 2.1 示例（manor_01 老宅，节选）

```json
{
  "map_version": 1,
  "map_id": "manor_01",
  "name": "老宅",
  "theme": "剧本杀·民国老宅（契约样例）",
  "tile_size": 32,
  "width": 20,
  "height": 14,
  "tileset": { "src": "assets/tiles.png", "first_gid": 1, "tile_count": 5 },
  "layers": {
    "ground": [
      [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2],
      [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2],
      [2, 2, 1, 1, 1, 1, 1, 1, 2, 5, 5, 2, 1, 1, 1, 1, 1, 1, 2, 2],
      [2, 2, 1, 1, 1, 1, 1, 1, 5, 5, 5, 5, 1, 1, 1, 1, 1, 1, 2, 2],
      [2, 2, 1, 1, 1, 1, 4, 1, 2, 5, 5, 2, 1, 1, 1, 1, 1, 1, 2, 2],
      [2, 2, 1, 1, 1, 1, 1, 1, 2, 5, 5, 2, 1, 1, 1, 1, 1, 1, 2, 2],
      [2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 5, 2, 2, 2, 2, 2, 2, 2, 2, 2],
      [2, 2, 2, 2, 2, 2, 2, 2, 2, 5, 5, 2, 2, 2, 2, 2, 2, 2, 2, 2],
      [2, 2, 1, 1, 1, 1, 1, 1, 2, 5, 5, 2, 3, 3, 3, 3, 3, 3, 2, 2],
      [2, 2, 1, 1, 1, 1, 1, 1, 5, 5, 5, 5, 3, 3, 3, 3, 3, 3, 2, 2],
      [2, 2, 1, 1, 1, 1, 1, 1, 2, 5, 5, 2, 3, 3, 3, 4, 3, 3, 2, 2],
      [2, 2, 1, 1, 1, 1, 1, 1, 2, 5, 5, 2, 3, 3, 3, 3, 3, 3, 2, 2],
      [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2],
      [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2]
    ],
    "collision": [
      [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1],
      [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1],
      [1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1],
      [1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1],
      [1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1],
      [1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1],
      [1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1],
      [1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1],
      [1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1],
      [1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1],
      [1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1],
      [1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1],
      [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1],
      [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
    ]
  },
  "rooms": [
    { "id": "living_room", "name": "客厅", "x": 2, "y": 2, "w": 6, "h": 4, "tags": ["searchable"] },
    { "id": "study", "name": "书房", "x": 12, "y": 2, "w": 6, "h": 4, "tags": ["searchable"] },
    { "id": "bedroom", "name": "卧室", "x": 2, "y": 8, "w": 6, "h": 4, "tags": ["searchable"] },
    { "id": "garden", "name": "花园", "x": 12, "y": 8, "w": 6, "h": 4, "tags": ["searchable"] }
  ],
  "corridors": [
    { "id": "cor_main", "from": "living_room", "to": "study",
      "points": [[9,2],[9,3],[9,4],[9,5],[9,6],[9,7],[9,8],[9,9],[9,10],[9,11],
                 [10,11],[10,10],[10,9],[10,8],[10,7],[10,6],[10,5],[10,4],[10,3],[10,2]] }
  ],
  "zones": [
    { "id": "z_living_table", "name": "客厅八仙桌", "type": "search", "x": 4, "y": 3, "radius": 1,
      "clue_location": "客厅", "prompt": "八仙桌上摊着一封没有署名的信，字迹潦草……" },
    { "id": "z_study_bookshelf", "name": "书房书架", "type": "search", "x": 13, "y": 3, "radius": 1,
      "clue_location": "书房", "prompt": "书架第三层的《福尔摩斯探案集》里夹着一张泛黄的照片。" }
  ],
  "spawn_points": [
    { "id": "sp_player", "type": "player", "x": 9, "y": 4 },
    { "id": "sp_npc_1", "type": "npc", "x": 4, "y": 3 }
  ]
}
```

> 完整样例：`maps/manor.json`（手写）与 `maps/bsp-sample.json`（BSP 生成，seed=20260801）。demo 页签 ⑤ 可在线切换查看并运行校验器。

## 3. 宽容解析规则（对齐 D-014 版本纪律）

| 情形 | 处理 |
|---|---|
| `map_version` 缺失 | 按 1 处理（内嵌版本=JSON 版本，不依赖表结构列） |
| 未来版本（>1） | 尽力按已知字段解析 + 告警；关键字段结构不匹配时拒绝渲染并报校验错误 |
| `tile_size` 缺失/非法 | 按 32 |
| `layers.collision` 缺失 | 报校验错误（碰撞语义必须显式，不推断） |
| 瓦片 id 超出 tileset 范围 | 渲染按 id 直取（超出部分显示空白）+ 校验警告（可能是 LLM 自定义装饰瓦片） |
| `rooms` / `corridors` / `zones` / `spawn_points` 缺失 | 视为空数组（纯地形地图合法） |
| zone `radius` 缺失 | 按 1 |
| 冲突字段（同名 id 等） | 校验警告；渲染取先出现者 |

## 4. 校验器（BSP 校验器 = 无 LLM 备选 / LLM 输出防线）

`js/bsp.js` 的 `validateMap(map)` 对任意契约 JSON 输出 `{ok, errors[], warnings[]}`，检查项：

1. `map_version` 类型、`width/height` 正整数、`tile_size` 合法性
2. `layers.ground` / `layers.collision` 为 `height×width` 二维数组、碰撞值 ∈ {0,1}
3. 瓦片 id 范围（越界→警告）
4. 房间越界（错误）、房间重叠（警告）
5. 走廊点越界（错误）、相邻点非四邻接（警告）
6. **热点落在不可通行格（碰撞=1）→ 错误**（搜证点不能埋在墙里）
7. **出生点落在不可通行格 → 错误**

demo 页签 ⑤ 提供粘贴校验；阶段 2 将该校验器作为 LLM 生成地图 JSON 的后置防线（生成 → 校验 → 不过则重试或兜底，对齐 D-014 的 normalize+defaultScript 模式）。

## 5. 阶段 2 绑定关系（草案预留）

| 地图契约字段 | 剧本杀 Schema v1（`docs/剧本-schema-v1.md`） | 绑定方式（阶段 2 落地） |
|---|---|---|
| `zones[].clue_location` | `clues[].location` | 搜证热点 ↔ 线索地点：热点命中 → 搜证该地点线索（AP 扣减/转交逻辑不动） |
| `zones[].id` | （新）地图热点与线索的关联键 | 建议 LLM 生成时保证 `clue_location` 字符串与 `locations[]`/`clues[].location` 一致；不一致时宽容映射（trim + 同义词表） |
| `rooms[].name` | `locations[]` | 房间即剧本地点，供 DM 面板/讨论上下文引用 |
| `spawn_points[]` | 剧本角色列表 | 玩家/角色出生位置（角色数 > 出生点数时循环分配） |

## 6. 版本化路径（已走完，阶段 2 直接沿用）

- ✅ 草案 v1 经未衡审查通过（2026-08-01）→ **定稿 v1**（字段表冻结）
- ✅ 契约文档更名 `docs/地图JSON契约-v1.md`（原 `-draft.md` 已废弃并指向本文件，对齐 `docs/剧本-schema-v1.md` 命名与纪律）
- 地图 JSON 落库（若需持久化）：内嵌 `map_version` 进 contentJson，**不扩展表结构**（D-013/D-014 纪律）
- LLM 生成统一路径 + 宽容归一 + 兜底（对齐 D-014 双生成器统一模式，阶段 2 落地）

---

## 7. v0.2 扩展键（2026-08-14，P-0814-F，依据《调研-星露谷地图数据物品交互-20260813》M1/M2/M3）

> **定位**：v0.2 = **可选扩展**（增强不是破坏性变更）。`map_version` **保持 1 不变**——旧前端按 v1 渲染自然忽略新键，旧 v1 数据零破坏；新前端可消费新键增强表现。所有新键**缺失一律兜底为空**（normalize 保证），校验器对缺失键不校验。瓦片 id 仍只允许 1-5（tiles.png 只有 5 格，禁止引入新瓦片 id，渲染会花屏）；装饰用**字符串类型键**表达。
>
> 设计出处：星露谷「静态瓦片层（外观+属性）↔ 运行时实体（交互/状态）↔ 生成器指示（Paths 索引）三层分离」——解决既有 2D 地图「不美观/内容少」根因（外观/交互/生成全挤在 ground+布尔碰撞网格）。

### 7.1 新增字段表（全部可选）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `layers.objects` | string[][]（可 null 元素） | 可选 | **Front 层**静态装饰类型名二维数组，与 ground 同尺寸；如 `"tree_oak"`/`"fence"`/`"flower_bed"`/`"pillar"`/`"bench"`/`"lamp"`；渲染层使用，无碰撞语义 |
| `layers.overlay` | string[][]（可 null 元素） | 可选 | **AlwaysFront 前景遮罩**二维数组，与 ground 同尺寸；如 `"canopy"`（树冠永远盖住角色） |
| `tileProps` | Map&lt;String,Object&gt; | 可选 | **每格属性字典**：键为 `"x,y"` 字符串，值为属性字典（`blocked`/`water`/`action`/`args` 等，**宽容解析不做白名单**）——星露谷「瓦片挂字符串属性」范式（M3），搜证点类交互可挂 `action` |
| `decor` | List&lt;Map&gt; | 可选 | **显式装饰/交互物**：`{id, type, tile:[x,y], state?{...}, onInteract?{...}, once?, radius?}`；id 全局唯一、type 为简单英文标识符、tile 不能落在墙格（ground=2） |
| `spawnMarkers` | Map&lt;String,List&lt;List&lt;Integer&gt;&gt;&gt; | 可选 | **生成器指示**：键=类别名，值=坐标数组；如 `{"grass":[[2,2],[3,2]], "debris":[[30,40]]}`——LLM 低成本铺装饰（M2），运行时按标记批量生成实体 |
| `warps` | List&lt;Map&gt; | 可选 | **传送点**：`{from:[x,y], to:[mapId字符串,x,y]}`；场景切换数据表（M3/B4），本批生成器不产出（场景切换走既有 door zone 编排），契约支持 + 校验 |
| `exits` | List&lt;Map&gt; | 可选 | **房间出口表（P-0817-G 房间模式）**：`{id, from, to, side?, door:[x,y]}`——from/to 为房间 id、door 为门洞格坐标（墙环上可通行格）、side 为方位（top/bottom/left/right）；前端「一屏一房间」走门切换的数据源，由生成器/服务端确定性推导（`MapExits.deriveExits`：房间墙环可通行格 → BFS 邻房），不让 LLM 手出坐标；缺失兜底为空（整图模式零影响） |
| `structure` | Map | 可选 | **结构树（P-0817-L 大型结构生成）**：`{version, kind, name?, seed, root 节点树, relations[]}`——语义层元数据（哪些部分组成/什么关系），几何仍以 rooms/exits/warps 为权威；缺失 = 普通地图零破坏（MapContract 透传，校验走 `StructureValidator`，6 项校验 + 多图 warps 反向检查）；详见 `docs/结构树契约与生成API设计.md` |

### 7.2 校验规则（新增 8-11 项，既有 1-7 项零变化）

| 检查项 | 规则 |
|---|---|
| 8. `tileProps` | 每个 `"x,y"` 键可解析为整数且 `0≤x<width`、`0≤y<height`；值必须为对象字典；键格式非法/越界/值非对象 → 错误 |
| 9. `decor` | id 非空且全局唯一；tile 为 `[x,y]` 整数对且不越界；type 非空字符串；**tile 落在 ground=2（墙）格 → 错误**（装饰不能嵌墙） |
| 10. `spawnMarkers` | 每类标记坐标列表为 `[x,y]` 整数对；越界 → 错误 |
| 11. `warps` | from 为 `[x,y]` 整数对且不越界；to 为 `[mapId字符串, x, y]` 且 x/y 整数 |
| 12. `exits`（P-0817-G） | from/to 必须是已知房间 id（否则错误）；door 为 `[x,y]` 整数对、不越界、**落在可通行格**且**在 from 房间墙环上**（不在环上 → 警告）；缺反向出口（A→B 无 B→A）→ 警告（切回可能不可达）；两侧门洞不对齐（距 >2）→ 警告 |
| 13. `tileProps.blocked`（P-0817-O） | **挡路家具一致性**：声明 `blocked=true` 的格必须 `collision=1`（否则错误）——挡路声明与碰撞层必须一致；非 blocked 键/旧地图零影响 |

**v0.2 新键缺失 = 不校验 = 通过**（保持 v1 语义）；`layers.objects/overlay` 不校验尺寸（宽容透传，渲染层尽力而为）。
**P-0817-L 扩展**：`structure` 为地图可选键（缺失 = 普通地图，宽容解析零破坏）；生成入口 `POST /api/structure/generate`（castle/mansion/city_block/dungeon 四模板，单图优先，超单图预算按 zone 自动拆多图 + warps 双向连接）。

### 7.3 完整示例（v0.2 增强版，基于 §2.1 manor_01 老宅）

```json
{
  "map_version": 1,
  "map_id": "manor_01",
  "name": "老宅",
  "theme": "剧本杀·民国老宅（契约样例）",
  "tile_size": 32,
  "width": 20,
  "height": 14,
  "tileset": { "src": "assets/tiles.png", "first_gid": 1, "tile_count": 5 },
  "layers": {
    "ground": [
      [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2],
      [2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2],
      [2, 2, 1, 1, 1, 1, 1, 1, 2, 5, 5, 2, 1, 1, 1, 1, 1, 1, 2, 2]
    ],
    "collision": [
      [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1],
      [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1],
      [1, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 1]
    ],
    "objects": [
      [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null],
      [null, "tree_oak", null, null, null, null, "flower_bed", null, null, null, null, null, null, null, null, "bench", null, null, null, null]
    ],
    "overlay": [
      [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null],
      [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null]
    ]
  },
  "rooms": [
    { "id": "living_room", "name": "客厅", "x": 2, "y": 2, "w": 6, "h": 4, "tags": ["searchable"] }
  ],
  "corridors": [
    { "id": "cor_main", "from": "living_room", "to": "study",
      "points": [[9,2],[9,3],[9,4],[9,5],[9,6],[9,7],[9,8],[9,9],[9,10],[9,11]] }
  ],
  "zones": [
    { "id": "z_living_table", "name": "客厅八仙桌", "type": "search", "x": 4, "y": 3, "radius": 1,
      "clue_location": "客厅", "prompt": "八仙桌上摊着一封没有署名的信，字迹潦草……" }
  ],
  "spawn_points": [
    { "id": "sp_player", "type": "player", "x": 9, "y": 4 },
    { "id": "sp_npc_1", "type": "npc", "x": 4, "y": 3 }
  ],
  "tileProps": {
    "4,3": { "action": "examine", "args": "wall_painting" },
    "5,9": { "water": true }
  },
  "decor": [
    { "id": "decor_bench_1", "type": "bench", "tile": [3, 2] },
    { "id": "decor_lamp_1", "type": "lamp", "tile": [17, 2], "once": false }
  ],
  "spawnMarkers": {
    "grass": [[2, 2], [3, 2], [6, 4]],
    "debris": [[15, 10]]
  },
  "warps": [
    { "from": [19, 6], "to": ["town", 10, 30] }
  ],
  "generator": { "kind": "llm", "prompt_version": 1 }
}
```

> 注：示例中 ground/collision/rooms 为节选（完整为 14 行 × 20 列）；objects/overlay 按 ground 同尺寸给出稀疏装饰。

### 7.4 BSP 生成器 v0.2 输出（P-0814-F）

- **房间地板多样性**：floor(1)/carpet(4)/stone(5) 按房间序号取模交替铺装（确定性，不再概率地毯）；走廊 stone(5)；围合外恒为草地(3)（BSP 房间开放连通无门，门口点缀语义不适用）。
- **spawnMarkers**：grass 按 ~2%（`GRASS_MARKER_DENSITY`，保底 1 个）/ debris 按 ~0.5%（`DEBRIS_MARKER_DENSITY`，小图可为 0）密度，种子确定性无放回抽样；候选格=可通行且非走廊（避开墙/走廊交叉口）。
- **decor**：每房间 1-2 个静态装饰（type 从 `pillar`/`flower_bed`/`bench`/`lamp` 按房间序号确定性选取），落在房间内可通行非墙非 spawn 非热点非标记格；无 blocked 语义（渲染层使用）。
- **warps**：空数组（契约支持，本批不产出；场景切换走既有 door zone 编排）。
- 密度为硬编码常量（评估：生成器为确定性静态工具，配置键收益低，未加 `roleplay.game.map.decor-*` 配置键；如需可调后续追加）。

### 7.5 LLM prompt 指导（ScriptMapService.buildPrompt）

- 新增「可选增强键」段：允许输出 objects/overlay/tileProps/decor/spawnMarkers（给出紧凑示例）；明确「全部可省略——不输出任何增强键也完全合法」。
- 约束：所有坐标 `0≤x<width`、`0≤y<height`；瓦片 id 只允许 1-5；decor 的 type 用简单英文标识符；objects/overlay/decor.type 是字符串类型名不是瓦片 id。
- 校验失败/LLM 失败/超预算 → BSP 兜底逻辑不变；响应溯源键（mode/generator/validation/fallback）保持。

### 7.6 decor 交互字段语义（P-0814-H 热点/搜证点交互系统，2026-08-15）

> 交互链：前端点击/靠近 → `POST /api/script/interact`（ScriptController）→ ScriptGameService.interact → **MapInteractService**（纯逻辑分发器）。半径判定/优先级/幂等/条件门全部在 MapInteractService（单测直测），对局层只做状态落地 + 快照持久化。

**7.6.1 交互目标与优先级链**（body 至少传 `decor_id` 或 `tile` 其一）：

| 优先级 | 目标 | 解析方式 |
|---|---|---|
| 1 | `decor_id` 显式指定 | decor 列表按 id 查找；不存在 → error「decor 不存在」 |
| 2 | `tile` 坐标 → decor 实体 | tileProps 前先查该格 decor（同格 decor 实体 > 瓦片动作） |
| 3 | `tile` 坐标 → tileProps.action | tileProps["x,y"] 有 `action` 键 → 查分发表 |
| 4 | `tile` 坐标 → 环境占位 | 无 decor 无 action → `这里没有什么特别的。` |

**7.6.2 半径判定**（Chebyshev，对齐星露谷 `tileWithinRadiusOfPlayer` radius=1 范式）：

- 玩家瓦片坐标 `x`/`y` 由客户端上报（**可选**）；缺省 → 跳过靠近校验（尽力而为，对齐 switchMap 语义）。
- 判定：`|px - tx| ≤ r 且 |py - ty| ≤ r`，其中 `r = decor.radius`（≥1）覆盖，缺省 **1**。
- 超半径 → `handled=false` + error「够不着：距离超过交互半径 …」。

**7.6.3 `decor.onInteract` 动作分发表**（数据驱动；单次交互可叠加多个动作；`onInteract` 可为单 Map 或 List<Map> 按序执行）：

| 动作键 | 载荷 | 效果 |
|---|---|---|
| `dialog` | string \| string[] | 返回文本（响应 `dialog` 数组，前端对话框/结果卡展示） |
| `addItem` | string（线索 id）\| Map{id, title?, content?, ...} | 授予玩家线索（进 `playerClues` 既有机制）；未知线索 id 且无 title/content 数据 → **不授予不报错** |
| `flag` | string（标记名）\| Map{name, flag?} | 写一次性标记（`decorFlags`，对齐 searchedLocations 幂等范式；`conditions.requireFlag` 读取） |
| `state` | Map{key: value} | 实例状态字段变更（与 decor.state 初始值 + 运行时覆盖合并，进 `decorStates` 随快照落库）；仅 decor 实体支持 |
| `sound` | string \| Map{name, sound?} | 占位返回（响应 `sounds`，前端可播） |
| `anim` | string \| Map{name, anim?} | 占位返回（响应 `anims`） |
| `menu` | string \| Map{type, hint?} | 占位返回（响应 `menu`，后续扩展交互面板） |
| 未知键 | — | 忽略 + warning（不崩） |

**7.6.4 `once` 幂等**：`decor.once=true` → 首次交互后标记 `processed`（decorStates["mapId|decorId"].processed=true）；重复交互返回「该处已处理过」且不重复执行（对齐搜证「已搜证过」幂等风格）。非 once decor 可重复交互。

**7.6.5 `conditions` 门**：`{requireFlag: "<flag>", failDialog?: "文案"}` —— requireFlag 未写入时拦截，返回 `blocked=true` + failDialog 文案（缺省「条件未满足，无法交互」），**零动作执行**；flag 写入后放行。

**7.6.6 三层持久化**（对齐 I3）：① 热点实例状态 `decorStates`（键 `"mapId|decorId"`）② 一次性 flag `decorFlags` ③ 玩家持有 `playerClues` —— 全部随对局快照落库（ScriptGameService.saveSnapshot），重启/重连 restoreFromSnapshot 恢复；前端对局状态 `decor_states`/`decor_flags` 附加键暴露（旧对局无此键 → 空，零影响）。

**7.6.7 端点契约**：`POST /api/script/interact`，body `{session_id?, player, player_key?, map_id?, decor_id?, tile?, x?, y?}`。响应：`{ok, map_id, player?, tile:[x,y], decor_id?, handled, processed?, blocked?, dialog?, items?[{id,title}], flags?, sounds?, anims?, menu?, state?, warnings?, result, error?}`。`player_key` 非法 → 403（C3 身份认证）；玩家不在局/地图未生成/缺 player → error。

