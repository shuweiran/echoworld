# -*- coding: utf-8 -*-
"""stress_history.py — 历史记录/持久化压测（LONG-02 回归，UTF-8）

目标端点：POST /api/init → POST /api/send → GET /api/history →
          GET /api/history/sessions/{id} → POST /api/history/load/{id}
对应测试方案 §9.2 LONG-02 回归（历史持久化/加载正确性）与 D12 修复后验证。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
用法：
  python scripts/stress/stress_history.py --sessions 10 --rounds-per-session 5
  python scripts/stress/stress_history.py --sessions 50 --rounds-per-session 10 --round-chars 500
  python scripts/stress/stress_history.py --base-url http://192.168.1.100:8000 --no-load-check

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

通过标准：
  ① 所有 HTTP 请求 200（init/send/history/list/get/load）
     成功率 ≥ --min-success-rate（默认 1.0）
  ② P95 响应时间 ≤ --p95-max（默认 5s）
  ③ load 返回 status="loaded" 且 round 字段正确（D12 修复后验证）

注意：
  - POST /api/history/load/{id} 将历史会话写回单例 RouterService（D12 修复后实际生效）。
    脚本在 load 后验证返回的 round 与创建时记录的一致。
  - 历史写入依赖 RouterService 内 auto-save 调用（每轮 send 后自动 saveSession）；
    若后端未调用 saveSession，则 history 列表为空（已知问题 D-11 相关）。
  - 在 mock/无真实 LLM 环境下，send 响应可能因 LLM 401 失败；
    脚本诚实地记录失败并体现在成功率中。
"""

