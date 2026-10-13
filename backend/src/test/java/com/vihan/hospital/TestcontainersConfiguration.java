package com.vihan.hospital;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A throwaway PostgreSQL container for integration tests (needs Docker Desktop running).
 * Each test run gets an empty database, so Flyway migrations are tested from scratch.
 * Same major version as the hosted database (Neon, DESIGN.md §16).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer("postgres:17-alpine");
	}

}
