/**
 * useAutoPlaybackDone.ts — P-0814-C：自动推进 hook（删除「▶ 推进下一轮」按钮，改播放完毕自动推进）
 *
 * 核心语义（主人拍板，P-0814-C）：播放完毕（打字机/消息展示队列排空）→ **自动** POST
 * /api/simulation/playback_done → 后端生成下一轮；玩家输入亦自动触发（输入=点击，既有机制保留）。
 * 触发点是「播放完成」事件（队列排空），**不是定时器**——保持 playback-driven 语义
 * （生成完停、播完才下一轮），节奏由播放速度（打字机/点击继续阅读）天然控制；
 * 禁止退回 auto-continue 定时无限跑（D-056 已废弃路径）。
 *
 * 关键时序（P-0814-C 实测锁定）：后端 POST /api/simulation/playback_done 是**阻塞式**——
 * 同步生成完下一轮才返回（10-40s）。因此：
 *  - in-flight 期间到达的新一轮武装不能丢：请求完成（成功）后若仍武装+排空 → 自动补发下一轮信号
 *    （播放驱动连续循环：每轮完成 → 信号 → 下一轮；节奏被后端生成时间天然约束）；
 *  - 失败不自动重发：armed 已在发信号前清除，由调用方 onAdvanceFailed 延迟重新武装（防无限重试刷后端）。
 *
 * 防重/防抖设计：
 *  - 同轮武装只发一次：onAdvancing（清除武装标志 + 记录已发信号轮次）在发请求前同步执行；
 *  - settleMs 稳定窗（默认 300ms）：队列排空后短暂停留，吸收「轮次完成与消息入队的到达顺序竞态」
 *    （轮询兜底武装可能早于 agent_output 入队、历史补拉可能稍后入队）——仍由 drain 事件驱动；
 *  - minGapMs 保守下限（默认 800ms）：防「空轮」（一轮无任何消息）时 drained 恒真导致超快连发；
 *    只作安全地板，不驱动节奏。
 *
 * P-0814-E「一问一答」门控（调用方经 enabled 控制）：
 *  - enabled=false（一般模式有玩家在场）：队列排空**不自动发信号**——AI 播完即停，等玩家输入
 *    （输入即推进：liveSay/api.send → 后端 runRound 玩家分支直接驱动一轮，无需 playback_done）；
 *  - enabled=true（导演模式无玩家 / 2D 组）：维持播完自动推进（无输入者，播完自动下一轮防卡死）。
 */
import { useEffect, useRef } from 'react';
import { api } from '../api/client';

export interface UseAutoPlaybackDoneOptions {
  /** 已武装（本轮播放完毕待推进；round_complete / 轮询兜底 / 新消息入队置位） */
  armed: boolean;
  /** 队列排空判定（调用方从 GalStore 计算：liveQueue.length===0 && !typing && !current） */
  drained: boolean;
  /** 推进目标：group_id 优先（2D 对话组），否则 session_id（一般模式 RouterService） */
  groupId?: string;
  sessionId?: string;
  /** 是否启用（如 2D 面板仅玩家所在组启用；未入组消息仅展示不推进；
   *  P-0814-E：一般模式有玩家时传 false=停等输入不发信号，一问一答） */
  enabled?: boolean;
  /** 队列排空后的稳定窗 ms（默认 300；吸收到达顺序竞态） */
  settleMs?: number;
  /** 两次信号的最小间隔 ms（默认 800；防空轮超快连发的安全地板） */
  minGapMs?: number;
  /** 发信号前回调（同步；调用方清除武装标志 + 记录已发信号轮次，防同轮重复触发） */
  onAdvancing?: () => void;
  /** 信号失败回调（调用方延迟重新武装；也可依赖轮询兜底重新武装） */
  onAdvanceFailed?: () => void;
}

export function useAutoPlaybackDone({
  armed,
  drained,
  groupId,
  sessionId,
  enabled = true,
  settleMs = 300,
  minGapMs = 800,
  onAdvancing,
  onAdvanceFailed,
}: UseAutoPlaybackDoneOptions) {
  const lastFireRef = useRef(0);
  const inflightRef = useRef(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const armedRef = useRef(armed);
  const drainedRef = useRef(drained);
  const enabledRef = useRef(enabled);
  const onAdvancingRef = useRef(onAdvancing);
  const onAdvanceFailedRef = useRef(onAdvanceFailed);
  const groupIdRef = useRef(groupId);
  const sessionIdRef = useRef(sessionId);
  const settleMsRef = useRef(settleMs);
  const minGapMsRef = useRef(minGapMs);
  armedRef.current = armed;
  drainedRef.current = drained;
  enabledRef.current = enabled;
  onAdvancingRef.current = onAdvancing;
  onAdvanceFailedRef.current = onAdvanceFailed;
  groupIdRef.current = groupId;
  sessionIdRef.current = sessionId;
  settleMsRef.current = settleMs;
  minGapMsRef.current = minGapMs;

  const clearTimer = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  };

  const doFire = () => {
    if (!armedRef.current || !drainedRef.current) return;
    inflightRef.current = true;
    lastFireRef.current = Date.now();
    onAdvancingRef.current?.();
    const gid = groupIdRef.current;
    const sid = sessionIdRef.current;
    api
      .simPlaybackDone(gid ? { group_id: gid } : { session_id: sid })
      .then(() => {
        inflightRef.current = false;
        // 阻塞式端点：响应=下一轮已生成完。in-flight 期间到达的下一轮武装不能丢
        // （armed 可能已在请求期间被 round_complete/轮询置位）→ 条件仍满足则补发下一轮信号，
        // 构成「每轮完成 → 信号 → 下一轮」的播放驱动连续循环（节奏由后端生成时间约束）。
        attemptFire();
      })
      .catch((e) => {
        inflightRef.current = false;
        console.warn('自动推进 playback_done 失败（将由调用方重新武装重试）', e);
        onAdvanceFailedRef.current?.();
      });
  };

  const attemptFire = () => {
    if (!enabledRef.current || !armedRef.current || !drainedRef.current) return;
    if (inflightRef.current) return; // 在途：请求完成后 .then 会再次 attemptFire
    clearTimer();
    timerRef.current = setTimeout(() => {
      // minGap 未满足（刚发过信号）→ 顺延到下限时刻再发，不丢
      const wait = Math.max(0, lastFireRef.current + minGapMsRef.current - Date.now());
      if (wait > 0) {
        timerRef.current = setTimeout(doFire, wait);
      } else {
        doFire();
      }
    }, settleMsRef.current);
  };

  useEffect(() => {
    attemptFire();
    return clearTimer;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [armed, drained, enabled, settleMs, minGapMs]);
}
