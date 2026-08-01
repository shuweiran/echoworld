package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批次 C2 验收测试：搜证机制增强 —— AP 行动点 + 线索转交（蓝图 P2 后备，对齐 Chronos CLUE_SEARCH）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>C2-1：AP 初始化 —— 初始 AP = 基础值(3) + 角色 ap_bonus（侦探类角色行动点多）；toMap 暴露 ap/ap_max/ap_pool</li>
 *   <li>C2-2：搜证扣 AP —— 搜索地点消耗线索 ap_cost 之和；公开线索不扣 AP；同地点不重复得</li>
 *   <li>C2-3：AP 不足拒绝 —— 不部分授予、AP 不变、线索不发放</li>
 *   <li>C2-4：转交成功 —— ownership 变更，接收方 status/my_clues 可见转入线索，原持有者不可见</li>
 *   <li>C2-5：转交拒绝 —— 非持有者 / 目标不存在 / 转给自己 / 不可转交(transferable=false) / 阶段守卫</li>
 *   <li>C2-6：schema 兼容 —— 旧剧本无 ap_cost → 默认 1、无 ap_bonus → 默认 0；v1 字段透传</li>
 *   <li>C2-7：端到端 —— 搜证 → AP 变化 → 转交 → status 可见</li>
 * </ul>
 *
 * <p>直接构造（mock LLMClient），与 ScriptGameServiceTest 风格一致；行动点基础值走 @Value 默认 3。
 */
class ScriptGameApTransferTest {

    private static final String SESSION = "test-script-ap";

