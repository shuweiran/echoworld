package com.roleplay.engine.agent;

import com.roleplay.engine.core.Message;
import com.roleplay.engine.core.Persona;
import com.roleplay.engine.interrupt.CancellationToken;
import com.roleplay.engine.interrupt.TaskCancelledException;
import com.roleplay.engine.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An AI character that participates in the roleplay conversation.
 *
 * <p>Each Agent wraps a {@link Persona} and generates responses via {@link LLMClient}.
 * The key difference from Python: Agent.generate() returns a {@link CompletableFuture},
 * enabling true parallel execution across agents via Virtual Threads.
 *
 * <p>Maps from Python {@code core/agent.py → Agent}.
 */
public class Agent {

    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    private final Persona persona;
    private final String role;
    private final LLMClient llmClient;
    private volatile boolean isGenerating = false;
    /** 简化生成路径（2D 对话等）的首轮完整人设门闩；之后由轻量提示维持风格。 */
    private final AtomicBoolean directPersonaPrimed = new AtomicBoolean(false);
    /** P-0810-09：当前角色的隐藏目标（场景目标机制）—— buildContext/生成路径注入系统提示，不暴露给玩家。 */
    private volatile String hiddenGoal = null;
    /** P-0813-I：当前行为窗口文案提供者（2D 世界由 SimulationService 注册，读 AgentState.scheduleText）。
     *  非 null 且返回非空时，系统提示追加【当前行为窗口】段；null/空 → 原 prompt 零变化。 */
    private volatile java.util.function.Supplier<String> scheduleContextSupplier = null;

    public Agent(Persona persona, String role, LLMClient llmClient) {
        this.persona = persona;
        this.role = role;
        this.llmClient = llmClient;
    }

    public String getName() {
        return persona.getName();
    }

    public Persona getPersona() {
        return persona;
    }

    public boolean isGenerating() {
        return isGenerating;
    }

    // ── P-0810-09：隐藏目标（场景目标机制） ───────────────────

    /** 设置当前角色隐藏目标（RouterService.setSceneGoals 时注入）。 */
    public void setHiddenGoal(String hiddenGoal) {
        this.hiddenGoal = hiddenGoal;
    }

    public String getHiddenGoal() {
        return hiddenGoal;
    }

    /** 系统提示追加隐藏目标块（行为引导，禁止暴露给玩家）。 */
    private String appendHiddenGoal(String systemContent) {
        if (hiddenGoal == null || hiddenGoal.isBlank()) return systemContent;
        return systemContent + "\n\n【隐藏目标】\n你的目标：" + hiddenGoal
                + "\n（不要主动暴露给玩家，用行为和言语自然引导剧情推进）";
    }

    // ── P-0813-I：当前行为窗口（混合架构——主控日程骨架，角色 LLM 只填台词与细节） ──────

    /** 设置行为窗口文案提供者（SimulationService 接线；null 清除=回退原 prompt）。 */
    public void setScheduleContextSupplier(java.util.function.Supplier<String> supplier) {
        this.scheduleContextSupplier = supplier;
    }

    public java.util.function.Supplier<String> getScheduleContextSupplier() {
        return scheduleContextSupplier;
    }

    /**
     * 系统提示追加【当前行为窗口】段（你在哪/正在做什么/允许的自由度）。
     * 窗口段显式约束「不要自行离开区域或更换行为」——弱化角色自行决定下一步行动的自由度
     * （P-0813-I：节奏可控 + 个性保留，台词仍自由）；无窗口/未接线 → 原样返回零变化。
     */
    private String appendScheduleWindow(String systemContent) {
        java.util.function.Supplier<String> s = scheduleContextSupplier;
        if (s == null) return systemContent;
        String window = s.get();
        if (window == null || window.isBlank()) return systemContent;
        return systemContent + "\n\n" + window;
    }

    // ── P-0810-23-D2：发言超长提醒（仅下一轮生效，玩家无感知） ──────────────

    /** 待发提醒：AI 角色单次发言超长时由 RouterService 记录，下一轮构建系统提示时注入并清除。 */
    private volatile String pendingReminder = null;

    /** 设置待发提醒（RouterService.maybeRecordOverLengthReminder 检测到超长时调用）。 */
    public void setPendingReminder(String reminder) {
        this.pendingReminder = reminder;
    }

    public String getPendingReminder() {
        return pendingReminder;
    }

