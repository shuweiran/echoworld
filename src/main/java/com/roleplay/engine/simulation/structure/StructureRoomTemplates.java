package com.roleplay.engine.simulation.structure;

import com.roleplay.engine.simulation.map.MapContract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L2 房间模板配方（P-0817-N，docs/结构树契约与生成API设计.md §5）——模板键 → 房间内容。
 *
 * <p>移植自模板原型（templates-proto.json 同源 template_proto.py）：每种房间类型定义
 * base_floor（基础地面）+ pattern（地面图案：top/bottom/border/center）+ furniture 配方
 * （锚定/固定/随机）+ 搜证锚点（note 放在指定家具旁）。全部由 L1 布局器确定性放置
 * （种子驱动），保证「每个剧本不同风格不同线索、房屋模板不出错」。
 *
 * <p>家具为渲染层装饰（契约 decor[]：无碰撞语义，P-0814-F 决策延续）；搜证锚点 note
 * 供 zones[].clue_location 绑定与前端「线索点」视觉。
 */
public final class StructureRoomTemplates {

    /** 家具占地 [w, h]（渲染层色块左上角定位；与原型 FURNITURE 表一致）。 */
    public static final Map<String, int[]> FURNITURE_SIZES = Map.ofEntries(
            Map.entry("counter", new int[]{1, 1}),
            Map.entry("counter_4", new int[]{4, 1}),
            Map.entry("stool", new int[]{1, 1}),
            Map.entry("table_round", new int[]{1, 1}),
            Map.entry("table_rect", new int[]{2, 1}),
            Map.entry("chair", new int[]{1, 1}),
            Map.entry("bookshelf", new int[]{2, 1}),
            Map.entry("sofa", new int[]{2, 1}),
            Map.entry("bed", new int[]{2, 1}),
            Map.entry("desk", new int[]{2, 1}),
            Map.entry("stove", new int[]{1, 1}),
            Map.entry("sink", new int[]{1, 1}),
            Map.entry("cabinet", new int[]{1, 1}),
            Map.entry("shelf", new int[]{2, 1}),
            Map.entry("chest", new int[]{1, 1}),
            Map.entry("note", new int[]{1, 1}),
            Map.entry("lamp", new int[]{1, 1}),
            Map.entry("plant", new int[]{1, 1}),
            Map.entry("pillar", new int[]{1, 1}),
            Map.entry("tree", new int[]{1, 1}),
            Map.entry("flower_bed", new int[]{2, 1}),
            Map.entry("bench", new int[]{2, 1}),
            Map.entry("fountain", new int[]{2, 2}),
            Map.entry("rock", new int[]{1, 1}),
            Map.entry("wood_stack", new int[]{1, 1}),
            Map.entry("rug", new int[]{3, 2}),
            Map.entry("window", new int[]{1, 1}),
            Map.entry("screen", new int[]{2, 1}),
            Map.entry("tea_table", new int[]{2, 1}),
            Map.entry("wardrobe", new int[]{2, 1}),
            Map.entry("dressing_table", new int[]{1, 1}),
            Map.entry("incense", new int[]{1, 1}),
            Map.entry("scroll", new int[]{1, 1}),
            Map.entry("hay", new int[]{1, 1}),
            Map.entry("cart", new int[]{2, 1}));

    /**
     * 挡路家具集合（P-0817-O 真正挡路的家具）：占用格写入 collision=1 + tileProps{blocked:true}，
     * 玩家/AI 移动与寻路自动绕开。对齐模板原型 blocked=True 清单；
     * 不挡路：note（搜证便条）/ flower_bed / rug / window / incense / scroll。
     */
    public static final Set<String> BLOCKED_FURNITURE = Set.of(
            "counter", "counter_4", "stool", "table_round", "table_rect", "chair",
            "bookshelf", "sofa", "bed", "desk", "stove", "sink", "cabinet", "shelf",
            "chest", "lamp", "plant", "pillar", "tree", "bench", "fountain", "rock",
            "wood_stack", "screen", "tea_table", "wardrobe", "dressing_table", "hay", "cart");

    /** 家具是否挡路（占用格碰撞=1）。 */
    public static boolean isBlocked(String type) {
        return type != null && BLOCKED_FURNITURE.contains(type);
    }

