package com.roleplay.engine.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application configuration — single source of truth for all engine parameters.
 * Maps from Python backend/config.py （AppConfig + nested configs）.
 *
 * <p>All fields have sensible defaults. API keys are resolved from
 * environment variables, file persistence, or runtime overrides.</p>
 *
 * <p>D25: {@code @ConfigurationProperties(prefix = "roleplay")} binds all
 * {@code roleplay.*} keys from application.yml (incl. the
 * {@code ${ROLEPLAY_LLM_API_KEY:}} environment-variable placeholder, which
 * Spring resolves at startup) into this bean. Same pattern as
 * {@code McpConfiguration}. Runtime overrides via
 * {@code /api/config/apikey} still win because they write to this same bean
 * after startup binding.</p>
 */
@org.springframework.stereotype.Component
@ConfigurationProperties(prefix = "roleplay")
public class AppConfig {

    private LLMConfig llm = new LLMConfig();
    private MemoryConfig memory = new MemoryConfig();
    private ArbiterConfig arbiter = new ArbiterConfig();
    private MonitorConfig monitor = new MonitorConfig();
    private RoundConfig round = new RoundConfig();
    private ModeConfig mode = new ModeConfig();
    /** 游戏相关配置（D27：roleplay.game.*，审批门接线）。 */
    private GameConfig game = new GameConfig();
    private FrontendConfig frontend = new FrontendConfig();
    /** 语音配置（D20：/api/config/voice 运行时落地，TtsService 读取）。 */
    private VoiceConfig voice = new VoiceConfig();
    /** 广播配置（演讲与广播合并地基）：roleplay.broadcast.*，AnnouncementService 节流参数。 */
    private BroadcastConfig broadcast = new BroadcastConfig();

    private String host = "0.0.0.0";
    private int port = 8000;
    private String interruptMode = "always";

    // ── Getters & Setters ──────────────────────────────────────

    public LLMConfig getLlm() { return llm; }
    public void setLlm(LLMConfig llm) { this.llm = llm; }

    public MemoryConfig getMemory() { return memory; }
    public void setMemory(MemoryConfig memory) { this.memory = memory; }

    public ArbiterConfig getArbiter() { return arbiter; }
    public void setArbiter(ArbiterConfig arbiter) { this.arbiter = arbiter; }

    public MonitorConfig getMonitor() { return monitor; }
    public void setMonitor(MonitorConfig monitor) { this.monitor = monitor; }

    public RoundConfig getRound() { return round; }
    public void setRound(RoundConfig round) { this.round = round; }

    public ModeConfig getMode() { return mode; }
    public void setMode(ModeConfig mode) { this.mode = mode; }

    public GameConfig getGame() { return game; }
    public void setGame(GameConfig game) { this.game = game; }

    public FrontendConfig getFrontend() { return frontend; }
    public void setFrontend(FrontendConfig frontend) { this.frontend = frontend; }

    public VoiceConfig getVoice() { return voice; }
    public void setVoice(VoiceConfig voice) { this.voice = voice; }

