# Chat Profile Miner

从聊天记录中挖掘对方的喜好、厌恶、礼物信号，输出结构化偏好画像。
核心原则：**事实 ≠ 推断 ≠ 猜测**，所有结论必须绑定原聊天证据。

## 触发条件

用户上传/粘贴聊天记录并要求分析对方喜好、推荐礼物时使用。

## 核心理念

> "她是什么人格"远没有"她过去真实表达过什么、分享过什么、舍不得买什么、持续使用什么"重要。

**不要输出：** "她是 INFJ、浪漫主义、焦虑依恋，所以送项链。"
**应该输出：** "她主动分享细项链 4 次，2 次明确说'好看'，曾说'不太喜欢金色'，最近仍佩戴类似小型首饰。"

---

## 分析流程

### Phase 0: 输入解析

支持格式：
- 微信/QQ 导出 txt
- 抖音/飞书/Telegram 聊天
- 直接粘贴文本
- JSON/HTML 格式

统一转为 JSONL 标准格式：

```json
{
  "message_id": "wx_8e19c07f",
  "platform": "wechat",
  "conversation_id": "gf_001",
  "timestamp": "2026-05-13T22:16:32+08:00",
  "speaker": "target",
  "speaker_role": "girlfriend",
  "content_type": "text",
  "text": "这个项链好好看，但是八百多有点舍不得",
  "reply_to": null,
  "source_file": "chat_2026_05.txt",
  "source_offset": 183924
}
```

**关键：** source_file + source_offset 必须保留，系统最终能做到"为什么说她喜欢这个？→ 点击 → 看到原聊天"。

### Phase 1: 脱敏

自动过滤：
- 手机号、地址、账号、密码
- 身份证、银行卡号
- 第三方隐私信息

不持久化：
- 政治观点、宗教信仰
- 性生活/性取向
- 疾病与医疗诊断
- 精神疾病推断
- 创伤分析

### Phase 2: 会话切分

按时间间隔 > 2 小时自动切 session。

```
session_001: 2026-05-13 22:00 - 23:30
session_002: 2026-05-14 08:00 - 09:15
...
```

### Phase 3: 显式偏好规则扫描

用正则/关键词快速提取高置信度信号：

**正向关键词：**
```
喜欢|好喜欢|好看|好可爱|好香|想买|想要|种草|心动|一直想
舍不得|有点贵|以后买|这个不错|适合我|给你看这个
```

**负向关键词：**
```
不喜欢|不好看|好丑|太土|显老|太甜|太冲|太成熟
不适合我|不要买|用不上|浪费钱|已经有了|买过了|后悔
```

**关键：** 不是只看关键词，而是看关键词前后 5-15 条消息的上下文。

例如：
```
她："这个 APM 好好看"
你："买啊"
她："算了八百多舍不得"
```
→ 极强信号：desire=high, price_barrier=true, gift_signal=very_high

### Phase 4: LLM 结构化抽取

对规则无法覆盖的隐含偏好，用 LLM + JSON Schema 抽取：

**输入：** 一个 session 的聊天记录
**输出：** 结构化 JSON

```json
{
  "aesthetic_appreciation": true,
  "personal_preference": false,
  "purchase_intent": "low",
  "gift_recommendation": "avoid",
  "reasoning": "她觉得好看但明确表示跟自己不搭"
}
```

**重要：** LLM 做语义解析器，不做人格判断器。

### Phase 5: 证据聚合

每条偏好必须附带证据：

```json
{
  "preference_id": "pref_0042",
  "category": "jewelry",
  "object": "银色细项链",
  "polarity": "like",
  "strength": 0.89,
  "status": "active",
  "inference_level": "behavioral_inference",
  "evidence": [
    {
      "message_id": "wx_a71",
      "timestamp": "2026-05-13T22:16:32+08:00",
      "evidence_type": "explicit_statement",
      "quote": "这个银色的好好看",
      "support": 0.95
    },
    {
      "message_id": "wx_b82",
      "timestamp": "2026-06-07T17:52:11+08:00",
      "evidence_type": "shared_product",
      "quote": "这个也好看诶",
      "support": 0.82
    }
  ],
  "counter_evidence": [],
  "confidence": {
    "raw_score": 0.88,
    "calibrated_probability": 0.83,
    "evidence_count": 5,
    "explicit_count": 2,
    "contradiction_count": 0
  }
}
```

### Phase 6: 冲突检测

不要 overwrite 旧偏好，保留矛盾：

```
喜欢甜香 → 2024
不喜欢太甜 → 2026
```

