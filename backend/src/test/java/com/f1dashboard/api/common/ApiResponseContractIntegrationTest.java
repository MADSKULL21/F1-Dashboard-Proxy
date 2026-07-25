package com.f1dashboard.api.common;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The { data, meta } envelope is the contract every feature inherits, and the
 * "Last updated" banner in PRD 4.7 reads meta directly. Serialised field names
 * and the ISO-8601 instant format are therefore part of the public API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiResponseContractIntegrationTest.EnvelopeController.class)
class ApiResponseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void liveResponseSerialisesDataAndMeta() throws Exception {
        mockMvc.perform(get("/test-envelope/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("VER"))
                .andExpect(jsonPath("$.data[1]").value("NOR"))
                .andExpect(jsonPath("$.meta.season").value(2026))
                .andExpect(jsonPath("$.meta.round").value(10))
                .andExpect(jsonPath("$.meta.source").value("LIVE"))
                .andExpect(jsonPath("$.meta.stale").value(false))
                .andExpect(jsonPath("$.meta.fetchedAt").value("2026-07-25T09:12:03Z"));
    }

    @Test
    void cachedResponseIsMarkedStale() throws Exception {
        mockMvc.perform(get("/test-envelope/cached"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.source").value("CACHE"))
                .andExpect(jsonPath("$.meta.stale").value(true));
    }

    /**
     * Off-season: PRD 4.1 requires an explicit empty state, never a broken
     * table. An empty data array with valid meta is how the backend says that.
     */
    @Test
    void offSeasonSerialisesEmptyDataWithNullRound() throws Exception {
        mockMvc.perform(get("/test-envelope/off-season"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.season").value(2027))
                .andExpect(jsonPath("$.meta.round").value(org.hamcrest.Matchers.nullValue()));
    }

    @TestConfiguration
    @RestController
    static class EnvelopeController {

        private static final Instant FETCHED_AT = Instant.parse("2026-07-25T09:12:03Z");

        @GetMapping("/test-envelope/live")
        ApiResponse<List<String>> live() {
            return ApiResponse.live(List.of("VER", "NOR"), 2026, 10, FETCHED_AT);
        }

        @GetMapping("/test-envelope/cached")
        ApiResponse<List<String>> cached() {
            return ApiResponse.of(List.of("VER"),
                    new ResponseMeta(2026, 10, FETCHED_AT, DataOrigin.CACHE, true));
        }

        @GetMapping("/test-envelope/off-season")
        ApiResponse<List<String>> offSeason() {
            return ApiResponse.live(List.of(), 2027, null, FETCHED_AT);
        }
    }
}
