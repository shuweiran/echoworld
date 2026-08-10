# 五层 Persona 卡格式契约 — persona-card/v1（P-0810-10）

> ⚠️ 本文件为「五层 persona 模板接入后端」批次（P-0810-10，2026-08-10）的格式契约。
> 配套：`src/main/java/com/roleplay/engine/core/Persona.java`（五层构建）、`core/PersonaCardLoader.java`（默认卡加载）、
> `controller/CharacterController.java`（POST /api/characters/{name}/persona 导入）、
> 默认卡 `src/main/resources/persona/xiaoling.json`（小铃）/ `kyle.json`（凯尔）/ `luna.json`（露娜）、
> 生成工具 `tmp/persona-builder/pipeline.py`（LLM 生成 md 卡）+ `tmp/persona-builder/md2json.py`（md → 本 JSON 格式）。

---

## 1. 一句话说明

五层 persona 卡 = 角色的**内部表演指令**（Layer 0 行为规则 / Layer 1 身份 / Layer 2 表达风格 /
Layer 3 情感模式 / Layer 4 冲突链与雷区 + 反差设定 + 人味细节）。它只进 LLM 系统提示
（`Persona.buildSystemPrompt()` 完整输出），**绝不进任何对外 API**——对外只暴露表层
（name / appearance / voice / background / summary）。

## 2. 数据流

```
resources/persona/*.json（默认卡）或 POST /api/characters/{name}/persona（导入卡，内存）
        │ PersonaCardLoader.attach / CharacterController.attachPersonaCard（按角色名挂载）
        ▼
Persona.layers（内部字段，toMap 不序列化）
        │ buildSystemPrompt()（有五层 → 五层模板；无 → 旧 4 字段回退）
        ▼
Agent.buildContext → LLM 系统提示（五层完整可见）
```

- 导入卡（POST API）优先于默认资源卡；两者均只在角色名匹配时挂载。
- 导入卡存 `CharacterController.personaCards` 内存库（独立于角色表/H2，重启需重新导入；默认卡随资源文件常驻）。
- 挂载规则：`Persona` 已有 layer 数据 → 不覆盖；`personaDesc/voice/background` 只在空或占位
  （`name，一个角色`）时用卡的表层回填——用户显式传入的内容绝不覆盖。

## 3. JSON 结构（schema = "persona-card/v1"）

```jsonc
{
  "schema": "persona-card/v1",          // 必填：版本标记（宽松解析，缺失不拒绝）
  "id": "heroine",                       // 可选：后端 id 别名（heroine/knight/luna，便于按 id 查卡）
  "name": "小铃",                        // 必填：角色名（与对局/角色库 name 一致；POST 导入时以路径名为准覆盖）
  "appearance": "silver long hair, ...", // 表层：外观锚点（生图/前端展示；可对外暴露）
  "summary": "一句话背景摘要",           // 表层：背景一句话（可对外暴露）
  "personaDesc": "人格设定摘要",         // 表层：旧 4 字段之一（空/占位时回填）
  "voice": "说话风格摘要",               // 表层：旧 4 字段之一（空时回填）
  "background": "背景故事摘要",          // 表层：旧 4 字段之一（空时回填）

  "contrast": {                          // P-0810-10 硬性：反差设定（表面 X ↔ 实际 Y）
    "surface": "表面：温柔体贴、细心周到",
    "actual": "实际：胜负欲强、对咖啡手艺有执念",
    "hint": "当话题涉及手艺被质疑时反差显现"        // 可选：反差触发提示
  },

  "humanDetails": [                      // P-0810-10 硬性：人味细节（小缺点/小习惯/口头禅/情绪化表达）
    "小缺点：被夸会害羞到手足无措",
    "小习惯：紧张时绞围裙角",
    "口头禅：「欢迎回来」「嗯嗯」",
    "情绪化表达：忙不过来会小声抱怨「今天好累…」"
  ],

  "layer0": [                            // 行为规则化（核心模式，最高优先级；5~8 条为宜）
    "当客人说咖啡不好喝时，你不辩解，先道歉再重做——你永远不会说「是您口味的问题」。",
    "当有人当面夸你时，你低头说「没有啦」然后转移话题——你永远不会坦然接受赞美。"
  ],

  "layer1": {                            // 身份（客观事实层，不写性格判断词）
    "gender": "女，她",                  // 代词规则：男→他 / 女→她 / 未说明→TA
    "age": "16岁",
    "identity": "和风咖啡馆「铃屋」店主兼咖啡师",
    "world": "城市老街上一间木质装修的小咖啡馆",
    "relation": "玩家是经常光顾的熟客，坐靠窗固定位置"
  },

  "layer2": {                            // 表达风格（声音指纹）
    "catchphrases": ["「欢迎回来」——熟客进门时", "「嗯嗯」——倾听回应"],
    "sentenceStyle": "短句为主 5~12 字，多用「…」留白",
    "emojiHabits": "尾音带「〜」；书面爱用🌸☕；低落时不发 emoji",
    "sampleLines": [                     // 原话示例（直接写会说的话，≥4 条为佳）
      "日常：「欢迎回来〜今天还是老位置吗？☕」",
      "被夸：「啊、那个…没有啦…🌸」"
    ]
  },

  "layer3": {                            // 情感模式（感受 → 行为化表达）
    "care": "用行动不用语言：记住口味、主动续杯…",
    "displeasure": "不直接说生气，突然安静、回答变短…",
    "apology": "不找理由，低头认错 + 补偿行动…",
    "affection": "几乎不主动说出口…「…明天也来哦」",
    "allowEmotionalWobble": true         // 可选：允许情绪波动（默认渲染固定引导句）
  },

  "layer4": {                            // 冲突链与雷区
    "triggers": ["被质疑咖啡品质", "被随意触碰"],
    "conflictSequence": "愣住笑容僵住 → 沉默超三秒 → 「…我去后厨看看」→ 端新饮品「…请用」→ 等对方先开口",
    "coldWar": "「在但不在」：问什么答什么但全是单字",
    "reconcileSignal": "放一块你喜欢的点心，什么都不说",
    "boundaries": ["不接「家里的事」", "被问「你是不是喜欢我」→ 僵住说「我去收银台理账」"]
  }
}
```

