package dev.nosurprise.autotests.tests.stub

import dev.nosurprise.autotests.support.AllureSteps.step
import dev.nosurprise.autotests.support.ApiTestBase
import dev.nosurprise.autotests.support.Phones.unique
import dev.nosurprise.autotests.support.ProviderStubs
import dev.nosurprise.autotests.support.RedisSupport
import dev.nosurprise.autotests.support.SqlSupport
import dev.nosurprise.autotests.support.WireMockSupport
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Severity
import io.qameta.allure.SeverityLevel
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID

/**
 * T2: отправка через провайдера (Twilio подменён WireMock) и верификация кода.
 *
 * <p>Стенд работает в профиле twilio (`docker-compose.autotests.yml`): `TWILIO_BASE_URL` указывает
 * на WireMock, backoff ускорен до 1/2 секунд, dispatch-джоба запускается каждые 5 секунд.
 */
@Epic("SMS-верификация")
@Feature("Отправка SMS")
@Story("Ретраи провайдера и подтверждение кода")
@Tag("stub")
@DisplayName("Dispatch: ретраи через WireMock и верификация OTP")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SmsDispatchRetryAndVerifyTest : ApiTestBase() {

    @BeforeEach
    fun cleanStubs() = WireMockSupport.reset()

    @Test
    @Order(1)
    @Severity(SeverityLevel.BLOCKER)
    @Description("Первая попытка провайдера падает 500, вторая успешна: SMS становится DELIVERED за 2 попытки")
    fun `provider fails once then succeeds and sms becomes delivered`() {
        val phone = unique()
        ProviderStubs.stubTwilioRetryOnce(phone)
        val id = createSmsOrFail(phone)

        val delivered = awaitStatus(id, "DELIVERED", Duration.ofSeconds(90))

        assertThat(delivered.get("attempts").asInt)
            .describedAs("ровно две попытки отправки")
            .isEqualTo(2)
        assertThat(attemptResults(id))
            .describedAs("история попыток в sms_attempt")
            .containsExactly("1:FAIL", "2:SUCCESS")

        val requests = ProviderStubs.twilioCalls(phone)
        assertThat(requests).describedAs("вызовов провайдера для этого номера").hasSize(2)
        val sentBody = URLDecoder.decode(requests.last().bodyAsString, UTF_8)
        assertThat(sentBody)
            .describedAs("провайдеру уходит настоящий код, а не маска из БД")
            .containsPattern("Body=Код подтверждения: \\d{6}")
    }

    @Test
    @Order(2)
    @Severity(SeverityLevel.CRITICAL)
    @Description("Три неудачи провайдера исчерпывают max-attempts: статус NOT_DELIVERED, три строки в sms_attempt")
    fun `provider fails all attempts and sms ends as not delivered`() {
        val phone = unique()
        ProviderStubs.stubTwilioAlwaysFail()
        val id = createSmsOrFail(phone)

        val finished = awaitStatus(id, "NOT_DELIVERED", Duration.ofSeconds(120))

        assertThat(finished.get("attempts").asInt)
            .describedAs("исчерпан лимит попыток")
            .isEqualTo(3)
        assertThat(attemptResults(id))
            .describedAs("каждая попытка записана в sms_attempt")
            .containsExactly("1:FAIL", "2:FAIL", "3:FAIL")
        assertThat(finished.get("sentAt").isJsonNull)
            .describedAs("недоставленное SMS не имеет sentAt")
            .isTrue()
    }

    @Test
    @Order(3)
    @Severity(SeverityLevel.BLOCKER)
    @Description("Код из Redis: неверный даёт INVALID_CODE, верный подтверждает, повтор идемпотентен")
    fun `code from redis verifies and repeat is idempotent`() {
        val phone = unique()
        ProviderStubs.stubTwilioSuccess(phone)
        val id = createSmsOrFail(phone)
        awaitStatus(id, "DELIVERED", Duration.ofSeconds(90))

        val code = step("Прочитать OTP из Redis (handoff)") { RedisSupport.code(id) }
        assertThat(code).describedAs("код — шесть цифр").matches("\\d{6}")

        val wrong = verify(id, RedisSupport.differentCode(code))
        assertThat(wrong.get("verified").asBoolean).isFalse()
        assertThat(wrong.get("reason").asString)
            .describedAs("неверный код")
            .isEqualTo("INVALID_CODE")

        val confirmed = verify(id, code)
        assertThat(confirmed.get("verified").asBoolean)
            .describedAs("верный код подтверждает запрос")
            .isTrue()
        assertThat(RedisSupport.isUsed(id))
            .describedAs("после успеха код помечен использованным")
            .isTrue()

        val repeated = verify(id, code)
        assertThat(repeated.get("verified").asBoolean)
            .describedAs("повторная верификация идемпотентна")
            .isTrue()

        val status = step("GET /api/v1/sms/$id после подтверждения") { getSms(id) }
        assertThat(status.get("status").asString).isEqualTo("VERIFIED")
    }

    /** Строки sms_attempt в виде "номер:результат", например "1:FAIL". */
    private fun attemptResults(id: UUID): List<String> = SqlSupport.query(
        "select attempt_no, result from sms_attempt where sms_request_id = cast(? as uuid) order by attempt_no",
        listOf(id.toString()),
    ) { rs -> "${rs.getInt(1)}:${rs.getString(2)}" }

    companion object {
        @JvmStatic
        @BeforeAll
        fun startStubs() = WireMockSupport.start()

        @JvmStatic
        @AfterAll
        fun stopStubs() = WireMockSupport.stop()
    }
}
