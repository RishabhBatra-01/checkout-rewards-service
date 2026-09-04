package com.uniblox.store;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Supplies integration tests with a throwaway PostgreSQL container.
 *
 * <p>{@code @ServiceConnection} points Spring's DataSource at the container, so no
 * connection properties need to be duplicated in test configuration. The image
 * tracks the major version Supabase runs, so tests exercise the same engine as
 * production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }
}
