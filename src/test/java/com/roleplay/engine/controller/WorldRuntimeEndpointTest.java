package com.roleplay.engine.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roleplay.engine.service.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorldRuntimeEndpointTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired SessionRegistry sessions;

    @Test
    void ambientExtraIsVisibleWithoutCreatingAFullAgent() throws Exception {
        String roleId = "endpoint-extra-1";
        mockMvc.perform(post("/api/world/extras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "session_id", "simulation", "role_id", roleId,
                                "name", "报童", "line", "号外！号外！"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tier").value("AMBIENT"));

        mockMvc.perform(get("/api/world/state").param("session_id", "simulation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ambient_agents[0].roleId").value(roleId))
                .andExpect(jsonPath("$.ambient_agents[0].ambient").value(true));

        mockMvc.perform(post("/api/world/extras/{roleId}/interact", roleId)
                        .param("session_id", "simulation")
                        .param("kind", "relationship"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interactionCount").value(0));
    }

    @Test
    void inputMailboxAcceptsRetryableIdAndRejectsDuplicate() throws Exception {
        String sessionId = "mailbox-endpoint-session";
        sessions.getOrCreate(sessionId);
        Map<String, Object> body = Map.of("session_id", sessionId,
                "input_id", "same-input", "content", "先排队", "priority", "HIGH");
        String json = mapper.writeValueAsString(body);
        mockMvc.perform(post("/api/world/input").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true));
        mockMvc.perform(post("/api/world/input").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    void rejectsUnknownWorldCommandType() throws Exception {
        mockMvc.perform(post("/api/world/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "session_id", "endpoint-world", "type", "DELETE_DATABASE"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAutomaticMapPublication() throws Exception {
        mockMvc.perform(post("/api/world/maps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "session_id", "map-session", "auto_publish", true,
                                "idempotency_key", "unsafe-auto"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("disabled")));
    }
}