    /** 地面图案（rows：top:n / bottom:n / border:1 / center）。 */
    public record Pattern(String rows, int tile) {
    }

    /** 家具条目：锚定（anchor+offset）/ 固定（atX,atY）/ 随机（count）/ 搜证锚点（noteOn+slot）。 */
    public record Furniture(String type, String anchor, int offX, int offY, int atX, int atY,
                            int count, String noteOn, int noteSlot) {
        static Furniture anchor(String type, String anchor, int ox, int oy) {
            return new Furniture(type, anchor, ox, oy, -1, -1, 0, null, 0);
        }

        static Furniture at(String type, int x, int y) {
            return new Furniture(type, "", 0, 0, x, y, 0, null, 0);
        }

        static Furniture random(String type, int count) {
            return new Furniture(type, "", 0, 0, -1, -1, count, null, 0);
        }

        static Furniture note(String on, int slot) {
            return new Furniture("note", "", 0, 0, -1, -1, 0, on, slot);
        }

        boolean isNote() {
            return "note".equals(type);
        }
    }

    /** 房间配方：baseFloor + patterns + furniture。 */
    public record Recipe(int baseFloor, List<Pattern> patterns, List<Furniture> furniture) {
        /** 搜证锚点目标家具类型（无 note 配方 → null，zone 落房间中心）。 */
        String noteTarget() {
            for (Furniture f : furniture) {
                if (f.isNote()) return f.noteOn();
            }
            return null;
        }
    }

    private static final Map<String, Recipe> RECIPES = new LinkedHashMap<>();

