package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.ScriptController;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.SimulationService;
import com.roleplay.engine.simulation.map.MapContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0803-K（剧本杀模式多地图切换）验收测试：
 * <ul>
 *   <li>K1：对局层多图注册表 —— init 自动图注册为 map_1 并设为当前；显式 map_id 注册多图；
 *       每图独立尺寸；toMap/status 暴露 current_map_id + map_ids</li>
 *   <li>K2：door zone 触发切换 —— addDoorZone 布门 → switchMap（door_zone_id + 靠近坐标）→
 *       当前图切换 + 响应携带目标图（zone target 字段解析）</li>
 *   <li>K3：靠近校验（远离拒绝）+ 直切模式（无 door_zone_id 显式 target）+ body target 覆盖 zone target</li>
 *   <li>K4：非法 door 目标容错 —— door 不存在 / 非 door 型 / 无目标 / 目标=当前图 / 非本局玩家 /
 *       缺玩家 / 阶段不符（投票后禁切）/ 缺 door_zone_id 且缺 target</li>
 *   <li>K5：切换后状态迁移 —— 线索/AP/秘密对局级保留；足迹按图隔离（切走暂存、切回恢复）</li>
 *   <li>K6：尺寸联动 —— 24×16 ↔ 64×64 切换后 mapWidth/mapHeight 随目标图更新</li>
 *   <li>K7：未知目标自动生成（door 携带可选 width/height → 按尺寸 BSP 生成）+ controller
 *       POST /api/script/map/switch 与 /map/door 端点透传容错</li>
 * </ul>
 * 快照持久化（K8）见 ScriptMapSwitchPersistenceTest（Spring 上下文）。
 */
class ScriptMapSwitchTest {

