package com.quizopia.backend.authentication;

import com.quizopia.backend.authentication.application.RegistrationService;
import com.quizopia.backend.authentication.dto.AccountType;
import com.quizopia.backend.authentication.dto.RegisterRequest;
import com.quizopia.backend.security.authentication.QuizopiaJwtAuthenticationConverter;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link QuizopiaJwtAuthenticationConverter}: authorities
 * carry active roles ({@code ROLE_<CODE>}) and effective permissions (raw
 * codes), and an expired role/permission assignment is excluded.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainerConfiguration.class)
@Transactional
@SuppressWarnings({"null"})
class JwtAuthoritiesIntegrationTests {

    @Autowired
    private QuizopiaJwtAuthenticationConverter converter;

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void authoritiesContainActiveRolesAndPermissions() {
        Long userId = registerStudent(unique("auth"));

        Set<String> authorities = authorities(converter.convert(jwtFor(userId, 0)));

        assertThat(authorities).contains("ROLE_STUDENT");
        // Permissions are exposed under their raw codes (no prefix).
        assertThat(authorities).contains("ATTEMPT_START", "EXAM_READ");
    }

    @Test
    void expiredRoleAssignmentIsExcludedFromAuthorities() {
        Long userId = registerStudent(unique("expired-role"));
        expireRolesOf(userId);

        Set<String> authorities = authorities(converter.convert(jwtFor(userId, 0)));

        assertThat(authorities).doesNotContain("ROLE_STUDENT");
        assertThat(authorities).doesNotContain("ATTEMPT_START");
    }

    private Long registerStudent(String username) {
        return registrationService.register(new RegisterRequest(
                username,
                username + "@example.com",
                "Passw0rd!",
                username + " Name",
                "+84991234567",
                AccountType.STUDENT,
                null)).id();
    }

    private void expireRolesOf(Long userId) {
        // Keep expires_at > assigned_at (constraint) while moving both into the
        // past so the role is treated as expired but remains constraint-valid.
        entityManager.createNativeQuery(
                        "UPDATE user_roles SET assigned_at = :assigned, expires_at = :expires WHERE user_id = :id")
                .setParameter("assigned", Instant.now().minusSeconds(7200))
                .setParameter("expires", Instant.now().minusSeconds(3600))
                .setParameter("id", userId)
                .executeUpdate();
        entityManager.clear();
    }

    private Jwt jwtFor(Long userId, int tokenVersion) {
        return Jwt.withTokenValue("dummy")
                .header("alg", "HS256")
                .subject(String.valueOf(userId))
                .claim("token_version", tokenVersion)
                .build();
    }

    private static Set<String> authorities(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String unique(String base) {
        return base + UUID.randomUUID().toString().substring(0, 8);
    }
}
