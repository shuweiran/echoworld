package com.roleplay.engine.db.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * P-0804-C：素材库实体（assets 表）—— 角色像素动画 / 场景瓦片图集素材登记层。
 *
 * <p>设计（对齐 CharacterEntity/SceneEntity 风格，不扩既有表结构、新表独立）：
 * <ul>
 *   <li>素材只做「登记」：元数据 + 文件路径引用，不承载文件实体（文件由调用方预先放置到
 *       {@code src/main/resources/static/assets/} 下，filePath 为相对路径）；</li>
 *   <li>关联不设外键约束（防级联麻烦）：characterName 关联 characters.name（可空）、
 *       sceneId 关联 scenes.id（可空）——两者皆空 = 未关联素材（允许）；</li>
 *   <li>metaJson 存 Aseprite JSON 元数据全文（角色动画）或 tileset 尺寸/格子数等元信息（场景瓦片），可空。</li>
 * </ul>
 */
@Entity
@Table(name = "assets")
public class AssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 素材名（唯一，如「主角行走动画」/「地牢瓦片」） */
    @Column(unique = true, nullable = false, length = 200)
    private String name;

    /** 素材类型枚举字符串：CHARACTER_ANIMATION（角色像素动画）/ SCENE_TILESET（场景瓦片图集） */
    @Column(nullable = false, length = 32)
    private String assetType;

    /** 关联角色名（可空；引用 characters.name，不设外键约束） */
    @Column(length = 200)
    private String characterName;

    /** 关联场景 id（可空；引用 scenes.id，不设外键约束） */
    @Column(length = 200)
    private String sceneId;

    /** 素材文件相对路径（相对 static/assets/，如 CHARACTER_ANIMATION/demo_player/player.png） */
    @Column(length = 2000)
    private String filePath;

    /** 元数据（Aseprite JSON 全文 / tileset 尺寸·格子数等），可空 */
    @Lob
    @Column(nullable = true)
    private String metaJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public AssetEntity() {}

    public AssetEntity(String name, String assetType, String characterName,
                       String sceneId, String filePath, String metaJson) {
        this.name = name;
        this.assetType = assetType;
        this.characterName = characterName;
        this.sceneId = sceneId;
        this.filePath = filePath;
        this.metaJson = metaJson;
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

    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }

    public String getCharacterName() { return characterName; }
    public void setCharacterName(String characterName) { this.characterName = characterName; }

    public String getSceneId() { return sceneId; }
    public void setSceneId(String sceneId) { this.sceneId = sceneId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getMetaJson() { return metaJson; }
    public void setMetaJson(String metaJson) { this.metaJson = metaJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
