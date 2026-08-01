package com.roleplay.engine.broadcast;

import com.roleplay.engine.interrupt.GameEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公告 REST 端点（玩家侧广播入口，调研报告落地计划 Step 1/4）。
 *
 * <ul>
 *   <li>{@code POST /api/announcements} —— 玩家发起广播（默认 PLAYER 级 / global 通道；
 *       支持显式指定 level/channel/mode/speaker），演示「玩家发广播→全员横幅」链路；</li>
 *   <li>{@code GET /api/announcements/recent?since=ts} —— 断线补发：重连后拉取最近公告。</li>
 * </ul>
 *
 * <p>AI 侧自动演讲/广播触发在 {@code POST /api/simulation/speech}
 * （SimulationController，需要 2D 世界坐标做听众判定）。
 */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementController.class);

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    /**
     * 玩家/系统发布公告（默认 PLAYER 级全局公告）。
     * <pre>{@code POST /api/announcements  {"text":"xxx","level":"PLAYER","channel":"global","mode":"announcement","speaker":"玩家"}}
     * level: SYSTEM|EVENT|PLAYER|NPC；channel: global|area|system；mode: speech|announcement</pre>
     */
    @PostMapping
    public Map<String, Object> post(@RequestBody(required = false) Map<String, String> body) {
        if (body == null || body.getOrDefault("text", "").isBlank()) {
            return Map.of("status", "error", "message", "text 必填");
        }
        String text = body.get("text").trim();
        String speaker = body.getOrDefault("speaker", "玩家").trim();
        String levelStr = body.getOrDefault("level", "PLAYER").trim().toUpperCase();
        String channel = body.getOrDefault("channel", "global").trim().toLowerCase();
        String mode = body.getOrDefault("mode", BroadcastMessage.MODE_ANNOUNCEMENT).trim().toLowerCase();

        BroadcastMessage.Level level;
        try {
            level = BroadcastMessage.Level.valueOf(levelStr);
        } catch (IllegalArgumentException e) {
            return Map.of("status", "error", "message", "非法 level: " + levelStr
                    + "（可选 SYSTEM|EVENT|PLAYER|NPC）");
        }
        if (!List.of("global", "area", "system").contains(channel)) {
            return Map.of("status", "error", "message", "非法 channel: " + channel + "（可选 global|area|system）");
        }

        BroadcastMessage msg = BroadcastMessage.of(level, channel, speaker, text,
                -1, -1, 0, mode);
        BroadcastMessage enqueued = announcementService.enqueue(msg);
        log.info("Announcement posted by {}: {} (level={}, channel={})", speaker, text, level, channel);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("id", enqueued != null ? enqueued.id() : null);
        out.put("coalesce_key", msg.coalesceKey());
        out.put("level", level.name());
        out.put("channel", channel);
        out.put("mode", mode);
        return out;
    }

    /** 断线补发：since（epoch millis，默认 0 = 全量最近缓冲）之后推送过的公告。 */
    @GetMapping("/recent")
    public Map<String, Object> recent(@RequestParam(name = "since", defaultValue = "0") long since) {
        List<Map<String, Object>> list = announcementService.recentSince(since).stream()
                .map(this::toMap)
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("announcements", list);
        out.put("count", list.size());
        return out;
    }

    /**
     * 演讲广播模式查看。
     * merged=正式版（HearingSystem 声学判定 + 可配置兜底，默认）；
     * auto=方案A 旧行为（wouldOthersListen 硬编码判定）；split=方案B 旧行为（SpeechStrategy 内联）。
     */
    @GetMapping("/mode")
    public Map<String, Object> mode() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", announcementService.getSpeechMode());
        out.put("valid", List.of("merged", "auto", "split"));
        out.put("note", "merged=正式版（HearingSystem 声学判定+可配置兜底，默认）；auto=方案A 旧行为；split=方案B 内联路径；merged/auto 走回调、split 走内联，互斥不重复推送");
        return out;
    }

    /**
     * 演讲广播模式运行时切换（同一运行实例演示三路径）。
     * <pre>{@code POST /api/announcements/mode  {"mode":"merged"}}（合法值 merged|auto|split）</pre>
     */
    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestBody(required = false) Map<String, String> body) {
        if (body == null || body.get("mode") == null || body.get("mode").isBlank()) {
            return Map.of("status", "error", "message", "mode 必填（merged|auto|split）");
        }
        String before = announcementService.getSpeechMode();
        announcementService.setSpeechMode(body.get("mode"));
        String after = announcementService.getSpeechMode();
        if (before.equals(after) && !before.equals(body.get("mode").trim().toLowerCase())) {
            return Map.of("status", "error", "message", "非法 mode: " + body.get("mode") + "（可选 merged|auto|split）");
        }
        log.info("Speech broadcast mode switched: {} → {}", before, after);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("mode", after);
        return out;
    }

    private Map<String, Object> toMap(BroadcastMessage m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", m.id());
        p.put("level", m.level().name());
        p.put("channel", m.channel());
        p.put("speaker", m.speaker());
        p.put("text", m.text());
        p.put("x", m.x());
        p.put("y", m.y());
        p.put("radius", m.radius());
        p.put("mode", m.mode());
        p.put("timestamp", m.timestamp());
        return p;
    }
}
