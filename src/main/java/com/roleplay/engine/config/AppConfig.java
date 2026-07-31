package com.roleplay.engine.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Application configuration — single source of truth for all engine parameters.
 * Maps from Python backend/config.py （AppConfig + nested configs）.
 *
 * <p>All fields have sensible defaults. API keys are resolved from
 * environment variables, file persistence, or runtime overrides.</p>
 */
@org.springframework.stereotype.Component
public class AppConfig {

    private LLMConfig llm = new LLMConfig();
    private MemoryConfig memory = new MemoryConfig();
    private ArbiterConfig arbiter = new ArbiterConfig();
    private MonitorConfig monitor = new MonitorConfig();
    private RoundConfig round = new RoundConfig();
    private ModeConfig mode = new ModeConfig();
    private FrontendConfig frontend = new FrontendConfig();
    /** 语音配置（D20：/api/config/voice 运行时落地，TtsService 读取）。 */
    private VoiceConfig voice = new VoiceConfig();

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

    public FrontendConfig getFrontend() { return frontend; }

    public VoiceConfig getVoice() { return voice; }
    public void setVoice(VoiceConfig voice) { this.voice = voice; }

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
        public double getTemperature() { return temperature; }
    }

    public static class MemoryConfig {
        private int shortTermRounds = 20;
        private int summaryInterval = 10;
        private boolean resume = false;

        public int getShortTermRounds() { return shortTermRounds; }
        public int getSummaryInterval() { return summaryInterval; }
        public boolean isResume() { return resume; }
    }

    public static class ArbiterConfig {
        private boolean enabled = true;
        private int loopDetectionRounds = 3;
        private String arbiterModel = "";

        public boolean isEnabled() { return enabled; }
        public int getLoopDetectionRounds() { return loopDetectionRounds; }
        public String getArbiterModel() { return arbiterModel; }
    }

    public static class MonitorConfig {
        private boolean enabled = true;
        private double budgetUsd = 10.0;
        private String fallbackModel = "deepseek-v4-flash";
        private int timeoutSeconds = 60;
        private int maxRetries = 3;

        public boolean isEnabled() { return enabled; }
        public double getBudgetUsd() { return budgetUsd; }
        public String getFallbackModel() { return fallbackModel; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public int getMaxRetries() { return maxRetries; }
    }

    public static class RoundConfig {
        private boolean enabled = true;
        private boolean parallelAgents = true;
        private int arbiterMaxTokens = 150;
        private int agentMaxTokens = 300;
        private int compressionInterval = 5;

        public boolean isParallelAgents() { return parallelAgents; }
        public int getArbiterMaxTokens() { return arbiterMaxTokens; }
        public int getAgentMaxTokens() { return agentMaxTokens; }
        public int getCompressionInterval() { return compressionInterval; }
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
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
        public String getTrackActivity() { return trackActivity; }
    }

    public static class FrontendConfig {
        private boolean devMode = false;
        private int devPort = 5173;
        private String distDir = "frontend/dist";

        public boolean isDevMode() { return devMode; }
        public int getDevPort() { return devPort; }
        public String getDistDir() { return distDir; }
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
}
