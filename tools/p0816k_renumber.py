# -*- coding: utf-8 -*-
"""P-0816-K 部署子任务：撞号修正——部署行 #226->#227、v119->v120（并行 coder 续作批次同号）"""
import io

# ---------- 修改记录.md：我的部署行 #226 -> #227 ----------
p = r'D:\echoworld\docs\修改记录.md'
with io.open(p, 'r', encoding='utf-8') as f:
    content = f.read()

old_marker = '| 226 | 2026-08-16 18:33-18:5x | 未为-worker'
new_marker = '| 227 | 2026-08-16 18:33-18:5x | 未为-worker'
assert old_marker in content, 'my row 226 not found'
content = content.replace(old_marker, new_marker, 1)

# 行内引用修正（仅我的行内，位于该行文本中）
content = content.replace(
    '本行 #226，注：任务书指定 #225 已被 P-0816-J coder 批次占用',
    '本行 #227（注：任务书指定 #225 已被 P-0816-J coder 批次占用；并行 coder 续作批次登记 #226 撞号，本部署行顺延 #227，提请主会话归并）',
    1,
)
content = content.replace(
    '#225 已由 P-0816-J 占用故本行 #226，请主会话归并',
    '#225 已由 P-0816-J 占用、#226 已由并行 coder 续作批次占用，故本部署行 #227',
    1,
)
with io.open(p, 'w', encoding='utf-8') as f:
    f.write(content)
print('修改记录.md: deployment row renumbered to #227')

# ---------- TEST_STATUS.md：我的 v119 -> v120 ----------
p2 = r'D:\echoworld\TEST_STATUS.md'
with io.open(p2, 'r', encoding='utf-8') as f:
    ts = f.read()

old_h = '## Round 119 / v119（2026-08-16 18:33-18:5x）— **P-0816-K：剧本杀 UI 重设计阶段一打包上线'
new_h = '## Round 120 / v120（2026-08-16 18:33-18:5x）— **P-0816-K：剧本杀 UI 重设计阶段一打包上线'
assert old_h in ts, 'my v119 header not found'
ts = ts.replace(old_h, new_h, 1)
ts = ts.replace(
    '修改记录 #225 已被 P-0816-J coder 占用，本批登记 #226；未 git commit（统一 gate）',
    '修改记录 #225 已被 P-0816-J coder 占用、#226 已被并行 coder 续作批次占用，本批登记 #227；TEST_STATUS 本行 v120（并行 coder 续作批次登记 v119 撞号，顺延）；未 git commit（统一 gate）',
    1,
)
with io.open(p2, 'w', encoding='utf-8') as f:
    f.write(ts)
print('TEST_STATUS.md: deployment round renumbered to v120')
print('DONE')
