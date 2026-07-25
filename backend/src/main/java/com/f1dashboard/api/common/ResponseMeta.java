package com.f1dashboard.api.common;

import java.time.Instant;

/**
 * Provenance of the payload it accompanies.
 *
 * @param season    the season the data belongs to, as resolved by Jolpica rather than
 *                  assumed from the calendar year
 * @param round     the latest round the data covers, or {@code null} off-season
 * @param fetchedAt when the data was retrieved from Jolpica — not when this response was built
 * @param source    which tier served the data
 * @param stale     whether the data is older than its intended freshness window; drives the
 *                  "Last updated" banner in the UI
 */
public record ResponseMeta(
        Integer season,
        Integer round,
        Instant fetchedAt,
        DataOrigin source,
        boolean stale) {
}
