package dev.nosurprise.autotests.tests.stub

import dev.nosurprise.autotests.config.TestConfig
import dev.nosurprise.autotests.support.ApiTestBase
import dev.nosurprise.autotests.support.Phones.unique
import dev.nosurprise.autotests.support.ProviderStubs
import dev.nosurprise.autotests.support.SqlSupport
import dev.nosurprise.autotests.support.WireMockSupport
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Severity
import io.qameta.allure.SeverityLevel
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * T3: Telegram Bot API подменён WireMock, а доставка идёт через outbox-релей.
 *
 * <p>Событие `SMS_DELIVERED` пишется в `sms.outbox_event` в той же транзакции, что и переход SMS
 * в `DELIVERED`, и только если этого SMS ждёт незавершённая верификация (`verification_requests`
 * со статусом `CREATED`). Дальше релей (`sms.outbox-relay-interval: PT5S`) вызывает
 * `sendMessage`. Тест сидит верификацию и Telegram-привязку в БД: брокер для этого не нужен,
 * проверяется контракт релея и политика ретраев.
 *
 * <p>Ретраи: `RETRYABLE` (Telegram недоступен) → `RETRY` с backoff 30 с, максимум 10 попыток;
 * `PERMANENT` (верификация истекла, кода нет) → `DEAD` сразу.
 */
