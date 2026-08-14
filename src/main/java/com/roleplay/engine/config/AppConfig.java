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
    /** P-0813-F：2D 世界节奏控制（roleplay.pacing.*）——对话密度/时序/移动速度基准。 */
    private PacingConfig pacing = new PacingConfig();
    /** P-0813-I：主控日程（roleplay.director.*）——混合架构「时刻→地点→行为」骨架。 */
    private DirectorConfig director = new DirectorConfig();
    /** P-0815-A：轨道系统（roleplay.track.*）——空间会话距离等。 */
    private TrackConfig track = new TrackConfig();

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

    public PacingConfig getPacing() { return pacing; }
    public void setPacing(PacingConfig pacing) { this.pacing = pacing; }

    public DirectorConfig getDirector() { return director; }
    public void setDirector(DirectorConfig director) { this.director = director; }

    public TrackConfig getTrack() { return track; }
    public void setTrack(TrackConfig track) { this.track = track; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    // ── Nested config classes ──────────────────────────────────

    public static class LLMConfig {
        private String apiKey = "";
        private String apiBase = "https://api.deepseek.com";
        private String model = "deepseek-v4-flash";
        /** 默认 max_tokens 兑底（roleplay.llm.max-tokens）：callJson 传 null 时使用；各调用点显式传值优先（概略 1200 / 完整剧本 4000 / 地图 8000）。 */
        private int maxTokens = 4000;
        /** P-0810-21-D：对话主链路 max_tokens（roleplay.llm.dialogue-max-tokens）：callSync 无参/带 token 入口与 callStream 使用（原硬编码 300 偏短，AI 发言常截断）；显式传值调用点优先。 */
        private int dialogueMaxTokens = 700;
        /** 默认 temperature（roleplay.llm.temperature）：callJson 等结构化生成路径使用；对话主链路 callSync 显式 0.7 不受影响。 */
        private double temperature = 0.1;
        /** 生成确定性 seed（roleplay.llm.seed，DeepSeek 兼容 OpenAI seed）：非空时 buildChatRequest 携带 seed 字段；默认 null=不启用（行为不变）。 */
        private Integer seed = null;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiBase() { return apiBase; }
        public void setApiBase(String apiBase) { this.apiBase = apiBase; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getDialogueMaxTokens() { return dialogueMaxTokens; }
        public void setDialogueMaxTokens(int dialogueMaxTokens) { this.dialogueMaxTokens = dialogueMaxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public Integer getSeed() { return seed; }
        public void setSeed(Integer seed) { this.seed = seed; }
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
        /** C-2 串行调度开关（roleplay.round.serial，默认 false=保持并行；true=同轮按序生成、每完成一个即时入史）。 */
        private boolean serial = false;
        /** P-0813-A：自动续轮延时（roleplay.round.auto-continue-ms，毫秒；默认 3000，0=禁用）。
         *  一般模式每轮完成后延时自动跑下一轮（导演模式 AI 自主推进），玩家发言打断 pending 任务。
         *  P-0814-A：playback-driven=true 时本键被忽略（不再定时自续，改为等「播出完毕」信号）；
         *  playback-driven=false 时仍为本键定时续轮（旧行为回退）。 */
        private long autoContinueMs = 3000;
        /** P-0814-A：点击驱动对话模式总开关（roleplay.round.playback-driven，默认 true=主人拍板新语义）。
         *  true：一轮生成完即停（一般模式 RouterService 轮间 / 2D 世界对话组轮间），
         *  收到前端「播出完毕」信号（POST /api/simulation/playback_done）后才生成下一轮；
         *  无玩家（导演模式）播完即停等导演点击；auto-continue-ms 定时自续被忽略。
         *  false：回退旧行为——一般模式按 auto-continue-ms 定时自续、2D 组按 pacing 间隔自动连跑。 */
        private boolean playbackDriven = true;
        /** P-0814-B：2D 对话组「等待播出完毕」超时（roleplay.round.group-await-timeout-ms，毫秒；默认 30000）。
         *  playback-driven=true 时：无玩家成员的组（AI-AI / 单 AI）等待播出完毕超过该时长自动解散
         *  （防 awaitPlayback 永久阻塞、组内角色冻结）；有玩家成员的组不自动解散（玩家输入/点击唤醒）。
         *  对齐 D-004「阈值勿 hardcode」纪律，由 SimulationService 注入 ConversationManager。 */
        private long groupAwaitTimeoutMs = 30_000;
        /** P-0813-B：校准轮间隔（roleplay.round.calibrate-every；默认 10，0=禁用）。
         *  每 N 个 AI 自主推进轮向会话消息列表注入校准提醒（layer0 前 3 条 + 反差 + 角色关系，防漂移）。
         *  P-0813-C：默认 6→10（调低校准注入频率，给角色更长的自然发挥窗口）。 */
        private int calibrateEvery = 10;
        private int arbiterMaxTokens = 150;
        private int agentMaxTokens = 300;
        private int compressionInterval = 5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isParallelAgents() { return parallelAgents; }
        public void setParallelAgents(boolean parallelAgents) { this.parallelAgents = parallelAgents; }
        public boolean isSerial() { return serial; }
        public void setSerial(boolean serial) { this.serial = serial; }
        public long getAutoContinueMs() { return autoContinueMs; }
        public void setAutoContinueMs(long autoContinueMs) { this.autoContinueMs = autoContinueMs; }
        public boolean isPlaybackDriven() { return playbackDriven; }
        public void setPlaybackDriven(boolean playbackDriven) { this.playbackDriven = playbackDriven; }
        public long getGroupAwaitTimeoutMs() { return groupAwaitTimeoutMs; }
        public void setGroupAwaitTimeoutMs(long groupAwaitTimeoutMs) { this.groupAwaitTimeoutMs = groupAwaitTimeoutMs; }
        public int getCalibrateEvery() { return calibrateEvery; }
        public void setCalibrateEvery(int calibrateEvery) { this.calibrateEvery = calibrateEvery; }
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
    /**
     * 节奏控制配置（P-0813-F）：映射 yml {@code roleplay.pacing.*}，仅 2D 模拟世界生效。
     *
     * <p>目标（主人原话提炼）：①进入对话前降低 AI 对话密度、控制时序；
     * ②进入对话后当前对话轨道全速、其余轨道进一步降频。
     *
     * <ul>
     *   <li>{@code enabled}=总开关（默认 true）。false 时全部参数按原硬编码行为
     *       （倍率 1.0、间隔取旧常量）——剧本杀/狼人杀各局自有的 ConversationManager
     *       实例不接本配置（不注入 pacing=禁用），零影响（模式守卫）；</li>
     *   <li>{@code director-interval-ms}=导演（主控）LLM 轮次间隔（原硬编码 10000；
     *       默认 15000=降低目标覆盖频率）；</li>
     *   <li>{@code conversation-cooldown-ms}=同一对角色解散后再次自动组对话的冷却
     *       （原硬编码 5000；默认 8000=降低对话建立密度）；</li>
     *   <li>{@code round-cooldown-ms}=对话轮次基础间隔（DYAD/DEBATE/PUBLIC_SPEAKING，
     *       原硬编码 2000；进入对话后当前轨道全速用此值）；</li>
     *   <li>{@code group-round-cooldown-ms}=群聊（GROUP_DISCUSSION）轮次基础间隔
     *       （原硬编码 3000）；</li>
     *   <li>{@code idle-round-multiplier}=未进入对话（无玩家对话轨道）时轮次间隔倍率
     *       （默认 1.5：所有轨道放慢，降低未对话态密度）；</li>
     *   <li>{@code inactive-track-multiplier}=进入对话后非当前轨道的轮次间隔倍率
     *       （P-0813-H 需求更正：默认 2.0→4.0——其他轨道不挂起、保持并行推进，但轮次间隔
     *       大幅拉长（对话时间延长、密度降低），比 F 的 ×2 更强，可调 ×3~×5）；</li>
     *   <li>{@code speech-bubble-hold-ms}=非玩家轨道发言的气泡停留/展示时长基准
     *       （P-0813-H，默认 4000；经 conversation-status 的 pacing.speechBubbleHoldMs
     *       下发，前端可用作气泡展示时长/展示间隔参数）；</li>
     *   <li>{@code move-speed-base}=2D 角色移动速度基准 px/s（原硬编码 50；默认 45）；</li>
     *   <li>{@code move-speed-random-range}=移动速度随机幅度（原硬编码 60；默认 35，
     *       实际速度 = base + rand×range → 45-80）。</li>
     * </ul>
     *
     * <p>P-0813-H 需求更正（主人澄清）：不做全局串行队列——多个对话群同时存在、同时推进
     * （并行保持）；轨道内成员轮流发言（回合制）为既有机制零改动；节奏控制仅作用于轮次间隔
     * （玩家轨道全速、其他轨道大幅拉长）与气泡展示时长下发。
     * 玩家手动目标（manualTarget，/target 端点）不受降频影响：降频只作用于
     * 对话轮次间隔与导演轮间隔，不触碰目标/移动指令（玩家控制优先，同 P-0813-E）。
     */
    /**
     * P-0813-I：主控日程配置（混合架构）——映射 yml {@code roleplay.director.*}。
     *
     * <p>主控（WorldDirector 系）给每个角色下发轻量**日程窗口**（时段→区域→行为类型），
     * 角色 LLM 不再每轮重决策「去哪/干什么」，只负责窗口内的行为细节 + 全部台词
     * （节奏可控 + 个性保留 + LLM 调用量下降）。
     *
     * <ul>
     *   <li>{@code schedule-enabled}=总开关（默认 true）。false 时 SchedulerService 全部
     *       no-op：移动目标回退导演 LLM 每轮重决策 + MovementConstraint 既有行为；</li>
     *   <li>{@code schedule-window-duration-ms}=窗口时长（默认 300000ms=5 分钟）。
     *       时段索引 = floorDiv(now, windowDurationMs) % slots（与真实时钟解耦，可单测）；</li>
     *   <li>{@code schedule-slots}=时段数量（默认 6）——每个角色一天 6 个窗口，
     *       对齐星露谷「一天 4–6 个日程点」的粒度哲学。</li>
     * </ul>
     */
    public static class DirectorConfig {
        private boolean scheduleEnabled = true;
        private long scheduleWindowDurationMs = 300_000;
        private int scheduleSlots = 6;

        public boolean isScheduleEnabled() { return scheduleEnabled; }
        public void setScheduleEnabled(boolean scheduleEnabled) { this.scheduleEnabled = scheduleEnabled; }
        public long getScheduleWindowDurationMs() { return scheduleWindowDurationMs; }
        public void setScheduleWindowDurationMs(long scheduleWindowDurationMs) {
            this.scheduleWindowDurationMs = scheduleWindowDurationMs;
        }
        public int getScheduleSlots() { return scheduleSlots; }
        public void setScheduleSlots(int scheduleSlots) { this.scheduleSlots = scheduleSlots; }
    }

    /**
     * P-0815-A：轨道系统配置——映射 yml {@code roleplay.track.*}。
     *
     * <p>{@code conversation-distance}=空间会话距离（px，默认 70）：两两距离 &lt; 该值 →
     * MERGED（可对话，可见对方）。修正历史单位错位——需求文档「5 格」中的「格」与世界
     * 像素坐标混用（5.0 按 px 用时 5px≈贴脸，近距离对话几乎永不触发）；统一 px 语义后
     * 默认 70px（调研报告 2.4 #5 建议 60-80px 区间）。经 SimulationService 接线到
     * TrackDirectorService.setConversationDistance（重建 SpatialTrackResolver）。
     */
    public static class TrackConfig {
        /** 空间会话距离 px（roleplay.track.conversation-distance）。 */
        private double conversationDistance = 70.0;

        public double getConversationDistance() { return conversationDistance; }
        public void setConversationDistance(double conversationDistance) {
            this.conversationDistance = conversationDistance;
        }
    }

    public static class PacingConfig {
        private boolean enabled = true;
        private long directorIntervalMs = 15_000;
        private long conversationCooldownMs = 8_000;
        private long roundCooldownMs = 2_000;
        private long groupRoundCooldownMs = 3_000;
        private double idleRoundMultiplier = 1.5;
        private double inactiveTrackMultiplier = 4.0;
        private double moveSpeedBase = 45;
        private double moveSpeedRandomRange = 35;
        /** P-0814-I：玩家角色移动速度基准 px/s（roleplay.pacing.move-speed-player-base，默认 90）——
         *  playerControlled 角色提速（WASD/点击更跟手），AI 保持 moveSpeedBase 45-80 降密度。 */
        private double moveSpeedPlayerBase = 90;
        /** P-0814-I：玩家角色速度随机幅度（默认 15 → 玩家 90-105 px/s）。 */
        private double moveSpeedPlayerRandomRange = 15;
        /** P-0813-H：非玩家轨道发言的气泡停留/展示时长基准 ms（默认 4000；conversation-status 下发前端）。 */
        private long speechBubbleHoldMs = 4_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getDirectorIntervalMs() { return directorIntervalMs; }
        public void setDirectorIntervalMs(long directorIntervalMs) { this.directorIntervalMs = directorIntervalMs; }
        public long getConversationCooldownMs() { return conversationCooldownMs; }
        public void setConversationCooldownMs(long conversationCooldownMs) { this.conversationCooldownMs = conversationCooldownMs; }
        public long getRoundCooldownMs() { return roundCooldownMs; }
        public void setRoundCooldownMs(long roundCooldownMs) { this.roundCooldownMs = roundCooldownMs; }
        public long getGroupRoundCooldownMs() { return groupRoundCooldownMs; }
        public void setGroupRoundCooldownMs(long groupRoundCooldownMs) { this.groupRoundCooldownMs = groupRoundCooldownMs; }
        public double getIdleRoundMultiplier() { return idleRoundMultiplier; }
        public void setIdleRoundMultiplier(double idleRoundMultiplier) { this.idleRoundMultiplier = idleRoundMultiplier; }
        public double getInactiveTrackMultiplier() { return inactiveTrackMultiplier; }
        public void setInactiveTrackMultiplier(double inactiveTrackMultiplier) { this.inactiveTrackMultiplier = inactiveTrackMultiplier; }
        public double getMoveSpeedBase() { return moveSpeedBase; }
        public void setMoveSpeedBase(double moveSpeedBase) { this.moveSpeedBase = moveSpeedBase; }
        public double getMoveSpeedRandomRange() { return moveSpeedRandomRange; }
        public void setMoveSpeedRandomRange(double moveSpeedRandomRange) { this.moveSpeedRandomRange = moveSpeedRandomRange; }
        public double getMoveSpeedPlayerBase() { return moveSpeedPlayerBase; }
        public void setMoveSpeedPlayerBase(double moveSpeedPlayerBase) { this.moveSpeedPlayerBase = moveSpeedPlayerBase; }
        public double getMoveSpeedPlayerRandomRange() { return moveSpeedPlayerRandomRange; }
        public void setMoveSpeedPlayerRandomRange(double moveSpeedPlayerRandomRange) { this.moveSpeedPlayerRandomRange = moveSpeedPlayerRandomRange; }
        public long getSpeechBubbleHoldMs() { return speechBubbleHoldMs; }
        public void setSpeechBubbleHoldMs(long speechBubbleHoldMs) { this.speechBubbleHoldMs = Math.max(1, speechBubbleHoldMs); }
    }

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
