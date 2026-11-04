package com.quizopia.backend.authentication.api;

import com.quizopia.backend.authentication.application.CurrentUserService;
import com.quizopia.backend.authentication.application.EmailVerificationService;
import com.quizopia.backend.authentication.application.LoginService;
import com.quizopia.backend.authentication.application.LogoutService;
import com.quizopia.backend.authentication.application.RefreshService;
import com.quizopia.backend.authentication.application.RegistrationService;
import com.quizopia.backend.authentication.application.SessionIssuanceService;
import com.quizopia.backend.authentication.dto.AuthProvidersResponse;
import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.dto.LinkGoogleRequest;
import com.quizopia.backend.authentication.dto.CurrentUserResponse;
import com.quizopia.backend.authentication.dto.LoginRequest;
import com.quizopia.backend.authentication.dto.OauthExchangeRequest;
import com.quizopia.backend.authentication.dto.RegisterRequest;
import com.quizopia.backend.authentication.dto.RegisterResponse;
import com.quizopia.backend.authentication.dto.ResendOtpRequest;
import com.quizopia.backend.authentication.dto.TokenResponse;
import com.quizopia.backend.authentication.dto.VerifyEmailRequest;
import com.quizopia.backend.authentication.oauth.OAuthProperties;
import com.quizopia.backend.authentication.oauth.OauthExchangeService;
import com.quizopia.backend.security.web.RefreshCookieService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authentication endpoints. The controller only validates the request body,
 * delegates to an application service, sets or clears the refresh cookie, and
 * returns the response DTO. All business logic and transaction boundaries live
 * in the services.
 *
 * <p>Identity-M1 additions: email-OTP verification (+resend), the OAuth
 * exchange step, and the provider availability endpoint. Login/refresh keep
 * their existing contracts; register now reports the pending verification.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {

    static final String REFRESH_COOKIE_NAME = "quizopia_refresh";

    private final RegistrationService registrationService;
    private final LoginService loginService;
    private final RefreshService refreshService;
    private final LogoutService logoutService;
    private final CurrentUserService currentUserService;
    private final RefreshCookieService refreshCookieService;
    private final EmailVerificationService emailVerificationService;
    private final OauthExchangeService oauthExchangeService;
    private final OAuthProperties oauthProperties;
    private final com.quizopia.backend.identity.repository.UserRepository userRepository;
    private final com.quizopia.backend.authentication.oauth.GoogleLinkService googleLinkService;

    public AuthenticationController(RegistrationService registrationService,
                                    LoginService loginService,
                                    RefreshService refreshService,
                                    LogoutService logoutService,
                                    CurrentUserService currentUserService,
                                    RefreshCookieService refreshCookieService,
                                    EmailVerificationService emailVerificationService,
                                    OauthExchangeService oauthExchangeService,
                                    OAuthProperties oauthProperties,
                                    com.quizopia.backend.identity.repository.UserRepository userRepository,
                                    com.quizopia.backend.authentication.oauth.GoogleLinkService googleLinkService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.refreshService = refreshService;
        this.logoutService = logoutService;
        this.currentUserService = currentUserService;
        this.refreshCookieService = refreshCookieService;
        this.emailVerificationService = emailVerificationService;
        this.oauthExchangeService = oauthExchangeService;
        this.oauthProperties = oauthProperties;
        this.userRepository = userRepository;
        this.googleLinkService = googleLinkService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
                                                     HttpServletRequest httpRequest) {
        RegisterResponse response = registrationService.register(request, clientContext(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/verify-email")
    public ResponseEntity<Map<String, Object>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.email(), request.otp());
        return ResponseEntity.ok(Map.of("verified", true));
    }

    @PostMapping("/verify-email/resend")
    public ResponseEntity<Map<String, Object>> resendOtp(@Valid @RequestBody ResendOtpRequest request,
                                                         HttpServletRequest httpRequest) {
        long cooldownSeconds = emailVerificationService.resend(
                request.email(), clientContext(httpRequest).remoteAddress());
        return ResponseEntity.ok(Map.of("resendCooldownSeconds", cooldownSeconds));
    }

    @PostMapping("/oauth/exchange")
    public ResponseEntity<TokenResponse> oauthExchange(@Valid @RequestBody OauthExchangeRequest request,
                                                       HttpServletRequest httpRequest,
                                                       HttpServletResponse httpResponse) {
        SessionIssuanceService.IssuedSession session =
                oauthExchangeService.exchange(request.code(), clientContext(httpRequest));
        refreshCookieService.setRefreshCookie(httpResponse, session.rawRefreshToken(), session.refreshMaxAge());
        return ResponseEntity.ok(session.tokenResponse());
    }

    @GetMapping("/providers")
    public AuthProvidersResponse providers() {
        return new AuthProvidersResponse(oauthProperties.getGoogle().isConfigured());
    }

    /**
     * Review M1-06: starts the authenticated Google LINKING flow. Requires a
     * valid JWT AND the account password (re-authentication); the response is
     * the browser-facing Google authorization URL bound (state/PKCE/nonce)
     * to this exact account.
     */
    @PostMapping("/oauth/google/link")
    public ResponseEntity<Map<String, Object>> startGoogleLink(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody LinkGoogleRequest request) {
        if (!oauthProperties.getGoogle().isConfigured()) {
            throw new com.quizopia.backend.authentication.exception.AuthenticationException(
                    com.quizopia.backend.authentication.exception.AuthErrorCode.AUTH_OAUTH_DISABLED);
        }
        com.quizopia.backend.identity.domain.model.User user = userRepository.findById(Long.valueOf(jwt.getSubject()))
                .orElseThrow(() -> new com.quizopia.backend.authentication.exception.AuthenticationException(
                        com.quizopia.backend.authentication.exception.AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID));
        String authorizationUrl = googleLinkService.startLink(user, request.password());
        return ResponseEntity.ok(Map.of("authorizationUrl", authorizationUrl));
    }

    /**
     * Review M1-06: browser redirect target completing the Google LINKING
     * flow. Stateless errors never leak detail — the SPA receives only a
     * status/error code. No tokens travel in URLs.
     */
    @GetMapping("/oauth/google/link/callback")
    public void completeGoogleLink(
            @org.springframework.web.bind.annotation.RequestParam(name = "code", required = false) String code,
            @org.springframework.web.bind.annotation.RequestParam(name = "state", required = false) String state,
            @org.springframework.web.bind.annotation.RequestParam(name = "error", required = false) String error,
            HttpServletResponse httpResponse) throws java.io.IOException {
        if (error != null && !error.isBlank()) {
            httpResponse.sendRedirect(googleLinkService.linkResultUrl("error", "AUTH_OAUTH_FAILED"));
            return;
        }
        try {
            googleLinkService.completeLink(code, state);
            httpResponse.sendRedirect(googleLinkService.linkResultUrl("success", null));
        } catch (Exception ex) {
            httpResponse.sendRedirect(googleLinkService.linkResultUrl("error", "AUTH_OAUTH_FAILED"));
        }
    }


    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        SessionIssuanceService.IssuedSession result = loginService.login(request, clientContext(httpRequest));
        refreshCookieService.setRefreshCookie(httpResponse, result.rawRefreshToken(), result.refreshMaxAge());
        return ResponseEntity.ok(result.tokenResponse());
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        RefreshService.RefreshResult result = refreshService.refresh(refreshToken, clientContext(httpRequest));
        refreshCookieService.setRefreshCookie(httpResponse, result.rawRefreshToken(), result.refreshMaxAge());
        return ResponseEntity.ok(result.tokenResponse());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse httpResponse) {
        logoutService.logout(refreshToken);
        refreshCookieService.clearRefreshCookie(httpResponse);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return currentUserService.currentUser(userId);
    }

    private static ClientContext clientContext(HttpServletRequest request) {
        return new ClientContext(request.getHeader("User-Agent"), request.getRemoteAddr());
    }
}
