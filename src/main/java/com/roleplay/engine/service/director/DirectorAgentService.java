package com.roleplay.engine.service.director;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.controller.CharacterController;
import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.entity.ChatMessageEntity;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player-facing authoritative Director Agent.
 *
 * <p>The Director has two server-owned state domains: {@link DirectorSession} owns
 * cast/stage truth, while {@link DirectorStoryPlan} owns opening story design. The LLM
 * can only propose operations; the service validates and applies them before reporting
 * success.</p>
 */
@Service
public class DirectorAgentService {
    private static final Logger log = LoggerFactory.getLogger(DirectorAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATE_NAME = "director_state";
    private static final String PLAN_NAME = "director_story_plan";
    private static final String TRACK = "director";

    private static final Pattern NPC_NAME_PATTERN = Pattern.compile(
            "(?:叫|名叫|名字(?:叫|是)?|角色名(?:叫|是)?)[「『\\\"'“‘]?([^，。；;、\\s」』\\\"'”’]{1,24})");
    private static final Pattern NPC_PERSONA_PATTERN = Pattern.compile(
            "(?:性格|人设|设定)(?:是|为|：|:)?([^。；;]{1,180})");
    private static final Pattern PREMISE_PATTERN = Pattern.compile(
            "(?:剧本前提|故事前提|故事设定|剧本设定)(?:是|为|：|:)?([^。；;]{2,500})");
    private static final Pattern TONE_PATTERN = Pattern.compile(
            "(?:基调|整体风格|故事风格|氛围)(?:是|为|：|:)?([^。；;]{2,240})");
    private static final Pattern OPENING_PATTERN = Pattern.compile(
            "(?:开场局势|开场情况|开局情况|开场设定)(?:是|为|：|:)?([^。；;]{2,500})");
    private static final Pattern STAKES_PATTERN = Pattern.compile(
            "(?:核心冲突|主要冲突|主要矛盾|失败代价|风险)(?:是|为|：|:)?([^。；;]{2,400})");

    private static final String SYSTEM_PROMPT = """
            你是 EchoWorld 的玩家侧主控 Agent，也是开局导演台。你不是剧中角色，也不是普通旁白生成器。

            开局前你的核心职责不是只排出场顺序，而是与玩家共同完成可执行的剧本编排：
            - 确认玩家扮演身份与角色表；
            - 确定剧本前提、类型/基调、开场局势、核心冲突与代价；
            - 编排人物关系、每个角色在本场景中的身份/社会位置；
            - 为角色设定本场目标，以及仅该角色知道的秘密；
            - 安排剧情拍点、开场事件、触发条件和不可改写的世界事实；
            - 最后才处理首批在场角色、出场顺序并等待玩家明确确认开局。

            权限边界：
            1. roster 与 onstage 是不同事实；离场角色不得被描述为仍在当前场景参与。
            2. 你编排角色的身份、目标、秘密和剧情条件，但不能替角色直接决定具体台词、即时内心或每一步动作；表演由角色 Agent 完成。
            3. 剧情拍点/触发条件是未来规划，不等于已经发生；不能把“计划发生”说成“已经发生”。
            4. 世界状态只由服务器提供的权威状态决定。你只能提出结构化操作，服务器执行后才可称已完成。
            5. 角色秘密必须只写入对应角色的私有操作，绝不能混入公开场景事实。
            6. 一句“让某角色进场”不等于确认开局；只有玩家明确确认开始/进入游戏才可 CONFIRM。
            7. 创建 NPC 必须有名字；不得在服务器确认前声称创建成功。
            8. 回复简短、清楚，像导演与玩家对设定，不写长篇正文剧情。

            必须只返回 JSON，不要 markdown：
            {"reply":"给玩家的简短说明","operations":[...]}

            operations 支持：
            {"type":"SET_PREMISE","text":"剧本前提"}
            {"type":"SET_TONE","text":"风格/基调"}
            {"type":"SET_OPENING_SITUATION","text":"开场局势"}
            {"type":"SET_STAKES","text":"核心冲突或失败代价"}
            {"type":"SET_SCENE_IDENTITY","character":"角色名","text":"该角色在本场景的身份/社会位置"}
            {"type":"SET_CHARACTER_GOAL","character":"角色名","text":"该角色本场目标"}
            {"type":"SET_CHARACTER_SECRET","character":"角色名","text":"仅该角色知道的秘密"}
            {"type":"SET_RELATION","from":"甲","to":"乙","relation":"关系类型","text":"关系细节"}
            {"type":"ADD_STORY_BEAT","text":"未来剧情拍点"}
            {"type":"ADD_OPENING_EVENT","text":"开场事件或触发条件"}
            {"type":"ADD_WORLD_FACT","text":"不可随意改写的世界事实"}
            {"type":"SET_STAGE","character":"角色名","present":true|false}
            {"type":"CREATE_NPC","character":"角色名","persona":"人设","voice":"说话风格","background":"背景","identity":"场景身份","goal":"本场目标","secret":"私密信息","present":true|false}
            {"type":"SET_ENTRY_ORDER","characters":["甲","乙"]}
            {"type":"ADD_RELATION","text":"兼容旧版的自由文本关系事实"}
            {"type":"ADD_SCENE_NOTE","text":"公开场景事实"}
            {"type":"CONFIRM"}
            没有状态修改则 operations=[]。
            """;

    private final LLMClient llm;
    private final SessionRegistry sessions;
    private final CharacterController characters;
    private final DatabaseService database;
    private final Map<String, DirectorSession> preflights = new ConcurrentHashMap<>();
    private final Map<String, DirectorSession> runtimes = new ConcurrentHashMap<>();
    private final Map<String, DirectorStoryPlan> preflightPlans = new ConcurrentHashMap<>();
    private final Map<String, DirectorStoryPlan> runtimePlans = new ConcurrentHashMap<>();

    public DirectorAgentService(LLMClient llm, SessionRegistry sessions,
                                CharacterController characters, DatabaseService database) {
        this.llm = llm;
        this.sessions = sessions;
        this.characters = characters;
        this.database = database;
    }

    public Map<String, Object> createPreflight(Map<String, Object> body) {
        String id = UUID.randomUUID().toString().substring(0, 12);
        String sceneId = string(body, "scene_id", "scene");
        String sceneDescription = string(body, "scene_description", sceneId);
        Map<String, Object> player = object(body.get("player"));
        List<Map<String, Object>> cast = objects(body.get("characters"));
        List<String> relationships = strings(body.get("relationships"));
        List<String> entryOrder = strings(body.get("entry_order"));
        List<String> onstage = body.containsKey("onstage") ? strings(body.get("onstage")) : null;
        DirectorSession state = new DirectorSession(id, sceneId, sceneDescription, player, cast,
                relationships, entryOrder, onstage);
        DirectorStoryPlan story = DirectorStoryPlan.fromRequest(body);

        String hello = "开局导演台已建立。你扮演「"
                + (state.playerName().isBlank() ? "未指定" : state.playerName())
                + "」。先不用急着进场：可以直接告诉我剧本想怎么玩——故事前提、人物关系、"
                + "每个人在这个场景里的身份、目标/秘密、开场事件和剧情走向；最后再确认谁先在场和正式开局。";
        state.addMessage("assistant", hello);
        preflights.put(id, state);
        preflightPlans.put(id, story);
        persist(state, story, false);
        return response(state, story, hello, List.of(), List.of(), true);
    }

    public Map<String, Object> getPreflight(String id) {
        DirectorSession state = requirePreflight(id);
        DirectorStoryPlan story = requirePreflightPlan(state);
        return response(state, story, "", List.of(), List.of(), true);
    }

    public Map<String, Object> chatPreflight(String id, String text) {
        DirectorSession state = requirePreflight(id);
        DirectorStoryPlan story = requirePreflightPlan(state);
        String input = required(text, "message");
        state.addMessage("user", input);
        Plan plan = plan(state, story, input, true);
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Operation op : plan.operations()) {
            if ("CREATE_NPC".equals(op.type())) {
                applyCreateNpc(null, state, story, op, applied, rejected);
            } else if (isStoryOperation(op.type())) {
                applyStoryOperation(state, story, op, applied, rejected);
            } else {
                applyToState(state, op, input, applied, rejected);
            }
        }
        if (state.confirmed() && !isExplicitEntryConfirmation(input)) {
            rejected.add("未检测到明确的‘确认开始/进入游戏’，本次不开放进场门");
            state = copyWithoutConfirmation(state);
            preflights.put(id, state);
        }
        String reply = verifiedReply(state, story, plan.reply(), applied, rejected, input);
        state.addMessage("assistant", reply);
        persist(state, story, false);
        return response(state, story, reply, applied, rejected, true);
    }

