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
 * SMTP implementation of {@link PasswordResetMailSender} backed by Spring
 * Mail ({@code spring.mail.*}), mirroring {@link SmtpOtpMailSender}: plain
 * text only — no HTML, no user-controlled header content — so the reset URL
 * cannot be smuggled into mail headers and the message renders everywhere.
 * The local compose stack runs Mailpit on {@code localhost:1025}.
 */
@Component
@ConditionalOnProperty(name = "quizopia.mail.mode", havingValue = "smtp")
public class SmtpPasswordResetMailSender implements PasswordResetMailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpPasswordResetMailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpPasswordResetMailSender(JavaMailSender mailSender,
            @Value("${quizopia.mail.from:Quizopia <no-reply@quizopia.local>}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public boolean sendPasswordResetMail(String toEmail, String displayName, String resetUrl,
            long expiresInSeconds) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(toEmail);
            helper.setSubject("Quizopia - Dat lai mat khau cua ban");
            helper.setText(body(displayName, resetUrl, expiresInSeconds), false);
            mailSender.send(message);
            return true;
        } catch (Exception ex) {
            // Transport exception messages can contain recipients and message contents.
            log.warn("Password reset mail delivery failed: {}", ex.getClass().getName());
            return false;
        }
    }

    private static String body(String displayName, String resetUrl, long expiresInSeconds) {
        long minutes = expiresInSeconds / 60;
        return """
                Xin chao %s,

                Ban (hoac ai do) vua yeu cau dat lai mat khau Quizopia cua ban.

                Mo lien ket sau de dat lai mat khau (lien ket chi dung mot lan va het han sau %d phut):

                %s

                Neu ban khong yeu cau, hay bo qua email nay. Chi doi tuong co quyen truy cap hop thu cua ban moi co the dat lai mat khau.

                Quizopia
                """.formatted(displayName, minutes, resetUrl);
    }
}
