package com.quizopia.backend.account.api;

import com.quizopia.backend.account.application.ChangePasswordService;
import com.quizopia.backend.account.dto.ChangePasswordRequest;
import com.quizopia.backend.security.web.RefreshCookieService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PasswordChangeController {
    private final ChangePasswordService passwords;
    private final RefreshCookieService cookies;

    public PasswordChangeController(ChangePasswordService passwords, RefreshCookieService cookies) {
        this.passwords = passwords;
        this.cookies = cookies;
    }

    @PostMapping("/api/account/security/change-password")
    public ResponseEntity<Void> change(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody ChangePasswordRequest request,
                                      HttpServletResponse response) {
        Object epoch = jwt.getClaim("token_version");
        passwords.change(Long.valueOf(jwt.getSubject()), epoch instanceof Number number ? number : null, request);
        cookies.clearRefreshCookie(response);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
