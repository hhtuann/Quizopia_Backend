package com.quizopia.backend.testsupport;

import com.quizopia.backend.authentication.application.EmailVerificationService;
import com.quizopia.backend.authentication.application.LoginService;
import com.quizopia.backend.authentication.application.RegistrationService;
import com.quizopia.backend.authentication.dto.AccountType;
import com.quizopia.backend.authentication.dto.ClientContext;
import com.quizopia.backend.authentication.dto.LoginRequest;
import com.quizopia.backend.authentication.dto.RegisterRequest;
import com.quizopia.backend.notification.email.CaptureOtpMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test helper that seeds a real STUDENT account (email-verified, Identity-M1)
 * and an active refresh session in a single committed transaction (so other
 * threads/transactions can observe it) and returns the raw refresh token.
 * Used by concurrency tests.
 */
@Component
public class AuthScenario {

    private final RegistrationService registrationService;
    private final EmailVerificationService emailVerificationService;
    private final LoginService loginService;
    private final CaptureOtpMailSender otpMailbox;

    public AuthScenario(RegistrationService registrationService,
            EmailVerificationService emailVerificationService,
            LoginService loginService,
            CaptureOtpMailSender otpMailbox) {
        this.registrationService = registrationService;
        this.emailVerificationService = emailVerificationService;
        this.loginService = loginService;
        this.otpMailbox = otpMailbox;
    }

    @Transactional
    public String seedStudentAndReturnRefreshToken(String username, String password) {
        String email = username + "@example.com";
        registrationService.register(new RegisterRequest(
                username,
                email,
                password,
                username + " Name",
                "+84991234567",
                AccountType.STUDENT),
                new ClientContext("concurrency-test", "127.0.0.1"));
        emailVerificationService.verify(email, otpMailbox.latestOtpFor(email));
        return loginService.login(
                new LoginRequest(username, password),
                new ClientContext("concurrency-test", "127.0.0.1")).rawRefreshToken();
    }
}
