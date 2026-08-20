# 🎭 Roleplay Engine — Java

> 一个可运行的 AI 多角色互动引擎：让多个 AI 角色在 2D 空间中移动、感知、对话，并支持自由角色扮演、狼人杀和剧本杀完整流程。

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=springboot" alt="Spring Boot 3.4" />
  <img src="https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=111827" alt="React 19" />
  <img src="https://img.shields.io/badge/Phaser-3.90-4D9DE0" alt="Phaser 3.90" />
  <img src="https://img.shields.io/badge/tests-985%20passing-2EA44F" alt="985 tests passing" />
  <a href="https://github.com/shuweiran/roleplay-java/actions/workflows/ci.yml"><img src="https://github.com/shuweiran/roleplay-java/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
</p>

如果这个项目对你有帮助，欢迎点一个 **Star** ⭐。你也可以直接打开 Issue 分享一个角色、场景或剧本杀玩法想法。

一般模式现在包含“晨雾镇 · AI 社会实验”入口：通过一般模式 Phaser 2D 主链路加载 8 个角色与专用 96×64 室外城镇地图（草地、道路、河流、建筑碰撞），角色位置、移动和对话均来自后端 SimulationService。地图只展示空间与会话组，点击并加入某一组后才显示右侧该组的对话与发言区；预览环境同样转发真实 API。

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/shuweiran/roleplay-java)

## 运行画面

下面是一次真实剧本杀流程：角色选择 → 搜证 → 讨论 → 投票 → 揭晓 → 结束。

| 角色选择 | 搜证 | 讨论 |
|---|---|---|
| ![角色选择](work/full_play/01-role-select.png) | ![搜证阶段](work/full_play/02-invest.png) | ![讨论阶段](work/full_play/04-discuss.png) |

| 投票 | 揭晓 | 结束 |
|---|---|---|
| ![投票阶段](work/full_play/05-vote.png) | ![揭晓阶段](work/full_play/07-reveal.png) | ![结束状态](work/full_play/08-ended.png) |

## 5 分钟运行

### 前置条件

- JDK 21+
- Maven 3.9+
- Node.js 20+（需要重新构建前端时）
- 一个 OpenAI 兼容的 LLM API Key；默认配置使用 DeepSeek

### 1. 配置 LLM Key

PowerShell：

```powershell
$env:ROLEPLAY_LLM_API_KEY = "你的 API Key"
```

Bash：

```bash
export ROLEPLAY_LLM_API_KEY="你的 API Key"
```

### 2. 构建并启动后端

```bash
mvn -q package -DskipTests
java -jar target/roleplay-engine-1.0.0-SNAPSHOT.jar
```

浏览器打开：**http://localhost:8000**

### 2.1 Docker 一键启动（推荐体验）

安装 Docker Desktop 后：

```bash
docker compose up --build
```

PowerShell 配置 API Key：

```powershell
$env:ROLEPLAY_LLM_API_KEY = "你的 API Key"
docker compose up --build
```

容器会自动构建 React 前端和 Java 后端，持久化 H2 数据到 `roleplay-data` 卷；浏览器仍访问 **http://localhost:8000**。

### 2.2 部署公开 Demo

点击上方 **Deploy to Render** 按钮，或在 Render 中从仓库导入 `render.yaml`。首次部署时填入 `ROLEPLAY_LLM_API_KEY`，Render 会自动构建 Docker 镜像并提供公开 `onrender.com` 地址。

> Render 免费实例可能会休眠，适合演示和分享；正式长期 Demo 建议使用不会休眠的实例，并设置 API 用量和访问保护。

### 3. 修改前端（可选）

```bash
cd roleplay-v4/frontend
npm ci
npm run build
```

开发模式：

```bash
npm run dev
```

### 4. 验证项目

```bash
# 后端全量测试：985 tests / 0 failures / 0 errors
mvn -q test

# 前端 TypeScript + Vite 生产构建
cd roleplay-v4/frontend
npm run build
```

