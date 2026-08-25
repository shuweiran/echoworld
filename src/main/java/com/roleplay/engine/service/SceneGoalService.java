package com.roleplay.engine.service;

import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P-0810-09：一般模式「场景与场景目标」机制 —— 目标集生成 + 每轮进展判定。
 *
 * <p><b>目标集结构</b>（存 session 的 RouterService.sceneGoals，可选落库到 SceneEntity.goals JSON 列）：
 * <pre>{@code
 * {
 *   "global_goal":  {"desc": "...", "status": "NOT_STARTED|IN_PROGRESS|COMPLETED|FAILED"},
 *   "role_goals":   {"<roleName>": {"desc": "...", "status": "..."}, ...},   // 每 AI 角色 1 条隐藏目标
 *   "player_goal":  {"desc": "...", "status": "..."}                          // 可展示给玩家的目标（可选）
 * }
 * }</pre>
 *
 * <p><b>生成</b>（init 时）：场景无目标集 → LLM 生成（prompt 全英文，风格同 GeneratorService/ScriptService）；
 * LLM 失败/缺字段 → 规则兜底，恒产出结构完整的目标集（零崩溃）。
 * <b>判定</b>（每轮 send 后由 RouterService 异步触发）：轻量 LLM 按最近对话判定各目标状态，
 * 失败静默降级（不阻塞主流程、不广播）。
 *
 * <p>状态值（机器可读，前端映射中文）：NOT_STARTED=未开始 / IN_PROGRESS=进行中 /
 * COMPLETED=完成 / FAILED=失败。
 */
@Service
public class SceneGoalService {

    private static final Logger log = LoggerFactory.getLogger(SceneGoalService.class);

    /** 目标状态常量。 */
    public static final String NOT_STARTED = "NOT_STARTED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";

    /** 目标集顶层键。 */
    public static final String KEY_GLOBAL = "global_goal";
    public static final String KEY_ROLE_GOALS = "role_goals";
    public static final String KEY_PLAYER = "player_goal";

    /** 隐藏目标对玩家展示的占位符（「？？」）。 */
    public static final String MASK = "??";

    private final LLMClient llmClient;
    /** null 守卫：测试/无 DB 场景跳过落库，生成链路不受影响。 */
    private final DatabaseService databaseService;

    @Autowired
    public SceneGoalService(LLMClient llmClient, DatabaseService databaseService) {
        this.llmClient = llmClient;
        this.databaseService = databaseService;
    }

    // ═══════════════════════════════════════════════════════════
    //  生成入口（init 时）
    // ═══════════════════════════════════════════════════════════

    /**
     * 生成并装载目标集：① DB 中该场景已存 goals → 装载并归一化；② 否则 LLM 生成 →
     * 若场景在 DB 中则回写持久化（下次 init 零 LLM）；③ LLM 失败 → 规则兜底。
     * 恒返回非 null 的完整目标集。
     */
    public Map<String, Object> generateAndLoad(String sceneId, String sceneDesc,
                                                List<String> roleNames, String customPlayerGoal) {
        Optional<Map<String, Object>> stored = loadStoredGoals(sceneId, roleNames);
        return stored.orElseGet(() -> generateAndPersist(sceneId, sceneDesc, roleNames, customPlayerGoal));
    }

    /** 只读取已缓存的目标；不触发 LLM，适合起局的快速路径。 */
    public Optional<Map<String, Object>> loadStoredGoals(String sceneId, List<String> roleNames) {
        if (sceneId == null || sceneId.isBlank() || databaseService == null) return Optional.empty();
        Optional<Map<String, Object>> sceneOpt = databaseService.getScene(sceneId);
        if (sceneOpt.isEmpty()) return Optional.empty();
        Object stored = sceneOpt.get().get("goals");
        if (!(stored instanceof Map<?, ?> m) || m.isEmpty()) return Optional.empty();
        return Optional.of(normalizeGoals(coerceMap(m), roleNames));
    }

    /** 生成 LLM 目标并在场景存在时回写缓存；供后台任务调用。 */
    public Map<String, Object> generateAndPersist(String sceneId, String sceneDesc,
                                                   List<String> roleNames, String customPlayerGoal) {
        Map<String, Object> goals = generateGoals(sceneDesc, roleNames, customPlayerGoal);
        if (sceneId != null && !sceneId.isBlank() && databaseService != null) {
            databaseService.getScene(sceneId).ifPresent(scene -> persistToScene(scene, goals));
        }
        return goals;
    }

