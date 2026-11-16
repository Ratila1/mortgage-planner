package dev.nosurprise.config;

import dev.nosurprise.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * К22: при старте приложения на чистой PostgreSQL (Testcontainers) миграции Flyway
 * V1–V6 создают схему автоматически: реальные таблицы (§6.1), flyway_schema_history
 * с успешными версиями 1..6, seed банковских предложений из V5.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywaySchemaIT extends IntegrationTestBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        baseProps(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("миграции V1–V6 применяются при старте: таблицы схемы созданы")
    void migrations_createAllTablesOnFreshDatabase() {
        List<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);

        assertThat(tables).contains("users", "user_roles", "bank_offers",
                "saved_scenarios", "notifications", "flyway_schema_history");
    }

    @Test
    @DisplayName("flyway_schema_history фиксирует успешное применение версий 1..6")
    void flywayHistory_containsVersions1Through6_allSuccessful() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "select version, success from flyway_schema_history order by version");

        assertThat(history).hasSize(6);
        for (int i = 1; i <= 6; i++) {
            int version = i;
            Map<String, Object> row = history.stream()
                    .filter(r -> String.valueOf(r.get("version")).equals(String.valueOf(version)))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("migration V" + version + " not applied"));
            assertThat(row.get("success")).as("migration V" + version + " success").isEqualTo(Boolean.TRUE);
        }
    }

    @Test
    @DisplayName("ключевые поля схемы §6.1 созданы (users, saved_scenarios)")
    void schema_containsKeyColumnsFromInstruction() {
        List<String> userColumns = columnsOf("users");
        assertThat(userColumns).contains("email", "password_hash", "email_verified",
                "provider", "provider_id", "created_at");

        List<String> scenarioColumns = columnsOf("saved_scenarios");
        assertThat(scenarioColumns).contains("user_id", "name", "params", "summary",
                "calc_id", "offer_id", "created_at", "updated_at");
    }

    @Test
    @DisplayName("V5 seed_offers положил демо-предложения в bank_offers")
    void seedMigration_insertsDemoBankOffers() {
        Integer offers = jdbcTemplate.queryForObject("select count(*) from bank_offers", Integer.class);
        assertThat(offers).as("seed offers from V5").isGreaterThanOrEqualTo(1);
    }

    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
                "select column_name from information_schema.columns where table_name = ?",
                String.class, table);
    }
}
