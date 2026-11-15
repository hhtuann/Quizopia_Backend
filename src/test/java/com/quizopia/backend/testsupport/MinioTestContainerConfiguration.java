package com.quizopia.backend.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provides a real MinIO container for P4-D evidence integration tests
 * (issues #91–#94): real PUT/GET/HEAD/presign semantics against the same
 * image the dev stack runs. Credentials are fixed test-only values; the
 * {@code quizopia.media.storage.*} properties are registered dynamically
 * so {@code MinioEvidenceStorage} connects to the container.
 */
@TestConfiguration(proxyBeanMethods = false)
@SuppressWarnings("resource")
public class MinioTestContainerConfiguration {

    private static final int MINIO_PORT = 9000;

    @Bean
    GenericContainer<?> minioContainer() {
        return new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z"))
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", "minioadmin")
                .withEnv("MINIO_ROOT_PASSWORD", "minioadmin123")
                .withExposedPorts(MINIO_PORT);
    }

    @Bean
    DynamicPropertyRegistrar minioStorageProperties(GenericContainer<?> minioContainer) {
        return registry -> {
            registry.add("quizopia.media.storage.endpoint",
                    () -> "http://" + minioContainer.getHost() + ":"
                            + minioContainer.getMappedPort(MINIO_PORT));
            registry.add("quizopia.media.storage.access-key", () -> "minioadmin");
            registry.add("quizopia.media.storage.secret-key", () -> "minioadmin123");
            registry.add("quizopia.media.storage.bucket", () -> "quizopia-evidence-test");
        };
    }
}
