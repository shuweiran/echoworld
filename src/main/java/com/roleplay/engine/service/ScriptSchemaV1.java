package com.roleplay.engine.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 剧本数据模型 Schema v1（批次 C1：schema 版本化，对齐通用剧本杀范式 Chronos Script Schema v2 的核心子集）。
 *
 * <p>结构化字段（详见 docs/剧本-schema-v1.md）：
 * <ul>
 *   <li>{@code schema_version}: 1 —— 版本内嵌 JSON，不额外建表列（避免 H2 迁移，D-014）</li>
 *   <li>{@code metadata{title, player_min, player_max, tags}} —— 剧本元信息（Chronos metadata）</li>
 *   <li>{@code roles[]{id, name, intro, is_hidden, secret, ap_bonus}} —— 角色表（Chronos roles + 本项目 secret 机制并入角色；ap_bonus 为行动点加成，侦探类角色行动点多，C2）</li>
 *   <li>{@code clues[]{id, title, location, content, transferable, visible_to_owner_only, ap_cost}} —— 线索表（Chronos clues 裁剪；ap_cost 为搜索该线索消耗的行动点，C2）</li>
 *   <li>{@code killer_id} —— 凶手角色 id（D-010：判定实体化，与 truth 文案分离）</li>
 *   <li>{@code truth / background / locations / secrets} —— 真相叙述、背景、搜证地点、角色名→秘密（D5 兼容层）</li>
 * </ul>
 *
 * <p><b>宽容解析</b>：normalize() 同时接受旧格式（roles 为字符串数组、clues 带 public/related_role、无 metadata/killer_id，
 * 如既有测试 mock 与历史 LLM 输出）与 v1 格式，输出统一 v1 规范结构。线索对象保留 {@code public}/{@code related_role}
 * 派生键，保证既有 search()/toMap() 消费（按 public/location/id/content 读取）不被破坏。
 */
public final class ScriptSchemaV1 {

    /** 当前 schema 版本。 */
    public static final int CURRENT_VERSION = 1;

    /** 默认角色健谈度（批次 D：talkativeness 人格化发言概率，缺省 0.5 中性）。 */
    public static final double DEFAULT_TALKATIVENESS = 0.5;

    /** 兜底秘密文案（每个角色必有秘密，A1-3：LLM 异常兜底仍含 secrets 且键集合==roles）。 */
    private static final String FALLBACK_SECRET = "你有一段不愿为人所知的往事，它与这起案件有着隐秘的联系。";

    /** 默认搜证地点（旧 initGame 行为保持）。 */
    private static final List<String> DEFAULT_LOCATIONS = List.of("客厅", "书房", "花园", "厨房", "地下室");

    private ScriptSchemaV1() {}

    // ═══════════════════════════════════════════════════════════
    //  生成路径（统一：ScriptService / ScriptGameService 共用）
    // ═══════════════════════════════════════════════════════════

