# -*- coding: utf-8 -*-
"""真机验证：真实 LLM 对话 3 轮 + 多会话隔离 + turns 参数（阶段 2/3/4）
运行前确保服务已启动且 POST /api/config/apikey 已设置 key。
"""
import json
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:8000"

def http(method, path, body=None, timeout=120):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
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
    print(f"[{'PASS' if ok else 'FAIL'}] {label} | {detail}")

def main():
    # ---------- 阶段 2：真实对话 ----------
    print("===== 阶段 2: 真实对话 3 轮 =====")
    st, d, dt = http("POST", "/api/init", {"characters": [{"name": "助手", "persona": "温柔体贴的助手"}],
                                           "scene": "默认场景"})
    sid = d.get("session_id") if isinstance(d, dict) else None
    dump("init", st == 200 and sid, f"HTTP {st} session_id={sid} agents={d.get('agents') if isinstance(d, dict) else d}")

    round_msgs = ["你好，介绍一下你自己", "你有什么特长？", "我们刚才聊了什么？总结一下"]
    for i, msg in enumerate(round_msgs, 1):
        st, d, dt = http("POST", "/api/send", {"message": msg}, timeout=180)
        outputs = d.get("agent_outputs", []) if isinstance(d, dict) else []
        ok = st == 200 and outputs
        content = ""
        if outputs and isinstance(outputs[0], dict):
            content = outputs[0].get("content") or outputs[0].get("text") or json.dumps(outputs[0], ensure_ascii=False)
        print(f"  round{i}: HTTP {st} 耗时 {dt:.1f}s agent_outputs={len(outputs)} round={d.get('round') if isinstance(d, dict) else '?'}")
        if content:
            print(f"    回复: {content[:160]}")
        dump(f"send round{i}", ok, f"HTTP {st} outputs={len(outputs)}")

    # state 确认 round 递增
    st, d, dt = http("GET", "/api/state")
    dump("state round", isinstance(d, dict) and d.get("round") >= 3,
         f"round={d.get('round')} message_count={d.get('message_count')}")

    # ---------- 阶段 3：多会话隔离 ----------
    print("===== 阶段 3: 多会话隔离 =====")
    # 会话 A
    st, d, dt = http("POST", "/api/init", {"session_id": "A1",
                                           "characters": [{"name": "角色甲", "persona": "沉默寡言的剑客"}],
                                           "scene": "竹林"})
    dump("init A1", st == 200, f"HTTP {st} agents={d.get('agents') if isinstance(d, dict) else d}")
    st, d, dt = http("POST", "/api/send", {"session_id": "A1", "message": "我是甲，我只谈剑术"}, timeout=180)
    dump("send A1", st == 200, f"HTTP {st} outputs={len(d.get('agent_outputs', [])) if isinstance(d, dict) else 0}")
    # 会话 B
    st, d, dt = http("POST", "/api/init", {"session_id": "B1",
                                           "characters": [{"name": "角色乙", "persona": "活泼好奇的少女"}],
                                           "scene": "花园"})
    dump("init B1", st == 200, f"HTTP {st} agents={d.get('agents') if isinstance(d, dict) else d}")
    st, d, dt = http("POST", "/api/send", {"session_id": "B1", "message": "我是乙，我喜欢唱歌"}, timeout=180)
    dump("send B1", st == 200, f"HTTP {st} outputs={len(d.get('agent_outputs', [])) if isinstance(d, dict) else 0}")

    # state 隔离验证
    st, dA, _ = http("GET", "/api/state", {"session_id": "A1"})
    st, dB, _ = http("GET", "/api/state", {"session_id": "B1"})
    namesA = dA.get("agent_names") or [a.get("name") for a in dA.get("agents", [])] if isinstance(dA, dict) else []
    namesB = dB.get("agent_names") or [a.get("name") for a in dB.get("agents", [])] if isinstance(dB, dict) else []
    print(f"  A1 agents={namesA} round={dA.get('round') if isinstance(dA, dict) else '?'}")
    print(f"  B1 agents={namesB} round={dB.get('round') if isinstance(dB, dict) else '?'}")
    isoA = "角色甲" in namesA and "角色乙" not in namesA
    isoB = "角色乙" in namesB and "角色甲" not in namesB
    dump("隔离 A/B 角色互不串", isoA and isoB, f"A={namesA} B={namesB}")

    # 无 session_id 默认单例（向后兼容）
    st, d, dt = http("POST", "/api/send", {"message": "不带 session 的消息"}, timeout=180)
    dump("无 session_id 默认单例", st == 200, f"HTTP {st} outputs={len(d.get('agent_outputs', [])) if isinstance(d, dict) else 0}")

    # ---------- 阶段 4：turns 参数 ----------
    print("===== 阶段 4: turns 参数 =====")
    st, d, dt = http("POST", "/api/round/start", {"turns": 3}, timeout=300)
    rounds = d.get("rounds") if isinstance(d, dict) else None
    stop = d.get("stop_reason") if isinstance(d, dict) else None
    outs = d.get("agent_outputs", []) if isinstance(d, dict) else []
    dump("round/start turns=3", st == 200 and rounds == 3 and stop == "completed",
         f"HTTP {st} rounds={rounds} stop_reason={stop} outputs={len(outs)} 耗时{dt:.1f}s")

    # turns=5 中途 stop
    st, d, dt = http("POST", "/api/round/start", {"turns": 5, "session_id": "A1"}, timeout=60)
    print(f"  round/start turns=5 发起 HTTP {st} (后台)")
    time.sleep(2.5)
    st, d, dt = http("POST", "/api/stop", {"session_id": "A1"}, timeout=30)
    print(f"  POST /api/stop -> HTTP {st} {json.dumps(d, ensure_ascii=False)[:120] if isinstance(d, dict) else d}")
    time.sleep(3)
    st, d, _ = http("GET", "/api/state", {"session_id": "A1"})
    if isinstance(d, dict):
        print(f"  停止后 A1 round={d.get('round')} status={d.get('status')}")

    print("==== DONE ====")

if __name__ == "__main__":
    sys.exit(main())
