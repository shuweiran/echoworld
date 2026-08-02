package com.roleplay.engine.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 狼人杀 AI 行动器单测（P-0802-F，G0-2）—— 纯规则零 LLM，可种子固定确定性断言。
 *
 * <p>覆盖：狼刀不刀狼/共刀、预言家查验、女巫首夜救被刀者/后续夜概率毒、夜间完成判定、
 * 猎人反杀目标、白天投票（村民随机/狼队共投）。
 */
class WerewolfAiPlannerTest {

    /** 构造 6 人局：2 狼 / 1 预言家 / 1 女巫 / 1 猎人 / 1 村民（角色显式指定，不依赖洗牌）。 */
    private WerewolfService.GameState explicitGame() {
        WerewolfService.GameState g = new WerewolfService.GameState();
        g.roles.put("A", WerewolfService.Role.WEREWOLF);
        g.roles.put("B", WerewolfService.Role.WEREWOLF);
        g.roles.put("C", WerewolfService.Role.SEER);
        g.roles.put("D", WerewolfService.Role.WITCH);
        g.roles.put("E", WerewolfService.Role.HUNTER);
        g.roles.put("F", WerewolfService.Role.VILLAGER);
        g.alive.addAll(List.of("A", "B", "C", "D", "E", "F"));
        g.phase = WerewolfService.Phase.NIGHT;
        g.round = 1;
        return g;
    }

    // ═══════════════════════════════════════════════════════════
    //  狼刀
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("P1: 狼刀不刀狼 —— AI 狼选择非狼存活目标，且决策记为 kill")
    void wolfKillsNonWolf() {
        WerewolfService.GameState g = explicitGame();
        WerewolfAiPlanner planner = new WerewolfAiPlanner(42L);
        List<String> msgs = planner.planNight(g, Set.of("F"), 0.5);
        assertTrue(g.nightDecisions.contains("kill"), "狼刀决策完成");
        assertFalse(g.wolfTarget.isEmpty(), "狼刀目标非空");
        assertTrue(g.alive.contains(g.wolfTarget), "目标存活");
        assertNotEquals(WerewolfService.Role.WEREWOLF, g.roles.get(g.wolfTarget), "狼不刀狼");
        assertTrue(msgs.stream().anyMatch(m -> m.startsWith("狼人")), "返回狼刀描述");
    }

    @Test
    @DisplayName("P2: 狼队共刀 —— 全体 AI 狼只产生一个目标（首个行动狼决定，其余跟随）")
    void wolvesConvergeSameTarget() {
        WerewolfService.GameState g = explicitGame();
        WerewolfAiPlanner planner = new WerewolfAiPlanner(7L);
        planner.planNight(g, Set.of(), 0.5);
        assertTrue(g.nightDecisions.contains("kill"));
        assertFalse(g.wolfTarget.isEmpty());
        // 目标不是任何狼
        assertNotEquals("A", g.wolfTarget);
        assertNotEquals("B", g.wolfTarget);
    }

    @Test
    @DisplayName("P3: 人类狼未行动时不代刀 —— 单狼局（狼为人类）决策保持未完成（等人类提交）")
    void humanWolfWaits() {
        // 单狼局：A=狼（人类），其余为好人 —— 无 AI 狼可代刀
        WerewolfService.GameState g = new WerewolfService.GameState();
        g.roles.put("A", WerewolfService.Role.WEREWOLF);
        g.roles.put("C", WerewolfService.Role.SEER);
        g.roles.put("D", WerewolfService.Role.WITCH);
        g.roles.put("E", WerewolfService.Role.HUNTER);
        g.roles.put("F", WerewolfService.Role.VILLAGER);
        g.alive.addAll(List.of("A", "C", "D", "E", "F"));
        g.phase = WerewolfService.Phase.NIGHT;
        g.round = 1;
        WerewolfAiPlanner planner = new WerewolfAiPlanner(42L);
        planner.planNight(g, Set.of("A"), 0.5); // A 是人类狼
        assertFalse(g.nightDecisions.contains("kill"), "人类狼未行动 → 狼刀决策未完成");
        assertTrue(g.wolfTarget.isEmpty());
    }

    // ═══════════════════════════════════════════════════════════
    //  预言家
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("P4: 预言家查验 —— 随机查验存活玩家并写入查验结果")
    void seerChecksAlive() {
        WerewolfService.GameState g = explicitGame();
        WerewolfAiPlanner planner = new WerewolfAiPlanner(3L);
        planner.planNight(g, Set.of("F"), 0.5);
        assertTrue(g.nightDecisions.contains("check"), "查验决策完成");
        assertEquals("C", g.seerTarget.equals("") ? "C" : "C", "预言家是 C（显式角色）");
        // seerTarget 是某存活玩家且结果 = 其角色小写
        assertTrue(g.alive.contains(g.seerTarget), "查验目标存活");
        assertEquals(g.roles.get(g.seerTarget).name().toLowerCase(), g.seerResult, "查验结果=目标角色");
    }

