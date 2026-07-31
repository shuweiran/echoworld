# -*- coding: utf-8 -*-
"""stress_long_message.py — LONG-01 超长单条消息压测（E2E 抽样版，UTF-8）

目标端点：POST /api/init  →  POST /api/send
对应测试方案 §9.1 LONG-01：单条消息 ≥10 万字，验证不崩溃/不 OOM/响应正常。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
用法（python scripts/stress/stress_long_message.py --help）：  
  python scripts/stress/stress_long_message.py --chars 10000 50000 100000
  python scripts/stress/stress_long_message.py --chars 100000 --base-url http://192.168.1.100:8000
  python scripts/stress/stress_long_message.py --chars 10000 --use-round-start  # 走 /api/round/start（含服务端 metrics）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

通过标准（对齐 §9.1）：
  LONG-01: ① 所有档位 HTTP 200，无 500/413/连接断开
           ② 单档总请求客户端耗时 P95 ≤ 10s（--p95-total）
           ③ 成功率 = 100%（--min-success-rate 1.0）

注意：
  - 上下文构建段耗时无法通过 /api/send 直接测量（响应不返回 timing）；若需该指标，
    请使用 --use-round-start 走 /api/round/start（响应含 metrics.total_round_time_ms）。
  - 在无可用 LLM（401）的环境下，脚本会诚实地记录失败（HTTP 4xx/5xx），非编造数据。
"""

import argparse
import os
import sys
import textwrap

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.client import HttpClient, make_long_text, percentile, summarize_latencies, print_summary


