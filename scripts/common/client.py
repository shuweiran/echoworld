# -*- coding: utf-8 -*-
"""Shared HTTP client + statistics helpers for stress/smoke scripts (UTF-8).

Provides:
  - HttpClient: thin requests.Session wrapper with base_url, timeout, JSON helpers
  - Stats helpers: percentile(), summarize_latencies(), print_summary()
  - Text generation: make_long_text() for injecting anchor words into repeated templates

Usage from scripts/stress/*.py:
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from common.client import HttpClient, percentile, summarize_latencies, print_summary, make_long_text
"""

import math
import random
import time
from typing import Any, Dict, List, Optional, Tuple

import requests


# ═══════════════════════════════════════════════════════════════════
#  HTTP client
# ═══════════════════════════════════════════════════════════════════

class HttpClient:
    """Thin wrapper around requests.Session with base_url and JSON helpers."""

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:8000",
        timeout: float = 60.0,
        session: Optional[requests.Session] = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._session = session or requests.Session()
        self._session.headers.setdefault("Content-Type", "application/json")

    def _url(self, path: str) -> str:
        return self.base_url + ("" if path.startswith("/") else "/") + path

    def request(
        self,
        method: str,
        path: str,
        json: Any = None,
        timeout: Optional[float] = None,
        **kwargs: Any,
    ) -> requests.Response:
        """Send an HTTP request; return the response (caller handles status)."""
        return self._session.request(
            method,
            self._url(path),
            json=json,
            timeout=timeout if timeout is not None else self.timeout,
            **kwargs,
        )

    def get(self, path: str, timeout: Optional[float] = None, **kwargs: Any) -> requests.Response:
        return self.request("GET", path, timeout=timeout, **kwargs)

    def post(
        self, path: str, json: Any = None, timeout: Optional[float] = None, **kwargs: Any
    ) -> requests.Response:
        return self.request("POST", path, json=json, timeout=timeout, **kwargs)

    def timed_post(self, path: str, json: Any = None, timeout: Optional[float] = None) -> Tuple[requests.Response, float]:
        """POST and return (response, elapsed_seconds) using perf_counter."""
        t0 = time.perf_counter()
        resp = self.post(path, json=json, timeout=timeout)
        elapsed = time.perf_counter() - t0
        return resp, elapsed

    def timed_get(self, path: str, timeout: Optional[float] = None) -> Tuple[requests.Response, float]:
        """GET and return (response, elapsed_seconds) using perf_counter."""
        t0 = time.perf_counter()
        resp = self.get(path, timeout=timeout)
        elapsed = time.perf_counter() - t0
        return resp, elapsed


# ═══════════════════════════════════════════════════════════════════
#  Statistics
# ═══════════════════════════════════════════════════════════════════

def percentile(values: List[float], p: float) -> float:
    """Return the p-th percentile (0..100) from a list of floats."""
    if not values:
        return 0.0
    if p <= 0:
        return values[0]
    if p >= 100:
        return values[-1]
    n = len(values)
    k = (p / 100.0) * (n - 1)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_values(values)[int(k)]
    d0 = sorted_values(values)[f] * (c - k)
    d1 = sorted_values(values)[c] * (k - f)
    return d0 + d1


def sorted_values(values: List[float]) -> List[float]:
    return sorted(values)


def summarize_latencies(
    latencies: List[float],
    label: str = "latency",
) -> Dict[str, Any]:
    """Summarise a list of latency measurements (seconds)."""
    if not latencies:
        return {"count": 0, label: "n/a"}
    s = sorted(latencies)
    return {
        "count": len(s),
        "min": round(s[0], 4),
        "p50": round(percentile(s, 50), 4),
        "p95": round(percentile(s, 95), 4),
        "p99": round(percentile(s, 99), 4),
        "max": round(s[-1], 4),
        "mean": round(sum(s) / len(s), 4),
    }


def print_summary(stats: Dict[str, Any], label: str = "Results") -> None:
    """Print a human-readable stats block."""
    print(f"\n── {label} ──")
    for key in ("count", "min", "p50", "p95", "p99", "max", "mean"):
        if key in stats:
            val = stats[key]
            print(f"  {key:>5s}: {val}")


# ═══════════════════════════════════════════════════════════════════
#  Long text generation
# ═══════════════════════════════════════════════════════════════════

_TEXT_TEMPLATE = (
    "这是一段用于压力测试的文本内容。"
    "用于模拟超长消息场景，验证系统在高负载下的稳定性和响应能力。"
    "每个字符都经过精心安排以确保测试可重复性和结果可比性。"
    "测试内容包括中文字符、英文字符、标点符号以及各种引用文本。"
    "系统需要正确处理编码转换、内存分配和流式输出等各个环节。"
    "在真实业务场景中，用户可能会输入非常长的对话内容，"
    "例如角色扮演中的长篇背景故事、详细的场景描述或者长篇对话历史。"
    "因此系统必须具备处理超长文本的能力，"
    "包括但不限于 JSON 序列化/反序列化、内存管理、文本截断和流式传输。"
    "本测试通过生成大量重复但结构合理的文本，"
    "模拟这些极端场景以评估系统在压力下的表现。"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ-abcdefghijklmnopqrstuvwxyz-0123456789。"
)
_TEMPLATE_LEN = len(_TEXT_TEMPLATE)


def make_long_text(
    target_chars: int,
    anchor: str = "",
    anchor_position: int = 0,
    fill_template: str = "",
) -> str:
    """Generate text of approximately *target_chars* Chinese/ASCII characters.

    Parameters:
        target_chars: desired total character count (approximate).
        anchor: optional anchor word/phrase to inject for retrievability tests.
        anchor_position: character position at which anchor is inserted
                         (0 = prepend; -1 = append; positive = position from start).
        fill_template: custom repeating text (defaults to the built-in template).

    Returns:
        A string of approximately target_chars characters.
    """
    tmpl = fill_template if fill_template else _TEXT_TEMPLATE
    reps = max(1, (target_chars // len(tmpl)) + 2)
    body = (tmpl * reps)[:target_chars]

    if not anchor:
        return body

    if anchor_position < 0:
        return body + anchor
    if anchor_position == 0:
        return anchor + body[-len(anchor):] if len(body) >= len(anchor) else anchor + body
    # Insert at specific position (keep total length ≈ target_chars)
    pos = min(anchor_position, len(body))
    return body[:pos] + anchor + body[pos:]


def make_long_persona(target_chars: int, name: str = "角色A", anchor: str = "") -> str:
    """Generate a long persona description."""
    base = make_long_text(target_chars, anchor=anchor, anchor_position=0)
    return f"{name}是{base}"