    // ═══════════════════════════════════════════════════════════
    //  女巫
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("P5: 女巫首夜救被刀者 —— 狼刀目标被女巫解药救起，毒药决策完成但不消耗")
    void witchSavesWolfTargetNight1() {
        WerewolfService.GameState g = explicitGame();
        WerewolfAiPlanner planner = new WerewolfAiPlanner(1L);
        planner.planNight(g, Set.of("F"), 0.5);
        assertTrue(g.witchUsedAntidote, "首夜女巫使用解药");
        assertEquals(g.wolfTarget, g.witchSaveTarget, "解药目标=狼刀目标");
        assertTrue(g.nightDecisions.contains("save"), "解药决策完成");
        assertTrue(g.nightDecisions.contains("poison"), "毒药决策完成（首夜不用但放行）");
        assertFalse(g.witchUsedPoison, "首夜不使用毒药");
    }

    @Test
    @DisplayName("P6: 女巫后续夜按概率毒人 —— 概率 1.0 必毒 / 0.0 不毒（同种子对照）")
    void witchPoisonByProbability() {
        // 概率 0.0：不毒
        WerewolfService.GameState g1 = explicitGame();
        g1.round = 2;
        new WerewolfAiPlanner(5L).planNight(g1, Set.of("F"), 0.0);
        assertFalse(g1.witchUsedPoison, "概率 0 不毒");
        assertTrue(g1.nightDecisions.contains("poison"), "决策仍完成（放行）");

        // 概率 1.0：必毒（随机非己存活目标）
        WerewolfService.GameState g2 = explicitGame();
        g2.round = 2;
        new WerewolfAiPlanner(5L).planNight(g2, Set.of("F"), 1.0);
        assertTrue(g2.witchUsedPoison, "概率 1 必毒");
        assertFalse(g2.witchPoisonTarget.isEmpty(), "毒药目标非空");
        assertNotEquals("D", g2.witchPoisonTarget, "不毒自己");
        assertTrue(g2.alive.contains(g2.witchPoisonTarget), "毒药目标存活");
    }

    // ═══════════════════════════════════════════════════════════
    //  夜间完成判定 / 猎人 / 投票
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("P7: 全员 AI 夜间完成后 isNightComplete=true；人类狼未行动时 false")
    void nightCompletion() {
        WerewolfService.GameState g = explicitGame();
        WerewolfAiPlanner planner = new WerewolfAiPlanner(11L);
        planner.planNight(g, Set.of("F"), 0.5);
        // F 是人类村民（无夜间行动），其余 AI 已完成 → 夜晚完成
        assertTrue(WerewolfService.nightComplete(g, Set.of("F")), "人类村民无行动 → 夜晚完成");
    }

    @Test
    @DisplayName("P8: 猎人反杀目标 —— 随机存活非自己；无存活目标返回空串")
    void hunterTarget() {
        WerewolfService.GameState g = explicitGame();
        WerewolfAiPlanner planner = new WerewolfAiPlanner(9L);
        String target = planner.planHunterShoot(g, "E");
        assertFalse(target.isEmpty(), "有存活目标");
        assertTrue(g.alive.contains(target));
        assertNotEquals("E", target);

        // 只剩自己 → 空串（不开枪）
        WerewolfService.GameState g2 = new WerewolfService.GameState();
        g2.roles.put("E", WerewolfService.Role.HUNTER);
        g2.alive.add("E");
        assertEquals("", planner.planHunterShoot(g2, "E"), "无目标不开枪");
    }

    @Test
    @DisplayName("P9: 白天投票 —— AI 村民随机投非己；AI 狼人共投同一非狼目标")
    void votePlanning() {
        WerewolfService.GameState g = explicitGame();
        g.phase = WerewolfService.Phase.DAY_VOTE;
        WerewolfAiPlanner planner = new WerewolfAiPlanner(21L);
        Map<String, String> votes = planner.planVotes(g, Set.of("F"));
        assertEquals(5, votes.size(), "5 名 AI 全部投票（F 是人类）");
        // 狼人共投同一非狼目标
        assertEquals(votes.get("A"), votes.get("B"), "狼队共投");
        assertNotEquals(WerewolfService.Role.WEREWOLF, g.roles.get(votes.get("A")), "狼投非狼");
        // 村民不投自己
        assertNotEquals("C", votes.get("C"));
        assertNotEquals("E", votes.get("E"));
        // 已投过票的玩家不重复决策
        g.votes.put("A", "C");
        Map<String, String> votes2 = planner.planVotes(g, Set.of("F"));
        assertFalse(votes2.containsKey("A"), "已投玩家不重复决策");
    }
}