    /** LLM mock：剧本走 2 参 4000（ScriptService 现行档位），地图走 3 参 4000+45 → 空输出 → BSP 兜底（确定性）。 */
    private LLMClient bspLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房", "花园"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false),
            Map.of("id", "c2", "location", "书房", "content", "密信", "public", false),
            Map.of("id", "c3", "location", "花园", "content", "脚印", "public", false)));
        script.put("secrets", Map.of("管家", "贪图遗产", "女仆", "知道密信", "园丁", "目击真凶"));
        when(llm.callJson(anyString(), eq(4000))).thenReturn(script);
        when(llm.callJson(anyString(), eq(4000), anyInt())).thenReturn(Map.of());
        return llm;
    }

    // ═══════════════════════════════════════════════════════════
    //  K1: 对局层多图注册表
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("K1: 多图注册表 —— init 自动图注册 map_1 设为当前；显式/自动 map_id 注册多图；每图独立尺寸")
    void multiMapRegistry() {
        ScriptGameService svc = new ScriptGameService(bspLlm(), new ApprovalService());
        String sid = "switch-k1";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        // init 自动图 = map_1（BSP 默认 24×16）
        assertEquals("map_1", svc.getCurrentMapId(sid));
        assertEquals(List.of("map_1"), svc.getRegisteredMapIds(sid));
        assertEquals(40, svc.getGame(sid).mapWidth);
        assertEquals(24, svc.getGame(sid).mapHeight);
        // 第二张图（显式 map_id + 大图 64×64 → BSP）
        Map<String, Object> r2 = svc.generateMap(sid, "地下室", 0, true, 64, 64, "map_2");
        assertEquals("map_2", svc.getCurrentMapId(sid));
        assertEquals(64, svc.getGame(sid).mapWidth);
        assertEquals(64, ((Map<?, ?>) r2.get("map")).get("width"));
        // 第三张图（缺省 map_id → 自动 map_3，48×48）
        Map<String, Object> r3 = svc.generateMap(sid, "花园迷宫", 0, true, 48, 48, "");
        assertEquals("map_3", svc.getCurrentMapId(sid));
        assertEquals(48, ((Map<?, ?>) r3.get("map")).get("width"));
        // 注册表 3 图、尺寸各自独立（多图各自带独立尺寸）
        assertEquals(List.of("map_1", "map_2", "map_3"), svc.getRegisteredMapIds(sid));
        assertEquals(40, ((Map<?, ?>) svc.getGame(sid).maps.get("map_1")).get("width"));
        assertEquals(64, ((Map<?, ?>) svc.getGame(sid).maps.get("map_2")).get("width"));
        assertEquals(48, ((Map<?, ?>) svc.getGame(sid).maps.get("map_3")).get("width"));
        // toMap 暴露 current_map_id + map_ids（附加键不破坏既有契约）
        Map<String, Object> st = svc.getGame(sid).toMap("Alice");
        assertEquals("map_3", st.get("current_map_id"));
        assertEquals(List.of("map_1", "map_2", "map_3"), st.get("map_ids"));
    }

    // ═══════════════════════════════════════════════════════════
    //  K2: door zone 触发切换（happy path）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("K2: door zone 触发切换 —— 布门 + 靠近 → 切到目标图（zone target 字段解析）")
    void doorSwitchHappyPath() {
        ScriptGameService svc = new ScriptGameService(bspLlm(), new ApprovalService());
        String sid = "switch-k2";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.generateMap(sid, "地下室", 0, true, 64, 64, "map_2");
        // 在 map_2 布门 → map_1（x/y=-1 → 自动吸附最近可通行格）
        Map<String, Object> door = svc.addDoorZone(sid, "map_2", "door_north", "北门", -1, -1, 1, "map_1");
        assertEquals(Boolean.TRUE, door.get("ok"));
        Map<?, ?> zone = (Map<?, ?>) door.get("zone");
        int dx = MapContract.intOf(zone.get("x"), -1);
        int dy = MapContract.intOf(zone.get("y"), -1);
        assertTrue(dx >= 0 && dy >= 0, "door 必须吸附到可通行格");
        // Alice 靠近 door → 触发切图（body 无 target → 从 zone.target 解析 map_1）
        Map<String, Object> sw = svc.switchMap(sid, "Alice", "", "door_north", dx, dy, "");
        assertEquals(Boolean.TRUE, sw.get("switched"));
        assertEquals("map_2", sw.get("from_map_id"));
        assertEquals("map_1", sw.get("to_map_id"));
        assertEquals("map_1", svc.getCurrentMapId(sid));
        assertEquals(40, ((Map<?, ?>) sw.get("map")).get("width"));
        assertEquals("map_1", ((Map<?, ?>) sw.get("map")).get("map_id"), "目标图 map_id 归一为注册表键");
        // door zone 已落到注册表数据（切回可用）
        List<?> zones = (List<?>) svc.getGame(sid).maps.get("map_2").get("zones");
        assertTrue(zones.stream().anyMatch(z -> "door_north".equals(((Map<?, ?>) z).get("id"))
                && "door".equals(((Map<?, ?>) z).get("type"))), "door zone 持久于注册表");
    }

    // ═══════════════════════════════════════════════════════════
    //  K3: 靠近校验 + 直切模式 + target 覆盖
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("K3: 靠近校验（远离拒绝）+ 直切模式（无 door_zone_id）+ body target 覆盖 zone target")
    void proximityAndDirectSwitch() {
        ScriptGameService svc = new ScriptGameService(bspLlm(), new ApprovalService());
        String sid = "switch-k3";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.generateMap(sid, "地下室", 0, true, 64, 64, "map_2");
        Map<String, Object> door = svc.addDoorZone(sid, "map_2", "door_east", "东门", -1, -1, 1, "map_1");
        Map<?, ?> zone = (Map<?, ?>) door.get("zone");
        int dx = MapContract.intOf(zone.get("x"), -1);
        int dy = MapContract.intOf(zone.get("y"), -1);
        // 远离 door（坐标差 > radius+2）→ 拒绝
        Map<String, Object> far = svc.switchMap(sid, "Alice", "", "door_east", dx + 10, dy + 10, "");
        assertTrue(String.valueOf(far.get("error")).contains("未靠近"), "远离 door 必须拒绝: " + far);
        assertEquals("map_2", svc.getCurrentMapId(sid), "拒绝后当前图不变");
        // 直切模式（无 door_zone_id，显式 target_map_id）→ 成功
        Map<String, Object> direct = svc.switchMap(sid, "Alice", "", null, null, null, "map_1");
        assertEquals(Boolean.TRUE, direct.get("switched"));
        assertEquals("map_1", svc.getCurrentMapId(sid));
        // 回 map_2（直切）→ body target 覆盖 zone target：door 指向 map_1，但 body 显式 map_new → 自动生成 map_new
        assertEquals(Boolean.TRUE, svc.switchMap(sid, "Bob", "", null, null, null, "map_2").get("switched"));
        Map<String, Object> override = svc.switchMap(sid, "Alice", "", "door_east", dx, dy, "map_new");
        assertEquals(Boolean.TRUE, override.get("switched"));
        assertEquals("map_new", override.get("to_map_id"));
        assertTrue(svc.getRegisteredMapIds(sid).contains("map_new"), "未注册目标自动生成并注册");
    }

    // ═══════════════════════════════════════════════════════════
    //  K4: 非法 door 目标容错
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("K4: 非法 door 目标容错 —— 不存在/非 door 型/无目标/目标=当前图/非本局玩家/缺玩家/阶段不符/双缺")
    void errorTolerance() {
        ScriptGameService svc = new ScriptGameService(bspLlm(), new ApprovalService());
        String sid = "switch-k4";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        // a) door 不存在
        assertTrue(String.valueOf(svc.switchMap(sid, "Alice", "", "no_such_door", null, null, "").get("error"))
                .contains("door zone 不存在"));
        // b) 非 door 型 zone（取当前图任一 search zone）→ 不能触发切图
        Map<?, ?> searchZone = firstSearchZone(svc.getGame(sid).mapData);
        assertTrue(String.valueOf(svc.switchMap(sid, "Alice", "", String.valueOf(searchZone.get("id")), null, null, "map_2").get("error"))
                .contains("不是 door 类型"));
        // c) 无目标 door（手工注入缺 target 字段的 door zone）→ 容错
        addRawZone(svc.getGame(sid).mapData, rawDoor("door_no_target"));
        assertTrue(String.valueOf(svc.switchMap(sid, "Alice", "", "door_no_target", null, null, "").get("error"))
                .contains("未配置目标地图"));
        // d) 目标 = 当前地图 → 拒绝
        assertTrue(String.valueOf(svc.switchMap(sid, "Alice", "", null, null, null, "map_1").get("error"))
                .contains("目标地图就是当前地图"));
        // e) 非本局玩家
        assertTrue(String.valueOf(svc.switchMap(sid, "Eve", "", null, null, null, "map_2").get("error"))
                .contains("玩家不在本局中"));
        // f) 缺触发玩家名
        assertTrue(String.valueOf(svc.switchMap(sid, "", "", null, null, null, "map_2").get("error"))
                .contains("缺少触发玩家名"));
        // h) door_zone_id 与 target_map_id 双缺（须在搜证阶段测——阶段守卫先于参数校验，
        //    投票后命中「当前阶段不能切换地图」，双缺分支不可达）
        assertTrue(String.valueOf(svc.switchMap(sid, "Alice", "", "", null, null, "").get("error"))
                .contains("至少其一"));
        // g) 阶段不符（进投票后禁切图）
        svc.startVoting(sid);
        assertTrue(String.valueOf(svc.switchMap(sid, "Alice", "", null, null, null, "map_2").get("error"))
                .contains("当前阶段不能切换地图"));
    }

    // ═══════════════════════════════════════════════════════════
    //  K5: 切换后状态迁移
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("K5: 切换后状态迁移 —— 线索/AP/秘密对局级保留；足迹按图隔离（切走暂存、切回恢复）")
    void stateMigrationAcrossSwitch() {
        ScriptGameService svc = new ScriptGameService(bspLlm(), new ApprovalService());
        String sid = "switch-k5";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(sid);
        // map_1：Alice 搜证 客厅
        Map<String, Object> sr = svc.search(sid, "Alice", "客厅");
        assertFalse(String.valueOf(sr.get("result")).contains("没有更多"), "搜证应成功: " + sr.get("result"));
        assertTrue(game.searchedLocations.contains("客厅"));
        int aliceAp = game.playerAp.get("Alice");
        // map_2：Bob 搜证 花园（当前图足迹切到 map_2 后应为空）
        svc.generateMap(sid, "地下室", 0, true, 64, 64, "map_2");
        assertTrue(game.searchedLocations.isEmpty(), "切到无足迹的 map_2 后当前足迹为空");
        svc.search(sid, "Bob", "花园");
        assertTrue(game.searchedLocations.contains("花园"));
        // 对局级状态跨图保留：线索 / AP / 秘密
        assertTrue(game.playerClues.get("Alice").contains("c1"), "线索跨图保留");
        assertEquals(aliceAp, game.playerAp.get("Alice"), "AP 跨图保留");
        assertFalse(game.getSecretFor("Alice").isBlank(), "秘密跨图保留");
        // 切回 map_1 → 客厅足迹恢复（花园足迹留在地图_2）
        Map<String, Object> sw = svc.switchMap(sid, "Alice", "", null, null, null, "map_1");
        assertEquals(Boolean.TRUE, sw.get("switched"));
        assertTrue(game.searchedLocations.contains("客厅"));
        assertFalse(game.searchedLocations.contains("花园"));
        // 再切 map_2 → 花园足迹恢复
        svc.switchMap(sid, "Bob", "", null, null, null, "map_2");
        assertTrue(game.searchedLocations.contains("花园"));
        assertFalse(game.searchedLocations.contains("客厅"));
        // 搜证联动：map_2 上搜过的地点不污染 map_1（searchedByMap 隔离）
        assertEquals(1, game.searchedByMap.get("map_1").size());
        assertEquals(1, game.searchedByMap.get("map_2").size());
    }

    // ═══════════════════════════════════════════════════════════
    //  K6: 尺寸联动
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("K6: 尺寸联动 —— 24×16 ↔ 64×64 切换后 mapWidth/mapHeight 随目标图更新")
    void sizeLinkage() {
        ScriptGameService svc = new ScriptGameService(bspLlm(), new ApprovalService());
        String sid = "switch-k6";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        svc.generateMap(sid, "", 0, true, 64, 64, "map_2");
        assertEquals(64, svc.getGame(sid).mapWidth);
        assertEquals(64, svc.getGame(sid).mapHeight);
        svc.switchMap(sid, "Alice", "", null, null, null, "map_1");
        assertEquals(40, svc.getGame(sid).mapWidth);
        assertEquals(24, svc.getGame(sid).mapHeight);
        svc.switchMap(sid, "Alice", "", null, null, null, "map_2");
        assertEquals(64, svc.getGame(sid).mapWidth);
        assertEquals(64, svc.getGame(sid).mapHeight);
    }

    // ═══════════════════════════════════════════════════════════
    //  K7: 未知目标自动生成（door 携带尺寸）+ controller 端点
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("K7: 未知目标自动生成（door 可选 width/height → BSP 按尺寸生成）+ controller 端点透传容错")
    void controllerAndAutoGen() throws Exception {
        ScriptGameService svc = new ScriptGameService(bspLlm(), new ApprovalService());
        String sid = "switch-k7";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptController controller = new ScriptController(svc, null, mock(SimulationService.class));
        setCurrentSession(controller, sid);
        // 布门（缺省当前图 map_1）→ door 携带可选 width/height → 目标 map_new 未注册 → 自动生成
        Map<String, Object> door = svc.addDoorZone(sid, "", "door_out", "后门", -1, -1, 1, "map_new");
        assertEquals(Boolean.TRUE, door.get("ok"));
        assertEquals("map_1", door.get("map_id"));
        ((Map<String, Object>) door.get("zone")).put("width", 48);
        ((Map<String, Object>) door.get("zone")).put("height", 48);
        ResponseEntity<Map<String, Object>> resp = controller.mapSwitch(Map.of(
                "session_id", sid, "player", "Alice", "door_zone_id", "door_out"));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.TRUE, resp.getBody().get("switched"));
        assertEquals("map_new", resp.getBody().get("to_map_id"));
        assertEquals(48, ((Map<?, ?>) resp.getBody().get("map")).get("width"), "自动生成目标图按 door 尺寸");
        assertTrue(svc.getRegisteredMapIds(sid).contains("map_new"));
        // door 端点缺 target_map_id → 容错
        ResponseEntity<Map<String, Object>> doorErr = controller.mapDoor(Map.of("session_id", sid, "zone_id", "d2"));
        assertTrue(String.valueOf(doorErr.getBody().get("error")).contains("缺少目标地图"));
        // switch 缺 session_id → 容错（controller 遵循 P-0803-H2 先例：body 缺省时回退 currentSessionId；
        // 仅当显式空串/当前对局也为空时才命中「缺少 session_id」守卫分支）
        ResponseEntity<Map<String, Object>> noSid = controller.mapSwitch(Map.of("player", "Alice", "session_id", ""));
        assertEquals("缺少 session_id", noSid.getBody().get("error"));
        // switch 缺 player → 容错
        ResponseEntity<Map<String, Object>> noPlayer = controller.mapSwitch(Map.of("session_id", sid));
        assertTrue(String.valueOf(noPlayer.getBody().get("error")).contains("缺少触发玩家名"));
    }

    // ═══════════════════════════════════════════════════════════

    /** 取地图数据中第一个 type=search 的 zone（K4b 非 door 型容错用）。 */
    private static Map<?, ?> firstSearchZone(Map<String, Object> mapData) {
        Object zonesObj = mapData.get("zones");
        assertTrue(zonesObj instanceof List<?>, "map 必须携带 zones");
        for (Object o : (List<?>) zonesObj) {
            Map<?, ?> z = (Map<?, ?>) o;
            if ("search".equals(String.valueOf(z.get("type")))) return z;
        }
        fail("map 必须至少有一个 search zone");
        return null;
    }

    /** 向地图数据追加一个 zone（K4c 无目标 door 容错用；原地更新注册表实例）。 */
    @SuppressWarnings("unchecked")
    private static void addRawZone(Map<String, Object> mapData, Map<String, Object> zone) {
        List<Map<String, Object>> zones = new ArrayList<>();
        if (mapData.get("zones") instanceof List<?> zl) {
            for (Object o : zl) {
                if (o instanceof Map<?, ?> z) zones.add(new LinkedHashMap<>((Map<String, Object>) z));
            }
        }
        zones.add(zone);
        mapData.put("zones", zones);
    }

    /** 构造无 target 字段的 door zone（容错路径）。 */
    private static Map<String, Object> rawDoor(String id) {
        Map<String, Object> z = new LinkedHashMap<>();
        z.put("id", id);
        z.put("name", id);
        z.put("type", "door");
        z.put("x", 1);
        z.put("y", 1);
        z.put("radius", 1);
        return z;
    }

    /** 反射设置 controller.currentSessionId（private 字段，与 ScriptMapSizeExpansionTest 同款）。 */
    private static void setCurrentSession(ScriptController controller, String sid) throws Exception {
        Field f = ScriptController.class.getDeclaredField("currentSessionId");
        f.setAccessible(true);
        f.set(controller, sid);
    }
}
