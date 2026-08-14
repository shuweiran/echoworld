package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.HearingSystem;

import java.util.*;

public class ModeClassifier {

    /** 组内任意成员对距离上限（px）：听力连通分量是传递闭包，链式可听（每跳 80-130px）会把
     *  相距 400px+ 的成员拉进同一组——本阈值做组内空间直径校验（调研报告-移动与分组问题.md
     *  2.4 #1，默认 250-300px 区间取 300）。原 CLOSE_THRESHOLD=150 定义为近距分组阈值但从未接线
     *  （B14），本次改造接线并调整到报告建议区间。 */
    static final double MAX_GROUP_DIAMETER = 300.0;

    public List<GroupCandidate> classify(List<HearingSystem.HearingResult> hearing,
                                          Map<String, AgentState> allStates) {
        List<GroupCandidate> groups = new ArrayList<>();
        Set<String> assigned = new HashSet<>();

        List<HearingSystem.HearingResult> audible = new ArrayList<>();
        for (HearingSystem.HearingResult h : hearing) {
            if (h.canHear()) audible.add(h);
        }

        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (HearingSystem.HearingResult h : audible) {
            adjacency.computeIfAbsent(h.speakerName(), k -> new ArrayList<>()).add(h.listenerName());
            adjacency.computeIfAbsent(h.listenerName(), k -> new ArrayList<>()).add(h.speakerName());
        }

        Set<Set<String>> components = findConnectedComponents(adjacency, allStates);

        for (Set<String> component : components) {
            boolean anyAssigned = false;
            for (String name : component) { if (assigned.contains(name)) { anyAssigned = true; break; } }
            if (anyAssigned) continue;

            List<AgentState> members = new ArrayList<>();
            for (String name : component) {
                AgentState s = allStates.get(name);
                if (s != null && !s.isInConversation()) members.add(s);
            }
            if (members.size() < 2) continue;

            // 组内空间直径校验：任意成员对距离 < MAX_GROUP_DIAMETER（px）——
            // 禁止听力链式传递（A↔B↔C）导致相距 400px+ 的成员同组（超距不允许成组）。
            if (!withinDiameter(members)) continue;

            if (!willingToTalk(members)) continue;

            ConversationMode mode = determineMode(members, allStates);
            groups.add(new GroupCandidate(members, mode));
            for (AgentState m : members) assigned.add(m.getAgentName());
        }

        return groups;
    }

    private Set<Set<String>> findConnectedComponents(Map<String, List<String>> adjacency,
                                                       Map<String, AgentState> allStates) {
        Set<Set<String>> components = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();

        for (String start : adjacency.keySet()) {
            if (visited.contains(start)) continue;
            if (allStates.get(start) != null && allStates.get(start).isInConversation()) continue;

            Set<String> component = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                if (!visited.add(cur)) continue;
                if (allStates.get(cur) != null && allStates.get(cur).isInConversation()) continue;
                component.add(cur);
                for (String neighbor : adjacency.getOrDefault(cur, List.of())) {
                    if (!visited.contains(neighbor)) queue.add(neighbor);
                }
            }
            if (component.size() >= 2) components.add(component);
        }
        return components;
    }

    private ConversationMode determineMode(List<AgentState> members, Map<String, AgentState> allStates) {
        int angryCount = 0;
        int forCount = 0, againstCount = 0;
        for (AgentState s : members) {
            if (s.getEmotion() == Emotion.ANGRY) angryCount++;
            if (s.getStance() == AgentState.Stance.FOR) forCount++;
            if (s.getStance() == AgentState.Stance.AGAINST) againstCount++;
        }

        if (angryCount >= 2 && members.size() >= 3 && (forCount > 0 && againstCount > 0)) {
            return ConversationMode.DEBATE;
        }

        if (members.size() == 1 && wouldOthersListen(members.get(0), allStates)) {
            return ConversationMode.PUBLIC_SPEAKING;
        }

        if (members.size() == 2) {
            return ConversationMode.DYAD;
        }

        if (members.size() >= 3) {
            return ConversationMode.GROUP_DISCUSSION;
        }

        return ConversationMode.DYAD;
    }

    /**
     * 演讲听众判定（演讲/广播合并地基复用点）：speaker 周围 2.5×hearRange 内、
     * 距离 &gt; 50 的未入群角色 ≥2 → 有人听 → 该发言应走「演讲」（区域广播）。
     * 公开供 SimulationService 的 AI 自动选择调用（原 private）。
     */
    public boolean wouldOthersListen(AgentState speaker, Map<String, AgentState> allStates) {
        int listeners = 0;
        for (AgentState other : allStates.values()) {
            if (other == speaker || other.isInConversation()) continue;
            double dist = speaker.distanceTo(other);
            if (dist < speaker.getHearRange() * 2.5 && dist > 50) {
                listeners++;
            }
        }
        return listeners >= 2;
    }

    /** 组内任意成员对距离 < {@link #MAX_GROUP_DIAMETER}（px）。超距 → 不成组。 */
    private boolean withinDiameter(List<AgentState> members) {
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                if (members.get(i).distanceTo(members.get(j)) >= MAX_GROUP_DIAMETER) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean willingToTalk(List<AgentState> members) {
        int angryPairs = 0;
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                if (members.get(i).getEmotion() == Emotion.ANGRY
                        && members.get(j).getEmotion() == Emotion.ANGRY) {
                    angryPairs++;
                }
            }
        }
        return angryPairs < members.size();
    }

    public record GroupCandidate(List<AgentState> members, ConversationMode mode) {
        public String groupId() {
            List<String> names = members.stream().map(AgentState::getAgentName).sorted().toList();
            return String.join("+", names);
        }
    }
}
