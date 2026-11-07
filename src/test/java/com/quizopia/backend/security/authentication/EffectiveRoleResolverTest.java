package com.quizopia.backend.security.authentication;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link EffectiveRoleResolver} — proves role comes from effective authorities, not JWT claim. */
class EffectiveRoleResolverTest {

    @Test
    void teacherHasHighestPrecedence() {
        assertThat(EffectiveRoleResolver.resolve(List.of(
                new SimpleGrantedAuthority("ROLE_STUDENT"),
                new SimpleGrantedAuthority("ROLE_TEACHER"),
                new SimpleGrantedAuthority("ATTEMPT_READ")))).isEqualTo("TEACHER");
    }

    @Test
    void unsupportedLegacyRolesAreIgnored() {
        assertThat(EffectiveRoleResolver.resolve(List.of(
                new SimpleGrantedAuthority("ROLE_TEACHER"),
                new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"),
                new SimpleGrantedAuthority("ROLE_ACADEMIC_ADMIN")))).isEqualTo("TEACHER");
    }

    @Test
    void teacherResolved() {
        assertThat(EffectiveRoleResolver.resolve(List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))))
                .isEqualTo("TEACHER");
    }

    @Test
    void studentResolved() {
        assertThat(EffectiveRoleResolver.resolve(List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .isEqualTo("STUDENT");
    }

    @Test
    void unsupportedRoleReturnsNull() {
        assertThat(EffectiveRoleResolver.resolve(List.of(new SimpleGrantedAuthority("ROLE_GHOST"))))
                .isNull();
    }

    @Test
    void noRoleAuthoritiesReturnsNull() {
        assertThat(EffectiveRoleResolver.resolve(List.of(new SimpleGrantedAuthority("ATTEMPT_READ"))))
                .isNull();
    }

    @Test
    void nullInputReturnsNull() {
        assertThat(EffectiveRoleResolver.resolve(null)).isNull();
    }

    @Test
    void emptyAuthoritiesReturnsNull() {
        assertThat(EffectiveRoleResolver.resolve(List.of())).isNull();
    }
}
