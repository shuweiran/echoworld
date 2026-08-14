package com.roleplay.engine.simulation.map.interact;

import com.roleplay.engine.simulation.map.MapContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P-0814-H 热点/搜证点交互分发器（依据 tmp/调研-星露谷地图数据物品交互-20260813.md 核心借鉴 4/5 = I1/I2/I3）：
 *
 * <p>星露谷交互范式 = 「固定半径（1 格切比雪夫）+ 统一动作键 → 一条 checkAction 优先级链」——
 * <ul>
 *   <li><b>半径判定</b>：Chebyshev |dx|≤r 且 |dy|≤r，默认 r=1（{@link #DEFAULT_RADIUS}），decor.radius 可覆盖；
 *       超半径 → 拒绝 + 「够不着」提示文案（对齐星露谷光标半透明够不着反馈）；缺玩家坐标跳过（尽力而为，对齐 switchMap 语义）。</li>
 *   <li><b>优先级链</b>：decor 实体 &gt; tileProps.action &gt; 环境占位（{@link Target#kind()} 留扩展位，后续可加
 *       zone 级交互/实体类型分流）。</li>
 *   <li><b>动作分发表</b>（数据驱动，参考 Data/Machines 触发器/条件/产出/反馈一体表）：dialog（返回文本）/
 *       addItem（给玩家加线索物品）/ flag（写一次性标记）/ sound+anim（占位返回，前端播）/ menu（占位，后续扩展）/
 *       state（实例状态字段变更）；一次交互可叠加多个动作；未知动作 → 忽略 + warning。</li>
 *   <li><b>once 幂等</b>：decor.once=true 交互后标记 processed，重复交互返回「已处理」语义（对齐既有 searchedLocations 幂等风格）。</li>
 *   <li><b>conditions 门</b>：requireFlag 不满足 → failDialog 文案返回，不执行任何动作。</li>
 * </ul>
 *
 * <p>本类为纯逻辑（无 Spring、不持有游戏状态）：状态变更经 {@link GameContext} 接口由调用方（ScriptGameService 匿名实现）
 * 落地；三层持久化（热点实例状态 decorStates / 一次性 flag decorFlags / 玩家持有 playerClues）由调用方在
 * 对局快照层负责（本类只产生变更指令）。单测用假上下文直测全链。
 */
public final class MapInteractService {

    /** 默认交互半径（星露谷 Utility.tileWithinRadiusOfPlayer radius=1 范式）。 */
    public static final int DEFAULT_RADIUS = 1;

    /** 环境占位文案（优先级链最末：该格无 decor 也无 tileProps.action）。 */
    public static final String PLACEHOLDER_TEXT = "这里没有什么特别的。";

    /** 一次性已处理语义文案（对齐搜证「已搜证过」幂等风格）。 */
    public static final String ALREADY_PROCESSED_TEXT = "该处已处理过";

    /** 够不着反馈前缀（对齐星露谷「够不着」提示语义）。 */
    public static final String OUT_OF_RANGE_TEXT = "够不着：距离超过交互半径";

    private MapInteractService() {
    }

    /**
     * 交互目标（优先级链解析结果）：kind = decor 实体 / tileProps 瓦片动作 / placeholder 环境占位。
     *
     * @param decorId  decor 实体 id（tileProps/占位目标为空串）
     * @param decor    decor 实体原始数据（宽容解析副本；非 decor 目标为 null）
     * @param action   tileProps 目标的分发表动作名（decor 目标为 null，动作在 onInteract 里）
     * @param args     tileProps 目标透传参数（decor 目标为 null）
     * @param tx, ty   目标格坐标
     */
    public record Target(String kind, String decorId, Map<String, Object> decor,
                         String action, Object args, int tx, int ty) {
        public boolean isDecor() {
            return "decor".equals(kind);
        }
    }

    /**
     * 游戏状态访问接口 —— 纯逻辑侧零状态，状态落地由调用方（ScriptGameService）实现；
     * 单测注入假上下文即可全链直测（半径/优先级/once/conditions/动作表）。
     */
    public interface GameContext {
        /**
         * 授予线索物品（对齐既有 playerClues 持有机制 + 线索表）。返回是否实际授予：
         * 已持有 / 未知线索 id 且无 title-content 数据 → false（不授予、不报错）。
         *
         * @param clueData addItem 动作携带的附加数据（{id, title, content, ...}；可为 null/空）
         */
        boolean grantClue(String player, String clueId, Map<String, Object> clueData);

        /** 既有线索标题（addItem 响应展示用；未知返回 null）。 */
        String clueTitle(String clueId);

        /** 一次性 flag 是否已写（conditions.requireFlag 门读取）。 */
        boolean hasFlag(String flag);

        /** 写一次性 flag（对齐 searchedLocations 幂等标记范式，由调用方持久化）。 */
        void writeFlag(String flag);

        /** decor 是否已处理（once 幂等判定）。 */
        boolean isProcessed(String mapId, String decorId);

        /** 标记 decor 已处理。 */
        void setProcessed(String mapId, String decorId);

        /** 运行时状态覆盖（不含 decor.state 初始值；无覆盖返回空 map）。 */
        Map<String, Object> runtimeState(String mapId, String decorId);

        /** 写运行时状态（合并后的完整有效状态；调用方持久化）。 */
        void setRuntimeState(String mapId, String decorId, Map<String, Object> merged);
    }

    /**
     * 交互主链：目标解析（优先级链）→ 半径判定 → once 幂等 → conditions 门 → 动作分发表执行 → 状态落地。
     *
     * @param mapId   目标地图 id（调用方已解析为注册表键）
     * @param mapData 地图数据（契约 v1/v0.2，宽容读取）
     * @param decorId 显式目标 decor id（可空 —— 缺省走 tile 坐标解析）
     * @param tile    目标格坐标 "x,y"（可空 —— 与 decor_id 至少其一）
     * @param px, py  玩家瓦片坐标（可空 —— 缺省跳过靠近校验，尽力而为）
     * @return 交互结果（{ok, handled, dialog/items/flags/sounds/anims/menu/state/processed/result/warnings} 按需附加）
     */
    public static Map<String, Object> interact(String mapId, Map<String, Object> mapData, String player,
                                               String decorId, String tile, Integer px, Integer py,
                                               GameContext ctx) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("map_id", mapId);
        if (player != null && !player.isBlank()) resp.put("player", player);

        // 1) 目标解析：decor_id 显式 > tile 坐标（decor 实体 > tileProps.action > 环境占位）
        Target target = resolveTarget(mapData, decorId, tile, resp);
        if (target == null) return resp; // resolveTarget 已写 error

        resp.put("tile", List.of(target.tx(), target.ty()));
        if (target.isDecor()) resp.put("decor_id", target.decorId());

        // 2) 半径判定（Chebyshev |dx|≤r 且 |dy|≤r；缺玩家坐标跳过 —— 客户端上报，尽力而为）
        if (px != null && py != null) {
            int r = radiusOf(target.decor());
            int dx = Math.abs(px - target.tx());
            int dy = Math.abs(py - target.ty());
            if (dx > r || dy > r) {
                resp.put("handled", false);
                resp.put("error", OUT_OF_RANGE_TEXT + " " + r + " 格（距目标 " + Math.max(dx, dy) + " > " + r + "）");
                return resp;
            }
        }

        // 3) once 幂等（对齐 searchedLocations 幂等语义：已处理 → 直接返回，不重复执行）
        if (target.isDecor() && Boolean.TRUE.equals(target.decor().get("once"))
                && ctx.isProcessed(mapId, target.decorId())) {
            resp.put("handled", false);
            resp.put("processed", true);
            resp.put("result", ALREADY_PROCESSED_TEXT + "（" + target.decorId() + "）");
            return resp;
        }

        // 4) conditions 门：requireFlag 不满足 → failDialog 文案返回，不执行动作
        if (target.isDecor()) {
            Map<String, Object> cond = mapOf(target.decor().get("conditions"));
            String requireFlag = MapContract.str(cond.get("requireFlag"), "");
            if (!requireFlag.isBlank() && !ctx.hasFlag(requireFlag)) {
                String fail = MapContract.str(cond.get("failDialog"), "条件未满足，无法交互");
                resp.put("handled", false);
                resp.put("blocked", true);
                resp.put("require_flag", requireFlag);
                resp.put("dialog", List.of(fail));
                resp.put("result", fail);
                return resp;
            }
        }

        // 5) 动作计划：decor.onInteract（map 或 list 形态）或 tileProps.action（分发表查表）
        List<Map<String, Object>> plan = actionPlanOf(target);
        if (plan.isEmpty()) {
            resp.put("handled", false);
            resp.put("result", target.isDecor()
                    ? "你看了看「" + MapContract.str(target.decor().get("type"), "?") + "」，" + PLACEHOLDER_TEXT
                    : PLACEHOLDER_TEXT);
            return resp;
        }

        // 6) 动作分发表执行（一次交互可叠加多个动作；未知动作 → 忽略 + warning）
        List<String> dialogs = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        List<String> sounds = new ArrayList<>();
        List<String> anims = new ArrayList<>();
        Map<String, Object> menu = null;
        List<String> warnings = new ArrayList<>();
        Map<String, Object> effectiveState = null;
        boolean anyExecuted = false;

        for (Map<String, Object> step : plan) {
            for (Map.Entry<String, Object> e : step.entrySet()) {
                String act = e.getKey();
                Object payload = e.getValue();
                switch (act) {
                    case "dialog" -> {
                        if (payload instanceof List<?> dl) {
                            for (Object o : dl) {
                                if (o != null) dialogs.add(String.valueOf(o));
                            }
                        } else if (payload != null) {
                            dialogs.add(String.valueOf(payload));
                        } else {
                            warnings.add("dialog 动作缺少文本");
                            continue;
                        }
                        anyExecuted = true;
                    }
                    case "addItem" -> {
                        String clueId;
                        Map<String, Object> data = null;
                        if (payload instanceof Map<?, ?> pm) {
                            clueId = MapContract.str(pm.get("id"), MapContract.str(pm.get("clue_id"), ""));
                            data = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> en : pm.entrySet()) {
                                if (en.getKey() != null) data.put(String.valueOf(en.getKey()), en.getValue());
                            }
                        } else if (payload != null) {
                            clueId = String.valueOf(payload).trim();
                        } else {
                            warnings.add("addItem 缺少线索 id");
                            continue;
                        }
                        if (clueId.isBlank()) {
                            warnings.add("addItem 缺少线索 id");
                            continue;
                        }
                        if (ctx.grantClue(player, clueId, data)) {
                            Map<String, Object> it = new LinkedHashMap<>();
                            it.put("id", clueId);
                            String title = ctx.clueTitle(clueId);
                            it.put("title", title != null ? title
                                    : (data != null && data.get("title") != null ? String.valueOf(data.get("title")) : clueId));
                            items.add(it);
                            anyExecuted = true;
                        }
                    }
                    case "flag" -> {
                        String name = null;
                        if (payload instanceof Map<?, ?> pm) {
                            name = MapContract.str(pm.get("name"), MapContract.str(pm.get("flag"), ""));
                        } else if (payload != null) {
                            name = String.valueOf(payload).trim();
                        }
                        if (name == null || name.isBlank()) {
                            warnings.add("flag 动作缺少标记名");
                            continue;
                        }
                        if (!ctx.hasFlag(name)) {
                            ctx.writeFlag(name);
                            flags.add(name);
                        }
                        anyExecuted = true;
                    }
                    case "state" -> {
                        if (payload instanceof Map<?, ?> pm) {
                            if (target.isDecor()) {
                                if (effectiveState == null) {
                                    effectiveState = new LinkedHashMap<>();
                                    effectiveState.putAll(mapOf(target.decor().get("state"))); // 初始 state 为基底
                                    effectiveState.putAll(ctx.runtimeState(mapId, target.decorId())); // 运行时覆盖
                                }
                                for (Map.Entry<?, ?> en : pm.entrySet()) {
                                    if (en.getKey() != null) effectiveState.put(String.valueOf(en.getKey()), en.getValue());
                                }
                                anyExecuted = true;
                            } else {
                                warnings.add("state 动作仅 decor 实体支持（当前目标: " + target.kind() + "）");
                            }
                        } else {
                            warnings.add("state 动作需要对象 payload");
                        }
                    }
                    case "sound" -> {
                        String n = payload instanceof Map<?, ?> pm
                                ? MapContract.str(pm.get("name"), MapContract.str(pm.get("sound"), ""))
                                : (payload != null ? String.valueOf(payload).trim() : "");
                        if (n.isBlank()) {
                            warnings.add("sound 动作缺少名称");
                            continue;
                        }
                        sounds.add(n);
                        anyExecuted = true;
                    }
                    case "anim" -> {
                        String n = payload instanceof Map<?, ?> pm
                                ? MapContract.str(pm.get("name"), MapContract.str(pm.get("anim"), ""))
                                : (payload != null ? String.valueOf(payload).trim() : "");
                        if (n.isBlank()) {
                            warnings.add("anim 动作缺少名称");
                            continue;
                        }
                        anims.add(n);
                        anyExecuted = true;
                    }
                    case "menu" -> {
                        if (payload instanceof Map<?, ?> pm) {
                            String type = MapContract.str(pm.get("type"), "");
                            if (type.isBlank()) {
                                warnings.add("menu 动作缺少 type");
                                continue;
                            }
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("type", type);
                            if (pm.get("hint") != null) m.put("hint", String.valueOf(pm.get("hint")));
                            menu = m;
                            anyExecuted = true;
                        } else if (payload != null) {
                            menu = Map.of("type", String.valueOf(payload));
                            anyExecuted = true;
                        } else {
                            warnings.add("menu 动作缺少 type");
                        }
                    }
                    default -> warnings.add("未知动作类型被忽略: " + act);
                }
            }
        }

        if (!anyExecuted) {
            resp.put("handled", false);
            resp.put("result", "交互未产生任何效果");
            if (!warnings.isEmpty()) resp.put("warnings", warnings);
            return resp;
        }

        // 7) 状态落地：实例状态合并（进对局快照由调用方负责）+ once 标记
        if (effectiveState != null && target.isDecor()) {
            ctx.setRuntimeState(mapId, target.decorId(), effectiveState);
            resp.put("state", effectiveState);
        }
        boolean processedNow = false;
        if (target.isDecor() && Boolean.TRUE.equals(target.decor().get("once"))
                && !ctx.isProcessed(mapId, target.decorId())) {
            ctx.setProcessed(mapId, target.decorId());
            processedNow = true;
            resp.put("processed", true);
        }
        resp.put("handled", true);

        if (!dialogs.isEmpty()) resp.put("dialog", dialogs);
        if (!items.isEmpty()) resp.put("items", items);
        if (!flags.isEmpty()) resp.put("flags", flags);
        if (!sounds.isEmpty()) resp.put("sounds", sounds);
        if (!anims.isEmpty()) resp.put("anims", anims);
        if (menu != null) resp.put("menu", menu);
        if (!warnings.isEmpty()) resp.put("warnings", warnings);

        StringBuilder sb = new StringBuilder("交互成功");
        if (!dialogs.isEmpty()) sb.append("：").append(dialogs.get(0));
        if (!items.isEmpty()) sb.append("；获得线索 ").append(items.size()).append(" 条");
        if (processedNow) sb.append("（一次性，已处理）");
        resp.put("result", sb.toString());
        return resp;
    }

    // ═══════════════════════════════════════════════════════════
    //  目标解析（优先级链）与宽容读取辅助
    // ═══════════════════════════════════════════════════════════

    /** 优先级链目标解析：decor_id 显式 > tile 坐标（decor 实体 > tileProps.action > 环境占位）。失败写 error 返回 null。 */
    private static Target resolveTarget(Map<String, Object> mapData, String decorId, String tile,
                                        Map<String, Object> resp) {
        if (decorId != null && !decorId.isBlank()) {
            Map<String, Object> decor = findDecor(mapData, decorId.trim());
            if (decor == null) {
                resp.put("ok", false);
                resp.put("error", "decor 不存在: " + decorId.trim());
                return null;
            }
            int[] t = tileOf(decor);
            return new Target("decor", decorId.trim(), decor, null, null, t[0], t[1]);
        }
        if (tile != null && !tile.isBlank()) {
            int[] t = MapContract.tileKey(tile.trim());
            if (t == null) {
                resp.put("ok", false);
                resp.put("error", "tile 坐标格式非法（应为 \"x,y\"）: " + tile);
                return null;
            }
            Map<String, Object> decor = findDecorAt(mapData, t[0], t[1]);
            if (decor != null) {
                String did = MapContract.str(decor.get("id"), "decor@" + t[0] + "," + t[1]);
                return new Target("decor", did, decor, null, null, t[0], t[1]);
            }
            Map<String, Object> tp = tilePropsAt(mapData, t[0], t[1]);
            if (tp != null) {
                return new Target("tileProps", "", null,
                        MapContract.str(tp.get("action"), ""), tp.get("args"), t[0], t[1]);
            }
            return new Target("placeholder", "", null, null, null, t[0], t[1]);
        }
        resp.put("ok", false);
        resp.put("error", "缺少交互目标（decor_id 或 tile 至少其一）");
        return null;
    }

    /** 动作计划：decor.onInteract（map 或 list 形态，按序 step）；tileProps.action → 单步 {action: args}。 */
    private static List<Map<String, Object>> actionPlanOf(Target target) {
        List<Map<String, Object>> plan = new ArrayList<>();
        if (target.isDecor()) {
            Object oi = target.decor().get("onInteract");
            if (oi instanceof Map<?, ?> om) {
                plan.add(stringKeyedCopy(om));
            } else if (oi instanceof List<?> ol) {
                for (Object o : ol) {
                    if (o instanceof Map<?, ?> om) plan.add(stringKeyedCopy(om));
                }
            }
        } else if ("tileProps".equals(target.kind()) && target.action() != null && !target.action().isBlank()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put(target.action(), target.args());
            plan.add(step);
        }
        return plan;
    }

    /** decor 有效半径：decor.radius（≥1）覆盖，缺省 DEFAULT_RADIUS。 */
    private static int radiusOf(Map<String, Object> decor) {
        if (decor != null) {
            int r = MapContract.intOf(decor.get("radius"), DEFAULT_RADIUS);
            if (r >= 1) return r;
        }
        return DEFAULT_RADIUS;
    }

    private static Map<String, Object> findDecor(Map<String, Object> mapData, String decorId) {
        if (mapData == null || decorId == null) return null;
        Object d = mapData.get("decor");
        if (!(d instanceof List<?> list)) return null;
        for (Object o : list) {
            if (o instanceof Map<?, ?> dm && decorId.equals(String.valueOf(dm.get("id")))) {
                return stringKeyedCopy(dm);
            }
        }
        return null;
    }

    private static Map<String, Object> findDecorAt(Map<String, Object> mapData, int x, int y) {
        if (mapData == null) return null;
        Object d = mapData.get("decor");
        if (!(d instanceof List<?> list)) return null;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> dm)) continue;
            int[] t = tileOf(stringKeyedCopy(dm));
            if (t[0] == x && t[1] == y) return stringKeyedCopy(dm);
        }
        return null;
    }

    /** decor.tile → [x, y]（宽容 Number/String；缺失/非法 → [-1, -1]）。 */
    private static int[] tileOf(Map<String, Object> decor) {
        Object t = decor.get("tile");
        if (t instanceof List<?> tl && tl.size() >= 2) {
            return new int[]{MapContract.intOf(tl.get(0), -1), MapContract.intOf(tl.get(1), -1)};
        }
        return new int[]{-1, -1};
    }

    /** tileProps["x,y"] → 属性字典（无 action 时由调用链落入占位）。 */
    private static Map<String, Object> tilePropsAt(Map<String, Object> mapData, int x, int y) {
        if (mapData == null) return null;
        Object tp = mapData.get("tileProps");
        if (!(tp instanceof Map<?, ?> m)) return null;
        Object v = m.get(x + "," + y);
        if (!(v instanceof Map<?, ?> vm)) return null;
        return stringKeyedCopy(vm);
    }

    /** Map&lt;?,?&gt; → Map&lt;String,Object&gt;（宽容复制，null 键丢弃）。 */
    private static Map<String, Object> mapOf(Object o) {
        if (o instanceof Map<?, ?> m) return stringKeyedCopy(m);
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> stringKeyedCopy(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }
}
