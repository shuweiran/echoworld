package com.roleplay.engine.service;

import com.roleplay.engine.approval.ApprovalService;
import com.roleplay.engine.controller.WerewolfController;
import com.roleplay.engine.db.entity.ScriptEntity;
import com.roleplay.engine.db.repository.ScriptRepository;
import com.roleplay.engine.db.service.DatabaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0802-J：狼人杀 resume roleKey 防冒充验收测试（对齐剧本杀 C3 roleKey 体系）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>R-1：initGame 为每玩家发放唯一 roleKey；toMap 仅向本人暴露 role_key（匿名/他人视图不含）</li>
 *   <li>R-2：resumeGame 正反例 —— 有效 key 恢复成功 / 缺 key 拒绝 / 错误 key 拒绝 / 他人 key 冒充拒绝 /
 *       仅凭 key 反查玩家（不传玩家名）</li>
 *   <li>R-3：roleKey 随快照持久化 —— 跨实例恢复（重启）后 resume 仍按 key 校验</li>
 *   <li>R-4：GET /api/werewolf/keys 主持人分发端点</li>
 * </ul>
 *
 * <p>风格：直构服务（真实 ApprovalService），快照测试用 mock ScriptRepository 装配真实 DatabaseService。
 */
class WerewolfRoleKeyTest {

    /** 6 人局：A/B 狼、C 预言家、D 女巫、E 猎人、F 村民。 */
    private Map<String, String> sixRoles() {
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put("A", "wolf");
        roles.put("B", "werewolf");
        roles.put("C", "预言家");
        roles.put("D", "witch");
        roles.put("E", "hunter");
        roles.put("F", "villager");
        return roles;
    }

    private List<String> sixPlayers() {
        return new ArrayList<>(List.of("A", "B", "C", "D", "E", "F"));
    }

    private WerewolfService newService() {
        return new WerewolfService(new ApprovalService());
    }

    /** 用 mock ScriptRepository 装配真实 DatabaseService（快照落库/恢复全链路）。 */
    private DatabaseService dbWithMockRepo() {
        ScriptRepository repo = mock(ScriptRepository.class);
        List<ScriptEntity> saved = new CopyOnWriteArrayList<>();
        when(repo.save(any(ScriptEntity.class))).thenAnswer(inv -> {
            ScriptEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId((long) saved.size() + 1);
            saved.add(e);
            return e;
        });
        when(repo.findByNameStartingWithOrderByIdDesc(anyString())).thenAnswer(inv -> {
            String prefix = inv.getArgument(0);
            List<ScriptEntity> reversed = new ArrayList<>(saved);
            java.util.Collections.reverse(reversed);
            return reversed.stream().filter(s -> s.getName().startsWith(prefix)).toList();
        });
        return new DatabaseService(null, null, null, null, repo, null);
    }

    @Test
    @DisplayName("R-1: initGame 发放唯一 roleKey；toMap 仅向本人暴露 role_key")
    void initIssuesUniqueRoleKeysExposedToSelfOnly() {
        WerewolfService svc = newService();
        svc.initGame("rk-1", sixPlayers(), sixRoles());

        Map<String, Object> viewA = svc.getGame("rk-1").toMap("A");
        Map<String, Object> viewB = svc.getGame("rk-1").toMap("B");
        Map<String, Object> anon = svc.getGame("rk-1").toMap("");
        Map<String, Object> outsider = svc.getGame("rk-1").toMap("局外人");

        String keyA = svc.getRoleKey("rk-1", "A");
        String keyB = svc.getRoleKey("rk-1", "B");
        assertFalse(keyA.isEmpty(), "A 应有 roleKey");
        assertFalse(keyB.isEmpty(), "B 应有 roleKey");
        assertNotEquals(keyA, keyB, "每玩家 roleKey 唯一");

        assertEquals(keyA, viewA.get("role_key"), "A 视角暴露自己的 roleKey");
        assertEquals(keyB, viewB.get("role_key"), "B 视角暴露自己的 roleKey");
        assertFalse(anon.containsKey("role_key"), "匿名视图不含 role_key（防泄露）");
        assertFalse(outsider.containsKey("role_key"), "非本局玩家视图不含 role_key");
    }

