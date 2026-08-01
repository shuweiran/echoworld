package com.roleplay.engine.service;

import com.roleplay.engine.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Track change request system — agents request track mode changes, DM approves.
 *
 * <p>Maps from Python core/track_request.py + router.py track request flow.
 *
 * <p>Rules:
 * <ul>
 *   <li>DISCONNECT (merge→weak, weak→isolated): auto-approved</li>
 *   <li>STRENGTHEN (weak→merge, isolated→weak): PENDING, needs DM approval</li>
 *   <li>CONNECT (join another agent's track): PENDING, needs DM approval</li>
 * </ul>
 */
@Service
public class TrackRequestService {
    private static final Logger log = LoggerFactory.getLogger(TrackRequestService.class);

    public enum RequestType {
        DISCONNECT,   // 断链 — 自动批准
        WEAKEN,       // 减弱 — 需审批
        STRENGTHEN,   // 增强 — 需审批
        CONNECT       // 连接 — 需审批
    }

    public enum RequestStatus {
        PENDING, APPROVED, REJECTED, AUTO_APPROVED
    }

    public static class TrackChangeRequest {
        public final String id;
        public final String agentName;
        public final String targetAgent;
        public final String currentTrackId;
        public final String targetTrackId;
        public final String currentMode;    // merged | weak | isolated
        public final String targetMode;
        public final RequestType type;
        public final String reason;
        public RequestStatus status;
        public final Instant createdAt;
        public String reviewNote = "";

        public TrackChangeRequest(String agentName, String targetAgent,
                                   String currentTrackId, String targetTrackId,
                                   String currentMode, String targetMode,
                                   RequestType type, String reason) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.agentName = agentName;
            this.targetAgent = targetAgent;
            this.currentTrackId = currentTrackId;
            this.targetTrackId = targetTrackId;
            this.currentMode = currentMode;
            this.targetMode = targetMode;
            this.type = type;
            this.reason = reason;
            this.createdAt = Instant.now();
            this.status = type == RequestType.DISCONNECT
                ? RequestStatus.AUTO_APPROVED : RequestStatus.PENDING;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("agent", agentName);
            m.put("target_agent", targetAgent);
            m.put("from_mode", currentMode); m.put("to_mode", targetMode);
            m.put("from_track", currentTrackId); m.put("to_track", targetTrackId);
            m.put("type", type.name().toLowerCase());
            m.put("reason", reason); m.put("status", status.name().toLowerCase());
            m.put("review_note", reviewNote);
            return m;
        }
    }

    private final Map<String, List<TrackChangeRequest>> sessionRequests = new ConcurrentHashMap<>();
    private final LLMClient llmClient;

    public TrackRequestService(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    // ═══════════════════════════════════════════════════════════
    //  Submit & manage requests
    // ═══════════════════════════════════════════════════════════

    /** Submit a track change request. Auto-approves DISCONNECT. */
    public TrackChangeRequest submitRequest(String sessionId, String agentName,
                                             String targetAgent, String currentTrackId,
                                             String targetTrackId, String currentMode,
                                             String targetMode, String reason) {
        RequestType type = classifyRequest(currentMode, targetMode);
        TrackChangeRequest req = new TrackChangeRequest(agentName, targetAgent,
            currentTrackId, targetTrackId, currentMode, targetMode, type, reason);
        sessionRequests.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(req);

        if (type == RequestType.DISCONNECT) {
            log.info("Auto-approved disconnect: {} → {}", agentName, targetMode);
        } else {
            log.info("Pending request: {} wants {} ({}→{})", agentName, type, currentMode, targetMode);
        }
        return req;
    }

    public void approveRequest(String sessionId, String requestId, String note) {
        findRequest(sessionId, requestId).ifPresent(r -> {
            r.status = RequestStatus.APPROVED;
            r.reviewNote = note;
        });
    }

    public void rejectRequest(String sessionId, String requestId, String note) {
        findRequest(sessionId, requestId).ifPresent(r -> {
            r.status = RequestStatus.REJECTED;
            r.reviewNote = note;
        });
    }

    public List<TrackChangeRequest> getPendingRequests(String sessionId) {
        return sessionRequests.getOrDefault(sessionId, List.of()).stream()
            .filter(r -> r.status == RequestStatus.PENDING)
            .collect(Collectors.toList());
    }

    public List<TrackChangeRequest> getAllRequests(String sessionId) {
        return sessionRequests.getOrDefault(sessionId, List.of());
    }

    public List<TrackChangeRequest> getApprovedRequests(String sessionId) {
        return sessionRequests.getOrDefault(sessionId, List.of()).stream()
            .filter(r -> r.status == RequestStatus.APPROVED
                       || r.status == RequestStatus.AUTO_APPROVED)
            .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════
    //  LLM-powered request generation & review
    // ═══════════════════════════════════════════════════════════

    /**
     * Step 1-2: LLM evaluates each agent's track needs based on their goals
     * and the current plot progress. Returns list of suggested requests.
     */
    public List<Map<String, Object>> evaluateAgentNeeds(String sessionId,
                                                          String sceneDescription,
                                                          List<String> agentNames,
                                                          List<String> agentGoals,
                                                          Map<String, String> currentTrackModes,
                                                          String plotSummary) {
        String goalsText = agentGoals != null && !agentGoals.isEmpty()
            ? "角色个人目标：\n" + agentGoals.stream().map(g -> "- " + g).collect(Collectors.joining("\n"))
            : "";

        String modesText = currentTrackModes.entrySet().stream()
            .map(e -> "  " + e.getKey() + ": " + e.getValue())
            .collect(Collectors.joining("\n"));

        String prompt = String.format("""
            你是角色扮演游戏的主控（DM）。请分析每个角色当前的轨道需求。

            场景：%s
            剧本进度：
            %s

            当前轨道模式：
            %s

            %s

            请分析每个角色是否需要变更轨道模式。评估标准：
            - 该角色当前剧情线是否已经收尾 → 可断链（merged→weak 或 weak→isolated）
            - 该角色是否需要参与主线推进 → 可增强（weak→merged）
            - 该角色是否与同轨道其他角色有独立剧情 → 可申请连接新轨道

            返回JSON格式（Agent名→建议）：
            {"角色名": {"suggested_mode": "merged/weak/isolated", "reason": "理由", "request_type": "disconnect/weaken/strengthen/connect", "connect_to": "目标角色名（如是连接请求）"}}
            只返回需要变更的角色。
            """, sceneDescription, plotSummary, modesText, goalsText);

        // D-023：N 角色变更建议 JSON（suggested_mode/reason/request_type/connect_to），300 偏紧，提升至 600
        Map<String, Object> result = llmClient.callJson(prompt, 600);
        List<Map<String, Object>> suggestions = new ArrayList<>();

        for (Map.Entry<String, Object> entry : result.entrySet()) {
            String agent = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> eval = (Map<String, Object>) entry.getValue();
            if (eval != null) {
                suggestions.add(eval);
            }
        }
        return suggestions;
    }

    /**
     * Step 3: DM reviews pending requests using LLM (only plot/scene logic,
     * not agent subjective goals).
     */
    public List<Map<String, Object>> reviewRequests(String sessionId,
                                                      String sceneDescription,
                                                      String plotSummary,
                                                      List<Map<String, Object>> pendingRequests) {
        if (pendingRequests.isEmpty()) return List.of();

        String requestsText = pendingRequests.stream()
            .map(r -> "  " + r.getOrDefault("agent_name", "?")
                + " wants " + r.getOrDefault("target_mode", "?")
                + " (from " + r.getOrDefault("current_mode", "?") + ")"
                + " reason: " + r.getOrDefault("reason", ""))
            .collect(Collectors.joining("\n"));

        String prompt = String.format("""
            你是角色扮演游戏的主控（DM）。请审批以下轨道变更申请。

            审批标准（只看剧本逻辑，不受角色主观目标影响）：
            1. ✅ 是否符合剧本逻辑
            2. ✅ 是否符合场景逻辑
            3. ✅ 至少保持 2 个角色在 merged 轨道，维持剧情推进
            4. ❌ 拒绝会导致剧情孤立的申请

            场景：%s
            剧本进度：%s

            待审批申请：
            %s

            返回JSON：
            {"审批结果": [{"agent": "角色名", "approved": true/false, "reason": "审批理由"}]}
            """, sceneDescription, plotSummary, requestsText);

        // D-023：N 条审批 JSON（agent/approved/reason），200 偏紧，提升至 400
        Map<String, Object> result = llmClient.callJson(prompt, 400);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reviews = (List<Map<String, Object>>) result.getOrDefault("审批结果", List.of());

        // Apply reviews
        for (Map<String, Object> review : reviews) {
            String agent = (String) review.get("agent");
            boolean approved = (boolean) review.getOrDefault("approved", false);
            String reason = (String) review.getOrDefault("reason", "");
            findRequestForAgent(sessionId, agent).ifPresent(r -> {
                r.status = approved ? RequestStatus.APPROVED : RequestStatus.REJECTED;
                r.reviewNote = reason;
            });
        }
        return reviews;
    }

    /**
     * Step 4: Apply approved requests to generate final track configuration.
     * Returns modified track list.
     */
    public List<Map<String, Object>> applyApprovedRequests(String sessionId,
                                                             List<Map<String, Object>> currentTracks) {
        List<TrackChangeRequest> approved = getApprovedRequests(sessionId);
        if (approved.isEmpty()) return currentTracks;

        // Build a map of agent → their approved request
        Map<String, TrackChangeRequest> approvedMap = new HashMap<>();
        for (TrackChangeRequest req : approved) {
            approvedMap.put(req.agentName, req);
        }

        for (Map<String, Object> track : currentTracks) {
            @SuppressWarnings("unchecked")
            List<String> agents = (List<String>) track.get("agents");
            @SuppressWarnings("unchecked")
            Map<String, String> actions = (Map<String, String>) track.get("agent_actions");

            List<String> toRemove = new ArrayList<>();
            for (String agent : agents) {
                TrackChangeRequest req = approvedMap.get(agent);
                if (req == null) continue;

                if (req.type == RequestType.DISCONNECT) {
                    // Downgrade: active→silent or silent→offline
                    if ("merged".equals(req.targetMode) || "weak".equals(req.targetMode)) {
                        // Keep in track but change action
                        String newAction = "weak".equals(req.targetMode) ? "silent" : "offline";
                        if (actions != null) actions.put(agent, newAction);
                    } else {
                        toRemove.add(agent);
                    }
                }
            }
            agents.removeAll(toRemove);
        }

        // Handle CONNECT requests — add agents to target tracks or create new tracks
        for (TrackChangeRequest req : approved) {
            if (req.type == RequestType.CONNECT || req.type == RequestType.STRENGTHEN) {
                boolean added = false;
                for (Map<String, Object> track : currentTracks) {
                    if (req.targetTrackId.equals(track.get("id"))) {
                        @SuppressWarnings("unchecked")
                        List<String> agents = (List<String>) track.get("agents");
                        if (!agents.contains(req.agentName)) {
                            agents.add(req.agentName);
                            @SuppressWarnings("unchecked")
                            Map<String, String> actions = (Map<String, String>) track.get("agent_actions");
                            if (actions != null) actions.put(req.agentName, "active");
                        }
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    // Create new track for this agent
                    Map<String, Object> newTrack = new LinkedHashMap<>();
                    newTrack.put("id", req.targetTrackId);
                    newTrack.put("agents", new ArrayList<>(List.of(req.agentName)));
                    newTrack.put("agent_actions", new LinkedHashMap<>(Map.of(req.agentName, "active")));
                    newTrack.put("mode", req.targetMode);
                    newTrack.put("label", "轨道_" + req.agentName);
                    currentTracks.add(newTrack);
                }
            }
        }

        return currentTracks;
    }

    /**
     * Silent processing: auto-approve pending requests based on plot goals
     * without showing to frontend or writing to context.
     */
    public void silentProcessPending(String sessionId, List<String> plotGoals) {
        List<TrackChangeRequest> pending = getPendingRequests(sessionId);
        for (TrackChangeRequest req : pending) {
            // Auto-approve if it aligns with plot goals or is a disconnect
            if (req.type == RequestType.DISCONNECT) {
                req.status = RequestStatus.AUTO_APPROVED;
            } else if (plotGoals != null && !plotGoals.isEmpty()) {
                // Simplistic: approve if the agent has a goal
                boolean hasGoal = plotGoals.stream().anyMatch(g -> g.contains(req.agentName));
                req.status = hasGoal ? RequestStatus.APPROVED : RequestStatus.REJECTED;
            } else {
                // Default: approve strengthens, reject weakens (keeps剧情推进)
                req.status = req.type == RequestType.STRENGTHEN
                    ? RequestStatus.APPROVED : RequestStatus.REJECTED;
            }
            req.reviewNote = "silent auto-processed";
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private RequestType classifyRequest(String currentMode, String targetMode) {
        int curr = modeWeight(currentMode);
        int tgt = modeWeight(targetMode);
        if (tgt < curr) return RequestType.DISCONNECT;
        if (tgt == curr) return RequestType.WEAKEN;
        if (tgt > curr && !targetMode.isEmpty()) return RequestType.STRENGTHEN;
        return RequestType.CONNECT;
    }

    private int modeWeight(String mode) {
        return switch (mode) {
            case "isolated" -> 0;
            case "weak" -> 1;
            case "merged" -> 2;
            default -> 1;
        };
    }

    private Optional<TrackChangeRequest> findRequest(String sessionId, String requestId) {
        return sessionRequests.getOrDefault(sessionId, List.of()).stream()
            .filter(r -> r.id.equals(requestId)).findFirst();
    }

    private Optional<TrackChangeRequest> findRequestForAgent(String sessionId, String agentName) {
        List<TrackChangeRequest> pending = getPendingRequests(sessionId);
        return pending.stream().filter(r -> r.agentName.equals(agentName)).findFirst();
    }

    public void clearSession(String sessionId) {
        sessionRequests.remove(sessionId);
    }
}