ANCHOR_PFX = "锚点词LONG01-"


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="stress_long_message — LONG-01 超长单条消息压测",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
            示例：
              python stress_long_message.py --chars 10000 50000 100000
              python stress_long_message.py --chars 100000 --base-url http://192.168.1.100:8000
              python stress_long_message.py --chars 10000 --use-round-start --p95-total 30

            通过标准（对齐测试方案 §9.1）：
              ① 所有档位 HTTP 200，无 500/413
              ② 总请求客户端耗时 P95 ≤ 10s（--p95-total，默认 10）
              ③ 成功率 ≥ --min-success-rate（默认 1.0）
            """))
    p.add_argument("--base-url", default="http://127.0.0.1:8000",
                   help="后端 base URL（默认 http://127.0.0.1:8000）")
    p.add_argument("--timeout", type=float, default=120.0,
                   help="单请求超时秒数（默认 120；10 万字档建议 ≥120）")
    p.add_argument("--chars", type=int, nargs="+", default=[10000],
                   help="消息长度档位（字符数），如 --chars 10000 50000 100000")
    p.add_argument("--anchor-prefix", default=ANCHOR_PFX,
                   help="锚点词前缀（默认 %(default)s）")
    p.add_argument("--p95-total", type=float, default=10.0,
                   help="总请求客户端耗时 P95 通过阈值秒数（默认 10s，§9.1）")
    p.add_argument("--p95-build", type=float, default=5.0,
                   help="上下文构建段 P95 通过阈值秒数（默认 5s，仅 --use-round-start 有效）")
    p.add_argument("--min-success-rate", type=float, default=1.0,
                   help="最低成功率（默认 1.0 = 100%%）")
    p.add_argument("--use-round-start", action="store_true",
                   help="走 /api/round/start 端点（响应含服务端 metrics，支持构建段计时）")
    p.add_argument("--repeat", type=int, default=3,
                   help="每档重复次数取稳定结论（默认 3，对齐 §9.1）")
    return p


def run(args: argparse.Namespace) -> int:
    print(f"=== stress_long_message (LONG-01 抽样) ===")
    print(f"    base_url: {args.base_url}")
    print(f"    chars:    {args.chars}")
    print(f"    repeat:   {args.repeat}")
    print(f"    endpoint: {'/api/round/start' if args.use_round_start else '/api/send'}")

    client = HttpClient(args.base_url, timeout=args.timeout)
    all_pass = True
    overall_results: list[dict] = []

    for char_len in args.chars:
        print(f"\n── 档位 {char_len:,} 字 ──")
        latencies_total: list[float] = []
        latencies_build: list[float] = []  # only populated when --use-round-start
        successes = 0
        total = args.repeat
        anchor_word = f"{args.anchor_prefix}{char_len}"

        for i in range(total):
            # 1) Init session
            try:
                r_init = client.post("/api/init", json={
                    "characters": [{"name": "压力测试角色", "persona": "用于长文本压测的测试角色"}],
                    "scene": "压测场景",
                })
                init_ok = r_init.status_code == 200
            except Exception as e:
                print(f"   [FAIL] init #{i+1}: {e!r}")
                all_pass = False
                continue

            # 2) long message
            msg = make_long_text(char_len, anchor=anchor_word, anchor_position=0)
            payload = {"message": msg}

            try:
                if args.use_round_start:
                    payload_round = {"message": msg, "turns": 1}
                    resp, elapsed = client.timed_post("/api/round/start", json=payload_round)
                else:
                    resp, elapsed = client.timed_post("/api/send", json=payload)

                latencies_total.append(elapsed)

                if resp.status_code == 200:
                    data = resp.json()
                    successes += 1
                    # Extract server-reported metrics if available (/api/round/start)
                    metrics = data.get("metrics", {})
                    if metrics:
                        build_ms = metrics.get("total_round_time_ms", 0) / 1000.0
                        latencies_build.append(build_ms)
                    status_str = "PASS"
                    detail = (f"{elapsed:.2f}s"
                              + (f" server={build_ms:.2f}s" if metrics else ""))
                else:
                    status_str = "FAIL"
                    try:
                        detail = f"HTTP {resp.status_code} {resp.json()}"
                    except Exception:
                        detail = f"HTTP {resp.status_code} {resp.text[:120]}"
                    all_pass = False

                print(f"   [{status_str}] #{i+1}: {detail}")
            except requests.exceptions.ConnectionError:
                print(f"   [FAIL] #{i+1}: connection refused — 后端未启动？")
                all_pass = False
            except Exception as e:
                print(f"   [FAIL] #{i+1}: {e!r}")
                all_pass = False

        # Summarise this tier
        stats_total = summarize_latencies(latencies_total, "total_latency_s")
        rate = successes / max(total, 1)
        passed_tier = (
            rate >= args.min_success_rate
            and stats_total.get("p95", float("inf")) <= args.p95_total
        )
        if latencies_build:
            stats_build = summarize_latencies(latencies_build, "build_latency_s")
            build_p95_ok = stats_build.get("p95", float("inf")) <= args.p95_build
            passed_tier = passed_tier and build_p95_ok
        else:
            stats_build = {"count": 0, "note": "n/a (未使用 --use-round-start，无服务端 build 计时)"}

        print_summary(stats_total, f"总请求耗时 · {char_len:,}字")
        if stats_build.get("count", 0) > 0:
            print_summary(stats_build, f"上下文构建段 · {char_len:,}字")

        tier_result = {
            "chars": char_len,
            "total": total,
            "successes": successes,
            "success_rate": round(rate, 4),
            "total_latency": stats_total,
            "build_latency": stats_build,
            "passed": passed_tier,
        }
        overall_results.append(tier_result)

        check_items = [
            (f"HTTP 200 (≥{args.min_success_rate*100:.0f}%)", rate >= args.min_success_rate),
            (f"总耗时 P95 ≤ {args.p95_total}s", stats_total.get("p95", float("inf")) <= args.p95_total),
        ]
        if latencies_build:
            check_items.append((f"构建段 P95 ≤ {args.p95_build}s", build_p95_ok))
        for desc, ok in check_items:
            print(f"   {'✓' if ok else '✗'} {desc}")
        print(f"   档位结论: {'通过' if passed_tier else '失败'}")
        if not passed_tier:
            all_pass = False

    # Final summary
    print("\n══════════ 最终结论 ══════════")
    for r in overall_results:
        status = "通过" if r["passed"] else "失败"
        print(f"  {r['chars']:,}字: {status}  (成功率 {r['success_rate']:.2%}, "
              f"P95={r['total_latency'].get('p95','n/a')}s)")
    print(f"  整体: {'通过' if all_pass else '失败'}")
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(run(build_parser().parse_args()))
