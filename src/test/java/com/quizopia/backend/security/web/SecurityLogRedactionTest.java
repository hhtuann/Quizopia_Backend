package com.quizopia.backend.security.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.quizopia.backend.notification.email.SmtpOtpMailSender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityLogRedactionTest {
    @Test
    void unexpectedFailureDoesNotLogExceptionSecretsOrRawPath() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/secret-path-sentinel");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/auth/password/reset");
            var response = new GlobalExceptionHandler(new ApiErrorWriter(new ObjectMapper()))
                    .handleUnexpected(new IllegalStateException("password-sentinel",
                            new RuntimeException("token-sentinel")), request);
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("password-sentinel", "token-sentinel", "secret-path-sentinel");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("IllegalStateException", "/api/auth/password/reset"));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void smtpFailureDoesNotLogRecipientOrTransportSecrets() {
        Logger logger = (Logger) LoggerFactory.getLogger(SmtpOtpMailSender.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            JavaMailSender transport = mock(JavaMailSender.class);
            when(transport.createMimeMessage()).thenThrow(new IllegalStateException("mail-token-sentinel"));
            boolean sent = new SmtpOtpMailSender(transport, "no-reply@example.test")
                    .sendAccountVerificationOtp("recipient-sentinel@example.test", "Name", "123456", 600);
            assertThat(sent).isFalse();
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("IllegalStateException"));
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("recipient-sentinel", "mail-token-sentinel", "123456");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
