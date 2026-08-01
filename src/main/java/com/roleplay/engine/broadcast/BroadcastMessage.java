package com.roleplay.engine.broadcast;

import java.time.Instant;
import java.util.UUID;

/**
 * 广播消息 —— 演讲与广播合并地基的统一领域载体（调研报告 §3.2）。
 *
 * <p>设计：不分开建「演讲系统」和「广播系统」，而是统一一条消息管线。
 * <ul>
 *   <li><b>演讲</b> = 带空间范围（{@code channel="area"} + {@code x}/{@code y}/{@code radius}）+ 听众模型（HearingSystem）的广播；</li>
 *   <li><b>公告</b> = 无范围 / 全局广播（{@code channel="global"}，{@code radius=0}）；</li>
 *   <li><b>系统广播</b> = {@code channel="system"}（阶段切换等，level=SYSTEM）。</li>
 * </ul>
 *
 * <p>{@code mode} 字段标记来源形态（speech 演讲 / announcement 公告），供前端按形态渲染
 * （横幅 vs 字幕条）；{@code coalesceKey} 为防刷屏合并键（默认 speaker|channel，null 不合并）。
 */
public record BroadcastMessage(
        String id,
        Level level,
        String channel,
        String speaker,
        String text,
        double x,
        double y,
        double radius,
        String mode,
        String coalesceKey,
        long timestamp) {

    /** 优先级：SYSTEM(0) &gt; EVENT(1) &gt; PLAYER(2) &gt; NPC(3)，数值小者先出队。 */
    public enum Level {
        SYSTEM(0), EVENT(1), PLAYER(2), NPC(3);

        private final int prio;

        Level(int p) { this.prio = p; }

        public int prio() { return prio; }
    }

    /** 形态标记：演讲（带空间范围/听众）｜公告（全局/无范围）。 */
    public static final String MODE_SPEECH = "speech";
    public static final String MODE_ANNOUNCEMENT = "announcement";

    /** 通用工厂：默认 coalesceKey = speaker|channel（同人同频道合并防刷屏）。 */
    public static BroadcastMessage of(Level level, String channel, String speaker, String text,
                                      double x, double y, double radius, String mode) {
        return new BroadcastMessage(UUID.randomUUID().toString(), level, channel, speaker, text,
                x, y, radius, mode, speaker + "|" + channel, Instant.now().toEpochMilli());
    }

    /** 全局公告快捷工厂（无坐标、无半径、公告形态）。 */
    public static BroadcastMessage of(Level level, String channel, String speaker, String text) {
        return of(level, channel, speaker, text, -1, -1, 0, MODE_ANNOUNCEMENT);
    }
}