    /** 剧本生成的 LLM prompt —— 请求 v1 格式输出（normalize 仍宽容兜底）。 */
    public static String buildPrompt(String theme, int playerCount) {
        return String.format("""
            你是一个剧本杀创作者。请根据以下信息生成一个完整的谋杀之谜剧本（剧本数据模型 Schema v1）。

            主题：%s
            角色数：%d

            剧本要求：
            - metadata：title 剧本名称；player_min/player_max 按角色数给合理区间；tags 风格标签
            - roles[]：每个角色一个对象，id 形如 "role_1"（从 1 递增）、name 角色名、intro 角色介绍、
              is_hidden 是否隐藏角色（默认 false）、secret 该角色不可告人的秘密、
              ap_bonus 行动点加成（侦探类角色给 1-2，其余给 0，默认 0）、
              talkativeness 健谈程度（0.0-1.0，侦探/外向角色给 0.6-0.9，内向寡言角色给 0.2-0.4，默认 0.5）
            - 每个角色都有作案动机和秘密，其中一个角色是凶手
            - killer_id：指向凶手角色的 id
            - clues[]：至少 3 条线索，每条含 id、title（线索名）、location（所属地点）、content（线索内容）、
              transferable（可否转交，默认 false）、visible_to_owner_only（是否仅持有者可见，默认 false）、
              ap_cost（搜索该线索消耗的行动点，默认 1）
            - locations[]：3-5 个可搜证地点
            - truth：真相文本（50-80字），必须明示"凶手是X"
            - secrets：角色名 → 秘密（与 roles[].secret 内容一致，方便按角色发放）

            返回JSON格式（不要任何markdown标记，纯JSON）：
            {"schema_version": 1,
             "metadata": {"title": "剧本名称", "player_min": 2, "player_max": 5, "tags": ["本格推理"]},
             "background": "背景故事（100-150字）",
             "roles": [{"id": "role_1", "name": "角色1", "intro": "角色介绍", "is_hidden": false, "secret": "秘密内容", "ap_bonus": 0, "talkativeness": 0.5}],
             "locations": ["地点1", "地点2"],
             "clues": [{"id": "clue_1", "title": "线索名", "location": "地点1", "content": "线索内容", "transferable": false, "visible_to_owner_only": false, "ap_cost": 1}],
             "secrets": {"角色1": "秘密内容"},
             "killer_id": "role_x",
             "truth": "真相（50-80字）"}
            """, theme, playerCount);
    }

    // ═══════════════════════════════════════════════════════════
    //  阶段 1（P-0810-17）：概略剧本（outline）生成路径
    //  两阶段生成第一阶段：只生成概略（地点/人物一句话人设/线索标题/剧情线），
    //  完整剧本由 POST /api/script/generate_full 后台异步补齐。
    // ═══════════════════════════════════════════════════════════

    /**
     * 概略剧本的 LLM prompt —— 轻量输出（目标 <10s，maxTokens≈800-1200），只含
     * locations / roles（名字+一句话人设）/ clues（标题+地点）/ storyline / killer_hint。
     */
    public static String buildOutlinePrompt(String theme, int playerCount) {
        return String.format("""
            你是一个剧本杀创作者。请先生成剧本的概略（outline），用于建局后快速展示给玩家；完整剧本稍后由后台继续生成。

            主题：%s
            角色数：%d

            概略要求（轻量输出，不要生成完整剧本）：
            - locations[]：3-5 个可搜证地点
            - roles[]：每个角色一个对象，含 name 角色名、intro 一句话人设（30字以内）
            - clues[]：3-5 条线索，每条含 title 线索标题、location 所属地点
            - storyline：剧情梗概（50-100字）
            - killer_hint（可选）：凶手的模糊提示

            返回JSON格式（不要任何markdown标记，纯JSON）：
            {"locations": ["客厅", "书房", "花园"],
             "roles": [{"name": "管家", "intro": "沉默寡言的老管家"}],
             "clues": [{"title": "沾血的怀表", "location": "客厅"}],
             "storyline": "风雨夜庄园主人遇害，众人各怀秘密，需要调查推理找出真凶。",
             "killer_hint": "凶手可能与遗嘱有关"}
            """, theme, playerCount);
    }

