package com.roleplay.engine.simulation.conversation;

import com.roleplay.engine.simulation.AgentState;
import com.roleplay.engine.simulation.track.TrackAssignment;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ConversationGroup {

    private final String groupId;
    private final ConversationMode mode;
    /** 组类型（P-0815-A）：默认 AI_AUTO；joinGroup → USER_JOINED；剧本杀讨论 → SCRIPT_DISCUSSION。 */
    private volatile GroupKind kind = GroupKind.AI_AUTO;
    private final LinkedHashMap<String, AgentState> participants;
    private final Set<String> frozenAgents;
    private volatile int turnCount = 0;
    private volatile int roundCount = 0;
    private volatile String currentSpeaker;
    private final List<String> turnHistory = new ArrayList<>();
    private final List<Map<String, String>> messageHistory = new ArrayList<>();
    private volatile long createdAt;
    private volatile long lastActivity;
    private volatile boolean active = true;
    private String topic = "";
    private final Map<String, Double> engagement = new ConcurrentHashMap<>();
    /** Phase 1 Track fusion: spatial assignments computed at group creation. */
    private volatile Map<String, TrackAssignment> trackAssignments = Map.of();
    /** Phase 2 Track fusion: cached WEAK-track eavesdrop summary (EavesdropSummarizer
     *  product) so TrackStrategy does not call the LLM on every round. */
    private volatile String trackSummary = "";
    /** messageHistory size when {@link #trackSummary} was last computed. */
    private volatile int trackSummaryHistorySize = 0;
    /** 参与者上限（方案A 用户加入）：0 语义不用，默认 {@link Integer#MAX_VALUE}=不限；
     *  DYAD 对偶组由 ConversationManager 建组时传 2（一对一的语义上限，防 join 破坏 1v1）。 */
    private final int maxParticipants;

    public ConversationGroup(String groupId, ConversationMode mode, List<AgentState> members) {
        this(groupId, mode, members, Integer.MAX_VALUE);
    }

    /** @param maxParticipants 参与者上限；满员后 {@link #addParticipant} 拒绝（方案A 满员校验）。 */
    public ConversationGroup(String groupId, ConversationMode mode, List<AgentState> members, int maxParticipants) {
        this.groupId = groupId;
        this.mode = mode;
        this.participants = new LinkedHashMap<>();
        this.frozenAgents = ConcurrentHashMap.newKeySet();
        this.maxParticipants = Math.max(1, maxParticipants);
        for (AgentState s : members) {
            participants.put(s.getAgentName(), s);
            engagement.put(s.getAgentName(), 1.0);
        }
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastActivity = now;
    }

    public String getGroupId() { return groupId; }
    public ConversationMode getMode() { return mode; }
    /** 组类型（P-0815-A，见 {@link GroupKind}）。 */
    public GroupKind getKind() { return kind; }
    public void setKind(GroupKind kind) { this.kind = kind == null ? GroupKind.AI_AUTO : kind; }
    public Map<String, AgentState> getParticipants() { return participants; }
    /** 参与者上限；Integer.MAX_VALUE=不限（方案A 加入时满员校验用）。 */
    public int getMaxParticipants() { return maxParticipants; }

    /** 成员表快照（synchronized：与 {@link #addParticipant}/{@link #removeParticipant} 互斥，
     *  防后台 executeRound 迭代期间 join/leave 并发修改 LinkedHashMap 抛 CME）。 */
    public synchronized List<AgentState> getParticipantList() { return new ArrayList<>(participants.values()); }
    public synchronized int getParticipantCount() { return participants.size(); }

    /**
     * 方案A：加入成员（join 原语的地基）。重复加入返回 false；达到 {@link #maxParticipants} 上限返回 false。
     * 新成员参与度初始化为 1.0，默认非冻结（冻结与否由调用方决定，对齐 startGroup 语义）。
     */
    public synchronized boolean addParticipant(AgentState member) {
        if (member == null) return false;
        if (participants.containsKey(member.getAgentName())) return false;
        if (participants.size() >= maxParticipants) return false;
        participants.put(member.getAgentName(), member);
        engagement.put(member.getAgentName(), 1.0);
        frozenAgents.remove(member.getAgentName());
        touchActivity();
        return true;
    }

    /** 方案A：移除成员（leave 原语的地基）。不在组内返回 false。 */
    public synchronized boolean removeParticipant(String name) {
        AgentState removed = participants.remove(name);
        if (removed == null) return false;
        engagement.remove(name);
        frozenAgents.remove(name);
        touchActivity();
        return true;
    }
    public int getTurnCount() { return turnCount; }
    public int getRoundCount() { return roundCount; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActivity() { return lastActivity; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }

    public String getCurrentSpeaker() { return currentSpeaker; }
    public void setCurrentSpeaker(String name) { this.currentSpeaker = name; }

    public List<String> getTurnHistory() { return turnHistory; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public double getEngagement(String name) { return engagement.getOrDefault(name, 1.0); }
    public void setEngagement(String name, double v) { engagement.put(name, Math.max(0, Math.min(1, v))); }

    public Set<String> getFrozenAgents() { return frozenAgents; }

    public Map<String, TrackAssignment> getTrackAssignments() { return trackAssignments; }
    public void setTrackAssignments(Map<String, TrackAssignment> assignments) {
        this.trackAssignments = assignments == null ? Map.of() : Map.copyOf(assignments);
    }
    public TrackAssignment getTrackAssignment(String name) { return trackAssignments.get(name); }
    public String getTrackSummary() { return trackSummary; }
    public void setTrackSummary(String summary) { this.trackSummary = summary == null ? "" : summary; }
    public int getTrackSummaryHistorySize() { return trackSummaryHistorySize; }
    public void setTrackSummaryHistorySize(int size) { this.trackSummaryHistorySize = size; }
    public void freeze(String name) { frozenAgents.add(name); }
    public void unfreeze(String name) { frozenAgents.remove(name); }
    public boolean isFrozen(String name) { return frozenAgents.contains(name); }

    public void touchActivity() { this.lastActivity = System.currentTimeMillis(); }

    public void recordTurn(String speaker, String message) {
        turnCount++;
        roundCount = (turnCount + participants.size() - 1) / participants.size();
        turnHistory.add(speaker);
        if (turnHistory.size() > 20) turnHistory.remove(0);
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("speaker", speaker);
        entry.put("message", message);
        entry.put("round", String.valueOf(roundCount));
        messageHistory.add(entry);
        if (messageHistory.size() > 30) messageHistory.remove(0);
        touchActivity();
    }

    public List<Map<String, String>> getMessageHistory() { return messageHistory; }

    public synchronized boolean containsAgent(String name) { return participants.containsKey(name); }

    public synchronized AgentState getParticipant(String name) { return participants.get(name); }

    public boolean allFrozen() {
        return frozenAgents.size() >= participants.size();
    }

    public long idleMs() { return System.currentTimeMillis() - lastActivity; }

    // ═══════════════════════════════════════════════════════════
    //  P-0814-A：播放驱动轮间门（点击驱动对话模式）
    // ═══════════════════════════════════════════════════════════
    // 一轮生成完 → 组循环 awaitPlayback() 阻塞等待「播出完毕」信号（signalPlaybackDone）
    // → 收到信号后进入下一轮。stop/解散时 active=false + wakePlaybackWaiters() 唤醒返回。

    private final Object playbackMonitor = new Object();
    /** 「播出完毕」信号计数（持续置位/counter：每次 signalPlaybackDone 递增，每次 awaitPlayback
     *  唤醒消费一个）。P-0814-B：改为计数器消除「布尔置位+进入清位」双重丢失竞态——
     *  连点 N 次信号逐轮消费（每信号至多推进一轮）；信号在组生成期间到达不再被丢。 */
    private int playbackSignalCount = 0;
    /** 当前是否处于等待播出完毕状态（tick 据此跳过 idle 超时解散，导演思考/播放期间组不拆）。 */
    private volatile boolean awaitingPlayback = false;

    /** 阻塞等待「播出完毕」信号（playback-driven 组轮间门）。active=false/stop 时被唤醒返回。
     *  P-0814-B：等待期间到达的每个信号唤醒一轮（计数器逐轮消费，不丢轮）；
     *  消费在同步块内完成（与 signalPlaybackDone 互斥，无「唤醒后清位吞新信号」竞态）。 */
    public void awaitPlayback() throws InterruptedException {
        synchronized (playbackMonitor) {
            awaitingPlayback = true;
            try {
                while (playbackSignalCount == 0 && active) {
                    playbackMonitor.wait();
                }
                if (playbackSignalCount > 0) playbackSignalCount--; // 消费一个信号
            } finally {
                awaitingPlayback = false;
            }
        }
    }

    /** 播放完毕信号 → 计数+1 并唤醒等待中的轮次循环（持续置位：无等待者时信号保持，
     *  下一轮 awaitPlayback 进入即通过；幂等，可重复调用；每信号至多推进一轮）。 */
    public void signalPlaybackDone() {
        synchronized (playbackMonitor) {
            playbackSignalCount++;
            playbackMonitor.notifyAll();
        }
    }

    /** 停止/解散时唤醒等待者（调用方应先 setActive(false) 保证等待循环退出）。 */
    public void wakePlaybackWaiters() {
        synchronized (playbackMonitor) {
            playbackMonitor.notifyAll();
        }
    }

    /** 当前是否处于等待播出完毕状态。 */
    public boolean isAwaitingPlayback() { return awaitingPlayback; }
}
