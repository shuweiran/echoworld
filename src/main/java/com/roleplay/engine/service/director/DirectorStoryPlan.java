package com.roleplay.engine.service.director;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative story design owned by the player-facing Director.
 *
 * <p>This object deliberately stays separate from {@link DirectorSession}: the session
 * owns cast/stage truth, while this plan owns why the scene exists and how it should
 * unfold. Future scene changes can therefore replace/revise the plan without rebuilding
 * character Agents or corrupting stage state.</p>
 */
public final class DirectorStoryPlan {
    private String premise = "";
    private String tone = "";
    private String openingSituation = "";
    private String stakes = "";
    private String scenePrompt = "";
    private final LinkedHashMap<String, String> characterPrompts = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> sceneIdentities = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> characterGoals = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> characterSecrets = new LinkedHashMap<>();
    private final List<Relationship> relationships = new ArrayList<>();
    private final List<String> storyBeats = new ArrayList<>();
    private final List<String> openingEvents = new ArrayList<>();
    private final List<String> worldFacts = new ArrayList<>();

    public static DirectorStoryPlan fromRequest(Map<String, Object> body) {
        DirectorStoryPlan plan = new DirectorStoryPlan();
        if (body == null) return plan;
        plan.setPremise(text(body.get("story_premise"), text(body.get("premise"), "")));
        plan.setTone(text(body.get("story_tone"), text(body.get("tone"), "")));
        plan.setOpeningSituation(text(body.get("opening_situation"), ""));
        plan.setStakes(text(body.get("stakes"), ""));
        plan.scenePrompt = clean(text(body.get("scene_prompt"), text(body.get("scene_description"), "")), 8000);
        captureCharacterPrompts(body.get("characters"), plan.characterPrompts);
        copyStringMap(body.get("scene_identities"), plan.sceneIdentities, 80, 500);
        copyStringMap(body.get("character_goals"), plan.characterGoals, 80, 800);
        copyStringMap(body.get("character_secrets"), plan.characterSecrets, 80, 1200);
        copyStrings(body.get("story_beats"), plan.storyBeats, 40, 1000);
        copyStrings(body.get("opening_events"), plan.openingEvents, 30, 1000);
        copyStrings(body.get("world_facts"), plan.worldFacts, 80, 1000);
        if (body.get("structured_relationships") instanceof Collection<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> raw)) continue;
                plan.setRelationship(text(raw.get("from"), ""), text(raw.get("to"), ""),
                        text(raw.get("relation"), ""), text(raw.get("detail"), text(raw.get("text"), "")));
            }
        }
        return plan;
    }

    public synchronized void setPremise(String value) { premise = clean(value, 2000); }
    public synchronized void setTone(String value) { tone = clean(value, 500); }
    public synchronized void setOpeningSituation(String value) { openingSituation = clean(value, 2000); }
    public synchronized void setStakes(String value) { stakes = clean(value, 1500); }

    public synchronized Result setSceneIdentity(String character, String identity) {
        return setCharacterValue(sceneIdentities, character, identity, 500, "场景身份");
    }

    public synchronized Result setCharacterGoal(String character, String goal) {
        return setCharacterValue(characterGoals, character, goal, 800, "角色目标");
    }

    public synchronized Result setCharacterSecret(String character, String secret) {
        return setCharacterValue(characterSecrets, character, secret, 1200, "角色秘密");
    }

    private Result setCharacterValue(Map<String, String> target, String character, String value,
                                     int max, String label) {
        String name = clean(character, 80);
        String text = clean(value, max);
        if (name.isBlank()) return new Result(false, label + "缺少角色名");
        if (text.isBlank()) {
            target.remove(name);
            return new Result(true, name + " 的" + label + "已清除");
        }
        target.put(name, text);
        return new Result(true, name + " 的" + label + "已设定");
    }

    public synchronized Result setRelationship(String from, String to, String relation, String detail) {
        String a = clean(from, 80);
        String b = clean(to, 80);
        String type = clean(relation, 120);
        String desc = clean(detail, 500);
        if (a.isBlank() || b.isBlank()) return new Result(false, "结构化人物关系需要 from/to");
        relationships.removeIf(r -> r.from().equals(a) && r.to().equals(b));
        relationships.add(new Relationship(a, b, type, desc));
        while (relationships.size() > 80) relationships.remove(0);
        return new Result(true, a + " → " + b + " 的关系已设定");
    }

    public synchronized Result addStoryBeat(String value) {
        return addUnique(storyBeats, value, 40, 1000, "剧情拍点");
    }

    public synchronized Result addOpeningEvent(String value) {
        return addUnique(openingEvents, value, 30, 1000, "开场事件/触发条件");
    }

    public synchronized Result addWorldFact(String value) {
        return addUnique(worldFacts, value, 80, 1000, "世界事实");
    }

    private static Result addUnique(List<String> target, String value, int maxItems, int maxLen, String label) {
        String text = clean(value, maxLen);
        if (text.isBlank()) return new Result(false, label + "内容为空");
        if (!target.contains(text)) target.add(text);
        while (target.size() > maxItems) target.remove(0);
        return new Result(true, label + "已记录");
    }

    /**
     * Shared hidden Director context. Character secrets and private goals are excluded;
     * they are delivered via {@link #privateGuidance(Collection)} only.
     */
    public synchronized String authoritativeDirective() {
        StringBuilder out = new StringBuilder("【开局剧本编排】\n");
        append(out, "剧本前提", premise);
        append(out, "风格/基调", tone);
        append(out, "开场局势", openingSituation);
        append(out, "核心冲突/代价", stakes);
        if (!worldFacts.isEmpty()) out.append("世界事实：").append(String.join("；", worldFacts)).append('\n');
        if (!sceneIdentities.isEmpty()) {
            out.append("场景内身份：\n");
            sceneIdentities.forEach((name, identity) -> out.append("- ").append(name).append("：").append(identity).append('\n'));
        }
        if (!relationships.isEmpty()) {
            out.append("人物关系：\n");
            relationships.forEach(r -> out.append("- ").append(r.summary()).append('\n'));
        }
        if (!storyBeats.isEmpty()) {
            out.append("剧情拍点（规划，不等于已发生事实）：\n");
            for (int i = 0; i < storyBeats.size(); i++) out.append(i + 1).append(". ").append(storyBeats.get(i)).append('\n');
        }
        if (!openingEvents.isEmpty()) {
            out.append("事件/触发条件（满足条件后再自然发生）：\n");
            for (String event : openingEvents) out.append("- ").append(event).append('\n');
        }
        out.append("规则：剧情拍点和触发条件是导演规划，不是已经发生的事实；角色不得提前泄露未来安排。\n")
                .append("角色目标和角色秘密通过私有通道单独注入，禁止推断或复述其他角色的私密信息。");
        return out.toString();
    }

    /** Per-role hidden information; no role receives another role's goal or secret. */
    public synchronized Map<String, String> privateGuidance(Collection<String> characterNames) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (characterNames == null) return out;
        for (String raw : characterNames) {
            String name = clean(raw, 80);
            if (name.isBlank()) continue;
            List<String> parts = new ArrayList<>();
            String identity = sceneIdentities.get(name);
            String goal = characterGoals.get(name);
            String secret = characterSecrets.get(name);
            if (identity != null && !identity.isBlank()) parts.add("你在本场景的身份：" + identity);
            if (goal != null && !goal.isBlank()) parts.add("你的场景目标：" + goal);
            if (secret != null && !secret.isBlank()) parts.add("仅你知道的秘密：" + secret + "。除非剧情自然要求，不要主动泄露");
            if (!parts.isEmpty()) out.put(name, String.join("；", parts));
        }
        return out;
    }

    /** Full authoring view used before launch and for persistence. */
    public synchronized Map<String, Object> toMap() {
        Map<String, Object> out = baseMap();
        out.put("character_goals", new LinkedHashMap<>(characterGoals));
        out.put("character_secrets", new LinkedHashMap<>(characterSecrets));
        out.put("story_beats", List.copyOf(storyBeats));
        out.put("opening_events", List.copyOf(openingEvents));
        out.put("source_material", sourceMaterial());
        return out;
    }

    /** Runtime player view: future beats, NPC private data, and raw source prompts stay hidden. */
    public synchronized Map<String, Object> runtimeView(String playerCharacter) {
        Map<String, Object> out = baseMap();
        String viewer = clean(playerCharacter, 80);
        if (!viewer.isBlank() && characterGoals.containsKey(viewer)) {
            out.put("your_goal", characterGoals.get(viewer));
        }
        if (!viewer.isBlank() && characterSecrets.containsKey(viewer)) {
            out.put("your_secret", characterSecrets.get(viewer));
        }
        out.put("story_beats", List.of());
        out.put("opening_events", List.of());
        out.put("hidden_plan", !storyBeats.isEmpty() || !openingEvents.isEmpty()
                || characterGoals.keySet().stream().anyMatch(n -> !n.equals(viewer))
                || characterSecrets.keySet().stream().anyMatch(n -> !n.equals(viewer))
                || !scenePrompt.isBlank() || !characterPrompts.isEmpty());
        return out;
    }

    private Map<String, Object> baseMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("premise", premise);
        out.put("tone", tone);
        out.put("opening_situation", openingSituation);
        out.put("stakes", stakes);
        out.put("scene_identities", new LinkedHashMap<>(sceneIdentities));
        out.put("structured_relationships", relationships.stream().map(Relationship::toMap).toList());
        out.put("world_facts", List.copyOf(worldFacts));
        return out;
    }

    private synchronized Map<String, Object> sourceMaterial() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("scene_prompt", scenePrompt);
        source.put("character_prompts", new LinkedHashMap<>(characterPrompts));
        source.put("authoring_rule", "开局编排必须优先依据场景 Prompt 与人物 Prompt 推导；不得为了制造冲突而改写人物核心人格、既有背景或场景硬设定。允许补全 Prompt 未规定的关系、场景身份、目标、秘密和事件，但补全部分必须与原 Prompt 相容。");
        return source;
    }

    public static DirectorStoryPlan fromMap(Map<String, Object> raw) {
        DirectorStoryPlan plan = new DirectorStoryPlan();
        if (raw == null) return plan;
        plan.setPremise(text(raw.get("premise"), ""));
        plan.setTone(text(raw.get("tone"), ""));
        plan.setOpeningSituation(text(raw.get("opening_situation"), ""));
        plan.setStakes(text(raw.get("stakes"), ""));
        copyStringMap(raw.get("scene_identities"), plan.sceneIdentities, 80, 500);
        copyStringMap(raw.get("character_goals"), plan.characterGoals, 80, 800);
        copyStringMap(raw.get("character_secrets"), plan.characterSecrets, 80, 1200);
        copyStrings(raw.get("story_beats"), plan.storyBeats, 40, 1000);
        copyStrings(raw.get("opening_events"), plan.openingEvents, 30, 1000);
        copyStrings(raw.get("world_facts"), plan.worldFacts, 80, 1000);
        if (raw.get("source_material") instanceof Map<?, ?> source) {
            plan.scenePrompt = clean(source.get("scene_prompt"), 8000);
            copyStringMap(source.get("character_prompts"), plan.characterPrompts, 80, 6000);
        } else {
            plan.scenePrompt = clean(raw.get("scene_prompt"), 8000);
            copyStringMap(raw.get("character_prompts"), plan.characterPrompts, 80, 6000);
        }
        if (raw.get("structured_relationships") instanceof Collection<?> items) {
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> m)) continue;
                plan.setRelationship(text(m.get("from"), ""), text(m.get("to"), ""),
                        text(m.get("relation"), ""), text(m.get("detail"), ""));
            }
        }
        return plan;
    }

    public synchronized String premise() { return premise; }
    public synchronized String tone() { return tone; }
    public synchronized String openingSituation() { return openingSituation; }
    public synchronized String stakes() { return stakes; }
    public synchronized Map<String, String> sceneIdentities() { return new LinkedHashMap<>(sceneIdentities); }
    public synchronized Map<String, String> characterGoals() { return new LinkedHashMap<>(characterGoals); }
    public synchronized Map<String, String> characterSecrets() { return new LinkedHashMap<>(characterSecrets); }
    public synchronized List<Relationship> relationships() { return List.copyOf(relationships); }
    public synchronized List<String> storyBeats() { return List.copyOf(storyBeats); }
    public synchronized List<String> openingEvents() { return List.copyOf(openingEvents); }
    public synchronized List<String> worldFacts() { return List.copyOf(worldFacts); }
    public synchronized String scenePrompt() { return scenePrompt; }
    public synchronized Map<String, String> characterPrompts() { return new LinkedHashMap<>(characterPrompts); }
    public synchronized String goalFor(String character) { return characterGoals.getOrDefault(clean(character, 80), ""); }

    public synchronized String summary() {
        List<String> parts = new ArrayList<>();
        if (!premise.isBlank()) parts.add("剧本=" + premise);
        if (!openingSituation.isBlank()) parts.add("开场=" + openingSituation);
        if (!sceneIdentities.isEmpty()) parts.add("场景身份=" + sceneIdentities);
        if (!relationships.isEmpty()) parts.add("关系=" + relationships.stream().map(Relationship::summary).toList());
        if (!characterGoals.isEmpty()) parts.add("角色目标已设=" + characterGoals.keySet());
        if (!characterSecrets.isEmpty()) parts.add("角色秘密已设=" + characterSecrets.keySet());
        if (!storyBeats.isEmpty()) parts.add("剧情拍点=" + storyBeats.size() + "个");
        return parts.isEmpty() ? "剧本编排尚未补充" : String.join("；", parts);
    }

    private static void captureCharacterPrompts(Object raw, Map<String, String> target) {
        if (!(raw instanceof Collection<?> items)) return;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> character)) continue;
            String name = clean(character.get("name"), 80);
            if (name.isBlank()) continue;
            List<String> parts = new ArrayList<>();
            addPromptPart(parts, "人格", firstText(character, "persona", "personality"));
            addPromptPart(parts, "背景", firstText(character, "background"));
            addPromptPart(parts, "角色简介", firstText(character, "intro"));
            addPromptPart(parts, "说话风格", firstText(character, "talk_style", "talkStyle", "voice"));
            String prompt = clean(String.join("\n", parts), 6000);
            if (!prompt.isBlank()) target.put(name, prompt);
        }
    }

    private static String firstText(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            String value = clean(map.get(key), 4000);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static void addPromptPart(List<String> target, String label, String value) {
        if (value == null || value.isBlank()) return;
        String line = label + "：" + value;
        if (!target.contains(line)) target.add(line);
    }

    private static void append(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) out.append(label).append('：').append(value).append('\n');
    }

    private static void copyStringMap(Object raw, Map<String, String> target, int keyMax, int valueMax) {
        if (!(raw instanceof Map<?, ?> map)) return;
        map.forEach((k, v) -> {
            String key = clean(k, keyMax);
            String value = clean(v, valueMax);
            if (!key.isBlank() && !value.isBlank()) target.put(key, value);
        });
    }

    private static void copyStrings(Object raw, List<String> target, int maxItems, int maxLen) {
        if (!(raw instanceof Collection<?> items)) return;
        for (Object item : items) {
            String value = clean(item, maxLen);
            if (!value.isBlank() && !target.contains(value)) target.add(value);
            if (target.size() >= maxItems) break;
        }
    }

    private static String text(Object value, String fallback) {
        String clean = clean(value, 4000);
        return clean.isBlank() ? fallback : clean;
    }

    private static String clean(Object value, int max) {
        if (value == null) return "";
        String s = String.valueOf(value).replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record Result(boolean accepted, String detail) {}

    public record Relationship(String from, String to, String relation, String detail) {
        public String summary() {
            String type = relation == null || relation.isBlank() ? "关系" : relation;
            String extra = detail == null || detail.isBlank() ? "" : "（" + detail + "）";
            return from + " → " + to + "：" + type + extra;
        }
        public Map<String, Object> toMap() {
            return Map.of("from", from, "to", to, "relation", relation == null ? "" : relation,
                    "detail", detail == null ? "" : detail);
        }
    }
}
