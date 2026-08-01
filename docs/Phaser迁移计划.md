# Phaser 迁移计划 — 接入 Phaser 3.90 作为 2D 渲染层（渐进式迁移）

> **状态**：🟢 已决策（DECISION_LOG.md **D-020**，2026-08-01）；**阶段 0 验证 demo 已完成（2026-08-01 台账 #49，http/file:// 双模式自测全绿），待未衡审查验收**——审查通过后进入阶段 1
> **一句话**：用 Phaser 3.90（锁 v3 稳定线）渐进式替换自研 Canvas 渲染，后端零改动，先验证后替换再接入 AI 内容。

---

## 1. 决策摘要

| 项 | 内容 |
|---|---|
| 决策编号 | DECISION_LOG.md D-020（2026-08-01） |
| 技术选型 | Phaser 3.90，**锁定 v3 稳定线**（不追 v4 重构线，生态与资料成熟度优先） |
| 迁移方式 | 渐进式三步（阶段 0 验证 → 阶段 1 换渲染层 → 阶段 2 接 AI 内容），非一次性重写 |
| 核心前提 | 后端 Java 权威模拟 + 前端纯渲染——引擎只换渲染层，**后端零改动** |
| 工作量对比 | 自研 2D 渲染能力 10–16 人日 vs Phaser 引擎 2.5–4.5 人日；回本点 3–5 个渲染功能点，当前已确定 5 个 |
| 资产保值 | 约 70% 自研资产复用（见 §4 清单） |
| 路线图来源 | 主人拍板（2026-08-01），本计划为执行基线 |

---

## 2. 迁移三步阶段

### 阶段 0：验证 demo（可行性验证，先行）

**范围**：在既有 2D 视觉 demo 同位置（`src/main/resources/static/simulation/`）做独立 Phaser 验证页，验证 5 个已确定功能点在 Phaser 下的实现成本与手感。

- 瓦片渲染 + 碰撞（Tilemap + Arcade/静态物理）
- BSP 分区（房间/走廊生成的地图数据在 Phaser 中的承载与渲染）
- Zone 热点（可交互区域：搜证点/房间入口/广播位）
- Aseprite 动画（精灵帧动画接入，验证素材管线）
- **地图 JSON 契约草案**（数据结构定义，阶段 2 由 LLM 生成的对接面）

**交付物**（阶段 0 已完成，2026-08-01）：
- 验证页 `static/simulation/phaser_validate/`（已交付：index.html 5 页签 + vendor/phaser.min.js 3.90.0 + 占位素材 + 地图 JSON 样例 maps/manor.json、maps/bsp-sample.json + 自测脚本 tools/self_test*.py）
- 地图 JSON 契约草案文档 `docs/地图JSON契约-draft.md`（字段表 + 示例 + 宽容解析规则 + 校验器）
- 验证结论：5 个功能点全部跑通，单点实现耗时均 ≤ 自研预估的一半（引擎红利兑现），详见 TEST_STATUS.md 阶段 0 条目

**验收标准**（阶段 0 执行结果 2026-08-01）：
- ✅ 5 个功能点全部跑通（瓦片渲染+碰撞 / BSP 分区 / Zone 热点 / Aseprite 动画 / 地图 JSON 契约草案），自测证据：http 5 页签 + file:// 5 页签 + 生命周期轮巡 ALL PASS 无 JS 异常（tools/self_test*.py + TEST_STATUS.md 阶段 0 条目）；实现耗时全部显著低于自研预估（引擎红利兑现，见 TEST_STATUS 条目中的耗时对比）
- ⏳ 地图 JSON 契约草案（docs/地图JSON契约-draft.md）待未衡审查
- ✅ 验证页 file:// 与 http 均可访问（对齐 vision demo 惯例；file:// 走内嵌 base64 兑底，http 走真实文件管线）

**退出条件**：任一功能点验证失败且无替代方案 → 回滚保持自研渲染（见 §3），本计划终止。

### 阶段 1：ScenePage 渲染层换 Phaser（数据流不变）

**范围**：`roleplay-v4/frontend` 的 ScenePage 2D 渲染层由自研 Canvas 换为 Phaser（React 内嵌 Phaser Game 实例，Ref 挂载），**数据流与状态流不变**——后端 SSE/REST 推送的状态仍是唯一数据源，Track 数据管线（SpatialTrackResolver→TrackStrategy→MovementConstraint）的产出直接作为渲染输入。

**交付物**：
- ScenePage 渲染层改造（Phaser Scene 承载 2D 模拟视图，替换手绘 Canvas 绘制循环）
- 瓦片地图 / BSP 分区 / Zone 热点 / 角色精灵动画在 ScenePage 实装
- 前后端契约零变更确认（对照 `docs/测试方案-全功能覆盖-v2.md` 与既有端点）

**验收标准**：
- 2D 模拟功能与改造前行为一致（角色移动/聚集/听觉带/避让等 Track 约束可视化不回归）
- 无后端改动、无数据流契约改动（git diff 仅前端 + static 产物）
- 既有 176 tests 基线全绿（后端零触碰，回归面为前端）

### 阶段 2：LLM 生成地图 JSON 接入 + 热点绑定 + schema 版本化

**范围**：
- LLM 生成地图 JSON（阶段 0 契约草案定稿 → schema 版本化，对齐剧本 schema v1 的版本纪律：JSON 内嵌版本、宽容解析归一）
- 搜证线索绑定 Zone 热点（搜证点 = 热点，搜证交互落在 Phaser 热点上，联动剧本杀线索体系）
- 地图 schema 落库/读取路径（若需持久化，遵循「不扩展表结构、JSON 内嵌版本」纪律，见 D-013/D-014）

