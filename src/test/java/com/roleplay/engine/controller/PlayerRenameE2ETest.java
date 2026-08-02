package com.roleplay.engine.controller;

import com.roleplay.engine.core.Persona;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.PlayerIdentityService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.ScriptGameService;
import com.roleplay.engine.service.WerewolfService;
import com.roleplay.engine.simulation.SimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 改造方案《玩家角色改名与 AI 识别》Phase 3 验收测试（P-0802-P3）：局中改名端点同步式 E2E。
 *
 * <p>一次改名 → 四链路同步断言（方案 §8 用例 6）：
 * <ul>
 *   <li>2D：states 键换名 + playerControlled 标记保留（旧名 state 不存在、新名 state 存在且被标记）</li>
 *   <li>一般模式：RouterService agents 键换名（protagonist/restrictedAgents 引用同步）</li>
 *   <li>狼人杀：humanPlayers 集合换名 + GameState 名字键（roles/alive/playerKeys）迁移</li>
 *   <li>剧本杀：ScriptGame 全键迁移 + checkPlayerAccess（新名+roleKey）通过 + playerSessions/playerIdBindings 键换名</li>
 * </ul>
 * 响应含 synced_sessions 清单；撞名 409；未绑定 403；兼容路径 old_name。
 *
 * <p>@SpringBootTest + @MockBean LLMClient（ScriptPersistenceTest 同模式）：真实 Spring 上下文装配
 * 全部服务，E2E 走真实 RouterService/SimulationService/WerewolfService/ScriptGameService/CharacterController。
 * 各用例使用独立名字前缀，互不依赖、顺序无关（角色库/对局内存为上下文级共享）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class PlayerRenameE2ETest {

    @Autowired
    private PlayerIdentityService identityService;

    @Autowired
    private RouterService router;

    @Autowired
    private SimulationService simulationService;

    @Autowired
    private WerewolfService werewolfService;

    @Autowired
    private ScriptGameService scriptGameService;

    @Autowired
    private CharacterController characterController;

    @Autowired
    private ScriptController scriptController;

    @Autowired
    private WerewolfController werewolfController;

    @MockBean
    private LLMClient llmClient;

    private static final String PID = "pid-e2e-rollback";

    /** 经 CharacterController.create 建角色（内存列表 + DB 双写，renameCharacterInMemory 依赖内存列表）。 */
    private void createCharacter(String name, String playerId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("persona", name + "的性格");
        body.put("voice", "正常");
        body.put("background", "测试背景");
        if (playerId != null) body.put("player_id", playerId);
        characterController.create(body);
    }

    /** 角色库内存列表是否含指定名字（CharacterController 内存态）。 */
    private boolean libraryHas(String name) {
        return characterController.getAll().stream().anyMatch(c -> name.equals(c.get("name")));
    }

    // ═══════════════════════════════════════════════════════════
    //  ① 四链路同步：一次改名 → 四处运行态全部换新名
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("① E2E：一次改名 → Router/2D/Werewolf/Script 四处同步 + synced_sessions 清单")
    void renameSyncsAllFourLinks() {
        String pid = "pid-e2e-1";
        String oldName = "t1小明";
        String newName = "t1大明";
        createCharacter(oldName, pid);

        // ── 四链路开局状态 ──
        // 一般模式：RouterService 会话含 oldName
        router.initSession("e2e-router", List.of(persona(oldName), persona("t1小红")),
                "公园", "free", oldName, "");
        router.setRestrictedAgents(Set.of(oldName));
        assertTrue(router.hasAgent(oldName), "开局 router agents 应含旧名");
        assertTrue(router.isProtagonist(oldName), "开局 protagonist 应为旧名");

        // 2D：playerId 解析标记 playerControlled（Phase 2）
        simulationService.initWithPersonas(List.of(persona(oldName), persona("t1小红")), "park", oldName, pid);
        assertTrue(simulationService.isPlayerControlled(oldName), "开局 2D 旧名应 playerControlled");

        // 狼人杀：人类玩家 oldName
        String wwSid = "e2e-ww";
        werewolfService.initGame(wwSid, List.of(oldName, "t1小红"), Map.of(oldName, "SEER", "t1小红", "VILLAGER"));
        werewolfService.setHumanPlayers(wwSid, Set.of(oldName));
        werewolfService.setAutoPlay(wwSid, false);
        assertTrue(werewolfService.getHumanPlayers(wwSid).contains(oldName), "开局 humans 应含旧名");

        // 剧本杀：玩家 oldName + playerSessions/playerIdBindings 登记
        String scSid = "e2e-sc";
        scriptGameService.initGame(scSid, "庄园疑云", List.of(oldName, "t1小红"));
        scriptController.playerSessions().put(oldName, scSid);
        scriptController.playerIdBindings().put(oldName, pid);
        scriptGameService.registerPlayerBinding(scSid, pid, oldName);
        String roleKey = scriptGameService.getRoleKey(scSid, oldName);

        // ── 改名 ──
        Map<String, Object> result = identityService.renamePlayerCharacter(pid, null, newName);
        assertEquals(Boolean.FALSE, result.get("collision"));
        assertEquals(newName, result.get("new_name"));
        assertEquals(oldName, result.get("old_name"));

        @SuppressWarnings("unchecked")
        List<String> synced = (List<String>) result.get("synced_sessions");
        assertNotNull(synced, "应返回 synced_sessions 清单");
        assertTrue(synced.contains("router"), "synced_sessions 应含 router，实际: " + synced);
        assertTrue(synced.contains("2d"), "synced_sessions 应含 2d，实际: " + synced);
        assertTrue(synced.contains("werewolf:" + wwSid), "synced_sessions 应含 werewolf 局，实际: " + synced);
        assertTrue(synced.contains("script:" + scSid), "synced_sessions 应含 script 局，实际: " + synced);

        // ── ① 一般模式：agents 键换名 + 引用同步 ──
        assertTrue(router.hasAgent(newName), "router agents 应含新名");
        assertFalse(router.hasAgent(oldName), "router agents 不应再含旧名");
        assertTrue(router.isProtagonist(newName), "protagonist 引用应换新名");
        assertTrue(router.getRestrictedAgents().contains(newName), "restrictedAgents 引用应换新名");
        assertFalse(router.getRestrictedAgents().contains(oldName), "restrictedAgents 不应含旧名");

        // ── ② 2D：states 键换名 + playerControlled 保留 ──
        assertTrue(simulationService.isPlayerControlled(newName), "2D 新名 state 应 playerControlled");
        assertFalse(simulationService.hasAgent(oldName), "2D 不应再含旧名 state");

        // ── ③ 狼人杀：humanPlayers + GameState 名字键迁移 ──
        Set<String> humans = werewolfService.getHumanPlayers(wwSid);
        assertTrue(humans.contains(newName), "狼人杀 humans 应换新名，实际: " + humans);
        assertFalse(humans.contains(oldName), "狼人杀 humans 不应含旧名");
        assertTrue(werewolfService.getGame(wwSid).getRoles().containsKey(newName), "狼人杀 roles 键应换新名");
        assertTrue(werewolfService.getGame(wwSid).getAlive().contains(newName), "狼人杀 alive 应换新名");
        assertNotNull(werewolfService.getRoleKey(wwSid, newName), "狼人杀 playerKeys 键应换新名");

        // ── ④ 剧本杀：全键迁移 + checkPlayerAccess 通过 + playerSessions/playerIdBindings 键换名 ──
        ScriptGameService.ScriptGame game = scriptGameService.getGame(scSid);
        assertTrue(game.getPlayers().contains(newName), "剧本杀 players 应换新名");
        assertTrue(game.getAssignments().containsKey(newName), "剧本杀 assignments 键应换新名");
        assertNotNull(game.getPlayerKeys().get(newName), "剧本杀 playerKeys 键应换新名");
        assertNull(scriptGameService.checkPlayerAccess(scSid, newName, roleKey), "checkPlayerAccess 新名+key 应通过");
        Map<String, Object> view = game.toMap(newName);
        assertFalse(String.valueOf(view.get("your_role")).isBlank(), "toMap 新名视角应能读到角色");
        assertTrue(scriptController.playerSessions().containsKey(newName), "script playerSessions 键应换新名");
        assertFalse(scriptController.playerSessions().containsKey(oldName), "script playerSessions 不应含旧名");
        assertEquals(pid, scriptController.playerIdBindings().get(newName), "script playerIdBindings 键应换新名");

        // ── 角色库：新名存在、旧名不存在，绑定随新行保留 ──
        assertTrue(libraryHas(newName), "角色库应含新名");
        assertFalse(libraryHas(oldName), "角色库不应含旧名");
        assertEquals(newName, identityService.resolveCharacterName(pid).orElse(""), "playerId 绑定应解析到新名");
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 撞名 409（库内同名 / 活跃会话内同名）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("② 撞名校验②：库内同名 → 409；活跃会话内同名（NPC）→ 409")
    void collisionChecks() {
        String pid = "pid-e2e-2";
        createCharacter("t2小明", pid);

        // 库内同名：t2小红已在角色库 → 409
        createCharacter("t2小红", null);
        Map<String, Object> r1 = identityService.renamePlayerCharacter(pid, null, "t2小红");
        assertEquals(409, r1.get("status"), "库内同名应 409");
        assertTrue(String.valueOf(r1.get("error")).contains("角色名已存在"), "错误信息应含角色名已存在");

        // 活跃会话内同名：改名为「t2NPC」→ 不撞库，但 2D 世界先开一局含 NPC「t2NPC」→ 409
        simulationService.initWithPersonas(List.of(persona("t2小明"), persona("t2NPC")), "park", "t2小明", pid);
        Map<String, Object> r2 = identityService.renamePlayerCharacter(pid, null, "t2NPC");
        assertEquals(409, r2.get("status"), "活跃会话内同名应 409");
        assertTrue(String.valueOf(r2.get("error")).contains("活跃会话"), "错误信息应含活跃会话");
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 鉴权 403：player_id 未绑定 → 拒绝；兼容路径 old_name 定位
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("③ 鉴权：未绑定 player_id → 403；old_name 兼容路径可改名")
    void authAndCompatPath() {
        createCharacter("t3小明", "pid-e2e-3");

        // 未绑定 player_id → 403
        Map<String, Object> r1 = identityService.renamePlayerCharacter("pid-unknown", null, "t3大明");
        assertEquals(403, r1.get("status"), "未绑定应 403");
        assertTrue(String.valueOf(r1.get("error")).contains("未绑定"), "错误信息应含未绑定");

        // 兼容路径：无 player_id 用 old_name（t3小明未被绑定占用？——已绑定 pid-e2e-3 → 按设计
        // 带绑定的角色应走 player_id；此处用未绑定角色验证兼容路径）
        createCharacter("t3未绑定", null);
        Map<String, Object> r2 = identityService.renamePlayerCharacter(null, "t3未绑定", "t3大壮");
        assertEquals(200, r2.getOrDefault("status", 200), "兼容路径应可改名");
        assertEquals("t3大壮", r2.get("new_name"));
        assertTrue(libraryHas("t3大壮"), "兼容路径改名后角色库应含新名");
    }

    // ═══════════════════════════════════════════════════════════
    //  ④ 端点契约：POST /api/player/rename 响应形态（200/403/409 映射）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("④ POST /api/player/rename 端点：200 带 synced_sessions；无绑定 403；撞名 409")
    void endpointContract() {
        String pid = "pid-e2e-4";
        createCharacter("t4小明", pid);
        // 先开一局 2D 让同步有内容
        simulationService.initWithPersonas(List.of(persona("t4小明")), "park", "t4小明", pid);
        PlayerController ctl = new PlayerController(identityService);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("player_id", pid);
        body.put("new_name", "t4大鹏");
        ResponseEntity<Map<String, Object>> ok = ctl.rename(body);
        assertEquals(200, ok.getStatusCode().value());
        assertTrue(ok.getBody().containsKey("synced_sessions"), "成功响应应含 synced_sessions");
        // 仅 2D 局存在（router 无该角色）→ synced 应为 [2d]
        assertEquals(List.of("2d"), ok.getBody().get("synced_sessions"), "synced 应仅含 2d");

        // 未绑定 → 403
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("player_id", "nope");
        body2.put("new_name", "t4大鹏");
        assertEquals(403, ctl.rename(body2).getStatusCode().value(), "未绑定应 403");

        // 撞名 → 409（角色库已有 t4NPC）
        createCharacter("t4NPC", null);
        Map<String, Object> body3 = new LinkedHashMap<>();
        body3.put("player_id", pid);
        body3.put("new_name", "t4NPC");
        assertEquals(409, ctl.rename(body3).getStatusCode().value(), "撞名应 409");
    }

    // ═══════════════════════════════════════════════════════════
    //  ⑤ 无 player_id 请求行为零变化（回归锚点）
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("⑤ 无 player_id / 未改名请求：现状行为零变化（rename 只影响显式调用）")
    void noPlayerIdUnchanged() {
        // 角色库原样
        createCharacter("t5小明", null);
        assertTrue(libraryHas("t5小明"));
        // 解析器：无 player_id → empty；未绑定名 → empty
        assertTrue(identityService.resolveCharacterName("").isEmpty());
        assertTrue(identityService.resolveCharacterName(null).isEmpty());
        assertTrue(identityService.resolveCharacterName("pid-none").isEmpty());
        // 2D 无 player_id 路径（Phase 2 已锁定，此处回归）
        simulationService.initWithPersonas(List.of(persona("t5小明")), "park", "t5小明", null);
        assertTrue(simulationService.isPlayerControlled("t5小明"), "无 player_id 显式 playerName 标记不变");
    }

    // ═══════════════════════════════════════════════════════════
    //  ⑥ 回滚路径：任一同步失败 → 已改项全部回滚（角色库 + 已同步会话），返回 500 rolled_back
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("⑥ 回滚路径：剧本杀同步抛异常 → 角色库/已同步会话全部回滚，响应 500 rolled_back=true")
    void syncFailure_rollsBackEverything() {
        // 手工装配：真实 CharacterController（内存列表）+ mock CharacterRepository + 失败注入的 ScriptGameService
        com.roleplay.engine.db.repository.CharacterRepository repo = mock(com.roleplay.engine.db.repository.CharacterRepository.class);
        com.roleplay.engine.db.entity.CharacterEntity entity = new com.roleplay.engine.db.entity.CharacterEntity();
        entity.setName("小明");
        entity.setPlayerId(PID);
        when(repo.findByPlayerId(PID)).thenReturn(java.util.Optional.of(entity));
        when(repo.findByName("小明")).thenReturn(java.util.Optional.of(entity));
        when(repo.findByName("大明")).thenReturn(java.util.Optional.empty());

        DatabaseService db = mock(DatabaseService.class);
        com.roleplay.engine.service.GeneratorService gen = mock(com.roleplay.engine.service.GeneratorService.class);
        CharacterController cc = new CharacterController(gen, db);
        // 经 create() 建角色（内存列表 + mock DB 落库）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "小明");
        body.put("persona", "开朗");
        body.put("voice", "正常");
        body.put("background", "背景");
        body.put("player_id", PID);
        cc.create(body);

        // 已同步成功项：router / 2d（真实可验证回滚）
        RouterService routerMock = mock(RouterService.class);
        when(routerMock.hasAgent("小明")).thenReturn(true);
        when(routerMock.hasAgent("大明")).thenReturn(false);
        SimulationService simMock = mock(SimulationService.class);
        when(simMock.hasAgent("小明")).thenReturn(true);
        when(simMock.hasAgent("大明")).thenReturn(false);
        // 失败注入：剧本杀 renamePlayer 抛异常（排在 router/2d/werewolf 之后）
        ScriptGameService scriptMock = mock(ScriptGameService.class);
        when(scriptMock.sessionsOfPlayer("小明")).thenReturn(java.util.Set.of("sc-rollback"));
        doThrow(new RuntimeException("simulated sync failure")).when(scriptMock).renamePlayer("sc-rollback", "小明", "大明");
        // 狼人杀：无该玩家局（跳过）
        WerewolfService wolfMock = mock(WerewolfService.class);
        when(wolfMock.sessionsOfPlayer("小明")).thenReturn(java.util.Set.of());

        PlayerIdentityService pis = new PlayerIdentityService(repo, db, cc, routerMock, simMock, wolfMock,
                scriptMock, null, null);
        Map<String, Object> result = pis.renamePlayerCharacter(PID, null, "大明");

        // 响应：500 + rolled_back
        assertEquals(500, result.get("status"), "同步失败应 500");
        assertEquals(Boolean.TRUE, result.get("rolled_back"), "应标记 rolled_back");
        // 角色库回滚：内存列表仍为小明，无大明（断言本地 cc，非 Spring 共享 bean）
        boolean hasOld = cc.getAll().stream().anyMatch(c -> "小明".equals(c.get("name")));
        boolean hasNew = cc.getAll().stream().anyMatch(c -> "大明".equals(c.get("name")));
        assertTrue(hasOld, "角色库应回滚为小明");
        assertFalse(hasNew, "角色库不应残留大明");
        // 已同步会话回滚：router/2d 被逆操作改回旧名
        verify(routerMock).renameAgent("小明", "大明");   // 正向
        verify(routerMock).renameAgent("大明", "小明");   // 回滚
        verify(simMock).renamePlayerCharacter("小明", "大明");
        verify(simMock).renamePlayerCharacter("大明", "小明");
    }

    private static Persona persona(String name) {
        Persona p = new Persona(name, name + "的性格");
        p.setVoice("正常");
        p.setBackground("测试背景");
        return p;
    }
}
