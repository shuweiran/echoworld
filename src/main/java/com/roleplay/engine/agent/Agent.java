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

        // 1. System prompt from persona
        String systemContent = persona.buildSystemPrompt();
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
                if (m.getRole() == Message.Role.SYSTEM) continue;
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
                new Message(Message.Role.SYSTEM, "system", persona.buildSystemPrompt()),
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
                new Message(Message.Role.SYSTEM, "system", persona.buildSystemPrompt()),
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
}
