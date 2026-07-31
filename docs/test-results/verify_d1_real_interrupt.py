# -*- coding: utf-8 -*-
"""D1 实测：在 agent 任务 RUNNING 窗口内发起真实中断"""
import json, time, urllib.request, urllib.error, threading, sys

BASE = "http://127.0.0.1:8000"
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

def http(method, path, payload=None, timeout=90):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:
        return -1, f"EXC:{type(e).__name__}:{e}"

def tasks_dialogue():
    sc, body = http("GET", "/api/interrupt/tasks?type=dialogue", timeout=10)
    try:
        j = json.loads(body)
        return [(t["id"], t["status"], t.get("stop_type"), (t.get("reason") or "")[:60]) for t in j.get("tasks", [])]
    except Exception:
        return body

sc, body = http("POST", "/api/init", {"characters": [{"name": "中断靶子", "persona": "靶子", "voice": "", "background": ""}], "scene": "默认场景", "mode": "free"})
print("[init]", sc, body[:100])

send_out = {}
def do_send():
    send_out["result"] = http("POST", "/api/send", {"message": "开始生成，等待被打断"})

t = threading.Thread(target=do_send)
t0 = time.time()
t.start()

# 在 agent RUNNING 窗口内中断（arbiter 阶段 ~8.6s，agent 阶段 ~+8.7~9.8s）
time.sleep(9.0)
sc, body, = http("POST", "/api/interrupt", {"stop_type": "hard", "reason": "运行时验证-真实中断"})
elapsed = time.time() - t0
print(f"[interrupt @{elapsed:.2f}s] {sc} {body[:300]}")
t.join(timeout=90)
sc2, body2 = send_out.get("result", ("?", "not finished"))
print(f"[send] {sc2} ({time.time()-t0:.2f}s) {body2[:250]}")

print("[tasks now]")
for row in tasks_dialogue():
    print("   ", row)
