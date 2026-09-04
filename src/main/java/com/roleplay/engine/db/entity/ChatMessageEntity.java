package com.roleplay.engine.db.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * P1 消息持久化：一般模式聊天消息单行记录（SSE 流式/结算/历史补拉的 DB 事实源）。
 *
 * <p>状态机：STREAMING（流式生成中）→ FINAL（完成）/ FAILED（中断失败）。
 * 用户消息与非流式 Agent 消息直接 FINAL 落库。messageId 全局唯一（后端生成，
 * 与内存 {@code Message.messageId}、SSE {@code message_id} 同源）。
 */
@Entity
@Table(name = "chat_messages",
        indexes = {@Index(name = "idx_chat_messages_session", columnList = "sessionId")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_chat_messages_message_id", columnNames = {"messageId"})})
public class ChatMessageEntity {

    public static final String STATUS_STREAMING = "STREAMING";
    public static final String STATUS_FINAL = "FINAL";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String messageId;

    /** 所属会话（SessionRegistry session_id；空=默认单例兼容会话）。 */
    @Column(nullable = false, length = 64)
    private String sessionId;

    private int roundNumber;

    /** agent / user / arbiter / system（小写，与 Message.Role 对齐）。 */
    @Column(length = 16)
    private String role;

    /** 发言者展示名。 */
    @Column(length = 128)
    private String name;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String content;

    /** STREAMING / FINAL / FAILED。 */
    @Column(length = 16)
    private String status;

    @Column(length = 64)
    private String trackId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public ChatMessageEntity() {}

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public int getRoundNumber() { return roundNumber; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
