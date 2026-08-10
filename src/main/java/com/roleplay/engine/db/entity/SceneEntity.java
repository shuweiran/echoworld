package com.roleplay.engine.db.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "scenes")
public class SceneEntity {

    @Id
    private String id; // scene_id

    @Column(nullable = false, length = 2000)
    private String name;

    @Column(length = 5000)
    private String description;

    /** Comma-separated agent names */
    private String initialAgentNames;

    /** Free-form keywords carried by the client (persisted for restart fidelity) */
    @Column(length = 2000)
    private String keywords;

    /** P-0803-H：剧本/场景分类 —— general=一般模式 / werewolf=狼人杀模式（默认 general） */
    @Column(length = 32)
    private String category;

    /** P-0803-H：剧本绑定默认角色组（JSON 数组字符串，如 ["苏哲","林诗"]；前端选择剧本时自动选中） */
    @Column(length = 4000)
    private String defaultRoles;

    /** P-0803-H：剧本绑定默认地图（地图 JSON 契约 v1 字符串，可空；前端剧本卡点开后地图预览用） */
    @Column(length = 40000)
    private String defaultMap;

    /** P-0810-09：场景目标集（JSON 字符串：{global_goal, role_goals, player_goal}，可空=旧数据无目标） */
    @Column(length = 8000)
    private String goals;

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

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDefaultRoles() { return defaultRoles; }
    public void setDefaultRoles(String defaultRoles) { this.defaultRoles = defaultRoles; }

    public String getDefaultMap() { return defaultMap; }
    public void setDefaultMap(String defaultMap) { this.defaultMap = defaultMap; }

    public String getGoals() { return goals; }
    public void setGoals(String goals) { this.goals = goals; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
