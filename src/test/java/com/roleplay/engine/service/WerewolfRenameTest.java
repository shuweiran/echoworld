package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.WerewolfController;
import com.roleplay.engine.db.entity.CharacterEntity;
import com.roleplay.engine.db.repository.CharacterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 改造方案《玩家角色改名与 AI 识别》Phase 2 判定测试（方案 §8 用例 3，P-0802-P2）：
 * 狼人杀 humanPlayers —— init 带 player_id 解析式登记人类玩家。
 *
 * <p>场景：角色库中「小明」已改名为「大明」（playerId 绑定随新名迁移，Phase 1 特性），
 * 前端仍传旧名 player_name=小明 + player_id → humanPlayers 登记解析名「大明」
 * （AI = 存活玩家中非人类，若登记旧名则 AI 行动器会接管玩家角色）。
 *
 * <p>关键回归断言：无 player_id 请求行为与现状逐字节一致（humans = Set.of(player_name)）。
 * 直接构造 WerewolfController（1/3 参构造 + 真实 PlayerIdentityService(mock repo)），
 * 与 WerewolfGameSmokeTest 同款（不加载 Spring 上下文）。
 */
class WerewolfRenameTest {

    /** 角色库绑定夹具：pid → 当前角色名（模拟改名后绑定随新名，Phase 1 特性）。 */
    private CharacterRepository boundRepo(String pid, String currentName) {
        CharacterRepository repo = mock(CharacterRepository.class);
        CharacterEntity entity = new CharacterEntity();
        entity.setName(currentName);
        when(repo.findByPlayerId(pid)).thenReturn(Optional.of(entity));
        return repo;
    }

    private CharacterRepository emptyRepo() {
        return mock(CharacterRepository.class);
    }

