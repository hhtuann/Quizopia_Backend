package com.quizopia.backend.security.config;

import com.quizopia.backend.security.authentication.QuizopiaJwtAuthenticationConverter;
import com.quizopia.backend.security.web.OriginCheckFilter;
import com.quizopia.backend.security.web.QuizopiaAccessDeniedHandler;
import com.quizopia.backend.security.web.QuizopiaAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Spring Security configuration for the authentication API.
 *
 * <p>Stateless token-based API: CSRF disabled (the stateless Bearer API does not
 * need it; the cookie-based refresh/logout endpoints are instead defended by
 * HttpOnly + SameSite=Lax cookies plus the {@link OriginCheckFilter}), form
 * login / HTTP Basic / OAuth2 client all disabled, resource-server JWT enabled
 * with a custom converter that loads authorities and enforces token-version
 * checks.
 *
 * <p>Authorization rules:
 * <ul>
 *   <li>public: POST {@code /api/auth/{register,login,refresh,logout}} and
 *       {@code GET /actuator/health/**},</li>
 *   <li>authenticated: {@code GET /api/auth/me},</li>
 *   <li>everything else is denied by default until its module is opened.</li>
 * </ul>
 */
@Configuration
public class SecurityFilterChainConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            CorsConfigurationSource corsConfigurationSource,
                                            QuizopiaJwtAuthenticationConverter jwtAuthenticationConverter,
                                            QuizopiaAuthenticationEntryPoint authenticationEntryPoint,
                                            QuizopiaAccessDeniedHandler accessDeniedHandler,
                                            OriginCheckFilter originCheckFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // OriginCheckFilter runs before Spring's CorsFilter so a disallowed
                // origin is answered with AUTH_ORIGIN_NOT_ALLOWED instead of the
                // default "Invalid CORS request" body.
                .addFilterBefore(originCheckFilter, CorsFilter.class)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                        jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/actuator/health",
                                "/actuator/health/**").permitAll()
                        // WebSocket handshake upgrade: permitAll is only the HTTP upgrade; the STOMP
                        // CONNECT frame authenticates with a Bearer access token (StompConnectInterceptor).
                        // Origin is enforced by WebSocketConfig.setAllowedOrigins (never a wildcard).
                        .requestMatchers("/ws").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .requestMatchers("/api/question-banks/**").authenticated()
                        .requestMatchers("/api/questions/**").authenticated()
                        .requestMatchers("/api/notifications/**").authenticated()
                        // Academic API (subjects CRUD + grade-levels). Permissions (SUBJECT_READ /
                        // SUBJECT_CREATE / SUBJECT_UPDATE / SUBJECT_STATUS_UPDATE / GRADE_LEVEL_READ)
                        // are enforced in AcademicService (deny-by-default); these rules only let an
                        // authenticated JWT reach the controller (default is denyAll).
                        .requestMatchers("/api/subjects/**").authenticated()
                        .requestMatchers("/api/grade-levels/**").authenticated()
                        .requestMatchers("/api/schools/**").authenticated()
                        // User management (SYSTEM_ADMIN) + role catalog. Permissions are enforced in
                        // UserService (active SYSTEM_ADMIN role, deny-by-default); these rules only let
                        // an authenticated JWT reach the controller (default is denyAll).
                        .requestMatchers("/api/users/**").authenticated()
                        .requestMatchers("/api/roles").authenticated()
                        .requestMatchers("/api/exam-purposes/**").authenticated()
                        .requestMatchers("/api/exams/**").authenticated()
                        .requestMatchers("/api/exam-sessions/**").authenticated()
                        .requestMatchers("/api/attempts/**").authenticated()
                        // Classroom API (class management + members). Permissions (CLASSROOM_CREATE /
                        // CLASSROOM_READ / CLASSROOM_UPDATE / CLASSROOM_MEMBER_*) are enforced in
                        // ClassroomService (deny-by-default); this rule only lets an authenticated JWT
                        // reach the controller (default is denyAll).
                        .requestMatchers("/api/classrooms/**").authenticated()
                        // Student onboarding (pending-students, assign-school, student search).
                        // Permissions enforced in StudentOnboardingService (deny-by-default).
                        .requestMatchers("/api/admin/**").authenticated()
                        .requestMatchers("/api/students/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }
}
