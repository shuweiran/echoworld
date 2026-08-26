# -*- coding: utf-8 -*-
"""P-0816-K 部署子任务：追加台账行（修改记录 #226 + TEST_STATUS v119 + 并行作业登记）"""
import io

# ---------- 1) docs/修改记录.md 追加 #226 ----------
row_226 = (
    "| 226 | 2026-08-16 18:33-18:5x | 未为-worker（**P-0816-K 部署子任务：剧本杀 UI 重设计阶段一（后端 MVP 接口包 P-0816-G + 前端渐进嵌入包 1/2 P-0816-H/I + 并行 P-0816-J 剧本选择页删除按钮）打包上线**；代码由 coder 批次就绪，本批只验证+部署；后端/前端源码零改动） | "
    "target/roleplay-engine-1.0.0-SNAPSHOT.jar（重建 18:36:14，232,288,758B，SHA256 891D13BF...）；target/roleplay-engine-p0816k-backup.jar（旧 jar 备份，232,270,517B，SHA256 CA4F3124...）；"
    "src/main/resources/static（同步 dist 三件：assets/index-BngTj9NR.js + assets/index-Dzao9dZU.css + index.html 引用切换，删旧 index-CmeziCCo.js/index-Djk9uJBM.css，SHA256 dist↔static 3/3 MATCH：JS 4B65282E.../CSS BF14B1E3.../HTML E74EECB3...）；"
    "target/classes/static（整目录删除由 mvn 重建，旧哈希残留全清）；8000 端口重启（18072→25784）；logs/p0816k_stdout.log + p0816k_stderr.log（新增）；docs：修改记录.md（本行 #226，注：任务书指定 #225 已被 P-0816-J coder 批次占用，按并行纪律顺延）、TEST_STATUS.md（Round 119/v119）、并行作业登记.md（P-0816-K 行） | "
    "①**停旧进程**：确认 PID 18072（java -jar target\\roleplay-engine-1.0.0-SNAPSHOT.jar，11:52 起）→ Stop-Process -Force，停机后无残留 java 进程；②**备份**：旧 jar 232,270,517B @ 01:38:31 → roleplay-engine-p0816k-backup.jar（SHA256 CA4F31248FFC04EEA9C353BC7C033C70B458591DD059010D6828FFD251D1AA15）；③**前端同步**（对齐历史惯例先同步再打包，保证 jar 内为新产物）：dist（18:04 构建，P-0816-H/I/J 合并产物）→ static 三件 SHA256 3/3 MATCH + index.html 引用切至 index-BngTj9NR.js/index-Dzao9dZU.css + 删旧 CmeziCCo.js/Djk9uJBM.css；CHARACTER_ANIMATION/SCENE_TILESET 子目录未触碰；④**打包**：mvn package -DskipTests BUILD SUCCESS 30.4s（18:36:14 完成）→ 新 jar 232,288,758B；jar tf 实证 assets 仅 2 新产物无旧哈希；javap 实证 ScriptController 含 P-0816-G 新端点方法（actions/action/voteStatus/goal）；⑤**启动**：Start-Process java -jar 隐藏窗口 → 新 PID **25784**（18:36:51 启动，Tomcat 18:37:07 就绪 16.5s，stderr 空 0 ERROR）；⑥**验证**：/ 200 引用新 bundle、index-BngTj9NR.js 200 text/javascript 2,066,273B、index-Dzao9dZU.css 200 text/css 121,474B、**/api/script/goal 200 {\"error\":\"缺少 session_id\"}（端点已注册，参数校验正常）**、**/api/script/actions 200 同**、**/api/script/vote/status 200 同**、/api/mode 200；旧哈希 index-CmeziCCo.js 200 但 text/html 464B（SPA fallback 非 JS，jar 已删）；stdout 0 ERROR/0 Exception。未 git commit（统一 gate）。遗留：任务书步骤 2/3 顺序调整（先同步 static 再打包，否则 jar 内为旧前端——历史批次 #214/#217 同款惯例）；#225 已由 P-0816-J 占用故本行 #226，请主会话归并 | 待核查 | 待主会话核查 |\n"
)
with io.open(r"D:\echoworld\docs\修改记录.md", "a", encoding="utf-8") as f:
    f.write(row_226)
print("修改记录.md #226 appended")

# ---------- 2) TEST_STATUS.md 追加 v119 ----------
with io.open(r"D:\echoworld\TEST_STATUS.md", "r", encoding="utf-8") as f:
    ts = f.read()
