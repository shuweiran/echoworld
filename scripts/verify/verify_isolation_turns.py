# -*- coding: utf-8 -*-
"""真机验证 第二遍：多会话隔离(query参数) + turns/stop 并发 (阶段3/4 修正版)"""
import json
import sys
import time
import urllib.request
import urllib.parse

BASE = "http://127.0.0.1:8000"

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

def dump(label, ok, detail=""):
    print(f"[{'PASS' if ok else 'FAIL'}] {label} | {detail}", flush=True)

def main():
    # ===== 阶段 3 修正：query 参数 =====
    print("===== 阶段 3(修正): 多会话隔离 query 参数 =====", flush=True)
    st, d, _ = http("POST", "/api/init", {"session_id": "A1",
                                          "characters": [{"name": "角色甲", "persona": "沉默寡言的剑客"}],
                                          "scene": "竹林"})
    dump("init A1", st == 200, f"HTTP {st} agents={d.get('agents') if isinstance(d, dict) else d}")
    st, d, dt = http("POST", "/api/send", {"session_id": "A1", "message": "我是甲，我只谈剑术"}, timeout=180)
    dump("send A1", st == 200, f"HTTP {st} outputs={len(d.get('agent_outputs', [])) if isinstance(d, dict) else 0} {dt:.1f}s")

    st, d, _ = http("POST", "/api/init", {"session_id": "B1",
                                          "characters": [{"name": "角色乙", "persona": "活泼好奇的少女"}],
                                          "scene": "花园"})
    dump("init B1", st == 200, f"HTTP {st} agents={d.get('agents') if isinstance(d, dict) else d}")
    st, d, dt = http("POST", "/api/send", {"session_id": "B1", "message": "我是乙，我喜欢唱歌"}, timeout=180)
    dump("send B1", st == 200, f"HTTP {st} outputs={len(d.get('agent_outputs', [])) if isinstance(d, dict) else 0} {dt:.1f}s")

    # query 参数获取 state
    st, dA, _ = http("GET", "/api/state", query={"session_id": "A1"})
    st, dB, _ = http("GET", "/api/state", query={"session_id": "B1"})
    namesA = (dA.get("agent_names") or [a.get("name") for a in dA.get("agents", [])]) if isinstance(dA, dict) else []
    namesB = (dB.get("agent_names") or [a.get("name") for a in dB.get("agents", [])]) if isinstance(dB, dict) else []
    roundA = dA.get("round") if isinstance(dA, dict) else "?"
    roundB = dB.get("round") if isinstance(dB, dict) else "?"
    print(f"  A1: agents={namesA} round={roundA}", flush=True)
    print(f"  B1: agents={namesB} round={roundB}", flush=True)
    isoA = "角色甲" in namesA and "角色乙" not in namesA
    isoB = "角色乙" in namesB and "角色甲" not in namesB
    dump("隔离 A/B 角色互不串", isoA and isoB, f"A={namesA} B={namesB}")

    # 内容隔离：A1 历史里无 B 内容（send 时 persona 不同、消息独立）
    st, dH, _ = http("GET", "/api/history", query={"session_id": "A1"})
    hA = json.dumps(dH, ensure_ascii=False) if isinstance(dH, dict) else str(dH)
    st, dH2, _ = http("GET", "/api/history", query={"session_id": "B1"})
    hB = json.dumps(dH2, ensure_ascii=False) if isinstance(dH2, dict) else str(dH2)
    leakA = "唱歌" in hA and "我是乙" in hA
    leakB = "剑术" in hB and "我是甲" in hB
    dump("历史内容隔离", not leakA and not leakB,
         f"leakA={leakA}(乙内容进A) leakB={leakB}(甲内容进B)")

    # 无 session_id 默认单例
    st, d, dt = http("POST", "/api/send", {"message": "不带 session 的消息"}, timeout=180)
    dump("无 session_id 默认单例", st == 200 and d.get("agent_outputs"),
         f"HTTP {st} outputs={len(d.get('agent_outputs', [])) if isinstance(d, dict) else 0} {dt:.1f}s")

    # ===== 阶段 4 修正：turns=5 + 并发 stop =====
    print("===== 阶段 4(修正): turns=5 中途 stop 并发 =====", flush=True)
    import threading

    result = {}
    def run_round():
        try:
            st, d, dt = http("POST", "/api/round/start", {"turns": 5, "session_id": "A1"}, timeout=120)
            result['round'] = (st, d, dt)
        except Exception as e:
            result['round'] = ("EXC", str(e), 0)

    t = threading.Thread(target=run_round)
    t.start()
    time.sleep(3.0)  # 让 round/start 开始执行
    st, d, dt = http("POST", "/api/stop", {"session_id": "A1"}, timeout=30)
    print(f"  POST /api/stop -> HTTP {st} {json.dumps(d, ensure_ascii=False)[:150] if isinstance(d, dict) else d} {dt:.1f}s", flush=True)
    t.join(timeout=60)
    if 'round' in result:
        st_r, d_r, dt_r = result['round']
        if isinstance(d_r, dict):
            print(f"  round/start 返回: HTTP {st_r} rounds={d_r.get('rounds')} stop_reason={d_r.get('stop_reason')} outputs={len(d_r.get('agent_outputs', []))} 耗时{dt_r:.1f}s", flush=True)
            dump("turns=5 中途 stop 提前停止", st_r == 200 and (d_r.get("stop_reason") == "stopped" or (isinstance(d_r.get("rounds"), int) and d_r.get("rounds") < 5)),
                 f"rounds={d_r.get('rounds')} stop_reason={d_r.get('stop_reason')}")
        else:
            print(f"  round/start 异常: {d_r}", flush=True)
            dump("turns=5 中途 stop", False, f"EXC {d_r}")
    else:
        print("  round/start 未在 60s 内返回（stop 后仍阻塞）", flush=True)
        dump("turns=5 中途 stop", False, "round/start 超时未返回")

    st, d, _ = http("GET", "/api/state", query={"session_id": "A1"})
    if isinstance(d, dict):
        print(f"  停止后 A1: round={d.get('round')} status={d.get('status')}", flush=True)

    print("==== DONE ====", flush=True)

if __name__ == "__main__":
    sys.exit(main())
