package com.quizopia.backend.monitoring.api;

import com.quizopia.backend.media.webhook.LiveKitWebhookEvent;
import com.quizopia.backend.media.webhook.LiveKitWebhookVerifier;
import com.quizopia.backend.monitoring.application.LiveKitWebhookService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * LiveKit server-to-server webhook receiver (P4-B4, issue #87):
 * {@code POST /internal/media/v1/livekit/webhooks}.
 *
 * <p>Authentication is the webhook's own HMAC: a bare HS256 JWT in the
 * {@code Authorization} header carrying a sha256 of the raw body (verified by
 * {@link LiveKitWebhookVerifier}). The path is permitAll in the security
 * chain precisely because the signature IS the authentication; verification
 * failure answers 401 with no detail (never tell a forger which check
 * failed).
 *
 * <p>Accepted deliveries always answer 200 — LiveKit retries non-2xx, and a
 * processed delivery is a success even when domain routing no-ops (unknown
 * identity, ledger-only event type). Duplicate/replayed deliveries are
 * short-circuited by the dedupe ledger inside
 * {@link LiveKitWebhookService}.
 */
@RestController
public class LiveKitWebhookController {

    private final LiveKitWebhookVerifier verifier;
    private final LiveKitWebhookService service;

    public LiveKitWebhookController(LiveKitWebhookVerifier verifier, LiveKitWebhookService service) {
        this.verifier = verifier;
        this.service = service;
    }

    @PostMapping("/internal/media/v1/livekit/webhooks")
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        LiveKitWebhookEvent event;
        try {
            event = verifier.verify(rawBody, authorization);
        } catch (LiveKitWebhookVerifier.VerificationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        service.handle(event);
        return ResponseEntity.ok().build();
    }
}
