package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0805-A（B2/B4）：killer_id 结构化判定 + 重投次数上限。
 *
 * B2：schema 含 killer_id 且 truth 文本不含凶手名（旧文本解析必判"冤枉好人"）→
 *     结构化判定应命中凶手角色对应玩家。
 * B4：平票/审批驳回重投达 maxRevotes 上限 → 按「无人被定罪」终止，revote=false，不无限循环。
 */
class ScriptGameKillerIdRevoteTest {

    private static final String SESSION = "kid-revote-session";

    /** 剧本：killer_id=role_b（真凶角色 id），truth 不含凶手名（逼出 B2 结构化路径）。 */
    private LLMClient mockLlm() {
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("schema_version", 1);
        script.put("name", "雪夜凶案");
        script.put("background", "雪夜庄园，主人被发现死于书房。");
        script.put("truth", "真相：凶器是书房花瓶，动机是遗产之争。");
        script.put("killer_id", "role_b");
        script.put("roles", List.of(
            Map.of("id", "role_a", "name", "管家"),
            Map.of("id", "role_b", "name", "女仆"),
            Map.of("id", "role_c", "name", "园丁")));
        script.put("locations", List.of("书房", "客厅"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "书房", "content", "碎花瓶", "public", false, "related_role", "女仆")));
        script.put("secrets", Map.of("管家", "你见过女仆进书房", "女仆", "你动了花瓶", "园丁", "你在雪地看到脚印"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);
        return llm;
    }

