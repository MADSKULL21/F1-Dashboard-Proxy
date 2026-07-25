package com.f1dashboard.api.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Adds the security headers required by PRD 5.
 *
 * <p>A plain filter rather than Spring Security: MVP has no authentication, so
 * Spring Security would contribute only these headers in exchange for a filter
 * chain to configure and extra memory inside a 512MB ceiling. When V2 introduces
 * OAuth, Spring Security arrives with it and takes this over.
 *
 * <p>The policy is deliberately restrictive because every response is JSON —
 * nothing here needs to load a script, embed a frame or submit a form.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    private static final String PERMISSIONS_POLICY =
            "geolocation=(), microphone=(), camera=(), payment=(), usb=()";
    private static final String HSTS = "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);

        // HSTS is meaningless (and misleading) over plain HTTP, so it is emitted
        // only for genuinely secure requests. Render terminates TLS upstream,
        // hence the forwarded-proto check as well as isSecure().
        if (isHttps(request)) {
            response.setHeader("Strict-Transport-Security", HSTS);
        }

        chain.doFilter(request, response);
    }

    private boolean isHttps(HttpServletRequest request) {
        return request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    }
}
