package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.service.MimoTtsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P-0817-A（MiMo TTS）：端点层验证 —— @SpringBootTest MOCK + MockMvc（真实 Spring 容器 +
 * 真实 controller 路由，RANDOM_PORT/H2 mem 走 application-test.yml，D-008 基建），
 * {@link MimoTtsService} 以 @MockBean 替换（零真实 API 调用）。
 *
 * <p>覆盖：同步合成 json=true（base64）/ 原始字节（audio/wav + X-Tts-Mode 头）、text 空 400、
 * 未知 mode 400、clone 缺 voice_data 400、按角色名解析声线（真实角色库落库链路）、
 * 异步任务（提交→轮询 done→未知任务 404）、/voices、/status、/voice-config。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MimoTtsEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MimoTtsService tts;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final byte[] AUDIO = {1, 2, 3, 4, 5};
    private static final MimoTtsService.TtsResult RESULT =
            new MimoTtsService.TtsResult(AUDIO, "wav", "你好，世界。", "mimo-v2.5-tts", 5L);

    private String postJson(String url, Object body) throws Exception {
        MvcResult r = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andReturn();
        return r.getResponse().getContentAsString();
    }

    // ── 同步合成 ──────────────────────────────────────────────

    @Test
    @DisplayName("synthesize json=true → 200 base64 音频 + transcript + mode")
    void synthesizeJsonReturnsBase64() throws Exception {
        when(tts.synthesize(anyString(), any())).thenReturn(RESULT);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", "你好，世界。");
        body.put("mode", "basic");
        body.put("voice", "mimo_default");

        mockMvc.perform(post("/api/tts/mimo/synthesize").param("json", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audio_base64").value(Base64.getEncoder().encodeToString(AUDIO)))
                .andExpect(jsonPath("$.format").value("wav"))
                .andExpect(jsonPath("$.transcript").value("你好，世界。"))
                .andExpect(jsonPath("$.model").value("mimo-v2.5-tts"))
                .andExpect(jsonPath("$.mode").value("basic"))
                .andExpect(jsonPath("$.bytes").value(AUDIO.length));

        ArgumentCaptor<MimoTtsService.VoiceSpec> captor =
                ArgumentCaptor.forClass(MimoTtsService.VoiceSpec.class);
        verify(tts).synthesize(org.mockito.ArgumentMatchers.eq("你好，世界。"), captor.capture());
        assertEquals(MimoTtsService.Mode.BASIC, captor.getValue().mode());
        assertEquals("mimo_default", captor.getValue().voiceName());
    }

    @Test
    @DisplayName("synthesize 默认 → 200 原始 WAV 字节 + X-Tts-Mode 头")
    void synthesizeRawBytes() throws Exception {
        when(tts.synthesize(anyString(), any())).thenReturn(RESULT);

        MvcResult r = mockMvc.perform(post("/api/tts/mimo/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"原始字节测试\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"))
                .andExpect(header().string("X-Tts-Mode", "basic"))
                .andExpect(header().string("X-Tts-Format", "wav"))
                .andReturn();
        assertArrayEquals(AUDIO, r.getResponse().getContentAsByteArray());
    }

    @Test
    @DisplayName("text 为空 → 400")
    void blankTextReturns400() throws Exception {
        mockMvc.perform(post("/api/tts/mimo/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("未知 mode → 400")
    void unknownModeReturns400() throws Exception {
        mockMvc.perform(post("/api/tts/mimo/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\",\"mode\":\"banana\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("clone 缺 voice_data → 400")
    void cloneWithoutVoiceDataReturns400() throws Exception {
        mockMvc.perform(post("/api/tts/mimo/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\",\"mode\":\"clone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("服务层未启用 → 503")
    void disabledReturns503() throws Exception {
        when(tts.synthesize(anyString(), any()))
                .thenThrow(new IllegalStateException("MiMo TTS 未启用（roleplay.tts.mimo.enabled=false）"));
        mockMvc.perform(post("/api/tts/mimo/synthesize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    // ── 角色声线解析 ──────────────────────────────────────────

    @Test
    @DisplayName("按角色名解析声线（真实角色库落库链路）→ design 模式透传")
    void characterVoiceResolution() throws Exception {
        String name = "tts-char-" + System.currentTimeMillis();
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("name", name);
        ch.put("persona", "测试角色");
        ch.put("voice", "");
        ch.put("background", "");
        ch.put("voice_mode", "design");
        ch.put("voice_data", "低沉沙哑的男声");
        postJson("/api/characters", ch);

        when(tts.synthesize(anyString(), any())).thenReturn(RESULT);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", "角色声线测试");
        body.put("character", name);
        postJson("/api/tts/mimo/synthesize", body);

        ArgumentCaptor<MimoTtsService.VoiceSpec> captor =
                ArgumentCaptor.forClass(MimoTtsService.VoiceSpec.class);
        verify(tts).synthesize(org.mockito.ArgumentMatchers.eq("角色声线测试"), captor.capture());
        assertEquals(MimoTtsService.Mode.DESIGN, captor.getValue().mode());
        assertEquals("低沉沙哑的男声", captor.getValue().voiceData());
    }

    @Test
    @DisplayName("voice-config/{name} → 200 角色声线配置；未知角色 → 404")
    void voiceConfigEndpoint() throws Exception {
        when(tts.statusMap()).thenReturn(Map.of("provider", "xiaomimimo", "configured", true));
        String name = "tts-cfg-" + System.currentTimeMillis();
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("name", name);
        ch.put("persona", "");
        ch.put("voice", "");
        ch.put("background", "");
        ch.put("voice_mode", "clone");
        ch.put("voice_data", "D:/ref.wav");
        postJson("/api/characters", ch);

        mockMvc.perform(get("/api/tts/mimo/voice-config/" + name))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.character").value(name))
                .andExpect(jsonPath("$.voice_mode").value("clone"))
                .andExpect(jsonPath("$.voice_data").value("D:/ref.wav"))
                .andExpect(jsonPath("$.tts.configured").exists());

        mockMvc.perform(get("/api/tts/mimo/voice-config/no-such-char-xyz"))
                .andExpect(status().isNotFound());
    }

    // ── 异步任务 ──────────────────────────────────────────────

    @Test
    @DisplayName("async 提交 → job_id；轮询 result → done + audio_base64；未知任务 → 404")
    void asyncJobFlow() throws Exception {
        when(tts.isEnabled()).thenReturn(true);
        when(tts.synthesizeAsync(anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(RESULT));

        String resp = postJson("/api/tts/mimo/synthesize/async",
                Map.of("text", "异步合成测试", "mode", "basic"));
        assertEquals("pending", mapper.readTree(resp).get("status").asText());
        String jobId = mapper.readTree(resp).get("job_id").asText();
        assertEquals(36, jobId.length());

        mockMvc.perform(get("/api/tts/mimo/result/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.audio_base64").value(Base64.getEncoder().encodeToString(AUDIO)));

        mockMvc.perform(get("/api/tts/mimo/result/no-such-job"))
                .andExpect(status().isNotFound());
    }

    // ── 查询端点 ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /voices → 内置音色清单")
    void voicesEndpoint() throws Exception {
        when(tts.builtinVoices()).thenReturn(java.util.List.of("mimo_default"));
        mockMvc.perform(get("/api/tts/mimo/voices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("mimo_default"));
    }

    @Test
    @DisplayName("GET /status → 运行时状态")
    void statusEndpoint() throws Exception {
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("provider", "xiaomimimo");
        st.put("enabled", true);
        st.put("configured", true);
        when(tts.statusMap()).thenReturn(st);
        mockMvc.perform(get("/api/tts/mimo/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("xiaomimimo"))
                .andExpect(jsonPath("$.configured").value(true));
    }
}
