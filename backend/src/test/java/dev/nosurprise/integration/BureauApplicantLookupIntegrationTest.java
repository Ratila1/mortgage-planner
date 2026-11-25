package dev.nosurprise.integration;

import dev.nosurprise.integration.support.LoanItSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import tools.jackson.databind.JsonNode;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт с сервисом credit-bureau (CREDIT_BUREAU.md §4): VIEW bureau_applicant_lookup отдаёт только займы
 * (ACTIVE/REPAID) с нормализованными ФИО, а поиск по телефону и по ФИО идёт по индексам.
 */
@DisplayName("Кредитное бюро: VIEW поиска заёмщика")
class BureauApplicantLookupIntegrationTest extends LoanItSupport {

    @Test
    @DisplayName("в VIEW только ACTIVE и REPAID; ФИО в нижнем регистре")
    void onlyLoans() {
        Client client = readyClient("bureau-view");
        JsonNode application = submitOk(client);
        String applicationId = id(application);
        assertThat(lookupByPhone(client.phone())).as("SUBMITTED — ещё не заём").isEmpty();

        approve(adminToken(), application);
        assertThat(lookupByPhone(client.phone())).as("APPROVED — ещё не заём").isEmpty();

        awaitContract(client, applicationId);
        sign(client, applicationId);

        assertThat(lookupByPhone(client.phone())).containsExactly(client.id());
        // ФИО тестовых клиентов — «Иванов Иван Иванович» (LoanItSupport.profileJson).
        List<String> names = jdbc.queryForList("select last_name_norm || ' ' || first_name_norm || ' ' "
                + "|| middle_name_norm from bureau_applicant_lookup where application_id = ?::uuid", String.class,
                applicationId);
        assertThat(names).containsExactly("иванов иван иванович");
    }

    @Test
    @DisplayName("поиск по телефону и по ФИО идёт по частичным индексам")
    void usesIndexes() {
        assertThat(plan("select distinct user_id from bureau_applicant_lookup where phone = '+375290000000'"))
                .contains("idx_loan_applications_applicant_phone");
        assertThat(plan("select distinct user_id from bureau_applicant_lookup "
                + "where last_name_norm = 'иванов' and first_name_norm = 'иван'"))
                .contains("idx_loan_applications_applicant_names");
    }

    private List<UUID> lookupByPhone(String phone) {
        return jdbc.queryForList("select distinct user_id from bureau_applicant_lookup where phone = ?", UUID.class,
                phone);
    }

    /** План запроса с выключенным seq scan: в тестовой базе мало строк, иначе Postgres честно выберет полный проход. */
    private String plan(String sql) {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("set local enable_seqscan = off");
                StringBuilder plan = new StringBuilder();
                try (ResultSet rs = statement.executeQuery("explain " + sql)) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
                return plan.toString();
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }
}