    /** 规则即时目标：不访问 LLM，保证起局请求可立即返回。 */
    public Map<String, Object> fallbackGoals(List<String> roleNames, String customPlayerGoal) {
        Map<String, Object> goals = normalizeGoals(Map.of(), roleNames != null ? roleNames : List.of());
        if (customPlayerGoal != null && !customPlayerGoal.isBlank()) {
            goals.put(KEY_PLAYER, goalEntry(customPlayerGoal.trim(), NOT_STARTED));
        }
        return goals;
    }

    /**
     * LLM 生成目标集（纯函数，测试直接调用）。
     * <ul>
     *   <li>global_goal：场景隐藏事件/氛围主线 1 条（隐藏）</li>
     *   <li>role_goals：每个 AI 角色 1 条隐藏目标（不暴露给玩家，行为引导）</li>
     *   <li>player_goal：init body 自定义（非空时优先）→ 否则 LLM 生成 1 条可展示目标</li>
     * </ul>
     * LLM 失败/缺角色/空输出 → 规则兜底 + 归一化，恒返回结构完整的目标集。
     */
    public Map<String, Object> generateGoals(String sceneDesc, List<String> roleNames,
                                             String customPlayerGoal) {
        List<String> roles = roleNames != null ? roleNames : List.of();
        boolean customPlayer = customPlayerGoal != null && !customPlayerGoal.isBlank();

        String prompt = buildGenerationPrompt(sceneDesc, roles, customPlayer ? customPlayerGoal.trim() : null);
        Map<String, Object> raw = Map.of();
        try {
            raw = llmClient.callJson(prompt, 1500);
        } catch (Exception e) {
            log.warn("SceneGoalService: goal generation LLM failed: {}", e.getMessage());
        }
        Map<String, Object> goals = normalizeGoals(raw, roles);
        // 自定义玩家目标优先（LLM 输出恒被覆盖）
        if (customPlayer) {
            Map<String, Object> pg = new LinkedHashMap<>();
            pg.put("desc", customPlayerGoal.trim());
            pg.put("status", NOT_STARTED);
            goals.put(KEY_PLAYER, pg);
        }
        return goals;
    }

    // ═══════════════════════════════════════════════════════════
    //  进展判定（每轮 send 后，异步）
    // ═══════════════════════════════════════════════════════════

    /**
     * 判定结果（纯数据）：各目标新状态 + 变化目标（仅状态与当前不同者）。LLM 失败返回 null
     * （调用方静默跳过，不广播）。revealed 由调用方依据「变化到 COMPLETED/FAILED」自行组装
     * （desc 以目标集内存文本为准，不经 LLM 回传，防编造）。
     */
    public record JudgeResult(
            Map<String, String> roleStatuses,
            String globalStatus,
            String playerStatus
    ) {}

    /**
     * 轻量 LLM 判定本轮对话后各目标状态；失败/解析不出 → 返回 null（静默降级）。
     */
    public JudgeResult judgeGoals(Map<String, Object> goals, String transcript) {
        String prompt = buildJudgmentPrompt(goals, transcript);
        Map<String, Object> raw;
        try {
            raw = llmClient.callJson(prompt, 400);
        } catch (Exception e) {
            log.warn("SceneGoalService: judgment LLM failed (silent): {}", e.getMessage());
            return null;
        }
        if (raw == null || raw.isEmpty()) return null;

        Map<String, String> roleStatuses = new LinkedHashMap<>();
        Object rg = raw.get(KEY_ROLE_GOALS);
        if (rg instanceof Map<?, ?> roleMap) {
            for (Map.Entry<?, ?> e : roleMap.entrySet()) {
                if (e.getValue() == null) continue;
                String s = normalizeStatus(String.valueOf(e.getValue()));
                if (s != null) roleStatuses.put(String.valueOf(e.getKey()), s);
            }
        }
        String global = normalizeStatus(raw.get(KEY_GLOBAL) != null ? String.valueOf(raw.get(KEY_GLOBAL)) : null);
        String player = normalizeStatus(raw.get(KEY_PLAYER) != null ? String.valueOf(raw.get(KEY_PLAYER)) : null);
        if (roleStatuses.isEmpty() && global == null && player == null) return null;
        return new JudgeResult(roleStatuses, global, player);
    }

