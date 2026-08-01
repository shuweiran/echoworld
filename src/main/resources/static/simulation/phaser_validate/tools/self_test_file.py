#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""file:// 模式自测：内嵌资源（base64 data URI）兜底下所有页签是否正常 boot"""
import re
import sys

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from self_test import dump  # noqa: E402

import pathlib

INDEX = pathlib.Path(__file__).resolve().parents[1] / "index.html"
FILE_URL = "file:///" + str(INDEX).replace("\\", "/")

TABS = [
    ("tile", "", [r"stats-tile", r"老宅", r"障碍格 160", r"<canvas"]),
    ("bsp", "?tab=bsp", [r"stats-bsp", r"校验器", r"<canvas"]),
    ("zones", "?tab=zones", [r"stats-zones", r"热点数", r"<canvas"]),
    ("anim", "?tab=anim", [r"stats-anim", r"4 个动画已创建", r"<canvas"]),
    ("contract", "?tab=contract", [r"schema-table", r"contract-json"]),
]

all_ok = True
for name, qs, patterns in TABS:
    url = FILE_URL + qs
    dom = dump(url)
    ok = True
    for p in patterns:
        hit = len(re.findall(p, dom)) > 0
        ok = ok and hit
        print(f"  [{'PASS' if hit else 'FAIL'}] {p}")
    no_err = re.search(r'<div id="boot-errors"[^>]*></div>', dom) is not None
    print(f"  [{'PASS' if no_err else 'FAIL'}] boot-errors 为空")
    ok = ok and no_err
    all_ok = all_ok and ok
    print(f"RESULT {name} (file://): {'PASS' if ok else 'FAIL'}\n")

print("OVERALL (file://):", "ALL PASS" if all_ok else "FAIL")
sys.exit(0 if all_ok else 1)
