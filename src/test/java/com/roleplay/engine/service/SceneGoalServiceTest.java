package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P-0810-09：场景目标生成结构校验 + 进展判定解析（单元测试，mock LLM）。
 *
 * <p>覆盖：① LLM 正常输出 → 目标集字段齐全（global_goal / 每角色 role_goals / player_goal，
 * 状态默认 NOT_STARTED）；② 自定义玩家目标优先；③ LLM 失败（空 map）→ 规则兜底结构完整零崩溃；
 * ④ LLM 缺角色 → 归一化补全全部角色；⑤ 判定解析状态机输入；⑥ 判定 LLM 失败 → 返回 null 静默。
 */
class SceneGoalServiceTest {

    private LLMClient mockLlm(Map<String, Object> callJsonResult) {
        LLMClient llm = mock(LLMClient.class);
        when(llm.callJson(anyString(), anyInt())).thenReturn(callJsonResult);
        return llm;
    }

    // ── ① 生成结构校验：字段齐全 ──

    @Test
    @DisplayName("① LLM 正常输出 → 目标集结构字段齐全（全局/每角色/玩家，状态 NOT_STARTED）")
    void llmFullJson_producesCompleteStructure() {
        Map<String, Object> llmOut = new LinkedHashMap<>();
        llmOut.put("global_goal", Map.of("desc", "庄园隐藏的真相逐渐浮出水面", "status", "NOT_STARTED"));
        Map<String, Object> roleGoals = new LinkedHashMap<>();
        roleGoals.put("小铃", Map.of("desc", "找到丢失的怀表", "status", "NOT_STARTED"));
        roleGoals.put("凯尔", Map.of("desc", "保守自己的秘密", "status", "NOT_STARTED"));
        llmOut.put("role_goals", roleGoals);
        llmOut.put("player_goal", Map.of("desc", "查明怀表的去向", "status", "NOT_STARTED"));

        SceneGoalService svc = new SceneGoalService(mockLlm(llmOut), null);
        Map<String, Object> goals = svc.generateGoals("夜晚的庄园", List.of("小铃", "凯尔"), null);

        // global_goal
        assertNotNull(goals.get("global_goal"), "global_goal 必须存在");
        Map<?, ?> global = (Map<?, ?>) goals.get("global_goal");
        assertTrue(!String.valueOf(global.get("desc")).isBlank(), "global_goal.desc 非空");
        assertEquals("NOT_STARTED", String.valueOf(global.get("status")));
        // role_goals —— 每个角色 1 条
        Map<?, ?> roles = (Map<?, ?>) goals.get("role_goals");
        assertEquals(2, roles.size(), "角色目标数 = 角色数");
        for (String role : List.of("小铃", "凯尔")) {
            Map<?, ?> entry = (Map<?, ?>) roles.get(role);
            assertNotNull(entry, role + " 必须有目标");
            assertTrue(!String.valueOf(entry.get("desc")).isBlank(), role + " desc 非空");
            assertEquals("NOT_STARTED", String.valueOf(entry.get("status")), role + " 初始状态未开始");
        }
        // player_goal
        Map<?, ?> player = (Map<?, ?>) goals.get("player_goal");
        assertNotNull(player, "player_goal 必须存在");
        assertTrue(!String.valueOf(player.get("desc")).isBlank(), "player_goal.desc 非空");
        assertEquals("NOT_STARTED", String.valueOf(player.get("status")));
    }

    // ── ② 自定义玩家目标优先 ──

    @Test
    @DisplayName("② init body 自定义 player_goal → 覆盖 LLM 输出")
    void customPlayerGoal_overridesLlm() {
        Map<String, Object> llmOut = new LinkedHashMap<>();
        llmOut.put("global_goal", Map.of("desc", "隐藏主线", "status", "NOT_STARTED"));
        llmOut.put("role_goals", Map.of("小铃", Map.of("desc", "找到怀表", "status", "NOT_STARTED")));
        llmOut.put("player_goal", Map.of("desc", "LLM 生成的玩家目标", "status", "NOT_STARTED"));

        SceneGoalService svc = new SceneGoalService(mockLlm(llmOut), null);
        Map<String, Object> goals = svc.generateGoals("夜晚的庄园", List.of("小铃"), "我的自定义目标：找回传家宝");

        Map<?, ?> player = (Map<?, ?>) goals.get("player_goal");
        assertEquals("我的自定义目标：找回传家宝", String.valueOf(player.get("desc")));
        assertEquals("NOT_STARTED", String.valueOf(player.get("status")));
    }