    public BroadcastConfig getBroadcast() { return broadcast; }
    public void setBroadcast(BroadcastConfig broadcast) { this.broadcast = broadcast; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    // ── Nested config classes ──────────────────────────────────

    public static class LLMConfig {
        private String apiKey = "";
        private String apiBase = "https://api.deepseek.com";
        private String model = "deepseek-v4-flash";
        private int maxTokens = 4096;
        private double temperature = 0.9;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    public static class MemoryConfig {
        private int shortTermRounds = 20;
        private int summaryInterval = 10;
        private boolean resume = false;

        public int getShortTermRounds() { return shortTermRounds; }
        public void setShortTermRounds(int shortTermRounds) { this.shortTermRounds = shortTermRounds; }
        public int getSummaryInterval() { return summaryInterval; }
        public void setSummaryInterval(int summaryInterval) { this.summaryInterval = summaryInterval; }
        public boolean isResume() { return resume; }
        public void setResume(boolean resume) { this.resume = resume; }
    }

    public static class ArbiterConfig {
        private boolean enabled = true;
        private int loopDetectionRounds = 3;
        private String arbiterModel = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getLoopDetectionRounds() { return loopDetectionRounds; }
        public void setLoopDetectionRounds(int loopDetectionRounds) { this.loopDetectionRounds = loopDetectionRounds; }
        public String getArbiterModel() { return arbiterModel; }
        public void setArbiterModel(String arbiterModel) { this.arbiterModel = arbiterModel; }
    }

    public static class MonitorConfig {
        private boolean enabled = true;
        private double budgetUsd = 10.0;
        private String fallbackModel = "deepseek-v4-flash";
        private int timeoutSeconds = 60;
        private int maxRetries = 3;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getBudgetUsd() { return budgetUsd; }
        public void setBudgetUsd(double budgetUsd) { this.budgetUsd = budgetUsd; }
        public String getFallbackModel() { return fallbackModel; }
        public void setFallbackModel(String fallbackModel) { this.fallbackModel = fallbackModel; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    }

    public static class RoundConfig {
        private boolean enabled = true;
        private boolean parallelAgents = true;
        private int arbiterMaxTokens = 150;
        private int agentMaxTokens = 300;
        private int compressionInterval = 5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isParallelAgents() { return parallelAgents; }
        public void setParallelAgents(boolean parallelAgents) { this.parallelAgents = parallelAgents; }
        public int getArbiterMaxTokens() { return arbiterMaxTokens; }
        public void setArbiterMaxTokens(int arbiterMaxTokens) { this.arbiterMaxTokens = arbiterMaxTokens; }
        public int getAgentMaxTokens() { return agentMaxTokens; }
        public void setAgentMaxTokens(int agentMaxTokens) { this.agentMaxTokens = agentMaxTokens; }
        public int getCompressionInterval() { return compressionInterval; }
        public void setCompressionInterval(int compressionInterval) { this.compressionInterval = compressionInterval; }
    }

    public static class ModeConfig {
        private String mode = "free";         // free | protagonist | multi_track | director | werewolf
        private String protagonist = "";
        private String directorCharacter = "";
        private List<String> advancedTracks = new ArrayList<>();
        private String language = "zh";
        private String trackActivity = "auto";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getProtagonist() { return protagonist; }
        public void setProtagonist(String protagonist) { this.protagonist = protagonist; }
        public String getDirectorCharacter() { return directorCharacter; }
        public void setDirectorCharacter(String dc) { this.directorCharacter = dc; }
        public List<String> getAdvancedTracks() { return advancedTracks; }
        public void setAdvancedTracks(List<String> advancedTracks) { this.advancedTracks = advancedTracks; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getTrackActivity() { return trackActivity; }
        public void setTrackActivity(String trackActivity) { this.trackActivity = trackActivity; }
        /** 兼容 yml 的 {@code roleplay.mode.default}（default 是 Java 关键字，不能作字段名，故映射到 mode）。 */
        public void setDefault(String mode) { this.mode = mode; }
    }

    /** 游戏相关配置（D27）：映射 yml {@code roleplay.game.*}。 */
    public static class GameConfig {
        /** 审批门配置（D6D7/D27）：{@code roleplay.game.approval.*}。 */
        private ApprovalConfig approval = new ApprovalConfig();

        public ApprovalConfig getApproval() { return approval; }
        public void setApproval(ApprovalConfig approval) { this.approval = approval; }

        /** 审批门：true=手动审批（挂起等待 DM 审批，超时自动驳回回滚），false=自动通过。 */
        public static class ApprovalConfig {
            private boolean enabled = true;
            private long timeoutSeconds = 60;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public long getTimeoutSeconds() { return timeoutSeconds; }
            public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        }
    }

    public static class FrontendConfig {
        private boolean devMode = false;
        private int devPort = 5173;
        private String distDir = "frontend/dist";

        public boolean isDevMode() { return devMode; }
        public void setDevMode(boolean devMode) { this.devMode = devMode; }
        public int getDevPort() { return devPort; }
        public void setDevPort(int devPort) { this.devPort = devPort; }
        public String getDistDir() { return distDir; }
        public void setDistDir(String distDir) { this.distDir = distDir; }
    }

    /** 语音配置（D20）：engine=edge|cosyvoice，voice 为默认音色，autoSelect 自动选引擎。 */
    public static class VoiceConfig {
        private boolean enabled = true;
        private String engine = "edge";
        private boolean autoSelect = true;
        private String voice = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }
        public boolean isAutoSelect() { return autoSelect; }
        public void setAutoSelect(boolean autoSelect) { this.autoSelect = autoSelect; }
        public String getVoice() { return voice; }
        public void setVoice(String voice) { this.voice = voice; }
    }

    /**
     * 广播节流配置（演讲与广播合并地基）：映射 yml {@code roleplay.broadcast.*}。
     * window-ms=滑动窗口长度、max-per-window=每窗口每 channel 上限、
     * max-pending=优先级队列深度上限、recent-ring-size=断线补发环形缓冲大小。
     *
     * <p>{@code speech-mode} 为演讲广播模式开关（默认 merged=正式版）：
     * <ul>
     *   <li>{@code merged}（默认，正式版合并方案）——走方案A 管线架构
     *       （ConversationManager 回调 → SimulationService 判定 → enqueueAutoSpeech），
     *       听众判定用 HearingSystem 声学模型（computeAudibility+canHear 距离衰减，
     *       半径内可听听众计数，单事实源 {@code HearingSystem.countHearingListeners}），
     *       无听众时是否升级全局公告由 {@code fallback-to-global} 决定；</li>
     *   <li>{@code auto}（方案A 旧行为，供回退对比）——判定用
     *       ModeClassifier.wouldOthersListen 硬编码启发式（2.5×hearRange/距离>50/≥2），
     *       无听众恒升级全局公告（不读 fallback 配置）；</li>
     *   <li>{@code split}（方案B 旧行为，供回退对比）——SpeechStrategy.processResults
     *       内联直接调 AnnouncementService.enqueue（演讲即刻变区域广播，携带 speaker
     *       坐标与半径）。同一运行实例可经 POST /api/broadcast/mode 运行时切换，
     *       各路径互斥（merged/auto 走回调、split 走内联），不会重复推送。</li>
     * </ul>
     *
     * <p>{@code fallback-to-global}=无听众兜底（正式版 merged 生效）：true=无听众时自动
     * 升级全局公告（信息不哑火，默认）；false=不升级，仅区域演讲（纯空间语义，半径外自然无人展示）。
     *
     * <p>{@code script-phase-broadcast}=剧本杀阶段切换 SYSTEM 广播总开关（默认 true=启用）：
     * 五处阶段切换（initGame/startDiscussion/startVoting/resolveVote/confirmEnded）发
     * SYSTEM 级 announcement 到全局横幅通道，与 script_phase SSE 会话面板通道并存。
     */
    public static class BroadcastConfig {
        private long windowMs = 1000;
        private int maxPerWindow = 5;
        private int maxPending = 20;
        private int recentRingSize = 100;
        /** 演讲广播模式：merged=正式版（默认）｜auto=方案A 旧行为｜split=方案B 旧行为。 */
        private String speechMode = "merged";
        /** 无听众兜底：true=自动升级全局公告（默认）；false=不升级，仅区域演讲。 */
        private boolean fallbackToGlobal = true;
        /** 剧本杀阶段切换 SYSTEM 广播总开关（默认 true=启用，进正式版）。 */
        private boolean scriptPhaseBroadcast = true;

        public long getWindowMs() { return windowMs; }
        public void setWindowMs(long windowMs) { this.windowMs = windowMs; }
        public int getMaxPerWindow() { return maxPerWindow; }
        public void setMaxPerWindow(int maxPerWindow) { this.maxPerWindow = maxPerWindow; }
        public int getMaxPending() { return maxPending; }
        public void setMaxPending(int maxPending) { this.maxPending = maxPending; }
        public int getRecentRingSize() { return recentRingSize; }
        public void setRecentRingSize(int recentRingSize) { this.recentRingSize = recentRingSize; }
        public String getSpeechMode() { return speechMode; }
        public void setSpeechMode(String speechMode) { this.speechMode = speechMode; }
        public boolean isFallbackToGlobal() { return fallbackToGlobal; }
        public void setFallbackToGlobal(boolean fallbackToGlobal) { this.fallbackToGlobal = fallbackToGlobal; }
        public boolean isScriptPhaseBroadcast() { return scriptPhaseBroadcast; }
        public void setScriptPhaseBroadcast(boolean scriptPhaseBroadcast) { this.scriptPhaseBroadcast = scriptPhaseBroadcast; }
    }
}
