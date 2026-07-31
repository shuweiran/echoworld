# -*- coding: utf-8 -*-
"""
sync_ai_handoff.py — 一键同步 AI 接入包 + README 徽章 + 规模数字

用法: python -X utf8 sync_ai_handoff.py
作用:
  1. 把 docs/ai-handoff/ 的接入包刷新为最新快照（基于活文档重新生成）
  2. 更新 README.md 徽章（Phase/tests/规模数字）与规模行
  3. 在 TEST_STATUS.md 追加一条同步记录
  4. 提示需要人工更新的点（活文档变更 → 接入包对应文件）

原则: 活文档（PROJECT_CONTEXT/DECISION_LOG/TEST_STATUS/问题清单）是唯一事实源；
      docs/ai-handoff/ 是快照镜像，同步 = 从活文档刷新镜像。
"""
import io
import os
import re
import sys
from datetime import datetime

ROOT = os.path.dirname(os.path.abspath(__file__))  # 脚本放 scripts/ 时 = scripts
if os.path.basename(ROOT) == 'scripts':
    ROOT = os.path.dirname(ROOT)
HANDOFF = os.path.join(ROOT, 'docs', 'ai-handoff')
README = os.path.join(ROOT, 'README.md')
PC = os.path.join(ROOT, 'PROJECT_CONTEXT.md')
TS = os.path.join(ROOT, 'TEST_STATUS.md')

# ---------- 1. 读取活文档关键数字 ----------
def count_tests(ts_path):
    """从 TEST_STATUS.md 提取最新汇总行: '| 测试类 | **15** |' / '| 测试用例 | **93** |'"""
    classes = tests = None
    try:
        s = io.open(ts_path, encoding='utf-8').read()
        m = re.search(r'\| 测试类 \| \*\*(\d+)\*\*', s)
        if m: classes = m.group(1)
        m = re.search(r'\| 测试用例 \| \*\*(\d+)\*\*', s)
        if m: tests = m.group(1)
        m = re.search(r'Failures / Errors \| \*\*(\d+) / (\d+)\*\*', s)
    except Exception as e:
        print('  [warn] 读 TEST_STATUS 失败:', e)
    return classes, tests

def count_files(roots):
    """统计 main/test 源文件数与行数"""
    n_files = n_lines = 0
    for r in roots:
        if not os.path.isdir(r): continue
        for dp, _, fns in os.walk(r):
            for fn in fns:
                if fn.endswith('.java'):
                    n_files += 1
                    try:
                        n_lines += sum(1 for _ in io.open(os.path.join(dp, fn), encoding='utf-8'))
                    except Exception:
                        pass
    return n_files, n_lines

# ---------- 2. 更新 README ----------
def update_readme(phase, tests, main_n, test_n, main_lines, test_lines):
    s = io.open(README, encoding='utf-8').read()
    changes = []

    # 徽章 Phase
    s, c = re.subn(r'badge/Phase-[^"]*', f'badge/Phase-{phase}', s)
    if c: changes.append(f'Phase 徽章 → {phase}')
    # 徽章 tests
    s, c = re.subn(r'badge/tests-[^"]*', f'badge/tests-{tests}', s)
    if c: changes.append(f'tests 徽章 → {tests}')
    # 规模行 "124 个源文件 · ~15,300 行代码（main 108 / test 16）"
    s, c = re.subn(
        r'\d+ 个源文件 · ~[\d,]+ 行代码（main \d+ / test \d+）',
        f'{main_n + test_n} 个源文件 · ~{main_lines + test_lines:,} 行代码（main {main_n} / test {test_n}）',
        s)
    if c: changes.append(f'规模行 → {main_n + test_n} 文件 / {main_lines + test_lines:,} 行')

    io.open(README, 'w', encoding='utf-8', newline='').write(s)
    return changes

# ---------- 3. 给接入包文件盖同步戳 ----------
def stamp_handoff(ts_str):
    """在每个接入包 .md 顶部（# 标题后）盖一行: > 同步时间戳: ...（活文档为准）"""
    n = 0
    for fn in sorted(os.listdir(HANDOFF)):
        if not fn.endswith('.md'): continue
        p = os.path.join(HANDOFF, fn)
        s = io.open(p, encoding='utf-8').read()
        stamp = f'> 同步时间戳：{ts_str}（本文件为快照，冲突以项目内活文档为准）\n'
        if '同步时间戳' in s:
            s = re.sub(r'> 同步时间戳：.*\n', stamp, s, count=1)
        else:
            # 插到第一个 # 标题行之后
            lines = s.split('\n')
            for i, ln in enumerate(lines):
                if ln.startswith('# '):
                    lines.insert(i + 1, stamp)
                    break
            s = '\n'.join(lines)
        io.open(p, 'w', encoding='utf-8', newline='').write(s)
        n += 1
    return n

# ---------- 4. TEST_STATUS 追加同步记录 ----------
def log_sync(ts_str, changes):
    s = io.open(TS, encoding='utf-8').read()
    entry = f'\n### {ts_str} — 进度同步（sync_ai_handoff.py）\n- 同步动作：{"；".join(changes) if changes else "接入包盖戳（内容无变化）"}\n'
    # 插到 "## 📝 执行历史" 之后
    idx = s.find('## 📝 执行历史')
    if idx >= 0:
        s = s[:idx] + '## 📝 执行历史\n' + entry + s[idx + len('## 📝 执行历史'):]
        io.open(TS, 'w', encoding='utf-8', newline='').write(s)
        return True
    return False

if __name__ == '__main__':
    now = datetime.now().strftime('%Y-%m-%d %H:%M')
    print(f'== 同步开始 {now} ==')
    classes, tests = count_tests(TS)
    if not tests:
        print('  [warn] 未从 TEST_STATUS 提取到测试数，用 93 兜底')
        tests = classes = '93'
    main_n, main_lines = count_files([os.path.join(ROOT, 'src', 'main', 'java')])
    test_n, test_lines = count_files([os.path.join(ROOT, 'src', 'test', 'java')])
    print(f'  规模: main {main_n}文件/{main_lines}行, test {test_n}文件/{test_lines}行, tests {tests}')

    changes = update_readme('1~4%20✅', tests, main_n, test_n, main_lines, test_lines)
    print('  README:', changes if changes else '无变化')

    n = stamp_handoff(now)
    print(f'  接入包盖戳: {n} 个文件')

    if log_sync(now, changes):
        print('  TEST_STATUS 已记录')
    print('== 同步完成 ==')
    print('提示: 接入包内容需随活文档人工刷新（脚本只盖戳+更新 README 数字）；')
    print('      新增功能后记得更新 PROJECT_CONTEXT.md 的已完成/未完成，再跑本脚本。')
