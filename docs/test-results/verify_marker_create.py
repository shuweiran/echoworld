# -*- coding: utf-8 -*-
"""D14 重启恢复验证：创建唯一标记数据（字符+场景）"""
import json, time, urllib.request, urllib.error

BASE = "http://127.0.0.1:8000"
TS = time.strftime("%Y%m%d-%H%M%S")

def post(path, payload):
    req = urllib.request.Request(BASE + path, data=json.dumps(payload).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            body = r.read().decode("utf-8")
            return r.status, body
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")

char_name = "验证角色-" + TS
sc, body = post("/api/characters", {"name": char_name, "persona": "D14重启恢复验证专用角色",
                                     "voice": "测试音色", "background": "由运行时验证脚本创建"})
print("POST /api/characters ->", sc, body)
with open(r"D:\roleplay-java\docs\test-results\verify_marker_ts.txt", "w", encoding="utf-8") as f:
    f.write(TS + "\n" + char_name + "\n")

scene_id = "verify-" + TS
sc, body = post("/api/scenes", {"scene_id": scene_id, "name": "验证场景-" + TS,
                                 "description": "D14重启恢复验证专用场景", "initial_agent_names": [char_name]})
print("POST /api/scenes ->", sc, body)
