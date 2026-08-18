# 结构树契约与生成 API 设计 — 大型结构分层生成（方案）

> 状态：✅ 设计定稿（2026-08-17，P-0817-H，纯文档零代码）；**代码落地：P1-P4 已完成（P-0817-L 契约+模板+L1 布局+API+拆图 warps；P-0817-M LLM 蓝图 L0 + 前端生成入口），L2 房间内容深化已落地（P-0817-N：模板配方库/家具/地面图案/搜证锚点 + 前端家具色块），详见 修改记录.md #254/#255/#256**
> 定位：大型结构（城堡/庄园/城市街区/地牢/飞船等）的**通用生成方法**落地设计——分层生成（结构树），小镇只是其中一个用例。
> 前置：地图 JSON 契约 v1（v0.2 扩展键 exits/warps/decor 等）、MapExits（P-0817-G）、大图支持（P-0817-D）、多图注册表（P-0803-K）、人物固定比例尺（P-0817-F）、模板原型（templates-proto.json 10 模板/28 家具）

---

## 一、目标与范围

**目标**：提供一个可复现、可校验、可扩展的大型结构生成能力：

1. 输入主题/类型/种子/规模 → 输出**结构树 + 一张或多张契约 v1 地图 + 连接表**
2. 结构树 = 语义层（哪些部分组成、什么关系）；地图 = 几何层（房间/走廊/墙/瓦片）；连接表 = 缝合层（图内 exits、跨图 warps）
3. 与既有地图体系共存：`rooms[]/exits[]/warps[]/zones[]` 仍是渲染与玩法的权威字段，`structure` 是生成语义元数据（宽容解析，旧前端零破坏）

**范围外**：本批为设计；代码落地按第十节分期执行。

---

## 二、设计原则（为什么这么设计）

| 原则 | 内容 | 依据 |
|---|---|---|
| 契约先行 | structure 为地图 JSON 可选键，缺失=普通地图，零破坏 | D-014/D-020 版本纪律 |
| 确定性 | 整棵结构树由单一 seed 派生（每节点子 seed = hash(父seed + 路径)），同 seed 同输出 | D-031 大图确定性先例 |
| LLM 只做语义 | L0 蓝图可走结构模板库（零成本）或 LLM（长尾主题）；几何全部程序化 | P-0804-H 教训（LLM 手出坐标需大量修补） |
| 连接点缝合 | 每层只暴露连接点：图内 exits[]、跨图 warps[]，连通性 BFS 校验 | P-0817-G MapExits / P-0803-K 多图 |
| 单图优先、超限拆图 | 单图面积 ≤ 预算 → 一张图 + exits；超限 → 拆多图 + warps + 多图注册表 | P-0817-D max 256×256 |
| 复用不重造 | L2/L3/L4 复用模板生成器/BSP/Phaser 房间模式，新增只在 L0/L1 + API | AGENTS 禁动纪律 |

---

## 三、结构树契约 v0.1

### 3.1 字段表（地图 JSON 新增可选键 `structure`）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `structure.version` | int | ✅ | 结构树版本（本设计 = 1；缺失按 1） |
| `structure.kind` | string | ✅ | 结构类型：`castle` / `mansion` / `city_block` / `dungeon` / `custom` |
| `structure.name` | string | 可选 | 结构名（如「晨曦城堡」） |
| `structure.seed` | long | ✅ | 根种子（生成可复现） |
| `structure.root` | node | ✅ | 结构树根节点（见 3.2） |
| `structure.relations` | array | 可选 | 关系表：`{from, to, kind}`，kind ∈ `contains`/`adjacent`/`connects` |

### 3.2 节点（node）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | ✅ | 全局唯一（结构内） |
| `type` | string | ✅ | `structure` / `zone`（分区，超大结构拆图单位）/ `building` / `room`（叶子） |
| `name` | string | ✅ | 语义名（前端展示/LLM 绑定） |
| `children[]` | array | 可选 | 子节点（仅 structure/zone/building 可含） |
| `template` | string | 可选 | 房间模板键（L2 用；如 `great_hall`/`cafe_bar`）；缺省由 L0 生成器分配 |
| `open` | bool | 可选 | 开放区域（庭院/广场，无围合墙） |
| `size` | [w,h] | 可选 | 期望占格（L1 布局参考；缺省按模板默认） |
| `theme/style` | string | 可选 | 主题覆盖（如「古风」） |
| `clue_locations[]` | array | 可选 | 线索地点（绑定 zones，沿用既有 ensureClueZoneCoverage） |

### 3.3 示例（城堡，节选）

