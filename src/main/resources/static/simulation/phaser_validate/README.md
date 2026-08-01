# Phaser 3.90 迁移验证 Demo（阶段 0）— phaser_validate

> 独立验证页：验证 Phaser 3.90 能否覆盖剧本杀后续 2D 渲染需求。对应 `docs/Phaser迁移计划.md` 阶段 0（DECISION_LOG **D-020**）。
> 严格不触碰生产代码（ScenePage.tsx / simulation.html / vision demo / Java 后端一律不动）。

## 打开方式

| 方式 | URL / 命令 | 说明 |
|---|---|---|
| ① 双击（推荐，最快） | `index.html`（本目录） | file:// 模式，资源自动走内嵌 base64（`js/assets_embedded.js`），无需任何服务器 |
| ② 本地 http | `python -m http.server 8899` 后访问 `http://127.0.0.1:8899/index.html` | 真实文件管线（assets/ 目录直读），推荐日常使用 |
| ③ 后端实例 | `http://localhost:8000/simulation/phaser_validate/index.html` | 静态产物已同步 `target/classes`，**运行中实例需重启后生效**（既有惯例：前端产物同步亦需重启，见台账 #44/#46） |

- 页签直达：`index.html?tab=bsp` / `?tab=zones` / `?tab=anim` / `?tab=contract`
- 生命周期自测：`index.html?selftest=cycle`（自动轮巡 5 页签，验证 Game 实例 destroy/重建收敛）

## 五个验证点（对照迁移计划 §2 阶段 0）

| # | 验证点 | 页签 | 操作与验证 |
|---|---|---|---|
| 1 | **瓦片渲染 + 碰撞** | ① 瓦片渲染+碰撞 | WASD 移动，3 个红色 AI 漫游；玩家/AI 均被墙体阻挡（Arcade + Tilemap 碰撞层），墙体验证直观可见；右侧统计显示碰撞格 160 |
| 2 | **BSP 分区** | ② BSP 分区 | 打开即见 BSP 递归二分生成的房间/走廊地图（固定 seed 可复现）；右侧显示生成 JSON（契约 v1）与校验器结果；「重新生成」随机 seed 再跑；校验器对坏 JSON 报错（页签 ⑤ 可试） |
| 3 | **Zone 热点** | ③ Zone 热点 | 金色区域=搜证热点；走近触发提示条（onEnter 回调），点击热点 或 按 E 触发搜证（onInteract 回调）→ 底部弹出线索文本并计入「已搜证」列表 |
| 4 | **Aseprite 动画** | ④ Aseprite 动画 | WASD 移动角色1（`load.aseprite` + `createFromAseprite` 管线），方向键移动角色2（spritesheet 管线）；走动切方向动画、静止回 idle 帧；右侧确认「4 个动画已创建」 |
| 5 | **地图 JSON 契约草案** | ⑤ 地图 JSON 契约 | 字段表 + manor_01 样例 + 校验器（粘贴任意地图 JSON 运行校验）；契约文档 `docs/地图JSON契约-draft.md` |

## 技术取舍说明

- **Phaser 引入方式**：本地 vendor 文件（`vendor/phaser.min.js`，npm `phaser@3.90.0` dist 提取，锁定 v3 稳定线）。
  取舍：CDN 在国内网络可用性不稳定且 file:// 下无法离线使用 → 本地 vendor 直引（1.19MB，可接受）。
  CDN 备选：`https://cdn.jsdelivr.net/npm/phaser@3.90.0/dist/phaser.min.js`（若改用 CDN，仅需替换 index.html 一个 script src）。
- **纯静态直引，不纳入 Vite 构建**：对齐既有 vision demo 先例（`static/simulation/vision/`，台账 #42）与 `static/simulation.html` 模式；构建型前端归 `roleplay-v4/frontend`，demo 独立可运行（file:// 与 http 均可）。
- **file:// 资源策略**：浏览器禁止 file:// 下 XHR（Phaser 加载器依赖 XHR）→ 检测 `location.protocol === 'file:'` 时自动改用 `js/assets_embedded.js` 的内嵌 base64 data URI（`tools/export_assets.js` 生成）；http 下走 assets/ 真实文件管线（真实素材加载路径验证）。
- **素材来源标注**：全部占位素材由 `tools/gen_assets.js` 程序生成（瓦片集 5 格 + 两个 4×4 帧 32px 角色精灵表 + Aseprite 格式 JSON），**零第三方版权素材**；验证的是 Phaser 素材加载/动画管线而非美术质量，真实 Aseprite 素材替换同构 png+json 即可。
- **Aseprite JSON 格式说明**：Phaser 3.90 `createFromAseprite` 按数字索引串解析帧（源码：`frames[i.toString()]` → `texture.get(frame)`），故 JSON 的 `frames` 键必须为 `"0".."15"`（本 demo 的生成器已按此产出，非 Aseprite 原版 hash 键名，替换真实素材时需注意）。

## 自测

```bash
python tools/self_test.py            # http 模式：5 页签 DOM 证据（默认 127.0.0.1:8899，需先起 http.server）
python tools/self_test_file.py       # file:// 模式：内嵌资源兜底验证
python tools/self_test_lifecycle.py  # 生命周期轮巡：destroy/重建收敛
node tools/gen_assets.js             # 重新生成占位素材（需在仓库外新机器复现时运行）
node tools/export_assets.js          # 重新导出内嵌资源 + maps/*.json 规范副本
```

自测结果（2026-08-01 阶段 0 批次）：http 5/5 页签 ALL PASS + file:// 5/5 ALL PASS + 生命周期收敛无 JS 异常（详见 TEST_STATUS.md 阶段 0 条目）。

## 目录结构

```
phaser_validate/
├── index.html              入口页（5 页签 + 说明 + 错误面）
├── README.md               本文件
├── vendor/phaser.min.js    Phaser 3.90.0（本地 vendor）
├── assets/                 占位素材（tiles.png / player*.png / player*.json）
├── js/
│   ├── map_contract.js     契约样例（manor_01 老宅，UMD 双端）
│   ├── bsp.js              BSP 生成器 + 契约校验器（纯逻辑，node 可测）
│   ├── common.js           契约 JSON → Tilemap 渲染管线（阶段 1 原型）
│   ├── assets_embedded.js  file:// 内嵌资源（生成）
│   ├── main.js             页签管理 + Game 生命周期 + 契约页
│   └── scenes/             四个 Phaser scene
├── maps/                   manor.json / bsp-sample.json（契约 JSON 规范副本）
└── tools/                  gen_assets.js / export_assets.js / self_test*.py
```
