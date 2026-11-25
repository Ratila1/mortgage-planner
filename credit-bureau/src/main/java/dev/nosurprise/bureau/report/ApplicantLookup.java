package dev.nosurprise.bureau.report;

import com.zaxxer.hikari.HikariDataSource;
import dev.nosurprise.bureau.config.LookupProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Поиск заёмщика в базе монолита (CREDIT_BUREAU.md §7.2, шаг 5): VIEW bureau_applicant_lookup → user_id.
 * <p>
 * Своё подключение, а не второй DataSource-бин: так автонастройка Spring Boot (JPA, Flyway) остаётся на базе
 * бюро и не путается в двух источниках. Пул read-only — Postgres отклонит любую запись даже при баге; маленький —
 * чтобы не отъедать соединения у монолита.
 * <p>
 * Контракт с монолитом — только колонки VIEW (миграция монолита V16). Поменяли VIEW — поправь запрос здесь.
 */
@Component
public class ApplicantLookup implements DisposableBean {

    /**
     * Три варианта запроса под три режима поиска (§3.1), а не один с «(? IS NULL OR ...)»: на подготовленном
     * запросе Postgres со временем переходит на общий план, и с OR по параметрам перестаёт использовать индексы.
     * LIMIT 2: больше не нужно — 0 не найден, 1 найден, 2 неоднозначно (§3.4).
     */
    static final String BY_PHONE = "SELECT DISTINCT user_id FROM bureau_applicant_lookup WHERE phone = ? LIMIT 2";
    static final String BY_NAME = "SELECT DISTINCT user_id FROM bureau_applicant_lookup "
            + "WHERE last_name_norm = ? AND first_name_norm = ?";
    static final String BY_PHONE_AND_NAME = "SELECT DISTINCT user_id FROM bureau_applicant_lookup "
            + "WHERE phone = ? AND last_name_norm = ? AND first_name_norm = ?";
    static final String MIDDLE_NAME = " AND middle_name_norm = ?";
    static final String LIMIT = " LIMIT 2";

    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;

    public ApplicantLookup(LookupProperties properties) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("monolith-lookup");
        ds.setJdbcUrl(properties.url());
        ds.setUsername(properties.username());
        ds.setPassword(properties.password());
        ds.setReadOnly(true);
        ds.setMaximumPoolSize(properties.maximumPoolSize());
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(properties.connectionTimeout().toMillis());
        // Не падаем при старте, если монолит ещё поднимает свою базу: соединение возьмётся при первом запросе.
        ds.setInitializationFailTimeout(-1);
        this.dataSource = ds;
        this.jdbc = new JdbcTemplate(ds);
    }

    /** До двух разных user_id, у которых есть займ (ACTIVE/REPAID) с такими параметрами заявителя. */
    public List<UUID> find(SearchQuery query) {
        return switch (query.searchBy()) {
            case PHONE -> jdbc.queryForList(BY_PHONE, UUID.class, query.phone());
            case NAME -> query.middleName() == null
                    ? jdbc.queryForList(BY_NAME + LIMIT, UUID.class, query.lastName(), query.firstName())
                    : jdbc.queryForList(BY_NAME + MIDDLE_NAME + LIMIT, UUID.class, query.lastName(),
                            query.firstName(), query.middleName());
            case PHONE_AND_NAME -> query.middleName() == null
                    ? jdbc.queryForList(BY_PHONE_AND_NAME + LIMIT, UUID.class, query.phone(), query.lastName(),
                            query.firstName())
                    : jdbc.queryForList(BY_PHONE_AND_NAME + MIDDLE_NAME + LIMIT, UUID.class, query.phone(),
                            query.lastName(), query.firstName(), query.middleName());
        };
    }

    @Override
    public void destroy() {
        dataSource.close();
    }
}
