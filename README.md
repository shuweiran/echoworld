# 💝 Chat Profile Miner

从聊天记录中挖掘对方的喜好、厌恶和礼物信号，输出结构化偏好画像。

核心原则：**事实 ≠ 推断 ≠ 猜测**，所有结论必须绑定原聊天证据。

## ✨ 功能特点

- 📱 **多平台导入**：微信/QQ/抖音/Telegram/纯文本/WeFlow
- 🔍 **规则提取**：正向/负向关键词、价格犹豫、分享信号
- 🤖 **LLM 抽取**：隐含偏好、行为模式、礼物信号
- 📋 **证据系统**：每条结论绑定原聊天证据
- ⚔️ **冲突检测**：识别偏好变化（去年喜欢 vs 现在不喜欢）
- 📊 **置信度校准**：基于证据数量和质量
- 🎁 **礼物排序**：综合偏好强度、时效性、风险
- 🖥️ **Streamlit UI**：可视化分析界面

## 🚀 快速开始

### 安装依赖

```bash
pip install -r requirements.txt
```

### 命令行使用

```python
from pipeline import ProfilePipeline

pipeline = ProfilePipeline(
    target_name="春杪",
    self_name="我",
    use_llm=True,
    llm_api_key="your-api-key"
)

# 从文件
profile = pipeline.run_from_file("chat.txt", platform="wechat")

# 从文本
profile = pipeline.run_from_text("我: 这个好看吗\n她: 好好看！")

# 生成报告
report = pipeline.generate_report(profile)
print(report)
```

### Streamlit UI

```bash
streamlit run src/app.py
```

## 📁 项目结构

```
chat-profile-miner/
├── SKILL.md                    # 技能文档
├── README.md                   # 项目说明
├── requirements.txt            # 依赖
├── .gitignore                  # Git 忽略规则
└── src/
    ├── schemas.py              # 数据模型（Pydantic）
    ├── importer.py             # 聊天记录导入器
    ├── importer_weflow.py      # WeFlow 导入器
    ├── extractor_rules.py      # 显式规则提取器
    ├── extractor_llm.py        # LLM 结构化抽取器
    ├── gift_ranker.py          # 礼物排序器
    ├── pipeline.py             # 主流程管道
    ├── app.py                  # Streamlit UI
    └── test_pipeline.py        # 测试
```

## 📊 分析结果示例

```
💚 喜好清单
- 银色, 项链 | 置信度 75% | 1 条证据 | 最近: 05-13
  > 「这个银色的项链好好看」

❤️ 避雷清单
- 金色 (65%)
  > 「金色感觉有点显老」

🎁 礼物推荐 Top 5
1. 银色项链 (300-1500元) — 匹配度 80%
   理由：有价格犹豫（想要但舍不得），明确想要
```

## 🔧 技术栈

- Python 3.10+
- Pydantic 2.0+
- DeepSeek V4 Flash (LLM)
- Streamlit (UI)
- httpx (HTTP 客户端)

## 📚 参考项目

- [person-behavior-analysis-skill](https://github.com/wangguofeng728/person-behavior-analysis-skill) - 事实/推断/猜测分离
- [SoulCraft](https://github.com/Losii-L/SoulCraft) - if-then 情境模式
- [Companion-AI](https://github.com/ayushchhipa1509/Companion-AI) - likes/dislikes JSON 抽取
- [relation-agent](https://github.com/Ephemeral6/relation-agent) - RAG 增量架构

## 📄 许可证

MIT License

## ⚠️ 免责声明

本工具仅供个人学习和研究使用。请确保您有权分析相关聊天记录，并遵守相关法律法规。请勿用于非法用途或侵犯他人隐私。
