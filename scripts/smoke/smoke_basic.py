# -*- coding: utf-8 -*-
"""E2E smoke tests S1-S8 against the running instance on port 8000 (UTF-8)."""
import json
import sys
import time

import requests

BASE = "http://localhost:8000"
TIMEOUT = 60


def post(path, body=None):
    return requests.post(BASE + path, json=body, timeout=TIMEOUT)


def get(path):
    return requests.get(BASE + path, timeout=TIMEOUT)


def dump(label, ok, detail=""):
    print(f"[{'PASS' if ok else 'FAIL'}] {label}" + (f" | {detail}" if detail else ""))


def main():
    results = []

    # S1: GET /api/state  (扁平结构: status/mode/session_id/round/scene/agent_count/...)
    try:
        r = get("/api/state")
        data = r.json()
        ok = r.status_code == 200 and "status" in data and "characters" in data and "scenes" in data
        dump("S1 GET /api/state", ok, f"HTTP {r.status_code} keys={list(data.keys())[:8]}")
        results.append(("S1", ok, f"HTTP {r.status_code}"))
    except Exception as e:
        dump("S1 GET /api/state", False, repr(e))
        results.append(("S1", False, repr(e)))

    # S2: POST /api/init
    sid = None
    try:
        r = post("/api/init", {"characters": [{"name": "小明", "persona": "开朗外向的年轻人"},
                                              {"name": "小红", "persona": "温柔细心的女孩"}],
                               "scene": "默认场景"})
        data = r.json()
        sid = data.get("session_id")
        agents = data.get("agents", [])
        ok = r.status_code == 200 and sid and len(agents) == 2
        dump("S2 POST /api/init", ok, f"HTTP {r.status_code} session_id={sid} agents={agents}")
        results.append(("S2", ok, f"session_id={sid}"))
    except Exception as e:
        dump("S2 POST /api/init", False, repr(e))
        results.append(("S2", False, repr(e)))

    # S3: POST /api/send (真实 LLM；现网实例曾出现 401 → 记录实际行为)
    try:
        r = post("/api/send", {"message": "你好，介绍一下自己吧"})
        data = r.json()
        outputs = data.get("agent_outputs", [])
        status = data.get("status", "")
        ok = r.status_code == 200 and len(outputs) > 0
        sample = ""
        if outputs:
            sample = json.dumps(outputs[0], ensure_ascii=False)[:150]
        dump("S3 POST /api/send", ok, f"HTTP {r.status_code} status={status!r} outputs={len(outputs)} first={sample}")
        results.append(("S3", ok, f"status={status!r} outputs={len(outputs)}"))
    except Exception as e:
        dump("S3 POST /api/send", False, repr(e))
        results.append(("S3", False, repr(e)))

    # S4: GET /api/events (SSE; 心跳 15s → 等待最多 17s 收取首帧)
    try:
        with requests.get(BASE + "/api/events", stream=True, timeout=20) as resp:
            start = time.time()
            got = b""
            while time.time() - start < 17.0:
                try:
                    chunk = next(resp.iter_content(1024))
                except StopIteration:
                    break
                got += chunk
                if got:
                    break
        ok = resp.status_code == 200 and len(got) > 0
        dump("S4 GET /api/events SSE", ok, f"HTTP {resp.status_code} bytes={len(got)} first={got[:80]!r}")
        results.append(("S4", ok, f"bytes={len(got)}"))
    except Exception as e:
        dump("S4 GET /api/events SSE", False, repr(e))
        results.append(("S4", False, repr(e)))

    # S5: simulation init/start/state
    agent_names = []
    try:
        r = post("/api/simulation/init", {"count": 3})
        d1 = r.json()
        r2 = post("/api/simulation/start", {})
        d2 = r2.json()
        r3 = get("/api/simulation/state")
        d3 = r3.json()
        agents = d3.get("agents", []) if isinstance(d3, dict) else []
        if agents and isinstance(agents[0], dict):
            k = "name" if "name" in agents[0] else ("agentName" if "agentName" in agents[0] else list(agents[0].keys()))
            agent_names = [a.get("name") or a.get("agentName") or a.get("agent_name") or "" for a in agents]
        ok = r.status_code == 200 and r2.status_code == 200 and r3.status_code == 200 and len(agents) >= 3
        dump("S5 simulation init/start/state", ok,
             f"init={d1.get('message')} start={d2} agents={len(agents)} name_key={k if agents else '?'}")
        results.append(("S5", ok, f"agents={len(agents)}"))
    except Exception as e:
        dump("S5 simulation init/start/state", False, repr(e))
        results.append(("S5", False, repr(e)))

    # S6: track/secret + track/state
    try:
        target = agent_names[0] if agent_names else "阿杰"
        r = post("/api/simulation/track/secret", {"agents": [target]})
        d1 = r.json()
        time.sleep(4.5)  # 分配在下一个 tick 生效（延迟一拍）
        r2 = get("/api/simulation/track/state")
        d2 = r2.json()
        assignments = d2.get("assignments", {})
        mine = assignments.get(target, {})
        mode = mine.get("mode") if isinstance(mine, dict) else mine
        ok = r.status_code == 200 and mode == "ISOLATED"
        dump("S6 track/secret -> ISOLATED", ok,
             f"HTTP {r.status_code} secret={d1.get('secret_agents')} {target}={mode} assignments_keys={list(assignments.keys())[:5]}")
        results.append(("S6", ok, f"{target}={mode}"))
    except Exception as e:
        dump("S6 track/secret -> ISOLATED", False, repr(e))
        results.append(("S6", False, repr(e)))

    # S7: mcp servers + approval status
    try:
        r1 = get("/api/mcp/servers")
        d1 = r1.json()
        r2 = get("/api/approval/status")
        d2 = r2.json()
        ok = r1.status_code == 200 and r2.status_code == 200
        dump("S7 mcp/servers + approval/status", ok,
             f"mcp HTTP {r1.status_code} type={type(d1).__name__} approval HTTP {r2.status_code} {str(d2)[:80]}")
        results.append(("S7", ok, f"mcp={r1.status_code} approval={r2.status_code}"))
    except Exception as e:
        dump("S7 mcp/servers + approval/status", False, repr(e))
        results.append(("S7", False, repr(e)))

    # S8: script/init (players+theme) -> status
    try:
        r = post("/api/script/init", {"players": ["侦探A", "助手B"], "theme": "庄园谋杀案"})
        d1 = r.json()
        r2 = get("/api/script/status")
        d2 = r2.json()
        ok = r.status_code == 200 and r2.status_code == 200 and d2.get("phase") in ("investigation", "discussion", "vote", "resolution")
        dump("S8 script/init -> status", ok,
             f"init HTTP {r.status_code} theme={d1.get('name')!r} phase={d1.get('phase')} status HTTP {r2.status_code} phase={d2.get('phase')}")
        results.append(("S8", ok, f"theme={d1.get('name')!r} phase={d2.get('phase')}"))
    except Exception as e:
        dump("S8 script/init -> status", False, repr(e))
        results.append(("S8", False, repr(e)))

    print("\n==== SUMMARY ====")
    passed = sum(1 for _, ok, _ in results if ok)
    for label, ok, detail in results:
        print(f"{'PASS' if ok else 'FAIL'} {label}: {detail}")
    print(f"TOTAL: {passed}/{len(results)} passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
