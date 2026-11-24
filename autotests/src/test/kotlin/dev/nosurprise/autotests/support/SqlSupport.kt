package dev.nosurprise.autotests.support

import dev.nosurprise.autotests.config.TestConfig
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Прямые проверки состояния в PostgreSQL тестового стенда: API отвечает 202, но запись в БД
 * проверяется отдельно (иначе идемпотентность проверялась бы только по ответам сервиса).
 */
object SqlSupport {

    /** Схема backend (mortgage-planner): users, customer_profile, loan_applications. */
    const val BACKEND_SCHEMA = "public"

    private val url: String
        get() = "jdbc:postgresql://localhost:${TestConfig.postgresPort}/${TestConfig.postgresDatabase}"

    fun connection(schema: String = TestConfig.postgresSchema): Connection = DriverManager
        .getConnection(url, TestConfig.postgresUser, TestConfig.postgresPassword)
        .also { connection ->
            connection.createStatement().use { it.execute("set search_path to $schema") }
        }

    /** Первая колонка первой строки как Int (например, count(*)). */
    fun count(sql: String, vararg params: Any): Int = connection().use { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    /** UPDATE/INSERT/DELETE: число затронутых строк (например, ускорить retry в outbox). */
    fun update(
        sql: String,
        params: List<Any> = emptyList(),
        schema: String = TestConfig.postgresSchema,
    ): Int = connection(schema).use { connection ->
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    /** Все строки запроса, смапленные в T. */
    fun <T> query(
        sql: String,
        params: List<Any> = emptyList(),
        schema: String = TestConfig.postgresSchema,
        mapper: (ResultSet) -> T,
    ): List<T> = connection(schema).use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(mapper(rs)) }
                }
            }
        }
}