    private ApprovalService autoApprove() {
        ApprovalService approval = mock(ApprovalService.class);
        try {
            when(approval.submitForApproval(any(), anyString(), anyLong()))
                .thenAnswer(inv -> inv.getArgument(0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return approval;
    }

    private ScriptGameService newService() {
        return new ScriptGameService(mockLlm(), autoApprove());
    }

    /** 建局并直达投票阶段（不走讨论引擎，计时基准确定）。 */
    private void toVote(ScriptGameService svc, List<String> players) {
        svc.initGame(SESSION, "雪夜凶案", players);
        svc.startVoting(SESSION);
    }

    @Test
    @DisplayName("B2-1 killer_id 结构化判定：truth 不含凶手名也能正确归因")
    void structuralKillerResolve() {
        ScriptGameService svc = newService();
        toVote(svc, List.of("Alice", "Bob", "Carol"));

        // truth 无凶手名 → 旧文本解析必返回空串；此处须靠 killer_id=role_b → 女仆
        // assignments shuffle：先查 roleNamesById 含 role_b→女仆，再反查持有"女仆"的玩家
        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        String roleOfBob = game.getAssignments().get("Bob");
        String roleOfAlice = game.getAssignments().get("Alice");
        // 无论 shuffle 结果，投给"女仆"角色的玩家应被判中
        String culpritPlayer = roleOfBob.equals("女仆") ? "Bob" : (roleOfAlice.equals("女仆") ? "Alice" : "Carol");
        assertNotNull(culpritPlayer);

        svc.castVote(SESSION, culpritPlayer, "女仆");
        svc.castVote(SESSION, "Carol", "女仆");
        svc.castVote(SESSION, "Bob", culpritPlayer);

        Map<String, Object> res = svc.resolveVote(SESSION);

        assertEquals(Boolean.TRUE, res.get("correct"), "killer_id 结构化判定应命中: " + res);
        assertEquals(culpritPlayer, res.get("murderer"), "真凶应等于持有女仆角色的玩家");
        assertEquals("剧本杀成功！真凶被找到", String.valueOf(res.get("result")));
    }

    @Test
    @DisplayName("B2-2 结构化判定与文本解析共存：killer_id 为空（旧剧本）回退文本解析零破坏")
    void fallbackToTextParsingWhenKillerIdEmpty() {
        // 旧格式剧本无 killer_id → 走 D6 文本解析（truth 含凶手名）
        LLMClient llm = mock(LLMClient.class);
        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", "庄园疑云");
        script.put("truth", "凶手是管家，因为管家贪图遗产。");
        script.put("roles", List.of("管家", "女仆", "园丁"));
        script.put("clues", List.of(
            Map.of("id", "c1", "location", "客厅", "content", "碎玻璃", "public", false, "related_role", "管家")));
        script.put("secrets", Map.of("管家", "你贪图遗产", "女仆", "你知道秘密", "园丁", "你看到了凶手"));
        when(llm.callJson(anyString(), anyInt())).thenReturn(script);

        ScriptGameService svc = new ScriptGameService(llm, autoApprove());
        svc.initGame(SESSION, "庄园疑云", List.of("Alice", "Bob", "Carol"));
        svc.startVoting(SESSION);

        // 3 人局全投管家角色 → 得票最高且 truth 解析命中管家
        svc.castVote(SESSION, "Alice", "管家");
        svc.castVote(SESSION, "Bob", "管家");
        svc.castVote(SESSION, "Carol", "管家");
        Map<String, Object> res = svc.resolveVote(SESSION);

        assertEquals(Boolean.TRUE, res.get("correct"), "文本解析回退路径应保持命中: " + res);
    }

    @Test
    @DisplayName("B4-1 平票重投达上限：按「无人被定罪」终止，revote=false，不无限循环")
    void tieRevoteLimitStops() {
        ScriptGameService svc = newService();
        svc.setMaxRevotes(2); // 上限 2 次重投
        svc.setQuorumEnabled(false); // 排除 quorum 干扰（平票优先）
        toVote(svc, List.of("Alice", "Bob", "Carol"));

        // 3 人局：Alice→Bob、Bob→Alice、Carol 弃 → 1:1 平票
        svc.castVote(SESSION, "Alice", "Bob");
        svc.castVote(SESSION, "Bob", "Alice");

        // 第 1 次平票 → 清票重投（revote=true, tie=true）
        Map<String, Object> r1 = svc.resolveVote(SESSION);
        assertEquals(Boolean.TRUE, r1.get("tie"));
        assertEquals(Boolean.TRUE, r1.get("revote"));
        assertEquals(ScriptGameService.Phase.VOTE, svc.getGame(SESSION).phase);
        assertEquals(1, svc.getGame(SESSION).revoteCount);

        // 第 2 次平票 → 仍重投（计数 2）
        svc.castVote(SESSION, "Alice", "Bob");
        svc.castVote(SESSION, "Bob", "Alice");
        Map<String, Object> r2 = svc.resolveVote(SESSION);
        assertEquals(Boolean.TRUE, r2.get("tie"));
        assertEquals(Boolean.TRUE, r2.get("revote"));
        assertEquals(2, svc.getGame(SESSION).revoteCount);

        // 第 3 次平票 → 达上限 → 无人被定罪，revote=false，留在 VOTE（不进入 REVEAL 不无限循环）
        svc.castVote(SESSION, "Alice", "Bob");
        svc.castVote(SESSION, "Bob", "Alice");
        Map<String, Object> r3 = svc.resolveVote(SESSION);
        assertEquals(Boolean.TRUE, r3.get("tie_limit"), "达上限应标记 tie_limit: " + r3);
        assertEquals(Boolean.FALSE, r3.get("revote"), "达上限不应再重投");
        assertTrue(String.valueOf(r3.get("result")).contains("无人被定罪"), "文案应提示无人被定罪: " + r3.get("result"));
        assertEquals(3, svc.getGame(SESSION).revoteCount);
    }

    @Test
    @DisplayName("B4-2 重投计数与 roleNamesById 在对局状态内正确维护")
    void revoteCountAndRoleMapMaintained() {
        ScriptGameService svc = newService();
        svc.setMaxRevotes(5);
        svc.setQuorumEnabled(false);
        toVote(svc, List.of("Alice", "Bob", "Carol"));

        ScriptGameService.ScriptGame game = svc.getGame(SESSION);
        assertNotNull(game.roleNamesById);
        assertEquals("女仆", game.roleNamesById.get("role_b"), "B2：killer_id=role_b 应映射角色名 女仆");
        assertEquals("管家", game.roleNamesById.get("role_a"));

        svc.castVote(SESSION, "Alice", "Bob");
        svc.castVote(SESSION, "Bob", "Alice");
        svc.resolveVote(SESSION); // 1 次平票重投
        assertEquals(1, game.revoteCount, "平票重投应累加 revoteCount");
    }

    @Test
    @DisplayName("B5-1 对局 TTL：超时未访问的对局被惰性清理（快照持久化不受影响）")
    void ttlEvictsIdleGame() throws Exception {
        ScriptGameService svc = newService();
        svc.setGameTtlMs(50); // 50ms TTL
        toVote(svc, List.of("Alice", "Bob", "Carol"));

        // 初始可访问
        assertNotNull(svc.getGame(SESSION));
        Thread.sleep(150); // 超过 TTL 且未访问

        // 触发清扫 → 应被清理
        svc.sweepExpired();
        assertNull(svc.getGame(SESSION), "闲置超 TTL 的对局应被惰性清理");
        assertNull(svc.getGame(SESSION), "清理后再次访问仍为空");
    }

    @Test
    @DisplayName("B5-2 TTL=0 禁用清理：长期保留（回归守卫）")
    void ttlDisabledKeepsGame() throws Exception {
        ScriptGameService svc = newService();
        svc.setGameTtlMs(0); // 禁用
        toVote(svc, List.of("Alice", "Bob", "Carol"));
        Thread.sleep(80);
        assertNotNull(svc.getGame(SESSION), "TTL=0 不应清理");
    }
}
