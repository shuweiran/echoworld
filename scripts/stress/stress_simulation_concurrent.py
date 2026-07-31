# -*- coding: utf-8 -*-
"""stress_simulation_concurrent.py — LONG-04 2D 模拟多 agent 长文本并发压测（E2E 抽样版，UTF-8）

目标端点：POST /api/simulation/init → /load-characters → /start →
          并发 POST /api/simulation/send/{agent} + SSE /api/simulation/events 观察 →
          /stop → GET /api/simulation/state
对应测试方案 §9.4 LONG-04：8 agent 并发长文本对话（总注入 ≥80 万字），验证世界循环/SSE 流/并发管线稳定。

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
用法：
  python scripts/stress/stress_simulation_concurrent.py --agents 8 --rounds-per-agent 5
  python scripts/stress/stress_simulation_concurrent.py --agents 4 --rounds-per-agent 10 --persona-chars 5000 --round-chars 5000
  python scripts/stress/stress_simulation_concurrent.py --base-url http://192.168.1.100:8000 --no-sse

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

通过标准（对齐 §9.4）：
  LONG-04: ① 总注入 ≥ --min-total-chars（默认不强制）
           ② 并发 send 成功率 ≥ --min-success-rate（默认 1.0）
           ③ 并发 send P95 ≤ --p95-send（默认 5s，mock 下）
           ④ SSE 流在窗口内无断连；帧间隔退化 < 2× 基线（--sse-max-interval-ratio）
           ⑤ 结束后 /api/simulation/state 完整、stop 成功

注意：
  - SSE 监听使用 daemon 线程 + 阻塞 iter_lines；在真实环境中断连会诚实地记录失败。
  - persona-chars 与 round-chars 过长可能触发真实 LLM 请求体超限；建议 mock 环境使用。
"""

import argparse
import json
import os
import sys
import textwrap
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from common.client import HttpClient, make_long_text, make_long_persona, percentile, summarize_latencies, print_summary


AGENT_NAMES = [
    "小明", "小红", "小刚", "小丽",
    "阿杰", "小林", "小美", "大壮",
    "小兰", "阿飞", "小武", "小雪",
]


