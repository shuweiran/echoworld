# -*- coding: utf-8 -*-
"""真机验证 第三遍：多会话隔离(真实session_id) + turns/stop(轮间) — 阶段3/4 最终版
关键修正：init 忽略 body 的 session_id（每次生成新 UUID），必须用 init 返回值。
"""
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
    # ===== 阶段 3 最终版：真实 session_id 隔离 =====
    print("===== 阶段3(最终): 多会话隔离(真实session_id) =====", flush=True)
    st, d, _ = http("POST", "/api/init", {"characters": [{"name": "角色甲", "persona": "沉默寡言的剑客"}],
                                          "scene": "竹林"})
    sidA = d.get("session_id") if isinstance(d, dict) else None
    dump("init A(角色甲)", st == 200 and sidA, f"HTTP {st} session_id={sidA} agents={d.get('agents') if isinstance(d, dict) else d}")

    st, d, _ = http("POST", "/api/init", {"characters": [{"name": "角色乙", "persona": "活泼好奇的少女"}],
                                          "scene": "花园"})
    sidB = d.get("session_id") if isinstance(d, dict) else None
    dump("init B(角色乙)", st == 200 and sidB, f"HTTP {st} session_id={sidB} agents={d.get('agents') if isinstance(d, dict) else d}")
    print(f"  A session_id={sidA}  B session_id={sidB}", flush=True)

    st, d, dt = http("POST", "/api/send", {"session_id": sidA, "message": "我是甲，我只谈剑术"}, timeout=180)
    dump("send A(剑术)", st == 200 and d.get("agent_outputs"), f"HTTP {st} outputs={len(d.get('agent_outputs', [])) if isinstance(d, dict) else 0} {dt:.1f}s")
    st, d, dt = http("POST", "/api/send", {"session_id": sidB, "message": "我是乙，我喜欢唱歌"}, timeout=180)
    dump("send B(唱歌)", st == 200 and d.get("agent_outputs"), f"HTTP {st} outputs={len(d.get('agent_outputs', [])) if isinstance(d, dict) else 0} {dt:.1f}s")

    # state 隔离
    st, dA, _ = http("GET", "/api/state", query={"session_id": sidA})
    st, dB, _ = http("GET", "/api/state", query={"session_id": sidB})
    namesA = (dA.get("agent_names") or [a.get("name") for a in dA.get("agents", [])]) if isinstance(dA, dict) else []
    namesB = (dB.get("agent_names") or [a.get("name") for a in dB.get("agents", [])]) if isinstance(dB, dict) else []
    print(f"  A state agents={namesA} round={dA.get('round') if isinstance(dA, dict) else '?'}", flush=True)
    print(f"  B state agents={namesB} round={dB.get('round') if isinstance(dB, dict) else '?'}", flush=True)
    dump("隔离 A/B 角色独立", "角色甲" in namesA and "角色乙" not in namesA and "角色乙" in namesB and "角色甲" not in namesB,
         f"A={namesA} B={namesB}")

    # 历史内容隔离
    st, hA, _ = http("GET", "/api/history", query={"session_id": sidA})
    st, hB, _ = http("GET", "/api/history", query={"session_id": sidB})
    txtA = json.dumps(hA, ensure_ascii=False) if isinstance(hA, dict) else str(hA)
    txtB = json.dumps(hB, ensure_ascii=False) if isinstance(hB, dict) else str(hB)
    leakA = ("唱歌" in txtA and "我是乙" in txtA)
    leakB = ("剑术" in txtB and "我是甲" in txtB)
    dump("历史内容隔离", not leakA and not leakB, f"leakA={leakA} leakB={leakB}")

    # 无 session_id → 默认单例（最后一次 init = 角色乙会话）
    st, d, dt = http("POST", "/api/send", {"message": "默认会话你好"}, timeout=180)
    st2, d2, _ = http("GET", "/api/state")
    namesDef = (d2.get("agent_names") or [a.get("name") for a in d2.get("agents", [])]) if isinstance(d2, dict) else []
    dump("无 session_id 默认单例向后兼容", st == 200 and d.get("agent_outputs"),
         f"HTTP {st} outputs={len(d.get('agent_outputs', [])) if isinstance(d, dict) else 0} 默认会话agents={namesDef}")

    # ===== 阶段 4 最终版：turns=5 轮间 stop =====
    print("===== 阶段4(最终): turns=5 中途 stop（等待首轮完成后） =====", flush=True)
    import threading
    result = {}
    def run_round():
        try:
            st, d, dt = http("POST", "/api/round/start", {"turns": 5, "session_id": sidA}, timeout=180)
            result['round'] = (st, d, dt)
        except Exception as e:
            result['round'] = ("EXC", str(e), 0)

    t = threading.Thread(target=run_round)
    t.start()
    # 等待第一轮完成（LLM 约 15-30s/轮），然后 stop
    time.sleep(40)
    st, d, dt = http("POST", "/api/stop", {"session_id": sidA}, timeout=30)
    print(f"  40s后 POST /api/stop -> HTTP {st} {json.dumps(d, ensure_ascii=False)[:120] if isinstance(d, dict) else d}", flush=True)
    t.join(timeout=120)
    if 'round' in result:
        st_r, d_r, dt_r = result['round']
        if isinstance(d_r, dict):
            rounds = d_r.get("rounds"); reason = d_r.get("stop_reason")
            print(f"  round/start 返回: HTTP {st_r} rounds={rounds} stop_reason={reason} outputs={len(d_r.get('agent_outputs', []))} 耗时{dt_r:.1f}s", flush=True)
            dump("turns=5 提前停止", st_r == 200 and (reason == "stopped" or (isinstance(rounds, int) and rounds < 5)),
                 f"rounds={rounds} stop_reason={reason}")
        else:
            dump("turns=5 提前停止", False, f"EXC {d_r}")
    else:
        dump("turns=5 提前停止", False, "round/start 未返回")

    st, d, _ = http("GET", "/api/state", query={"session_id": sidA})
    if isinstance(d, dict):
        print(f"  停止后 A: round={d.get('round')} status={d.get('status')}", flush=True)

    print("==== DONE ====", flush=True)

if __name__ == "__main__":
    sys.exit(main())
