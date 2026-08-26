/**
 * useLocalCountdown.ts — 阶段倒计时本地计时 hook（决策 U9：倒计时不走 SSE 事件）
 *
 * 由 status.phase_elapsed_ms / phase_timeout_ms 本地计时：挂载/数值变化时以
 * 服务端 elapsed 为基准，每秒本地 tick 累加（不依赖轮询刷新即可走秒）；
 * phase_timeout_ms <= 0（默认 0 = 禁用强制推进）→ 返回 null（不显示）。
 */
import { useEffect, useState } from 'react';

export interface LocalCountdown {
  /** 剩余毫秒（timeout<=0 → null 不显示） */
  remainMs: number | null;
  /** 剩余秒数（向上取整；null 表示禁用） */
  remainSec: number | null;
  /** 是否已到 0（剩余 ≤0 且启用） */
  expired: boolean;
}

export function useLocalCountdown(
  elapsedMs: number | null | undefined,
  timeoutMs: number | null | undefined
): LocalCountdown {
  const [tick, setTick] = useState(0);

  const timeout = typeof timeoutMs === 'number' && timeoutMs > 0 ? timeoutMs : 0;
  const base = typeof elapsedMs === 'number' && elapsedMs >= 0 ? elapsedMs : 0;

  useEffect(() => {
    if (timeout <= 0) return;
    setTick(0);
    const t = setInterval(() => setTick(n => n + 1), 1000);
    return () => clearInterval(t);
  }, [timeout, base]);

  if (timeout <= 0) return { remainMs: null, remainSec: null, expired: false };

  const remain = Math.max(0, timeout - (base + tick * 1000));
  return {
    remainMs: remain,
    remainSec: Math.ceil(remain / 1000),
    expired: remain <= 0,
  };
}
