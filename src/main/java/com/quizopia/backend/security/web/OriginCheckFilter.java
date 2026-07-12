package com.quizopia.backend.security.web;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.security.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Validates the {@code Origin} header on cookie-based endpoints.
 *
 * <p>Applies only to {@code POST /api/auth/refresh} and {@code POST /api/auth/logout},
 * which are driven by the {@code quizopia_refresh} HttpOnly, SameSite-Lax cookie.
 * CSRF is left disabled for the stateless Bearer API, so these cookie endpoints
 * rely on this explicit origin check as their CSRF defence. Login/register and
 * the Bearer-token API do not use the cookie and are therefore exempt.
 *
 * <p>A missing {@code Origin} is accepted only when
 * {@code quizopia.security.cors.allow-missing-origin=true} (server-to-server
 * tests); in production a browser must send an origin that is in the allowlist.
 */
@Component
public class OriginCheckFilter extends OncePerRequestFilter {

    private final ApiErrorWriter apiErrorWriter;
    private final Set<String> allowedOrigins;
    private final boolean allowMissingOrigin;

    public OriginCheckFilter(ApiErrorWriter apiErrorWriter, SecurityProperties properties) {
        this.apiErrorWriter = apiErrorWriter;
        this.allowedOrigins = Set.copyOf(CorsConfig.normalizedOrigins(properties));
        this.allowMissingOrigin = properties.getCors().isAllowMissingOrigin();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (requiresOriginCheck(request)) {
            String origin = request.getHeader("Origin");
            boolean originMissing = origin == null || origin.isBlank();
            if (originMissing) {
                if (!allowMissingOrigin) {
                    apiErrorWriter.write(response, AuthErrorCode.AUTH_ORIGIN_NOT_ALLOWED, request);
                    return;
                }
            } else if (!allowedOrigins.contains(origin)) {
                apiErrorWriter.write(response, AuthErrorCode.AUTH_ORIGIN_NOT_ALLOWED, request);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean requiresOriginCheck(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return "/api/auth/refresh".equals(path) || "/api/auth/logout".equals(path);
    }
}
