package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.Emotion;
import com.roleplay.engine.simulation.HearingSystem;

import java.util.*;

public class ModeClassifier {

    private static final double CLOSE_THRESHOLD = 150.0;

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

    private boolean wouldOthersListen(AgentState speaker, Map<String, AgentState> allStates) {
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