    @Test
    @DisplayName("R-2: resume 正反例 —— 有效 key 成功 / 缺 key 拒 / 错 key 拒 / 他人 key 冒充拒 / 仅 key 反查")
    void resumeRequiresValidRoleKey() {
        WerewolfService svc = newService();
        svc.initGame("rk-2", sixPlayers(), sixRoles());
        String keyF = svc.getRoleKey("rk-2", "F");
        String keyA = svc.getRoleKey("rk-2", "A");

        // 正例：有效 key + 玩家名
        Map<String, Object> ok = svc.resumeGame("rk-2", "F", keyF);
        assertFalse(ok.containsKey("error"), "有效 key 应恢复成功，实际: " + ok);
        assertEquals("F", ok.get("player"));
        assertEquals(Boolean.TRUE, ok.get("resumed"));

        // 正例：仅凭 key 反查玩家（重连场景客户端只持 key）
        Map<String, Object> keyOnly = svc.resumeGame("rk-2", "", keyF);
        assertFalse(keyOnly.containsKey("error"));
        assertEquals("F", keyOnly.get("player"), "仅凭 key 反查玩家");

        // 反例：缺 key
        Map<String, Object> noKey = svc.resumeGame("rk-2", "F", "");
        assertEquals("身份校验失败：player_key 缺失或不匹配", noKey.get("error"));

        // 反例：错误 key
        Map<String, Object> wrongKey = svc.resumeGame("rk-2", "F", "not-a-real-key");
        assertEquals("身份校验失败：player_key 缺失或不匹配", wrongKey.get("error"));

        // 反例：他人 key 冒充（用 A 的 key 恢复 F）
        Map<String, Object> impersonate = svc.resumeGame("rk-2", "F", keyA);
        assertEquals("身份校验失败：player_key 缺失或不匹配", impersonate.get("error"), "拿他人 key 冒充被拒");

        // 反例：玩家名与 key 不匹配（用 F 的 key 恢复 A）
        Map<String, Object> mismatch = svc.resumeGame("rk-2", "A", keyF);
        assertEquals("身份校验失败：player_key 缺失或不匹配", mismatch.get("error"), "key 与玩家名不匹配被拒");

        // 反例：不存在的对局（对局缺失优先于身份校验）
        Map<String, Object> noGame = svc.resumeGame("no-such", "F", keyF);
        assertEquals("对局不存在且无快照可恢复", noGame.get("error"));
    }

    @Test
    @DisplayName("R-3: roleKey 随快照持久化 —— 跨实例恢复（重启）后 resume 仍按 key 校验")
    void roleKeysSurviveSnapshotRestore() {
        DatabaseService db = dbWithMockRepo();
        // 实例 1：开局并推进到白天讨论（落快照）
        WerewolfService svc1 = new WerewolfService(new ApprovalService(), null, null, db);
        String sid = "rk-persist";
        svc1.initGame(sid, sixPlayers(), sixRoles());
        svc1.setHumanPlayers(sid, Set.of("F"));
        svc1.runAiNightActions(sid);
        svc1.resolveNight(sid);
        String keyF1 = svc1.getRoleKey(sid, "F");
        assertFalse(keyF1.isEmpty());

        // 实例 2：模拟重启（同一 DB，无内存对局）→ 快照重建后 resume 仍校验原 key
        WerewolfService svc2 = new WerewolfService(new ApprovalService(), null, null, db);
        Map<String, Object> ok = svc2.resumeGame(sid, "F", keyF1);
        assertFalse(ok.containsKey("error"), "快照恢复后原 roleKey 仍有效，实际: " + ok);
        assertEquals(Boolean.TRUE, ok.get("restored"), "从快照重建");
        assertEquals("F", ok.get("player"));

        // 错误 key 在恢复后同样被拒（keys 已随快照恢复）
        Map<String, Object> wrong = svc2.resumeGame(sid, "F", "bad-key");
        assertEquals("身份校验失败：player_key 缺失或不匹配", wrong.get("error"));
    }

    @Test
    @DisplayName("R-4: GET /api/werewolf/keys 主持人分发端点 —— 全员 roleKey 一览")
    void keysEndpointListsAllPlayerKeys() {
        WerewolfService svc = newService();
        WerewolfController ctl = new WerewolfController(svc);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("players", sixPlayers());
        body.put("roles", sixRoles());
        ResponseEntity<Map<String, Object>> initResp = ctl.init("F", "", body);
        String sid = (String) initResp.getBody().get("session_id");

        ResponseEntity<Map<String, Object>> resp = ctl.getKeys(sid);
        assertEquals(200, resp.getStatusCode().value());
        assertEquals(sid, resp.getBody().get("session_id"));
        @SuppressWarnings("unchecked")
        Map<String, String> keys = (Map<String, String>) resp.getBody().get("player_keys");
        assertNotNull(keys);
        assertEquals(6, keys.size(), "全员 6 个 roleKey");
        assertEquals(svc.getRoleKey(sid, "F"), keys.get("F"), "keys 与 service 侧一致");

        // 缺 session_id → 报错（用无当前会话的新 controller：currentSessionId 未被 init 占用）
        WerewolfController fresh = new WerewolfController(svc);
        ResponseEntity<Map<String, Object>> noSid = fresh.getKeys("");
        assertEquals("缺少 session_id", noSid.getBody().get("error"));
    }
}
