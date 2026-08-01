#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阶段 0 demo 无头自测（Edge headless + dump-dom）
用法: python tools/self_test.py [base_url]
对 5 个页签各跑一次无头加载，检查 DOM 证据（scene 是否 boot、统计面板、契约页），
并检查 boot-errors 是否为空（无 JS 异常）。也支持 file:// 模式。
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

TABS = [
    ("tile", "", [
        r'stats-tile', r'老宅', r'碰撞层', r'障碍格 160',
        r'ground 行数 14', r'1 玩家 \+ 3 AI', r'<canvas',
    ]),
    ("bsp", "?tab=bsp", [
        r'stats-bsp', r'生成器', r'校验器', r'map_version 1',
        r'通过', r'json-bsp', r'<canvas',
    ]),
    ("zones", "?tab=zones", [
        r'stats-zones', r'热点数', r'互动方式', r'线索绑定', r'clue-box', r'<canvas',
    ]),
    ("anim", "?tab=anim", [
        r'stats-anim', r'4 个动画已创建', r'spritesheet', r'load\.aseprite', r'<canvas',
    ]),
    ("contract", "?tab=contract", [
        r'schema-table', r'map_version', r'val-input', r'val-result', r'contract-json',
    ]),
]


def dump(url: str) -> str:
    r = subprocess.run(
        [EDGE, "--headless=new", "--disable-gpu", "--no-first-run",
         "--virtual-time-budget=15000", "--dump-dom", url],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=60,
    )
    return r.stdout.decode("utf-8", errors="replace")


def check_no_errors(dom: str) -> bool:
    # boot-errors 容器必须存在且为空（未被 window error 监听器写入内容）
    # 注意：浏览器序列化 style 时可能保留原样（display:none）也可能归一化（display: none）
    m = re.search(r'<div id="boot-errors"[^>]*></div>', dom)
    return m is not None


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8899"
    all_ok = True
    for name, qs, patterns in TABS:
        url = base + "/index.html" + qs
        dom = dump(url)
        print(f"=== {name} ({url}) ===")
        ok = True
        for p in patterns:
            hit = len(re.findall(p, dom)) > 0
            ok = ok and hit
            print(f"  [{'PASS' if hit else 'FAIL'}] {p}")
        no_err = check_no_errors(dom)
        print(f"  [{'PASS' if no_err else 'FAIL'}] boot-errors 为空（无 JS 异常）")
        ok = ok and no_err
        all_ok = all_ok and ok
        print(f"RESULT {name}: {'PASS' if ok else 'FAIL'}\n")
    print("OVERALL:", "ALL PASS" if all_ok else "FAIL")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
