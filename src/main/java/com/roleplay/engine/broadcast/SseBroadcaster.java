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
}
