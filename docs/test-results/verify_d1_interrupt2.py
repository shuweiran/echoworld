# -*- coding: utf-8 -*-
"""D1 中断系统实测（会话初始化后）：真实 send + 并发 interrupt + 状态机观察"""
import json, time, urllib.request, urllib.error, threading

BASE = "http://127.0.0.1:8000"

def http(method, path, payload=None, timeout=60):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", errors="replace"), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace"), time.time() - t0
    except Exception as e:
        return -1, f"EXC:{type(e).__name__}:{e}", time.time() - t0

# 1. 初始化会话
sc, body, dt = http("POST", "/api/init", {
    "characters": [{"name": "测试甲", "persona": "活泼开朗的测试角色", "voice": "明亮", "background": "D1验证用"}],
    "scene": "默认场景", "mode": "free"})
print(f"[init] -> {sc} ({dt:.2f}s) {body[:200]}")
session_id = json.loads(body).get("session_id", "") if sc == 200 else ""
print("session_id:", session_id)

# 2. send 并发 + 立即 interrupt（cancel all, hard）
send_out = {}
def do_send():
    send_out["result"] = http("POST", "/api/send", {"message": "你好，这是中断验证消息"}, timeout=60)

t = threading.Thread(target=do_send)
t0 = time.time()
t.start()
time.sleep(0.25)
sc, body, dt = http("POST", "/api/interrupt", {"stop_type": "hard", "reason": "运行时验证-立即中断"})
print(f"[send+interrupt] interrupt -> {sc} ({dt:.3f}s after send-start={time.time()-t0:.3f}s) {body[:300]}")
t.join(timeout=60)
sc2, body2, dt2 = send_out.get("result", ("?", "send-not-finished", 0))
print(f"[send result] -> {sc2} (took {dt2:.2f}s) {body2[:400]}")

# 3. 任务状态查询
sc, body, dt = http("GET", "/api/interrupt/tasks", timeout=10)
print(f"[tasks after] -> {sc} {body[:1000]}")
try:
    j = json.loads(body)
    tasks = j.get("tasks", [])
    print("  task count:", len(tasks), "active:", j.get("active"))
    for tk in tasks:
        print("  task:", {k: tk.get(k) for k in ("id","agent","type","status","stop_type","reason")})
except Exception as e:
    print("  parse err:", e)

# 4. 若存在任务，按 task_id 精确查询
if tasks:
    tid = tasks[0].get("id")
    sc, body, dt = http("GET", f"/api/interrupt/tasks/{tid}")
    print(f"[task detail {tid}] -> {sc} {body[:400]}")

# 5. 再发一轮 send，观察无 interrupt 时的正常错误路径（LLM 401 预期）
sc, body, dt = http("POST", "/api/send", {"message": "第二发，不打断"}, timeout=60)
print(f"[send#2 no-interrupt] -> {sc} (took {dt:.2f}s) {body[:300]}")
