package com.quizopia.backend.monitoring.api;

import com.quizopia.backend.monitoring.application.AiDetectionIngestService;
import com.quizopia.backend.monitoring.application.AiIngestProperties;
import com.quizopia.backend.monitoring.dto.AiDetectionBatchResponse;
import com.quizopia.backend.monitoring.dto.AiWorkerDetectionBatchRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal AI-worker detection receiver (P4-C, CONTRACT §3.3):
 * {@code POST /internal/ai/v1/detections}.
 *
 * <p><b>Service auth posture (the LiveKit webhook pattern):</b> the path
 * is permitAll in the security chain precisely because the
 * {@code X-Api-Key} header IS the authentication — verified in constant
 * time against {@code quizopia.monitoring.ai-ingest.worker-key}. When the
 * property is unset/blank the endpoint is deny-all and answers 404 (a
 * deployment without a worker configured exposes nothing); a present but
 * wrong key answers 401 without detail. No worker is deployed in P4-C —
 * this endpoint is the tested, documented integration point.
 *
 * <p>Items carry {@code attemptId} + {@code userId} explicitly (the
 * worker has no student JWT); provenance is pinned to WORKER_VERIFIED
 * server-side.
 */
@RestController
public class AiWorkerIngestController {

    private final AiDetectionIngestService ingestService;
    private final AiIngestProperties properties;

    public AiWorkerIngestController(AiDetectionIngestService ingestService,
            AiIngestProperties properties) {
        this.ingestService = ingestService;
        this.properties = properties;
    }

    @PostMapping("/internal/ai/v1/detections")
    public ResponseEntity<AiDetectionBatchResponse> ingest(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @Valid @RequestBody AiWorkerDetectionBatchRequest request) {
        String expected = properties.getWorkerKey();
        if (expected == null || expected.isBlank()) {
            // Deny-all while unconfigured (fail closed) — the endpoint
            // pretends not to exist.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (apiKey == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                apiKey.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(ingestService.ingestWorker(request));
    }
}
