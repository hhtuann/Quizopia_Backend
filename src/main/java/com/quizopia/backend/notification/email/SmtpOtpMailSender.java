package com.quizopia.backend.notification.email;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * SMTP implementation of {@link OtpMailSender} backed by Spring Mail
 * ({@code spring.mail.*}). Plain text only — no HTML, no user-controlled
 * header content — so the OTP cannot be smuggled into mail headers and the
 * message renders everywhere. For local development the compose stack runs
 * Mailpit on {@code localhost:1025}.
 */
@Component
@ConditionalOnProperty(name = "quizopia.mail.mode", havingValue = "smtp")
public class SmtpOtpMailSender implements OtpMailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpOtpMailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpOtpMailSender(JavaMailSender mailSender,
            @Value("${quizopia.mail.from:Quizopia <no-reply@quizopia.local>}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public boolean sendAccountVerificationOtp(String toEmail, String displayName,
            String otp, long expiresInSeconds) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject("Quizopia - Ma xac thuc email cua ban");
            helper.setText(body(displayName, otp, expiresInSeconds), false);
            mailSender.send(message);
            return true;
        } catch (Exception ex) {
            // Recipient address and exception class only — never the OTP.
            log.warn("OTP mail delivery failed for {}: {}", toEmail, ex.toString());
            return false;
        }
    }

    private static String body(String displayName, String otp, long expiresInSeconds) {
        long minutes = expiresInSeconds / 60;
        return """
                Xin chao %s,

                Ma xac thuc email Quizopia cua ban la:

                %s

                Ma co hieu luc trong %d phut. Neu ban khong yeu cau, hay bo qua email nay.

                Quizopia
                """.formatted(displayName, otp, minutes);
    }
}