    private Map<String, Object> initBody(List<String> players) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("players", players);
        return body;
    }

    // ── ① player_id 解析式登记：角色改名后旧名 player_name + player_id → humanPlayers 含解析名 ──

    @Test
    @DisplayName("① init 带 player_id：humanPlayers 登记解析出的当前名（角色改名后旧名 player_name 不再被 AI 接管）")
    void initWithPlayerId_registersResolvedNameAsHuman() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        WerewolfController ctl = new WerewolfController(svc, null,
                new PlayerIdentityService(boundRepo("pid-ww", "大明")));

        Map<String, Object> body = initBody(List.of("小明", "老王", "苏哲"));
        body.put("player_id", "pid-ww");
        ResponseEntity<Map<String, Object>> resp = ctl.init("小明", "", "", body);
        assertEquals(200, resp.getStatusCode().value());
        String sid = String.valueOf(resp.getBody().get("session_id"));

        Set<String> humans = svc.getHumanPlayers(sid);
        assertTrue(humans.contains("大明"), "humanPlayers 应含解析名「大明」");
        assertFalse(humans.contains("小明"), "humanPlayers 不应再含旧名「小明」（否则 AI 行动器接管玩家角色）");
    }

    // ── ② 无 player_id：现状行为不变（humans = Set.of(player_name)）──

    @Test
    @DisplayName("② 无 player_id：humanPlayers = Set.of(player_name)（现状行为，零变化回归）")
    void initWithOutPlayerId_registersPlayerNameAsBefore() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        WerewolfController ctl = new WerewolfController(svc, null,
                new PlayerIdentityService(emptyRepo()));

        ResponseEntity<Map<String, Object>> resp = ctl.init("小明", "", "",
                initBody(List.of("小明", "老王", "苏哲")));
        assertEquals(200, resp.getStatusCode().value());
        String sid = String.valueOf(resp.getBody().get("session_id"));

        Set<String> humans = svc.getHumanPlayers(sid);
        assertEquals(Set.of("小明"), humans, "无 player_id 时 humanPlayers 应为 {player_name}（旧行为）");
    }

    // ── ③ player_id 未绑定 → 回退 player_name 字符串逻辑（零变化回归） ──

    @Test
    @DisplayName("③ player_id 未绑定（解析空）：回退 player_name 字符串登记（零变化）")
    void unboundPlayerId_fallsBackToPlayerName() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        WerewolfController ctl = new WerewolfController(svc, null,
                new PlayerIdentityService(emptyRepo()));

        Map<String, Object> body = initBody(List.of("小明", "老王", "苏哲"));
        body.put("player_id", "pid-unknown");
        ResponseEntity<Map<String, Object>> resp = ctl.init("小明", "", "", body);
        assertEquals(200, resp.getStatusCode().value());
        String sid = String.valueOf(resp.getBody().get("session_id"));

        assertEquals(Set.of("小明"), svc.getHumanPlayers(sid), "解析空应回退 player_name 登记（旧行为）");
    }

    // ── ④ player_id 从 query 参数传入（前端 query 格式兼容） ──

    @Test
    @DisplayName("④ player_id 经 query 参数传入：同样解析登记（query/body 双通道）")
    void playerIdViaQueryParam_alsoResolves() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        WerewolfController ctl = new WerewolfController(svc, null,
                new PlayerIdentityService(boundRepo("pid-ww2", "大明")));

        ResponseEntity<Map<String, Object>> resp = ctl.init("小明", "", "pid-ww2",
                initBody(List.of("小明", "老王", "苏哲")));
        assertEquals(200, resp.getStatusCode().value());
        String sid = String.valueOf(resp.getBody().get("session_id"));

        assertTrue(svc.getHumanPlayers(sid).contains("大明"), "query player_id 应同样解析登记");
    }

    @Test
    @DisplayName("⑤ 真人已在逆序名单后位：init 私密视图和 roleKey 仍归真人")
    void humanAlreadyInLaterPosition_receivesOwnPrivateViewAndRoleKey() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        WerewolfController ctl = new WerewolfController(svc, null,
                new PlayerIdentityService(emptyRepo()));

        Map<String, Object> body = initBody(List.of("AI甲", "真人", "AI乙"));
        body.put("roles", Map.of("AI甲", "werewolf", "真人", "seer", "AI乙", "villager"));
        ResponseEntity<Map<String, Object>> resp = ctl.init("真人", "", "", body);
        Map<String, Object> view = resp.getBody();
        String sid = String.valueOf(view.get("session_id"));

        assertEquals("seer", view.get("your_role"), "init 必须返回真人视角，不能返回 players[0] 的 AI 私密角色");
        assertEquals(svc.getRoleKey(sid, "真人"), view.get("role_key"), "init 令牌必须归当前真人");
        assertFalse(view.get("role_key").equals(svc.getRoleKey(sid, "AI甲")), "不得泄露首个 AI 的令牌");
        assertEquals("真人", svc.getGame(sid).alive.get(0), "Controller 应在建局前把真人移动到首位");
    }

    @Test
    @DisplayName("⑥ player_id 解析名不同：替换旧名、迁移角色并返回解析名令牌")
    void resolvedPlayerName_replacesLegacyNameAndOwnsInitToken() {
        WerewolfService svc = new WerewolfService(new ApprovalService());
        WerewolfController ctl = new WerewolfController(svc, null,
                new PlayerIdentityService(boundRepo("pid-renamed", "新名")));

        Map<String, Object> body = initBody(List.of("AI甲", "旧名", "AI乙"));
        body.put("roles", Map.of("AI甲", "werewolf", "旧名", "witch", "AI乙", "villager"));
        ResponseEntity<Map<String, Object>> resp = ctl.init("旧名", "", "pid-renamed", body);
        Map<String, Object> view = resp.getBody();
        String sid = String.valueOf(view.get("session_id"));
        WerewolfService.GameState game = svc.getGame(sid);

        assertEquals(List.of("新名", "AI甲", "AI乙"), game.alive, "解析名应替换旧名并置于首位，不得产生双身份");
        assertFalse(game.roles.containsKey("旧名"), "旧名角色键必须移除");
        assertEquals(WerewolfService.Role.WITCH, game.roles.get("新名"), "旧名的自定义角色应迁移到解析名");
        assertEquals("witch", view.get("your_role"), "init 私密视图应按解析后的真人名生成");
        assertEquals(svc.getRoleKey(sid, "新名"), view.get("role_key"), "返回令牌必须属于解析后的真人身份");
        assertEquals(Set.of("新名"), svc.getHumanPlayers(sid), "AI 行动器的人类排除名单应使用解析名");
    }
}
