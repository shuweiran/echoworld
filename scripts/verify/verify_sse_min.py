# -*- coding: utf-8 -*-
"""阶段5b: SSE 最小复现 — 连接建立确认 + 多次触发观察事件完整性"""
import json
import sys
import time
import threading
import urllib.request
import urllib.parse

BASE = "http://127.0.0.1:8000"
EVENTS = []
LOCK = threading.Lock()

def sse_collector(duration=60):
    url = BASE + "/api/events"
    req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
    try:
        with urllib.request.urlopen(req, timeout=duration + 10) as r:
            print("SSE 连接已建立, HTTP", r.status, flush=True)
            buf = b""
            deadline = time.time() + duration
            while time.time() < deadline:
                chunk = r.read(2048)
                if not chunk:
                    print("SSE 流关闭", flush=True)
                    break
                buf += chunk
                while b"\n\n" in buf:
                    raw, buf = buf.split(b"\n\n", 1)
                    text = raw.decode("utf-8", "replace")
                    event = ""
                    data = ""
                    for line in text.splitlines():
                        if line.startswith("event:"):
                            event = line[6:].strip()
                        elif line.startswith("data:"):
                            data = line[5:].strip()
                    with LOCK:
                        EVENTS.append({"event": event, "data": data[:150], "t": time.time()})
                    print(f"  [收到] event={event!r} data={data[:80]!r}", flush=True)
    except Exception as e:
        print("SSE 收集异常:", str(e)[:200], flush=True)

def http(method, path, body=None, timeout=180, query=None):
    url = BASE + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
    dt = time.time() - t0
    try:
        return r.status, json.loads(raw), dt
    except Exception:
        return r.status, raw.decode("utf-8", "replace"), dt

def main():
    sys.stdout.reconfigure(encoding="utf-8")
    print("===== SSE 最小复现 =====", flush=True)

    # 1. 先建会话
    st, d, _ = http("POST", "/api/init", {"characters": [{"name": "小宁", "persona": "温柔的书店店员"}],
                                          "scene": "书店"})
    sid = d.get("session_id") if isinstance(d, dict) else None
    print(f"init: HTTP {st} session_id={sid}", flush=True)

    # 2. 启动 SSE 收集（先连接）
    collector = threading.Thread(target=sse_collector, args=(55,), daemon=True)
    collector.start()
    time.sleep(3)  # 确保连接注册

    # 3. 触发一轮（简短消息，控制耗时）
    st, d, dt = http("POST", "/api/send", {"session_id": sid, "message": "嗨"}, timeout=120)
    print(f"send: HTTP {st} 耗时 {dt:.1f}s", flush=True)

    collector.join(timeout=65)
    with LOCK:
        names = [e["event"] for e in EVENTS]
    from collections import Counter
    print(f"\n===== 事件统计({len(names)}): {dict(Counter(names))} =====", flush=True)
    for w in ["track_created", "round_start", "arbiter_task", "user_input", "agent_output", "arbiter_integrate", "round_complete", "compression"]:
        print(f"  {w}: {'✅' if w in names else '❌'}", flush=True)

if __name__ == "__main__":
    main()
