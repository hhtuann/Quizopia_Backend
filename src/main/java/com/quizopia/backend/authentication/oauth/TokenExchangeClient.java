package com.quizopia.backend.authentication.oauth;

/**
 * Exchanges an OAuth authorization code for a token response at the
 * provider's token endpoint (review M1-06 linking flow). Production uses
 * {@link RestClientTokenExchangeClient}; tests substitute a fake provider.
 */
public interface TokenExchangeClient {

    /**
     * Performs the authorization-code + PKCE exchange.
     *
     * @param code         the authorization code from the callback
     * @param codeVerifier the PKCE verifier created with the request
     * @param redirectUri  the exact redirect_uri used in the authorization request
     * @return the raw ID token issued by the provider
     */
    String exchangeForIdToken(String code, String codeVerifier, String redirectUri);
}
