package com.roleplay.engine.broadcast;

/**
 * SSE 推送抽象 —— 解耦 AnnouncementService 与具体推送实现，便于单元测试注入假广播器。
 *
 * <p>{@code SSEController} 实现此接口（其 {@code broadcast(String, Object)} 签名天然匹配），
 * Spring 直接把 SSEController bean 注入 AnnouncementService；测试可用 lambda 捕获推送记录。
 */
public interface SseBroadcaster {

    /**
     * 向所有在线 SSE 客户端推送一个命名事件（data 由实现方序列化，通常为 JSON）。
     *
     * @param eventType 事件名（公告统一用 {@code "announcement"}）
     * @param data      载荷（Map 等可序列化对象）
     */
    void broadcast(String eventType, Object data);

    /**
     * P-0802-I：按会话定向推送 —— 仅投递给注册了指定 {@code sessionId} 的 SSE 连接。
     *
     * <p>用于狼人杀 werewolf_* 事件（D-013 已知限制「SSE 推送仍全局广播，多局并发各局事件串到同一连接」
     * 的阶段 1 修复）：多客户端/多对局并发时，各对局事件只到达本对局的连接，互不串扰。
     *
     * <p>默认实现回退到全局广播 —— 既有 SseBroadcaster 实现（测试录制器等）不实现本方法时
     * 行为与旧版一致（零破坏），{@code SSEController} 覆写为真正的定向投递。
     *
     * @param sessionId 对局会话标识（为空/空白时回退全局广播，向后兼容）
     * @param eventType 事件名
     * @param data      载荷
     */
    default void broadcastToSession(String sessionId, String eventType, Object data) {
        broadcast(eventType, data);
    }

    /**
     * 向指定会话内已认证的玩家连接推送私密事件。
     *
     * <p>默认实现安全丢弃；实现方必须显式提供玩家级投递能力，绝不把私密事件降级为整局广播。
     */
    default void broadcastToPlayers(String sessionId, String eventType, Object data, String... players) {
        // fail closed: 私密事件没有玩家级通道时宁可丢弃，也不能降级为整局/全局广播
    }
}
