package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.agent.Agent;
import com.roleplay.engine.llm.LLMClient;
import com.roleplay.engine.simulation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;

@Component
public class ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);

    private static final long GROUP_IDLE_TIMEOUT_MS = 30_000;
    private static final long CONVERSATION_COOLDOWN_MS = 5_000;

    private final Map<ConversationMode, ConversationStrategy> strategies = new EnumMap<>(ConversationMode.class);
    private final Map<String, ConversationGroup> activeGroups = new ConcurrentHashMap<>();
    private final Map<String, Long> recentPairCooldowns = new ConcurrentHashMap<>();
    private final Map<String, TopicManager> groupTopicManagers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private SimulationWorld world;
    private LLMClient llmClient;

    public ConversationManager() {}

    public void init(SimulationWorld world, LLMClient llmClient,
                     java.util.function.Function<String, Agent> agentLookup,
                     java.util.function.Supplier<String> narrationSupplier) {
        this.world = world;
        this.llmClient = llmClient;

        java.util.function.Function<String, String> narFn = s -> narrationSupplier.get();
        java.util.function.BiConsumer<String, String> arbCb = (groupId, report) -> {
            world.setUserDirective("[仲裁] " + report);
        };

        strategies.put(ConversationMode.DYAD,
                new DyadStrategy(agentLookup, narFn));
        strategies.put(ConversationMode.GROUP_DISCUSSION,
                new GroupStrategy(agentLookup, narFn));
        strategies.put(ConversationMode.PUBLIC_SPEAKING,
                new SpeechStrategy(agentLookup, narFn, getOrCreateTopicManager("speech")));
        strategies.put(ConversationMode.DEBATE,
                new DebateStrategy(agentLookup, narFn, arbCb));
    }

    public TopicManager getOrCreateTopicManager(String groupId) {
        return groupTopicManagers.computeIfAbsent(groupId, k -> new TopicManager());
    }

    private static final String PLAYER_AGENT_NAME = "me";

    public void tick(long now) {
        List<AgentState> allStates = new ArrayList<>(world.getAllStates().values());
        if (allStates.size() < 2) return;

        List<HearingSystem.HearingResult> hearing =
                world.getHearingSystem().computeAudibility(allStates);

        Map<String, AgentState> available = new LinkedHashMap<>();
        for (AgentState s : allStates) {
            // Keep "me" out of auto-initiated conversations (proximity-based)
            // Only converse when user sends a message manually
            if (s.isPlayerControlled()) continue;
            if (!isBusy(s)) available.put(s.getAgentName(), s);
        }

        // If any player-controlled agent has a pending message, start a conversation with nearest agent
        for (AgentState player : allStates) {
            if (!player.isPlayerControlled()) continue;
            if (player.getCurrentMessage() == null || player.getCurrentMessage().isEmpty()
                    || player.getCurrentMessage().startsWith("(主控")) continue;
            AgentState nearest = null;
            double minDist = Double.MAX_VALUE;
            for (AgentState a : available.values()) {
                if (a.isPlayerControlled()) continue;
                double d = player.distanceTo(a);
                if (d < minDist) { minDist = d; nearest = a; }
            }
            if (nearest != null) {
                String gid = player.getAgentName() + "+" + nearest.getAgentName();
                if (!activeGroups.containsKey(gid)) {
                    startGroup(gid, ConversationMode.DYAD, List.of(player, nearest));
                }
            }
        }

        ModeClassifier classifier = new ModeClassifier();
        List<ModeClassifier.GroupCandidate> candidates = classifier.classify(hearing, available);

        for (ModeClassifier.GroupCandidate cand : candidates) {
            String gid = cand.groupId();
            if (activeGroups.containsKey(gid)) continue;

            String pairKey = makePairKey(cand.members());
            if (recentPairCooldowns.containsKey(pairKey)
                    && now - recentPairCooldowns.get(pairKey) < CONVERSATION_COOLDOWN_MS) {
                continue;
            }

            startGroup(gid, cand.mode(), cand.members());
        }

        List<String> toRemove = new ArrayList<>();
        for (ConversationGroup group : activeGroups.values()) {
            if (!group.isActive() || group.idleMs() > GROUP_IDLE_TIMEOUT_MS) {
                toRemove.add(group.getGroupId());
            }
        }
        for (String id : toRemove) {
            dissolveGroup(id);
        }
    }

    private boolean isBusy(AgentState s) {
        if (s.isInConversation()) return true;
        for (ConversationGroup g : activeGroups.values()) {
            if (g.containsAgent(s.getAgentName()) && g.isActive()) return true;
        }
        return false;
    }

    private void startGroup(String groupId, ConversationMode mode, List<AgentState> members) {
        ConversationStrategy strat = strategies.get(mode);
        if (strat == null) { strat = strategies.get(ConversationMode.DYAD); }
        final ConversationStrategy strategy = strat;
        final ConversationMode finalMode = mode;

        ConversationGroup group = new ConversationGroup(groupId, mode, members);

        for (AgentState s : members) {
            s.setInConversation(true);
            s.setVx(0);
            s.setVy(0);
            group.freeze(s.getAgentName());
        }

        String firstSpeaker = members.get(0).getAgentName();
        group.setCurrentSpeaker(firstSpeaker);

        activeGroups.put(groupId, group);

        TopicManager tm = getOrCreateTopicManager(groupId);
        String topicDesc = mode == ConversationMode.DEBATE ? "观点分歧辩论" :
                           mode == ConversationMode.PUBLIC_SPEAKING ? "主题演讲" :
                           mode == ConversationMode.GROUP_DISCUSSION ? "多人闲聊" : "偶遇聊天";
        tm.initiateTopic(groupId, topicDesc, firstSpeaker,
                members.stream().map(AgentState::getAgentName).toList());

        log.info("Group started: {} | mode={} | members={} | topic={}", groupId, mode,
                members.stream().map(AgentState::getAgentName).toList());

        CompletableFuture.runAsync(() -> {
            while (group.isActive() && strategy.shouldContinue(group)) {
                try {
                    executeRound(group, strategy);
                    long cooldown = finalMode == ConversationMode.GROUP_DISCUSSION ? 3000 : 2000;
                    Thread.sleep(cooldown);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Group {} round failed: {}", groupId, e.getMessage());
                    break;
                }
            }
            dissolveGroup(groupId);
        }, executor);
    }

    private void executeRound(ConversationGroup group, ConversationStrategy strategy) {
        Map<String, Map<String, String>> agentContexts = new ConcurrentHashMap<>();
        strategy.prepareContext(group, agentContexts);

        Map<String, String> responses = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(agentContexts.size());

        for (var entry : agentContexts.entrySet()) {
            String name = entry.getKey();
            String context = entry.getValue().get("context");

            // Skip LLM for player-controlled agents - use their existing message
            AgentState playerState = world.getState(name);
            if (playerState != null && playerState.isPlayerControlled()) {
                String existingMsg = playerState.getCurrentMessage();
                if (existingMsg != null && !existingMsg.isEmpty() && !existingMsg.startsWith("(\u4e3b\u63a7")) {
                    responses.put(name, existingMsg);
                } else {
                    responses.put(name, "...");
                }
                latch.countDown();
                continue;
            }

            executor.submit(() -> {
                try {
                    Agent agent = world.getAgent(name);
                    if (agent != null) {
                        String resp = agent.generateWithContext(context);
                        responses.put(name, resp);
                    }
                } catch (Exception e) {
                    log.warn("Agent {} generation failed: {}", name, e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        strategy.processResults(group, responses, llmClient);

        Map<String, Object> convEntry = new LinkedHashMap<>();
        convEntry.put("group", group.getGroupId());
        convEntry.put("mode", group.getMode().name());
        convEntry.put("tick", world.getTickCount());
        convEntry.put("round", group.getRoundCount());
        for (var entry : responses.entrySet()) {
            String val = entry.getValue();
            if (val != null && val.length() > 80) val = val.substring(0, 80);
            convEntry.put(entry.getKey(), val);
        }
        world.addConversationEntry(convEntry);

        if (!responses.isEmpty()) {
            log.info("Group {} round {} | {} responses",
                    group.getGroupId(), group.getRoundCount(), responses.size());
        }
    }

    private void dissolveGroup(String groupId) {
        ConversationGroup group = activeGroups.remove(groupId);
        if (group == null) return;

        for (AgentState s : group.getParticipantList()) {
            s.setInConversation(false);
            s.setVx(0);
            s.setVy(0);
        }

        String pairKey = makePairKey(group.getParticipantList());
        recentPairCooldowns.put(pairKey, System.currentTimeMillis());

        TopicManager tm = groupTopicManagers.get(groupId);
        if (tm != null) tm.closeCurrentTopic();

        log.info("Group dissolved: {} | mode={} | turns={}", groupId,
                group.getMode(), group.getTurnCount());
    }

    public Collection<ConversationGroup> getActiveGroups() {
        return activeGroups.values();
    }

    public int getActiveGroupCount() {
        return activeGroups.size();
    }

    private String makePairKey(List<AgentState> members) {
        List<String> sorted = members.stream().map(AgentState::getAgentName).sorted().toList();
        return String.join("_", sorted);
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("activeGroups", activeGroups.size());
        List<Map<String, Object>> groupList = new ArrayList<>();
        for (ConversationGroup g : activeGroups.values()) {
            Map<String, Object> gs = new LinkedHashMap<>();
            gs.put("id", g.getGroupId());
            gs.put("mode", g.getMode().name());
            gs.put("participants", g.getParticipantList().stream().map(AgentState::getAgentName).toList());
            gs.put("rounds", g.getRoundCount());
            gs.put("turns", g.getTurnCount());
            gs.put("idleMs", g.idleMs());
            TopicManager tm = groupTopicManagers.get(g.getGroupId());
            if (tm != null && tm.hasActiveTopic()) {
                gs.put("topic", tm.getTopicContext());
            }
            groupList.add(gs);
        }
        status.put("groups", groupList);
        return status;
    }
}
