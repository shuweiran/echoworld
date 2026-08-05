# 🎨 生图管线 Demo（阶段一 · 独立验证页）

> **性质**：纯静态独立验证页（对齐 `phaser_validate` 先例），**零后端零禁动零成本**。
> **访问**：`src/main/resources/static/simulation/imagegen/index.html` —— file:// 双击即开；工程内 `/simulation/imagegen/index.html`。
> **自测**：`node src/main/resources/static/simulation/imagegen/js/self_test.js`（Node 直跑，零依赖）。

## 一句话

演示「结构化生图描述（`image_spec`）→ 生图 provider → 渲染 → 接入映射」整条管线，
回答主人「如何更好的生图」：**文本模型出结构化描述，扩散模型出图，瓦片保持程序化**。

## 管线

```
① 输入（主题 / 剧本 JSON v1）
   ↓
② image_spec 合成器（image_spec.js）
   角色→立绘(portrait) / 背景·地点→场景氛围(landscape) / 线索→物证(square) / 瓦片→风格锚点
   全局风格锚点 styleForTheme(theme) —— 风格统一 > 单图质量
   ↓
③ 生图 provider（providers.js，三层降级）
   1) openai_image（/images/generations 兼容：DALL-E/通义万相/即梦如走兼容接口可接）
   2) sd_webui（SD WebUI /txt2img，本地 ComfyUI 可对位）
   3) offline（程序化 SVG 占位 —— 离线/无 key 仍可完整演示管线）
   可选 LLM 扩写钩子（extendPrompt，OpenAI 兼容 chat/completions，注入风格锚点）
   ↓
④ 渲染展示 + ⑤ 接入映射（→ assets 表 / Phaser tilemap）
```

## image_spec 契约 v1（字段表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `image_version` | int | 内嵌版本（对齐 D-014 纪律，缺省按 1） |
| `theme` / `style` | string | 主题 / 全局风格锚点 |
| `images[]` | array | 生图单元 |
| `images[].id` | string | 唯一 id（`char_<roleId>` / `scene_<n>` / `clue_<id>` / `tile_style`） |
| `images[].kind` | string | `character` \| `scene` \| `clue` \| `tile_style` |
| `images[].name` | string | 展示名 |
| `images[].prompt` | string | 正向描述（可选 LLM 扩写） |
| `images[].negative` | string | 负向描述 |
| `images[].style` | string | 风格提示（继承全局锚点） |
| `images[].aspect` | string | `portrait` \| `landscape` \| `square` |
| `images[].usage` | string | `role_card_avatar` \| `scene_background` \| `clue_evidence` \| `tileset_style` |
| `images[].related` | string | 关联对象（角色名/场景/线索地点，映射 assets.characterName/sceneId） |
| `images[].status` | string | `pending` \| `generated` \| `fallback` \| `failed` |

## 整合阶段（后续批次，非本 demo）

1. **后端**：`POST /api/image/spec`（LLM 由剧本 schema v1 扩写 image_spec）+ `POST /api/image/generate`（provider 调用，异步任务 + 状态轮询，不阻塞主循环）
2. **schema**：`ScriptSchemaV1` 可选加 `image_spec`（宽容解析，版本仍 1）——剧本生成即带图描述
3. **assets 消费**：生成结果登记 assets 表（新增 `ROLE_PORTRAIT` / `SCENE_BACKGROUND` / `CLUE_IMAGE` 类型，对齐 P-0804-C 既有 assets 表 + assetFileUrl 消费链）
4. **前端**：角色卡立绘 / 剧本卡封面 / 搜证结果卡配图 / 地图背景层；瓦片风格图仅作概念参考，实际 tilemap 保持程序化（避免 AI 瓦片接缝问题）

## 风险与取舍

- **成本/延迟**：生图 API 有成本与延迟 → 整合阶段必须异步解耦 + 懒加载，瓦片离线预生成
- **风格漂移**：靠固定风格模板 + 同种子/LoRA + 图生图统一，demo 已把风格锚点内置演示
- **本 demo 不含真实 LLM/生图调用**：避免新增端点触碰禁动面；provider 配置页留真实接入口