    // ═══════════════════════════════════════════════════════════
    //  归一化（宽容解析：旧数据 / LLM 缺字段 / 兜底）
    // ═══════════════════════════════════════════════════════════

    /**
     * 归一化为完整结构：global_goal + 每个 roleName 的 role_goals 条目 + player_goal。
     * 缺失条目用规则兜底描述填充，状态默认 NOT_STARTED。恒返回可变 Map（状态更新可写）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> normalizeGoals(Map<String, Object> raw, List<String> roleNames) {
        Map<String, Object> goals = new LinkedHashMap<>();
        Map<String, Object> src = raw != null ? raw : Map.of();

        // global_goal
        Object gg = src.get(KEY_GLOBAL);
        if (gg instanceof Map<?, ?> m && m.get("desc") != null && !String.valueOf(m.get("desc")).isBlank()) {
            goals.put(KEY_GLOBAL, goalEntry(String.valueOf(m.get("desc")), statusOf(m, NOT_STARTED)));
        } else {
            goals.put(KEY_GLOBAL, goalEntry("在场景中推进事件发展，让隐藏的氛围主线自然浮现", NOT_STARTED));
        }

        // role_goals —— 每个角色至少 1 条（LLM 缺角色 → 兜底填充）
        Map<String, Object> roleGoals = new LinkedHashMap<>();
        Map<String, Object> srcRoles = new LinkedHashMap<>();
        if (src.get(KEY_ROLE_GOALS) instanceof Map<?, ?> rm) {
            srcRoles.putAll((Map<String, Object>) rm);
        }
        for (String role : roleNames) {
            Object entry = srcRoles.get(role);
            if (entry instanceof Map<?, ?> m && m.get("desc") != null && !String.valueOf(m.get("desc")).isBlank()) {
                roleGoals.put(role, goalEntry(String.valueOf(m.get("desc")), statusOf(m, NOT_STARTED)));
            } else {
                roleGoals.put(role, goalEntry("扮演好「" + role + "」，顺着剧情自然行动，在场景中推动自己的故事线", NOT_STARTED));
            }
        }
        // LLM 多给的角色条目也保留（角色名单之外的隐藏目标不丢）
        for (Map.Entry<String, Object> e : srcRoles.entrySet()) {
            if (!roleGoals.containsKey(e.getKey())) {
                if (e.getValue() instanceof Map<?, ?> m && m.get("desc") != null) {
                    roleGoals.put(e.getKey(), goalEntry(String.valueOf(m.get("desc")), statusOf(m, NOT_STARTED)));
                }
            }
        }
        goals.put(KEY_ROLE_GOALS, roleGoals);

        // player_goal
        Object pg = src.get(KEY_PLAYER);
        if (pg instanceof Map<?, ?> m && m.get("desc") != null && !String.valueOf(m.get("desc")).isBlank()) {
            goals.put(KEY_PLAYER, goalEntry(String.valueOf(m.get("desc")), statusOf(m, NOT_STARTED)));
        } else {
            goals.put(KEY_PLAYER, goalEntry("与场景中的角色互动，推动故事发展，达成自己的目标", NOT_STARTED));
        }
        return goals;
    }

    /** 规整状态字符串；非法值返回 null（调用方忽略该键）。 */
    private static String normalizeStatus(String s) {
        if (s == null) return null;
        String up = s.trim().toUpperCase();
        return switch (up) {
            case NOT_STARTED, IN_PROGRESS, COMPLETED, FAILED -> up;
            default -> null;
        };
    }

    private static String statusOf(Map<?, ?> m, String def) {
        Object s = m.get("status");
        String n = normalizeStatus(s != null ? String.valueOf(s) : null);
        return n != null ? n : def;
    }

