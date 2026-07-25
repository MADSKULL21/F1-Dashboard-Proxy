package com.f1dashboard.api.common;

import java.time.Instant;

/**
 * The envelope every successful endpoint returns (PRD 8).
 *
 * <p>Keeping provenance in the body rather than in headers means the resilience
 * chain cannot quietly lose it: a fallback path that forgets to populate
 * {@code meta} fails to compile rather than silently serving stale data that
 * looks fresh.
 */
public record ApiResponse<T>(T data, ResponseMeta meta) {

    public static <T> ApiResponse<T> of(T data, ResponseMeta meta) {
        return new ApiResponse<>(data, meta);
    }

    /** Data fetched from Jolpica during this request, and therefore fresh. */
    public static <T> ApiResponse<T> live(T data, Integer season, Integer round, Instant fetchedAt) {
        return new ApiResponse<>(data, new ResponseMeta(season, round, fetchedAt, DataOrigin.LIVE, false));
    }
}
