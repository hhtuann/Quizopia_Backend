package com.quizopia.backend.account.application;

import com.quizopia.backend.notification.email.PasswordResetMailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Public forgot-password intake (ADR-004 §"Reset persistence, intake and
 * delivery").
 *
 * <p>The HTTP path performs NO account-specific work: one short database
 * transaction admits the request through the fixed-order IP-then-email abuse
 * buckets for every syntactically valid email, then — only when admitted —
 * enqueues the work on the bounded intake executor (2 workers, 200
 * capacity). Unknown addresses, ineligible accounts, throttled requests and
 * a saturated queue all leave the caller with the identical generic 202.
 *
 * <p>The worker issues the challenge via {@link PasswordResetService#issue}
 * (its own transaction, per-user lock) and delivers the purpose-specific
 * reset mail AFTER that commit using the issuance-captured address; SMTP
 * failure changes nothing about the already-answered response. Failure logs
 * carry the exception class only — never recipient, URL, body or token.
 */
@Service
public class PasswordResetIntakeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetIntakeService.class);

    private final PasswordResetAbuseService abuse;
    private final PasswordResetService resets;
    private final PasswordResetMailSender mail;
    private final PasswordResetProperties properties;
    private final TransactionTemplate transactions;
    private final ExecutorService intakeExecutor;

    public PasswordResetIntakeService(PasswordResetAbuseService abuse,
            PasswordResetService resets,
            PasswordResetMailSender mail,
            PasswordResetProperties properties,
            TransactionTemplate transactions,
            @Qualifier("passwordResetIntakeExecutor") ExecutorService intakeExecutor) {
        this.abuse = abuse;
        this.resets = resets;
        this.mail = mail;
        this.properties = properties;
        this.transactions = transactions;
        this.intakeExecutor = intakeExecutor;
    }

    /**
     * Intake entry point; the controller already validated the email syntax.
     * Never inspects the account: admission happens first, and even a denied
     * or dropped request looks identical to an accepted one.
     */
    public void requestReset(String email, String requestIp) {
        final String normalized = email == null ? "" : email.trim();
        boolean admitted = Boolean.TRUE.equals(
                transactions.execute(status -> abuse.admit(normalized, requestIp)));
        if (!admitted) {
            // Throttled/saturated bucket: uniform 202 already decided.
            return;
        }
        try {
            intakeExecutor.execute(() -> process(normalized));
        } catch (RejectedExecutionException saturated) {
            // Queue full: the admission allowance is consumed, the work is
            // dropped, and the caller still gets the identical 202.
            log.warn("Password reset intake dropped: worker queue full (capacity bounded)");
        }
    }

    /**
     * Background issuance + delivery. Runs off the HTTP path; failures never
     * propagate to a client and are logged with the exception class only.
     */
    void process(String email) {
        PasswordResetService.IssuedReset issued;
        try {
            issued = transactions.execute(status -> resets.issue(email));
        } catch (RuntimeException ex) {
            log.warn("Password reset issuance failed: {}", ex.getClass().getName());
            return;
        }


        if (issued == null) {
            // Unknown address or ineligible account — intake already answered 202.
            log.debug("Password reset skipped for ineligible or unknown address");
            return;
        }
        boolean sent = mail.sendPasswordResetMail(
                issued.email(), issued.displayName(),
                resets.resetUrl(issued.rawToken()), properties.getTtl().toSeconds());
        if (!sent) {
            log.warn("Password reset mail not delivered (retry after cooldown is the recovery flow)");
        }
    }
}
