# -*- coding: utf-8 -*-
"""阶段 3：回归冒烟测试（不依赖 LLM 的端点 + 游戏 init + admin 鉴权）"""
import json, sys, urllib.request, urllib.error

BASE = "http://127.0.0.1:8000"
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROWS = []

def http(method, path, payload=None, headers=None, timeout=40):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:
        return -1, f"EXC:{type(e).__name__}:{e}"

def rec(name, expect, sc, body, extra=""):
    ok = (sc in expect) if isinstance(expect, (list, tuple, set)) else (sc == expect)
    ROWS.append((name, sc, "PASS" if ok else "FAIL", body[:160].replace("\n", " "), extra))
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: HTTP {sc} (expect {expect}) {extra} | {body[:120]}")

# ── 基础端点 ──
sc, b = http("GET", "/api/state"); rec("GET /api/state", 200, sc, b)
sc, b = http("GET", "/api/characters"); rec("GET /api/characters", 200, sc, b)
sc, b = http("GET", "/api/scenes"); rec("GET /api/scenes", 200, sc, b)
sc, b = http("GET", "/api/history"); rec("GET /api/history", 200, sc, b)
sc, b = http("GET", "/api/history/sessions"); rec("GET /api/history/sessions", 200, sc, b)

# ── rooms（无 GET /api/rooms 根端点；实测 POST 创建 + GET /{code}）──
sc, b = http("POST", "/api/rooms", {"code": "smoke-room-001", "host": "tester"})
rec("POST /api/rooms (无根GET，测实际端点)", (200, 400, 500), sc, b)
room_code = None
try:
    room_code = json.loads(b).get("code") or "smoke-room-001"
except Exception:
    room_code = "smoke-room-001"
sc, b = http("GET", f"/api/rooms/{room_code}")
rec(f"GET /api/rooms/{room_code}", 200, sc, b)

# ── auth ──
sc, b = http("GET", "/api/auth/me")  # 无 Authorization 头
rec("GET /api/auth/me (无token)", 401, sc, b, "实际语义记录")
sc, b = http("GET", "/api/auth/me", headers={"Authorization": "Bearer bad-token-xyz"})
rec("GET /api/auth/me (无效token)", 401, sc, b)
sc, b = http("POST", "/api/auth/admin/generate")
rec("POST /api/auth/admin/generate (无X-Admin-Key)", 403, sc, b)
sc, b = http("POST", "/api/auth/admin/generate", headers={"X-Admin-Key": "admin-secret-change-me"})
rec("POST /api/auth/admin/generate (默认key)", 200, sc, b)
code = ""
try:
    code = json.loads(b).get("code", "")
except Exception:
    pass
print("    生成邀请码:", code)
if code:
    sc, b = http("POST", "/api/auth/verify", {"code": code})
    rec(f"POST /api/auth/verify (用生成的code {code})", 200, sc, b)
    token = ""
    try:
        token = json.loads(b).get("token", "")
    except Exception:
        pass
    if token:
        sc, b = http("GET", "/api/auth/me", headers={"Authorization": f"Bearer {token}"})
        rec("GET /api/auth/me (有效token)", 200, sc, b)
    # 清理：deactivate 邀请码
    sc, b = http("POST", "/api/auth/admin/deactivate", {"code": code},
                 headers={"X-Admin-Key": "admin-secret-change-me"})
    rec(f"POST /api/auth/admin/deactivate {code} (清理)", 200, sc, b)
    sc, b = http("POST", "/api/auth/verify", {"code": code})
    rec(f"POST /api/auth/verify (deactivate后 {code})", 401, sc, b, "确认已清理")

# ── voice ──
sc, b = http("GET", "/api/voice/status"); rec("GET /api/voice/status", 200, sc, b)
sc, b = http("POST", "/api/voice/transcribe")  # 无文件
rec("POST /api/voice/transcribe (无文件)", 400, sc, b)

# ── config（无根 GET /api/config；测实际子端点）──
sc, b = http("GET", "/api/config"); rec("GET /api/config (无根端点)", 404, sc, b, "实际端点:/apikey /language /models /voice")
sc, b = http("GET", "/api/config/models"); rec("GET /api/config/models", 200, sc, b)
sc, b = http("GET", "/api/config/language"); rec("GET /api/config/language", 200, sc, b)
sc, b = http("GET", "/api/config/voice"); rec("GET /api/config/voice", 200, sc, b)

# ── interrupt（GET 列表；POST /api/interrupt/tasks 应为 405）──
sc, b = http("GET", "/api/interrupt/tasks?type=dialogue"); rec("GET /api/interrupt/tasks?type=dialogue", 200, sc, b)
sc, b = http("POST", "/api/interrupt/tasks"); rec("POST /api/interrupt/tasks (仅GET端点)", 405, sc, b)

# ── 狼人杀 init（无 LLM 依赖）──
sc, b = http("POST", "/api/werewolf/init", {"players": ["狼A", "狼B", "村民C", "预言家D"]})
rec("POST /api/werewolf/init (无LLM)", 200, sc, b)
sc, b = http("GET", "/api/werewolf/status"); rec("GET /api/werewolf/status", 200, sc, b)

# ── 剧本杀 init（LLM 生成剧本 → 401 预期阻塞）──
sc, b = http("POST", "/api/script/init", {"players": ["甲", "乙", "丙"], "theme": "山庄谋杀案"}, timeout=60)
rec("POST /api/script/init (LLM剧本生成)", 200, sc, b, "LLM 401 时如实记录")
sc, b = http("GET", "/api/script/status"); rec("GET /api/script/status", 200, sc, b)

print("\n=== SMOKE SUMMARY ===")
for r in ROWS:
    print(f"  [{r[2]}] {r[0]} -> {r[1]}")
