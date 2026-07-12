package com.quizopia.backend.authentication.api;

import com.quizopia.backend.authentication.application.CurrentUserService;
import com.quizopia.backend.authentication.application.LoginService;
import com.quizopia.backend.authentication.application.LogoutService;
import com.quizopia.backend.authentication.application.RefreshService;
import com.quizopia.backend.authentication.application.RegistrationService;
import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.dto.CurrentUserResponse;
import com.quizopia.backend.authentication.dto.LoginRequest;
import com.quizopia.backend.authentication.dto.RegisterRequest;
import com.quizopia.backend.authentication.dto.RegisterResponse;
import com.quizopia.backend.authentication.dto.TokenResponse;
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

/**
 * Authentication endpoints. The controller only validates the request body,
 * delegates to an application service, sets or clears the refresh cookie, and
 * returns the response DTO. All business logic and transaction boundaries live
 * in the services.
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

    public AuthenticationController(RegistrationService registrationService,
                                    LoginService loginService,
                                    RefreshService refreshService,
                                    LogoutService logoutService,
                                    CurrentUserService currentUserService,
                                    RefreshCookieService refreshCookieService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.refreshService = refreshService;
        this.logoutService = logoutService;
        this.currentUserService = currentUserService;
        this.refreshCookieService = refreshCookieService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        LoginService.LoginResult result = loginService.login(request, clientContext(httpRequest));
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
