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
 * Player-facing Director Agent.
 *
 * <p>Architecture boundary: the LLM may interpret an instruction, but it never owns
 * world truth. The server validates each operation, mutates the live Router first,
 * persists the authoritative DirectorSession, and only then reports success.</p>
 */
@Service
public class DirectorAgentService {
    private static final Logger log = LoggerFactory.getLogger(DirectorAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATE_NAME = "director_state";
    private static final String TRACK = "director";
    private static final Pattern NPC_NAME_PATTERN = Pattern.compile(
            "(?:叫|名叫|名字(?:叫|是)?|角色名(?:叫|是)?)[「『\\\"'“‘]?([^，。；;、\\s」』\\\"'”’]{1,24})");
    private static final Pattern NPC_PERSONA_PATTERN = Pattern.compile(
            "(?:性格|人设|设定)(?:是|为|：|:)?([^。；;]{1,180})");

    private static final String SYSTEM_PROMPT = """
            你是 EchoWorld 的玩家侧主控 Agent，不是剧中角色，也不是普通旁白生成器。
            你的职责：在开局前确认玩家扮演身份、角色关系、首批在场角色、出场顺序和场景补充；开局后处理玩家明确发给主控的场景管理要求。
            权限边界：
            1. 角色表 roster 与当前在场 onstage 是不同事实；离场角色不得被描述为仍在参与当前场景。
            2. 你不能替任何角色决定台词、内心或行动；这些属于角色 Agent。
            3. 你不能重写已发生事实，也不能把“打算执行”说成“已经执行”。
            4. 世界状态只由服务器提供的【主控权威状态】决定。你只能提出结构化操作，服务器执行后才会确认结果。
            5. 不得因为一句“让某角色进入场景”而把整个开局流程标记为 confirmed；只有玩家明确确认开始/进入游戏才可 CONFIRM。
            6. 创建新 NPC 时必须给出名字；persona/voice/background 可按玩家提供的信息填写，未提供就留空。不得声称创建成功，必须等服务器确认。
            7. 回复简短、清楚，不写长篇剧情。

            必须只返回 JSON，不要 markdown：
            {"reply":"给玩家的简短说明","operations":[...]}
            operations 支持：
            {"type":"SET_STAGE","character":"角色名","present":true|false}
            {"type":"CREATE_NPC","character":"角色名","persona":"人设","voice":"说话风格","background":"背景","present":true|false}
            {"type":"SET_ENTRY_ORDER","characters":["甲","乙"]}
            {"type":"SET_RELATION","text":"关系事实"}
            {"type":"ADD_RELATION","text":"关系事实"}
            {"type":"ADD_SCENE_NOTE","text":"场景事实"}
            {"type":"CONFIRM"}
            没有状态修改则 operations=[]。
            """;

    private final LLMClient llm;
    private final SessionRegistry sessions;
    private final CharacterController characters;
    private final DatabaseService database;
    private final Map<String, DirectorSession> preflights = new ConcurrentHashMap<>();
    private final Map<String, DirectorSession> runtimes = new ConcurrentHashMap<>();

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
        String hello = "开局前我先和你核对设定。" + state.stateSummary()
                + " 你可以直接说谁先在场、谁稍后进场，或补充关系和场景条件；确认无误后再正式开始。";
        state.addMessage("assistant", hello);
        preflights.put(id, state);
        persist(state, false);
        return response(state, hello, List.of(), List.of());
    }

    public Map<String, Object> getPreflight(String id) {
        DirectorSession state = requirePreflight(id);
        return response(state, "", List.of(), List.of());
    }

