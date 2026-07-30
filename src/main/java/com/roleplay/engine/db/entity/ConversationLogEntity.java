package com.roleplay.engine.db.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "conversation_logs")
public class ConversationLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String groupId;

    private String mode;

    private String participants;

    @Column(columnDefinition = "TEXT")
    private String messagesJson;

    private int tick;

    private LocalDateTime createdAt;

    public ConversationLogEntity() {}

    public ConversationLogEntity(String groupId, String mode, String participants,
                                  String messagesJson, int tick) {
        this.groupId = groupId;
        this.mode = mode;
        this.participants = participants;
        this.messagesJson = messagesJson;
        this.tick = tick;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getParticipants() { return participants; }
    public void setParticipants(String participants) { this.participants = participants; }

    public String getMessagesJson() { return messagesJson; }
    public void setMessagesJson(String messagesJson) { this.messagesJson = messagesJson; }

    public int getTick() { return tick; }
    public void setTick(int tick) { this.tick = tick; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
