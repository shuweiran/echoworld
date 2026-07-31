# -*- coding: utf-8 -*-
"""Observe simulation track state over ticks after setting secret agent."""
import sys
import time

import requests

BASE = "http://localhost:8000"

# ensure a secret agent is set
r = requests.post(BASE + "/api/simulation/track/secret", json={"agents": ["阿杰"]}, timeout=10)
print("set secret:", r.status_code, r.json())

for i in range(8):
    time.sleep(3)
    d = requests.get(BASE + "/api/simulation/track/state", timeout=10).json()
    print("t+%ds assignments=%s secret=%s" % ((i + 1) * 3, d.get("assignments"), d.get("secret_agents")), flush=True)
