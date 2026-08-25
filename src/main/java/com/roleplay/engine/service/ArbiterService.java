package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.springframework.beans.factory.annotation.Qualifier;
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

    public ArbiterService(@Qualifier("arbiterLlmClient") LLMClient llmClient) {
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
        return configureTracks(sceneDescription, agentNames, historySummary, mode, protagonist,
                previousTracks, goals, restrictedAgents, null);
    }

    /**
     * P-0811-G 主控调度增强：带 nextRoundPrediction（上一轮主控整合时对本轮出场的预测）的重载。
     *
     * <p>「上轮预测→下轮执行」闭环：RouterService 在 integrateOutputs 时保存主控产出的
     * {@code next_round}（含预测出场 agents/order/mode/reason），下一轮 configureTracks 传入
     * 本参数；prompt 强制主控优先遵守该预测分配 agent_actions（预测出场角色 active、其余按需
     * silent），实现「谁出场/谁隔离」由主控跨轮连续决策而非每轮从零自由发挥。</p>
     *
     * <p>P-0811-G 主控开局分配：protagonist 模式（带玩家角色）开局不再强制 ≤3 人全员 active
     * —— 玩家角色恒 active（L202-228），AI 角色是否出场由主控按剧情分配（可 silent），
     * 避免开局全员抢话。其余模式保留 ≤3 人全 active 兜底。</p>
     */
    public TrackConfigResult configureTracks(String sceneDescription,
                                              List<String> agentNames,
                                              String historySummary,
                                              String mode,
                                              String protagonist,
                                              List<Map<String, Object>> previousTracks,
                                              List<String> goals,
                                              Set<String> restrictedAgents,
                                              Map<String, Object> nextRoundPrediction) {
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

                已知对话历史摘要：
                %s

                上一轮轨道与行动（如有）：
                %s

                请根据当前阶段和规则，为存活角色配置本轮轨道和行动：
                - 白天讨论：所有存活者 active（公开讨论）

                【狼人杀主控推理协议】
                1. 严格区分公开发言、已验证规则结果与角色私密身份；不能把未公开的身份或夜间信息泄露到公开轨道。
                2. 先核对存活名单、阶段和历史中已发生的事件，再决定发言顺序；不要凭空宣布死亡、查验或胜负。
                3. reasoning 只说明调度依据与不确定点，不替玩家下结论；胜负只按狼人数量与好人数量的规则状态判定。

                回复JSON（必须包含 role_info 标注各角色身份）：
                {"reasoning": "阶段说明+行动理由", "role_info": {"角色名": "身份(werewolf/seer/witch/villager)", "alive": true/false}, "tracks": [轨道列表]}
                """, sceneDescription != null ? sceneDescription : "狼人杀游戏",
                String.join(", ", agentNames),
                historySummary != null && !historySummary.isBlank() ? historySummary : "（无历史）",
                prevText);
        } else {
            String goalsText = "";
            if (goals != null && !goals.isEmpty()) {
                goalsText = "\n当前剧情目标（必须严格遵守）：\n" +
                    goals.stream().map(g -> "- " + g).collect(Collectors.joining("\n"));
            }
            String restrictedList = restrictedAgents.isEmpty() ? "（无）" :
                String.join(", ", new TreeSet<>(restrictedAgents));

            // P-0811-G：上一轮主控预测的本轮出场安排（闭环「上轮预测→下轮执行」；无预测则空）
            String nextRoundText = "";
            if (nextRoundPrediction != null && !nextRoundPrediction.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> predAgents = (List<String>) nextRoundPrediction.getOrDefault("agents", List.of());
                @SuppressWarnings("unchecked")
                List<String> predOrder = (List<String>) nextRoundPrediction.getOrDefault("order", List.of());
                String reason = String.valueOf(nextRoundPrediction.getOrDefault("reason", ""));
                StringBuilder sb = new StringBuilder("\n━━━━ 上一轮主控预测（必须优先遵守） ━━━━\n");
                if (!predAgents.isEmpty()) sb.append("预测本轮出场角色: ").append(String.join(", ", predAgents)).append("\n");
                if (!predOrder.isEmpty()) sb.append("预测出场顺序: ").append(String.join(" → ", predOrder)).append("\n");
                if (!reason.isBlank()) sb.append("预测理由: ").append(reason).append("\n");
                sb.append("【执行要求】优先按预测把出场角色设为 active、其余可 silent；若你认为必须调整，"
                        + "请在 reasoning 中说明理由。\n");
                nextRoundText = sb.toString();
            }
            // P-0811-G：protagonist 模式（带玩家角色）开局由主控分配轨道——玩家恒 active，AI 是否出场按剧情。
            String rotationRule = "protagonist".equals(mode)
                    ? "\n【开局分配要求】本局带玩家角色（主角 " + (protagonist == null ? "" : protagonist) + "）。"
                        + "玩家角色必须 active；AI 角色是否本轮出场由你按剧情分配（不必全员说话，"
                        + "可让部分角色 silent 等待，形成对话节奏）。"
                    : "\n【轮换要求】≤3人时全部active。4人以上每轮必须轮换active角色。";

            prompt = String.format("""
                你是一个角色扮演游戏的主控（DM）。请先基于完整上下文进行事实核对，再为本轮配置铁轨。

                当前场景：%s

                可用角色：%s
                对话历史摘要：
                %s

                上一轮轨道配置（避免重复）：
                %s

                %s

                %s

                ━━━━ 角色 action ━━━━
                - "active"  → 本轮生成回复，参与对话
                - "silent"  → 本轮不输出，但同步轨道上下文
                - "offline" → 完全隔离

                %s
                【禁止调度角色】%s

                【主控推理准则】
                1. 只依据场景、历史、目标和上一轮预测作决定；不臆造已发生的事实。
                2. 先判断剧情目标与角色信息边界，再决定谁需要说话；避免无关角色重复抢话。
                3. 若调整上一轮预测、或目标彼此冲突，必须在 reasoning 说明取舍。
                4. 把“已发生事实”“角色主张”“待验证推测”分开处理；下一轮优先安排能推进或验证当前目标的角色。

                请回复JSON：
                {"reasoning": "配置逻辑", "tracks": [{"id":"", "agents":[""], "mode":"merged/weak/isolated", "agent_actions":{}}, ...]}
                """,
                sceneDescription != null ? sceneDescription : "默认场景",
                String.join(", ", agentNames),
                historySummary != null ? historySummary : "(新对话)",
                prevText,
                goalsText,
                nextRoundText,
                rotationRule,
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
            // P-0811-G：protagonist 模式（带玩家角色开局）不强制 ≤3 全 active——
            // 主控按剧情分配（AI 角色可 silent 形成节奏，玩家恒 active 见上方强制）；其余模式保留兜底。
            if (!"protagonist".equals(mode) && agents.size() <= 3 && List.of("merged", "weak").contains(t.get("mode"))) {
                agents.forEach(n -> actions.put(n, "active"));
            }
            boolean hasActive = actions.values().stream().anyMatch("active"::equals);
            if (!hasActive && !agents.isEmpty()) {
                actions.put(agents.get(0), "active");
            }
        }
        tracks.removeIf(t -> ((List<?>) t.get("agents")).isEmpty());

        // P-0815-F：双人 protagonist 死锁防护 —— 会话唯一 AI 角色必须 active（玩家恒有回应）。
        // 背景：protagonist 模式 prompt 允许主控把 AI 角色分配为 silent（P-0811-G 开局节奏设计），
        // 但双人局（1 AI + 1 玩家）若唯一 AI 被 silent：玩家角色已被上方 enforcement 强制 active →
        // 下方 hasActive 兜底见已有 active（玩家）不再补强 → RouterService.runRound 随后
        // agentMap.remove(protagonist)（P-0810-25-2，玩家不参与生成）后 agentMap 无任何 active AI →
        // 串行/并行双路径任务列表为空 → 「Agent round complete (serial): 0 agents in 0ms」对话死锁
        // （2026-08-15 真机实测 19:35 复现：玩家连发 R2/R3/R4 AI 零回应；19:39 同配置 1 agents 正常 =
        // LLM 每轮随机漂移 silent/active，非确定性判定）。规则：protagonist 模式且 AI 角色恰 1 个时
        // 强制其 active（不依赖 LLM 漂移）；多人（≥2 AI）保留主控自由分配（P-0811-G 节奏设计）不受影响。
        // restricted 硬性禁止优先（本兜底先于 restricted 块执行，用户「禁止出场」命令仍可覆盖）。
        if ("protagonist".equals(mode) && protagonist != null && !protagonist.isEmpty()) {
            List<String> aiAgents = agentNames.stream()
                    .filter(n -> !n.equals(protagonist))
                    .collect(Collectors.toList());
            if (aiAgents.size() == 1) {
                String onlyAi = aiAgents.get(0);
                boolean aiActive = false;
                for (Map<String, Object> t : tracks) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> actions = (Map<String, String>) t.get("agent_actions");
                    if ("active".equals(actions.get(onlyAi))) {
                        aiActive = true;
                        break;
                    }
                }
                if (!aiActive) {
                    boolean placed = false;
                    for (Map<String, Object> t : tracks) {
                        @SuppressWarnings("unchecked")
                        List<String> agents = (List<String>) t.get("agents");
                        if (agents.contains(onlyAi)) {
                            @SuppressWarnings("unchecked")
                            Map<String, String> actions = (Map<String, String>) t.get("agent_actions");
                            actions.put(onlyAi, "active");
                            if ("isolated".equals(t.get("mode"))) t.put("mode", "merged");
                            placed = true;
                            break;
                        }
                    }
                    if (!placed && !tracks.isEmpty()) {
                        Map<String, Object> best = tracks.stream()
                                .max(Comparator.comparingInt(t -> ((List<?>) t.get("agents")).size()))
                                .orElse(null);
                        if (best != null) {
                            @SuppressWarnings("unchecked")
                            List<String> agents = (List<String>) best.get("agents");
                            @SuppressWarnings("unchecked")
                            Map<String, String> actions = (Map<String, String>) best.get("agent_actions");
                            agents.add(onlyAi);
                            actions.put(onlyAi, "active");
                        }
                    }
                    reasoning += " [双人防护：唯一AI角色" + onlyAi + "强制active（玩家恒有回应）]";
                }
            }
        }

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

        // P-0811-G 闭环兜底：「上轮预测→下轮执行」——若 LLM 未遵守上轮预测（预测出场角色全未被调 active），
        // 强制预测名单首位角色 active（保证主控跨轮决策不因 LLM 漂移而失效）；restricted/offline 角色除外。
        if (nextRoundPrediction != null && !nextRoundPrediction.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<String> predAgents = (List<String>) nextRoundPrediction.getOrDefault("agents", List.of());
            if (!predAgents.isEmpty()) {
                boolean anyPredActive = false;
                for (Map<String, Object> t : tracks) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> actions = (Map<String, String>) t.get("agent_actions");
                    if (predAgents.stream().anyMatch(n -> "active".equals(actions.get(n)))) {
                        anyPredActive = true;
                        break;
                    }
                }
                if (!anyPredActive) {
                    String first = predAgents.get(0);
                    if (finalAgentNames.contains(first) && !restrictedAgents.contains(first)) {
                        for (Map<String, Object> t : tracks) {
                            @SuppressWarnings("unchecked")
                            List<String> agents = (List<String>) t.get("agents");
                            if (agents.contains(first)) {
                                @SuppressWarnings("unchecked")
                                Map<String, String> actions = (Map<String, String>) t.get("agent_actions");
                                actions.put(first, "active");
                                reasoning += " [预测执行兜底：" + first + "设为active]";
                                break;
                            }
                        }
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

                【狼人杀结果判定协议】
                1. 只根据提供的轨道、发言及明确规则状态归纳本轮，公开旁白不得泄露未公开阵营或夜间私密决策。
                2. 发言中的指控、辩解均是玩家主张，不等同于查验或定案；没有规则结果不得改写为事实。
                3. phase、killed、saved、exiled、game_over 与 winner 必须彼此一致；不确定时保留空值或 false。

                回复JSON：
                {"narration": "GM旁白", "scene_progress": "阶段推进", "phase": "night/day_vote/day_discuss", "killed": "", "saved": "", "exiled": "", "game_over": false, "winner": "", "next_round": {"phase": "", "agents": [], "order": [], "reason": ""}}
                """, sceneDescription, tracksStr, outputsStr);
        } else {
            prompt = String.format("""
                你是主控（DM）。请先核对场景、铁轨与本轮原始发言，再输出结构化结果。

                当前场景：%s
                铁轨配置：%s
                各角色的本轮输出：%s

                要求：
                1. 整合叙事：用一段连贯文字整合本轮所有角色发言（80-100字）
                2. 下一轮判断：推测下一轮出场角色、轨道和顺序
                3. 逻辑约束：不得编造未出现的信息；next_round 必须延续已发生事件与角色目标；有不确定处在 reason 中保守说明
                4. 上下文校验：明确区分角色说出的主张与已被场景/行动证实的事实；不能把猜测写成剧情既定事实

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
