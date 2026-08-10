package com.roleplay.engine.controller;

import com.roleplay.engine.core.PersonaCardLoader;
import com.roleplay.engine.db.service.DatabaseService;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.service.GeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-0811-D：旧角色批量升级（POST /api/characters/upgrade）测试（mock LLM，不触发真实 LLM 批量）。
 *
 * <p>覆盖：① 无卡角色升级（五层卡写盘 + personaCards 挂载 + 表层替换 + H2 四字段更新）；
 * ② 有卡跳过（幂等，不调 LLM）；③ 单角色失败跳过继续 + 汇总 {upgraded, skipped, failed, names[]}；
 * ④ max_roles 限制升级数；⑤ started 响应形状 + status 端点。
 *
 * <p>注意：测试角色名避开 classpath 默认卡名（小铃/凯尔/露娜及其 id 别名），否则 hasCardForUpgrade
 * 会把它们误判为「已有卡」而跳过。
 */
class CharacterUpgradeTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetLoader() {
        PersonaCardLoader.resetForTests();
    }

    // ── helpers ──────────────────────────────────────────────────

    /** 五层 persona 生成输出（mock LLM 返回值 = GeneratorService 输出形状：表层 + 五层键）。 */
    private static Map<String, Object> fiveLayerOutput() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", "生成名(升级时忽略)");
        out.put("appearance", "升级后外观");
        out.put("summary", "升级后摘要");
        out.put("persona", "升级后的人格设定（全新行为指令）");
        out.put("voice", "升级后说话风格");
        out.put("background", "升级后背景故事");
        out.put("contrast", Map.of("surface", "表面X", "actual", "实际Y"));
        out.put("humanDetails", List.of("小习惯：转笔"));
        out.put("layer0", List.of("升级铁律一：以新行为规则为准。"));
        out.put("layer1", Map.of("identity", "升级后身份"));
        out.put("layer2", Map.of("sentenceStyle", "短句"));
        out.put("layer3", Map.of("care", "行动表达"));
        out.put("layer4", Map.of("triggers", List.of("升级触发点")));
        return out;
    }

    /** mock LLM：prompt 含角色名 failName 时抛异常（模拟单角色生成失败），其余返回五层输出。 */
    private static LLMClient mockLlmWithFailure(String failName) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            if (failName != null && prompt.contains("角色「" + failName + "」")) {
                throw new RuntimeException("mock LLM 生成失败（模拟）");
            }
            return fiveLayerOutput();
        });
        return llm;
    }

    private static CharacterController newController(LLMClient llm, DatabaseService db, String dir) {
        CharacterController cc = new CharacterController(new GeneratorService(llm), db);
        if (dir != null) cc.setPersonaCardsDir(dir);
        return cc;
    }

    /** 建角色（create 端点，内存列表 + mock DB 双写）。 */
    private static void seed(CharacterController cc, String name, String persona, String voice, String bg) {
        ResponseEntity<?> resp = cc.create(Map.of("name", name, "persona", persona, "voice", voice, "background", bg));
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "seed 角色 " + name);
    }

    /** 轮询等待升级完成（虚拟线程异步），超时抛错。 */
    private static Map<String, Object> waitForUpgrade(CharacterController cc, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Map<String, Object> st;
        do {
            st = cc.upgradeStatus().getBody();
            if (st != null && !Boolean.TRUE.equals(st.get("running"))) return st;
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("upgrade 超时未完成: " + st);
    }

    // ── ① 无卡角色升级 ──────────────────────────────────────────

    @Test
    @DisplayName("① 无卡角色升级：卡写盘 + 挂载 + 表层替换（H2 四字段 + 内存列表）+ 汇总")
    void upgradeNoCardCharacter() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        CharacterController cc = newController(mockLlmWithFailure(null), db, tempDir.toString());
        seed(cc, "张三", "旧人格", "旧嗓音", "旧背景");

        // started 立即返回
        ResponseEntity<Map<String, Object>> resp = cc.upgrade(Map.of());
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("started"), "响应立即返回 started=true");

        Map<String, Object> status = waitForUpgrade(cc, 5000);
        assertEquals(1, status.get("upgraded"));
        assertEquals(0, status.get("skipped"));
        assertEquals(0, status.get("failed"));
        assertEquals(List.of("张三"), status.get("names"));
        assertEquals(false, status.get("running"));

        // 五层卡挂载 + 写盘
        assertNotNull(cc.personaCardFor("张三"), "升级后 personaCards 挂载");
        Path file = tempDir.resolve("张三.json");
        assertTrue(Files.exists(file), "升级卡落盘: " + file);
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("升级铁律一"), "卡文件含层内容");

        // 表层替换：H2 四字段用生成结果覆盖（升级路径显式「替换」，区别于常规 attach 不覆盖）
        verify(db).saveCharacter("张三", "升级后的人格设定（全新行为指令）", "升级后说话风格", "升级后背景故事");
        // 内存列表同步替换
        Map<String, Object> listed = cc.getAll().stream()
                .filter(x -> "张三".equals(x.get("name"))).findFirst().orElseThrow();
        assertEquals("升级后的人格设定（全新行为指令）", listed.get("persona"), "内存列表 persona 已替换");
        assertEquals("升级后说话风格", listed.get("voice"));
        assertEquals("升级后背景故事", listed.get("background"));
        assertEquals("升级后外观", listed.get("appearance"), "list 附加表层 appearance");
        assertFalse(listed.containsKey("layer0"), "list 不透出五层");

        // 幂等：再跑一次 → 有卡跳过
        ResponseEntity<Map<String, Object>> again = cc.upgrade(Map.of());
        assertEquals(true, again.getBody().get("started"));
        Map<String, Object> status2 = waitForUpgrade(cc, 5000);
        assertEquals(0, status2.get("upgraded"));
        assertEquals(1, status2.get("skipped"), "有卡角色第二次升级被跳过");
    }

    // ── ② 有卡跳过（幂等） ───────────────────────────────────────

    @Test
    @DisplayName("② 有卡角色跳过：导入卡角色不升级，不调 LLM")
    void upgradeSkipsCardedCharacters() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        LLMClient llm = mockLlmWithFailure(null);
        CharacterController cc = newController(llm, db, tempDir.toString());
        seed(cc, "李四", "P", "V", "B");
        // 导入五层卡 → 有卡
        ResponseEntity<?> imp = cc.importPersonaCard("李四", Map.of("layer0", List.of("已有规则")));
        assertEquals(HttpStatus.OK, imp.getStatusCode());

        cc.upgrade(Map.of());
        Map<String, Object> status = waitForUpgrade(cc, 5000);
        assertEquals(0, status.get("upgraded"));
        assertEquals(1, status.get("skipped"), "有卡角色跳过");
        assertEquals(0, status.get("failed"));

        // 未调用 LLM 生成（有卡直接跳过）；卡不被覆盖
        verify(llm, org.mockito.Mockito.never()).callJson(anyString(), any());        assertEquals(List.of("已有规则"), cc.personaCardFor("李四").get("layer0"), "已有卡不被覆盖");
    }

    // ── ③ 单角色失败跳过 + 汇总 ──────────────────────────────────

    @Test
    @DisplayName("③ 混合场景：无卡升级 + 有卡跳过 + 单角色失败跳过，汇总 {upgraded, skipped, failed, names}")
    void upgradeMixedUpgradedSkippedFailed() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        CharacterController cc = newController(mockLlmWithFailure("王五"), db, tempDir.toString());
        seed(cc, "赵六", "P1", "V1", "B1");       // 无卡 → 升级成功
        seed(cc, "钱七", "P2", "V2", "B2");       // 导入卡 → 跳过
        seed(cc, "王五", "P3", "V3", "B3");       // 无卡但 LLM 失败 → failed
        cc.importPersonaCard("钱七", Map.of("layer0", List.of("已有规则")));

        cc.upgrade(Map.of());
        Map<String, Object> status = waitForUpgrade(cc, 5000);
        assertEquals(1, status.get("upgraded"));
        assertEquals(1, status.get("skipped"));
        assertEquals(1, status.get("failed"));
        assertEquals(List.of("赵六"), status.get("names"), "names 只列升级成功的");
        assertEquals(false, status.get("running"));

        // 赵六升级成功（卡挂载），王五失败无卡，钱七导入卡保留
        assertNotNull(cc.personaCardFor("赵六"));
        assertNull(cc.personaCardFor("王五"), "失败角色不挂卡");
        assertNotNull(cc.personaCardFor("钱七"), "导入卡保留");
    }

    // ── ④ max_roles 限制 ────────────────────────────────────────

    @Test
    @DisplayName("④ max_roles 限制升级数：最多升级 N 个，其余不处理")
    void upgradeMaxRolesLimit() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        CharacterController cc = newController(mockLlmWithFailure(null), db, tempDir.toString());
        seed(cc, "角色甲", "P", "V", "B");
        seed(cc, "角色乙", "P", "V", "B");
        seed(cc, "角色丙", "P", "V", "B");

        cc.upgrade(Map.of("max_roles", 2));
        Map<String, Object> status = waitForUpgrade(cc, 5000);
        assertEquals(2, status.get("upgraded"), "max_roles=2 只升级 2 个");
        assertEquals(List.of("角色甲", "角色乙"), status.get("names"));
        assertNotNull(cc.personaCardFor("角色甲"));
        assertNotNull(cc.personaCardFor("角色乙"));
        assertNull(cc.personaCardFor("角色丙"), "第三个未被处理（无卡且未升级）");
    }

    // ── ⑤ status 端点形状 ───────────────────────────────────────

    @Test
    @DisplayName("⑤ status 端点：未跑过返回空态不炸；跑完含 upgraded/skipped/failed/names/running")
    void statusEndpointShapes() {
        DatabaseService db = mock(DatabaseService.class);
        CharacterController cc = newController(mockLlmWithFailure(null), db, tempDir.toString());
        // 未跑过 → 空态
        Map<String, Object> idle = cc.upgradeStatus().getBody();
        assertNotNull(idle);
        assertFalse(Boolean.TRUE.equals(idle.get("running")), "空态 running 不为 true");
    }
}
