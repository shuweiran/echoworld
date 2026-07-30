package com.roleplay.engine.db.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "game_sessions")
public class GameSessionEntity {

    @Id
    private String id; // UUID

    @Column(name = "session_type", nullable = false)
    private String sessionType; // free / werewolf / 2d / script

    private String name;

    @Column(columnDefinition = "TEXT")
    private String stateJson;

    /** Comma-separated agent names */
    private String agents;

    private String currentScene;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public GameSessionEntity() {}

    public GameSessionEntity(String id, String sessionType, String name,
                              String stateJson, String agents, String currentScene) {
        this.id = id;
        this.sessionType = sessionType;
        this.name = name;
        this.stateJson = stateJson;
        this.agents = agents;
        this.currentScene = currentScene;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionType() { return sessionType; }
    public void setSessionType(String sessionType) { this.sessionType = sessionType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }

    public String getAgents() { return agents; }
    public void setAgents(String agents) { this.agents = agents; }

    public String getCurrentScene() { return currentScene; }
    public void setCurrentScene(String currentScene) { this.currentScene = currentScene; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
