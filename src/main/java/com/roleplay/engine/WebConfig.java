package com.roleplay.engine;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Frontend static resources served from classpath:/static/ (copied from frontend/dist/).
 * API endpoints served by controllers. No custom resource handlers needed.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // All static resources are auto-configured by Spring Boot from classpath:/static/
}