    /** Start only after the player explicitly confirms the full opening design. */
    public Map<String, Object> start(String preflightId) {
        DirectorSession state = requirePreflight(preflightId);
        DirectorStoryPlan story = requirePreflightPlan(state);
        if (!state.confirmed()) throw new IllegalStateException("开局配置尚未明确确认");
        if (!state.runtimeSessionId().isBlank()) {
            RouterService existing = sessions.require(state.runtimeSessionId());
            return startResponse(state, story, existing);
        }

        List<Persona> personas = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> c : state.cast()) {
            String name = string(c, "name", "");
            if (name.isBlank()) continue;
            Persona p = personaFrom(c);
            characters.attachPersonaCard(p);
            personas.add(p);
            names.add(name);
        }
        if (personas.isEmpty()) throw new IllegalStateException("开局角色表为空");
        String player = state.playerName();
        if (!player.isBlank() && personas.stream().noneMatch(p -> player.equals(p.getName()))) {
            throw new IllegalStateException("玩家角色不在开局角色表中: " + player);
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        RouterService router = sessions.getOrCreate(sessionId);
        String mode = player.isBlank() ? "director" : "protagonist";
        router.initSession(sessionId, personas, state.sceneDescription(), mode, player, "");

        for (String name : state.offstageNames()) {
            if (name.equals(player)) continue;
            if (router.hasAgent(name) && !router.suspendWorldAgent(name)) {
                sessions.remove(sessionId);
                throw new IllegalStateException("无法把离场角色移出调度器: " + name);
            }
        }

        // Public story design goes to the shared Director channel; goals/secrets are per-role only.
        router.setDirectorDirective(combinedDirective(state, story));
        router.setDirectorRoleGuidance(story.privateGuidance(names));
        String playerGoal = story.goalFor(player);
        router.ensureSceneGoals(state.sceneId(), state.sceneDescription(), playerGoal.isBlank() ? null : playerGoal);

        state.attachRuntime(sessionId);
        runtimes.put(sessionId, state);
        runtimePlans.put(sessionId, story);
        persist(state, story, false);
        persist(state, story, true);
        router.triggerAutoFirstRound();
        return startResponse(state, story, router);
    }

