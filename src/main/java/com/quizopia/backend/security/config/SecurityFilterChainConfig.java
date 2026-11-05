package com.quizopia.backend.security.config;

import com.quizopia.backend.authentication.oauth.GoogleIdTokenValidator;
import com.quizopia.backend.authentication.oauth.OAuth2ClientConfig;
import com.quizopia.backend.authentication.oauth.OAuthProperties;
import com.quizopia.backend.authentication.oauth.OauthIdentityService;
import com.quizopia.backend.authentication.oauth.OauthLoginFailureHandler;
import com.quizopia.backend.authentication.oauth.OauthLoginSuccessHandler;
import com.quizopia.backend.authentication.oauth.QuizopiaOidcUserService;
import com.quizopia.backend.security.authentication.QuizopiaJwtAuthenticationConverter;
import com.quizopia.backend.security.web.OriginCheckFilter;
import com.quizopia.backend.security.web.QuizopiaAccessDeniedHandler;
import com.quizopia.backend.security.web.QuizopiaAuthenticationEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Spring Security configuration for the authentication API.
 *
 * <p>Two chains:
 * <ol>
 *   <li>{@code oauth2LoginChain} (only when Google credentials are configured):
 *       the browser-facing OIDC authorization endpoints. Needs HTTP sessions to
 *       hold the authorization-request state; scoped to exactly those paths so
 *       no other URL ever creates a session.</li>
 *   <li>{@code securityFilterChain} (fallback): the stateless JWT API. CSRF
 *       disabled (the stateless Bearer API does not need it; the cookie-based
 *       refresh/logout endpoints are instead defended by HttpOnly +
 *       SameSite=Lax cookies plus the {@link OriginCheckFilter}), form login /
 *       HTTP Basic disabled, resource-server JWT enabled with a custom
 *       converter that loads authorities and enforces token-version checks.</li>
 * </ol>
 *
 * <p>Authorization rules (API chain):
 * <ul>
 *   <li>public: POST {@code /api/auth/{register,login,refresh,logout,
 *       verify-email,verify-email/resend,oauth/exchange}}, GET
 *       {@code /api/auth/providers} and {@code /actuator/health/**},</li>
 *   <li>authenticated: {@code GET /api/auth/me},</li>
 *   <li>everything else is denied by default until its module is opened.</li>
 * </ul>
 */
@Configuration
public class SecurityFilterChainConfig {

    /**
     * Browser-facing OIDC login chain. Present only when a Google client
     * registration bean exists; without credentials this bean is skipped and
     * {@code /oauth2/authorization/google} falls through to the deny-all API
     * chain (404-equivalent for the SPA, which hides the button).
     */
    @Bean
    @Order(1)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ClientRegistrationRepository.class)
    SecurityFilterChain oauth2LoginChain(HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            ObjectProvider<OAuth2AuthorizationRequestResolver> authorizationRequestResolver,
            ClientRegistrationRepository clientRegistrations,
            GoogleIdTokenValidator googleIdTokenValidator,
            OauthIdentityService oauthIdentityService,
            OAuthProperties oauthProperties,
            com.quizopia.backend.classroom.application.RosterReconciliationService rosterReconciliationService) throws Exception {
        http
                .securityMatcher("/oauth2/authorization/*", OAuth2ClientConfig.CALLBACK_PATH)
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(login -> login
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(
                                authorizationRequestResolver.getIfAvailable(
                                        () -> new DefaultOAuth2AuthorizationRequestResolver(
                                                clientRegistrations,
                                                DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI))))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri(OAuth2ClientConfig.CALLBACK_PATH))
                        .userInfoEndpoint(endpoint -> endpoint.oidcUserService(new QuizopiaOidcUserService()))
                        .successHandler(new OauthLoginSuccessHandler(
                                googleIdTokenValidator, oauthIdentityService, oauthProperties,
                                rosterReconciliationService))
                        .failureHandler(new OauthLoginFailureHandler(oauthProperties)));
        return http.build();
    }

    @Bean
    @Order(2)
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
                                "/api/auth/logout",
                                "/api/auth/verify-email",
                                "/api/auth/verify-email/resend",
                                "/api/auth/oauth/exchange").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/auth/providers",
                                "/api/auth/oauth/google/link/callback",
                                "/actuator/health",
                                "/actuator/health/**").permitAll()
                        // WebSocket handshake upgrade: permitAll is only the HTTP upgrade; the STOMP
                        // CONNECT frame authenticates with a Bearer access token (StompConnectInterceptor).
                        // Origin is enforced by WebSocketConfig.setAllowedOrigins (never a wildcard).
                        .requestMatchers("/ws").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        // Identity-M2: teacher self-upgrade — authenticated only;
                        // ACTIVE + verified-email + TEACHER-only grant enforced in
                        // the service (deny-by-default otherwise).
                        .requestMatchers(HttpMethod.POST, "/api/auth/upgrade-teacher").authenticated()
                        // Review M1-06: linking start requires an authenticated JWT plus the
                        // account password (re-authentication) at the service layer.
                        .requestMatchers(HttpMethod.POST, "/api/auth/oauth/google/link").authenticated()
                        // Workspace API (active-workspace switcher). Membership checks are
                        // enforced in WorkspaceService (deny-by-default); this rule only lets
                        // an authenticated JWT reach the controller (default is denyAll).
                        .requestMatchers("/api/workspaces/**").authenticated()
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