    /**
     * 概略兜底（LLM 失败/空输出时）：从玩家名单派生出地点/角色/线索/剧情线，零 LLM 可确定性生成。
     */
    public static Map<String, Object> defaultOutline(String theme, List<String> playerNames) {
        List<String> players = playerNames == null ? List.of() : playerNames;
        String t = theme == null || theme.isBlank() ? "默认主题" : theme;
        Map<String, Object> outline = new LinkedHashMap<>();
        outline.put("locations", new ArrayList<>(DEFAULT_LOCATIONS));
        List<Map<String, Object>> roles = new ArrayList<>();
        for (String p : players) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", p);
            r.put("intro", "身份未知的神秘来客");
            roles.add(r);
        }
        if (roles.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", "嫌疑人_1");
            r.put("intro", "身份未知的神秘来客");
            roles.add(r);
        }
        outline.put("roles", roles);
        outline.put("clues", List.of(
            Map.of("title", "碎玻璃", "location", "客厅"),
            Map.of("title", "威胁信", "location", "书房"),
            Map.of("title", "脚印", "location", "花园")));
        outline.put("storyline", t + "：深夜宅邸突发命案，众人各怀秘密，需要搜证推理找出真凶。");
        outline.put("killer_hint", "");
        return outline;
    }

    /**
     * 概略宽容解析：接受 LLM 原始输出（或缺字段）→ 统一输出概略规范结构
     * {locations[], roles[]{name,intro}, clues[]{title,location}, storyline, killer_hint}。
     * 角色缺失时按玩家兜底；角色名即玩家名（概略阶段无角色分配）。
     */
    public static Map<String, Object> normalizeOutline(Map<String, Object> raw, List<String> playerNames, String theme) {
        List<String> players = playerNames == null ? List.of() : playerNames;
        String t = theme == null || theme.isBlank() ? "默认主题" : theme;
        if (raw == null) raw = Map.of();

        List<String> locations = strList(raw.get("locations"));
        if (locations.isEmpty()) locations = new ArrayList<>(DEFAULT_LOCATIONS);

        List<Map<String, Object>> roles = new ArrayList<>();
        Object r = raw.get("roles");
        if (r instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("name", s);
                    rm.put("intro", "身份未知的神秘来客");
                    roles.add(rm);
                } else if (o instanceof Map<?, ?> mm) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    String name = str(mm.get("name"));
                    if (name.isBlank()) continue;
                    rm.put("name", name);
                    rm.put("intro", str(mm.get("intro")));
                    roles.add(rm);
                }
            }
        }
        if (roles.isEmpty()) {
            for (String p : players) {
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("name", p);
                rm.put("intro", "身份未知的神秘来客");
                roles.add(rm);
            }
        }
        if (roles.isEmpty()) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("name", "嫌疑人_1");
            rm.put("intro", "身份未知的神秘来客");
            roles.add(rm);
        }

        List<Map<String, Object>> clues = new ArrayList<>();
        Object c = raw.get("clues");
        if (c instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> cm) {
                    Map<String, Object> cl = new LinkedHashMap<>();
                    cl.put("title", str(cm.get("title")));
                    cl.put("location", str(cm.get("location")));
                    if (!str(cm.get("title")).isBlank()) clues.add(cl);
                }
            }
        }
        if (clues.isEmpty()) {
            clues.add(Map.of("title", "碎玻璃", "location", "客厅"));
            clues.add(Map.of("title", "威胁信", "location", "书房"));
            clues.add(Map.of("title", "脚印", "location", "花园"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("locations", locations);
        out.put("roles", roles);
        out.put("clues", clues);
        String storyline = str(raw.get("storyline"));
        out.put("storyline", storyline.isBlank()
                ? t + "：深夜宅邸突发命案，众人各怀秘密，需要搜证推理找出真凶。" : storyline);
        out.put("killer_hint", str(raw.get("killer_hint")));
        return out;
    }
    /** 兜底剧本（LLM 失败/空输出时），输出同 v1 规范结构。 */
    public static Map<String, Object> defaultScript(String theme, List<String> playerNames) {
        List<String> players = playerNames == null ? List.of() : playerNames;
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", CURRENT_VERSION);
        script.put("name", theme + "谋杀案");
        script.put("background", "在一个风雨交加的夜晚，" + String.join("、", players) + "聚集在一座古老的庄园中。突然，灯光熄灭，当灯光再次亮起时，庄园主人倒在血泊中...");
        script.put("truth", "凶手是" + (players.size() > 0 ? players.get(0) : "未知"));
        Map<String, Object> roles = new LinkedHashMap<>();
        roles.put("roles", players.stream().map(p -> "嫌疑人_" + p).toList());
        roles.put("secrets", players.stream().collect(java.util.stream.Collectors.toMap(
            p -> "嫌疑人_" + p, p -> p + " 有一个隐藏的秘密，与这起案件有关。")));
        roles.put("locations", DEFAULT_LOCATIONS);
        roles.put("clues", List.of(
            Map.of("id", "clue_1", "location", "客厅", "content", "地上有碎玻璃和血迹", "public", false, "related_role", players.size() > 0 ? players.get(0) : ""),
            Map.of("id", "clue_2", "location", "书房", "content", "桌上有一封威胁信", "public", false, "related_role", players.size() > 1 ? players.get(1) : ""),
            Map.of("id", "clue_3", "location", "花园", "content", "泥土中的脚印通向围墙", "public", true, "related_role", "")));
        script.putAll(roles);
        return normalize(script, players, theme);
    }

    // ═══════════════════════════════════════════════════════════
    //  宽容解析 → v1 规范结构
    // ═══════════════════════════════════════════════════════════

    /**
     * 将 LLM 原始输出（旧格式或 v1 格式）归一为 v1 规范结构。
     *
     * @param raw         LLM 返回的剧本 map（可为空）
     * @param playerNames 玩家名单（兜底角色生成用）
     * @param theme       主题（兜底命名用）
     */
    public static Map<String, Object> normalize(Map<String, Object> raw, List<String> playerNames, String theme) {
        List<String> players = playerNames == null ? List.of() : playerNames;
        String t = theme == null ? "默认主题" : theme;
        if (raw == null) raw = Map.of();

        String title = firstNonBlank(
            str(raw.get("title")),
            str(raw.get("name")),
            str(meta(raw, "title")),
            t + "谋杀案");

        List<Map<String, Object>> roles = parseRoles(raw, players);
        Map<String, String> secrets = parseSecrets(raw, roles);
        List<Map<String, Object>> clues = parseClues(raw);
        List<String> locations = parseLocations(raw);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", CURRENT_VERSION);
        out.put("metadata", new LinkedHashMap<>(Map.of(
            "title", title,
            "player_min", intOf(meta(raw, "player_min"), players.isEmpty() ? 1 : players.size()),
            "player_max", intOf(meta(raw, "player_max"), roles.isEmpty() ? Math.max(1, players.size()) : roles.size()),
            "tags", strList(meta(raw, "tags")))));
        out.put("name", title); // 兼容键（旧消费方读 name）
        out.put("background", str(raw.get("background")));
        out.put("roles", roles);
        out.put("locations", locations);
        out.put("clues", clues);
        out.put("secrets", secrets);
        out.put("killer_id", parseKillerId(raw, roles));
        out.put("truth", str(raw.get("truth")));
        return out;
    }

    // ═══════════════════════════════════════════════════════════
    //  规范结构访问器（消费方统一入口，均 null 安全）
    // ═══════════════════════════════════════════════════════════

    /** 剧本标题（metadata.title，兼容 name）。 */
    public static String title(Map<String, Object> script) {
        if (script == null) return "";
        String t = str(meta(script, "title"));
        String result = t.isBlank() ? str(script.get("name")) : t;
        // P-0803-H（源头治理）：LLM 可能把整段描述写进 title（实测 268 字符），下游 scenes.name/scripts.name 都吃这个值。
        // 源头规约到 100 字符，一劳永逸防列溢出（各落库层另有截断兜底）。
        return result.length() <= 100 ? result : result.substring(0, 100);
    }

    public static String background(Map<String, Object> script) {
        return script == null ? "" : str(script.get("background"));
    }

    public static String truth(Map<String, Object> script) {
        return script == null ? "" : str(script.get("truth"));
    }

    public static String killerId(Map<String, Object> script) {
        return script == null ? "" : str(script.get("killer_id"));
    }

    /** 元信息（规范化结构；script 为 null 时返回空 map）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> metadata(Map<String, Object> script) {
        if (script == null) return Map.of();
        Object m = script.get("metadata");
        return m instanceof Map ? (Map<String, Object>) m : Map.of();
    }

    /** 角色对象表（v1 规范：id/name/intro/is_hidden/secret）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> roleObjects(Map<String, Object> script) {
        if (script == null) return List.of();
        Object r = script.get("roles");
        return r instanceof List ? (List<Map<String, Object>>) r : List.of();
    }

    /** 角色名序列（按 roles[] 顺序）。 */
    public static List<String> roleNames(Map<String, Object> script) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> role : roleObjects(script)) {
            String n = str(role.get("name"));
            if (!n.isBlank()) names.add(n);
        }
        return names;
    }

    /** 角色 id → 角色名 映射。 */
    public static Map<String, String> roleNamesById(Map<String, Object> script) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map<String, Object> role : roleObjects(script)) {
            String id = str(role.get("id"));
            String name = str(role.get("name"));
            if (!id.isBlank() && !name.isBlank()) m.put(id, name);
        }
        return m;
    }

    /** 角色名 → 秘密（D5 兼容层；LLM 未给任何秘密时兑底保证键集合==roles，A1-3；部分秘密则保持部分）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, String> secretsByRole(Map<String, Object> script) {
        if (script == null) return Map.of();
        Object s = script.get("secrets");
        return s instanceof Map ? (Map<String, String>) s : Map.of();
    }

    /** 规范化线索表（id/title/location/content/transferable/visible_to_owner_only + public/related_role 兼容键）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> clueList(Map<String, Object> script) {
        if (script == null) return List.of();
        Object c = script.get("clues");
        return c instanceof List ? (List<Map<String, Object>>) c : List.of();
    }

    /** 搜证地点。 */
    public static List<String> locations(Map<String, Object> script) {
        if (script == null) return List.of();
        return strList(script.get("locations"));
    }

    // ═══════════════════════════════════════════════════════════
    //  C2: 行动点字段访问器（AP 行动点 + 线索转交，对齐 Chronos CLUE_SEARCH）
    // ═══════════════════════════════════════════════════════════

    /** 线索行动点消耗（缺省 1；旧剧本无 ap_cost 字段时搜证按 1 扣，向后兼容）。 */
    public static int apCost(Map<String, Object> clue) {
        if (clue == null) return 1;
        return intOf(clue.get("ap_cost"), 1);
    }

    /** 角色行动点加成（缺省 0；侦探类角色可给 1-2，行动点多，蓝图 P2“角色差异化搜证”）。 */
    public static int roleApBonus(Map<String, Object> role) {
        if (role == null) return 0;
        return intOf(role.get("ap_bonus"), 0);
    }

    /** 角色名 → 行动点加成 映射（initGame 按玩家分配到的角色查初始 AP）。 */
    public static Map<String, Integer> apBonusByRoleName(Map<String, Object> script) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Map<String, Object> role : roleObjects(script)) {
            String name = str(role.get("name"));
            if (!name.isBlank()) m.put(name, roleApBonus(role));
        }
        return m;
    }

    // ═══════════════════════════════════════════════════════════
    //  批次 D: talkativeness 人格化健谈度（发言门控概率输入）
    // ═══════════════════════════════════════════════════════════

    /** 角色健谈度（缺省 0.5；兼容 roles[].personality.talkativeness 嵌套写法，缺省 0.5）。 */
    public static double roleTalkativeness(Map<String, Object> role) {
        if (role == null) return DEFAULT_TALKATIVENESS;
        Object t = role.get("talkativeness");
        if (t instanceof Number n) return clamp01(n.doubleValue());
        Object per = role.get("personality");
        if (per instanceof Map<?, ?> pm) {
            Object pt = pm.get("talkativeness");
            if (pt instanceof Number n) return clamp01(n.doubleValue());
        }
        return DEFAULT_TALKATIVENESS;
    }

    /** 角色名 → 健谈度 映射（initGame 按玩家分配到的角色查发言概率；缺省 0.5）。 */
    public static Map<String, Double> talkativenessByRoleName(Map<String, Object> script) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Map<String, Object> role : roleObjects(script)) {
            String name = str(role.get("name"));
            if (!name.isBlank()) m.put(name, roleTalkativeness(role));
        }
        return m;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ═══════════════════════════════════════════════════════════
    //  解析辅助
    // ═══════════════════════════════════════════════════════════

    /** roles：接受 List<String>（旧格式）与 List<Map>（v1），输出规范角色对象表；缺失时按玩家兜底。 */
    private static List<Map<String, Object>> parseRoles(Map<String, Object> raw, List<String> players) {
        List<Map<String, Object>> roles = new ArrayList<>();
        Object r = raw.get("roles");
        if (r instanceof List<?> list) {
            int idx = 1;
            for (Object o : list) {
                if (o instanceof String s) {
                    roles.add(role("role_" + idx, s, "", false, "", 0));
                    idx++;
                } else if (o instanceof Map<?, ?> mm) {
                    String id = str(mm.get("id"));
                    Map<String, Object> rl = role(id.isBlank() ? "role_" + idx : id,
                        str(mm.get("name")),
                        str(mm.get("intro")),
                        Boolean.TRUE.equals(mm.get("is_hidden")),
                        str(mm.get("secret")),
                        intOf(mm.get("ap_bonus"), 0));
                    // 批次 D: talkativeness（roles[].talkativeness 或 roles[].personality.talkativeness，缺省 0.5）
                    Object t = mm.get("talkativeness");
                    if (t instanceof Number n) {
                        rl.put("talkativeness", clamp01(n.doubleValue()));
                    } else if (mm.get("personality") instanceof Map<?, ?> pm
                            && pm.get("talkativeness") instanceof Number pn) {
                        rl.put("talkativeness", clamp01(pn.doubleValue()));
                    }
                    roles.add(rl);
                    idx++;
                }
            }
        }
        if (roles.isEmpty()) {
            int idx = 1;
            for (String p : players) {
                roles.add(role("role_" + idx, "嫌疑人_" + p, "", false, "", 0));
                idx++;
            }
        }
        roles.removeIf(m -> str(m.get("name")).isBlank());
        return roles;
    }

    private static Map<String, Object> role(String id, String name, String intro, boolean hidden, String secret, int apBonus) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("intro", intro);
        m.put("is_hidden", hidden);
        m.put("secret", secret);
        m.put("ap_bonus", apBonus);
        return m;
    }

    /** secrets：raw.secrets（角色名键）∪ roles[].secret；仅当所有角色均无秘密时才全量兑底（A1-3），部分秘密保持部分不臆造。 */
    private static Map<String, String> parseSecrets(Map<String, Object> raw, List<Map<String, Object>> roles) {
        Map<String, String> secrets = new LinkedHashMap<>();
        Object s = raw.get("secrets");
        if (s instanceof Map<?, ?> sm) {
            for (Map.Entry<?, ?> e : sm.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                secrets.put(str(e.getKey()), str(e.getValue()));
            }
        }
        for (Map<String, Object> role : roles) {
            String name = str(role.get("name"));
            String sec = str(role.get("secret"));
            if (!name.isBlank() && !sec.isBlank()) secrets.put(name, sec);
        }
        // 仅当 LLM 完全未给任何角色秘密时兑底（A1-3：每个角色必有秘密，键集合==roles）；
        // 若 LLM 只给了部分角色秘密（如 A3-2/A3-4 仅管家），其余角色保持无秘密，不臆造。
        boolean anySecret = secrets.values().stream().anyMatch(v -> v != null && !v.isBlank());
        if (!anySecret) {
            for (Map<String, Object> role : roles) {
                String name = str(role.get("name"));
                if (!name.isBlank()) secrets.putIfAbsent(name, FALLBACK_SECRET);
            }
        }
        return secrets;
    }

    /** clues：规范化线索对象（含 public/related_role 派生键供既有消费）；缺失时兜底 3 条。 */
    private static List<Map<String, Object>> parseClues(Map<String, Object> raw) {
        List<Map<String, Object>> clues = new ArrayList<>();
        Object c = raw.get("clues");
        if (c instanceof List<?> list) {
            int idx = 1;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> cm)) { idx++; continue; }
                Map<String, Object> m = new LinkedHashMap<>();
                String id = str(cm.get("id"));
                m.put("id", id.isBlank() ? "clue_" + idx : id);
                String content = str(cm.get("content"));
                String title = str(cm.get("title"));
                m.put("title", title.isBlank()
                    ? (content.length() > 20 ? content.substring(0, 20) : content) : title);
                m.put("location", str(cm.get("location")));
                m.put("content", content);
                m.put("transferable", Boolean.TRUE.equals(cm.get("transferable")));
                Object voo = cm.get("visible_to_owner_only");
                Object pub = cm.get("public");
                boolean ownerOnly = voo instanceof Boolean vb ? vb : !Boolean.TRUE.equals(pub);
                m.put("visible_to_owner_only", ownerOnly);
                // C2: 行动点消耗（缺省 1，旧剧本无 ap_cost 时搜证按 1 扣）
                m.put("ap_cost", intOf(cm.get("ap_cost"), 1));
                // 兼容派生键：search()/toMap() 按 public/location/id/content 消费
                m.put("public", pub instanceof Boolean pb ? pb : !ownerOnly);
                if (cm.get("related_role") != null) m.put("related_role", str(cm.get("related_role")));
                clues.add(m);
                idx++;
            }
        }
        if (clues.isEmpty()) {
            clues.add(clue("clue_1", "客厅", "地上有碎玻璃和血迹", false, ""));
            clues.add(clue("clue_2", "书房", "桌上有一封威胁信", false, ""));
            clues.add(clue("clue_3", "花园", "泥土中的脚印通向围墙", true, ""));
        }
        return clues;
    }

    private static Map<String, Object> clue(String id, String location, String content, boolean pub, String relatedRole) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", content.length() > 20 ? content.substring(0, 20) : content);
        m.put("location", location);
        m.put("content", content);
        m.put("transferable", false);
        m.put("visible_to_owner_only", !pub);
        m.put("public", pub);
        m.put("ap_cost", 1); // C2: 默认线索行动点消耗 1
        if (!relatedRole.isBlank()) m.put("related_role", relatedRole);
        return m;
    }

    private static List<String> parseLocations(Map<String, Object> raw) {
        List<String> locations = strList(raw.get("locations"));
        return locations.isEmpty() ? DEFAULT_LOCATIONS : locations;
    }

    /** killer_id：优先 schema 字段并校验存在；兼容旧 killer（角色名）反查 id。 */
    private static String parseKillerId(Map<String, Object> raw, List<Map<String, Object>> roles) {
        String kid = str(raw.get("killer_id"));
        if (!kid.isBlank()) {
            for (Map<String, Object> r : roles) {
                if (kid.equals(str(r.get("id")))) return kid;
            }
        }
        String killerName = str(raw.get("killer"));
        if (!killerName.isBlank()) {
            for (Map<String, Object> r : roles) {
                if (killerName.equals(str(r.get("name")))) return str(r.get("id"));
            }
        }
        return "";
    }

    private static Object meta(Map<String, Object> raw, String key) {
        Object m = raw.get("metadata");
        return m instanceof Map<?, ?> mm ? mm.get(key) : null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? def : Integer.parseInt(str(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<String> strList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object x : l) {
                if (x != null) out.add(String.valueOf(x));
            }
            return out;
        }
        return List.of();
    }
}
