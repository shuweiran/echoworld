#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生命周期轮巡自测：?selftest=cycle 下所有页签 destroy/重建是否收敛"""
import re
import sys

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from self_test import dump  # noqa: E402

base = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8899"
dom = dump(base + "/index.html?selftest=cycle")

m = re.search(r'id="selftest-report"[^>]*>(.*?)</div>', dom, re.S)
print("--- lifecycle report ---")
if m:
    print(m.group(1).replace("<br>", "\n"))
else:
    print("(no report found)")
    print(dom[dom.find("selftest"):dom.find("selftest") + 500])

no_err = re.search(r'<div id="boot-errors"[^>]*></div>', dom) is not None
print("boot-errors 空（无 JS 异常）:", no_err)
sys.exit(0 if m and no_err else 1)