@Epic("SMS-верификация")
@Feature("Уведомление в Telegram")
@Story("Outbox-релей: PROCESSED, DEAD и отсутствие токена бота")
@Tag("stub")
@DisplayName("Telegram через WireMock: outbox PROCESSED/DEAD")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TelegramOutboxWireMockTest : ApiTestBase() {

    @BeforeEach
    fun cleanStubs() = WireMockSupport.reset()

    @Test
    @Order(1)
    @Severity(SeverityLevel.BLOCKER)
    @Description("Telegram падает 500 на первой попытке и принимает со второй: outbox PROCESSED, верификация DELIVERED, код в Redis")
    fun `telegram fails once then outbox becomes processed`() {
        val phone = unique()
        val chatId = randomChatId()
        ProviderStubs.stubTwilioSuccess(phone)
        ProviderStubs.stubTelegramRetryOnce(chatId)

        val smsId = createSmsOrFail(phone)
        val verificationId = seedVerification(smsId, phone, chatId, Duration.ofMinutes(5))

        val processed = awaitOutbox(smsId, Duration.ofSeconds(90)) { row -> row?.status == "PROCESSED" }

        assertThat(processed.attempts)
            .describedAs("одна неудачная попытка зафиксирована, успешная счётчик не увеличивает")
            .isEqualTo(1)
        assertThat(processed.lastError)
            .describedAs("успешно обработанное событие не хранит ошибку")
            .isNull()
        assertThat(processed.processedAt).describedAs("processed_at заполнен").isNotNull()

        val calls = ProviderStubs.telegramCalls(chatId)
        assertThat(calls).describedAs("два вызова Bot API: отказ и повтор").hasSize(2)
        assertThat(calls.last().bodyAsString)
            .describedAs("в чат уходит код из общего handoff")
            .contains("Ваш код подтверждения: ")
            .containsPattern("\\d{6}")

        assertThat(verificationStatus(verificationId))
            .describedAs("верификация переведена в DELIVERED только после успешной отправки")
            .isEqualTo("DELIVERED")
    }

    @Test
    @Order(2)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Telegram недоступен: событие уходит в RETRY, а на 10-й попытке — в DEAD; верификация остаётся CREATED")
    fun `telegram failures retry then outbox becomes dead`() {
        val phone = unique()
        val chatId = randomChatId()
        ProviderStubs.stubTwilioSuccess(phone)
        ProviderStubs.stubTelegramAlwaysFails(chatId)

        val smsId = createSmsOrFail(phone)
        val verificationId = seedVerification(smsId, phone, chatId, Duration.ofMinutes(5))

        val retry = awaitOutbox(smsId, Duration.ofSeconds(90)) { row -> row?.status == "RETRY" }
        assertThat(retry.attempts).describedAs("попытка засчитана").isEqualTo(1)
        assertThat(retry.lastError)
            .describedAs("причина ретрая — исход доставки, без текста исключения с URL и токеном")
            .isEqualTo("RETRYABLE")
        assertThat(verificationStatus(verificationId))
            .describedAs("пока код не доставлен, верификация не завершается")
            .isEqualTo("CREATED")

        // Релей держит backoff 30 с: чтобы не ждать 9 циклов, ставим событие на предпоследнюю попытку.
        SqlSupport.update(
            "update outbox_event set attempts = 9, next_attempt_at = now() where id = cast(? as uuid)",
            listOf(retry.id.toString()),
        )

        val dead = awaitOutbox(smsId, Duration.ofSeconds(60)) { row -> row?.status == "DEAD" }
        assertThat(dead.attempts).describedAs("лимит попыток исчерпан").isEqualTo(10)
        assertThat(dead.lastError).isEqualTo("RETRYABLE")
        assertThat(dead.processedAt).describedAs("мёртвое событие помечено processed_at").isNotNull()
        assertThat(ProviderStubs.telegramCalls(chatId).size)
            .describedAs("сообщение не было доставлено ни разу")
            .isGreaterThanOrEqualTo(2)
    }

    @Test
    @Order(3)
    @Severity(SeverityLevel.NORMAL)
    @Description("Истёкшая верификация — PERMANENT: DEAD после первой попытки, без вызова Bot API")
    fun `expired verification becomes dead without retries`() {
        val phone = unique()
        val chatId = randomChatId()
        ProviderStubs.stubTwilioSuccess(phone)

        val smsId = createSmsOrFail(phone)
        awaitStatus(smsId, "DELIVERED", Duration.ofSeconds(90))

        // PERMANENT проверяется после доставки SMS, поэтому верификация сидится уже истёкшей,
        // а событие outbox ставится вручную: reconcile такие строки намеренно не подбирает.
        seedVerification(smsId, phone, chatId, Duration.ofSeconds(-60))
        seedOutbox(smsId)

        val dead = awaitOutbox(smsId, Duration.ofSeconds(60)) { row -> row?.status == "DEAD" }

        assertThat(dead.attempts)
            .describedAs("PERMANENT не ретраится: попытка одна")
            .isEqualTo(1)
        assertThat(dead.lastError).isEqualTo("PERMANENT")
        assertThat(ProviderStubs.telegramCalls(chatId))
            .describedAs("истёкший код в Telegram не отправляется")
            .isEmpty()
    }

    @Test
    @Order(4)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Токен бота не попадает ни в БД, ни в артефакты Allure, хотя Telegram отвечает ошибкой")
    fun `bot token never leaks into outbox or allure report`() {
        val phone = unique()
        val chatId = randomChatId()
        ProviderStubs.stubTwilioSuccess(phone)
        ProviderStubs.stubTelegramAlwaysFails(chatId)

        val smsId = createSmsOrFail(phone)
        val verificationId = seedVerification(smsId, phone, chatId, Duration.ofMinutes(5))
        val retry = awaitOutbox(smsId, Duration.ofSeconds(90)) { row -> row?.status == "RETRY" }

        val token = TestConfig.telegramBotToken
        assertThat(token).describedAs("токен стенда задан").isNotBlank()
        assertThat(retry.lastError.orEmpty())
            .describedAs("ошибка в БД описана исходом, а не текстом исключения с URL и токеном")
            .doesNotContain(token)

        val leakedFiles = allureArtifactsContaining(token)
        assertThat(leakedFiles)
            .describedAs("артефакты отчёта с токеном бота ($token) — утечка секрета в Allure")
            .isEmpty()

        // Убираем за собой: иначе релей будет ретраить это событие ещё ~5 минут после теста.
        SqlSupport.update(
            "delete from outbox_event where sms_id = cast(? as uuid)",
            listOf(smsId.toString()),
        )
        assertThat(verificationStatus(verificationId)).isEqualTo("CREATED")
    }

    // --- seeding ---

    /**
     * Telegram-привязка пользователя + ожидающая верификация для конкретного SMS.
     * Отрицательный [expiresIn] даёт уже истёкшую верификацию (сценарий PERMANENT).
     */
    private fun seedVerification(smsId: UUID, phone: String, chatId: Long, expiresIn: Duration): UUID {
        val verificationId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val now = Instant.now()

        SqlSupport.update(
            "insert into telegram_bindings (user_id, phone, telegram_id, username) values (cast(? as uuid), ?, ?, ?)",
            listOf(userId.toString(), phone, chatId, "autotest_$chatId"),
        )
        SqlSupport.update(
            "insert into verification_requests " +
                "(id, user_id, operation_type, operation_ref, status, created_at, expires_at, sms_id) " +
                "values (cast(? as uuid), cast(? as uuid), ?, ?, 'CREATED', ?, ?, cast(? as uuid))",
            listOf(
                verificationId.toString(),
                userId.toString(),
                OPERATION_TYPE,
                "autotest-$verificationId",
                Timestamp.from(now),
                Timestamp.from(now.plus(expiresIn)),
                smsId.toString(),
            ),
        )
        return verificationId
    }

    /** Ручное событие outbox: нужно там, где релей должен увидеть уже существующую строку. */
    private fun seedOutbox(smsId: UUID) {
        SqlSupport.update(
            "insert into outbox_event (id, event_type, sms_id, status, attempts, next_attempt_at, created_at) " +
                "values (cast(? as uuid), 'SMS_DELIVERED', cast(? as uuid), 'NEW', 0, now(), now())",
            listOf(UUID.randomUUID().toString(), smsId.toString()),
        )
    }

    // --- checks ---

    private data class OutboxRow(
        val id: UUID,
        val status: String,
        val attempts: Int,
        val lastError: String?,
        val processedAt: Instant?,
    )

    private fun outbox(smsId: UUID): OutboxRow? = SqlSupport.query(
        "select id, status, attempts, last_error, processed_at from outbox_event " +
            "where event_type = 'SMS_DELIVERED' and sms_id = cast(? as uuid)",
        listOf(smsId.toString()),
    ) { rs ->
        OutboxRow(
            UUID.fromString(rs.getString(1)),
            rs.getString(2),
            rs.getInt(3),
            rs.getString(4),
            rs.getTimestamp(5)?.toInstant(),
        )
    }.firstOrNull()

    private fun awaitOutbox(smsId: UUID, timeout: Duration, expected: (OutboxRow?) -> Boolean): OutboxRow {
        await().atMost(timeout).pollInterval(Duration.ofMillis(500)).untilAsserted {
            assertThat(expected(outbox(smsId)))
                .describedAs("состояние outbox-события для SMS $smsId (сейчас ${outbox(smsId)})")
                .isTrue()
        }
        return outbox(smsId) ?: throw AssertionError("outbox-событие для SMS $smsId исчезло")
    }

    private fun verificationStatus(id: UUID): String = SqlSupport.query(
        "select status from verification_requests where id = cast(? as uuid)",
        listOf(id.toString()),
    ) { rs -> rs.getString(1) }
        .firstOrNull()
        ?: throw AssertionError("Верификация $id не найдена")

    /**
     * Ищет токен бота в уже записанных артефактах прогона: отчёт уходит команде, и секрет
     * не должен появляться ни в шагах, ни во вложениях, ни в environment.properties.
     */
    private fun allureArtifactsContaining(token: String): List<String> {
        val dir = Path.of(System.getProperty("allure.results.directory") ?: "build/allure-results")
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .filter { file -> String(Files.readAllBytes(file), UTF_8).contains(token) }
                .map { dir.relativize(it).toString() }
                .toList()
        }
    }

    private fun randomChatId(): Long = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)

    companion object {
        /** Тип операции Telegram-2FA в audit-таблице; значение свободное, важно только для запроса. */
        private const val OPERATION_TYPE = "CREDIT_DECISION"

        @JvmStatic
        @BeforeAll
        fun startStubs() = WireMockSupport.start()

        @JvmStatic
        @AfterAll
        fun stopStubs() = WireMockSupport.stop()
    }
}
