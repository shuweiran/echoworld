# 🚀 QUICKSTART.md — EchoWorld 5 分钟上手（AI 接手速查）

> 定位：给新接手的 AI agent / 开发者的**最快上手路径**（≤10KB，5 分钟读完）。
> 动态事实（8000 端口/PID/测试基线/代码规模/文件清单等）一律**见 `PROJECT_CONTEXT.md`**（单一事实源，不在此复制数字，避免漂移）；文档索引见 `AGENTS.md`（不重复造表）。

## 1. 本仓库是什么

**一句话**：Java 多 Agent 角色扮演引擎——2D 空间模拟 × 铁轨系统（Track System）融合的实时社会模拟，支持狼人杀 / 剧本杀双游戏。

| 层 | 技术 |
|---|---|
| 后端 | Java 21 + Spring Boot 3.4 + Maven + Spring Data JPA + H2（生产 file / 测试 mem）+ 虚拟线程 |
| 前端 | React + TypeScript + Vite + Zustand（`roleplay-v4/frontend`，构建产物同步 `static/`）+ **Phaser 3.90 2D 渲染层**（渐进迁移已完成，D-020） |
| AI | DeepSeek API（OpenAI 兼容）；测试用 mock LLM |

## 2. 必读顺序（5 分钟进入状态）

1. **`PROJECT_CONTEXT.md`** — 项目速览（5 秒：目标/阶段/核心架构/已完成/未完成/硬性约束）
2. **`AGENTS.md`** — 协作规则与硬性约束（**优先级最高**，任何任务不得违反）
3. **`DECISION_LOG.md`** — 架构决策史（改码前查「为什么这么设计」，防推翻历史决策）
4. 按需：`docs/INDEX.md`（文档地图，先定位再深读）→ `TEST_STATUS.md` / `docs/问题清单-20260731.md` / `docs/剧本杀差距分析-待办.md`

> ⚠️ 按 `AGENTS.md` 硬性门禁：**改任何文件前**，还有第 0 步——先读 `docs/并行作业登记.md`（并行占用检查）。
> ⚠️ >30KB 大文件（README/TEST_STATUS/DECISION_LOG/修改记录/测试方案 v2 等）文件头有提示行，**只按需搜索读取，勿整体加载**。

## 3. 怎么跑

⚠️ **8000 端口有运行中后端（实例状态见 `PROJECT_CONTEXT.md`「硬性约束」）——禁止 `spring-boot:run`**；测试走 RANDOM_PORT（`application-test.yml`：port=0 + H2 mem + mock LLM）隔离，不撞 8000、不污染生产库。

```bash
# 后端：编译 / 全量测试（系统 mvn 路径见 PROJECT_CONTEXT.md「硬性约束」）
& "C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd" clean compile
& "C:\Users\shuweiran\AppData\Local\maven\apache-maven-3.9.8\bin\mvn.cmd" test

# 前端（roleplay-v4/frontend/）
npm run build      # tsc -b && vite build → dist/；手动同步产物到 src/main/resources/static/（改前端后需同步，8000 实例重启才生效）
npm run dev        # 5173 端口，自动代理 /api → localhost:8000（改前端联调用）
```

- 后端真机验证需：`mvn package` 重新打包 → 停旧 8000 实例 → `java -jar target/roleplay-engine-1.0.0-SNAPSHOT.jar` 重启（操作前先看并行作业登记，别撞别的批次）。
- LLM Key：环境变量 `ROLEPLAY_LLM_API_KEY`，启动自动绑定（D25 修复后无需写死 yml）。

## 4. 怎么改

| 步骤 | 动作 |
|---|---|
| 改前 | 读 `docs/并行作业登记.md`（目标文件被占用先协调；新批次登记 `P-<MMDD>-<序号>`） |
| 改前 | 查 `DECISION_LOG.md` 相关决策（防推翻历史设计） |
| 改中 | 不动 `RouterService`/`ArbiterService`/审批/狼人杀/SSE 主链路/`static/`（除非任务明确要求）；不删 GroupStrategy/DebateStrategy |
| 改后 | **必须登记** `docs/修改记录.md`（修改人/时间/文件/内容摘要/核查状态） |
| 禁 | 自行 `git commit`（统一交主会话核查后提交，需主人授权） |

## 5. 怎么测

1. 后端：`mvn test`（RANDOM_PORT + H2 mem + mock LLM，零成本可重复；测试基线见 `PROJECT_CONTEXT.md` / `TEST_STATUS.md`）
2. **测试后必须更新 `TEST_STATUS.md`**：追加执行历史 + 更新汇总；**失败也如实写**（含原因）
3. 前端自测脚本（`roleplay-v4/frontend/tools/`，Edge headless 冒烟，ALL PASS）：
   - `python tools/self_test_stage1.py` — Phaser 阶段1 2D 模拟冒烟
   - `python tools/self_test_stage2.py` — Phaser 阶段2 地图渲染冒烟
4. 环境备注：vite dev 需 `--host 127.0.0.1`；Edge headless 需 `--no-proxy-server`（本机代理对回环 502）

## 6. 遇到问题去哪查

| 问题 | 去哪查 |
|---|---|
| 为什么这么设计 / 改码犹豫 | `DECISION_LOG.md`（架构决策史，按需搜索） |
| 已知缺陷 | `docs/问题清单-20260731.md`（A-G 全量 + H 文档对照表） |
| 谁改过什么 / 功能历史 | `docs/修改记录.md`（台账）+ git log |
| 测试现状 / 历史基线 | `TEST_STATUS.md` |
| 文档定位 | `docs/INDEX.md`（唯一文档地图） |
| 剧本杀 | `docs/剧本杀差距分析-待办.md`（蓝图 v3）+ `docs/剧本杀调研报告-raw.md` |
| Phaser / 2D 渲染 | `docs/Phaser迁移计划.md` + `docs/地图JSON契约-v1.md` |
| 旧方案考古 | `docs/archive/`（已废弃，**勿读**，仅考古） |

## 7. 文档索引

完整文档索引表（文件名/用途/规模/何时读）见 **`AGENTS.md`「📚 文档索引」** 章节，此处不重复造表。

## 8. 红线清单（违反即失败）

- ❌ `spring-boot:run`（8000 端口被运行实例占用）
- ❌ 自行 `git commit`
- ❌ 改码不登记 `docs/修改记录.md` / 测试不更新 `TEST_STATUS.md`
- ❌ PowerShell 发中文 JSON（GBK 乱码）→ 一律用 Python（UTF-8 显式读写）
- ❌ 整读 >30KB 大文件 / 读 `docs/archive/`
- ❌ 编造测试结果或指标（数据不可用 → 如实报错登记）