> 如果你是在本项目维护环境中操作，请先阅读 [`AGENTS.md`](AGENTS.md)；8000 端口可能已有运行实例，按项目协作规则不要重复启动服务。

## 它能做什么？

- **AI 角色聊天**：多个角色拥有独立人格、记忆、目标和情绪，可进行自由对话与私聊。
- **铁轨系统（Track System）**：根据上下文可见性在 `MERGED`、`WEAK`、`ISOLATED` 三种轨道间调度，控制角色之间能听见什么、看见什么。
- **2D 互动世界**：角色移动、A* 寻路、碰撞、听觉范围、障碍物、地图热点和 Phaser 渲染。
- **剧本杀**：LLM 生成剧本 → 秘密分发 → 搜证 → 讨论 → 投票 → 揭晓 → 结束；支持断线恢复和主持人（DM）面板。
- **狼人杀**：昼夜循环、角色技能、投票、胜负判定和玩家视角脱敏。
- **AI 语音**：支持 TTS、角色声线配置和局内消息朗读。
- **事件驱动**：REST + SSE 推送状态、角色发言、剧本阶段和全局广播。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 3.4、Spring MVC、SSE、Spring Data JPA |
| 前端 | React 19、TypeScript、Vite、Zustand、Phaser 3.90 |
| 数据库 | H2（开发/测试） |
| AI | OpenAI 兼容 API；默认 DeepSeek；地图生成可使用 MiMo 通道 |
| 测试 | JUnit 5、Mockito、AssertJ、Spring Boot Test、RANDOM_PORT |

## 项目结构

```text
src/main/java/                 Java 后端、游戏规则、AI 编排、REST/SSE API
src/test/java/                 后端单元测试与集成测试
roleplay-v4/frontend/src/      React 前端与 Phaser 2D 视图
src/main/resources/static/     Spring Boot 提供的前端静态产物
docs/                          架构契约、测试方案、问题清单和变更记录
work/full_play/                本 README 使用的真实流程截图
```

## 当前状态

- 后端全量测试：**985 passed**
- 前端生产构建：**通过，Vite 143 modules**
- 剧本杀流程：SETUP → INVESTIGATION → DISCUSSION → VOTE → REVEAL → ENDED
- 当前没有公开在线 Demo；本地启动后访问 `http://localhost:8000`

详细架构和阶段状态见 [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md)，测试台账见 [`TEST_STATUS.md`](TEST_STATUS.md)。

## GitHub 信息

仓库地址：[github.com/shuweiran/roleplay-java](https://github.com/shuweiran/roleplay-java)

推荐在 GitHub 仓库设置以下 Topics，帮助 AI Agent、角色扮演和游戏开发方向的访客发现项目：

`ai` · `llm` · `roleplay` · `multi-agent` · `chatbot` · `game-ai` · `react` · `spring-boot` · `phaser` · `story-game`

推荐 About 描述：

> AI multi-agent roleplay engine with 2D worlds, character chat, Werewolf and Script Murder game modes.

### 适合分享的项目亮点

这是一个把 **LLM 角色聊天、空间感知、2D 地图和规则游戏** 放在同一个可运行引擎里的实验项目：AI 角色不仅会回复文本，还会移动、听见附近事件、隐藏或共享上下文，并参与剧本杀的搜证、讨论、投票和揭晓。

分享时建议使用这几个关键词：`AI agents`、`roleplay`、`LLM game`、`2D world`、`script murder`、`Phaser`。

## 许可证

本项目当前处于持续开发阶段。使用、部署或二次开发前，请先确认仓库中的许可证文件和第三方模型/API 服务条款。

## 贡献与反馈

欢迎提交 Issue，最好附上：运行环境、复现步骤、后端日志、浏览器控制台错误和相关截图。涉及架构改动前，请先阅读 [`DECISION_LOG.md`](DECISION_LOG.md)。
