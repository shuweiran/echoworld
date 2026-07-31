package com.roleplay.engine.interrupt;

/**
 * 任务取消信号（运行时异常，方便从 Callable / 生成链路任意检查点抛出）。
 *
 * <p>当 {@link CancellationToken} 被置位后，生成链路上的检查点调用
 * {@link CancellationToken#throwIfCancelled()} 抛出本异常，携带停止类型与原因；
 * 软停止（SOFT）时 {@link #partial} 保存"已生成但未提交"的内容（未完成状态）。
 */
public class TaskCancelledException extends RuntimeException {

    private final StopType stopType;
    private final String reason;
    /** SOFT 停止时：已生成但尚未提交的未完成内容（可为 null）。 */
    private volatile String partial;

    public TaskCancelledException(StopType stopType, String reason) {
        super(buildMessage(stopType, reason));
        this.stopType = stopType;
        this.reason = reason;
    }

    public TaskCancelledException(StopType stopType, String reason, Throwable cause) {
        super(buildMessage(stopType, reason), cause);
        this.stopType = stopType;
        this.reason = reason;
    }

    private static String buildMessage(StopType stopType, String reason) {
        return "task cancelled (" + stopType + "): " + (reason != null ? reason : "");
    }

    public StopType getStopType() { return stopType; }
    public String getReason() { return reason; }
    public String getPartial() { return partial; }
    public void setPartial(String partial) { this.partial = partial; }
}
