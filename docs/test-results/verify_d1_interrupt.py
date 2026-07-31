# -*- coding: utf-8 -*-
"""D1 中断系统实测：端点存在性 + 任务状态机 + stop 即时返回语义"""
import json, time, urllib.request, urllib.error, threading

BASE = "http://127.0.0.1:8000"
results = {}

def http(method, path, payload=None, timeout=30):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read().decode("utf-8", errors="replace")
            return r.status, body, time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace"), time.time() - t0
    except Exception as e:
        return -1, f"EXC:{type(e).__name__}:{e}", time.time() - t0

# ── 1. 端点存在性：GET /api/interrupt/tasks ──
sc, body, dt = http("GET", "/api/interrupt/tasks")
print(f"[1] GET /api/interrupt/tasks -> {sc} ({dt:.2f}s)")
print("    body:", body[:400])
results["tasks_endpoint"] = sc == 200 and '"tasks"' in body

# ── 2. GET /api/interrupt/tasks/{taskId}（不存在的 taskId → 404 语义）──
sc, body, dt = http("GET", "/api/interrupt/tasks/nonexistent-task-xyz")
print(f"[2] GET /api/interrupt/tasks/nonexistent -> {sc} ({dt:.2f}s)")
print("    body:", body[:200])
results["task404"] = sc == 404

# ── 3. POST /api/send 并发 + 立即 POST /api/interrupt {}（cancel all）──
send_out = {}
def do_send():
    send_out["result"] = http("POST", "/api/send", {"message": "你好，测试中断"})

t = threading.Thread(target=do_send)
t.start()
time.sleep(0.3)  # send 已进入生成流程后立即发起中断
sc, body, dt = http("POST", "/api/interrupt", {"stop_type": "hard", "reason": "运行时验证-立即中断"})
print(f"[3] POST /api/interrupt (hard, cancel-all) -> {sc} ({dt:.2f}s)")
print("    body:", body[:400])
results["interrupt_immediate"] = sc == 200
t.join(timeout=30)
sc2, body2, dt2 = send_out.get("result", ("?", "send-did-not-finish", 0))
print(f"[3b] POST /api/send (parallel) -> {sc2} ({dt2:.2f}s) body: {body2[:300]}")
results["send_response"] = sc2

# ── 4. POST /api/stop 即时返回语义（不应等待 LLM）──
sc, body, dt = http("POST", "/api/stop", {})
print(f"[4] POST /api/stop -> {sc} ({dt:.2f}s) body: {body[:200]}")
results["stop_immediate"] = sc == 200 and '"stopped"' in body and dt < 5

# ── 5. 中断后任务状态查询（看是否有 CANCELLED/INTERRUPTED 历史）──
sc, body, dt = http("GET", "/api/interrupt/tasks", timeout=10)
print(f"[5] GET /api/interrupt/tasks (after) -> {sc}")
print("    body:", body[:800])
try:
    j = json.loads(body)
    st = {t.get("status") for t in j.get("tasks", [])}
    print("    statuses seen:", st)
    results["state_machine_seen"] = ("CANCELLED" in st) or ("INTERRUPTED" in st)
except Exception as e:
    print("    parse err:", e)

# ── 6. 按 task_id 取消语义（用假 id，应返回 ok + cancelled_count 0，幂等）──
sc, body, dt = http("POST", "/api/interrupt", {"task_id": "fake-task-999", "stop_type": "soft"})
print(f"[6] POST /api/interrupt fake task_id -> {sc} ({dt:.2f}s) body: {body[:300]}")
results["fake_id_idempotent"] = sc == 200

print("\n=== SUMMARY ===")
for k, v in results.items():
    print(f"  {k}: {v}")