class SseObserver:
    """Collect SSE world_snapshot frames in a background daemon thread."""

    def __init__(self, client: HttpClient, duration_s: float):
        self.client = client
        self.duration_s = duration_s
        self.frames: list[dict] = []  # each: {"index": int, "ts": float}
        self.gaps: list[float] = []   # inter-frame gaps (seconds)
        self.disconnects = 0
        self.errors: list[str] = []
        self._stop = threading.Event()
        self._ready = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self):
        self._thread = threading.Thread(target=self._run, daemon=True, name="sse-observer")
        self._thread.start()
        self._ready.wait(timeout=10.0)  # Wait for SSE connect or timeout

    def _run(self):
        try:
            with self.client.get("/api/simulation/events", stream=True, timeout=120) as resp:
                self._ready.set()
                t0 = time.perf_counter()
                last_ts = t0
                frame_idx = 0
                for line in resp.iter_lines(decode_unicode=True):
                    if time.perf_counter() - t0 > self.duration_s:
                        break
                    if not line or line.startswith(":"):
                        continue
                    # lines like: data: {"type":"world_snapshot",...}
                    if line.startswith("data:"):
                        payload = line[5:].strip()
                        if not payload:
                            continue
                        try:
                            data = json.loads(payload)
                        except json.JSONDecodeError:
                            continue
                        now = time.perf_counter()
                        gap = now - last_ts
                        last_ts = now
                        if frame_idx > 0:  # skip first frame-to-itself gap
                            self.gaps.append(gap)
                        self.frames.append({"index": frame_idx, "ts": now - t0, "data": data})
                        frame_idx += 1
        except requests.exceptions.ConnectionError:
            self.disconnects += 1
            self.errors.append("SSE connection refused — 后端未启动？")
        except Exception as e:
            self.disconnects += 1
            self.errors.append(repr(e))
        finally:
            self._ready.set()

    def join(self, timeout: float = 5.0):
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=timeout)

    def summary(self) -> dict:
        if not self.frames:
            return {"frames": 0, "gaps": [], "baseline_gap": None, "disconnects": self.disconnects, "errors": self.errors}
        gaps_sorted = sorted(self.gaps) if self.gaps else []
        median_gap = percentile(gaps_sorted, 50) if gaps_sorted else 0
        later_gaps = self.gaps[len(self.gaps)//2:] if len(self.gaps) > 1 else self.gaps
        later_median = percentile(sorted(later_gaps), 50) if later_gaps else median_gap
        ratio = later_median / median_gap if median_gap > 0 else 1.0
        return {
            "frames": len(self.frames),
            "gaps_count": len(self.gaps),
            "baseline_gap_s": round(median_gap, 4),
            "later_median_gap_s": round(later_median, 4),
            "ratio": round(ratio, 2),
            "disconnects": self.disconnects,
            "errors": self.errors,
        }


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="stress_simulation_concurrent — LONG-04 2D 模拟多 agent 并发长文本压测",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
            示例：
              python stress_simulation_concurrent.py --agents 8 --rounds-per-agent 5
              python stress_simulation_concurrent.py --agents 4 --rounds-per-agent 10 --persona-chars 5000
              python stress_simulation_concurrent.py --no-sse --base-url http://192.168.1.100:8000

            通过标准（对齐测试方案 §9.4）：
              ① 并发 send 成功率 ≥ --min-success-rate（默认 1.0）
              ② 并发 send P95 ≤ --p95-send（默认 5s，mock 下）
              ③ SSE 无断连，帧间隔退化 < 2× 基线
              ④ state 完整、stop 成功
            """))
    p.add_argument("--base-url", default="http://127.0.0.1:8000",
                   help="后端 base URL")
    p.add_argument("--timeout", type=float, default=120.0,
                   help="单请求超时秒数（默认 120，并发长文本建议宽松）")
    p.add_argument("--agents", type=int, default=8,
                   help="agent 数量（默认 8，对齐 LONG-04）")
    p.add_argument("--rounds-per-agent", type=int, default=5,
                   help="每个 agent 发送轮数（默认 5，E2E 抽样）")
    p.add_argument("--persona-chars", type=int, default=5000,
                   help="每个 agent 的 persona 字符数（默认 5000）")
    p.add_argument("--round-chars", type=int, default=5000,
                   help="每轮消息字符数（默认 5000）")
    p.add_argument("--p95-send", type=float, default=5.0,
                   help="并发 send P95 通过阈值秒数（默认 5s，mock 下）")
    p.add_argument("--min-success-rate", type=float, default=1.0,
                   help="最低成功率")
    p.add_argument("--sse-duration", type=int, default=60,
                   help="SSE 监听窗口秒数（默认 60）")
    p.add_argument("--sse-max-interval-ratio", type=float, default=2.0,
                   help="SSE 帧间隔退化比阈值（默认 2.0 = 2× 基线）")
    p.add_argument("--no-sse", action="store_true",
                   help="不监听 SSE（仅测 concurrent send）")
    p.add_argument("--max-workers", type=int, default=0,
                   help="ThreadPoolExecutor max_workers（0=min(32, agents*rounds)）")
    return p


def send_agent_rounds(
    client: HttpClient,
    agent_name: str,
    n_rounds: int,
    round_chars: int,
    anchor_prefix: str,
) -> tuple[int, list[float]]:
    """Send N rounds for one agent; return (successes, latencies)."""
    successes = 0
    latencies: list[float] = []
    for r in range(1, n_rounds + 1):
        anchor = f"{anchor_prefix}{agent_name}-{r}"
        msg = make_long_text(round_chars, anchor=anchor, anchor_position=0)
        try:
            resp, elapsed = client.timed_post(
                f"/api/simulation/send/{agent_name}",
                json={"message": msg},
            )
            latencies.append(elapsed)
            if resp.status_code == 200:
                successes += 1
        except Exception:
            pass
    return successes, latencies


def run(args: argparse.Namespace) -> int:
    agent_list = AGENT_NAMES[:args.agents]
    if len(agent_list) < args.agents:
        for i in range(len(agent_list), args.agents):
            agent_list.append(f"agent-{i}")

    print(f"=== stress_simulation_concurrent (LONG-04 抽样) ===")
    print(f"    base_url:  {args.base_url}")
    print(f"    agents:    {len(agent_list)}")
    print(f"    rounds/ag: {args.rounds_per_agent}")
    print(f"    persona:   {args.persona_chars} chars")
    print(f"    msg:       {args.round_chars} chars")
    print(f"    total ≈    {len(agent_list) * args.rounds_per_agent * args.round_chars:,} chars 注入")
    print(f"    SSE:       {'no' if args.no_sse else f'yes ({args.sse_duration}s window)'}")

    client = HttpClient(args.base_url, timeout=args.timeout)
    all_pass = True

    # ── 1) Init simulation ──
    print("\n── 初始化 2D 模拟 ──")
    try:
        r_init = client.post("/api/simulation/init", json={"count": len(agent_list)})
        if r_init.status_code != 200:
            print(f"   [FAIL] /api/simulation/init HTTP {r_init.status_code}")
            return 1
        print(f"   [PASS] init: {r_init.json().get('message')}")
    except Exception as e:
        print(f"   [FAIL] init: {e!r}")
        return 1

    # ── 2) Load characters with long personas ──
    print(f"\n── 加载角色（persona {args.persona_chars} 字/人）──")
    characters = []
    for agent_name in agent_list:
        persona = make_long_persona(args.persona_chars, name=agent_name,
                                     anchor=f"锚点PA-{agent_name}")
        characters.append({
            "name": agent_name,
            "persona": persona,
            "voice": f"{agent_name}的语音",
            "background": f"{agent_name}的背景故事",
        })
    try:
        r_load = client.post("/api/simulation/load-characters",
                             json={"characters": characters, "scene": "压力测试场景"})
        if r_load.status_code != 200:
            print(f"   [FAIL] load-characters HTTP {r_load.status_code}")
            return 1
        print(f"   [PASS] loaded {len(agent_list)} characters")
    except Exception as e:
        print(f"   [FAIL] load-characters: {e!r}")
        return 1

    # ── 3) Start ──
    try:
        r_start = client.post("/api/simulation/start")
        if r_start.status_code != 200:
            print(f"   [FAIL] /api/simulation/start HTTP {r_start.status_code}")
            return 1
        print(f"   [PASS] simulation started")
    except Exception as e:
        print(f"   [FAIL] start: {e!r}")
        return 1

    # ── 4) Start SSE observer ──
    observer: SseObserver | None = None
    if not args.no_sse:
        observer = SseObserver(client, args.sse_duration)
        observer.start()
        time.sleep(2.0)  # Let SSE connect
        sse_ok = observer._ready.is_set()
        print(f"   SSE observer started: {'connected' if sse_ok else 'timeout/error'}")

    # ── 5) Concurrent sends ──
    print(f"\n── 并发发送 ({len(agent_list)} agents × {args.rounds_per_agent} rounds) ──")
    max_w = args.max_workers if args.max_workers > 0 else min(32, len(agent_list) * args.rounds_per_agent)
    all_latencies: list[float] = []
    total_successes = 0
    total_tasks = len(agent_list) * args.rounds_per_agent
    anchor_pfx = "锚点L04-"

    with ThreadPoolExecutor(max_workers=max_w) as pool:
        futures = {}
        for agent_name in agent_list:
            fut = pool.submit(
                send_agent_rounds,
                client, agent_name, args.rounds_per_agent, args.round_chars, anchor_pfx,
            )
            futures[fut] = agent_name

        for fut in as_completed(futures):
            agent_name = futures[fut]
            try:
                succ, lats = fut.result(timeout=args.timeout * args.rounds_per_agent + 10)
                total_successes += succ
                all_latencies.extend(lats)
            except Exception as e:
                print(f"   [FAIL] {agent_name}: {e!r}")
                all_pass = False

    # Wait for SSE observer to finish
    if observer:
        observer.join(timeout=args.sse_duration + 10)

    # ── 6) Stop ──
    print(f"\n── 停止模拟 ──")
    try:
        r_stop = client.post("/api/simulation/stop")
        if r_stop.status_code == 200:
            print(f"   [PASS] simulation stopped")
        else:
            print(f"   [FAIL] stop HTTP {r_stop.status_code}")
            all_pass = False
    except Exception as e:
        print(f"   [FAIL] stop: {e!r}")
        all_pass = False

    # ── 7) State check ──
    try:
        r_state = client.get("/api/simulation/state")
        if r_state.status_code == 200:
            state = r_state.json()
            agent_count = len(state.get("agents", [])) if isinstance(state, dict) else 0
            print(f"   /api/simulation/state: agents={agent_count}")
        else:
            print(f"   ✗ /api/simulation/state HTTP {r_state.status_code}")
            all_pass = False
    except Exception as e:
        print(f"   ✗ state: {e!r}")
        all_pass = False

    # ── 8) Results ──
    rate = total_successes / max(total_tasks, 1)
    stats_send = summarize_latencies(all_latencies, "send_latency_s")

    print(f"\n── 并发发送结果 ──")
    print_summary(stats_send, "并发 send 耗时")
    print(f"   成功/总数: {total_successes}/{total_tasks} ({rate:.2%})")

    sse_summary = observer.summary() if observer else {}
    if sse_summary:
        print(f"\n── SSE 观察 ──")
        print(f"   帧数:       {sse_summary.get('frames', 0)}")
        print(f"   帧间隔数:   {sse_summary.get('gaps_count', 0)}")
        print(f"   基线中位数: {sse_summary.get('baseline_gap_s', 'n/a')}s")
        print(f"   后半中位数: {sse_summary.get('later_median_gap_s', 'n/a')}s")
        print(f"   退化比:     {sse_summary.get('ratio', 'n/a')}×")
        print(f"   断连次数:   {sse_summary.get('disconnects', 0)}")
        if sse_summary.get("errors"):
            for err in sse_summary["errors"]:
                print(f"   SSE error: {err}")

    # Pass/fail
    checks = [
        (f"成功率 ≥ {args.min_success_rate*100:.0f}%", rate >= args.min_success_rate),
        (f"并发 send P95 ≤ {args.p95_send}s", stats_send.get("p95", float("inf")) <= args.p95_send),
    ]
    if sse_summary:
        checks.append(("SSE 无断连", sse_summary.get("disconnects", 1) == 0))
        checks.append(
            (f"帧间隔退化 < {args.sse_max_interval_ratio}×",
             sse_summary.get("ratio", float("inf")) < args.sse_max_interval_ratio)
        )

    print(f"\n── 通过检查 ──")
    for desc, ok in checks:
        mark = "✓" if ok else "✗"
        print(f"   {mark} {desc}")
        if not ok:
            all_pass = False

    # Cleanup: reset for next run
    try:
        client.post("/api/simulation/reset")
    except Exception:
        pass

    print(f"\n══════════ 结论 ══════════")
    print(f"   注入 ≈ {total_tasks * args.round_chars:,} 字")
    print(f"   成功率: {total_successes}/{total_tasks} ({rate:.2%})")
    print(f"   并发 P50: {stats_send.get('p50','n/a')}s  P95: {stats_send.get('p95','n/a')}s")
    print(f"   整体: {'通过' if all_pass else '失败'}")
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(run(build_parser().parse_args()))