    /**
     * 旧格式剧本：3 角色无 ap_bonus（默认 0）；线索：
     * c1（客厅，可转交，ap_cost 缺省→1）/ c2（书房，不可转交，ap_cost=2）/ c3（花园，公开线索）/ c4（地下室，不可转交）。
     */
    private LLMClient legacyLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("background", "风雨夜，庄园主人被杀。");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("locations", List.of("客厅", "书房", "花园", "地下室"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false, "transferable", true),
            Map.of("id", "c2", "location", "书房", "content", "密信", "public", false, "transferable", false, "ap_cost", 2),
            Map.of("id", "c3", "location", "花园", "content", "公开脚印", "public", true),
            Map.of("id", "c4", "location", "地下室", "content", "染血手套", "public", false, "transferable", false)));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道秘密", "园丁", "你看到了凶手"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        return llm;
    }

    /** v1 剧本：侦探 ap_bonus=2（初始 AP = 3+2 = 5），管家 ap_bonus=0（初始 3）；线索带 ap_cost/transferable。 */
    private LLMClient v1Llm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", 1);
        script.put("metadata", Map.of("title", "雾都谜案", "player_min", 2, "player_max", 2, "tags", List.of()));
        script.put("background", "雾都命案。");
        script.put("roles", List.of(
            Map.of("id", "role_1", "name", "侦探", "intro", "", "is_hidden", false, "secret", "你在场", "ap_bonus", 2),
            Map.of("id", "role_2", "name", "管家", "intro", "", "is_hidden", false, "secret", "你偷了遗嘱", "ap_bonus", 0)));
        script.put("locations", List.of("书房"));
        script.put("clues", List.of(
            Map.of("id", "clue_1", "title", "碎玻璃", "location", "书房", "content", "地上的碎玻璃", "transferable", true, "visible_to_owner_only", true, "ap_cost", 1)));
        script.put("secrets", Map.of("侦探", "你在场", "管家", "你偷了遗嘱"));
        script.put("killer_id", "role_2");
        script.put("truth", "凶手是管家。");
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        return llm;
    }

    /** 找出扮演指定角色的玩家。 */
    private String playerWithRole(ScriptGameService.ScriptGame game, String role) {
        return game.assignments.entrySet().stream()
            .filter(e -> role.equals(e.getValue()))
            .map(Map.Entry::getKey)
            .findFirst().orElse("");
    }

    /** my_clues 中是否含指定线索 id。 */
    private boolean myCluesContain(Map<String, Object> status, String clueId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> my = (List<Map<String, Object>>) status.get("my_clues");
        return my != null && my.stream().anyMatch(c -> clueId.equals(c.get("id")));
    }

    // ═══════════════════════════════════════════════════════════
    //  C2-1: AP 初始化（基础值 + 角色 ap_bonus）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C2-1: AP 初始化 —— 侦探(ap_bonus=2)初始 5 AP，管家(ap_bonus=0)初始 3 AP；toMap 暴露 ap/ap_max/ap_pool")
    void apInitializedWithRoleBonus() {
        ScriptGameService svc = new ScriptGameService(v1Llm(), new ApprovalService());
        svc.initGame(SESSION, "雾都", List.of("Alice", "Bob"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        String detective = playerWithRole(game, "侦探");
        String butler = playerWithRole(game, "管家");
        assertFalse(detective.isEmpty() || butler.isEmpty(), "两名玩家应分别拿到侦探/管家");

        // 状态层：初始 AP = 基础值 3 + ap_bonus
        assertEquals(5, game.playerAp.get(detective), "侦探：3 + 2 = 5 AP");
        assertEquals(5, game.playerApMax.get(detective));
        assertEquals(3, game.playerAp.get(butler), "管家：3 + 0 = 3 AP");
        assertEquals(3, game.playerApMax.get(butler));

        // toMap 暴露：ap / ap_max / ap_pool（各玩家剩余 AP 一览）
        Map<String, Object> status = game.toMap(detective);
        assertEquals(5, status.get("ap"));
        assertEquals(5, status.get("ap_max"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pool = (Map<String, Object>) status.get("ap_pool");
        assertEquals(5, pool.get(detective));
        assertEquals(3, pool.get(butler));
    }

    @Test
    @DisplayName("C2-1 兼容: 旧剧本无 ap_bonus → 全员初始 AP = 基础值 3")
    void apDefaultWithoutBonus() {
        ScriptGameService svc = new ScriptGameService(legacyLlm(), new ApprovalService());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        for (String p : game.players) {
            assertEquals(3, game.playerAp.get(p), p + " 无 ap_bonus → 3 AP");
            assertEquals(3, game.playerApMax.get(p));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  C2-2/C2-3: 搜证扣 AP / 公开线索免费 / AP 不足拒绝
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C2-2: 搜证扣 AP —— 客厅(c1, 1AP)后 AP 3→2；书房(c2, 2AP)后 2→0；公开线索不扣；同地点不重复得")
    void searchDeductsApAndGrantsClues() {
        ScriptGameService svc = new ScriptGameService(legacyLlm(), new ApprovalService());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));

        // 客厅：c1 ap_cost 缺省 → 1，授予并扣 1 AP
        Map<String, Object> r1 = svc.search(SESSION, "Alice", "客厅");
        assertEquals(List.of("c1"), r1.get("found"));
        assertEquals(2, r1.get("ap"), "搜证后 AP 3→2");
        assertEquals(1, r1.get("ap_cost"));
        assertTrue(r1.get("result").toString().contains("搜证成功"));

        // 书房：c2 ap_cost=2，授予并扣 2 AP → 0
        Map<String, Object> r2 = svc.search(SESSION, "Alice", "书房");
        assertEquals(List.of("c2"), r2.get("found"));
        assertEquals(0, r2.get("ap"), "再扣 2 AP → 0");
        assertEquals(2, r2.get("ap_cost"));

        // 公开线索：c3 不耗 AP、无需搜证（found 为空，AP 不变）
        Map<String, Object> r3 = svc.search(SESSION, "Alice", "花园");
        assertEquals(List.of(), r3.get("found"), "公开线索不进入 found");
        assertEquals(1, ((List<?>) r3.get("public_clues")).size(), "公开线索随响应可见");
        assertEquals(0, r3.get("ap"), "公开线索不扣 AP");
        assertTrue(r3.get("result").toString().contains("没有更多可搜证线索"));

        // 同地点重复搜：已持有不重复得、不扣 AP
        Map<String, Object> r4 = svc.search(SESSION, "Alice", "客厅");
        assertEquals(List.of(), r4.get("found"));
        assertEquals(0, r4.get("ap"));
    }

    @Test
    @DisplayName("C2-3: AP 不足拒绝 —— 需要 1 AP 当前 0 AP：整次拒绝、不部分授予、AP 不变、线索不发放")
    void searchRejectedWhenApInsufficient() {
        ScriptGameService svc = new ScriptGameService(legacyLlm(), new ApprovalService());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));

        svc.search(SESSION, "Alice", "客厅"); // c1，扣 1 → AP 2
        svc.search(SESSION, "Alice", "书房"); // c2，扣 2 → AP 0

        Map<String, Object> r = svc.search(SESSION, "Alice", "地下室"); // c4 需要 1 AP
        assertEquals(List.of(), r.get("found"), "AP 不足不授予任何线索");
        assertEquals(1, r.get("ap_cost"), "提示所需 AP");
        assertEquals(0, r.get("ap"), "AP 不变");
        assertTrue(r.get("result").toString().contains(ScriptGameService.ERR_AP_INSUFFICIENT), "明确提示行动点不足");

        // 线索确实未发放：转交/持有均不可见
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        assertFalse(game.playerClues.getOrDefault("Alice", List.of()).contains("c4"), "c4 不应被授予");
    }

    // ═══════════════════════════════════════════════════════════
    //  C2-4: 转交成功 —— ownership 变更 + status 可见
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C2-4: 转交成功 —— c1 从 Alice 转给 Bob：ownership 变更，Bob my_clues 可见，Alice 不可见")
    void transferSuccessChangesOwnership() {
        ScriptGameService svc = new ScriptGameService(legacyLlm(), new ApprovalService());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        svc.search(SESSION, "Alice", "客厅"); // Alice 持有 c1
        assertTrue(game.playerClues.getOrDefault("Alice", List.of()).contains("c1"));

        Map<String, Object> res = svc.transferClue(SESSION, "Alice", "Bob", "c1");
        assertEquals("Bob", res.get("to"));
        assertEquals("Bob", res.get("owner"), "转交后 owner=接收方");
        assertEquals("c1", res.get("clue_id"));
        assertTrue(res.get("result").toString().contains("转交"));

        // ownership 变更：源移除、目标加入
        assertFalse(game.playerClues.getOrDefault("Alice", List.of()).contains("c1"), "源持有者不再持有");
        assertTrue(game.playerClues.getOrDefault("Bob", List.of()).contains("c1"), "目标持有者获得");

        // status 可见性：接收方 my_clues 含转入线索；原持有者不可见（且 clues 列表也不含）
        assertTrue(myCluesContain(game.toMap("Bob"), "c1"), "接收方 status 可见转入线索");
        assertFalse(myCluesContain(game.toMap("Alice"), "c1"), "原持有者 my_clues 不再含 c1");
        assertFalse(game.toMap("Alice").get("clues").toString().contains("c1"), "原持有者线索列表不含 c1");
    }

    // ═══════════════════════════════════════════════════════════
    //  C2-5: 转交拒绝
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C2-5: 转交拒绝 —— 非持有者 / 目标不存在 / 转给自己 / 不可转交 / 阶段守卫")
    void transferRejectedCases() {
        ScriptGameService svc = new ScriptGameService(legacyLlm(), new ApprovalService());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);

        // ① 非持有者（Alice 尚未搜证）：归属校验拒绝
        Map<String, Object> nonOwner = svc.transferClue(SESSION, "Alice", "Bob", "c1");
        assertTrue(nonOwner.get("error").toString().contains("未持有"), "非持有者不能转交（可见性归属）");

        // ② 目标玩家不存在
        svc.search(SESSION, "Alice", "客厅"); // 持有 c1
        Map<String, Object> badTarget = svc.transferClue(SESSION, "Alice", "路人甲", "c1");
        assertTrue(badTarget.get("error").toString().contains("接收方不在本局玩家中"), "目标不存在拒绝");

        // ③ 转给自己
        Map<String, Object> self = svc.transferClue(SESSION, "Alice", "Alice", "c1");
        assertTrue(self.get("error").toString().contains("不能转交给自己"), "不能转给自己");

        // ④ 不可转交线索（c2 transferable=false）
        svc.search(SESSION, "Alice", "书房"); // 持有 c2
        Map<String, Object> noTransfer = svc.transferClue(SESSION, "Alice", "Bob", "c2");
        assertTrue(noTransfer.get("error").toString().contains("不可转交"), "transferable=false 拒绝");

        // ⑤ 阶段守卫：进投票后拒绝
        svc.startVoting(SESSION);
        Map<String, Object> latePhase = svc.transferClue(SESSION, "Alice", "Bob", "c1");
        assertTrue(latePhase.get("error").toString().contains("当前阶段不能转交线索"), "投票阶段拒绝转交");
        assertEquals(ScriptGameService.Phase.VOTE, game.phase);
    }

    // ═══════════════════════════════════════════════════════════
    //  C2-6: schema 兼容 —— 旧剧本缺字段默认值
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C2-6a: 旧剧本无 ap_cost/ap_bonus → 归一默认 ap_cost=1、ap_bonus=0（向后兼容）")
    void schemaDefaultsForLegacyScript() {
        Map<String, Object> script = new ScriptService(legacyLlm())
            .generateScript("庄园", List.of("Alice", "Bob", "Carol"));

        List<Map<String, Object>> clues = ScriptSchemaV1.clueList(script);
        // c1 无 ap_cost → 默认 1；c2 显式 2 → 透传；c3 公开 → 默认 1
        assertEquals(1, ScriptSchemaV1.apCost(clues.get(0)), "c1 无 ap_cost → 1");
        assertEquals(2, ScriptSchemaV1.apCost(clues.get(1)), "c2 ap_cost=2 透传");
        assertEquals(1, ScriptSchemaV1.apCost(clues.get(2)), "c3 无 ap_cost → 1");
        // 每个线索对象都带 ap_cost 键（消费方读取点一致）
        assertTrue(clues.get(0).containsKey("ap_cost"));

        List<Map<String, Object>> roles = ScriptSchemaV1.roleObjects(script);
        assertEquals(0, ScriptSchemaV1.roleApBonus(roles.get(0)), "旧格式角色无 ap_bonus → 0");
        assertEquals(0, ScriptSchemaV1.apBonusByRoleName(script).getOrDefault("管家", -1), "角色名映射默认 0");
    }

    @Test
    @DisplayName("C2-6b: v1 剧本 ap_cost/ap_bonus 透传（侦探 +2）")
    void schemaPassThroughForV1() {
        Map<String, Object> script = new ScriptService(v1Llm())
            .generateScript("雾都", List.of("Alice", "Bob"));

        assertEquals(2, ScriptSchemaV1.apBonusByRoleName(script).get("侦探"), "侦探 ap_bonus=2 透传");
        assertEquals(0, ScriptSchemaV1.apBonusByRoleName(script).get("管家"), "管家 ap_bonus=0");
        assertEquals(1, ScriptSchemaV1.apCost(ScriptSchemaV1.clueList(script).get(0)), "clue_1 ap_cost=1 透传");
    }

    // ═══════════════════════════════════════════════════════════
    //  C2-7: 端到端 —— 搜证 → AP 变化 → 转交 → status 可见
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("C2-7: 端到端 —— initGame → 搜证(AP 3→2) → 转交 → status 可见（转入方 my_clues + ap_pool）")
    void endToEndSearchTransferStatus() {
        ScriptGameService svc = new ScriptGameService(legacyLlm(), new ApprovalService());
        svc.initGame(SESSION, "庄园", List.of("Alice", "Bob", "Carol"));

        // 搜证：Alice 在客厅获得 c1，AP 3→2
        Map<String, Object> searchRes = svc.search(SESSION, "Alice", "客厅");
        assertEquals(2, searchRes.get("ap"), "搜证后 AP 变化");

        // 转交：c1 → Bob
        Map<String, Object> transferRes = svc.transferClue(SESSION, "Alice", "Bob", "c1");
        assertEquals("Bob", transferRes.get("owner"));

        // status 可见性：接收方看到转入线索 + 全员 AP 一览（Alice=2）
        Map<String, Object> bobStatus = svc.getGame(SESSION).toMap("Bob");
        assertTrue(myCluesContain(bobStatus, "c1"), "Bob 的 status 可见转入线索");
        @SuppressWarnings("unchecked")
        Map<String, Object> pool = (Map<String, Object>) bobStatus.get("ap_pool");
        assertEquals(2, pool.get("Alice"), "ap_pool 反映 Alice 剩余 2 AP");

        // 原持有者 status：不再持有 c1，AP 已扣
        Map<String, Object> aliceStatus = svc.getGame(SESSION).toMap("Alice");
        assertEquals(2, aliceStatus.get("ap"));
        assertFalse(myCluesContain(aliceStatus, "c1"), "Alice 不再持有 c1");
    }
}