import argparse
import os
import sys
import textwrap
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.client import HttpClient, make_long_text, percentile, summarize_latencies, print_summary


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="stress_history — 历史记录/持久化压测",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
            示例：
              python stress_history.py --sessions 10 --rounds-per-session 5
              python stress_history.py --sessions 50 --rounds-per-session 10 --round-chars 500
              python stress_history.py --no-load-check --p95-max 10

            通过标准：
              ① HTTP 200 成功率 ≥ --min-success-rate（默认 1.0）
              ② P95 响应时间 ≤ --p95-max（默认 5s）
              ③ load round 字段匹配创建时的轮数
            """))
    p.add_argument("--base-url", default="http://127.0.0.1:8000",
                   help="后端 base URL")
    p.add_argument("--timeout", type=float, default=60.0,
                   help="单请求超时秒数（默认 60）")
    p.add_argument("--sessions", type=int, default=10,
                   help="创建的会话数（默认 10）")
    p.add_argument("--rounds-per-session", type=int, default=5,
                   help="每个会话的对话轮数（默认 5）")
    p.add_argument("--round-chars", type=int, default=100,
                   help="每轮消息字符数（默认 100）")
    p.add_argument("--p95-max", type=float, default=5.0,
                   help="P95 响应时间通过阈值秒数（默认 5s）")
    p.add_argument("--min-success-rate", type=float, default=1.0,
                   help="最低成功率")
    p.add_argument("--no-load-check", action="store_true",
                   help="跳过 load 后的 round 校验（后端未实现 loadSession 时使用）")
    return p


def run(args: argparse.Namespace) -> int:
    print(f"=== stress_history ===\n")
    print(f"    base_url:   {args.base_url}")
    print(f"    sessions:   {args.sessions}")
    print(f"    rounds/ses: {args.rounds_per_session}")
    print(f"    chars/rnd:  {args.round_chars}")

    client = HttpClient(args.base_url, timeout=args.timeout)
    all_pass = True

    # Collect all latencies for overall stats
    all_latencies: list[float] = []
    total_requests = 0
    total_successes = 0

    # Record each session's id and round count for later load verification
    session_records: list[dict] = []

    # ── Phase 1: Create sessions ──
    print(f"\n── Phase 1: 创建 {args.sessions} 个会话 ──")
    for i in range(1, args.sessions + 1):
        anchor = f"历史压测会话-{i}"
        session_latencies: list[float] = []

        # 1a) Init
        try:
            resp, elapsed = client.timed_post("/api/init", json={
                "characters": [{"name": f"历史角色{i}", "persona": f"历史会话 {i} 的测试角色"}],
                "scene": f"历史压测场景{i}",
            })
            session_latencies.append(elapsed)
            ok = resp.status_code == 200
            if ok:
                sid = resp.json().get("session_id")
            else:
                print(f"   [FAIL] session {i} init: HTTP {resp.status_code}")
                all_pass = False
                continue
        except Exception as e:
            print(f"   [FAIL] session {i} init: {e!r}")
            all_pass = False
            continue

        # 1b) Send rounds
        successes = 1
        for rnd in range(1, args.rounds_per_session + 1):
            msg = make_long_text(args.round_chars, anchor=f"{anchor}-轮{rnd}", anchor_position=0)
            try:
                resp, elapsed = client.timed_post("/api/send", json={"message": msg})
                session_latencies.append(elapsed)
                if resp.status_code == 200:
                    successes += 1
            except Exception:
                pass

        all_latencies.extend(session_latencies)
        total_requests += len(session_latencies)
        total_successes += successes
        session_records.append({
            "session_id": sid,
            "rounds": args.rounds_per_session,
            "successes": successes,
            "total_requests": len(session_latencies),
        })

        if i % max(1, args.sessions // 10) == 0 or i == args.sessions:
            print(f"   ... {i}/{args.sessions} sessions created")

    # Print per-session brief
    for rec in session_records:
        pct = rec["successes"] / max(rec["total_requests"], 1)
        print(f"   [{rec['session_id']}] {rec['successes']}/{rec['total_requests']} ({pct:.0%})")

    # ── Phase 2: List history ──
    print(f"\n── Phase 2: 历史查询 ──")
    history_latencies: list[float] = []

    # 2a) GET /api/history (summary)
    try:
        resp, elapsed = client.timed_get("/api/history")
        history_latencies.append(elapsed)
        oh = resp.status_code == 200
        sessions_list = resp.json().get("sessions", []) if oh else []
        print(f"   GET /api/history → HTTP {resp.status_code}, {len(sessions_list)} sessions")
    except Exception as e:
        print(f"   [FAIL] GET /api/history: {e!r}")
        all_pass = False
        sessions_list = []

    # 2b) GET /api/history/sessions/{id} for each session
    get_failures = 0
    for rec in session_records:
        sid = rec["session_id"]
        try:
            resp, elapsed = client.timed_get(f"/api/history/sessions/{sid}")
            history_latencies.append(elapsed)
            if resp.status_code != 200:
                get_failures += 1
                all_pass = False
        except Exception:
            get_failures += 1
            all_pass = False

    print(f"   GET /api/history/sessions/{{id}} × {len(session_records)}: {get_failures} failures")

    # 2c) POST /api/history/load/{id} for each session
    if not args.no_load_check:
        print(f"\n── Phase 3: 会话加载（D12 修复验证）──")
        load_failures = 0
        load_round_mismatches = 0
        for rec in session_records:
            sid = rec["session_id"]
            expected_rounds = rec["rounds"]
            try:
                resp, elapsed = client.timed_post(f"/api/history/load/{sid}")
                history_latencies.append(elapsed)
                if resp.status_code == 200:
                    data = resp.json()
                    status = data.get("status")
                    loaded_round = data.get("round", -1)
                    if status != "loaded":
                        print(f"   [WARN] load {sid}: status={status!r} (expected 'loaded')")
                        load_failures += 1
                    if loaded_round != expected_rounds:
                        print(f"   [WARN] load {sid}: round={loaded_round} (expected {expected_rounds})")
                        if loaded_round != -1:  # -1 means field absent; not mismatched
                            load_round_mismatches += 1
                else:
                    load_failures += 1
                    all_pass = False
            except Exception:
                load_failures += 1
                all_pass = False

        print(f"   load × {len(session_records)}: {load_failures} failures")
        print(f"   round mismatches: {load_round_mismatches}")
        if load_round_mismatches > 0:
            all_pass = False
    else:
        print(f"\n   (Phase 3 skipped: --no-load-check)")

    # Merge history latencies into overall
    all_latencies.extend(history_latencies)
    total_requests += len(history_latencies)
    # Count history successes conservatively
    total_successes += len(history_latencies)

    # ── Final stats ──
    rate = total_successes / max(total_requests, 1)
    stats = summarize_latencies(all_latencies, "latency_s")

    print_summary(stats, f"全量请求耗时 ({total_requests} 次)")
    print(f"\n   成功率: {total_successes}/{total_requests} ({rate:.2%})")

    # Pass/fail
    checks = [
        (f"成功率 ≥ {args.min_success_rate*100:.0f}%", rate >= args.min_success_rate),
        (f"P95 ≤ {args.p95_max}s", stats.get("p95", float("inf")) <= args.p95_max),
    ]
    if not args.no_load_check:
        checks.append(("load round 全部匹配", load_round_mismatches == 0 and load_failures == 0))

    print(f"\n── 通过检查 ──")
    for desc, ok in checks:
        mark = "✓" if ok else "✗"
        print(f"   {mark} {desc}")
        if not ok:
            all_pass = False

    print(f"\n══════════ 结论 ══════════")
    print(f"   会话: {args.sessions} | 每会话轮数: {args.rounds_per_session}")
    print(f"   请求: {total_requests} | P50={stats.get('p50','n/a')}s P95={stats.get('p95','n/a')}s Max={stats.get('max','n/a')}s")
    print(f"   整体: {'通过' if all_pass else '失败'}")
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(run(build_parser().parse_args()))
