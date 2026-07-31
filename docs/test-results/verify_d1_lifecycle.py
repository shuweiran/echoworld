# -*- coding: utf-8 -*-
"""D1 实测：任务生命周期可见性（进行中/结束后）"""
import json, time, urllib.request, urllib.error, threading

BASE = "http://127.0.0.1:8000"

def http(method, path, payload=None, timeout=60):
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

def tasks_now(tag):
    sc, body = http("GET", "/api/interrupt/tasks", timeout=10)
    try:
        j = json.loads(body)
        print(f"[{tag}] count={j.get('count')} active={j.get('active')} tasks={[(t.get('id'), t.get('status')) for t in j.get('tasks', [])]}")
        return j
    except Exception:
        print(f"[{tag}] RAW {sc} {body[:200]}")
        return None

sc, body = http("POST", "/api/init", {"characters": [{"name": "观测者", "persona": "观测用", "voice": "", "background": ""}], "scene": "默认场景", "mode": "free"})
print("[init]", sc, body[:120])

send_out = {}
def do_send():
    send_out["result"] = http("POST", "/api/send", {"message": "生命周期观测"})

t = threading.Thread(target=do_send)
t.start()
for i in range(3):
    time.sleep(0.8)
    tasks_now(f"during-{i+1}")
t.join(timeout=60)
print("[send]", send_out.get("result")[0], send_out.get("result")[1][:200])
tasks_now("after-done")

# 单任务查询（若历史存在）
sc, body = http("GET", "/api/interrupt/tasks/观测者_dialogue_1")
print("[task-detail 观测者_dialogue_1]", sc, body[:300])
