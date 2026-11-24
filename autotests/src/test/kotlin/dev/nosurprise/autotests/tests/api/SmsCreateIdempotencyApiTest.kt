package dev.nosurprise.autotests.tests.api

import dev.nosurprise.autotests.api.CreateSmsRequestDto
import dev.nosurprise.autotests.support.AllureSteps.step
import dev.nosurprise.autotests.support.ApiTestBase
import dev.nosurprise.autotests.support.JwtFactory
import dev.nosurprise.autotests.support.Phones.unique
import dev.nosurprise.autotests.support.Responses.bodyOf
import dev.nosurprise.autotests.support.Responses.errorOf
import dev.nosurprise.autotests.support.SqlSupport
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Severity
import io.qameta.allure.SeverityLevel
import io.qameta.allure.Story
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * T1: контракт создания SMS и идемпотентность по Idempotency-Key.
 *
 * <p>Тест ходит в реальный сервис и реальные PostgreSQL/Redis тестового стенда
 * (`docker compose -p sms-autotests ...`), но не ждёт отправку провайдеру: проверяется
 * только синхронная часть API.
 *
 * <p>Телефон — уникальный на каждый тест-метод: лимит 3 запроса/мин считается на номер,
 * иначе соседний кейс получал бы 429.
 */
@Epic("SMS-верификация")
@Feature("Создание SMS")
@Story("Идемпотентность и валидация")
@Tag("api")
@DisplayName("SMS API: создание запроса и идемпотентность")
class SmsCreateIdempotencyApiTest : ApiTestBase() {

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @Description("Новый Idempotency-Key создаёт запрос: 202 и контракт CREATED с TTL 5 минут")
    fun `create returns 202 and CREATED contract`() {
        val phone = unique()
        val key = UUID.randomUUID().toString()

        val response = createSms(key, phone)

        assertThat(response.code())
            .describedAs("HTTP-код создания нового SMS")
            .isEqualTo(202)

        val body = bodyOf(response)
        assertThat(body.get("id").asString)
            .describedAs("id созданного запроса")
            .isNotBlank()
        assertThat(body.get("status").asString)
            .describedAs("статус сразу после создания")
            .isEqualTo("CREATED")
        assertThat(body.get("attempts").asInt)
            .describedAs("попыток отправки ещё не было")
            .isZero()
        assertThat(body.get("sentAt").isJsonNull)
            .describedAs("sentAt пуст до отправки")
            .isTrue()

        val createdAt = Instant.parse(body.get("createdAt").asString)
        val expiresAt = Instant.parse(body.get("expiresAt").asString)
        assertThat(Duration.between(createdAt, expiresAt))
            .describedAs("TTL кода: expiresAt ≈ createdAt + 5 минут")
            .isBetween(Duration.ofMinutes(4), Duration.ofMinutes(6))
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @Description("Повтор с тем же ключом и номером отдаёт 200 и тот же id, новая строка не создаётся")
    fun `same key and phone returns 200 with same id`() {
        val phone = unique()
        val key = UUID.randomUUID().toString()

        val first = bodyOf(createSms(key, phone))
        val second = createSms(key, phone)

        assertThat(second.code())
            .describedAs("повтор идемпотентного запроса")
            .isEqualTo(200)
        val repeated = bodyOf(second)
        assertThat(repeated.get("id").asString)
            .describedAs("повтор возвращает тот же запрос")
            .isEqualTo(first.get("id").asString)
        // Статус мог уйти вперёд, если dispatcher успел отправить SMS между вызовами.
        assertThat(repeated.get("status").asString)
            .describedAs("статус существующего запроса")
            .isIn("CREATED", "DELIVERED", "VERIFIED")

        assertThat(SqlSupport.count("select count(*) from sms_request where idempotency_key = ?", key))
            .describedAs("на Idempotency-Key должна быть ровно одна строка")
            .isEqualTo(1)
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @Description("Тот же ключ с другим номером — конфликт 409, а не второй SMS")
    fun `same key with another phone returns 409`() {
        val key = UUID.randomUUID().toString()
        val phone = unique()
        createSms(key, phone)

        val conflict = createSms(key, unique())

        assertThat(conflict.code())
            .describedAs("ключ уже использован с другим номером")
            .isEqualTo(409)
        val error = errorOf(conflict)
        assertThat(error.get("status").asInt).isEqualTo(409)
        assertThat(error.get("message").asString)
            .describedAs("сообщение объясняет конфликт ключа")
            .containsIgnoringCase("different phone")
    }

    @Test
    @Severity(SeverityLevel.NORMAL)
    @Description("Без обязательного заголовка Idempotency-Key сервис отвечает 400 с описанием ошибки")
    fun `missing idempotency key returns 400`() {
        val response = createSms(idempotencyKey = null, phone = unique())

        assertThat(response.code())
            .describedAs("отсутствующий Idempotency-Key")
            .isEqualTo(400)
        assertThat(errorOf(response).get("message").asString)
            .describedAs("в ошибке назван отсутствующий заголовок")
            .contains("Idempotency-Key")
    }

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @Description("SMS API принимает только service-JWT: без токена и с пользовательским токеном — 401")
    fun `auth is required and user token is rejected`() {
        val withoutToken = smsApi.create(UUID.randomUUID().toString(), null, CreateSmsRequestDto(unique())).execute()
        assertThat(withoutToken.code())
            .describedAs("запрос без Authorization")
            .isEqualTo(401)

        val userToken = step("POST /api/v1/sms с пользовательским JWT (sub=userId)") {
            smsApi.create(
                UUID.randomUUID().toString(),
                "Bearer ${JwtFactory.userToken()}",
                CreateSmsRequestDto(unique()),
            ).execute()
        }
        assertThat(userToken.code())
            .describedAs("пользовательский токен не должен приниматься SMS API")
            .isEqualTo(401)
    }

    @Test
    @Severity(SeverityLevel.NORMAL)
    @Description("Невалидный номер — 422, пустой — 400 (bean-validation), запись не создаётся")
    fun `invalid phone returns 422 and blank phone 400`() {
        listOf("+7", "abc").forEach { invalidPhone ->
            val response = createSms(UUID.randomUUID().toString(), invalidPhone)
            assertThat(response.code())
                .describedAs("номер '%s' должен быть отвергнут как невалидный", invalidPhone)
                .isEqualTo(422)
            assertThat(errorOf(response).get("message").asString)
                .describedAs("причина отказа для '%s'", invalidPhone)
                .contains("Invalid phone number")
        }

        val blankPhone = createSms(UUID.randomUUID().toString(), "")
        assertThat(blankPhone.code())
            .describedAs("пустой номер ловится bean-validation, а не нормализатором")
            .isEqualTo(400)
    }

    @Test
    @Severity(SeverityLevel.NORMAL)
    @Description("GET статуса не раскрывает телефон и текст SMS; неизвестный id — 404")
    fun `status endpoint hides phone and message`() {
        val created = bodyOf(createSms(UUID.randomUUID().toString(), unique()))
        val id = UUID.fromString(created.get("id").asString)

        val status = step("GET /api/v1/sms/$id") { smsApi.get(id, "Bearer $serviceToken").execute() }

        assertThat(status.code()).describedAs("статус существующего запроса").isEqualTo(200)
        val body = bodyOf(status)
        assertThat(body.has("phone")).describedAs("телефон не должен отдаваться наружу").isFalse()
        assertThat(body.has("message")).describedAs("текст SMS не должен отдаваться наружу").isFalse()

        val unknown = step("GET /api/v1/sms/<неизвестный id>") {
            smsApi.get(UUID.randomUUID(), "Bearer $serviceToken").execute()
        }
        assertThat(unknown.code()).describedAs("неизвестный id").isEqualTo(404)
    }

    @Test
    @Severity(SeverityLevel.MINOR)
    @Description("Лимит 3 SMS в минуту на номер: четвёртый запрос с новым ключом получает 429")
    fun `phone rate limit returns 429 on fourth request`() {
        val phone = unique()
        val codes = (1..4).map { attempt ->
            step("POST /api/v1/sms, попытка $attempt для $phone") {
                createSms(UUID.randomUUID().toString(), phone, withAllureStep = false)
            }.code()
        }

        assertThat(codes.take(3))
            .describedAs("первые три запроса в минуту проходят")
            .containsOnly(202)
        assertThat(codes.last())
            .describedAs("четвёртый запрос упирается в лимит на номер")
            .isEqualTo(429)
    }

}
