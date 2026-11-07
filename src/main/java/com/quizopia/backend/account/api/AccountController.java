package com.quizopia.backend.account.api;

import com.quizopia.backend.account.application.ProfileService;
import com.quizopia.backend.account.dto.UpdateProfileRequest;
import com.quizopia.backend.authentication.dto.CurrentUserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final ProfileService profiles;
    private final com.quizopia.backend.account.application.AccountSecurityService security;

    public AccountController(ProfileService profiles,
            com.quizopia.backend.account.application.AccountSecurityService security) {
        this.profiles = profiles;
        this.security = security;
    }

    @org.springframework.web.bind.annotation.GetMapping("/security")
    public com.quizopia.backend.account.dto.AccountSecurityResponse security(@AuthenticationPrincipal Jwt jwt) {
        Object epoch = jwt.getClaim("token_version");
        return security.read(Long.valueOf(jwt.getSubject()), epoch instanceof Number number ? number : null);
    }

    @PutMapping("/profile")
    public CurrentUserResponse updateProfile(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody UpdateProfileRequest request) {
        Object epoch = jwt.getClaim("token_version");
        return profiles.update(Long.valueOf(jwt.getSubject()),
                epoch instanceof Number number ? number : null, request);
    }
}
