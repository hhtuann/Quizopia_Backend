package com.quizopia.backend.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provides a shared PostgreSQL container for integration tests.
 *
 * <p>The container runs the same PostgreSQL major version as development
 * (image {@code postgres:17}) and is registered as a Spring bean, so Spring
 * manages its lifecycle (start/stop) automatically. {@link ServiceConnection}
 * supplies the JDBC connection details to the application, replacing the need
 * for a static datasource URL or {@code @DynamicPropertySource}.
 */
@TestConfiguration(proxyBeanMethods = false)
@SuppressWarnings({"resource"})
public class PostgresTestContainerConfiguration {

    /**
     * Creates the PostgreSQL container used by all integration tests.
     *
     * <p>The database name, username and password are fixed test-only values.
     * The container is not started or stopped manually; Spring's lifecycle
     * management and {@link ServiceConnection} handle wiring and execution.
     *
     * @return the PostgreSQL container registered for service connection
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withDatabaseName("quizopia_test")
                .withUsername("quizopia_test")
                .withPassword("quizopia_test");
    }
}