    static {
        // ── 城堡 ──
        RECIPES.put("gatehouse", simple(MapContract.TILE_STONE,
                Furniture.anchor("chest", "left", 0, 1),
                Furniture.anchor("wood_stack", "left", 0, 3),
                Furniture.anchor("lamp", "right", -1, 1),
                Furniture.anchor("shelf", "top", 3, 0),
                Furniture.note("chest", 0)));
        RECIPES.put("courtyard", simple(MapContract.TILE_GRASS,
                Furniture.anchor("flower_bed", "top", 2, 0),
                Furniture.anchor("flower_bed", "top", 6, 0),
                Furniture.anchor("tree", "left", 0, 2),
                Furniture.anchor("tree", "right", -1, 2),
                Furniture.anchor("bench", "bottom", 2, -1),
                Furniture.anchor("lamp", "bottom", 7, -1),
                Furniture.random("rock", 2),
                Furniture.note("flower_bed", 0)));
        RECIPES.put("great_hall", recipe(MapContract.TILE_FLOOR, List.of(new Pattern("border:1", MapContract.TILE_CARPET)),
                Furniture.anchor("table_rect", "center", -1, -1),
                Furniture.anchor("table_rect", "center", 1, -1),
                Furniture.anchor("chair", "center", 0, -2),
                Furniture.anchor("chair", "center", 0, 1),
                Furniture.anchor("screen", "top", 2, 0),
                Furniture.anchor("screen", "top", 6, 0),
                Furniture.anchor("pillar", "left", 0, 2),
                Furniture.anchor("pillar", "right", -1, 2),
                Furniture.anchor("lamp", "top", 8, 0),
                Furniture.note("table_rect", 0)));
        RECIPES.put("banquet", recipe(MapContract.TILE_FLOOR, List.of(new Pattern("top:1", MapContract.TILE_STONE)),
                Furniture.at("table_rect", 1, 1),
                Furniture.at("table_rect", 3, 1),
                Furniture.at("table_rect", 5, 1),
                Furniture.at("chair", 1, 2),
                Furniture.at("chair", 3, 2),
                Furniture.at("chair", 5, 2),
                Furniture.at("chair", 1, 0),
                Furniture.at("chair", 3, 0),
                Furniture.at("chair", 5, 0),
                Furniture.anchor("sofa", "right", -1, 2),
                Furniture.anchor("lamp", "left", 0, 1),
                Furniture.note("table_rect", 0)));
        RECIPES.put("armory", simple(MapContract.TILE_STONE,
                Furniture.anchor("shelf", "top", 1, 0),
                Furniture.anchor("shelf", "top", 4, 0),
                Furniture.anchor("chest", "bottom", 1, -1),
                Furniture.anchor("wood_stack", "bottom", 4, -1),
                Furniture.anchor("lamp", "right", -1, 1),
                Furniture.note("chest", 0)));
        // ── 古风 ──
        RECIPES.put("gu_parlor", recipe(MapContract.TILE_FLOOR, List.of(new Pattern("border:1", MapContract.TILE_CARPET)),
                Furniture.anchor("tea_table", "center", -1, 0),
                Furniture.anchor("chair", "center", 0, -1),
                Furniture.anchor("chair", "center", 0, 2),
                Furniture.anchor("screen", "top", 2, 0),
                Furniture.anchor("scroll", "top", 6, 0),
                Furniture.anchor("incense", "center", 2, 0),
                Furniture.anchor("pillar", "left", 0, 1),
                Furniture.anchor("pillar", "right", -1, 1),
                Furniture.note("tea_table", 0)));
        RECIPES.put("gu_study", simple(MapContract.TILE_FLOOR,
                Furniture.anchor("desk", "top", 3, 0),
                Furniture.anchor("chair", "top", 4, 1),
                Furniture.anchor("bookshelf", "right", -1, 0),
                Furniture.anchor("bookshelf", "right", -1, 2),
                Furniture.anchor("bed", "bottom", 1, -1),
                Furniture.anchor("lamp", "left", 0, 1),
                Furniture.anchor("scroll", "top", 6, 0),
                Furniture.anchor("plant", "bottom", 5, -1),
                Furniture.note("desk", 0)));
        RECIPES.put("gu_bedroom", recipe(MapContract.TILE_FLOOR, List.of(new Pattern("center", MapContract.TILE_CARPET)),
                Furniture.anchor("bed", "top", 2, 0),
                Furniture.anchor("wardrobe", "right", -1, 0),
                Furniture.anchor("dressing_table", "bottom", 2, -1),
                Furniture.anchor("window", "left", 0, 2),
                Furniture.anchor("lamp", "top", 7, 0),
                Furniture.note("dressing_table", 0)));
        // ── 通用厨房/储藏/花园（庄园/街区/地牢复用） ──
        RECIPES.put("kitchen", simple(MapContract.TILE_STONE,
                Furniture.anchor("stove", "top", 1, 0),
                Furniture.anchor("sink", "top", 3, 0),
                Furniture.anchor("counter", "top", 5, 0),
                Furniture.anchor("counter", "top", 6, 0),
                Furniture.at("table_rect", 1, 2),
                Furniture.anchor("cabinet", "bottom", 1, 0),
                Furniture.anchor("cabinet", "bottom", 3, 0),
                Furniture.anchor("shelf", "right", -1, 0),
                Furniture.note("sink", 0)));
        RECIPES.put("storage", simple(MapContract.TILE_STONE,
                Furniture.anchor("shelf", "top", 1, 0),
                Furniture.anchor("shelf", "top", 4, 0),
                Furniture.anchor("shelf", "top", 7, 0),
                Furniture.anchor("chest", "bottom", 1, 0),
                Furniture.anchor("chest", "bottom", 4, 0),
                Furniture.anchor("wood_stack", "bottom", 7, 0),
                Furniture.anchor("lamp", "left", 0, 2),
                Furniture.note("chest", 0)));
        RECIPES.put("garden", simple(MapContract.TILE_GRASS,
                Furniture.anchor("fountain", "center", -1, -1),
                Furniture.anchor("tree", "left", 0, 0),
                Furniture.anchor("tree", "left", 0, 2),
                Furniture.anchor("tree", "right", -1, 0),
                Furniture.anchor("flower_bed", "top", 1, 0),
                Furniture.anchor("flower_bed", "top", 4, 0),
                Furniture.anchor("bench", "bottom", 1, -1),
                Furniture.random("rock", 2),
                Furniture.anchor("lamp", "bottom", 5, -1),
                Furniture.note("flower_bed", 0)));
        // ── 庄园 ──
        RECIPES.put("foyer", simple(MapContract.TILE_FLOOR,
                Furniture.at("table_round", 3, 3),
                Furniture.anchor("lamp", "left", 0, 1),
                Furniture.anchor("plant", "right", -1, 1),
                Furniture.anchor("scroll", "top", 3, 0),
                Furniture.note("table_round", 0)));
        RECIPES.put("living", recipe(MapContract.TILE_FLOOR, List.of(new Pattern("border:1", MapContract.TILE_CARPET)),
                Furniture.anchor("sofa", "bottom", 1, -1),
                Furniture.anchor("tea_table", "center", -1, 0),
                Furniture.anchor("chair", "center", 0, 2),
                Furniture.anchor("bookshelf", "right", -1, 0),
                Furniture.anchor("lamp", "left", 0, 1),
                Furniture.anchor("plant", "right", -1, 2),
                Furniture.note("tea_table", 0)));
        RECIPES.put("study", simple(MapContract.TILE_FLOOR,
                Furniture.anchor("desk", "top", 2, 0),
                Furniture.anchor("chair", "top", 3, 1),
                Furniture.anchor("bookshelf", "right", -1, 0),
                Furniture.anchor("lamp", "left", 0, 1),
                Furniture.anchor("scroll", "top", 5, 0),
                Furniture.note("desk", 0)));
        RECIPES.put("dining", simple(MapContract.TILE_FLOOR,
                Furniture.at("table_rect", 4, 3),
                Furniture.at("chair", 4, 4),
                Furniture.at("chair", 4, 2),
                Furniture.at("chair", 3, 3),
                Furniture.at("chair", 5, 3),
                Furniture.anchor("cabinet", "right", -1, 0),
                Furniture.anchor("lamp", "left", 0, 1),
                Furniture.note("table_rect", 0)));
        RECIPES.put("servant", simple(MapContract.TILE_STONE,
                Furniture.anchor("bed", "left", 0, 0),
                Furniture.anchor("chest", "right", -1, 0),
                Furniture.anchor("lamp", "top", 3, 0),
                Furniture.note("chest", 0)));
        // ── 城市街区 ──
        RECIPES.put("street", simple(MapContract.TILE_GRASS,
                Furniture.anchor("tree", "left", 0, 0),
                Furniture.anchor("tree", "right", -1, 0),
                Furniture.anchor("bench", "bottom", 2, -1),
                Furniture.anchor("lamp", "bottom", 7, -1),
                Furniture.anchor("flower_bed", "top", 3, 0),
                Furniture.random("rock", 2),
                Furniture.note("flower_bed", 0)));
        RECIPES.put("plaza", simple(MapContract.TILE_GRASS,
                Furniture.anchor("fountain", "center", -1, -1),
                Furniture.anchor("bench", "bottom", 1, -1),
                Furniture.anchor("bench", "bottom", 5, -1),
                Furniture.anchor("tree", "left", 0, 1),
                Furniture.anchor("tree", "right", -1, 1),
                Furniture.anchor("lamp", "top", 3, 0),
                Furniture.note("fountain", 0)));
        RECIPES.put("shop", simple(MapContract.TILE_FLOOR,
                Furniture.anchor("counter_4", "top", 1, 0),
                Furniture.anchor("counter_4", "top", 6, 0),
                Furniture.anchor("stool", "top", 1, 1),
                Furniture.anchor("stool", "top", 3, 1),
                Furniture.anchor("stool", "top", 6, 1),
                Furniture.anchor("stool", "top", 8, 1),
                Furniture.anchor("shelf", "right", -1, 0),
                Furniture.anchor("lamp", "top", 9, 1),
                Furniture.note("counter_4", 0)));
        RECIPES.put("house", recipe(MapContract.TILE_FLOOR, List.of(new Pattern("border:1", MapContract.TILE_CARPET)),
                Furniture.anchor("sofa", "bottom", 1, -1),
                Furniture.anchor("tea_table", "center", -1, 0),
                Furniture.anchor("chair", "center", 0, 1),
                Furniture.anchor("bookshelf", "right", -1, 0),
                Furniture.anchor("lamp", "left", 0, 1),
                Furniture.anchor("plant", "right", -1, 2),
                Furniture.note("tea_table", 0)));
        RECIPES.put("warehouse", simple(MapContract.TILE_STONE,
                Furniture.anchor("shelf", "top", 1, 0),
                Furniture.anchor("shelf", "top", 4, 0),
                Furniture.anchor("shelf", "top", 7, 0),
                Furniture.anchor("chest", "bottom", 1, -1),
                Furniture.anchor("chest", "bottom", 4, -1),
                Furniture.anchor("wood_stack", "bottom", 7, -1),
                Furniture.anchor("lamp", "left", 0, 2),
                Furniture.note("chest", 0)));
        // ── 地牢 ──
        RECIPES.put("entrance", simple(MapContract.TILE_STONE,
                Furniture.anchor("pillar", "left", 0, 1),
                Furniture.anchor("pillar", "right", -1, 1),
                Furniture.anchor("lamp", "top", 3, 0),
                Furniture.anchor("chest", "bottom", 3, -1),
                Furniture.random("rock", 2),
                Furniture.note("chest", 0)));
        RECIPES.put("dungeon_hall", simple(MapContract.TILE_STONE,
                Furniture.anchor("table_rect", "center", -1, 0),
                Furniture.anchor("chair", "center", 0, -1),
                Furniture.anchor("chair", "center", 0, 2),
                Furniture.anchor("pillar", "left", 0, 1),
                Furniture.anchor("pillar", "right", -1, 1),
                Furniture.anchor("lamp", "top", 5, 0),
                Furniture.note("table_rect", 0)));
        RECIPES.put("dungeon_cell", simple(MapContract.TILE_STONE,
                Furniture.anchor("bed", "left", 0, 0),
                Furniture.anchor("hay", "right", -1, 0),
                Furniture.anchor("chest", "top", 2, 0),
                Furniture.note("chest", 0)));
        RECIPES.put("treasury", simple(MapContract.TILE_STONE,
                Furniture.anchor("chest", "top", 1, 0),
                Furniture.anchor("chest", "top", 4, 0),
                Furniture.anchor("chest", "bottom", 1, -1),
                Furniture.anchor("scroll", "top", 6, 0),
                Furniture.anchor("lamp", "center", 2, 1),
                Furniture.note("chest", 0)));
        RECIPES.put("boss_room", recipe(MapContract.TILE_FLOOR, List.of(new Pattern("border:1", MapContract.TILE_STONE)),
                Furniture.anchor("tea_table", "center", -1, 0),
                Furniture.anchor("chair", "center", 0, -1),
                Furniture.anchor("chair", "center", 0, 2),
                Furniture.anchor("screen", "top", 2, 0),
                Furniture.anchor("screen", "top", 6, 0),
                Furniture.anchor("pillar", "left", 0, 1),
                Furniture.anchor("pillar", "right", -1, 1),
                Furniture.anchor("lamp", "top", 8, 0),
                Furniture.note("tea_table", 0)));
    }

