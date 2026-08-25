package com.roleplay.engine.service.world;

import java.util.Locale;

/** 点击只算注意；真正对话、剧情绑定和关系行为才积累晋升分。 */
public enum RoleInteractionKind {
    ATTENTION(0),
    DIALOGUE(2),
    PLOT_REFERENCE(3),
    RELATIONSHIP(3);

    private final int score;

    RoleInteractionKind(int score) {
        this.score = score;
    }

    public int score() {
        return score;
    }

    public static RoleInteractionKind parse(String raw) {
        if (raw == null || raw.isBlank()) return ATTENTION;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ATTENTION;
        }
    }
}