    public Map<String, Object> chatPreflight(String id, String text) {
        DirectorSession state = requirePreflight(id);
        String input = required(text, "message");
        state.addMessage("user", input);
        Plan plan = plan(state, input, true);
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Operation op : plan.operations()) {
            if ("CREATE_NPC".equals(op.type())) {
                applyCreateNpc(null, state, op, applied, rejected);
            } else {
                applyToState(state, op, input, applied, rejected);
            }
        }
        // Defense in depth: character-entry language is not permission to open the game gate.
        if (state.confirmed() && !isExplicitEntryConfirmation(input)) {
            rejected.add("未检测到明确的‘确认开始/进入游戏’，本次不开放进场门");
            // A false-positive CONFIRM can only originate in this call; rebuild without confirmation.
            state = copyWithoutConfirmation(state);
            preflights.put(id, state);
        }
        String reply = verifiedReply(state, plan.reply(), applied, rejected, input);
        state.addMessage("assistant", reply);
        persist(state, false);
        return response(state, reply, applied, rejected);
    }

    /**
     * Starts a real roleplay session only from a confirmed preflight. Offstage core
     * characters are moved to RouterService's preserved-Agent suspended store before
     * the first automatic round can run.
     */
    public Map<String, Object> start(String preflightId) {
        DirectorSession state = requirePreflight(preflightId);
        if (!state.confirmed()) throw new IllegalStateException("开局配置尚未明确确认");
        if (!state.runtimeSessionId().isBlank()) {
            RouterService existing = sessions.require(state.runtimeSessionId());
            return startResponse(state, existing);
        }

        List<Persona> personas = new ArrayList<>();
        for (Map<String, Object> c : state.cast()) {
            String name = string(c, "name", "");
            if (name.isBlank()) continue;
            Persona p = personaFrom(c);
            characters.attachPersonaCard(p);
            personas.add(p);
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
        router.setDirectorDirective(state.authoritativeDirective());
        router.ensureSceneGoals(state.sceneId(), state.sceneDescription(), null);
        state.attachRuntime(sessionId);
        runtimes.put(sessionId, state);
        persist(state, false);
        persist(state, true);
        router.triggerAutoFirstRound();
        return startResponse(state, router);
    }

    public Map<String, Object> runtimeState(String sessionId) {
        DirectorSession state = requireRuntime(sessionId);
        RouterService router = sessions.require(sessionId);
        return runtimeResponse(state, router, "", List.of(), List.of());
    }

    public Map<String, Object> chatRuntime(String sessionId, String text) {
        DirectorSession state = requireRuntime(sessionId);
        RouterService router = sessions.require(sessionId);
        String input = required(text, "message");
        state.addMessage("user", input);
        Plan plan = plan(state, input, false);
        List<String> applied = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Operation op : plan.operations()) {
            if ("CREATE_NPC".equals(op.type())) {
                applyCreateNpc(router, state, op, applied, rejected);
            } else if ("SET_STAGE".equals(op.type())) {
                applyRuntimeStage(router, state, op, applied, rejected);
            } else {
                applyToState(state, op, input, applied, rejected);
            }
        }
        router.setDirectorDirective(state.authoritativeDirective());
        String reply = verifiedReply(state, plan.reply(), applied, rejected, input);
        state.addMessage("assistant", reply);
        persist(state, true);
        persist(state, false);
        return runtimeResponse(state, router, reply, applied, rejected);
    }

    /**
     * CREATE_NPC is a transactional Director operation. Text is never treated as success:
     * global character persistence, live Agent registration (when runtime), and Director
     * roster/onstage state must all succeed before an applied result is returned.
     */
    private void applyCreateNpc(RouterService router, DirectorSession state, Operation op,
                                List<String> applied, List<String> rejected) {
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
            applied.add(registered.detail());
        } catch (RuntimeException e) {
            if (router != null && liveAdded) {
                try { router.removeWorldAgent(name); }
                catch (RuntimeException rollbackError) {
                    log.warn("Rollback live NPC failed for {}: {}", name, rollbackError.getMessage());
                }
            }
            if (persisted) {
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
            case "ADD_RELATION", "SET_RELATION" -> {
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
                    applied.add("开局配置已确认");
                } else rejected.add("需要玩家明确说确认开始/进入游戏");
            }
            default -> rejected.add("未知主控操作: " + op.type());
        }
    }

    private Plan plan(DirectorSession state, String input, boolean preflight) {
        Plan fallback = fallbackPlan(state, input, preflight);
        if (!fallback.operations().isEmpty() || asksState(input)) return fallback;
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new Message(Message.Role.SYSTEM, "系统", SYSTEM_PROMPT));
            messages.add(new Message(Message.Role.SYSTEM, "系统", state.authoritativeDirective()));
            List<DirectorSession.DirectorMessage> history = state.messages();
            int from = Math.max(0, history.size() - 20);
            for (int i = from; i < history.size(); i++) {
                DirectorSession.DirectorMessage m = history.get(i);
                Message.Role role = "assistant".equalsIgnoreCase(m.role()) ? Message.Role.AGENT : Message.Role.USER;
                messages.add(new Message(role, role == Message.Role.AGENT ? "主控" : "玩家", m.content()));
            }
            String raw = llm.callSync(messages, null, 700, 0.2);
            Plan parsed = parsePlan(raw);
            if (parsed != null) return parsed;
        } catch (Exception e) {
            log.warn("Director Agent LLM planning failed, using deterministic fallback: {}", e.getMessage());
        }
        return fallback;
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
                    operations.add(new Operation(type, op.path("character").asText(""), present,
                            order, op.path("text").asText(""), op.path("persona").asText(""),
                            op.path("voice").asText(""), op.path("background").asText("")));
                }
            }
            return new Plan(reply, operations);
        } catch (Exception e) {
            return null;
        }
    }

    /** Deterministic parser covers the safety-critical commands even when the LLM is unavailable. */
    Plan fallbackPlan(DirectorSession state, String input, boolean preflight) {
        List<Operation> ops = new ArrayList<>();
        String text = input == null ? "" : input.trim();

        Operation createNpc = deterministicNpcOperation(text);
        if (createNpc != null && !state.knowsCharacter(createNpc.character())) ops.add(createNpc);

        for (Map<String, Object> c : state.cast()) {
            String name = string(c, "name", "");
            if (name.isBlank() || !text.contains(name)) continue;
            // Only inspect the punctuation-delimited clause containing this character.
            // This prevents “兔子在场，鲸鱼离场” from accidentally applying “离场” to 兔子.
            String clause = clauseForCharacter(text, name);
            if (containsAny(clause, "离场", "出去", "退场", "先走", "先等等", "暂时不在场")) {
                ops.add(Operation.stage(name, false));
            } else if (containsAny(clause, "进场", "进入场景", "进来", "回来", "拉进来", "加入场景", "在场")) {
                ops.add(Operation.stage(name, true));
            }
        }
        if (preflight && isExplicitEntryConfirmation(text)) ops.add(Operation.confirm());
        return new Plan(asksState(text) ? state.stateSummary() : "", ops);
    }

    /** Basic offline coverage for commands such as “添加一个叫林夏的新 NPC，性格有点怕生”. */
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
        return Operation.createNpc(name, persona, "", "", present);
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
        return containsAny(text == null ? "" : text, "谁在场", "现在在场", "当前在场", "谁离场", "现在什么状态", "当前状态");
    }

    private String verifiedReply(DirectorSession state, String modelReply, List<String> applied,
                                 List<String> rejected, String input) {
        if (!applied.isEmpty() || !rejected.isEmpty() || asksState(input)) {
            StringBuilder out = new StringBuilder();
            if (!applied.isEmpty()) out.append("已执行：").append(String.join("；", applied)).append("。 ");
            if (!rejected.isEmpty()) out.append("未执行：").append(String.join("；", rejected)).append("。 ");
            out.append(state.stateSummary());
            return out.toString().trim();
        }
        String reply = safe(modelReply);
        return reply.isBlank() ? "我已按当前权威状态理解你的要求。" + state.stateSummary() : reply;
    }

    private Map<String, Object> startResponse(DirectorSession state, RouterService router) {
        Map<String, Object> out = new LinkedHashMap<>(router.getState());
        out.put("session_id", state.runtimeSessionId());
        out.put("mode", state.playerName().isBlank() ? "director" : "protagonist");
        out.put("protagonist", state.playerName());
        out.put("goals", router.getSceneGoalsView());
        out.put("director_state", state.toMap());
        return out;
    }

    private Map<String, Object> response(DirectorSession state, String reply,
                                         List<String> applied, List<String> rejected) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reply", reply);
        out.put("applied", applied);
        out.put("rejected", rejected);
        out.put("state", state.toMap());
        return out;
    }

    private Map<String, Object> runtimeResponse(DirectorSession state, RouterService router, String reply,
                                                List<String> applied, List<String> rejected) {
        Map<String, Object> out = response(state, reply, applied, rejected);
        Object active = router.getState().get("agents");
        out.put("active_agents", active instanceof Collection<?> ? active : List.of());
        return out;
    }

    private DirectorSession requirePreflight(String id) {
        String key = required(id, "preflight_id");
        DirectorSession state = preflights.get(key);
        if (state != null) return state;
        state = load("director-preflight:" + key);
        if (state == null) throw new IllegalArgumentException("主控开局会话不存在或已过期: " + key);
        preflights.put(key, state);
        if (!state.runtimeSessionId().isBlank()) runtimes.put(state.runtimeSessionId(), state);
        return state;
    }

    private DirectorSession requireRuntime(String sessionId) {
        String key = required(sessionId, "session_id");
        DirectorSession state = runtimes.get(key);
        if (state != null) return state;
        state = load("director-runtime:" + key);
        if (state == null) throw new IllegalArgumentException("该对局没有主控权威状态: " + key);
        runtimes.put(key, state);
        preflights.put(state.preflightId(), state);
        return state;
    }

    private void persist(DirectorSession state, boolean runtime) {
        try {
            String namespace = runtime ? "director-runtime:" + state.runtimeSessionId()
                    : "director-preflight:" + state.preflightId();
            if (runtime && state.runtimeSessionId().isBlank()) return;
            String messageId = runtime ? "director-runtime-state:" + state.runtimeSessionId()
                    : "director-preflight-state:" + state.preflightId();
            database.saveChatMessage(messageId, namespace, 0, "system", STATE_NAME,
                    MAPPER.writeValueAsString(state.toMap()), ChatMessageEntity.STATUS_FINAL, TRACK);
        } catch (Exception e) {
            log.warn("Persist DirectorSession failed: {}", e.getMessage());
        }
    }

    private DirectorSession load(String namespace) {
        try {
            List<Map<String, Object>> rows = database.listChatMessages(namespace, 20);
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
                             String persona, String voice, String background) {
        static Operation stage(String character, boolean present) {
            return new Operation("SET_STAGE", character, present, List.of(), "", "", "", "");
        }
        static Operation createNpc(String character, String persona, String voice, String background, boolean present) {
            return new Operation("CREATE_NPC", character, present, List.of(), "", persona, voice, background);
        }
        static Operation confirm() {
            return new Operation("CONFIRM", "", false, List.of(), "", "", "", "");
        }
    }
}
