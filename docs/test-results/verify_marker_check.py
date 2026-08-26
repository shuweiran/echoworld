# -*- coding: utf-8 -*-
"""D14 重启恢复验证：重启后校验标记数据仍在"""
import json, urllib.request

BASE = "http://127.0.0.1:8000"
with open(r"D:\echoworld\docs\test-results\verify_marker_ts.txt", encoding="utf-8") as f:
    lines = f.read().splitlines()
TS, char_name = lines[0], lines[1]
scene_id = "verify-" + TS

def get(path):
    with urllib.request.urlopen(BASE + path, timeout=15) as r:
        return r.status, r.read().decode("utf-8")

sc, body = get("/api/characters")
chars = json.loads(body)
found_char = any(c.get("name") == char_name for c in chars)
print("GET /api/characters ->", sc, "| count:", len(chars), "| marker found:", found_char)

sc, body = get("/api/scenes")
scenes = json.loads(body)
found_scene = any(s.get("scene_id") == scene_id for s in scenes)
print("GET /api/scenes ->", sc, "| count:", len(scenes), "| marker found:", found_scene)

print("RESULT:", "PASS" if (found_char and found_scene) else "FAIL")
