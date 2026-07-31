package com.roleplay.engine.interrupt;

/**
 * 协作式取消令牌（需求文档第八条 §五）。
 *
 * <p>LLM 生成循环中在"检查点"调用 {@link #checkpoint()}：
 *
 * <pre>
 * while (stream.hasNext()) {
 *     cancelToken.checkpoint();      // ← 检查点
 *     send(token);
 * }
 * </pre>
 *
 * <p>取消语义：
 * <ul>
 *   <li>{@link StopType#HARD} / {@link StopType#STATE_INVALID}：令牌置位 +
 *       InterruptManager 立即中断关联线程（abort 进行中的 HTTP 调用）</li>
 *   <li>{@link StopType#SOFT}：仅令牌置位，等当前 LLM 调用结束后在下一个检查点
 *       退出，已生成内容由上层保存为未完成状态</li>
 * </ul>
 *
 * <p>线程安全：volatile 布尔 + synchronized 置位，一次取消不可逆。
 */
public class CancellationToken {

    private volatile boolean cancelled = false;
    private volatile StopType stopType;
    private volatile String reason;

    /** 是否已收到取消信号。 */
    public boolean isCancelled() { return cancelled; }

    public StopType getStopType() { return stopType; }
    public String getReason() { return reason; }

    /** 置位取消信号（幂等，先到先得）。 */
    public synchronized void cancel(StopType stopType, String reason) {
        if (cancelled) return;
        this.cancelled = true;
        this.stopType = stopType != null ? stopType : StopType.HARD;
        this.reason = reason != null ? reason : "";
    }

    /** 检查点：已取消则抛出 {@link TaskCancelledException} 中断当前生成。 */
    public void throwIfCancelled() {
        if (cancelled) {
            throw new TaskCancelledException(stopType, reason);
        }
    }

    /** 检查点别名（需求文档 §五 的 while 循环内调用）。 */
    public void checkpoint() {
        throwIfCancelled();
    }
}