**交付物**：
- 地图 JSON schema 契约文档（版本化）
- LLM 生成地图接入（生成路径统一 + 宽容解析 + 兜底，对齐 D-014 双生成器统一模式）
- 搜证热点绑定前端实装

**验收标准**：
- LLM 生成地图 → 前端 Phaser 渲染全链路闭环
- 搜证线索在热点上可交互（搜证结果/AP 扣减/线索转交不受影响）
- schema 版本化测试覆盖（旧格式归一/新格式透传/兜底）

---

## 3. 风险与回滚策略

| 风险 | 等级 | 缓解/回滚 |
|---|---|---|
| 阶段 0 验证失败（某功能点在 Phaser 下成本不降反升） | 高 | 阶段 0 即退出：不进入阶段 1，保持自研 Canvas 渲染，计划终止；验证结论存档供后续重估 |
| 阶段 1 渲染层替换引入前端回归（2D 模拟可视化行为不一致） | 中 | 渲染层与数据流解耦——数据流不变则回归面仅绘制层；对照验收标准逐项回归；可回滚 ScenePage 至自研 Canvas 版本（渐进式迁移每阶段独立可验收即回滚点） |
| React 内嵌 Phaser 生命周期冲突（Game 实例销毁/重建、HMR） | 中 | **阶段 0 已先行验证**（未衡审核建议 2026-08-01，台账 #49）：验证 demo 页签切换即执行 `game.destroy(true)` + 重建，`?selftest=cycle` 轮巡 5 页签全部收敛、无 JS 异常（实测证据见 TEST_STATUS 阶段 0 条目）；阶段 1 落地 React 集成模式（Ref 挂载 + 卸载时显式 destroy + HMR 保护——Vite dev 下 module.hot.dispose 中销毁实例）；Vite 构建链加入 Phaser 依赖后全量 build 回归 |
| Phaser 体积影响前端包体（~1MB 级） | 低 | 按需引入（核心模块）或代码分割；构建产物同步 static/ 前先验证 |
| 地图 JSON schema 漂移（LLM 输出与契约不一致） | 低 | 阶段 2 沿用剧本 schema v1 纪律：宽容解析归一 + 兜底 + 测试锁定；schema 版本化内嵌 JSON，不扩展表结构 |
| 与并行工作流撞车（另一主会话改前端） | 中 | 遵循 AGENTS.md：派单前 `git diff` 确认基线；涉及前端文件改动前与主会话确认 |

**总体回滚原则**：渐进式迁移的每一阶段独立可验收、可回滚；阶段 0 是总闸门——验证不过即停，无沉没成本。

---

## 4. 现有资产复用 / 作废清单（基于第二轮调研结论）

### ✅ 复用（约 70%）

| 资产 | 位置 | 复用方式 |
|---|---|---|
| vision_core.js（视线/迷雾纯函数逻辑） | `src/main/resources/static/simulation/vision/vision_core.js` | 算法直接移植或作为 Phaser 场景内逻辑参照（LOS/迷雾/草丛不对称视觉） |
| Track 数据管线 | `src/main/java/com/roleplay/engine/simulation/track/`（SpatialTrackResolver / InteractionDetector / EavesdropSummarizer） | **零改动**——其产出（位置/轨道/约束）即渲染数据源，Phaser 只消费 |
| 移动约束产出 | `simulation/movement/MovementConstraint` | 同上，零改动 |
| 演讲广播地基 | `broadcast/`（AnnouncementService / BroadcastMessage / SseBroadcaster）+ WorldEventBus | 零改动——横幅/公告/演讲渲染由前端现有组件承载，与渲染层正交 |
| React 组件 | `roleplay-v4/frontend/src/components/`（ScenePage / ChatPage / ScriptStatePanel / AnnouncementBanner / AnnouncementTicker 等） | 全复用——仅 ScenePage 内 2D 绘制层替换为 Phaser，组件外壳/状态/SSE 接线不动 |
| 前端状态与 API 层 | `store/appStore.ts` / `api/client.ts` / `useSSE.ts` | 全复用，数据流契约不变 |
| 后端全部（Java） | `src/main/java/` | **零改动**（结构性前提） |

### ❌ 作废

| 资产 | 位置 | 作废内容 |
|---|---|---|
| simulation.html 手绘渲染 | `src/main/resources/static/simulation.html`（791 行，手绘渲染约 450 行） | 手绘 Canvas 绘制循环（绘制角色/障碍/视野多边形等）由 Phaser 引擎能力替代；页面整体定位待阶段 1 决定（保留为纯静态参考或退役，需主人确认，不擅自删除——AGENTS 删除纪律） |

### ⚠️ 待评估（阶段 1 决定去留）

- `ScenePage.tsx.bak`（历史残留，问题清单已记录）——与迁移无关的既有清理项，阶段 1 顺手处理需主人确认

---

## 5. 执行纪律（对照 AGENTS.md）

1. **禁止 `spring-boot:run`**（8000 端口有运行中后端）；后端零改动前提下无需跑 Java 测试，但若触碰任何 Java 文件必须先登记台账
2. **不要 git commit**（等主人确认；2026-08-01 台账 #42 授权仅限该批次）
3. 改码必须登记 `docs/修改记录.md`，测试后更新 `TEST_STATUS.md`
4. 阶段完成 → 关键节点过未衡审查 → 通过后汇报主人
5. 前端改动派单前先 `git diff` 确认基线（并行工作流预警仍生效）
6. 文档变更同步登记 `docs/INDEX.md`（活文档地图）
