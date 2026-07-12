package com.quizopia.backend.authentication.application;

import com.quizopia.backend.identity.domain.model.RefreshSession;
import com.quizopia.backend.identity.repository.RefreshSessionRepository;
import com.quizopia.backend.security.token.RefreshTokenHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Logs out the current session by revoking the refresh session backing the
 * presented cookie. Idempotent: a missing cookie, an unknown token or an
 * already-revoked session all leave the caller with a successful 204 and a
 * cleared cookie. Only this one session is revoked; no token-version bump and no
 * family-wide revocation is performed.
 */
@Service
public class LogoutService {

    static final String REASON_LOGOUT = "LOGOUT";

    private final RefreshSessionRepository refreshSessionRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final Clock clock;

    public LogoutService(RefreshSessionRepository refreshSessionRepository,
                         RefreshTokenHasher refreshTokenHasher,
                         Clock clock) {
        this.refreshSessionRepository = refreshSessionRepository;
        this.refreshTokenHasher = refreshTokenHasher;
        this.clock = clock;
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String tokenHash = refreshTokenHasher.hash(rawRefreshToken);
        RefreshSession session = refreshSessionRepository.findByTokenHashWithUser(tokenHash).orElse(null);
        if (session == null || session.getRevokedAt() != null) {
            return;
        }
        session.setRevokedAt(Instant.now(clock));
        session.setRevokeReason(REASON_LOGOUT);
        refreshSessionRepository.save(session);
    }
}
