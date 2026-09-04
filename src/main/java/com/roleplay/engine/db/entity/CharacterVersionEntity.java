package com.roleplay.engine.db.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * P1 角色版本化：每次人设实质变更（AI 升级/卡片保存/生成落库）追加一行版本，
 * 主表 {@code characters} 恒存最新版。source 与角色来源语义一致：
 * MANUAL（用户手工）/ AI_GENERATED（AI 生成·升级）/ IMPORTED（外部导入）。
 */
@Entity
@Table(name = "character_versions",
        indexes = {@Index(name = "idx_character_versions_name", columnList = "characterName")})
public class CharacterVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String characterName;

    /** 该角色第 N 版（从 1 起按角色递增）。 */
    private int versionNo;

    @Column(length = 32)
    private String source;

    @Column(length = 2000)
    private String persona;

    private String voice;

    @Column(length = 2000)
    private String background;

    /** 版本时刻的完整五层卡 JSON（可空：纯表层变更时为空）。 */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String cardJson;

    private LocalDateTime createdAt;

    public CharacterVersionEntity() {}

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCharacterName() { return characterName; }
    public void setCharacterName(String characterName) { this.characterName = characterName; }

    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }

    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }

    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }

    public String getCardJson() { return cardJson; }
    public void setCardJson(String cardJson) { this.cardJson = cardJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
