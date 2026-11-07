package com.quizopia.backend.security.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.quizopia.backend.notification.email.CaptureOtpMailSender;
import com.quizopia.backend.testsupport.AbstractAuthenticationIntegrationTests;
import com.quizopia.backend.testsupport.PostgresTestContainerConfiguration;
import com.quizopia.backend.testsupport.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADR-004 redaction gate: an OTP/resend worker failure whose exception text
 * carries recipient/OTP material must never place either in the logs.
 */
@SpringBootTest
@Import({PostgresTestContainerConfiguration.class, TestClockConfig.class})
@ActiveProfiles("test")
@Transactional
class WorkerLogRedactionTest extends AbstractAuthenticationIntegrationTests {
    @MockitoSpyBean
    private CaptureOtpMailSender mailbox;

    @Autowired
    private com.quizopia.backend.testsupport.MutableClock clock;

    @Test
    void resendWorkerFailureLogsClassOnlyWithoutRecipientOrSecret() throws Exception {
        clock.setInstant(Instant.now());
        String username = "redact" + System.nanoTime();
        register(studentRegisterJson(username, username + "@example.test", "Passw0rd!", "Redact Probe", "+84991234567"));

        Logger logger = (Logger) LoggerFactory.getLogger(com.quizopia.backend.authentication.application.EmailVerificationService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            doThrow(new IllegalStateException("boom recipient-sentinel@example.test otp-sentinel"))
                    .when(mailbox).sendAccountVerificationOtp(anyString(), anyString(), anyString(), anyLong());

            int status = mockMvc.perform(post("/api/auth/verify-email/resend")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s@example.test\"}".formatted(username)))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(200);


            awaitWorkerLog(captured);
            assertThat(captured.list).isNotEmpty();
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage())
                        .doesNotContain("recipient-sentinel", "otp-sentinel");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    private static void awaitWorkerLog(ListAppender<ILoggingEvent> captured) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && captured.list.isEmpty()) {
            Thread.sleep(50);
        }
    }
}