    private static Map<String, Object> goalEntry(String desc, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("desc", desc);
        m.put("status", status);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> coerceMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    // ═══════════════════════════════════════════════════════════
    //  Prompt（全英文，LLM 调用风格同 GeneratorService/ScriptService）
    // ═══════════════════════════════════════════════════════════

    private String buildGenerationPrompt(String sceneDesc, List<String> roles, String customPlayerGoalText) {
        StringBuilder roleList = new StringBuilder();
        for (String r : roles) {
            roleList.append("  - ").append(r).append("\n");
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the game master (DM) of a Chinese roleplay session. ")
              .append("Create a structured set of scene goals for the upcoming story.\n\n");
        prompt.append("Scene description:\n").append(sceneDesc == null || sceneDesc.isBlank() ? "(none)" : sceneDesc).append("\n\n");
        prompt.append("AI roles:\n").append(roleList.length() == 0 ? "  (none)\n" : roleList).append("\n");
        if (customPlayerGoalText != null) {
            prompt.append("The player's goal is already decided by the player:\n  \"")
                  .append(truncate(customPlayerGoalText, 200))
                  .append("\"\n(ignore player_goal in your output)\n\n");
        }
        prompt.append("""
            Return ONLY a JSON object with this exact shape:
            {
              "global_goal": {"desc": "one hidden event/atmosphere mainline for the whole scene, 1 sentence, must not be obvious to players", "status": "NOT_STARTED"},
              "role_goals": {"<roleName>": {"desc": "one hidden personal goal for this role, 1 sentence; must never be revealed to the player directly; drive behavior subtly through actions and words", "status": "NOT_STARTED"}, ...},
              "player_goal": {"desc": "one clear goal the player can see and pursue, 1 sentence"}
            }

            Rules:
            - Every role in the list must have exactly one entry in role_goals.
            - Role goals and the global goal must stay hidden from the player.
            - The player goal must be visible, actionable, and interesting.
            - Use Chinese for all desc values.
            - Respond with JSON only, no commentary.
            """);
        return prompt.toString();
    }

    private String buildJudgmentPrompt(Map<String, Object> goals, String transcript) {
        String goalsJson;
        try {
            // 仅把 desc + 当前 status 交给判定（desc 对判定 LLM 可见，但不会回传给前端）
            goalsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(goals);
        } catch (Exception e) {
            goalsJson = String.valueOf(goals);
        }
        return """
            You are the game master (DM) of a roleplay session. Judge whether each hidden goal
            progressed after the latest dialogue round.

            Current goals (desc + current status):
            %s

            Recent dialogue (latest round only):
            %s

            Return ONLY a JSON object with new statuses:
            {"role_goals": {"<roleName>": "NOT_STARTED|IN_PROGRESS|COMPLETED|FAILED", ...},
             "global_goal": "NOT_STARTED|IN_PROGRESS|COMPLETED|FAILED",
             "player_goal": "NOT_STARTED|IN_PROGRESS|COMPLETED|FAILED"}

            Rules:
            - NOT_STARTED: no meaningful progress toward the goal yet.
            - IN_PROGRESS: the character/player took visible actions or words toward it.
            - COMPLETED: the goal is clearly achieved in this dialogue.
            - FAILED: the goal is clearly impossible, broken, or abandoned.
            - When uncertain, keep the current status; never fabricate progress.
            - Respond with JSON only.
            """.formatted(goalsJson, transcript == null ? "(empty)" : transcript);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ═══════════════════════════════════════════════════════════
    //  落库（可选：生成结果回写场景，下次 init 免 LLM）
    // ═══════════════════════════════════════════════════════════

    /** 生成结果回写 SceneEntity.goals（仅当场景存在于 DB；失败仅 log.warn 不影响主流程）。 */
    private void persistToScene(Map<String, Object> scene, Map<String, Object> goals) {
        if (databaseService == null || scene == null) return;
        try {
            String goalsJson;
            try {
                goalsJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(goals);
            } catch (Exception e) {
                log.warn("SceneGoalService: serialize goals failed: {}", e.getMessage());
                return;
            }
            databaseService.saveScene(
                    String.valueOf(scene.getOrDefault("scene_id", "")),
                    String.valueOf(scene.getOrDefault("name", "未命名场景")),
                    String.valueOf(scene.getOrDefault("description", "")),
                    stringList(scene.get("initial_agent_names")),
                    String.valueOf(scene.getOrDefault("keywords", "")),
                    String.valueOf(scene.getOrDefault("category", "general")),
                    toJsonStr(scene.get("default_roles")),
                    toJsonStr(scene.get("default_map")),
                    goalsJson);
        } catch (Exception e) {
            log.warn("SceneGoalService: persist goals to scene failed: {}", e.getMessage());
        }
    }

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> raw) {
            for (Object x : raw) if (x != null) out.add(String.valueOf(x));
        } else if (o instanceof String s && !s.isBlank()) {
            for (String x : s.split(",")) if (!x.isBlank()) out.add(x.trim());
        }
        return out;
    }

    private static String toJsonStr(Object o) {
        if (o == null) return null;
        if (o instanceof String s) return s;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
