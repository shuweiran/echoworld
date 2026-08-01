package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM-based conversation arbiter / DM — configures tracks, integrates outputs,
 * classifies user input, and handles werewolf GM duties.
 * Maps from Python core/arbiter.py → Arbiter.
 */
@Service
public class ArbiterService {
    private static final Logger log = LoggerFactory.getLogger(ArbiterService.class);

    private static final List<String> TRACK_COLORS = List.of(
        "#4CAF50", "#2196F3", "#FF9800", "#E91E63",
        "#9C27B0", "#00BCD4", "#FF5722", "#795548",
        "#607D8B", "#8BC34A");

    private final LLMClient llmClient;

    public enum UserInputCategory {
        SUPPLEMENT, TOPIC_SWITCH, COMMAND, NEW_PLOT
    }

    public ArbiterService(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Configure tracks for this round via LLM.
     * Returns list of track maps + reasoning string.
     */
    public TrackConfigResult configureTracks(String sceneDescription,
                                              List<String> agentNames,
                                              String historySummary,
                                              String mode,
                                              String protagonist,
                                              List<Map<String, Object>> previousTracks,
                                              List<String> goals,
                                              Set<String> restrictedAgents) {
        restrictedAgents = restrictedAgents != null ? restrictedAgents : Set.of();
        String prevText = "(本轮为新对话，无上一轮)";
        if (previousTracks != null && !previousTracks.isEmpty()) {
            List<String> lines = new ArrayList<>();
            for (Map<String, Object> t : previousTracks) {
                @SuppressWarnings("unchecked")
                Map<String, String> actions = (Map<String, String>) t.getOrDefault("agent_actions", Map.of());
                List<String> active = actions.entrySet().stream()
                    .filter(e -> "active".equals(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
                List<String> silent = actions.entrySet().stream()
                    .filter(e -> "silent".equals(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
                lines.add("  轨道「" + t.getOrDefault("label", "") + "」(" + t.getOrDefault("mode", "") + "): active=" + active + ", silent=" + silent);
            }
            prevText = String.join("\n", lines);
        }

        String prompt;
        if ("werewolf".equals(mode)) {
            prompt = String.format("""
                你是一个狼人杀游戏的主持人（GM）。你负责主持游戏流程、分配角色、宣布结果，并引导每轮讨论。

                当前场景：%s
                存活角色：%s
                当前阶段：day_discuss
                轮次：1

                请根据当前阶段和规则，为存活角色配置本轮轨道和行动：
                - 白天讨论：所有存活者 active（公开讨论）

                回复JSON（必须包含 role_info 标注各角色身份）：
                {"reasoning": "阶段说明+行动理由", "role_info": {"角色名": "身份(werewolf/seer/witch/villager)", "alive": true/false}, "tracks": [轨道列表]}
                """, sceneDescription != null ? sceneDescription : "狼人杀游戏",
                String.join(", ", agentNames));
        } else {
            String goalsText = "";
            if (goals != null && !goals.isEmpty()) {
                goalsText = "\n当前剧情目标（必须严格遵守）：\n" +
                    goals.stream().map(g -> "- " + g).collect(Collectors.joining("\n"));
            }
            String restrictedList = restrictedAgents.isEmpty() ? "（无）" :
                String.join(", ", new TreeSet<>(restrictedAgents));

            prompt = String.format("""
                你是一个角色扮演游戏的主控（DM）。请分析当前对话状态，为本轮配置铁轨。

                当前场景：%s

                可用角色：%s
                对话历史摘要：
                %s

                上一轮轨道配置（避免重复）：
                %s

                %s

                ━━━━ 角色 action ━━━━
                - "active"  → 本轮生成回复，参与对话
                - "silent"  → 本轮不输出，但同步轨道上下文
                - "offline" → 完全隔离

                【轮换要求】≤3人时全部active。4人以上每轮必须轮换active角色。
                【禁止调度角色】%s

                请回复JSON：
                {"reasoning": "配置逻辑", "tracks": [{"id":"", "agents":[""], "mode":"merged/weak/isolated", "agent_actions":{}}, ...]}
                """,
                sceneDescription != null ? sceneDescription : "默认场景",
                String.join(", ", agentNames),
                historySummary != null ? historySummary : "(新对话)",
                prevText,
                goalsText,
                restrictedList);
        }

        // D-023：≤6 角色多轨道结构化 JSON（reasoning+tracks[]+agent_actions），400 偏紧，提升至 600
        Map<String, Object> result = llmClient.callJson(prompt, 600);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawTracks = (List<Map<String, Object>>) result.getOrDefault("tracks", List.of());
        String reasoning = (String) result.getOrDefault("reasoning", "");

        if (rawTracks.isEmpty()) {
            rawTracks = defaultTracks(agentNames);
            reasoning = "LLM未返回配置，使用默认轨道";
        }

        List<Map<String, Object>> tracks = new ArrayList<>();
        Set<String> assigned = new HashSet<>();

        for (int i = 0; i < rawTracks.size(); i++) {
            Map<String, Object> rt = rawTracks.get(i);
            @SuppressWarnings("unchecked")
            List<Object> rawNames = (List<Object>) rt.getOrDefault("agents",
                rt.getOrDefault("agent_names", List.of()));
            if (rawNames == null || rawNames.isEmpty()) continue;

            // Handle agent names that might be strings or {"name": "..."} maps
            List<String> cleanNames = rawNames.stream()
                .map(n -> n instanceof Map ? String.valueOf(((Map<?, ?>) n).get("name")) : String.valueOf(n))
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank() && !"null".equals(s))
                .collect(Collectors.toList());

            String modeStr = (String) rt.getOrDefault("mode", "merged");
            @SuppressWarnings("unchecked")
            Map<String, String> actions = (Map<String, String>) rt.getOrDefault("agent_actions", Map.of());
            if (actions.isEmpty()) {
                actions = new LinkedHashMap<>();
                for (int j = 0; j < cleanNames.size(); j++) {
                    String status = switch (modeStr) {
                        case "weak" -> j == 0 ? "active" : "silent";
                        case "isolated" -> "offline";
                        default -> "active";
                    };
                    actions.put(cleanNames.get(j), status);
                }
            }

            Map<String, Object> track = new LinkedHashMap<>();
            track.put("id", rt.getOrDefault("id", "track_" + i));
            track.put("agents", cleanNames);
            track.put("agent_actions", actions);
            track.put("mode", modeStr);
            track.put("color", rt.getOrDefault("color", TRACK_COLORS.get(i % TRACK_COLORS.size())));
            track.put("label", rt.getOrDefault("label", "轨道" + (i + 1)));
            tracks.add(track);
            assigned.addAll(cleanNames);
        }

        // Ensure all agents assigned
        Set<String> agentSet = new HashSet<>(agentNames);
        List<String> missing = agentNames.stream().filter(n -> !assigned.contains(n)).collect(Collectors.toList());
        if (!missing.isEmpty()) {
            if (!tracks.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> lastAgents = (List<String>) tracks.get(tracks.size() - 1).get("agents");
                @SuppressWarnings("unchecked")
                Map<String, String> lastActions = (Map<String, String>) tracks.get(tracks.size() - 1).get("agent_actions");
                lastAgents.addAll(missing);
                missing.forEach(n -> lastActions.put(n, "silent"));
            } else {
                Map<String, String> defaultActions = new LinkedHashMap<>();
                agentNames.forEach(n -> defaultActions.put(n, "silent"));
                Map<String, Object> defaultTrack = new LinkedHashMap<>();
                defaultTrack.put("id", "main");
                defaultTrack.put("agents", new ArrayList<>(agentNames));
                defaultTrack.put("agent_actions", defaultActions);
                defaultTrack.put("mode", "merged");
                defaultTrack.put("color", TRACK_COLORS.get(0));
                defaultTrack.put("label", "主线");
                tracks.add(defaultTrack);
            }
        }

        // Protagonist mode enforcement
        if ("protagonist".equals(mode) && protagonist != null && !protagonist.isEmpty()) {
            boolean found = false;
            for (Map<String, Object> t : tracks) {
                @SuppressWarnings("unchecked")
                Map<String, String> actions = (Map<String, String>) t.get("agent_actions");
                @SuppressWarnings("unchecked")
                List<String> agents = (List<String>) t.get("agents");
                if (agents.contains(protagonist)) {
                    actions.put(protagonist, "active");
                    if ("isolated".equals(t.get("mode"))) t.put("mode", "merged");
                    found = true;
                }
            }
            if (!found && !tracks.isEmpty()) {
                Map<String, Object> best = tracks.stream()
                    .max(Comparator.comparingInt(t -> ((List<?>) t.get("agents")).size()))
                    .orElse(null);
                if (best != null) {
                    @SuppressWarnings("unchecked")
                    List<String> agents = (List<String>) best.get("agents");
                    @SuppressWarnings("unchecked")
                    Map<String, String> actions = (Map<String, String>) best.get("agent_actions");
                    agents.add(protagonist);
                    actions.put(protagonist, "active");
                }
            }
        }

        // Clean up tracks: filter invalid agents, ensure ≥1 active per track
        Set<String> finalAgentNames = agentSet;
        for (Map<String, Object> t : tracks) {
            @SuppressWarnings("unchecked")
            List<String> agents = (List<String>) t.get("agents");
            agents.removeIf(n -> !finalAgentNames.contains(n));
            @SuppressWarnings("unchecked")
            Map<String, String> actions = (Map<String, String>) t.get("agent_actions");
            actions.keySet().removeIf(n -> !finalAgentNames.contains(n));
            if (agents.size() <= 3 && List.of("merged", "weak").contains(t.get("mode"))) {
                agents.forEach(n -> actions.put(n, "active"));
            }
            boolean hasActive = actions.values().stream().anyMatch("active"::equals);
            if (!hasActive && !agents.isEmpty()) {
                actions.put(agents.get(0), "active");
            }
        }
        tracks.removeIf(t -> ((List<?>) t.get("agents")).isEmpty());

        // Hard enforcement: restricted agents must be offline
        if (!restrictedAgents.isEmpty()) {
            for (Map<String, Object> t : tracks) {
                @SuppressWarnings("unchecked")
                Map<String, String> actions = (Map<String, String>) t.get("agent_actions");
                for (String name : restrictedAgents) {
                    if (actions.containsKey(name)) {
                        actions.put(name, "offline");
                        reasoning += " [硬性禁止：" + name + "强制offline]";
                    }
                }
            }
        }

        return new TrackConfigResult(tracks, reasoning);
    }

    /** Integrate all agent outputs into narration via LLM. */
    public Map<String, Object> integrateOutputs(String sceneDescription,
                                                  List<Map<String, Object>> tracks,
                                                  List<Map<String, Object>> agentOutputs,
                                                  boolean isWerewolf) {
        if (agentOutputs == null || agentOutputs.isEmpty()) {
            return Map.of("narration", "(本轮无角色输出)", "scene_progress", "");
        }

        String tracksStr;
        try {
            tracksStr = new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(tracks);
        } catch (Exception e) {
            tracksStr = tracks.toString();
        }

        String outputsStr = agentOutputs.stream()
            .map(o -> "[" + o.getOrDefault("agent_name", "?") + "]: "
                + String.valueOf(o.getOrDefault("content", "")).substring(0,
                    Math.min(300, String.valueOf(o.getOrDefault("content", "")).length())))
            .collect(Collectors.joining("\n"));

        String prompt;
        if (isWerewolf) {
            prompt = String.format("""
                你是狼人杀游戏主持人（GM）。请分析本轮对话并输出结构化结果。

                当前场景：%s
                铁轨配置：%s
                各角色的本轮输出：%s

                回复JSON：
                {"narration": "GM旁白", "scene_progress": "阶段推进", "phase": "night/day_vote/day_discuss", "killed": "", "saved": "", "exiled": "", "game_over": false, "winner": "", "next_round": {"phase": "", "agents": [], "order": [], "reason": ""}}
                """, sceneDescription, tracksStr, outputsStr);
        } else {
            prompt = String.format("""
                你是主控（DM）。请分析本轮对话并输出结构化结果。

                当前场景：%s
                铁轨配置：%s
                各角色的本轮输出：%s

                要求：
                1. 整合叙事：用一段连贯文字整合本轮所有角色发言（80-100字）
                2. 下一轮判断：推测下一轮出场角色、轨道和顺序

                回复JSON：
                {"narration": "整合叙事（80-100字）", "scene_progress": "剧情推进（20-40字）", "next_round": {"agents": [], "mode": "merged", "order": [], "reason": ""}, "chain_analysis": {"tracks": [{"label": "", "mode": "", "reason": ""}]}}
                """, sceneDescription, tracksStr, outputsStr);
        }

        // D-023：主持整合大 JSON（narration 80-100 字 + scene_progress + next_round + chain_analysis，狼人杀分支另含 7 字段），800 偏紧，提升至 1000
        Map<String, Object> result = llmClient.callJson(prompt, 1000);
        if (result == null || result.isEmpty()) {
            result = llmClient.callJson(prompt, 1000);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("narration", truncate((String) result.getOrDefault("narration", ""), 150));
        output.put("scene_progress", truncate((String) result.getOrDefault("scene_progress", ""), 60));
        output.put("next_round", result.getOrDefault("next_round", Map.of()));
        output.put("chain_analysis", result.getOrDefault("chain_analysis", Map.of()));
        if (isWerewolf) {
            output.put("phase", result.getOrDefault("phase", ""));
            output.put("killed", result.getOrDefault("killed", ""));
            output.put("saved", result.getOrDefault("saved", ""));
            output.put("exiled", result.getOrDefault("exiled", ""));
            output.put("game_over", result.getOrDefault("game_over", false));
            output.put("winner", result.getOrDefault("winner", ""));
        }
        return output;
    }

    /** Classify user input category. */
    public UserInputCategory classifyUserInput(String text, String mode, List<Map<String, String>> contextHistory) {
        String stripped = text.strip().toLowerCase();
        if (stripped.startsWith("/")) return UserInputCategory.COMMAND;

        if ("always".equals(mode)) {
            String ctx = contextHistory != null
                ? contextHistory.stream()
                    .skip(Math.max(0, contextHistory.size() - 4))
                    .map(m -> "[" + m.getOrDefault("name", "?") + "]: " + m.getOrDefault("content", "").substring(0, Math.min(80, m.getOrDefault("content", "").length())))
                    .collect(Collectors.joining("\n"))
                : "(无历史)";

            String prompt = String.format("""
                请判断以下用户输入属于哪一类：
                (1) 补充 - 用户在现有剧情上补充细节或提问
                (2) 切换话题 - 用户有意改变当前话题
                (3) 命令 - 系统命令
                (4) 新剧情 - 用户引入新的剧情线
                用户输入：%s
                历史上下文：
                %s
                请只回复一个词：补充/切换话题/命令/新剧情
                """, text.length() > 200 ? text.substring(0, 200) : text, ctx);

            Map<String, Object> result = llmClient.callJson(prompt, 20);
            String raw = result.toString();
            if (raw.contains("切换话题")) return UserInputCategory.TOPIC_SWITCH;
            if (raw.contains("新剧情")) return UserInputCategory.NEW_PLOT;
            if (raw.contains("命令")) return UserInputCategory.COMMAND;
            return UserInputCategory.SUPPLEMENT;
        }

        return UserInputCategory.SUPPLEMENT;
    }

    /** Convert user input into DM narration. */
    public String processUserInput(String text, UserInputCategory category,
                                    String sceneDescription, List<String> agentNames,
                                    List<String> goals) {
        if (category == UserInputCategory.COMMAND) return text;

        String catLabel = switch (category) {
            case SUPPLEMENT -> "补充";
            case TOPIC_SWITCH -> "切换话题";
            case NEW_PLOT -> "新剧情";
            default -> "其他";
        };

        String goalsText = "";
        if (goals != null && !goals.isEmpty()) {
            goalsText = "\n当前剧情目标：\n" + goals.stream().map(g -> "- " + g).collect(Collectors.joining("\n"));
        }
        String agentText = agentNames != null ? "可用角色：" + String.join(", ", agentNames) : "";

        String prompt = String.format("""
            你是一个角色扮演游戏的主控（DM）。用户给你发了一段输入，请将其转化为一段"主控旁白"。

            当前场景：%s
            %s%s

            用户输入：%s
            用户输入分类：%s

            请用主控的口吻，将用户输入转化为一段叙事旁白（30-60字）。
            不要出现"用户"、"导演"等字眼。
            可以引导特定角色做出反应，但不要直接替角色说话。

            直接回复旁白内容即可。
            """, sceneDescription != null ? sceneDescription : "",
            agentText, goalsText,
            text.length() > 300 ? text.substring(0, 300) : text, catLabel);

        try {
            String narration = llmClient.callSimple(prompt, 120);
            if (narration == null || narration.isBlank()) {
                narration = "【场景变化】" + (text.length() > 100 ? text.substring(0, 100) : text);
            }
            return narration;
        } catch (Exception e) {
            return "【场景变化】" + (text.length() > 100 ? text.substring(0, 100) : text);
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : (s != null ? s : "");
    }

    /** Default track config when LLM fails. */
    private List<Map<String, Object>> defaultTracks(List<String> agentNames) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, String> actions = new LinkedHashMap<>();
        agentNames.forEach(n -> actions.put(n, "active"));
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("id", "main");
        track.put("agents", new ArrayList<>(agentNames));
        track.put("agent_actions", actions);
        track.put("mode", "merged");
        track.put("label", "主线");
        track.put("color", TRACK_COLORS.get(0));
        result.add(track);
        return result;
    }

    /** Result container for configureTracks. */
    public static class TrackConfigResult {
        public final List<Map<String, Object>> tracks;
        public final String reasoning;

        public TrackConfigResult(List<Map<String, Object>> tracks, String reasoning) {
            this.tracks = tracks;
            this.reasoning = reasoning;
        }
    }
}
