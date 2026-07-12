package com.quizopia.backend.security.web;

import com.quizopia.backend.security.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CorsConfig#normalizedOrigins(SecurityProperties)}: the
 * fail-fast rules (wildcard and empty) and the CSV trimming/de-blanking.
 */
class CorsConfigTest {

    @Test
    void wildcardOriginIsRejectedBecauseCredentialsAreEnabled() {
        SecurityProperties properties = new SecurityProperties();
        properties.getCors().setAllowedOrigins(List.of("*"));
        assertThatThrownBy(() -> CorsConfig.normalizedOrigins(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Wildcard");
    }

    @Test
    void emptyOriginListIsRejected() {
        SecurityProperties properties = new SecurityProperties();
        properties.getCors().setAllowedOrigins(List.of());
        assertThatThrownBy(() -> CorsConfig.normalizedOrigins(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void originsAreTrimmedAndBlankEntriesRemoved() {
        SecurityProperties properties = new SecurityProperties();
        properties.getCors().setAllowedOrigins(List.of("  http://localhost:3000  ", "  ", "http://prod.example.com"));
        assertThat(CorsConfig.normalizedOrigins(properties))
                .containsExactly("http://localhost:3000", "http://prod.example.com");
    }
}
