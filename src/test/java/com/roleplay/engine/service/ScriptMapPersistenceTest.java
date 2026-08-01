package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.db.service.DatabaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 阶段 2 验收测试（Spring 上下文）：map_data 快照持久化 + 重启恢复（M6）。
 *
 * <p>@SpringBootTest + @MockBean LLMClient（与 ScriptPersistenceTest 同模式）：剧本生成走
 * callJson(…, 600)、地图生成走 callJson(…, 800)，按 maxTokens 区分 stub，互不串扰。
 * 验证：generateMap 落快照（type=snapshot 含 map_data）→ 新 service 实例 resumeGame 从快照
 * 重建对局 → toMap 仍携带地图（重启后地图不丢）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ScriptMapPersistenceTest {

    private static final String SESSION = "test-script-map-persist";

    @Autowired
    private ScriptGameService svc;

    @Autowired
    private DatabaseService databaseService;

    @MockBean
    private LLMClient llmClient;

    /** 剧本 mock（callJson maxTokens=600 路径）。 */
    private void mockScriptLlm() {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房", "花园"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false),
            Map.of("id", "c2", "location", "书房", "content", "密信", "public", false),
            Map.of("id", "c3", "location", "花园", "content", "脚印", "public", true)));
        script.put("secrets", Map.of("管家", "贪图遗产", "女仆", "知道密信", "园丁", "目击真凶"));
        when(llmClient.callJson(anyString(), eq(600))).thenReturn(script);
    }

    /** 地图 mock（callJson maxTokens=800 路径）。 */
    private void mockMapLlm() {
        List<List<Integer>> ground = new ArrayList<>();
        List<List<Integer>> collision = new ArrayList<>();
        for (int y = 0; y < 8; y++) {
            List<Integer> g = new ArrayList<>();
            List<Integer> c = new ArrayList<>();
            for (int x = 0; x < 10; x++) {
                boolean wall = x == 0 || y == 0 || x == 9 || y == 7;
                g.add(wall ? 2 : 1);
                c.add(wall ? 1 : 0);
            }
            ground.add(g);
            collision.add(c);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("map_version", 1);
        m.put("map_id", "llm_manor");
        m.put("name", "老宅");
        m.put("theme", "民国老宅");
        m.put("tile_size", 32);
        m.put("width", 10);
        m.put("height", 8);
        m.put("layers", Map.of("ground", ground, "collision", collision));
        m.put("rooms", List.of(
            Map.of("id", "living", "name", "客厅", "x", 1, "y", 1, "w", 4, "h", 2),
            Map.of("id", "study", "name", "书房", "x", 5, "y", 1, "w", 4, "h", 2)));
        m.put("zones", List.of(
            Map.of("id", "z1", "name", "客厅八仙桌", "type", "search", "x", 2, "y", 1, "radius", 1, "clue_location", "客厅"),
            Map.of("id", "z2", "name", "书房书架", "type", "search", "x", 6, "y", 1, "radius", 1, "clue_location", "书房")));
        m.put("spawn_points", List.of(
            Map.of("id", "sp1", "type", "player", "x", 2, "y", 2),
            Map.of("id", "sp2", "type", "npc", "x", 6, "y", 2)));
        m.put("generator", Map.of("kind", "llm", "model", "mock"));
        when(llmClient.callJson(anyString(), eq(800))).thenReturn(m);
    }

    @Test
    @DisplayName("M6: map_data 快照持久化并可恢复（重启后地图不丢）")
    void snapshotRestoresMap() {
        mockScriptLlm();
        mockMapLlm();

        String sid = SESSION + "-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        Map<String, Object> r = svc.generateMap(sid, "", 0, false);
        assertEquals(Boolean.FALSE, r.get("cached"));
        assertNotNull(r.get("map"));
        assertEquals("llm", ((Map<?, ?>) r.get("generator")).get("kind"));
        // 取一个真实 roleKey（resumeGame 需 player_key 认证）
        String key = svc.getGame(sid).playerKeys.values().iterator().next();

        // 从快照重建（模拟重启：新实例不经过 initGame）
        ScriptGameService fresh = new ScriptGameService(llmClient, new ApprovalService(),
                databaseService, null, null);
        Map<String, Object> restored = fresh.resumeGame(sid, key);
        assertNotNull(restored.get("map"), "快照恢复后地图可用");
        Map<?, ?> map = (Map<?, ?>) restored.get("map");
        assertEquals("llm", ((Map<?, ?>) map.get("generator")).get("kind"));
        assertEquals(2, ((List<?>) map.get("zones")).size());
        // 阶段/玩家也一并恢复
        assertEquals("investigation", restored.get("phase"));
        assertEquals(3, ((List<?>) restored.get("players")).size());
    }

    @Test
    @DisplayName("M6b: 无地图快照的旧对局恢复 → toMap 无 map 键（向前兼容）")
    void restoreLegacySnapshotWithoutMap() {
        mockScriptLlm();
        String sid = SESSION + "-legacy-" + System.nanoTime();
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        String key = svc.getGame(sid).playerKeys.values().iterator().next();

        ScriptGameService fresh = new ScriptGameService(llmClient, new ApprovalService(),
                databaseService, null, null);
        Map<String, Object> restored = fresh.resumeGame(sid, key);
        assertNull(restored.get("map"), "旧快照无 map_data → 不出现 map 键");
        assertEquals("investigation", restored.get("phase"));
    }
}
