# -*- coding: utf-8 -*-
"""阶段5: SSE 事件流验证 — 连接 /api/events 触发一轮对话, 收集事件"""
import json
import sys
import time
import threading
import urllib.request
import urllib.parse

BASE = "http://127.0.0.1:8000"
EVENTS = []
LOCK = threading.Lock()

def sse_collector(duration=30):
    """后台线程: 连接 /api/events 收集事件"""
    url = BASE + "/api/events"
    req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
    try:
        with urllib.request.urlopen(req, timeout=duration + 10) as r:
            buf = b""
            deadline = time.time() + duration
            while time.time() < deadline:
                chunk = r.read(1024)
                if not chunk:
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
                    if event or data:
                        with LOCK:
                            EVENTS.append({"event": event, "data": data[:200], "t": round(time.time(), 1)})
    except Exception as e:
        with LOCK:
            EVENTS.append({"event": "COLLECTOR_ERR", "data": str(e)[:150], "t": round(time.time(), 1)})

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
    print("===== 阶段5: SSE 事件流验证 =====", flush=True)

    # 启动 SSE 收集线程
    collector = threading.Thread(target=sse_collector, args=(35,), daemon=True)
    collector.start()
    time.sleep(2)  # 确保 SSE 连接建立

    # 初始化会话 + 发送一轮真实对话
    st, d, _ = http("POST", "/api/init", {"characters": [{"name": "测试员", "persona": "友善的向导"}],
                                          "scene": "小镇"})
    sid = d.get("session_id") if isinstance(d, dict) else None
    print(f"init: HTTP {st} session_id={sid}", flush=True)

    st, d, dt = http("POST", "/api/send", {"session_id": sid, "message": "你好，请简单介绍一下这个小镇。"}, timeout=120)
    print(f"send: HTTP {st} 耗时 {dt:.1f}s", flush=True)
    if isinstance(d, dict):
        print(f"send 返回: status={d.get('status')} round={d.get('round')}", flush=True)

    # 等待 SSE 收集
    collector.join(timeout=130)
    print(f"\n===== 收集到 {len(EVENTS)} 个 SSE 事件 =====", flush=True)

    # 按事件名统计
    from collections import Counter
    names = Counter(e["event"] for e in EVENTS)
    print("事件名统计:", dict(names), flush=True)

    # 关键事件检查
    wanted = ["round_start", "agent_output", "round_complete", "user_input", "arbiter_integrate"]
    found = {w: any(e["event"] == w for e in EVENTS) for w in wanted}
    print("关键事件:", found, flush=True)

    # 心跳检查（data 含 ping/heartbeat 或空事件持续）
    heartbeats = [e for e in EVENTS if "heartbeat" in e["data"].lower() or "ping" in e["data"].lower() or e["event"] == ""]
    print(f"心跳类事件数: {len(heartbeats)}", flush=True)

    # 展示前 8 个事件样例
    print("\n事件样例(前8):", flush=True)
    for e in EVENTS[:8]:
        print(f"  [{e['t']}] event={e['event']!r} data={e['data'][:120]}", flush=True)

    # 结论
    ok = found.get("round_start") and found.get("agent_output") and found.get("round_complete")
    print(f"\n[{'PASS' if ok else 'FAIL'}] SSE 主对话事件流: round_start/agent_output/round_complete 均已广播"
          if ok else "\n[FAIL] SSE 关键事件缺失", flush=True)

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()