# 在文件末尾追加 v119 区块（对齐既有 Round 区块格式）
v119 = """
## Round 119 / v119（2026-08-16 18:33-18:5x）— **P-0816-K：剧本杀 UI 重设计阶段一打包上线（后端 MVP 接口包 P-0816-G + 前端渐进嵌入包 1/2 P-0816-H/I + P-0816-J 剧本选择页删除按钮）**，部署子任务（未为-worker）
- **部署内容**：①后端 P-0816-G 新端点 4 个（GET /api/script/actions、POST /api/script/action、GET /api/script/vote/status（+POST /api/script/vote abstain 扩展）、GET /api/script/goal）+ SSE 2 事件（script_vote_progress/script_goal）随新 jar 上线；②前端 dist 合并产物（P-0816-H 三栏布局+投票页替换+目标徽章 / P-0816-I 搜证页+讨论页 VN 化+心锁壳+证据检索 / P-0816-J 剧本卡删除按钮）同步 static 并打进 jar
- **执行**：停旧 PID 18072 → 备份旧 jar（roleplay-engine-p0816k-backup.jar，232,270,517B）→ 同步 static 三件（SHA256 dist↔static 3/3 MATCH：JS 4B65282EE542.../CSS BF14B1E300D7.../HTML E74EECB33134...，删旧 index-CmeziCCo.js/index-Djk9uJBM.css）→ 清 target/classes/static 残留 → mvn package -DskipTests BUILD SUCCESS 30.4s → 新 jar 232,288,758B @ 18:36:14（jar tf 仅 2 新产物，javap 实证 actions/action/voteStatus/goal 方法在位）→ 启动新 PID **25784**（Tomcat 16.5s 就绪，stderr 空）
- **验证结果（HTTP 实测）**：/ 200 引用 index-BngTj9NR.js + index-Dzao9dZU.css；新 JS 200 text/javascript 2,066,273B；新 CSS 200 text/css 121,474B；**/api/script/goal 200（body {\"error\":\"缺少 session_id\"}——端点已注册、参数校验正常，非 404）**；**/api/script/actions 200 同**；**/api/script/vote/status 200 同**；/api/mode 200；旧哈希 index-CmeziCCo.js 200 但 text/html 464B（SPA fallback，jar 已删非真实 JS）；stdout 0 ERROR/0 Exception
- **说明**：部署顺序按历史惯例调整为 先同步 static 再 mvn package（保证 jar 内为新前端）；修改记录 #225 已被 P-0816-J coder 占用，本批登记 #226；未 git commit（统一 gate）
"""
with io.open(r"D:\echoworld\TEST_STATUS.md", "a", encoding="utf-8") as f:
    f.write(v119)
print("TEST_STATUS.md v119 appended")

# ---------- 3) docs/并行作业登记.md 当日区块追加 P-0816-K 行 ----------
row_k = (
    "| P-0816-K（本批·部署子任务） | worker subagent（**P-0816-K 部署：剧本杀 UI 重设计阶段一（P-0816-G 后端 MVP 接口包 + P-0816-H/I 前端渐进嵌入包 1/2 + P-0816-J 剧本选择页删除按钮）打包上线**；按 #214/#217 教训先停旧→备份→同步 static→清 target/classes→打包→启动→验证） | "
    "①停旧 PID 18072（java -jar target jar，11:52 起）→ Stop-Process -Force，无残留 java；②旧 jar 232,270,517B → 备份 roleplay-engine-p0816k-backup.jar（SHA256 CA4F3124...）；③同步 static（dist 18:04 合并产物）：index-BngTj9NR.js/index-Dzao9dZU.css/index.html 三件 SHA256 dist↔static 3/3 MATCH，删旧 index-CmeziCCo.js/index-Djk9uJBM.css，CHARACTER_ANIMATION/SCENE_TILESET 未动；④整删 target/classes/static（旧哈希残留全清）→ mvn package -DskipTests BUILD SUCCESS 30.4s → 新 jar 232,288,758B @ 18:36:14（jar tf 仅 2 新资产 + javap 实证 actions/action/voteStatus/goal 新端点方法）；⑤Start-Process java -jar 隐藏 → 新 PID 25784（18:36:51 启动，Tomcat 18:37:07 就绪 16.5s）；⑥验证：/ 200 新 bundle、JS/CSS 200、**/api/script/goal 200（{\"error\":\"缺少 session_id\"} 端点已注册）**、/api/script/actions 200 同、/api/script/vote/status 200 同、/api/mode 200；旧哈希 SPA fallback 464B；stdout 0 ERROR；stderr 空；未 git commit | "
    "18:33-18:5x | static/（同步 3 件+删旧 2 件）、target/roleplay-engine-1.0.0-SNAPSHOT.jar（重建 18:36:14）+ roleplay-engine-p0816k-backup.jar（新增备份）、8000 重启（18072→25784）、logs/p0816k_stdout.log + p0816k_stderr.log（新增）；docs：修改记录.md（#226，注：任务书指定 #225 已被 P-0816-J coder 占用顺延）、TEST_STATUS.md（Round 119/v119）、本行 | 待主会话核查 |\n"
)
with io.open(r"D:\echoworld\docs\并行作业登记.md", "r", encoding="utf-8") as f:
    reg = f.read()
# 在 "## 当日作业（2026-08-16）" 区块标题行之后插入（该区块开头第一行表格首行之前）
anchor = "## 当日作业（2026-08-16）\n"
assert anchor in reg, "anchor not found in 并行作业登记.md"
reg = reg.replace(anchor, anchor + row_k, 1)
with io.open(r"D:\echoworld\docs\并行作业登记.md", "w", encoding="utf-8") as f:
    f.write(reg)
print("并行作业登记.md P-0816-K row inserted")
print("ALL LEDGER UPDATES DONE")
