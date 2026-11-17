package dev.nosurprise.customer.web.admin;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Поиск пользователей для админки по email и ФИО (MICROLOANS.md §3.5).
 * Исключение из правила «не ходить в чужие таблицы»: поиск по email (users) и ФИО (customer_profile)
 * с пагинацией возможен только одним запросом с join. Запрос только читает users, ничего не меняет;
 * изменения пользователя идут через UserService.
 */
@Repository
public class AdminUserDirectory {

    private static final String FROM = " FROM users u LEFT JOIN customer_profile p ON p.user_id = u.id ";
    private static final String WHERE = " WHERE (:q IS NULL OR lower(u.email) LIKE :q OR lower(p.last_name) LIKE :q "
            + "OR lower(p.first_name) LIKE :q OR lower(coalesce(p.middle_name, '')) LIKE :q) ";

    private final NamedParameterJdbcTemplate jdbc;

    public AdminUserDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page search(String query, int page, int size) {
        String pattern = query == null || query.isBlank()
                ? null
                : "%" + query.trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", pattern, java.sql.Types.VARCHAR)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM + WHERE, params, Long.class);
        List<Row> rows = jdbc.query("SELECT u.id, u.email, u.email_verified, u.blocked, u.created_at, "
                + "p.last_name, p.first_name, p.middle_name, p.phone" + FROM + WHERE
                + "ORDER BY u.created_at DESC LIMIT :limit OFFSET :offset", params, AdminUserDirectory::row);
        return new Page(rows, total == null ? 0 : total, page, size);
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("email"), rs.getBoolean("email_verified"),
                rs.getBoolean("blocked"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("last_name"), rs.getString("first_name"), rs.getString("middle_name"),
                rs.getString("phone"));
    }

    public record Row(UUID id, String email, boolean emailVerified, boolean blocked, Instant createdAt,
                      String lastName, String firstName, String middleName, String phone) {
    }

    public record Page(List<Row> items, long total, int page, int size) {
    }
}