    /**
     * 系统提示追加提醒块并消费：提醒仅注入下一轮一次，注入即清除（不持续、不广播旁白、无前端 UI）。
     * 无待发提醒时原样返回，零行为变化。
     */
    private String appendReminder(String systemContent) {
        String r = pendingReminder;
        if (r == null || r.isBlank()) return systemContent;
        pendingReminder = null; // 消费后清除：仅下一轮生效
        return systemContent + "\n\n【系统提醒】" + r;
    }

    // ── Core generation (blocking + non-blocking variants) ─────

    /**
     * Build the full message list for the LLM call.
     *
     * <p>This replaces Python's {@code build_messages()} method.
     * Constructs system prompt, history, role-lock, track mode constraints,
     * and output rules into a single OpenAI-format message list.
     */
    public List<Message> buildContext(
            String sceneDescription,
            List<Message> history,
            String trackMode,
            List<String> allAgentNames,
            String summaryContext,
            Message sameRoundPeerOutput,
            String userInterjection) {

        List<Message> messages = new ArrayList<>();

        // 首轮与带校准提醒的轮次使用完整人设；其余轮次用轻量版，避免固定台词逐轮强化。
        String systemContent = appendReminder(appendHiddenGoal(appendScheduleWindow(personaPromptFor(history))));
        if (sceneDescription != null && !sceneDescription.isEmpty()) {
            systemContent += "\n\n【当前场景】\n" + sceneDescription;
        }
        if (summaryContext != null && !summaryContext.isEmpty()) {
            systemContent += "\n\n" + summaryContext;
        }
        systemContent += "\n\n【轨道模式】\n" + (trackMode != null ? trackMode : "merged");
        messages.add(new Message(Message.Role.SYSTEM, "system", systemContent));

        // 2. Conversation history
        if (history != null) {
            for (Message m : history) {
                if (m.getRole() == Message.Role.SYSTEM) {
                    // P-0813-B：校准提醒（内容以「【校准提醒】」开头）是注入会话历史的系统级指令，
                    // 必须进入 LLM 上下文（防漂移）；其余 SYSTEM 消息（关系图等）维持跳过语义零变化
                    if (m.getContent() == null || !m.getContent().startsWith("【校准提醒】")) continue;
                }
                messages.add(m);
            }
        }

        // 3. User interjection (if any)
        if (userInterjection != null && !userInterjection.isEmpty()) {
            messages.add(new Message(Message.Role.USER, "主控", userInterjection));
        }

        return messages;
    }

    /**
     * Generate a response synchronously （blocking call, suitable for Virtual Threads）.
     *
     * <p>Unlike Python's async generator, this returns the complete string.
     * Streaming is handled at the SSE layer, not at the agent level.
     */
    public String generateSync(
            String systemPrompt,
            List<Message> history,
            String trackMode,
            List<String> allAgentNames,
            String summaryContext,
            Message sameRoundPeerOutput,
            String userInterjection) {
        return generateSync(systemPrompt, history, trackMode, allAgentNames,
                summaryContext, sameRoundPeerOutput, userInterjection, null);
    }

    /**
     * D1: 可中断生成 —— 携带 {@link CancellationToken}（需求文档第八条 §五）。
     *
     * <p>检查点：LLM 调用前 / 调用返回后。软停止（SOFT）时在第二个检查点抛出，
     * 已完整生成但未提交的内容随异常上抛（{@link TaskCancelledException#getPartial()}），
     * 由上层保存为任务未完成状态（§四 软停止：保存未完成状态 → 切换任务）。
     */
    public String generateSync(
            String systemPrompt,
            List<Message> history,
            String trackMode,
            List<String> allAgentNames,
            String summaryContext,
            Message sameRoundPeerOutput,
            String userInterjection,
            CancellationToken token) {

        isGenerating = true;
        String completed = null;
        try {
            if (token != null) token.checkpoint();
            List<Message> messages = buildContext(
                    systemPrompt, history, trackMode, allAgentNames,
                    summaryContext, sameRoundPeerOutput, userInterjection);

            completed = token != null
                    ? llmClient.callSync(messages, token)
                    : llmClient.callSync(messages);
            if (token != null) token.checkpoint();
            return completed;
        } catch (TaskCancelledException e) {
            // 软停止：回复已生成但未提交 → 附到异常上，由 AgentTask 保存未完成状态
            if (e.getPartial() == null && completed != null) e.setPartial(completed);
            throw e;
        } finally {
            isGenerating = false;
        }
    }