```json
"structure": {
  "version": 1, "kind": "castle", "name": "晨曦城堡", "seed": 20260817,
  "root": {
    "id": "castle", "type": "structure", "name": "晨曦城堡",
    "children": [
      { "id": "gate", "type": "building", "name": "城门楼", "template": "gatehouse", "size": [10, 8] },
      { "id": "courtyard", "type": "zone", "name": "外庭", "open": true, "size": [26, 8] },
      { "id": "great_hall", "type": "building", "name": "大厅", "template": "great_hall", "size": [16, 8] },
      { "id": "west_wing", "type": "building", "name": "会客厅", "template": "gu_parlor", "size": [12, 8] },
      { "id": "tower_bed", "type": "building", "name": "塔楼卧室", "template": "gu_bedroom", "size": [8, 8] },
      { "id": "armory", "type": "building", "name": "兵械库", "template": "armory", "size": [10, 8] }
    ]
  },
  "relations": [
    { "from": "gate", "to": "courtyard", "kind": "adjacent" },
    { "from": "courtyard", "to": "great_hall", "kind": "adjacent" },
    { "from": "great_hall", "to": "west_wing", "kind": "adjacent" }
  ]
}
```

### 3.4 宽容解析与校验（`StructureValidator`）

- 宽容：`structure` 缺失/非法 → 视为普通地图（零破坏）；version 缺失按 1；relations 缺失 = 由 L1 布局推导
- 校验：
  1. version 数字、kind 已知、seed 数字、root 存在
  2. 节点 id 全局唯一、父子无环、叶子必须 type=room 或含 template
  3. relations 的 from/to 必须存在、kind 已知
  4. 叶子节点（room）映射到地图 `rooms[]`（id 一一对应；缺失 → 警告）
  5. `exits[]` 的 from/to 必须落在结构叶子集合（与既有检查项 12 叠加）
  6. 多图结构：每张图的 `structure` 带 `map_id` 归属，跨图 warps 目标地图存在（警告缺反向）

---

## 四、生成 API

### 4.1 `POST /api/structure/generate`

请求：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `theme` | string | ✅ | 主题（如「晨曦城堡」） |
| `kind` | string | 可选 | `castle`/`mansion`/`city_block`/`dungeon`；缺省 `custom`（走 LLM 蓝图） |
| `seed` | long | 可选 | 根种子；缺省取当前毫秒（响应回传供复现） |
| `width`/`height` | int | 可选 | 单图尺寸上限（≤0 用配置默认；超 max 256×256 clamp） |
| `map_mode` | string | 可选 | `single`（默认，超预算自动升级 multi）/ `multi` |
| `style` | string | 可选 | 主题风格（幻想/现实/科幻/古风…） |
| `locations[]`/`clue_locations[]` | array | 可选 | 剧本地点/线索地点（沿用 zones 绑定） |

响应 200：

```json
{
  "structure": { "...": "结构树（见 §3）" },
  "maps": { "map_1": { "地图契约 v1 JSON" }, "map_2": { "..." } },
  "current_map_id": "map_1",
  "connections": [
    { "type": "exit",  "map_id": "map_1", "exit": { "from": "gate", "to": "courtyard", "door": [6, 10] } },
    { "type": "warp",  "from_map": "map_1", "to_map": "map_2",
      "warp": { "from": [95, 20], "to": ["map_2", 1, 20] } }
  ],
  "generator": { "l0": "template", "kind": "castle", "seed": 20260817, "validation": { "ok": true, "errors": [], "warnings": [] } },
  "fallback": []
}
```

错误：`{error}` 400（缺 theme/kind 未知/尺寸非法）；L0/L1 失败 → L0 回退 BSP 常规地图（`fallback` 记录原因），不 500。

### 4.2 单图/多图拆分策略

- 预算：`roleplay.structure.max-single-map-width/height`（建议 128×128，远小于契约 max 256×256——给多图留余量）
- 单图：结构总面积 ≤ 预算 → 一张图，节点全部落 `rooms[]`，房间间 `exits[]`（MapExits 推导）
- 多图：按 `zone` 节点切图（每 zone 一张 map）；zone 内房间用 exits，zone 间用 **warps**（契约 v0.2 已支持校验、本设计首次让生成器产出）+ 复用 `ScriptGameService` 多图注册表（P-0803-K：`maps`/`currentMapId`/`nextMapId`/`switchMap`）与既有 `/api/script/map/switch` 前端链路

### 4.3 与既有端点的关系

| 端点 | 关系 |
|---|---|
| `POST /api/scenes/map` | 保留（普通单图/预览）；structure 设计不替换它 |
| `POST /api/script/map` | 保留（剧本杀对局地图）；structure 生成器可内嵌为脚本地图的新 L0 来源（后续批次） |
| `POST /api/structure/generate` | **新增**：大型结构专用统一入口 |

---

## 五、分层管线 → 代码组件映射

