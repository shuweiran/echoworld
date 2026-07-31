package com.roleplay.engine.controller;

import com.roleplay.engine.service.WhisperService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Voice loop control endpoints.
 * Maps from Python api/routes_voice.py.
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final WhisperService whisper;
    private boolean voiceLoopRunning = false;

    public VoiceController(WhisperService whisper) {
        this.whisper = whisper;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
            "running", voiceLoopRunning,
            "engine", "edge",
            "auto_start", false
        ));
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        voiceLoopRunning = true;
        return ResponseEntity.ok(Map.of("status", "started"));
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        voiceLoopRunning = false;
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    /**
     * Transcribe an uploaded audio clip to text (D9 fix).
     * Frontend contract: POST multipart/form-data, field "audio" (e.g. voice.webm, audio/webm),
     * response JSON {"text": "..."}; empty text means nothing was recognized.
     */
    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestParam(value = "audio", required = false) MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("text", ""));
        }
        String format = resolveFormat(audio);
        String text;
        try {
            text = whisper.transcribe(audio.getBytes(), format);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("text", ""));
        }
        return ResponseEntity.ok(Map.of("text", text == null ? "" : text));
    }

    /**
     * Derive an audio format hint (file extension preferred, then content type).
     * Used as WhisperService's format parameter.
     */
    private String resolveFormat(MultipartFile audio) {
        String name = audio.getOriginalFilename();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                return name.substring(dot + 1).toLowerCase();
            }
        }
        String contentType = audio.getContentType();
        if (contentType != null && contentType.startsWith("audio/")) {
            return contentType.substring("audio/".length());
        }
        return "webm";
    }
}
