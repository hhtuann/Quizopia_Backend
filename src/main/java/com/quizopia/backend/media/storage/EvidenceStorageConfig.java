package com.quizopia.backend.media.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Evidence storage wiring (P4-D, ADR-009 §2.3). The decision is made at
 * bean-creation time from the bound {@link EvidenceStorageProperties} —
 * NOT via classpath conditions — so dynamically-registered test properties
 * (Testcontainers MinIO) select the real implementation reliably.
 *
 * <ul>
 *   <li><b>Configured</b> (non-blank access key): the real
 *       {@link MinioEvidenceStorage}; the private bucket is ensured once
 *       at startup.</li>
 *   <li><b>Unconfigured</b> (default in credential-less dev): a disabled
 *       implementation that fails every call — services translate that to
 *       503 MONITORING_INFRA_UNAVAILABLE. The context still starts, so
 *       exam flows NEVER depend on object storage (issue §6 isolation).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class EvidenceStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(EvidenceStorageConfig.class);

    @Bean
    public EvidenceStorage evidenceStorage(EvidenceStorageProperties properties) {
        if (properties.getAccessKey() == null || properties.getAccessKey().isBlank()) {
            log.warn("evidence storage not configured (quizopia.media.storage.access-key) — "
                    + "snapshot/clip/playback endpoints answer 503 until credentials are set");
            return new DisabledEvidenceStorage();
        }
        MinioEvidenceStorage storage = new MinioEvidenceStorage(properties);
        storage.ensureBucket();
        log.info("evidence storage configured (endpoint={}, bucket={})",
                properties.getEndpoint(), properties.getBucket());
        return storage;
    }

    /** Fails every operation — honest unavailability, never silent data loss. */
    static class DisabledEvidenceStorage implements EvidenceStorage {

        private static RuntimeException unavailable() {
            return new MinioEvidenceStorage.EvidenceStorageException(
                    "evidence storage not configured", null);
        }

        @Override
        public StoredObject put(String key, byte[] content, String contentType) {
            throw unavailable();
        }

        @Override
        public java.util.Optional<ObjectHead> head(String key) {
            throw unavailable();
        }

        @Override
        public String presignedGet(String key, java.time.Duration ttl) {
            throw unavailable();
        }

        @Override
        public boolean delete(String key) {
            throw unavailable();
        }

        @Override
        public java.util.List<String> list(String prefix) {
            throw unavailable();
        }
    }
}