| 层 | 新组件 | 复用 |
|---|---|---|
| L0 语义蓝图 | `simulation/structure/StructureTemplates.java`（resources/structure/*.json 模板库）+ `StructureLlmBlueprint.java`（可选 LLM） | LLMClient（callJson） |
| L0 校验 | `StructureContract.java`（normalize）+ `StructureValidator.java` | — |
| L1 布局 | `StructureLayoutGenerator.java`（确定性打包 + 走廊 + 门洞 + 拆图） | BspMapGenerator 走廊/门洞范式、MapExits |
| L2 房间内容 | 结构模板键 → 模板配方实例化 | 模板原型（templates-proto.json）→ Java 化；BSP 兜底 |
| L3 细节 | 复用既有模板图元 | decorData 样式映射（前端待扩 28 种） |
| L4 渲染漫游 | 复用 PhaserScriptMapView（房间模式 + warps 切图 + 固定比例尺） | P-0817-F/G |
| API | `controller/StructureController.java` + `service/StructureMapService.java` | ScriptMapService 校验/兜底链路 |

---

## 六、结构模板库（L0 默认来源，4 kinds）

每个 kind = resources/structure/<kind>.json，只含结构树（节点/关系/模板键），几何由 L1 生成：

| kind | 节点构成示例 |
|---|---|
| `castle` | 城门楼 → 外庭(zone) → 大厅 → 两翼（会客厅/宴会厅）→ 塔楼（卧室/书房）→ 厨房 → 兵械库 → 内庭/后花园(zone) |
| `mansion` | 门厅 → 客厅/书房/餐厅/厨房 → 卧室×3 → 后院(zone) → 佣人房/储藏室 |
| `city_block` | 街(zone) → 店铺×4/民居×4 → 广场(zone)/喷泉 → 仓库 |
| `dungeon` | 入口 → 大厅 → 监牢×4 → 储藏室 → 宝库 → BOSS 间 |

房间模板键复用模板原型（cafe_*/kitchen/storage/garden/gu_*/great_hall/gatehouse/armory…），未命中模板的房间由 L1 生成矩形 + BSP 兜底。

---

## 七、种子与确定性

- 根 seed = 请求 seed（缺省当前毫秒）
- 子节点 seed = `hash64(父 seed + "/" + 节点 id)`（确定性，无状态）
- L1 布局、L2 模板实例化、L3 装饰全部由节点 seed 驱动 → 同主题同 seed 同结构同图
- 响应回传 `generator.seed` 供「重新生成同结构」与测试断言

---

## 八、配置键（`roleplay.structure.*`，yml 双份）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `l0-source` | template | `template`（零成本）/ `llm`（长尾主题）/ `auto`（模板未命中→LLM） |
| `max-single-map-width` / `max-single-map-height` | 128 / 128 | 单图预算（超限拆多图） |
| `template-dir` | classpath:/structure | 结构模板库位置 |

---

## 九、测试计划

1. `StructureContractTest`：宽容解析（缺失/非法零破坏、version/relations 兜底）、节点 id 唯一/父子无环/叶子含模板、relations 目标存在
2. `StructureLayoutTest`：城堡/庄园模板 × 多 seed → 单图 rooms 覆盖结构叶子、exits 双向连通（BFS 全结构可达）
3. `StructureMapServiceTest`：同 seed 同输出（确定性）；超预算 → 多图 + warps 双向；LLM 蓝图失败 → fallback 记录 + BSP 兜底不 500
4. `StructureEndpointTest`（@SpringBootTest MockMvc）：400 缺 theme / 未知 kind / 非法尺寸；200 单图与多图契约完整、validation ok
5. 回归：MapValidator 既有 24 用例 + 地图 4 类 37 用例零破坏

---

## 十、分期落地

| 期 | 内容 | 验收（落地状态） |
|---|---|---|
| P1 | 契约 v0.1：MapContract +structure 宽容解析/空默认 + StructureContract/StructureValidator + 城堡模板 JSON | ✅ 完成（P-0817-L）：结构树校验通过；旧地图零破坏（全量回归 962/1 仅既有遗留） |
| P2 | L1 布局器（单图）+ `POST /api/structure/generate`（kind=castle 单图） | ✅ 完成（P-0817-L）：城堡 11 房间全连通、同 seed 同输出、四模板单图全连通 |
| P3 | 多图拆分 + warps 连接 + 接入多图注册表 | ✅ 完成（P-0817-L）：城市街区 40×40 自动拆 2+ 图、跨图 warp 双向（契约 v0.2 warps[]；对局注册表桥接留前端切图时接入） |
| P4 | LLM 蓝图 L0（kind=custom）+ 前端接线（结构树选择/生成入口） | ✅ 完成（P-0817-M）：StructureLlmBlueprint（语义 JSON、禁坐标）+ custom 服务接线（l0-source=llm/auto）+ 前端「🏰 大型结构」页（类型选择/生成/Phaser 预览/多图切换/warp 表） |

> 每期沿用项目纪律：契约先行、每层校验、seed 可复现、LLM 只做语义、几何程序化；禁动文件（RouterService/ArbiterService/审批/狼人杀/SSE 主链路/static）零改动；改动登记 `docs/修改记录.md`、测试更新 `TEST_STATUS.md`。
