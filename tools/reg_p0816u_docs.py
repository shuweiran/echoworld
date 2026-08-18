# -*- coding: utf-8 -*-
"""P-0816-U 登记：修改记录 #240 + TEST_STATUS Round 133/v133 + 并行作业登记行"""
import io, sys

def read(p):
    with io.open(p, encoding='utf-8') as f:
        return f.read()

def append(p, text):
    with io.open(p, 'a', encoding='utf-8') as f:
        f.write(text)
    print('appended ->', p)

row_240 = (
    "| 240 | 2026-08-16 23:0x-00:1x | 未为-coder（**P-0816-U 局内视觉对齐包：实际对局页向三张原型页「像素级」对齐"
    "（搜证·青蓝 #0ea5e9 / 讨论·暖橙 #f59e0b / 投票·红紫 #dc2626→#9333ea）**；只改前端样式与组件样式部分，功能/后端零改动，"
    "ui-proto-v2 开关保留） | 前端 roleplay-v4/frontend：新增 tools/smoke_p0816u.mjs（28 断言）；改 src/styles/global.css"
    "（尾部追加 P-0816-U 大块：三阶段 radial 氛围背景/顶栏 64px 玻璃深蓝 blur(10px)/卡片渐变+投影/阶段条连接线+30px 圆点+当前发光/"
    "VN 大演出弹层/讨论角色色气泡 color-mix/信任度·嫌疑卡·投票栏/tab/toast/FAB/窄屏适配，及修复 P-0816-M 潜伏 bug：.proto-sus.sel "
    "用未定义 --bg-2/--bg-3 导致渐变描边失效）、src/components/ChatPage/chatUtils.ts（+roleColorFor 五主角固定色，回退 colorFor）、"
    "ScriptDiscussionPanel.tsx（铭牌渐变底+气泡 --msg-c 注入 roleColorFor）、ScriptVnReveal.tsx（大头像角色渐变底）、"
    "ScriptVotePanel.tsx（嫌疑人名 roleColorFor）、ScriptInvestigationPanel.tsx（阶段横幅 h1）、ChatPage.tsx（FAB 改圆钮 📋+小字）；"
    "工具 tools/cdp_p0816u*.mjs + static_proxy_p0816u.mjs + regen_p0816u_alignment.mjs（新增）；证据 tmp/p0816u/"
    "（proto-*/real2-*/cmp-* 并排截图 + alignment.md + probe2.log）；构建 dist/assets/index-Vfzyry-l.js + index-Bca7P-_X.css（未同步 static） "
    "| ①**tokens 提取（原型唯一蓝本）**：深海军蓝底 #0c1322、面板 #141e33/#1b2944、线 rgba(255,255,255,.08)、文字层级 #e8eef9/#93a1bd/#5b6b8c、"
    "绿 #22c55e；三阶段相位色 + radial-gradient 氛围背景；顶栏 64px 玻璃深蓝 blur(10px)；卡片 12-16px 圆角渐变面板+投影；VN 弹层 660px/大头像/"
    "右上铭牌/130px 上留白；讨论气泡角色色底+左 3px 角色色边（五主角固定色 林#0ea5e9/苏#ec4899/陈#8b5cf6/顾#14b8a6/阿#f97316）；"
    "拍案震屏 bodyShake keyframes。②**逐组件对齐**：顶栏、左栏阶段条/角色列表、主区横幅/行动条/地点卡/VN 弹层/讨论流/快捷动作条/投票嫌疑卡/"
    "信任度条/拍案演出、右栏线索库/矩阵/历史、全局阶段色 tint 全部对齐（含 3 处关键差异修掉：阶段色氛围、卡片层次、发光动效；额外修复 "
    "P-0816-M 两个潜伏 bug）。③**验证（CDP 并排 diff，真实对局优先）**：8000 后端 + static_proxy_p0816u.mjs（4399 服务新 bundle 透传 /api）+ "
    "8899 原型服务；探针对比 **561 项全对齐 561/561（100%）**，实际对局三阶段 DOM 均健康（搜证 5 地点卡+7 行动条+横幅，讨论 2 消息+3 快捷钮+言弹，"
    "投票嫌疑/trust/voteBar/stat 齐全）；截图 proto-*.png + real2-*.png + cmp-*.png 存 tmp/p0816u/。④**构建**：npx tsc -b EXIT=0 + "
    "npm run build（vite 140 modules）→ dist/index-Vfzyry-l.js + index-Bca7P-_X.css；smoke_p0816u.mjs **28/28 ALL PASS**；"
    "改动文件 UTF-8 无 BOM 确认。未 git commit、未 spring-boot:run、未同步 static（待部署 worker）。**遗留**：①8000 实例仍为 P-0816-W 旧 bundle，"
    "新样式需部署后生效；②讨论阶段窗口短（LLM 快，约 3.5s 自动进投票），讨论截图采快速轮询捕捉；③投票候选 candidates 为空系后端数据条件"
    "（该局未推导出嫌疑人），非前端问题；④尾部遗留调试脚本 tools/cdp_p0816u*.mjs 可整理 | 待核查 | 待主会话核查 |"
)

