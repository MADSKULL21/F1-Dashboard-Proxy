package com.f1dashboard.api.common;

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
 * Every error response must match the shape fixed by PRD 6.3:
 * { code, message, timestamp, path }. Nothing may leak a stack trace
 * or a Spring default error body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ErrorEnvelopeIntegrationTest.ThrowingController.class)
class ErrorEnvelopeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unknownPathReturnsErrorEnvelope() throws Exception {
        mockMvc.perform(get("/api/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").value("/api/no-such-endpoint"));
    }

    @Test
    void resourceNotFoundReturnsErrorEnvelopeWithPath() throws Exception {
        mockMvc.perform(get("/test-errors/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Driver 'nobody' not found"))
                .andExpect(jsonPath("$.path").value("/test-errors/missing"));
    }

    @Test
    void unexpectedExceptionReturnsGenericEnvelopeWithoutLeakingDetail() throws Exception {
        mockMvc.perform(get("/test-errors/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                // the internal message must NOT reach the client
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path").value("/test-errors/boom"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @TestConfiguration
    @RestController
    static class ThrowingController {

        @GetMapping("/test-errors/missing")
        String missing() {
            throw new ResourceNotFoundException("Driver 'nobody' not found");
        }

        @GetMapping("/test-errors/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail: db password in log");
        }
    }
}
