package com.roleplay.engine.db.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "world_snapshots")
public class WorldSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String scene;

    @Column(columnDefinition = "TEXT")
    private String agentsJson;

    private int tick;

    private LocalDateTime createdAt;

    public WorldSnapshotEntity() {}

    public WorldSnapshotEntity(String name, String scene, String agentsJson, int tick) {
        this.name = name;
        this.scene = scene;
        this.agentsJson = agentsJson;
        this.tick = tick;
        this.createdAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }

    public String getAgentsJson() { return agentsJson; }
    public void setAgentsJson(String agentsJson) { this.agentsJson = agentsJson; }

    public int getTick() { return tick; }
    public void setTick(int tick) { this.tick = tick; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
