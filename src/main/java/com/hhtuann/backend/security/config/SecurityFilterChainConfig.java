package com.hhtuann.backend.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal Spring Security configuration for Batch 1.
 *
 * <p>It is intentionally restrictive:
 * <ul>
 *   <li>Stateless sessions (token-based API).</li>
 *   <li>Form login, HTTP Basic and CSRF disabled.</li>
 *   <li>No OAuth2 client / social login is enabled.</li>
 *   <li>OAuth2 resource server JWT is enabled using the shared {@code JwtDecoder}
 *       bean, so tokens can be validated once authentication endpoints exist.</li>
 *   <li>Only Actuator health is public; every other request is denied. The
 *       {@code /api/auth/**} paths are <em>not</em> permitted here because no
 *       authentication controller exists yet in Batch 1.</li>
 * </ul>
 *
 * <p>CORS, CSRF for cookie-based refresh/logout, origin validation, role and
 * permission authorities, and the authentication endpoints are completed in
 * Batch 2.
 */
@Configuration
public class SecurityFilterChainConfig {

    /**
     * Actuator health path permitted without authentication.
     */
    static final String[] PUBLIC_HEALTH_PATHS = new String[] {
            "/actuator/health",
            "/actuator/health/**"
    };

    /**
     * @param http the security builder
     * @return the security filter chain
     * @throws Exception if the chain cannot be configured
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_HEALTH_PATHS).permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
