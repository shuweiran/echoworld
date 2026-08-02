package com.roleplay.engine.db.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "characters")
public class CharacterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(length = 2000)
    private String persona;

    private String voice;

    @Column(length = 2000)
    private String background;

    /**
     * 玩家身份模型（改造方案 §3.1/§3.2）：客户端持有并持久化的玩家唯一 UUID。
     * 绑定语义：一个角色最多绑定一个玩家（unique）；null = 未绑定（NPC/库内普通角色）。
     * 判定链路后续按 player_id → 当前角色名动态解析，改名无需同步缓存。
     */
    @Column(unique = true, nullable = true)
    private String playerId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public CharacterEntity() {}

    public CharacterEntity(String name, String persona, String voice, String background) {
        this(name, persona, voice, background, null);
    }

    public CharacterEntity(String name, String persona, String voice, String background, String playerId) {
        this.name = name;
        this.persona = persona;
        this.voice = voice;
        this.background = background;
        this.playerId = playerId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }

    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }

    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }

    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
