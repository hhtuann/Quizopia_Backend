package com.quizopia.backend.authentication.oauth;

import com.quizopia.backend.authentication.application.SessionIssuanceService;
import com.quizopia.backend.identity.domain.model.User;
import org.springframework.stereotype.Service;

/**
 * Exchanges a one-time OAuth login code for a full Quizopia session. This is
 * the SPA's callback step: the code arrives via URL (opaque, 60s, single
 * use), while the access token and refresh cookie are delivered exactly like
 * a password login — token in the POST body, refresh in HttpOnly cookie
 * (ADR-002 §4).
 */
@Service
public class OauthExchangeService {

    private final OauthIdentityService identityService;
    private final SessionIssuanceService sessionIssuanceService;

    public OauthExchangeService(OauthIdentityService identityService,
            SessionIssuanceService sessionIssuanceService) {
        this.identityService = identityService;
        this.sessionIssuanceService = sessionIssuanceService;
    }

    public SessionIssuanceService.IssuedSession exchange(String rawCode,
            com.quizopia.backend.authentication.dto.ClientContext client) {
        User user = identityService.consumeExchangeCode(rawCode);
        return sessionIssuanceService.issue(user, client);
    }
}
