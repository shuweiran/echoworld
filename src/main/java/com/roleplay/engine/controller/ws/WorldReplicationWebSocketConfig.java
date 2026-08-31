package com.roleplay.engine.controller.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WorldReplicationWebSocketConfig implements WebSocketConfigurer {
    private final WorldReplicationWebSocketHandler handler;

    public WorldReplicationWebSocketConfig(WorldReplicationWebSocketHandler handler) { this.handler = handler; }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/world").setAllowedOriginPatterns("*");
    }
}
