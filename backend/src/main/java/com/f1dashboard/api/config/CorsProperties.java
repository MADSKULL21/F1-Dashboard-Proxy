package com.f1dashboard.api.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowedOrigins exact frontend origins permitted to call the API. Never a
 *                       wildcard — PRD 5 requires CORS locked to the known origin.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