    public Map<String, Object> runtimeState(String sessionId) {
        DirectorSession state = requireRuntime(sessionId);
        DirectorStoryPlan story = requireRuntimePlan(sessionId, state);
        RouterService router = sessions.require(sessionId);
        return runtimeResponse(state, story, router, "", List.of(), List.of());
    }

    public Map<String, Object> chatRuntime(String sessionId, String text) {
        DirectorSession state = requireRuntime(sessionId);
        DirectorStoryPlan story = requireRuntimePlan(sessionId, state);
        RouterService router = sessions.require(sessionId);
        String input = required(text, "message");
        state.addMessage("user", input);
        Plan plan = plan(state, story, input, false);
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Operation op : plan.operations()) {
            if ("CREATE_NPC".equals(op.type())) {
                applyCreateNpc(router, state, story, op, applied, rejected);
            } else if ("SET_STAGE".equals(op.type())) {
                applyRuntimeStage(router, state, op, applied, rejected);
            } else if (isStoryOperation(op.type())) {
                applyStoryOperation(state, story, op, applied, rejected);
            } else {
                applyToState(state, op, input, applied, rejected);
            }
        }
        router.setDirectorDirective(combinedDirective(state, story));
        router.setDirectorRoleGuidance(story.privateGuidance(state.cast().stream()
                .map(c -> string(c, "name", "")).filter(n -> !n.isBlank()).toList()));
        String reply = verifiedReply(state, story, plan.reply(), applied, rejected, input);
        state.addMessage("assistant", reply);
        persist(state, story, true);
        persist(state, story, false);
        return runtimeResponse(state, story, router, reply, applied, rejected);
    }

    /** Transactional creation: persistence + live Agent + Director roster must all succeed. */
    private void applyCreateNpc(RouterService router, DirectorSession state, DirectorStoryPlan story,
                                Operation op, List<String> applied, List<String> rejected) {
        String name = safe(op.character());
        if (name.isBlank()) {
            rejected.add("创建 NPC 需要角色名");
            return;
        }
        if (state.knowsCharacter(name)) {
            rejected.add("角色已在当前剧本角色表中: " + name);
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("persona", safe(op.persona()));
        body.put("voice", safe(op.voice()));
        body.put("background", safe(op.background()));
        boolean persisted = false;
        boolean liveAdded = false;
        try {
            if (characters == null) throw new IllegalStateException("角色服务不可用");
            ResponseEntity<?> createdResponse = characters.create(body);
            if (!createdResponse.getStatusCode().is2xxSuccessful()) {
                rejected.add("NPC 创建失败: " + name + "（" + safe(String.valueOf(createdResponse.getBody())) + "）");
                return;
            }
            persisted = true;
            Map<String, Object> created = object(createdResponse.getBody());
            if (created.isEmpty()) created.putAll(body);

            if (router != null) {
                Persona persona = personaFrom(created);
                characters.attachPersonaCard(persona);
                router.addWorldAgent(name, persona);
                liveAdded = true;
                if (!op.present() && !router.suspendWorldAgent(name)) {
                    throw new IllegalStateException("新角色创建后无法进入离场保留槽");
                }
            }

            DirectorSession.RegisterResult registered = state.registerCharacter(created, op.present());
            if (!registered.accepted()) throw new IllegalStateException(registered.detail());
            if (!op.identity().isBlank()) story.setSceneIdentity(name, op.identity());
            if (!op.goal().isBlank()) story.setCharacterGoal(name, op.goal());
            if (!op.secret().isBlank()) story.setCharacterSecret(name, op.secret());
            applied.add(registered.detail());
        } catch (RuntimeException e) {
            if (router != null && liveAdded) {
                try { router.removeWorldAgent(name); }
                catch (RuntimeException rollbackError) {
                    log.warn("Rollback live NPC failed for {}: {}", name, rollbackError.getMessage());
                }
            }
            if (persisted && characters != null) {
                try { characters.delete(name); }
                catch (RuntimeException rollbackError) {
                    log.warn("Rollback persisted NPC failed for {}: {}", name, rollbackError.getMessage());
                }
            }
            rejected.add("NPC 创建失败: " + name + "（" + safe(e.getMessage()) + "）");
        }
    }

    private static Persona personaFrom(Map<String, Object> character) {
        String name = string(character, "name", "");
        Persona p = new Persona(name);
        p.setPersonaDesc(string(character, "persona",
                string(character, "personality", string(character, "intro", ""))));
        p.setVoice(string(character, "voice",
                string(character, "talk_style", string(character, "talkStyle", ""))));
        p.setBackground(string(character, "background", ""));
        return p;
    }

    private void applyRuntimeStage(RouterService router, DirectorSession state, Operation op,
                                   List<String> applied, List<String> rejected) {
        String name = op.character();
        if (!state.knowsCharacter(name)) {
            rejected.add("角色不在当前剧本角色表中: " + name);
            return;
        }
        if (!op.present() && name.equals(state.playerName())) {
            rejected.add("不能把玩家当前扮演角色移出场景: " + name);
            return;
        }
        try {
            if (op.present()) {
                if (!router.hasAgent(name)) {
                    if (!router.isWorldSuspendedAgent(name) || !router.resumeWorldAgent(name)) {
                        rejected.add("运行时找不到可恢复的角色: " + name);
                        return;
                    }
                }
            } else {
                if (router.hasAgent(name)) {
                    if (!router.suspendWorldAgent(name)) {
                        rejected.add("角色离场执行失败: " + name);
                        return;
                    }
                } else if (!router.isWorldSuspendedAgent(name)) {
                    rejected.add("运行时找不到角色: " + name);
                    return;
                }
            }
            DirectorSession.StageResult result = state.setStage(name, op.present());
            if (result.accepted()) applied.add(result.detail()); else rejected.add(result.detail());
        } catch (RuntimeException e) {
            rejected.add(name + " 状态修改失败: " + safe(e.getMessage()));
        }
    }

    private void applyToState(DirectorSession state, Operation op, String source,
                              List<String> applied, List<String> rejected) {
        switch (op.type()) {
            case "SET_STAGE" -> {
                DirectorSession.StageResult result = state.setStage(op.character(), op.present());
                if (result.accepted()) applied.add(result.detail()); else rejected.add(result.detail());
            }
            case "SET_ENTRY_ORDER" -> {
                state.setEntryOrder(op.characters());
                applied.add("出场顺序已更新");
            }
            case "ADD_RELATION" -> {
                if (op.text().isBlank()) rejected.add("关系内容为空");
                else { state.addRelationship(op.text()); applied.add("关系事实已记录"); }
            }
            case "ADD_SCENE_NOTE" -> {
                if (op.text().isBlank()) rejected.add("场景补充为空");
                else { state.addSceneNote(op.text()); applied.add("场景补充已记录"); }
            }
            case "CONFIRM" -> {
                if (isExplicitEntryConfirmation(source)) {
                    state.confirm();
                    applied.add("完整开局配置已确认");
                } else rejected.add("需要玩家明确说确认开始/进入游戏");
            }
            default -> rejected.add("未知主控操作: " + op.type());
        }
    }

    private static boolean isStoryOperation(String type) {
        return switch (type) {
            case "SET_PREMISE", "SET_TONE", "SET_OPENING_SITUATION", "SET_STAKES",
                 "SET_SCENE_IDENTITY", "SET_CHARACTER_GOAL", "SET_CHARACTER_SECRET",
                 "SET_RELATION", "ADD_STORY_BEAT", "ADD_OPENING_EVENT", "ADD_WORLD_FACT" -> true;
            default -> false;
        };
    }

    private void applyStoryOperation(DirectorSession state, DirectorStoryPlan story, Operation op,
                                     List<String> applied, List<String> rejected) {
        DirectorStoryPlan.Result result;
        switch (op.type()) {
            case "SET_PREMISE" -> {
                story.setPremise(op.text());
                result = new DirectorStoryPlan.Result(!op.text().isBlank(),
                        op.text().isBlank() ? "剧本前提为空" : "剧本前提已设定");
            }
            case "SET_TONE" -> {
                story.setTone(op.text());
                result = new DirectorStoryPlan.Result(!op.text().isBlank(),
                        op.text().isBlank() ? "故事基调为空" : "故事基调已设定");
            }
            case "SET_OPENING_SITUATION" -> {
                story.setOpeningSituation(op.text());
                result = new DirectorStoryPlan.Result(!op.text().isBlank(),
                        op.text().isBlank() ? "开场局势为空" : "开场局势已设定");
            }
            case "SET_STAKES" -> {
                story.setStakes(op.text());
                result = new DirectorStoryPlan.Result(!op.text().isBlank(),
                        op.text().isBlank() ? "核心冲突/代价为空" : "核心冲突/代价已设定");
            }
            case "SET_SCENE_IDENTITY" -> {
                if (!state.knowsCharacter(op.character())) {
                    result = new DirectorStoryPlan.Result(false, "未知角色: " + op.character());
                } else result = story.setSceneIdentity(op.character(), op.text());
            }
            case "SET_CHARACTER_GOAL" -> {
                if (!state.knowsCharacter(op.character())) {
                    result = new DirectorStoryPlan.Result(false, "未知角色: " + op.character());
                } else result = story.setCharacterGoal(op.character(), op.text());
            }
            case "SET_CHARACTER_SECRET" -> {
                if (!state.knowsCharacter(op.character())) {
                    result = new DirectorStoryPlan.Result(false, "未知角色: " + op.character());
                } else result = story.setCharacterSecret(op.character(), op.text());
            }
            case "SET_RELATION" -> {
                if (op.from().isBlank() || op.to().isBlank()) {
                    if (op.text().isBlank()) result = new DirectorStoryPlan.Result(false, "人物关系缺少 from/to 或文本");
                    else {
                        state.addRelationship(op.text());
                        result = new DirectorStoryPlan.Result(true, "关系事实已记录");
                    }
                } else if (!state.knowsCharacter(op.from()) || !state.knowsCharacter(op.to())) {
                    result = new DirectorStoryPlan.Result(false, "人物关系包含不在角色表中的角色");
                } else {
                    result = story.setRelationship(op.from(), op.to(), op.relation(), op.text());
                    if (result.accepted()) {
                        String label = op.relation().isBlank() ? "关系" : op.relation();
                        state.addRelationship(op.from() + " → " + op.to() + "：" + label
                                + (op.text().isBlank() ? "" : "（" + op.text() + "）"));
                    }
                }
            }
            case "ADD_STORY_BEAT" -> result = story.addStoryBeat(op.text());
            case "ADD_OPENING_EVENT" -> result = story.addOpeningEvent(op.text());
            case "ADD_WORLD_FACT" -> result = story.addWorldFact(op.text());
            default -> result = new DirectorStoryPlan.Result(false, "未知剧本编排操作: " + op.type());
        }
        if (result.accepted()) applied.add(result.detail()); else rejected.add(result.detail());
    }

    private Plan plan(DirectorSession state, DirectorStoryPlan story, String input, boolean preflight) {
        Plan fallback = fallbackPlan(state, input, preflight);
        if (llm == null) return fallback;
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new Message(Message.Role.SYSTEM, "系统", SYSTEM_PROMPT));
            messages.add(new Message(Message.Role.SYSTEM, "系统", combinedDirective(state, story)));
            messages.add(new Message(Message.Role.SYSTEM, "系统", "【导演台完整设计稿（仅主控可见）】\n"
                    + MAPPER.writeValueAsString(story.toMap())));
            List<DirectorSession.DirectorMessage> history = state.messages();
            int from = Math.max(0, history.size() - 20);
            for (int i = from; i < history.size(); i++) {
                DirectorSession.DirectorMessage m = history.get(i);
                Message.Role role = "assistant".equalsIgnoreCase(m.role()) ? Message.Role.AGENT : Message.Role.USER;
                messages.add(new Message(role, role == Message.Role.AGENT ? "主控" : "玩家", m.content()));
            }
            String raw = llm.callSync(messages, null, 1200, 0.2);
            Plan parsed = parsePlan(raw);
            if (parsed != null) return mergePlans(fallback, parsed);
        } catch (Exception e) {
            log.warn("Director Agent LLM planning failed, using deterministic fallback: {}", e.getMessage());
        }
        return fallback;
    }

    private static Plan mergePlans(Plan fallback, Plan parsed) {
        List<Operation> merged = new ArrayList<>(fallback.operations());
        for (Operation candidate : parsed.operations()) {
            boolean duplicate = merged.stream().anyMatch(existing -> existing.sameMutation(candidate));
            if (!duplicate) merged.add(candidate);
        }
        String reply = parsed.reply() == null || parsed.reply().isBlank() ? fallback.reply() : parsed.reply();
        return new Plan(reply, merged);
    }

    private Plan parsePlan(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String json = raw.trim();
        int first = json.indexOf('{'), last = json.lastIndexOf('}');
        if (first >= 0 && last > first) json = json.substring(first, last + 1);
        try {
            JsonNode root = MAPPER.readTree(json);
            String reply = root.path("reply").asText("");
            List<Operation> operations = new ArrayList<>();
            JsonNode ops = root.path("operations");
            if (ops.isArray()) {
                for (JsonNode op : ops) {
                    String type = op.path("type").asText("").trim().toUpperCase(Locale.ROOT);
                    if (type.isBlank()) continue;
                    List<String> order = new ArrayList<>();
                    if (op.path("characters").isArray()) op.path("characters").forEach(n -> order.add(n.asText()));
                    boolean present = "CREATE_NPC".equals(type)
                            ? (!op.has("present") || op.path("present").asBoolean(true))
                            : op.path("present").asBoolean(false);
                    String text = op.path("text").asText(op.path("value").asText(""));
                    operations.add(new Operation(type, op.path("character").asText(""), present,
                            order, text, op.path("persona").asText(""), op.path("voice").asText(""),
                            op.path("background").asText(""), op.path("from").asText(""),
                            op.path("to").asText(""), op.path("relation").asText(""),
                            op.path("identity").asText(""), op.path("goal").asText(""),
                            op.path("secret").asText("")));
                }
            }
            return new Plan(reply, operations);
        } catch (Exception e) {
            return null;
        }
    }

    /** Offline parser preserves critical state mutations even if the LLM is unavailable. */
    Plan fallbackPlan(DirectorSession state, String input, boolean preflight) {
        List<Operation> ops = new ArrayList<>();
        String text = input == null ? "" : input.trim();

        Operation createNpc = deterministicNpcOperation(text);
        if (createNpc != null && !state.knowsCharacter(createNpc.character())) ops.add(createNpc);
        addMatch(ops, PREMISE_PATTERN, text, "SET_PREMISE");
        addMatch(ops, TONE_PATTERN, text, "SET_TONE");
        addMatch(ops, OPENING_PATTERN, text, "SET_OPENING_SITUATION");
        addMatch(ops, STAKES_PATTERN, text, "SET_STAKES");

        for (Map<String, Object> c : state.cast()) {
            String name = string(c, "name", "");
            if (name.isBlank() || !text.contains(name)) continue;
            String clause = clauseForCharacter(text, name);
            if (containsAny(clause, "离场", "出去", "退场", "先走", "先等等", "暂时不在场")) {
                ops.add(Operation.stage(name, false));
            } else if (containsAny(clause, "进场", "进入场景", "进来", "回来", "拉进来", "加入场景", "在场")) {
                ops.add(Operation.stage(name, true));
            }
            String identity = extractAfter(clause, "场景身份", "身份");
            if (!identity.isBlank()) ops.add(Operation.story("SET_SCENE_IDENTITY", name, identity));
            String goal = extractAfter(clause, "场景目标", "目标");
            if (!goal.isBlank()) ops.add(Operation.story("SET_CHARACTER_GOAL", name, goal));
            String secret = extractAfter(clause, "秘密", "私密信息");
            if (!secret.isBlank()) ops.add(Operation.story("SET_CHARACTER_SECRET", name, secret));
        }
        if (preflight && isExplicitEntryConfirmation(text)) ops.add(Operation.confirm());
        String reply = asksState(text) ? state.stateSummary() : "";
        return new Plan(reply, dedupe(ops));
    }

    private static List<Operation> dedupe(List<Operation> operations) {
        List<Operation> out = new ArrayList<>();
        for (Operation op : operations) if (out.stream().noneMatch(x -> x.sameMutation(op))) out.add(op);
        return out;
    }

    private static void addMatch(List<Operation> ops, Pattern pattern, String text, String type) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) ops.add(Operation.story(type, "", safe(matcher.group(1))));
    }

    private static String extractAfter(String clause, String... labels) {
        for (String label : labels) {
            int index = clause.indexOf(label);
            if (index < 0) continue;
            String tail = clause.substring(index + label.length()).replaceFirst("^(?:是|为|：|:|=)+", "").trim();
            if (!tail.isBlank()) return safe(tail);
        }
        return "";
    }

    /** Basic offline coverage for “添加一个叫林夏的新 NPC，性格有点怕生”. */
    private static Operation deterministicNpcOperation(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean createVerb = containsAny(text, "创建", "添加", "新增", "加一个", "加个", "生成", "安排一个");
        boolean roleNoun = lower.contains("npc") || containsAny(text, "新角色", "新人物", "路人", "角色");
        if (!createVerb || !roleNoun) return null;
        Matcher nameMatcher = NPC_NAME_PATTERN.matcher(text);
        if (!nameMatcher.find()) return null;
        String name = safe(nameMatcher.group(1));
        if (name.isBlank()) return null;
        Matcher personaMatcher = NPC_PERSONA_PATTERN.matcher(text);
        String persona = personaMatcher.find() ? safe(personaMatcher.group(1)) : "";
        boolean present = !containsAny(text, "先不要进场", "暂不进场", "先不进场", "先离场", "暂时离场");
        String clause = clauseForCharacter(text, name);
        return Operation.createNpc(name, persona, "", "", present,
                extractAfter(clause, "场景身份", "身份"), extractAfter(clause, "场景目标", "目标"),
                extractAfter(clause, "秘密", "私密信息"));
    }

    public static boolean isExplicitEntryConfirmation(String text) {
        String s = text == null ? "" : text.replaceAll("\\s+", "");
        return containsAny(s, "确认进入场景", "确认开始", "确认开局", "正式开始", "开始吧",
                "可以开始", "可以进场", "进入游戏", "就这样吧", "按这个来");
    }

    private static String clauseForCharacter(String text, String character) {
        int index = text.indexOf(character);
        if (index < 0) return "";
        int start = index;
        while (start > 0 && !isClauseSeparator(text.charAt(start - 1))) start--;
        int end = index + character.length();
        while (end < text.length() && !isClauseSeparator(text.charAt(end))) end++;
        return text.substring(start, end);
    }

    private static boolean isClauseSeparator(char c) {
        return c == '，' || c == ',' || c == '。' || c == '.' || c == '；' || c == ';'
                || c == '！' || c == '!' || c == '？' || c == '?' || c == '\n' || c == '\r';
    }

    private static boolean asksState(String text) {
        return containsAny(text == null ? "" : text,
                "谁在场", "现在在场", "当前在场", "谁离场", "现在什么状态", "当前状态",
                "剧本是什么", "剧本设定", "人物关系", "什么身份", "角色目标", "剧情安排", "开场怎么");
    }

    private String verifiedReply(DirectorSession state, DirectorStoryPlan story, String modelReply,
                                 List<String> applied, List<String> rejected, String input) {
        if (!applied.isEmpty() || !rejected.isEmpty() || asksState(input)) {
            StringBuilder out = new StringBuilder();
            if (!applied.isEmpty()) out.append("已执行：").append(String.join("；", applied)).append("。 ");
            if (!rejected.isEmpty()) out.append("未执行：").append(String.join("；", rejected)).append("。 ");
            out.append(state.stateSummary()).append(" 剧本编排：").append(story.summary()).append("。");
            return out.toString().trim();
        }
        String reply = safe(modelReply);
        return reply.isBlank() ? "我已按当前导演台状态理解你的要求。" + state.stateSummary()
                + " 剧本编排：" + story.summary() + "。" : reply;
    }

    private static String combinedDirective(DirectorSession state, DirectorStoryPlan story) {
        return state.authoritativeDirective() + "\n\n" + story.authoritativeDirective();
    }

    private Map<String, Object> startResponse(DirectorSession state, DirectorStoryPlan story, RouterService router) {
        Map<String, Object> out = new LinkedHashMap<>(router.getState());
        out.put("session_id", state.runtimeSessionId());
        out.put("mode", state.playerName().isBlank() ? "director" : "protagonist");
        out.put("protagonist", state.playerName());
        out.put("goals", router.getSceneGoalsView());
        out.put("director_state", state.toMap());
        out.put("story_plan", story.runtimeView(state.playerName()));
        return out;
    }

    private Map<String, Object> response(DirectorSession state, DirectorStoryPlan story, String reply,
                                         List<String> applied, List<String> rejected, boolean revealDesign) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", reply);
        out.put("applied", applied);
        out.put("rejected", rejected);
        out.put("state", state.toMap());
        out.put("story_plan", revealDesign ? story.toMap() : story.runtimeView(state.playerName()));
        return out;
    }

    private Map<String, Object> runtimeResponse(DirectorSession state, DirectorStoryPlan story, RouterService router,
                                                String reply, List<String> applied, List<String> rejected) {
        Map<String, Object> out = response(state, story, reply, applied, rejected, false);
        Object active = router.getState().get("agents");
        out.put("active_agents", active instanceof Collection<?> ? active : List.of());
        return out;
    }

    private DirectorSession requirePreflight(String id) {
        String key = required(id, "preflight_id");
        DirectorSession state = preflights.get(key);
        if (state != null) return state;
        state = loadState("director-preflight:" + key);
        if (state == null) throw new IllegalArgumentException("主控开局会话不存在或已过期: " + key);
        preflights.put(key, state);
        if (!state.runtimeSessionId().isBlank()) runtimes.put(state.runtimeSessionId(), state);
        return state;
    }

    private DirectorStoryPlan requirePreflightPlan(DirectorSession state) {
        String id = state.preflightId();
        DirectorStoryPlan plan = preflightPlans.get(id);
        if (plan != null) return plan;
        plan = loadPlan("director-preflight:" + id);
        if (plan == null) plan = new DirectorStoryPlan();
        preflightPlans.put(id, plan);
        return plan;
    }

    private DirectorSession requireRuntime(String sessionId) {
        String key = required(sessionId, "session_id");
        DirectorSession state = runtimes.get(key);
        if (state != null) return state;
        state = loadState("director-runtime:" + key);
        if (state == null) throw new IllegalArgumentException("该对局没有主控权威状态: " + key);
        runtimes.put(key, state);
        preflights.put(state.preflightId(), state);
        return state;
    }

    private DirectorStoryPlan requireRuntimePlan(String sessionId, DirectorSession state) {
        DirectorStoryPlan plan = runtimePlans.get(sessionId);
        if (plan != null) return plan;
        plan = loadPlan("director-runtime:" + sessionId);
        if (plan == null) plan = requirePreflightPlan(state);
        runtimePlans.put(sessionId, plan);
        return plan;
    }

    private void persist(DirectorSession state, DirectorStoryPlan story, boolean runtime) {
        try {
            if (database == null) return;
            String namespace = runtime ? "director-runtime:" + state.runtimeSessionId()
                    : "director-preflight:" + state.preflightId();
            if (runtime && state.runtimeSessionId().isBlank()) return;
            String stateId = runtime ? "director-runtime-state:" + state.runtimeSessionId()
                    : "director-preflight-state:" + state.preflightId();
            String planId = runtime ? "director-runtime-plan:" + state.runtimeSessionId()
                    : "director-preflight-plan:" + state.preflightId();
            database.saveChatMessage(stateId, namespace, 0, "system", STATE_NAME,
                    MAPPER.writeValueAsString(state.toMap()), ChatMessageEntity.STATUS_FINAL, TRACK);
            database.saveChatMessage(planId, namespace, 0, "system", PLAN_NAME,
                    MAPPER.writeValueAsString(story.toMap()), ChatMessageEntity.STATUS_FINAL, TRACK);
        } catch (Exception e) {
            log.warn("Persist Director state/plan failed: {}", e.getMessage());
        }
    }

    private DirectorSession loadState(String namespace) {
        try {
            if (database == null) return null;
            List<Map<String, Object>> rows = database.listChatMessages(namespace, 40);
            for (int i = rows.size() - 1; i >= 0; i--) {
                Map<String, Object> row = rows.get(i);
                if (!STATE_NAME.equals(String.valueOf(row.get("name")))) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = MAPPER.readValue(String.valueOf(row.get("content")), Map.class);
                return DirectorSession.fromMap(raw);
            }
        } catch (Exception e) {
            log.warn("Load DirectorSession failed for {}: {}", namespace, e.getMessage());
        }
        return null;
    }

    private DirectorStoryPlan loadPlan(String namespace) {
        try {
            if (database == null) return null;
            List<Map<String, Object>> rows = database.listChatMessages(namespace, 40);
            for (int i = rows.size() - 1; i >= 0; i--) {
                Map<String, Object> row = rows.get(i);
                if (!PLAN_NAME.equals(String.valueOf(row.get("name")))) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = MAPPER.readValue(String.valueOf(row.get("content")), Map.class);
                return DirectorStoryPlan.fromMap(raw);
            }
        } catch (Exception e) {
            log.warn("Load DirectorStoryPlan failed for {}: {}", namespace, e.getMessage());
        }
        return null;
    }

    /** Remove an accidental confirmation while preserving every other accepted fact. */
    private DirectorSession copyWithoutConfirmation(DirectorSession source) {
        Map<String, Object> raw = new LinkedHashMap<>(source.toMap());
        raw.put("confirmed", false);
        return DirectorSession.fromMap(raw);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static String safe(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return clean.length() <= 2000 ? clean : clean.substring(0, 2000);
    }

    private static String required(String value, String field) {
        String result = safe(value);
        if (result.isBlank()) throw new IllegalArgumentException(field + " required");
        return result;
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return String.valueOf(value).trim();
    }

    private static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> { if (k != null && v != null) out.put(String.valueOf(k), v); });
        return out;
    }

    private static List<Map<String, Object>> objects(Object raw) {
        if (!(raw instanceof Collection<?> items)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : items) if (item instanceof Map<?, ?>) out.add(object(item));
        return out;
    }

    private static List<String> strings(Object raw) {
        if (!(raw instanceof Collection<?> items)) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object item : items) if (item != null && !String.valueOf(item).isBlank()) out.add(String.valueOf(item).trim());
        return List.copyOf(out);
    }

    private record Plan(String reply, List<Operation> operations) {}

    private record Operation(String type, String character, boolean present,
                             List<String> characters, String text,
                             String persona, String voice, String background,
                             String from, String to, String relation,
                             String identity, String goal, String secret) {
        static Operation stage(String character, boolean present) {
            return new Operation("SET_STAGE", character, present, List.of(), "", "", "", "",
                    "", "", "", "", "", "");
        }
        static Operation story(String type, String character, String text) {
            return new Operation(type, character, false, List.of(), text, "", "", "",
                    "", "", "", "", "", "");
        }
        static Operation createNpc(String character, String persona, String voice, String background,
                                   boolean present, String identity, String goal, String secret) {
            return new Operation("CREATE_NPC", character, present, List.of(), "", persona, voice, background,
                    "", "", "", identity, goal, secret);
        }
        static Operation confirm() {
            return new Operation("CONFIRM", "", false, List.of(), "", "", "", "",
                    "", "", "", "", "", "");
        }
        boolean sameMutation(Operation other) {
            return other != null && type.equals(other.type) && character.equals(other.character)
                    && text.equals(other.text) && from.equals(other.from) && to.equals(other.to)
                    && present == other.present;
        }
    }
}
