package com.bank.mortgage.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class FlywayMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("mortgage")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + postgres.getDatabaseName());
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("flywayMigratesCleanDatabase_createsSchema (К22)")
    void flywayMigration_createsSchemaOnCleanDatabase() {
        // This integration test verifies:
        // 1. Flyway runs on application startup
        // 2. All migrations are applied successfully
        // 3. Schema tables are created (users, roles, offers, scenarios, flyway_schema_history)

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        // Check that flyway_schema_history table exists and has entries
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history", Integer.class
        );

        assertThat(migrationCount).isNotNull();
        assertThat(migrationCount).isGreaterThan(0);

        // Check that core tables exist
        assertTableExists(jdbcTemplate, "users");
        assertTableExists(jdbcTemplate, "roles");
        assertTableExists(jdbcTemplate, "offers");
        assertTableExists(jdbcTemplate, "scenarios");
        assertTableExists(jdbcTemplate, "refresh_tokens");
        assertTableExists(jdbcTemplate, "otp_codes");
    }

    private void assertTableExists(JdbcTemplate jdbcTemplate, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                Integer.class,
                tableName
        );
        assertThat(count).isNotNull();
        assertThat(count).isEqualTo(1);
    }
}