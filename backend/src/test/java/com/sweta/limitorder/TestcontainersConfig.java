package com.sweta.limitorder;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers config for all integration tests.
 *
 * <p>{@link ServiceConnection} wires the running container's JDBC URL,
 * username, and password into Spring's {@code DataSource} auto-configuration —
 * no {@code @DynamicPropertySource} boilerplate required.
 *
 * <p>Each {@code @SpringBootTest} that imports this class gets a freshly-booted
 * Postgres against which Flyway runs every migration from V1 forward, so all
 * integration tests see schema + seed data in a known-good state.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("lob_test")
                .withUsername("lob")
                .withPassword("lob");
    }
}
