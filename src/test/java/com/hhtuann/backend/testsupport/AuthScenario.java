package com.hhtuann.backend.testsupport;

import com.hhtuann.backend.authentication.application.LoginService;
import com.hhtuann.backend.authentication.application.RegistrationService;
import com.hhtuann.backend.authentication.dto.AccountType;
import com.hhtuann.backend.authentication.dto.ClientContext;
import com.hhtuann.backend.authentication.dto.LoginRequest;
import com.hhtuann.backend.authentication.dto.RegisterRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test helper that seeds a real STUDENT account and an active refresh session in
 * a single committed transaction (so other threads/transactions can observe it)
 * and returns the raw refresh token. Used by concurrency tests.
 */
@Component
public class AuthScenario {

    private final RegistrationService registrationService;
    private final LoginService loginService;

    public AuthScenario(RegistrationService registrationService, LoginService loginService) {
        this.registrationService = registrationService;
        this.loginService = loginService;
    }

    @Transactional
    public String seedStudentAndReturnRefreshToken(String username, String password) {
        registrationService.register(new RegisterRequest(
                username,
                username + "@example.com",
                password,
                username + " Name",
                "+84991234567",
                AccountType.STUDENT,
                null));
        return loginService.login(
                new LoginRequest(username, password),
                new ClientContext("concurrency-test", "127.0.0.1")).rawRefreshToken();
    }
}