    /**
     * Generate a response asynchronously （returns CompletableFuture）.
     *
     * <p>Used by {@link AgentExecutor} for parallel execution.
     */
    public CompletableFuture<String> generateAsync(
            String systemPrompt,
            List<Message> history,
            String trackMode,
            List<String> allAgentNames,
            String summaryContext,
            Message sameRoundPeerOutput,
            String userInterjection) {

        return CompletableFuture.supplyAsync(() ->
                generateSync(systemPrompt, history, trackMode, allAgentNames,
                        summaryContext, sameRoundPeerOutput, userInterjection)
        );
    }

    /**
     * Simplified generation for simulation module.
     * Generates a response given just a context string.
     */
    public String generateWithContext(String context) {
        return generateWithContext(context, null);
    }

    /**
     * D1: 可中断的简化生成（2D 模拟对话路径）。检查点与软停止语义同
     * {@link #generateSync(String, List, String, List, String, Message, String, CancellationToken)}。
     */
    public String generateWithContext(String context, CancellationToken token) {
        String completed = null;
        try {
            if (token != null) token.checkpoint();
            List<Message> messages = List.of(
                new Message(Message.Role.SYSTEM, "system", appendReminder(appendHiddenGoal(appendScheduleWindow(personaPromptFor(context))))),
                new Message(Message.Role.USER, "user", context)
            );
            completed = token != null
                    ? llmClient.callSync(messages, token)
                    : llmClient.callSync(messages);
            if (token != null) token.checkpoint();
            return completed;
        } catch (TaskCancelledException e) {
            if (e.getPartial() == null && completed != null) e.setPartial(completed);
            throw e;
        }
    }

    /**
     * P-0802-M：流式简化生成 —— 与 {@link #generateWithContext(String, CancellationToken)} 同语义
     * （同一 context），但 LLM 增量经 {@code onDelta} 逐片回调（SSE 推送用，每片可为 1~N 字符）。
     *
     * <p>流式失败（网络中断/协议异常）或调用方未实现流式接口（mock 返回 null）→ 自动降级
     * 非流式完整调用 {@link #generateWithContext(String, CancellationToken)}，完整文本始终返回，
     * 调用方照常以 agent_output 结算（前端增量草稿被完整内容替换，内容不丢）。
     */
    public String generateWithContextStream(String context, CancellationToken token,
                                            java.util.function.Consumer<String> onDelta) {
        String completed = null;
        try {
            if (token != null) token.checkpoint();
            List<Message> messages = List.of(
                new Message(Message.Role.SYSTEM, "system", appendReminder(appendHiddenGoal(appendScheduleWindow(personaPromptFor(context))))),
                new Message(Message.Role.USER, "user", context)
            );
            completed = llmClient.callStream(messages, token, onDelta);
            if (token != null) token.checkpoint();
            if (completed == null) {
                // 调用方未实现流式（mock/旧实现 callStream 返回 null）→ 非流式兜底
                return generateWithContext(context, token);
            }
            return completed;
        } catch (TaskCancelledException e) {
            if (e.getPartial() == null && completed != null) e.setPartial(completed);
            throw e;
        } catch (Exception e) {
            // 流式失败 → 降级非流式完整调用（内容不丢，前端以 agent_output 结算）
            log.warn("Streaming failed for agent {}: {}, falling back to non-streaming",
                    getName(), e.getMessage());
            return generateWithContext(context, token);
        }
    }

    @Override
    public String toString() {
        return "Agent{" + getName() + "}";
    }

    private String personaPromptFor(List<Message> history) {
        boolean hasConversation = history != null && history.stream()
                .anyMatch(m -> m.getRole() != Message.Role.SYSTEM);
        boolean hasCalibration = history != null && history.stream().anyMatch(m ->
                m.getRole() == Message.Role.SYSTEM && m.getContent() != null
                        && m.getContent().startsWith("【校准提醒】"));
        return !hasConversation || hasCalibration ? persona.buildSystemPrompt() : persona.buildLightweightPrompt();
    }

    private String personaPromptFor(String context) {
        boolean hasCalibration = context != null && context.contains("【校准提醒】");
        return hasCalibration || !directPersonaPrimed.getAndSet(true)
                ? persona.buildSystemPrompt() : persona.buildLightweightPrompt();
    }
}
