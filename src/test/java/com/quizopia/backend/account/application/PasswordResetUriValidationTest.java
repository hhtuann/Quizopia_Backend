package com.quizopia.backend.account.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-004: the trusted reset origin is validated at startup, never derived from request headers. */
class PasswordResetUriValidationTest {
    @Test
    void rejectsRelativeNonHttpUserinfoQueryFragmentPathAndNonLoopbackHttp() {
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("example.com", false))
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("ftp://example.com", false))
                .hasMessageContaining("scheme");
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("https://user@example.com", false))
                .hasMessageContaining("userinfo");
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("https://example.com?a=1", false))
                .hasMessageContaining("query");
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("https://example.com#t", false))
                .hasMessageContaining("fragment");
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("https://example.com/app", false))
                .hasMessageContaining("origin");
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("http://example.com", false))
                .hasMessageContaining("localhost/loopback");
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("http://localhost:3000", true))
                .hasMessageContaining("localhost/loopback");
    }

    @Test
    void loopbackIsLiteralOnlyNotAny127PrefixedDnsName() {
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("http://127.0.0.1.evil.com", false))
                .hasMessageContaining("localhost/loopback");
        assertThatThrownBy(() -> PasswordResetService.validatedBaseUri("http://127.in-addr.example", false))
                .hasMessageContaining("localhost/loopback");
        assertThat(PasswordResetService.validatedBaseUri("http://127.0.0.1:3000/", false))
                .isEqualTo("http://127.0.0.1:3000");
        assertThat(PasswordResetService.validatedBaseUri("http://localhost:3105", false))
                .isEqualTo("http://localhost:3105");
        assertThat(PasswordResetService.validatedBaseUri("https://quizopia.example", true))
                .isEqualTo("https://quizopia.example");
    }
}