    private StructureRoomTemplates() {
    }

    /** 取房间配方（未知模板键 → 通用配方：桌 + 椅 + 灯 + 桌上便条）。 */
    public static Recipe recipe(String templateKey) {
        Recipe r = RECIPES.get(templateKey == null ? "" : templateKey.trim());
        return r != null ? r : DEFAULT_RECIPE;
    }

    private static final Recipe DEFAULT_RECIPE = simple(MapContract.TILE_FLOOR,
            Furniture.anchor("table_rect", "center", -1, 0),
            Furniture.anchor("chair", "center", 0, 1),
            Furniture.anchor("lamp", "top", 2, 0),
            Furniture.note("table_rect", 0));

    /** 是否为已知专属配方（非通用默认）。 */
    public static boolean hasRecipe(String templateKey) {
        return RECIPES.containsKey(templateKey == null ? "" : templateKey.trim());
    }

    /** 通用默认配方（未知模板键兜底）。 */
    public static Recipe defaultRecipe() {
        return DEFAULT_RECIPE;
    }

    private static Recipe simple(int floor, Furniture... fs) {
        return recipe(floor, List.of(), fs);
    }

    private static Recipe recipe(int floor, List<Pattern> patterns, Furniture... fs) {
        return new Recipe(floor, patterns, List.of(fs));
    }
}
