package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.ScriptController;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次 C3 验收测试：剧本杀断线重连与会话恢复 —— roleKey 认证 + 对局快照恢复（对齐 Chronos：
 * roleKey 顶号一体 / 内存 Room 仅缓存 / 状态变更写持久化 / 崩溃后 restore() 重建）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>C3-1：roleKey 生成 —— 每玩家唯一、非空；toMap 仅向本人暴露 role_key（他人/匿名视图不含）</li>
 *   <li>C3-2：player_key 校验 —— 匹配通过 / 错误 key 拒绝 / 空 key 向后兼容</li>
 *   <li>C3-3：内存对局 resume —— 直接返回（restored=false）</li>
 *   <li>C3-4：快照恢复 —— 模拟清内存后 resume 重建，phase/AP/线索/投票与清内存前一致</li>
 *   <li>C3-5：恢复视图脱敏 —— 只能看到自己的 secret 与持有的线索</li>
 *   <li>C3-6：ENDED 对局 resume —— 返回终态（terminal/murderer/correct/truth）</li>
 *   <li>C3-7：resume 拒绝 —— 未知对局 / 错误 player_key</li>
 *   <li>C3-8：controller 层 —— init 绑定 room_code → resume 按房间码定位；status/search 带 player_key 认证（403 拒绝/200 通过）</li>
 * </ul>
 *
 * <p>@SpringBootTest + H2 mem + 真实 DatabaseService（快照真实落库可恢复）+ @MockBean LLMClient，
 * 与 ScriptPersistenceTest 同模式。每个测试用独立 sessionId（避免快照按 session 前缀串扰）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class ScriptGameResumeTest {

    @Autowired
    private ScriptGameService svc;

    @Autowired
    private ApprovalService approval;

    @MockBean
    private LLMClient llmClient;

    /** 旧格式剧本：3 角色 3 秘密；c1（客厅，非公开）/ c2（书房，公开）；truth 不含任何 secret 文案（脱敏断言安全）。 */
    private void mockLlm() {
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false, "related_role", "管家"),
            Map.of("id", "c2", "location", "书房", "content", "密信", "public", true, "related_role", "")));
        script.put("secrets", Map.of("管家", "秘闻_管家贪图遗产", "女仆", "秘闻_女仆知道密信", "园丁", "秘闻_园丁目击真凶"));
        when(llmClient.callJson(anyString(), anyInt())).thenReturn(script);
    }

    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    /** 反射清空内存对局表 —— 模拟“重启后内存丢失”（权威状态在快照）。 */
    @SuppressWarnings("unchecked")
    private void clearGamesMemory() throws Exception {
        Field f = ScriptGameService.class.getDeclaredField("games");
        f.setAccessible(true);
        Map<String, ?> games = (Map<String, ?>) f.get(svc);
        games.clear();
    }

    private boolean viewValuesContain(Map<String, Object> view, String needle) {
        return view.values().stream().anyMatch(v -> v != null && v.toString().contains(needle));
    }

    // ═══════════════════════════════════════════════════════════
    //  C3-1/C3-2: roleKey 生成与校验
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C3-1: roleKey 生成 —— 每玩家唯一非空；toMap 仅向本人暴露 role_key")
    void roleKeysGeneratedUniqueAndExposed() {
        mockLlm();
        String sid = "r-c31";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(sid);

        // 全员都有 key 且互不相同
        assertEquals(3, game.playerKeys.size(), "每玩家一个 roleKey");
        assertTrue(game.playerKeys.values().stream().distinct().count() == 3, "三个 key 互不相同");
        game.playerKeys.values().forEach(k -> assertFalse(k.isBlank(), "roleKey 非空"));

        // toMap 只向本人暴露自己的 role_key
        assertEquals(game.playerKeys.get("Alice"), game.toMap("Alice").get("role_key"), "本人可见自己的 key");
        assertEquals(game.playerKeys.get("Bob"), game.toMap("Bob").get("role_key"), "Bob 可见自己的 key");
        assertFalse(game.toMap("Bob").get("role_key").equals(game.playerKeys.get("Alice")), "不能看到别人的 key");
        // 匿名视图（广播用）不含 role_key
        assertFalse(game.toMap("").containsKey("role_key"), "匿名视图不暴露任何 role_key");
    }

    @Test
    @DisplayName("C3-2: player_key 校验 —— 匹配通过 / 错误 key 拒绝 / 空 key 向后兼容")
    void playerKeyValidation() {
        mockLlm();
        String sid = "r-c32";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        String aliceKey = svc.getRoleKey(sid, "Alice");

        // 正确 key 通过（checkPlayerAccess 返回 null；isPlayerKeyValid true）
        assertNull(svc.checkPlayerAccess(sid, "Alice", aliceKey), "正确 key 校验通过");
        assertTrue(svc.isPlayerKeyValid(sid, "Alice", aliceKey));

        // 错误 key 拒绝（错误码 403 的载荷）
        Map<String, Object> denied = svc.checkPlayerAccess(sid, "Alice", "wrong-key");
        assertNotNull(denied, "错误 key 拒绝");
        assertTrue(denied.get("error").toString().contains("身份校验失败"), "明确提示校验失败");
        assertFalse(svc.isPlayerKeyValid(sid, "Alice", "wrong-key"));

        // 跨玩家 key 也拒绝（Bob 的 key 不能冒充 Alice）
        assertNotNull(svc.checkPlayerAccess(sid, "Alice", svc.getRoleKey(sid, "Bob")), "跨玩家 key 拒绝");

        // 空 key 向后兼容：仍按玩家名放行（现状不变）
        assertNull(svc.checkPlayerAccess(sid, "Alice", ""), "无 key 兼容放行");
        assertNull(svc.checkPlayerAccess(sid, "Alice", null), "null key 兼容放行");
    }

    // ═══════════════════════════════════════════════════════════
    //  C3-3/C3-4: resume —— 内存命中 / 快照恢复
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C3-3: 内存对局 resume —— 直接返回该玩家视图（restored=false）")
    void resumeInMemoryReturnsWithoutRestore() {
        mockLlm();
        String sid = "r-c33";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        String aliceKey = svc.getRoleKey(sid, "Alice");

        Map<String, Object> view = svc.resumeGame(sid, aliceKey);
        assertEquals(Boolean.FALSE, view.get("restored"), "内存对局不重建");
        assertEquals(Boolean.TRUE, view.get("resumed"));
        assertEquals("Alice", view.get("player"));
        assertEquals("investigation", view.get("phase"));
        assertEquals(aliceKey, view.get("role_key"), "恢复视图带本人 role_key（重连凭证）");
    }

    @Test
    @DisplayName("C3-4: 快照恢复 —— 模拟清内存后 resume，phase/AP/线索/投票与清内存前一致")
    void resumeRestoresSnapshotAfterMemoryClear() throws Exception {
        mockLlm();
        String sid = "r-c34";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        String aliceKey = svc.getRoleKey(sid, "Alice");

        // 搜证：Alice 客厅得 c1，AP 3→2
        svc.search(sid, "Alice", "客厅");
        // 进投票并投两票
        svc.startVoting(sid);
        svc.castVote(sid, "Alice", "Bob");
        svc.castVote(sid, "Bob", "Alice");

        // 清内存（模拟重启）→ 从快照恢复
        clearGamesMemory();
        assertNull(svc.getGame(sid), "内存对局已清空");
        Map<String, Object> view = svc.resumeGame(sid, aliceKey);

        // 恢复完整：阶段 / 玩家 / AP / 线索
        assertEquals(Boolean.TRUE, view.get("restored"), "从快照重建");
        assertEquals("vote", view.get("phase"), "阶段恢复为 VOTE");
        assertEquals("Alice", view.get("player"));
        assertEquals(2, view.get("ap"), "AP 扣减恢复（3→2）");
        assertEquals(3, view.get("ap_max"), "AP 上限恢复");

        // 线索持有恢复：Alice 的 my_clues 含 c1
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> myClues = (List<Map<String, Object>>) view.get("my_clues");
        assertTrue(myClues.stream().anyMatch(c -> "c1".equals(c.get("id"))), "Alice 持有的 c1 恢复");
        assertTrue(view.get("clues").toString().contains("c1"), "线索列表恢复");

        // 投票恢复（toMap 按既有契约不含 votes，断言恢复后的 game 对象）
        ScriptGameService.ScriptGame restored = svc.getGame(sid);
        assertEquals(ScriptGameService.Phase.VOTE, restored.phase);
        assertEquals(2, restored.votes.size(), "票型恢复");
        assertEquals("Bob", restored.votes.get("Alice"), "Alice 的票恢复");
        assertEquals("Alice", restored.votes.get("Bob"), "Bob 的票恢复");
        assertEquals(2, restored.playerAp.get("Alice"), "恢复后的 AP 状态与内存一致");
        assertTrue(restored.playerClues.getOrDefault("Alice", List.of()).contains("c1"), "恢复后的线索归属一致");

        // 恢复后的对局可继续操作：再补一票成功（不越界）
        String cast = svc.castVote(sid, "Carol", "Alice");
        assertTrue(cast.contains("投票给了"), "恢复后的对局可继续投票");
    }

    @Test
    @DisplayName("C3-5: 恢复视图脱敏 —— 只能看到自己的 secret 与持有的线索")
    void resumeViewIsDesensitized() throws Exception {
        mockLlm();
        String sid = "r-c35";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(sid);
        String butlerPlayer = playerWithRole(game, "管家");
        String maidPlayer = playerWithRole(game, "女仆");
        String gardenerPlayer = playerWithRole(game, "园丁");
        // 先取各玩家 key（清内存后 games 为空，getRoleKey 取不到）
        String butlerKey = svc.getRoleKey(sid, butlerPlayer);
        String maidKey = svc.getRoleKey(sid, maidPlayer);
        String gardenerKey = svc.getRoleKey(sid, gardenerPlayer);

        // 管家搜证得 c1（非公开线索）
        svc.search(sid, butlerPlayer, "客厅");
        // 清内存 → 恢复
        clearGamesMemory();
        Map<String, Object> view = svc.resumeGame(sid, butlerKey);

        // 只见自己的秘密
        assertEquals("秘闻_管家贪图遗产", view.get("your_secret"), "管家只见自己的秘密");
        assertFalse(viewValuesContain(view, "秘闻_女仆知道密信"), "不泄露女仆秘密");
        assertFalse(viewValuesContain(view, "秘闻_园丁目击真凶"), "不泄露园丁秘密");

        // 非公开线索只对持有者可见：管家视图含 c1
        assertTrue(view.get("clues").toString().contains("c1"), "持有者可见自己搜到的非公开线索");
        // 女仆恢复视图不含 c1（非公开、未持有）
        Map<String, Object> maidView = svc.resumeGame(sid, maidKey);
        assertEquals("秘闻_女仆知道密信", maidView.get("your_secret"), "女仆只见自己的秘密");
        assertFalse(maidView.get("clues").toString().contains("c1"), "未持有者不可见非公开线索");
        // 公开线索 c2 全员可见
        assertTrue(maidView.get("clues").toString().contains("c2"), "公开线索全员可见");
        // 顺带验证园丁 key 也有效
        assertEquals("秘闻_园丁目击真凶", svc.resumeGame(sid, gardenerKey).get("your_secret"));
    }

    // ═══════════════════════════════════════════════════════════
    //  C3-6/C3-7: ENDED 终态恢复 / 拒绝路径
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C3-6: ENDED 对局 resume —— 返回终态（terminal/murderer/correct/truth）")
    void resumeEndedReturnsTerminalState() throws Exception {
        mockLlm();
        String sid = "r-c36";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(sid);
        String murderer = playerWithRole(game, "管家");
        List<String> others = game.players.stream().filter(p -> !p.equals(murderer)).toList();
        String otherKey = svc.getRoleKey(sid, others.get(0));

        svc.startVoting(sid);
        svc.castVote(sid, others.get(0), murderer);
        svc.castVote(sid, others.get(1), murderer);
        svc.castVote(sid, murderer, others.get(0));

        // 审批门 → 后台揭晓 + 批准（与 ScriptPersistenceTest 同模式）
        CompletableFuture<Map<String, Object>> fut = CompletableFuture.supplyAsync(() -> svc.resolveVote(sid));
        Thread.sleep(150);
        assertTrue(approval.approve(sid));
        Map<String, Object> res = fut.get(5, TimeUnit.SECONDS);
        assertEquals(Boolean.TRUE, res.get("correct"));
        svc.confirmEnded(sid);
        assertEquals(ScriptGameService.Phase.ENDED, svc.getGame(sid).phase);

        // 内存路径：ENDED 直接返回终态
        Map<String, Object> memView = svc.resumeGame(sid, otherKey);
        assertEquals(Boolean.TRUE, memView.get("terminal"), "内存 ENDED 视图标记终态");
        assertEquals("ended", memView.get("phase"));
        assertEquals(murderer, memView.get("murderer"), "终态含真凶");
        assertEquals(Boolean.TRUE, memView.get("correct"), "终态含判定");
        assertTrue(memView.get("truth").toString().contains("管家"), "终态含真相");

        // 快照路径：清内存后从终态快照恢复，同样返回终态
        clearGamesMemory();
        Map<String, Object> restoredView = svc.resumeGame(sid, otherKey);
        assertEquals(Boolean.TRUE, restoredView.get("restored"), "从终态快照重建");
        assertEquals(Boolean.TRUE, restoredView.get("terminal"), "恢复后仍标记终态");
        assertEquals("ended", restoredView.get("phase"));
        assertEquals(murderer, restoredView.get("murderer"), "恢复出真凶");
        assertEquals(Boolean.TRUE, restoredView.get("correct"), "恢复出判定");
        assertTrue(restoredView.get("truth").toString().contains("管家"), "恢复出真相");
    }

    @Test
    @DisplayName("C3-7: resume 拒绝 —— 未知对局 / 错误 player_key / 缺少对局标识")
    void resumeRejectsUnknownOrWrongKey() throws Exception {
        mockLlm();
        String sid = "r-c37";
        svc.initGame(sid, "庄园", List.of("Alice", "Bob", "Carol"));
        clearGamesMemory();

        // 未知对局且无快照
        Map<String, Object> unknown = svc.resumeGame("no-such-session", "any-key");
        assertTrue(unknown.get("error").toString().contains("无快照可恢复"), "未知对局明确报错");

        // 对局存在但 player_key 不属于任何玩家
        Map<String, Object> wrongKey = svc.resumeGame(sid, "not-a-real-key");
        assertTrue(wrongKey.get("error").toString().contains("身份校验失败"), "错误 key 拒绝恢复");

        // 空对局标识
        Map<String, Object> noId = svc.resumeGame("", "key");
        assertTrue(noId.get("error").toString().contains("缺少对局标识"), "空标识报错");
    }

    // ═══════════════════════════════════════════════════════════
    //  C3-8: controller 层 —— room_code 绑定 + player_key 认证
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C3-8: controller 层 —— init 绑定 room_code → resume 按房间码定位；status/search 带 player_key 认证")
    void controllerRoomCodeResumeAndKeyAuth() {
        mockLlm();
        ScriptController ctl = new ScriptController(svc, mock(RouterService.class), mock(SimulationService.class));

        // init 带 room_code → 响应含 room_code + session_id
        // （P-0810-17：controller 默认 outline_only=true 只出概略；本用例需完整局验证
        // 搜证/阶段，显式 outline_only=false 走既有同步完整生成路径）
        ResponseEntity<Map<String, Object>> initResp = ctl.init(new LinkedHashMap<>(Map.of(
            "players", List.of("Alice", "Bob", "Carol"), "theme", "庄园", "room_code", "ABC123",
            "outline_only", false)));
        String sessionId = (String) initResp.getBody().get("session_id");
        assertEquals("ABC123", initResp.getBody().get("room_code"), "init 响应回显房间码");
        assertNotNull(sessionId);
        String aliceKey = svc.getRoleKey(sessionId, "Alice");

        // resume 按 room_code + player_key 定位
        Map<String, Object> resumeResp = ctl.resume(Map.of("room_code", "ABC123", "player_key", aliceKey)).getBody();
        assertEquals("Alice", resumeResp.get("player"), "房间码 + key 恢复出玩家视图");
        assertEquals("investigation", resumeResp.get("phase"));

        // status：错误 key → 403；正确 key → 200 且含 role_key
        ResponseEntity<Map<String, Object>> deniedStatus = ctl.getStatus("Alice", "bad-key");
        assertEquals(403, deniedStatus.getStatusCode().value(), "错误 key 状态查询拒绝");
        assertTrue(deniedStatus.getBody().get("error").toString().contains("身份校验失败"));

        ResponseEntity<Map<String, Object>> okStatus = ctl.getStatus("Alice", aliceKey);
        assertEquals(200, okStatus.getStatusCode().value(), "正确 key 状态查询放行");
        assertEquals(aliceKey, okStatus.getBody().get("role_key"), "状态视图带本人 role_key");

        // search：错误 key → 403；正确 key → 200 正常搜证
        ResponseEntity<Map<String, Object>> deniedSearch = ctl.search(Map.of("player", "Alice", "location", "客厅", "player_key", "bad-key"));
        assertEquals(403, deniedSearch.getStatusCode().value(), "错误 key 搜证拒绝");

        ResponseEntity<Map<String, Object>> okSearch = ctl.search(Map.of("player", "Alice", "location", "客厅", "player_key", aliceKey));
        assertEquals(200, okSearch.getStatusCode().value(), "正确 key 搜证放行");
        assertEquals(List.of("c1"), okSearch.getBody().get("found"), "正确 key 搜证正常出线索");

        // 无 key 向后兼容：仍可搜证（现状不变）
        ResponseEntity<Map<String, Object>> legacySearch = ctl.search(Map.of("player", "Alice", "location", "书房"));
        assertEquals(200, legacySearch.getStatusCode().value(), "无 key 兼容放行");

        // DM keys 端点：返回全员令牌一览（P-0810-17 B3：新增可选 player_key 参数，缺省保持旧行为）
        Map<String, Object> keysResp = ctl.getKeys(sessionId, "").getBody();
        @SuppressWarnings("unchecked")
        Map<String, String> keys = (Map<String, String>) keysResp.get("player_keys");
        assertEquals(3, keys.size(), "DM 面板可见全员令牌");
        assertEquals(aliceKey, keys.get("Alice"));
    }
}
