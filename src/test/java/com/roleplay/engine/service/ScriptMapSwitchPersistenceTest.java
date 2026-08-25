package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * P-0803-K（剧本杀模式多地图切换）快照持久化验收（Spring 上下文，对齐 ScriptMapPersistenceTest 模式）：
 * <ul>
 *   <li>K8：多图注册表 / 当前图 / 每图足迹随快照落库 → 新实例 resumeGame 重启恢复后
 *       多图注册表完好、当前图正确、切图后足迹按图恢复、尺寸联动仍生效</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ScriptMapSwitchPersistenceTest {

    private static final String SESSION = "test-script-map-switch-persist";

    @Autowired
    private ScriptGameService svc;

    @Autowired
    private DatabaseService databaseService;

    @MockBean
    private LLMClient llmClient;

    /** 剧本 mock（callJson 2 参 4000 路径——ScriptService 现行档位）；地图 3 参 4000+45 → 空 → BSP 兜底（确定性）。 */
    private void mockLlm() {
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
        when(llmClient.callJson(anyString(), eq(4000))).thenReturn(script);
        when(llmClient.callJson(anyString(), eq(4000), anyInt())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("K8: 多图注册表/当前图/每图足迹随快照持久化 —— 重启恢复后切图仍可用、足迹按图隔离")
    void snapshotRestoresMultiMap() {
        mockLlm();

        String sid = SESSION + "-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        // map_1（BSP：测试 yml 默认 24×16 经 P-0810-21 下限 clamp → 32×20）：Alice 搜证 客厅
        svc.search(sid, "Alice", "客厅");
        // map_2（BSP 64×64，显式 map_id + 尺寸）：Bob 搜证 花园
        svc.generateMap(sid, "地下室", 0, true, 64, 64, "map_2");
        svc.search(sid, "Bob", "花园");
        // 切回 map_1（当前图足迹 = 客厅）
        assertEquals(Boolean.TRUE, svc.switchMap(sid, "Alice", svc.getRoleKey(sid, "Alice"), null, null, null, "map_1").get("switched"));
        String key = svc.getGame(sid).playerKeys.values().iterator().next();

        // 重启模拟：新实例不经过 initGame，从快照重建
        ScriptGameService fresh = new ScriptGameService(llmClient, new ApprovalService(),
                databaseService, null, null);
        Map<String, Object> restored = fresh.resumeGame(sid, key);
        // 当前图恢复为 map_1（快照时的当前图），注册表两图完好
        assertEquals("map_1", restored.get("current_map_id"));
        assertEquals(Set.of("map_1", "map_2"), new HashSet<>(fresh.getRegisteredMapIds(sid)));
        // P-0810-21（P0-3，B 方案）：测试 yml 同步 min 32×20，map_1 由 24×16 clamp 至 32×20
        assertEquals(32, fresh.getGame(sid).mapWidth);
        assertEquals(20, fresh.getGame(sid).mapHeight);
        // 当前图足迹恢复（map_1 = 客厅；花园留在 map_2）
        assertTrue(((List<?>) restored.get("searched_locations")).contains("客厅"));
        assertFalse(((List<?>) restored.get("searched_locations")).contains("花园"));
        // 恢复后切回 map_2 → 尺寸联动 + 花园足迹按图恢复
        Map<String, Object> sw = fresh.switchMap(sid, "Alice", key, null, null, null, "map_2");
        assertEquals(Boolean.TRUE, sw.get("switched"));
        assertEquals(64, fresh.getGame(sid).mapWidth);
        assertTrue(fresh.getGame(sid).searchedLocations.contains("花园"));
        assertFalse(fresh.getGame(sid).searchedLocations.contains("客厅"));
        // 恢复后 door 触发切换链路仍可用（布门 → 靠近 → 切回 map_1）
        Map<String, Object> door = fresh.addDoorZone(sid, "map_2", "door_back", "回廊门", -1, -1, 1, "map_1");
        assertEquals(Boolean.TRUE, door.get("ok"));
        Map<?, ?> zone = (Map<?, ?>) door.get("zone");
        // 触发者 Bob 必须用本人 roleKey（此前误用 Alice 的 key（line 78 首值）→ 身份校验失败）
        String bobKey = fresh.getGame(sid).playerKeys.get("Bob");
        Map<String, Object> back = fresh.switchMap(sid, "Bob", bobKey, "door_back",
                com.roleplay.engine.simulation.map.MapContract.intOf(zone.get("x"), -1),
                com.roleplay.engine.simulation.map.MapContract.intOf(zone.get("y"), -1), "");
        assertEquals(Boolean.TRUE, back.get("switched"));
        assertEquals("map_1", back.get("to_map_id"));
    }
}
