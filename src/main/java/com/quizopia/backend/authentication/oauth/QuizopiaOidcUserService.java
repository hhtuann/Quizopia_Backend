package com.quizopia.backend.authentication.oauth;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.List;

/**
 * Minimal {@link OidcUserService}: builds the principal from the validated ID
 * token WITHOUT calling the userinfo endpoint (the ID token already carries
 * subject/email/email_verified — the only claims this integration uses).
 *
 * <p>Signature/issuer/audience/expiry/nonce validation already happened in
 * the authorization-code flow before this service runs; the login success
 * handler then performs the independent {@link GoogleIdTokenValidator} check
 * before mapping the identity.
 */
public class QuizopiaOidcUserService extends OidcUserService {

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcIdToken idToken = userRequest.getIdToken();
        return new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_OAUTH_LOGIN")),
                idToken,
                org.springframework.security.oauth2.core.oidc.OidcUserInfo.builder().build(),
                "sub");
    }
}