两个事件都保留，由 profile 层计算 `current_preference`（新证据权重更高）。

### Phase 7: 置信度校准

**不能直接用"LLM 自己说 90%"。**

置信度公式：
```
confidence = w1·E_type + w2·E_consistency + w3·E_repetition + w4·E_recency + w5·E_behavior + w6·M_trust - w7·C_penalty
```

其中：
- E_type：证据类型强度（明确原话最高）
- E_consistency：多条证据一致性
- E_repetition：独立重复次数
- E_recency：时间新鲜度
- E_behavior：行为证据（实际购买/使用）
- M_trust：模型抽取可信评分（小权重）
- C_penalty：反证和矛盾惩罚

### Phase 8: 礼物排序

```
score = W·explicit_want + R·repetition + T·recency + B·behavior + F·style_fit + N·novelty - P·risk
```

输出格式：

```
证据强度排行榜
1. 首饰 / 项链                92%
   明确正向 6 次   主动分享 4 次
   价格犹豫 2 次   负向证据 0 次

2. 香水                      67%
   正向 3 次   但没有明确想买
   对甜香存在负向证据

3. 毛绒玩具                  43%
   经常说可爱   但没有购买或想拥有证据
```

---

## 输出格式

### 偏好画像报告

```markdown
# 👤 偏好画像报告

## 💚 喜好清单

### 首饰类
| 偏好 | 置信度 | 证据 | 最近提及 |
|------|--------|------|----------|
| 银色、小巧、简约 | 86% | "这个银色的好看" / 分享2次 | 4月5日 |

## ❤️ 避雷清单
- 金色粗首饰（"金色显老"）
- 浓甜香水（明确不喜欢）
- 大型毛绒玩具（吐槽过占地方）

## 🎁 礼物推荐 Top 5
1. **简约银色细项链** (300-500元) — 置信度86%
   证据：3月分享银色锁骨链截图 + 4月说"金色显老"
2. **清新花香小众香水** (200-400元) — 置信度71%
   证据：分享过小众香水品牌

## 🧠 行为模式
- 被敷衍时 → 回复变短 → 等对方主动发现
- 收到实用礼物 → 表面一般 → 后续频繁使用

## ⚠️ 敏感点
- 重视仪式感（纪念日、特殊日子）
- 不喜欢被敷衍
- 讨厌被忽视

## 📊 证据统计
- 提取事实：[N]条
- 偏好类别：[N]类
- 最高置信度：[描述] (XX%)
- 最低置信度：[描述] (XX%)，建议补充更多聊天记录
```

---

## 推荐技术栈（轻量本地版）

- Python + Pydantic
- regex / spaCy Matcher（显式规则）
- 本地 LLM + Ollama structured output（隐式抽取）
- SQLite（证据存储）
- Streamlit（UI）

### 开发时间线

| 周次 | 工作 |
|------|------|
| 第1周 | parser → JSONL → 脱敏 → session切分 → 显式规则 → LLM schema |
| 第2周 | evidence表 → 冲突聚合 → confidence → 礼物排序 → UI → 测试 |

---

## 重要原则

1. **事实 ≠ 推断 ≠ 猜测** — 每个判断必须附带证据和置信度
2. **不确定就标不确定** — 不要强行给出结论
3. **证据分级：**
   - explicit_fact (0.9+) — 她自己说的
   - direct_behavior (0.7-0.9) — 实际做过
   - behavioral_inference (0.5-0.7) — 多条行为归纳
   - weak_inference (<0.5) — 证据不足
4. **时效性** — 最近的证据权重更高
5. **礼物推荐必须附聊天证据**，不能凭空猜
6. **反证保留** — 不要删除矛盾证据，保留后由系统计算当前偏好

---

## 扩展能力

- RAG 版本：BGE embedding + Chroma + reranker，支持"她以前说过什么"检索
- 增量更新：新聊天记录追加后自动更新画像
- 多人画像：对比不同人的偏好差异
- Persona Skill：生成模拟对方说话风格的 AI persona

---

## 参考项目

| 项目 | 借鉴点 |
|------|--------|
| person-behavior-analysis-skill | 事实/推断/猜测分离；反证；置信度 |
| SoulCraft | if-then 情境模式；证据等级 |
| Companion-AI | likes/dislikes/habits/triggers JSON 抽取 |
| relation-agent | session → BGE → Chroma → rerank；增量架构 |
| me.skill | 多格式导入；增量更新；版本管理 |
| immortal-skill | 证据等级(verbatim/artifact/impression)；矛盾保留 |
