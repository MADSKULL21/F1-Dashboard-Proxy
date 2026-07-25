package com.f1dashboard.api.common;

/**
 * A requested F1 entity does not exist — an unknown driver id, or a round that
 * is not on the calendar.
 *
 * <p>Distinct from an upstream failure: this is a legitimate 404 and must not
 * trigger retries or cache fallback.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
