package com.quizopia.backend.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provides a real S3-compatible object store for P4-D evidence integration
 * tests (issues #91–#94): real PUT/GET/HEAD/presign semantics.
 *
 * <p>Image note: {@code minio/minio} left Docker Hub in 2025 (community
 * distribution stopped; the registry API answers "object not found"), so CI
 * cannot pull it. LocalStack's S3 provider speaks the same S3 API the MinIO
 * SDK + presigned URLs need. Dev/prod stacks keep their (cached/local)
 * MinIO image — only the TEST container changes.
 */
@TestConfiguration(proxyBeanMethods = false)
@SuppressWarnings("resource")
public class MinioTestContainerConfiguration {

    private static final int S3_PORT = 4566;

    @Bean
    GenericContainer<?> minioContainer() {
        return new GenericContainer<>(DockerImageName.parse("localstack/localstack:4"))
                .withEnv("SERVICES", "s3")
                .withEnv("AWS_ACCESS_KEY_ID", "testcontainers")
                .withEnv("AWS_SECRET_ACCESS_KEY", "testcontainers")
                .withExposedPorts(S3_PORT);
    }

    @Bean
    DynamicPropertyRegistrar minioStorageProperties(GenericContainer<?> minioContainer) {
        return registry -> {
            registry.add("quizopia.media.storage.endpoint",
                    () -> "http://" + minioContainer.getHost() + ":"
                            + minioContainer.getMappedPort(S3_PORT));
            registry.add("quizopia.media.storage.access-key", () -> "testcontainers");
            registry.add("quizopia.media.storage.secret-key", () -> "testcontainers");
            registry.add("quizopia.media.storage.bucket", () -> "quizopia-evidence-test");
        };
    }
}
