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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 改造方案《玩家角色改名与 AI 识别》Phase 3 验收测试（P-0802-P3）：剧本杀改名后重连快照恢复。
 *
 * <p>场景（方案 §4.2.4 快照兼容 + §7 旧存档兼容）：玩家改名后重连对局 ——
 * <ul>
 *   <li>① 改名端点同步后（内存对局已换新名）→ resumeGame 内存命中直接返回新名视图；</li>
 *   <li>② 重启后（内存对局丢失）→ 快照重建：新快照（改名后落库）players 已含新名；</li>
 *   <li>③ 旧快照（改名前落库，players 含旧名）+ player_id_bindings → 按绑定重映射恢复到新名
 *       （解决“改完名再重连”场景）；</li>
 *   <li>④ 无绑定旧快照 → 回退旧名逻辑（零行为变化，兼容老数据）。</li>
 * </ul>
 *
 * <p>@SpringBootTest + @MockBean LLMClient（ScriptPersistenceTest 同模式）：真实 DatabaseService（H2 mem）
 * 验证快照落库/恢复全链路。各用例使用独立 sessionId/名字前缀，互不依赖。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ScriptRenameResumeTest {

    @Autowired
    private ScriptGameService svc;

    @Autowired
    private PlayerIdentityService identityService;

    @Autowired
    private DatabaseService databaseService;

    @MockBean
    private LLMClient llmClient;

    private void mockLlm() {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false),
            Map.of("id", "c2", "location", "书房", "content", "密信", "public", true)));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道秘密", "园丁", "你看到了凶手"));
        when(llmClient.callJson(anyString(), anyInt())).thenReturn(script);
    }

    /** 建局：players=[oldName, 其他]，登记 playerId 绑定（可选，登记须在 initGame 前——初始快照即携带绑定），返回 roleKey。 */
    private String initGameWithBinding(String sid, String oldName, String pid) {
        mockLlm();
        if (pid != null && !pid.isBlank()) {
            svc.registerPlayerBinding(sid, pid, oldName);
        }
        svc.initGame(sid, "庄园疑云", List.of(oldName, "阿强"));
        return svc.getRoleKey(sid, oldName);
    }

    /** 模拟重启：新 service 实例（内存对局为空，走快照重建）。 */
    private ScriptGameService freshInstance() {
        return new ScriptGameService(llmClient, new ApprovalService(),
                databaseService, null, null, identityService);
    }

    // ── ① 内存对局改名后 resume → 直接返回新名视图（内存命中，restored=false） ──

    @Test
    @DisplayName("① 改名端点同步后 resume：内存命中直接返回新名视图（restored=false）")
    void resumeAfterRename_memoryHit_returnsNewName() {
        String sid = "resume-1-" + System.nanoTime();
        String oldName = "r1小明";
        String pid = "pid-r1";
        String key = initGameWithBinding(sid, oldName, pid);
        assertTrue(svc.getGame(sid).players.contains(oldName), "开局应含旧名");

        // 改名（编排同步：内存对局迁移 + 绑定更新 + 快照落库）
        svc.renamePlayer(sid, oldName, "r1大明");

        Map<String, Object> view = svc.resumeGame(sid, key);
        assertEquals(Boolean.FALSE, view.get("restored"), "内存命中 restored=false");
        assertEquals("r1大明", view.get("player"), "内存命中应返回新名玩家");
        assertTrue(((List<?>) view.get("players")).contains("r1大明"), "players 应含新名");
        assertFalse(((List<?>) view.get("players")).contains(oldName), "players 不应含旧名");
    }

    // ── ② 重启后（内存丢失）resume → 快照重建：改名后落库的快照 players 已含新名 ──

    @Test
    @DisplayName("② 重启后 resume：快照重建，改名后快照 players 已含新名")
    void resumeAfterRestart_snapshotHasNewName() {
        String sid = "resume-2-" + System.nanoTime();
        String oldName = "r2小明";
        String pid = "pid-r2";
        String key = initGameWithBinding(sid, oldName, pid);

        // 改名 → renamePlayer 内 saveSnapshot 落库（players 已换新名）
        svc.renamePlayer(sid, oldName, "r2大明");

        // 模拟重启：新实例 resume
        ScriptGameService fresh = freshInstance();
        Map<String, Object> view = fresh.resumeGame(sid, key);
        assertEquals(Boolean.TRUE, view.get("restored"), "重启后应从快照重建 restored=true");
        assertEquals("r2大明", view.get("player"), "快照重建后应恢复新名玩家");
        assertTrue(((List<?>) view.get("players")).contains("r2大明"), "players 应含新名");
        // 新实例 checkPlayerAccess 按新名+roleKey 通过（键迁移随快照恢复）
        ScriptGameService.ScriptGame restored = fresh.getGame(sid);
        assertNotNull(restored, "重建后对局应入 games 缓存");
        assertNull(fresh.checkPlayerAccess(sid, "r2大明", key), "重建后 checkPlayerAccess 新名应通过");
    }

    // ── ③ 旧快照（改名前落库）+ player_id_bindings → 按绑定重映射恢复到新名 ──

    @Test
    @DisplayName("③ 旧快照含旧名 + 绑定 → 恢复时按绑定重映射到新名（改完名再重连）")
    void restoreOldSnapshot_remapsByBinding() {
        String sid = "resume-3-" + System.nanoTime();
        String oldName = "r3小明";
        String pid = "pid-r3";
        // initGame 落初始快照（players 含旧名 + 绑定 {pid → 旧名}）
        String key = initGameWithBinding(sid, oldName, pid);
        // 确认初始快照已落库（players 旧名）
        assertTrue(svc.getGame(sid).players.contains(oldName), "内存对局应含旧名");

        // 角色库改名（模拟 Phase 3 端点对角色库的改名：绑定解析到新名）
        // 注：此处不调 renamePlayer（模拟“改名后未同步/服务端在改名与重连之间重启”），
        // 角色库直接落新名绑定 —— restoreFromSnapshot 经 identityService.resolveCharacterName(pid)
        // 解析到新名 → 按绑定把快照内旧名重映射到新名
        databaseService.saveCharacter("r3大明", "开朗", "正常", "背景", pid);

        // 模拟重启：新实例 resume → restoreFromSnapshot 读旧快照（players 旧名 + 绑定）
        ScriptGameService fresh = freshInstance();
        Map<String, Object> view = fresh.resumeGame(sid, key);
        assertEquals(Boolean.TRUE, view.get("restored"), "重启后应从快照重建");
        // 按绑定重映射：快照内旧名 → 绑定解析出的新名
        assertEquals("r3大明", view.get("player"), "旧快照应按绑定重映射到新名");
        assertTrue(((List<?>) view.get("players")).contains("r3大明"), "重映射后 players 应含新名");
        assertFalse(((List<?>) view.get("players")).contains(oldName), "重映射后 players 不应含旧名");
        // roleKey 随键迁移 → 新名认证通过
        ScriptGameService.ScriptGame restored = fresh.getGame(sid);
        assertNull(fresh.checkPlayerAccess(sid, "r3大明", key), "重映射后 checkPlayerAccess 新名应通过");
        assertNotNull(restored.playerKeys.get("r3大明"), "playerKeys 键应随重映射换新名");
    }

    // ── ④ 无绑定旧快照 → 回退旧名逻辑（零行为变化，兼容老数据） ──

    @Test
    @DisplayName("④ 无绑定旧快照恢复 → 回退旧名逻辑（players 保持快照名，零变化）")
    void restoreLegacySnapshotWithoutBinding_fallsBackToOldName() {
        String sid = "resume-4-" + System.nanoTime();
        String oldName = "r4小明";
        String key = initGameWithBinding(sid, oldName, null); // 无绑定

        // 模拟重启
        ScriptGameService fresh = freshInstance();
        Map<String, Object> view = fresh.resumeGame(sid, key);
        assertEquals(Boolean.TRUE, view.get("restored"), "重启后应从快照重建");
        assertEquals(oldName, view.get("player"), "无绑定应回退快照旧名（零变化）");
        assertTrue(((List<?>) view.get("players")).contains(oldName), "players 应保持快照旧名");
    }
}
