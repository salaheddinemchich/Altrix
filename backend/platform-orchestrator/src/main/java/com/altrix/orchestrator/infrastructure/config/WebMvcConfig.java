package com.altrix.orchestrator.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * REST CORS policy for platform-orchestrator.
 *
 * <p>Allows the Angular frontend (default: localhost:4200) and other configured
 * origins to call the REST API. WebSocket CORS is handled separately by
 * {@code WebSocketConfig} via {@code websocket.allowed-origins}.
 *
 * <p>Override at runtime: set {@code CORS_ALLOWED_ORIGINS} env var or
 * {@code cors.allowed-origins} property (comma-separated).
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:4200,http://localhost:8080}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
