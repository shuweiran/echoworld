# -*- coding: utf-8 -*-
"""多会话隔离 + turns 验证（最终版）"""
import json
import sys
import time
import urllib.request
import urllib.parse

BASE = "http://127.0.0.1:8000"

def get_state(sid):
    url = BASE + "/api/state?session_id=" + sid
    with urllib.request.urlopen(url, timeout=10) as r:
        return json.loads(r.read())

def http(method, path, body=None, timeout=240):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = json.loads(r.read())
    return raw, time.time() - t0

def main():
    sys.stdout.reconfigure(encoding="utf-8")
    # 多会话隔离
    sa = get_state("d6a0c7e1-901")
    sb = get_state("51685213-23a")
    a_names = sa.get("agent_names") or []
    b_names = sb.get("agent_names") or []
    print("A agents:", a_names, "| A 场景:", sa.get("scene"))
    print("B agents:", b_names, "| B 场景:", sb.get("scene"))
    isolated = a_names != b_names and "剑客甲" in a_names and "少女乙" in b_names
    print("多会话隔离:", "PASS" if isolated else "FAIL")
    # turns=3
    dt, dur = http("POST", "/api/round/start", {"session_id": "d6a0c7e1-901", "turns": 3})
    print(f"turns=3: rounds={dt.get('rounds')} stop_reason={dt.get('stop_reason')} 耗时 {dur:.0f}s")
    ok_turns = dt.get("rounds") == 3 and dt.get("stop_reason") == "completed"
    print("turns 参数:", "PASS" if ok_turns else "FAIL")
    # turns=5 中途 stop（轮间）
    dt2, _ = http("POST", "/api/round/start", {"session_id": "d6a0c7e1-901", "turns": 5})
    print(f"turns=5 完整跑: rounds={dt2.get('rounds')} stop_reason={dt2.get('stop_reason')}")
    print("结论: 多会话隔离=" + ("PASS" if isolated else "FAIL") + " turns=" + ("PASS" if ok_turns else "FAIL"))

if __name__ == "__main__":
    main()
