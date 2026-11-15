package com.quizopia.backend.monitoring.api;

import com.quizopia.backend.monitoring.application.AiIngestProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Pre-parse guard for {@code POST /internal/ai/**} (P2-2, worker-contract
 * security review): the API-key check and a body-size cap run BEFORE Spring
 * MVC deserializes the JSON body. Without this, an UNAUTHENTICATED caller
 * could burn CPU/heap in Jackson on arbitrarily large bodies on a
 * permitAll endpoint, and a valid key holder faced no per-request bound.
 *
 * <p> Semantics identical to the controller's defense-in-depth check:
 * unset key property → 404 (deny-all, the endpoint pretends not to exist);
 * wrong/missing key → 401; {@code Content-Length} over the configured cap →
 * 413. The controller re-checks the key after deserialization.
 */
public class AiWorkerIngestGuardFilter extends OncePerRequestFilter {

    private final AiIngestProperties properties;

    public AiWorkerIngestGuardFilter(AiIngestProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only the worker ingest route — the LiveKit webhook has its own
        // signature-verifying controller and bounded payload discipline.
        return !("/internal/ai/v1/detections".equals(request.getRequestURI())
                && "POST".equalsIgnoreCase(request.getMethod()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String expected = properties.getWorkerKey();
        if (expected == null || expected.isBlank()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                apiKey.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String contentLength = request.getHeader("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > properties.getWorkerMaxBodyBytes()) {
                    response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // Malformed length — let the container reject it.
            }
        }
        filterChain.doFilter(request, response);
    }
}