    // ── ③ LLM 失败 → 规则兜底结构完整 ──

    @Test
    @DisplayName("③ LLM 失败（空输出）→ 规则兜底，目标集结构完整零崩溃")
    void llmFailure_fallsBackToDefaults() {
        SceneGoalService svc = new SceneGoalService(mockLlm(Map.of()), null);
        Map<String, Object> goals = svc.generateGoals("夜晚的庄园", List.of("小铃", "凯尔"), null);

        Map<?, ?> global = (Map<?, ?>) goals.get("global_goal");
        assertTrue(!String.valueOf(global.get("desc")).isBlank());
        Map<?, ?> roles = (Map<?, ?>) goals.get("role_goals");
        assertEquals(2, roles.size(), "兜底也要覆盖全部角色");
        for (Object v : roles.values()) {
            assertTrue(!String.valueOf(((Map<?, ?>) v).get("desc")).isBlank(), "兜底角色 desc 非空");
        }
        Map<?, ?> player = (Map<?, ?>) goals.get("player_goal");
        assertTrue(!String.valueOf(player.get("desc")).isBlank(), "兜底玩家目标非空");
    }

    // ── ④ LLM 缺角色 → 归一化补全 ──

    @Test
    @DisplayName("④ LLM 只给部分角色目标 → 归一化补全全部角色（缺哪个补哪个）")
    void llmMissingRoles_normalizeFillsAll() {
        Map<String, Object> llmOut = new LinkedHashMap<>();
        llmOut.put("global_goal", Map.of("desc", "隐藏主线", "status", "NOT_STARTED"));
        llmOut.put("role_goals", Map.of("小铃", Map.of("desc", "找到怀表", "status", "NOT_STARTED")));
        llmOut.put("player_goal", Map.of("desc", "查明真相", "status", "NOT_STARTED"));

        SceneGoalService svc = new SceneGoalService(mockLlm(llmOut), null);
        Map<String, Object> goals = svc.generateGoals("夜晚的庄园", List.of("小铃", "凯尔"), null);

        Map<?, ?> roles = (Map<?, ?>) goals.get("role_goals");
        assertEquals(2, roles.size(), "归一化必须补全缺失角色");
        assertEquals("找到怀表", String.valueOf(((Map<?, ?>) roles.get("小铃")).get("desc")), "LLM 已给的角色保留");
        assertTrue(!String.valueOf(((Map<?, ?>) roles.get("凯尔")).get("desc")).isBlank(), "缺失角色用兜底填充");
    }

    // ── ⑤ 判定解析 ──

    @Test
    @DisplayName("⑤ 判定 LLM 输出 → JudgeResult 状态解析正确")
    void judgeGoals_parsesStatuses() {
        Map<String, Object> judgeOut = new LinkedHashMap<>();
        judgeOut.put("role_goals", Map.of("小铃", "COMPLETED", "凯尔", "IN_PROGRESS"));
        judgeOut.put("global_goal", "IN_PROGRESS");
        judgeOut.put("player_goal", "NOT_STARTED");

        SceneGoalService svc = new SceneGoalService(mockLlm(judgeOut), null);
        Map<String, Object> goals = svc.generateGoals("夜晚的庄园", List.of("小铃", "凯尔"), null);
        SceneGoalService.JudgeResult r = svc.judgeGoals(goals, "小铃：我找到了怀表");

        assertNotNull(r, "判定应返回结果");
        assertEquals("COMPLETED", r.roleStatuses().get("小铃"));
        assertEquals("IN_PROGRESS", r.roleStatuses().get("凯尔"));
        assertEquals("IN_PROGRESS", r.globalStatus());
        assertEquals("NOT_STARTED", r.playerStatus());
    }

    // ── ⑥ 判定 LLM 失败 → 静默返回 null ──

    @Test
    @DisplayName("⑥ 判定 LLM 失败（空输出）→ 返回 null（调用方静默跳过不广播）")
    void judgeGoals_llmFailure_returnsNull() {
        SceneGoalService svc = new SceneGoalService(mockLlm(Map.of()), null);
        Map<String, Object> goals = svc.generateGoals("夜晚的庄园", List.of("小铃"), null);
        assertNull(svc.judgeGoals(goals, "任意对话"), "LLM 失败应返回 null 静默降级");
    }
}
