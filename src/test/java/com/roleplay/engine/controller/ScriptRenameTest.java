package com.roleplay.engine.controller;

import com.roleplay.engine.db.entity.CharacterEntity;
import com.roleplay.engine.db.repository.CharacterRepository;
import com.roleplay.engine.service.PlayerIdentityService;
import com.roleplay.engine.service.RouterService;
import com.roleplay.engine.service.ScriptGameService;
import com.roleplay.engine.simulation.SimulationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 改造方案《玩家角色改名与 AI 识别》Phase 2 判定测试（方案 §8 用例 4，P-0802-P2）：
 * 剧本杀 init 登记 —— 带可选 player_id 按解析出的当前角色名登记玩家身份绑定。
 *
 * <p>场景：角色库中「小明」已改名为「大明」（playerId 绑定随新名迁移，Phase 1 特性），
 * 前端 init 仍传 players=旧名 + player_id → 登记绑定键为解析名「大明」
 * （Phase 3 局中改名同步 / 重连恢复以该绑定定位玩家）。
 *
 * <p>关键回归断言：无 player_id / 未绑定 → 不产生绑定，行为与现状逐字节一致。
 * 直接构造 ScriptController（mock ScriptGameService/RouterService/SimulationService +
 * 真实 PlayerIdentityService(mock repo)），不加载 Spring 上下文。
 */
class ScriptRenameTest {

    /** 角色库绑定夹具：pid → 当前角色名（模拟改名后绑定随新名，Phase 1 特性）。 */
    private CharacterRepository boundRepo(String pid, String currentName) {
        CharacterRepository repo = mock(CharacterRepository.class);
        CharacterEntity entity = new CharacterEntity();
        entity.setName(currentName);
        when(repo.findByPlayerId(pid)).thenReturn(Optional.of(entity));
        return repo;
    }

    private ScriptController newController(CharacterRepository repo) {
        ScriptGameService svc = mock(ScriptGameService.class);
        when(svc.initGame(anyString(), anyString(), anyList())).thenReturn(new LinkedHashMap<>());
        when(svc.getGame(anyString())).thenReturn(null); // router.setScriptGame null 守卫
        RouterService router = mock(RouterService.class);
        SimulationService sim = mock(SimulationService.class);
        return new ScriptController(svc, router, sim, new PlayerIdentityService(repo));
    }

    private Map<String, Object> initBody(List<String> players) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("players", players);
        body.put("theme", "庄园疑云");
        return body;
    }

    // ── ① player_id 解析式登记：角色改名后旧名 players + player_id → 绑定键为解析名 ──

    @Test
    @DisplayName("① init 带 player_id：按解析出的当前角色名登记绑定（角色改名后旧名 players 仍登记到新名）")
    void initWithPlayerId_registersResolvedName() {
        ScriptController ctl = newController(boundRepo("pid-sc", "大明"));
        Map<String, Object> body = initBody(List.of("小明", "小红"));
        body.put("player_id", "pid-sc");

        ResponseEntity<Map<String, Object>> resp = ctl.init(body);
        assertEquals(200, resp.getStatusCode().value());

        Map<String, String> bindings = ctl.playerIdBindings();
        assertEquals("pid-sc", bindings.get("大明"), "应按解析名「大明」登记绑定");
        assertFalse(bindings.containsKey("小明"), "不应以旧名登记");
    }

    // ── ② 无 player_id → 不产生绑定（现状行为，零变化回归） ──

    @Test
    @DisplayName("② 无 player_id：不产生玩家身份绑定（现状行为，零变化）")
    void initWithOutPlayerId_noBinding() {
        ScriptController ctl = newController(mock(CharacterRepository.class));
        ctl.init(initBody(List.of("小明", "小红")));
        assertTrue(ctl.playerIdBindings().isEmpty(), "无 player_id 不应产生绑定（零变化）");
    }

    // ── ③ player_id 未绑定 → 不产生绑定（回退零变化） ──

    @Test
    @DisplayName("③ player_id 未绑定（解析空）：不产生绑定（回退零变化）")
    void unboundPlayerId_noBinding() {
        ScriptController ctl = newController(mock(CharacterRepository.class));
        Map<String, Object> body = initBody(List.of("小明", "小红"));
        body.put("player_id", "pid-unknown");
        ctl.init(body);
        assertTrue(ctl.playerIdBindings().isEmpty(), "未绑定 player_id 不应产生绑定（零变化）");
    }
}
