package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.ScriptController;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.SimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

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
 * P-0814-H 热点/搜证点交互系统 —— 对局级集成 + 快照持久化验收（@SpringBootTest + H2 mem，对齐 ScriptMapSwitchPersistenceTest 模式）：
 * <ul>
 *   <li>S1：decor 实体交互 happy path —— addItem 授予既有线索（playerClues）+ dialog + once 标记 processed + toMap decor_states 暴露</li>
 *   <li>S2：once 幂等 —— 重复交互返回「已处理」不重复授予</li>
 *   <li>S3：半径判定 —— 超半径拒绝（够不着），线索不授予；缺坐标跳过</li>
 *   <li>S4：conditions 门 —— requireFlag 不满足 → failDialog + 零动作；flag 写入后放行 + flag 动作落 decorFlags</li>
 *   <li>S5：tileProps 分发 —— action 查表 + args 透传（dialog / addItem 对象）</li>
 *   <li>S6：state 实例状态合并 —— decorStates 落地 + toMap 可见</li>
 *   <li>S7：校验链 —— 游戏不存在/地图未生成/玩家不在局/装饰不存在/双缺目标</li>
 *   <li>S8：controller POST /api/script/interact 端点透传（含 player_key 403）</li>
 *   <li>S9：快照三层持久化 —— 实例状态/一次性 flag/玩家持有随快照落库，重启恢复后幂等语义跨实例保持</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ScriptGameInteractTest {

    private static final String SESSION = "test-script-interact";

    @Autowired
    private ScriptGameService svc;

    @Autowired
    private DatabaseService databaseService;

    @MockBean
    private LLMClient llmClient;

    /** 剧本 mock（callJson 2 参 4000 路径）；地图 3 参 → 空 → BSP 兜底（init 自动图，随后被测试注入自定义交互图替换）。 */
    private void mockLlm() {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房", "花园"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "title", "碎玻璃", "public", false),
            Map.of("id", "c2", "location", "书房", "content", "密信", "title", "密信", "public", false),
            Map.of("id", "c3", "location", "花园", "content", "脚印", "title", "脚印", "public", false)));
        script.put("secrets", Map.of("管家", "贪图遗产", "女仆", "知道密信", "园丁", "目击真凶"));
        when(llmClient.callJson(anyString(), eq(4000))).thenReturn(script);
        when(llmClient.callJson(anyString(), eq(4000), anyInt())).thenReturn(Map.of());
    }

    /** 构造含交互点的契约地图并注入对局（替换 BSP 自动图；data/mapData 同一对象原地生效）。 */
    @SuppressWarnings("unchecked")
    private static void injectCustomMap(ScriptGameService svc, String sid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map_version", 1);
        m.put("map_id", "map_1");
        m.put("name", "交互测试图");
        m.put("theme", "test");
        m.put("tile_size", 32);
        m.put("width", 10);
        m.put("height", 8);
        m.put("layers", Map.of(
                "ground", List.of(List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1), List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)),
                "collision", List.of(List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0))));
        m.put("rooms", List.of());
        m.put("zones", List.of());
        m.put("spawn_points", List.of());
        // decor 交互物
        Map<String, Object> chest = new LinkedHashMap<>();
        chest.put("id", "chest_1");
        chest.put("type", "chest");
        chest.put("tile", List.of(4, 4));
        chest.put("once", true);
        chest.put("radius", 1);
        chest.put("onInteract", Map.of(
                "dialog", "箱子打开了，里面有一片碎玻璃！",
                "addItem", Map.of("id", "c1", "title", "碎玻璃")));
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", "note_1");
        note.put("type", "note");
        note.put("tile", List.of(2, 2));
        note.put("conditions", Map.of("requireFlag", "key_room", "failDialog", "门锁着…"));
        note.put("onInteract", Map.of("dialog", "便条：凶手是管家。", "flag", "note_read"));
        Map<String, Object> lamp = new LinkedHashMap<>();
        lamp.put("id", "lamp_1");
        lamp.put("type", "lamp");
        lamp.put("tile", List.of(6, 3));
        lamp.put("state", Map.of("lit", false));
        lamp.put("onInteract", Map.of("state", Map.of("lit", true), "sound", "switch"));
        m.put("decor", List.of(chest, note, lamp));
        // tileProps 瓦片动作
        m.put("tileProps", Map.of(
                "8,2", Map.of("action", "dialog", "args", "墙上的画框很沉。"),
                "8,3", Map.of("action", "addItem", "args", Map.of("id", "c2", "title", "密信"))));
        m.put("generator", Map.of("kind", "test"));

        ScriptGameService.ScriptGame game = svc.getGame(sid);
        game.maps.put("map_1", m);
        game.mapData = m;
    }

    // ═══════════════════════════════════════════════════════════
    //  S1/S2: decor happy path + once 幂等
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S1: decor 实体交互 happy path —— addItem 授予既有线索 + dialog + once 标记 + toMap 暴露 decor_states")
    void decorInteractHappyPath() {
        mockLlm();
        String sid = SESSION + "-s1-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        injectCustomMap(svc, sid);

        // Alice 靠近箱子（4,4）→ 交互（站在 3,4 相邻格）
        Map<String, Object> r = svc.interact(sid, "Alice", "", "map_1", "chest_1", "", 3, 4);
        assertEquals(Boolean.TRUE, r.get("handled"));
        assertEquals(Boolean.TRUE, r.get("processed"), "once 交互标记 processed");
        assertEquals(List.of("箱子打开了，里面有一片碎玻璃！"), r.get("dialog"));
        assertEquals(1, ((List<?>) r.get("items")).size());
        assertEquals("c1", ((Map<?, ?>) ((List<?>) r.get("items")).get(0)).get("id"));
        // 线索授予既有机制（playerClues）
        assertTrue(svc.getGame(sid).playerClues.getOrDefault("Alice", List.of()).contains("c1"));
        // 实例状态进对局快照结构（decorStates）
        assertEquals(Boolean.TRUE, svc.getGame(sid).decorStates.get("map_1|chest_1").get("processed"));
        // toMap 附加键暴露（前端已处理态数据源）
        Map<String, Object> st = svc.getGame(sid).toMap("Alice");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) ((Map<?, ?>) st.get("decor_states")).get("map_1|chest_1")).get("processed"));
    }

    @Test
    @DisplayName("S2: once 幂等 —— 重复交互返回「已处理」不重复授予（对齐 searchedLocations 幂等风格）")
    void onceIdempotentAtGameLevel() {
        mockLlm();
        String sid = SESSION + "-s2-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        injectCustomMap(svc, sid);
        svc.interact(sid, "Alice", "", "map_1", "chest_1", "", 3, 4);
        assertEquals(1, svc.getGame(sid).playerClues.getOrDefault("Alice", List.of()).size());
        // 重复交互
        Map<String, Object> again = svc.interact(sid, "Alice", "", "map_1", "chest_1", "", 3, 4);
        assertEquals(Boolean.FALSE, again.get("handled"));
        assertEquals(Boolean.TRUE, again.get("processed"));
        assertTrue(String.valueOf(again.get("result")).contains("已处理过"));
        assertEquals(1, svc.getGame(sid).playerClues.getOrDefault("Alice", List.of()).size(), "不重复授予");
    }

    // ═══════════════════════════════════════════════════════════
    //  S3: 半径判定
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S3: 超半径拒绝（够不着）且不授予；缺坐标跳过校验")
    void radiusReject() {
        mockLlm();
        String sid = SESSION + "-s3-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        injectCustomMap(svc, sid);
        // Alice 在 (9,7) 距箱子 (4,4) 远超半径 1 → 拒绝
        Map<String, Object> far = svc.interact(sid, "Alice", "", "map_1", "chest_1", "", 9, 7);
        assertEquals(Boolean.FALSE, far.get("handled"));
        assertTrue(String.valueOf(far.get("error")).contains("够不着"), String.valueOf(far.get("error")));
        assertTrue(svc.getGame(sid).playerClues.getOrDefault("Alice", List.of()).isEmpty(), "拒绝时线索不授予");
        // 缺坐标 → 跳过靠近校验（尽力而为）
        Map<String, Object> noCoord = svc.interact(sid, "Bob", "", "map_1", "chest_1", "", null, null);
        assertEquals(Boolean.TRUE, noCoord.get("handled"), "缺坐标跳过半径校验");
    }

    // ═══════════════════════════════════════════════════════════
    //  S4: conditions 门
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S4: requireFlag 不满足 → failDialog + 零动作；flag 写入后放行 + flag 动作落 decorFlags")
    void conditionsGate() {
        mockLlm();
        String sid = SESSION + "-s4-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        injectCustomMap(svc, sid);
        // 未满足 → 拦截
        Map<String, Object> blocked = svc.interact(sid, "Alice", "", "map_1", "note_1", "", 2, 2);
        assertEquals(Boolean.FALSE, blocked.get("handled"));
        assertEquals(Boolean.TRUE, blocked.get("blocked"));
        assertEquals(List.of("门锁着…"), blocked.get("dialog"));
        assertFalse(svc.getGame(sid).decorFlags.contains("note_read"), "拦截时不写 flag");
        // 写入 key_room（同批 flag 动作/测试注入）→ 放行
        svc.getGame(sid).decorFlags.add("key_room");
        Map<String, Object> open = svc.interact(sid, "Alice", "", "map_1", "note_1", "", 2, 3);
        assertEquals(Boolean.TRUE, open.get("handled"));
        assertEquals(List.of("便条：凶手是管家。"), open.get("dialog"));
        assertTrue(svc.getGame(sid).decorFlags.contains("note_read"), "flag 动作写一次性标记");
        // toMap 暴露 decor_flags（前端条件展示数据源）
        Map<String, Object> st = svc.getGame(sid).toMap("Alice");
        assertTrue(((List<?>) st.get("decor_flags")).contains("note_read"));
    }

    // ═══════════════════════════════════════════════════════════
    //  S5: tileProps 分发
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S5: tileProps.action 分发 —— dialog args 透传 / addItem 对象授予线索")
    void tilePropsDispatch() {
        mockLlm();
        String sid = SESSION + "-s5-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        injectCustomMap(svc, sid);
        Map<String, Object> d1 = svc.interact(sid, "Alice", "", "map_1", "", "8,2", 8, 3);
        assertEquals(Boolean.TRUE, d1.get("handled"));
        assertEquals(List.of("墙上的画框很沉。"), d1.get("dialog"));
        Map<String, Object> d2 = svc.interact(sid, "Bob", "", "map_1", "", "8,3", 8, 3);
        assertEquals(Boolean.TRUE, d2.get("handled"));
        assertTrue(svc.getGame(sid).playerClues.getOrDefault("Bob", List.of()).contains("c2"));
        // 环境占位
        Map<String, Object> empty = svc.interact(sid, "Carol", "", "map_1", "", "9,7", 9, 7);
        assertEquals(Boolean.FALSE, empty.get("handled"));
        assertTrue(String.valueOf(empty.get("result")).contains("这里没有什么特别的"));
    }

    // ═══════════════════════════════════════════════════════════
    //  S6: state 实例状态合并
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S6: state 动作 —— 实例状态合并（初始 state + 运行时覆盖）落 decorStates")
    void stateMerge() {
        mockLlm();
        String sid = SESSION + "-s6-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        injectCustomMap(svc, sid);
        Map<String, Object> r = svc.interact(sid, "Alice", "", "map_1", "lamp_1", "", 6, 3);
        assertEquals(Boolean.TRUE, r.get("handled"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) r.get("state")).get("lit"), "state 动作合并 lit=true");
        assertEquals(List.of("switch"), r.get("sounds"), "sound 占位返回");
        assertEquals(Boolean.TRUE, svc.getGame(sid).decorStates.get("map_1|lamp_1").get("lit"));
        // 非 once 不标记 processed
        assertFalse(r.containsKey("processed"));
    }

    // ═══════════════════════════════════════════════════════════
    //  S7: 校验链
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S7: 校验链 —— 游戏不存在/地图不存在/玩家不在局/装饰不存在/双缺目标")
    void validationChain() {
        mockLlm();
        String sid = SESSION + "-s7-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        injectCustomMap(svc, sid);
        assertTrue(String.valueOf(svc.interact("no-such-game", "Alice", "", "", "", "", 3, 4).get("error")).contains("游戏不存在"));
        assertTrue(String.valueOf(svc.interact(sid, "Alice", "", "map_99", "chest_1", "", 3, 4).get("error")).contains("地图不存在"));
        assertTrue(String.valueOf(svc.interact(sid, "Ghost", "", "map_1", "chest_1", "", 3, 4).get("error")).contains("玩家不在本局"));
        assertTrue(String.valueOf(svc.interact(sid, "Alice", "", "map_1", "nope", "", 3, 4).get("error")).contains("decor 不存在"));
        assertTrue(String.valueOf(svc.interact(sid, "Alice", "", "map_1", "", "", 3, 4).get("error")).contains("缺少交互目标"));
    }

    // ═══════════════════════════════════════════════════════════
    //  S8: controller 端点
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S8: POST /api/script/interact 端点透传（含 player_key 403 与缺 player 容错）")
    void controllerEndpoint() throws Exception {
        mockLlm();
        String sid = SESSION + "-s8-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        injectCustomMap(svc, sid);
        ScriptController controller = new ScriptController(svc, null, mock(SimulationService.class));
        setCurrentSession(controller, sid);
        // happy path
        ResponseEntity<Map<String, Object>> resp = controller.interact(Map.of(
                "session_id", sid, "player", "Alice", "decor_id", "chest_1", "x", "3", "y", "4"));
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(Boolean.TRUE, resp.getBody().get("handled"));
        assertEquals("chest_1", resp.getBody().get("decor_id"));
        // tile 坐标路径
        ResponseEntity<Map<String, Object>> tile = controller.interact(Map.of(
                "session_id", sid, "player", "Bob", "tile", "8,2", "x", "8", "y", "3"));
        assertEquals(Boolean.TRUE, tile.getBody().get("handled"));
        // 缺 player → 服务层容错
        ResponseEntity<Map<String, Object>> noPlayer = controller.interact(Map.of("session_id", sid, "decor_id", "chest_1"));
        assertTrue(String.valueOf(noPlayer.getBody().get("error")).contains("缺少玩家名"));
        // 非法 player_key → 403（C3 身份认证）
        ResponseEntity<Map<String, Object>> badKey = controller.interact(Map.of(
                "session_id", sid, "player", "Alice", "player_key", "wrong", "decor_id", "chest_1", "x", "3", "y", "4"));
        assertEquals(403, badKey.getStatusCode().value());
        assertTrue(String.valueOf(badKey.getBody().get("error")).contains("身份校验失败"));
        // 合法 player_key → 200
        String aliceKey = svc.getGame(sid).playerKeys.get("Alice");
        ResponseEntity<Map<String, Object>> okKey = controller.interact(Map.of(
                "session_id", sid, "player", "Alice", "player_key", aliceKey, "decor_id", "chest_1", "x", "3", "y", "4"));
        assertEquals(200, okKey.getStatusCode().value());
    }

    // ═══════════════════════════════════════════════════════════
    //  S9: 快照三层持久化（实例状态 / 一次性 flag / 玩家持有）
    //  ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("S9: 三层持久化随快照落库 —— 重启恢复后 decorStates/decorFlags/playerClues 完好，once 幂等跨实例保持")
    void snapshotPersistsInteractionState() {
        mockLlm();
        String sid = SESSION + "-s9-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        injectCustomMap(svc, sid);
        // Alice 交互 once 箱子（授予 c1 + processed）；Bob 满足条件后读便条（note_read flag）
        svc.interact(sid, "Alice", "", "map_1", "chest_1", "", 3, 4);
        svc.getGame(sid).decorFlags.add("key_room");
        svc.interact(sid, "Bob", "", "map_1", "note_1", "", 2, 3);
        String aliceKey = svc.getGame(sid).playerKeys.get("Alice");

        // 重启模拟：新实例不经过 initGame，从快照重建
        ScriptGameService fresh = new ScriptGameService(llmClient, new ApprovalService(),
                databaseService, null, null);
        fresh.resumeGame(sid, aliceKey);
        ScriptGameService.ScriptGame fg = fresh.getGame(sid);
        // ① 热点实例状态恢复（once processed）
        assertEquals(Boolean.TRUE, fg.decorStates.get("map_1|chest_1").get("processed"), "实例状态随快照恢复");
        // ② 一次性 flag 恢复
        assertTrue(fg.decorFlags.contains("note_read"), "一次性 flag 随快照恢复");
        // ③ 玩家持有恢复（addItem 授予的线索）
        assertTrue(fg.playerClues.getOrDefault("Alice", List.of()).contains("c1"), "线索持有随快照恢复");
        // 快照恢复的地图数据含交互 decor（注册表随快照落库）
        assertNotNull(fg.maps.get("map_1"), "地图注册表随快照恢复");
        // once 幂等跨实例保持：新实例再交互 → 已处理
        Map<String, Object> again = fresh.interact(sid, "Alice", aliceKey, "map_1", "chest_1", "", 3, 4);
        assertEquals(Boolean.FALSE, again.get("handled"));
        assertEquals(Boolean.TRUE, again.get("processed"));
        assertTrue(String.valueOf(again.get("result")).contains("已处理过"));
        assertEquals(1, fg.playerClues.getOrDefault("Alice", List.of()).size(), "跨实例不重复授予");
    }

    /** 反射设置 controller.currentSessionId（与 ScriptMapSwitchTest 同款）。 */
    private static void setCurrentSession(ScriptController controller, String sid) throws Exception {
        Field f = ScriptController.class.getDeclaredField("currentSessionId");
        f.setAccessible(true);
        f.set(controller, sid);
    }
}
