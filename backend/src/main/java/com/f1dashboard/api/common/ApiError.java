package com.f1dashboard.api.common;

import java.time.Instant;

/**
 * The single error shape for the whole API, fixed by PRD 6.3.
 *
 * @param code      stable machine-readable identifier the frontend can branch on
 * @param message   human-readable summary, safe to display; never carries internal detail
 * @param timestamp when the error was produced
 * @param path      the request path that failed
 */
public record ApiError(String code, String message, Instant timestamp, String path) {

    public static ApiError of(String code, String message, String path) {
        return new ApiError(code, message, Instant.now(), path);
    }
}
