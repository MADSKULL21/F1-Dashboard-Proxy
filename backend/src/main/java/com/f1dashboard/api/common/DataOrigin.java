package com.f1dashboard.api.common;

/**
 * Where the data in a response actually came from.
 *
 * <p>Surfaced to the client in {@link ResponseMeta} so the UI can show the
 * "Last updated" notice required by PRD 4.7 instead of guessing.
 */
public enum DataOrigin {

    /** Fetched from Jolpica during this request. */
    LIVE,

    /** Served from the Redis cache. */
    CACHE,

    /** Served from the Postgres snapshot because Jolpica and Redis were both unavailable. */
    SNAPSHOT
}
