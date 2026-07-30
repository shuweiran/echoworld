package com.roleplay.engine.db.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "scenes")
public class SceneEntity {

    @Id
    private String id; // scene_id

    @Column(nullable = false)
    private String name;

    @Column(length = 5000)
    private String description;

    /** Comma-separated agent names */
    private String initialAgentNames;

    private LocalDateTime createdAt;

    public SceneEntity() {}

    public SceneEntity(String id, String name, String description, String initialAgentNames) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.initialAgentNames = initialAgentNames;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getInitialAgentNames() { return initialAgentNames; }
    public void setInitialAgentNames(String initialAgentNames) { this.initialAgentNames = initialAgentNames; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
