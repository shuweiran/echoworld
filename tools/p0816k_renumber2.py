# -*- coding: utf-8 -*-
"""P-0816-K 部署子任务：TEST_STATUS 我的部署区块 v119->v120（与并行 coder 续作 v119 撞号，顺延）"""
import io

p2 = r'D:\echoworld\TEST_STATUS.md'
with io.open(p2, 'r', encoding='utf-8') as f:
    ts = f.read()

# 我的部署区块标题（主会话已把 P-0816-K 改名为 P-0816-L，用当前实际文本匹配）
old_h = '## Round 119 / v119（2026-08-16 18:33-18:5x）— **P-0816-L：剧本杀 UI 重设计阶段一打包上线'
new_h = '## Round 120 / v120（2026-08-16 18:33-18:5x）— **P-0816-L（部署）：剧本杀 UI 重设计阶段一打包上线'
if old_h in ts:
    ts = ts.replace(old_h, new_h, 1)
    print('header renamed to v120')
else:
    print('WARN header not found:', old_h[:60])

# 我的区块说明行（顺延说明）
old_note = '修改记录 #225 已被 P-0816-J coder 占用，本批登记 #226；未 git commit（统一 gate）'
new_note = '修改记录 #225 已被 P-0816-J coder 占用、#226 已被并行 coder 续作批次占用，本批登记 #227；本行 TEST_STATUS v120（并行 coder 续作批次登记 v119 撞号，顺延）；未 git commit（统一 gate）'
if old_note in ts:
    ts = ts.replace(old_note, new_note, 1)
    print('note updated')
else:
    print('WARN note not found')

with io.open(p2, 'w', encoding='utf-8') as f:
    f.write(ts)
print('TEST_STATUS.md saved')
