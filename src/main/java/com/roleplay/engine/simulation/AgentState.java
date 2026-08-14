package com.roleplay.engine.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AgentState {
    /** P-0802-P3（改造方案 Phase 3）：去 final —— 局中改名时 {@link #rename} 原地改键（toMap 依赖对象引用一致性）。 */
    private volatile String agentName;
    private volatile double x;
    private volatile double y;
    private volatile double vx;
    private volatile double vy;
    private volatile Emotion emotion = Emotion.NEUTRAL;
    private volatile double hearRange = 200.0;
    private volatile double moveSpeed = 80.0;
    private volatile String currentMessage = "";
    private volatile long lastConversationTime = 0;
    private volatile long conversationStartTime = 0;
    private volatile boolean inConversation = false;
    private final List<String> visibleMessages = new CopyOnWriteArrayList<>();
    private volatile double targetX = -1;
    private volatile double targetY = -1;
    private volatile boolean hasTarget = false;
    /** Phase 4: 手动指定的目标（/target 端点）——MovementConstraint 不得覆盖。 */
    private volatile boolean manualTarget = false;
    /** P-0813-E：manualTarget 置位时间戳（ms）——导演轮跳过期判定用；-1 = 无手动目标。 */
    private volatile long manualTargetSince = -1L;
    private volatile Stance stance = Stance.NEUTRAL;
    private volatile double attention = 1.0;
    private volatile boolean playerControlled = false;
    /** P-0813-I：当前日程窗口文案（SchedulerService 每 tick 写入，SSE 可观测 + Agent 系统提示注入源）。 */
    private volatile String scheduleText = "";

    public enum Stance { FOR, AGAINST, NEUTRAL }

    public AgentState(String agentName, double startX, double startY) {
        this.agentName = agentName;
        this.x = startX;
        this.y = startY;
    }

    public String getAgentName() { return agentName; }

    /** P-0802-P3（改造方案 §4.2.2）：局中改名 —— 原地改 agentName（位置/情绪/标记等全字段保留）。 */
    public void rename(String newName) {
        this.agentName = newName;
    }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getVx() { return vx; }
    public void setVx(double vx) { this.vx = vx; }

    public double getVy() { return vy; }
    public void setVy(double vy) { this.vy = vy; }

    public Emotion getEmotion() { return emotion; }
    public void setEmotion(Emotion emotion) { this.emotion = emotion; }

    public double getHearRange() { return hearRange; }
    public void setHearRange(double hearRange) { this.hearRange = hearRange; }

    public double getMoveSpeed() { return moveSpeed; }
    public void setMoveSpeed(double moveSpeed) { this.moveSpeed = moveSpeed; }

    public String getCurrentMessage() { return currentMessage; }
    public void setCurrentMessage(String currentMessage) { this.currentMessage = currentMessage; }

    public long getLastConversationTime() { return lastConversationTime; }
    public void setLastConversationTime(long lastConversationTime) { this.lastConversationTime = lastConversationTime; }

    public long getConversationStartTime() { return conversationStartTime; }
    public void setConversationStartTime(long conversationStartTime) { this.conversationStartTime = conversationStartTime; }

    public boolean isInConversation() { return inConversation; }
    public void setInConversation(boolean inConversation) { this.inConversation = inConversation; }

    public List<String> getVisibleMessages() { return visibleMessages; }

    public double getTargetX() { return targetX; }
    public void setTargetX(double targetX) { this.targetX = targetX; }

    public double getTargetY() { return targetY; }
    public void setTargetY(double targetY) { this.targetY = targetY; }

    public boolean isHasTarget() { return hasTarget; }
    public void setHasTarget(boolean hasTarget) { this.hasTarget = hasTarget; }

    public boolean isManualTarget() { return manualTarget; }

    /**
     * P-0813-E：置位/释放手动目标标记并同步时间戳（置位→now，释放→-1）。
     * 时间戳供导演轮判断「手动目标是否仍新鲜」（不覆盖），超时后释放由导演接管。
     */
    public void setManualTarget(boolean manualTarget) {
        this.manualTarget = manualTarget;
        this.manualTargetSince = manualTarget ? System.currentTimeMillis() : -1L;
    }

    /** P-0813-E：手动目标置位时间戳（-1 = 无手动目标）。 */
    public long getManualTargetSince() { return manualTargetSince; }

    /** P-0813-E（测试支持，包可见）：直接改写时间戳（导演跳过期的单测无需等待真实时长）。 */
    void setManualTargetSinceForTest(long ts) { this.manualTargetSince = ts; }

    /** Phase 4 便捷方法：设置目标点并标记已有目标。 */
    public void setTarget(double x, double y) {
        this.targetX = x;
        this.targetY = y;
        this.hasTarget = true;
    }

    public Stance getStance() { return stance; }
    public void setStance(Stance stance) { this.stance = stance; }

    public double getAttention() { return attention; }
    public void setAttention(double attention) { this.attention = Math.max(0, Math.min(1, attention)); }

    public boolean isPlayerControlled() { return playerControlled; }
    public void setPlayerControlled(boolean playerControlled) { this.playerControlled = playerControlled; }

    /** P-0813-I：当前日程窗口文案（无窗口/未接管 → 空串）。 */
    public String getScheduleText() { return scheduleText; }
    public void setScheduleText(String scheduleText) { this.scheduleText = scheduleText == null ? "" : scheduleText; }

    public void clearTarget() {
        this.hasTarget = false;
        this.targetX = -1;
        this.targetY = -1;
        this.manualTarget = false;
        this.manualTargetSince = -1L;
    }

    public double distanceTo(AgentState other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("agentName", agentName);
        map.put("x", Math.round(x * 100.0) / 100.0);
        map.put("y", Math.round(y * 100.0) / 100.0);
        map.put("emotion", emotion.getLabel());
        map.put("emotionEmoji", emotion.getEmoji());
        map.put("hearRange", hearRange);
        map.put("moveSpeed", moveSpeed);
        map.put("currentMessage", currentMessage);
        map.put("inConversation", inConversation);
        map.put("hasTarget", hasTarget);
        map.put("stance", stance.name().toLowerCase());
        map.put("attention", Math.round(attention * 100.0) / 100.0);
        map.put("playerControlled", playerControlled);
        map.put("manualTarget", manualTarget);
        map.put("schedule", scheduleText);
        if (hasTarget) {
            map.put("targetX", Math.round(targetX * 100.0) / 100.0);
            map.put("targetY", Math.round(targetY * 100.0) / 100.0);
        }
        return map;
    }
}