test_row = (
    "## Round 133 / v133（2026-08-16 23:0x-00:1x）（**P-0816-U 局内视觉对齐包：实际对局页向三张原型页像素级对齐**，coder subagent（未为-coder））\n"
    "- **对齐内容**：从 docs/ui-prototype/investigation.html（青蓝 #0ea5e9）/ discussion.html（暖橙 #f59e0b）/ vote.html（红紫 #dc2626→#9333ea）"
    "提取 design tokens → global.css CSS 变量体系；逐组件对齐（顶栏 64px 玻璃深蓝/阶段条发光/地点卡/VN 弹层/讨论角色色气泡/嫌疑卡/信任度条/拍案震屏/"
    "右栏线索库/全局阶段色氛围）；tsx 样式微调 7 文件（roleColorFor 五主角固定色、铭牌渐变底、FAB 圆钮、阶段横幅 h1）；修复 P-0816-M 潜伏 bug 2 处"
    "（.proto-sus.sel 渐变描边失效、.proto-loc.searched 面板背景）。后端/功能零改动，ui-proto-v2 开关保留\n"
    "- **验证**：npx tsc -b EXIT=0；npm run build EXIT=0（vite 140 modules）→ dist/assets/index-Vfzyry-l.js + index-Bca7P-_X.css；"
    "tools/smoke_p0816u.mjs **28/28 ALL PASS**；CDP 并排 diff（8899 原型 + 4399 新 bundle 真实对局，8000 后端）**561 项 561/561 对齐（100%）**，"
    "三阶段实际对局截图 proto-*/real2-*/cmp-* 存 tmp/p0816u/（alignment.md 全量明细）；改动文件 UTF-8 无 BOM\n"
    "- **登记**：docs/修改记录.md **#240**（修改人：未为-coder，核查状态：待核查）；TEST_STATUS.md 本行 Round 133 / v133；并行作业登记.md P-0816-U；"
    "未 git commit（统一 gate）；未 spring-boot:run；未同步 static（待部署 worker）；待主会话核查\n"
)

par_row = (
    "| P-0816-U（本批） | coder subagent（未为-coder，**局内视觉对齐包：实际对局页向三张原型页像素级对齐（搜证·青蓝/讨论·暖橙/投票·红紫）**；"
    "只改前端样式与组件样式部分，功能/后端零改动，ui-proto-v2 开关保留）：①提取原型 design tokens → global.css（三阶段 radial 氛围背景/顶栏 64px "
    "玻璃深蓝/卡片渐变+投影/阶段条发光/VN 弹层/讨论角色色气泡 color-mix/嫌疑卡/信任度条/拍案震屏/FAB/tab/toast/窄屏适配）+ 修复 P-0816-M 潜伏 bug"
    "（.proto-sus.sel 渐变描边、.proto-loc.searched 背景）；②tsx 微调：chatUtils +roleColorFor（五主角固定色）、ScriptDiscussionPanel 铭牌渐变底+"
    "--msg-c、ScriptVnReveal 大头像渐变、ScriptVotePanel 嫌疑人名色、ScriptInvestigationPanel 横幅 h1、ChatPage FAB 圆钮；③验证：CDP 并排 diff"
    "（8899 原型 + 4399 新 bundle 真实对局 + 8000 后端）561 项 561/561 对齐，截图 tmp/p0816u/；④构建：tsc 0 + npm build 0 → dist/"
    "index-Vfzyry-l.js + index-Bca7P-_X.css + smoke 28/28 ALL PASS | 23:0x-00:1x | 前端 roleplay-v4/frontend/src/styles/global.css + "
    "components/ChatPage/（chatUtils.ts/ScriptDiscussionPanel.tsx/ScriptVnReveal.tsx/ScriptVotePanel.tsx/ScriptInvestigationPanel.tsx/ChatPage.tsx）；"
    "工具 tools/smoke_p0816u.mjs + cdp_p0816u*.mjs + static_proxy_p0816u.mjs + regen_p0816u_alignment.mjs（新增）；证据 tmp/p0816u/；"
    "docs：修改记录.md（#240）、TEST_STATUS.md（Round 133/v133）、本行 | 完成（561/561 对齐 + tsc 0 + npm build 0 + smoke 28/28 ALL PASS；"
    "未 git commit、未 spring-boot:run、未同步 static；待主会话核查） |\n"
)

append('docs/修改记录.md', '\n' + row_240)
append('TEST_STATUS.md', '\n' + test_row)

# 并行作业登记：插到「## 当日作业（2026-08-16）」小节顶部（P-0816-W 行之前）
p = 'docs/并行作业登记.md'
content = read(p)
anchor = '| P-0816-W 部署（本批） |'
assert anchor in content, 'anchor not found'
content = content.replace(anchor, par_row + anchor, 1)
with io.open(p, 'w', encoding='utf-8') as f:
    f.write(content)
print('inserted ->', p)

# 校验
md = read('docs/修改记录.md')
print('修改记录 last row #:', md.splitlines()[-1][:6])
ts = read('TEST_STATUS.md')
print('TEST_STATUS last round:', ts.splitlines()[-1][:60])
par = read(p)
print('并行登记 P-0816-U rows:', par.count('P-0816-U（本批）'))
