# -*- coding: utf-8 -*-
"""D1 实测：轮询检测 RUNNING 任务 → 立即按 task_id 硬中断"""
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

sc, body = http("POST", "/api/init", {"characters": [{"name": "靶子二号", "persona": "靶子", "voice": "", "background": ""}], "scene": "默认场景", "mode": "free"})
print("[init]", sc, body[:80])

send_out = {}
def do_send():
    send_out["result"] = http("POST", "/api/send", {"message": "等待被精确打断"})

t = threading.Thread(target=do_send)
t0 = time.time()
t.start()

target_id = None
deadline = time.time() + 20
while time.time() < deadline and target_id is None:
    try:
        sc, body = http("GET", "/api/interrupt/tasks?type=dialogue", timeout=5)
        j = json.loads(body)
        for tk in j.get("tasks", []):
            if tk.get("status") == "RUNNING":
                target_id = tk.get("id")
                break
    except Exception:
        pass
    if target_id is None:
        time.sleep(0.25)

if target_id:
    sc, body = http("POST", "/api/interrupt", {"task_id": target_id, "stop_type": "hard", "reason": "运行时验证-精确中断"})
    print(f"[interrupt {target_id} @{time.time()-t0:.2f}s] {sc} {body[:250]}")
else:
    print("[interrupt] NO RUNNING task detected within 20s — MISSED")

t.join(timeout=90)
sc2, body2 = send_out.get("result", ("?", "not finished"))
print(f"[send finished] {sc2} total={time.time()-t0:.2f}s body={body2[:200]}")

# 终态检查
sc, body = http("GET", "/api/interrupt/tasks?type=dialogue", timeout=10)
j = json.loads(body)
for tk in j.get("tasks", []):
    if tk.get("id") == target_id or "靶子二号" in tk.get("id", ""):
        print("[final task]", json.dumps({k: tk.get(k) for k in ("id", "status", "stop_type", "reason")}, ensure_ascii=False)[:300])
