package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Identity-M1 HTTP-level tests for the OIDC wiring: the authorization redirect
 * carries state + PKCE (S256), the provider-availability endpoint reports the
 * configured provider, a state-less callback is rejected, and the exchange
 * endpoint fails closed for unknown codes.
 */
@SpringBootTest
@Import(PostgresTestContainerConfiguration.class)
@ActiveProfiles("test")
class OauthAuthorizationEndpointTests extends AbstractAuthenticationIntegrationTests {

    @Test
    void authorizationRedirectCarriesStateAndPkce() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth2/authorization/google")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        String location = result.getResponse().getHeader("Location");
        assertThat(location).isNotNull();
        assertThat(location).contains("state=");
        assertThat(location).contains("code_challenge=");
        assertThat(location).contains("code_challenge_method=S256");
        // The client secret never appears anywhere near the redirect.
        assertThat(location).doesNotContain("test-google-client-secret");
    }

    @Test
    void providersEndpointReportsGoogleEnabled() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/providers")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"googleEnabled\":true");
    }

    @Test
    void callbackWithoutStateIsRejected() throws Exception {
        // No saved authorization request (no state/session): the framework must
        // refuse; nothing reaches the success handler, no code is minted.
        MvcResult result = mockMvc.perform(get("/api/auth/oauth/google/callback")
                        .param("code", "forged-code")
                        .param("state", "forged-state"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(200);
        // A redirect to the SPA never carries a code parameter on failure.
        String location = result.getResponse().getHeader("Location");
        assertThat(location == null || !location.contains("code=")).isTrue();
    }

    @Test
    void exchangeWithUnknownCodeFailsClosed() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/oauth/exchange")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"totally-unknown-code\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("AUTH_OAUTH_CODE_INVALID");
    }
}