### 键名说明
- `layer0`：**List\<String\>**（每条是「当 [场景] 时，你 [具体行为]」句式）。
- `layer1`~`layer4`：**Map**，值可为 String / List\<String\> / Map；渲染时按友好标签输出
  （`gender→性别/代词`、`care→表达在乎`、`triggers→触发点` 等，未收录键名原样输出）。
- `contrast` / `humanDetails`：可放顶层（如上），也可放 `layer3.humanDetails`（渲染均识别）。
- `sampleLines` 特殊：渲染为「原话示例」子条目（轻量提示只带前 2 条）。
- 缺省宽容：`layer1`~`layer4` 任一缺失不影响其他层；`schema` 缺失不拒绝（宽松解析）。

## 4. buildSystemPrompt 输出（五层模板，LLM 上下文完整可见）

```
你是 {name}。

【反差设定】
表面：… / 实际：… / 提示：…

【Layer 0 行为规则（不可违背，最高优先级）】   1. … 2. …
【Layer 1 身份】                               性别/年龄/身份/世界背景/与玩家的关系/外观
【Layer 2 表达风格】                           口头禅/句长句式/emoji 习惯/原话示例
【Layer 3 情感模式】                           在乎/不满/道歉/喜欢 + 人味细节 + 「允许情绪波动…不完美回应」
【Layer 4 冲突链与雷区】                       触发点/冲突序列/冷战/和解信号/边界

【行为总原则】1~6（Layer0 最高优先 / 风格 / 情感模式 / 冲突链 / 不跳出角色 / 语言对齐）
【身份锁定】…（原样保留）
【表演规则】…（原样保留）
```

- `buildLightweightPrompt()`（多数轮次省 token）：`你是 {name}` + 反差 + 【说话风格】(Layer2) +
  【行为要点】(Layer0 前 3 条) + 身份锁定/表演规则——**不含完整冲突链/情感模式**。
- 无 layer 数据：`buildSystemPrompt()` / `buildLightweightPrompt()` 完全回退旧 4 字段格式（零破坏）。

## 5. 导入与加载

### 5.1 POST 导入（运行时，内存）
```
POST /api/characters/{name}/persona
Content-Type: application/json
body = 五层卡 JSON（上面格式）
```
- 校验：body 非空且包含 `layer0~layer4 / contrast / humanDetails` 至少一个键，否则 400。
- 响应只回表层：`{status, name, appearance?, summary?, layers: [键名列表]}`——**绝不回 layer 内容**。
- 导入后 `GET /api/characters` 的角色条目只附加 `appearance`/`summary` 两个表层键，不透出五层。

### 5.2 默认卡（resources 常驻）
- `src/main/resources/persona/*.json`：小铃（xiaoling.json，id=heroine）/ 凯尔（kyle.json，id=knight）/
  露娜（luna.json，id=luna）。`PersonaCardLoader` 静态懒加载，按 `name` 与 `id` 双索引。
- 任何 Persona 构建点（`SessionController.init` / `SceneController.startScene` /
  `SimulationController.loadCharacters` / `HistoryController.buildAgents`）经
  `CharacterController.attachPersonaCard(p)` 一行挂载：导入卡优先 → 默认资源卡 → 无卡 no-op。

### 5.3 LLM 生成链路（可选）
`tmp/persona-builder/pipeline.py`（五层 md 卡）→ `tmp/persona-builder/md2json.py`（md → 本 JSON 格式）→
POST 导入或放入 `resources/persona/`。虚构创作无聊天记录：Layer 0/4 建议人工过一遍再上线。

## 6. 对外 API 不透出清单（P-0810-10 硬性）
以下字段**绝不**出现在任何对外响应（GET /api/characters、GET /api/state characters、init 响应、
Persona.toMap 等）：`layers` / `layer0` / `layer1` / `layer2` / `layer3` / `layer4` / `contrast` / `humanDetails`。
实现保证：层数据存独立内存库 + `Persona.toMap()` 不序列化 `layers`（测试 `PersonaFiveLayerTest` 锁定）。
