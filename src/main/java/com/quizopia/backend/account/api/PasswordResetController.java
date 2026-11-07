package com.quizopia.backend.account.api;

import com.quizopia.backend.account.application.PasswordResetIntakeService;
import com.quizopia.backend.account.application.PasswordResetService;
import com.quizopia.backend.account.dto.PasswordForgotRequest;
import com.quizopia.backend.account.dto.PasswordResetRequest;
import com.quizopia.backend.authentication.exception.AuthErrorCode;
import com.quizopia.backend.authentication.exception.AuthenticationException;
import com.quizopia.backend.security.web.RefreshCookieService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public password-recovery endpoints (ADR-004 §HTTP contracts). Both are
 * permitAll exact paths; the controller validates shape only and never
 * reveals account state.
 *
 * <ul>
 *   <li>{@code POST /api/auth/password/forgot} — 202 generic confirmation,
 *       identical for unknown, ineligible, throttled and saturated requests;
 *       400 only for syntax errors. The intake performs the synchronous
 *       abuse admission itself; everything account-specific is off-path.</li>
 *   <li>{@code POST /api/auth/password/reset} — 204 on success, clears the
 *       refresh cookie, no automatic login; every token problem shares one
 *       400 code. Both responses are no-store.</li>
 * </ul>
 */
@RestController
public class PasswordResetController {

    private final PasswordResetIntakeService intake;
    private final PasswordResetService resets;
    private final RefreshCookieService cookies;

    public PasswordResetController(PasswordResetIntakeService intake,
            PasswordResetService resets,
            RefreshCookieService cookies) {
        this.intake = intake;
        this.resets = resets;
        this.cookies = cookies;
    }

    @PostMapping("/api/auth/password/forgot")
    public ResponseEntity<Void> forgot(@Valid @RequestBody PasswordForgotRequest request,
            HttpServletRequest httpRequest) {
        // Never trust forwarded IP headers without a configured trusted proxy:
        // only the servlet-observed remote address reaches the buckets.
        intake.requestReset(request.email(), httpRequest.getRemoteAddr());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/auth/password/reset")
    public ResponseEntity<Void> reset(@Valid @RequestBody PasswordResetRequest request,
            HttpServletResponse httpResponse) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new AuthenticationException(AuthErrorCode.AUTH_VALIDATION_ERROR);
        }
        if (!resets.reset(request.token(), request.newPassword())) {
            throw new AuthenticationException(AuthErrorCode.AUTH_PASSWORD_RESET_INVALID);
        }
        cookies.clearRefreshCookie(httpResponse);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
