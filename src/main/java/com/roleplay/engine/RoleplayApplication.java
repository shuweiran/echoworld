package com.roleplay.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.awt.Desktop;
import java.net.URI;

/**
 * Spring Boot application entry point.
 * Roleplay v4 Engine — Java (Spring Boot 3.4 + JDK 21).
 */
@SpringBootApplication
@EnableScheduling
public class RoleplayApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoleplayApplication.class, args);
        openLocalUi();
    }

    /** Open the packaged desktop experience after the embedded server is ready. */
    private static void openLocalUi() {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("ROLEPLAY_NO_BROWSER", "false"))) {
            return;
        }
        Thread opener = new Thread(() -> {
            try {
                Thread.sleep(900);
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI.create("http://127.0.0.1:8000/"));
                }
            } catch (Exception ignored) {
                // The server remains usable when a browser is unavailable.
            }
        }, "roleplay-ui-opener");
        opener.setDaemon(true);
        opener.start();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                    .allowedOrigins("http://localhost:5173", "http://localhost:8000", "http://localhost", "https://localhost", "capacitor://localhost")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowCredentials(true);
            }
        };
    }
}
