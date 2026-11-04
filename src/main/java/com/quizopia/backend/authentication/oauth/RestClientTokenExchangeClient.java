package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Production {@link TokenExchangeClient} backed by Spring's {@link RestClient}
 * against the Google token endpoint configured on the client registration.
 * Only the standard authorization-code + PKCE parameters are sent; the client
 * secret stays server-side and is never logged.
 */
@Component
public class RestClientTokenExchangeClient implements TokenExchangeClient {

    private final RestClient restClient;
    private final OAuthProperties properties;

    public RestClientTokenExchangeClient(OAuthProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    @Override
    public String exchangeForIdToken(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", properties.getGoogle().getClientId());
        form.add("client_secret", properties.getGoogle().getClientSecret());

        try {
            Map<?, ?> response = restClient.post()
                    .uri(OAuth2ClientConfig.DEFAULT_TOKEN_URI)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            Object idToken = response == null ? null : response.get("id_token");
            if (idToken == null || idToken.toString().isBlank()) {
                throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
            }
            return idToken.toString();
        } catch (AuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            // Provider unreachable / rejected the code: fail closed.
            throw new AuthenticationException(AuthErrorCode.AUTH_OAUTH_CODE_INVALID);
        }
    }
}
