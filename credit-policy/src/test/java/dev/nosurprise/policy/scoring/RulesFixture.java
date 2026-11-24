package dev.nosurprise.policy.scoring;

import dev.nosurprise.policy.rules.RuleSet;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Правила версии 1 берутся прямо из миграции V1: тесты проверяют ровно то, что попадёт в базу,
 * и упадут, если JSON в миграции перестанет разбираться.
 */
public final class RulesFixture {

    private RulesFixture() {
    }

    public static RuleSet v1() {
        try (InputStream in = RulesFixture.class.getResourceAsStream("/db/migration/V1__credit_policy.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int start = sql.indexOf("'{", sql.indexOf("INSERT INTO rule_sets")) + 1;
            int end = sql.indexOf("}'::jsonb", start) + 1;
            return JsonMapper.builder().build().readValue(sql.substring(start, end), RuleSet.class);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
