package com.roleplay.engine.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 狼人杀 AI 行动器（P-0802-F，调研报告 G0-2）—— 纯规则零 LLM（对齐 D-002）：
 * <ul>
 *   <li>夜间：狼人共刀（首个行动的 AI 狼决定目标，其余跟随同一目标）、预言家查验随机存活、
 *       女巫首夜救狼刀目标（被刀者尚存活且非自己）/ 后续夜按概率毒随机存活（不毒自己）</li>
 *   <li>猎人反杀：被淘汰的 AI 猎人随机带走一名存活玩家</li>
 *   <li>白天投票：AI 村民随机投非己、AI 狼人共投同一非狼目标（狼队协同）</li>
 * </ul>
 *
 * <p>纯确定性组件：随机源可种子固定（测试确定性）；女巫毒药概率等参数由调用方传入，
 * 对齐 D-004「阈值勿 hardcode」纪律。
 */
public class WerewolfAiPlanner {

    private final Random random;

    public WerewolfAiPlanner() {
        this(System.nanoTime());
    }

    public WerewolfAiPlanner(long seed) {
        this.random = new Random(seed);
    }

    /**
     * 夜间 AI 行动编排：狼刀 → 预言家验 → 女巫救/毒（顺序固定，保证女巫首夜能看到狼刀目标）。
     * 仅决策 AI 角色（不在 {@code humans} 中的存活玩家）；人类角色的行动等待其通过 night_action 提交。
     *
     * @return 已执行行动的描述消息列表（供日志/调试）
     */
    public List<String> planNight(WerewolfService.GameState g, Set<String> humans, double poisonProbability) {
        List<String> msgs = new ArrayList<>();

        // 1) 狼人共刀：首个行动的 AI 狼决定目标（排除狼队友），其余狼自动跟随（不重复决策）
        if (!g.nightDecisions.contains("kill")) {
            for (String p : g.alive) {
                if (humans.contains(p)) continue;
                if (g.roles.get(p) != WerewolfService.Role.WEREWOLF) continue;
                String target = pickAliveOther(g, p, WerewolfService.Role.WEREWOLF);
                if (!target.isEmpty()) {
                    g.wolfTarget = target;
                    g.nightDecisions.add("kill");
                    msgs.add("狼人 " + p + " 选择击杀 " + target);
                }
                break;
            }
        }

        // 2) 预言家查验：随机查一名存活玩家（可查狼）
        if (!g.nightDecisions.contains("check")) {
            for (String p : g.alive) {
                if (humans.contains(p)) continue;
                if (g.roles.get(p) != WerewolfService.Role.SEER) continue;
                String target = pickAliveOther(g, p, null);
                if (!target.isEmpty()) {
                    g.seerTarget = target;
                    g.seerResult = g.roles.get(target).name().toLowerCase();
                    g.nightDecisions.add("check");
                    msgs.add("预言家 " + p + " 查验 " + target + "=" + g.seerResult);
                }
                break;
            }
        }

        // 3) 女巫解药：首夜救狼刀目标（经典规则女巫获知被刀者；若被刀者为自己则自救）；
        //    后续夜保留解药不使用（决策完成即放行，不一定消耗）。
        if (!g.nightDecisions.contains("save")) {
            for (String p : g.alive) {
                if (humans.contains(p)) continue;
                if (g.roles.get(p) != WerewolfService.Role.WITCH) continue;
                if (g.round == 1 && !g.witchUsedAntidote
                        && !g.wolfTarget.isEmpty() && g.alive.contains(g.wolfTarget)) {
                    g.witchSaveTarget = g.wolfTarget;
                    g.witchUsedAntidote = true;
                    msgs.add("女巫 " + p + " 使用解药救 " + g.wolfTarget);
                }
                g.nightDecisions.add("save");
                break;
            }
        }

        // 4) 女巫毒药：后续夜（round≥2）按概率毒随机存活（非自己；女巫不知狼，纯随机）。
        if (!g.nightDecisions.contains("poison")) {
            for (String p : g.alive) {
                if (humans.contains(p)) continue;
                if (g.roles.get(p) != WerewolfService.Role.WITCH) continue;
                if (g.round >= 2 && !g.witchUsedPoison && random.nextDouble() < poisonProbability) {
                    String target = pickAliveOther(g, p, null);
                    if (!target.isEmpty()) {
                        g.witchPoisonTarget = target;
                        g.witchUsedPoison = true;
                        msgs.add("女巫 " + p + " 使用毒药毒 " + target);
                    }
                }
                g.nightDecisions.add("poison");
                break;
            }
        }

        return msgs;
    }

    /** 猎人反杀目标：随机一名存活玩家（非自己）；无存活目标返回空串（不开枪）。 */
    public String planHunterShoot(WerewolfService.GameState g, String hunter) {
        // 注意：猎人此刻已死亡（被淘汰后才开枪），不检查猎人自身存活；仅需存在非己存活目标
        return pickAliveOther(g, hunter, null);
    }

    /**
     * 白天投票编排：AI 狼人共投同一非狼目标（狼队协同投票，防止互投散票），
     * AI 村民随机投非己。已投过票（g.votes 含该玩家）的不重复决策。
     *
     * @return voter → target 的新增票映射（未直接写入 g.votes，由调用方落票）
     */
    public Map<String, String> planVotes(WerewolfService.GameState g, Set<String> humans) {
        Map<String, String> votes = new LinkedHashMap<>();
        List<String> wolves = g.alive.stream()
                .filter(p -> g.roles.get(p) == WerewolfService.Role.WEREWOLF)
                .toList();
        String wolfVote = "";
        if (!wolves.isEmpty()) {
            String anyWolf = wolves.get(0);
            if (!humans.contains(anyWolf)) {
                wolfVote = pickAliveOther(g, anyWolf, WerewolfService.Role.WEREWOLF);
            } else {
                // 狼队含人类：以人类狼的已投票面为准（若已投），否则 AI 狼等人类先投
                wolfVote = g.votes.get(anyWolf);
            }
        }
        for (String p : g.alive) {
            if (humans.contains(p)) continue;
            if (g.votes.containsKey(p)) continue;
            if (g.roles.get(p) == WerewolfService.Role.WEREWOLF) {
                if (wolfVote != null && !wolfVote.isEmpty()) votes.put(p, wolfVote);
            } else {
                String t = pickAliveOther(g, p, null);
                if (!t.isEmpty()) votes.put(p, t);
            }
        }
        return votes;
    }

    /** 随机选一名存活玩家：非自己；excludeRole 非空时排除该角色（如狼不刀狼）。无候选返回空串。 */
    private String pickAliveOther(WerewolfService.GameState g, String self, WerewolfService.Role excludeRole) {
        List<String> candidates = g.alive.stream()
                .filter(p -> !p.equals(self))
                .filter(p -> excludeRole == null || g.roles.get(p) != excludeRole)
                .toList();
        if (candidates.isEmpty()) return "";
        return candidates.get(random.nextInt(candidates.size()));
    }
}
