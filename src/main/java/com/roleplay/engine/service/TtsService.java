package com.roleplay.engine.service;

import com.roleplay.engine.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * TTS service — Edge TTS and CosyVoice streaming.
 * Maps from Python services/tts_service.py.
 *
 * <p>Edge TTS: free, no API key, multi-language streaming.
 * CosyVoice: requires DashScope API key, higher quality.
 *
 * <p>D20: 引擎/开关/默认音色以 {@link AppConfig.VoiceConfig} 为运行时单一事实源
 * （{@code POST /api/config/voice} 写入，此处读取，立即生效）。
 */
@Service
public class TtsService {
    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    public enum Engine { EDGE, COSYVOICE }

    private final AppConfig appConfig;
    /** 本地镜像（兼容旧 setter 调用），读取一律以 AppConfig 为准。 */
    private Engine currentEngine = Engine.EDGE;
    private boolean enabled = true;

    public TtsService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.currentEngine = resolveEngine(appConfig.getVoice().getEngine());
        this.enabled = appConfig.getVoice().isEnabled();
    }

    private static Engine resolveEngine(String engine) {
        return engine != null && "cosyvoice".equalsIgnoreCase(engine.trim())
                ? Engine.COSYVOICE : Engine.EDGE;
    }

    /** 当前引擎（D20：优先读取运行时语音配置）。 */
    public Engine getEngine() {
        return resolveEngine(appConfig.getVoice().getEngine());
    }

    /** 当前开关（D20：优先读取运行时语音配置）。 */
    public boolean isEnabled() {
        return appConfig.getVoice().isEnabled();
    }

    /** 程序化设置引擎（同步写回 AppConfig，保证单一事实源）。 */
    public void setEngine(Engine engine) {
        this.currentEngine = engine;
        appConfig.getVoice().setEngine(engine == Engine.COSYVOICE ? "cosyvoice" : "edge");
    }

    /** 程序化开关（同步写回 AppConfig）。 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        appConfig.getVoice().setEnabled(enabled);
    }

    /**
     * Stream TTS audio for the given text.
     * In production this would call Edge TTS CLI or CosyVoice API.
     * Returns a placeholder URL for now.
     */
    public String synthesize(String text, String voice, Engine engine) {
        if (!isEnabled() || text == null || text.isBlank()) return "";
        log.debug("TTS: engine={}, voice={}, text={}chars", engine, voice, text.length());
        // TODO: Actual TTS integration
        // Edge TTS: edge-tts --voice zh-CN-Xiaoxiao --text "..." --write-media output.mp3
        // CosyVoice: REST API call to dashscope with voice_id
        return "/api/voice/audio/placeholder_" + System.currentTimeMillis() + ".mp3";
    }

    /** D20: voice 为空时回退到运行时配置的默认音色。 */
    public String synthesize(String text, String voice) {
        String v = (voice == null || voice.isBlank()) ? appConfig.getVoice().getVoice() : voice;
        return synthesize(text, v, getEngine());
    }

    /**
     * Pick the best engine based on scene complexity.
     * Single character → Edge (low latency). Multi-character → CosyVoice (quality).
     */
    public Engine selectEngine(int agentCount, boolean isNarration) {
        if (agentCount <= 2 && !isNarration) return Engine.EDGE;
        return Engine.COSYVOICE;
    }
}
