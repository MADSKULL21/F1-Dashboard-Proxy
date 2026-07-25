package com.f1dashboard.api.health;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cheap liveness endpoint under /api so it sits behind the same CORS and header
 * policy as the real endpoints. Actuator stays mounted separately for metrics.
 *
 * <p>Deliberately does not use the {@link com.f1dashboard.api.common.ApiResponse}
 * envelope: it has no season, round or upstream provenance to report.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final String version;

    HealthController(@Value("${app.version:dev}") String version) {
        this.version = version;
    }

    @GetMapping
    Health get() {
        return new Health("UP", version, Instant.now());
    }

    record Health(String status, String version, Instant timestamp) {
    }
}
