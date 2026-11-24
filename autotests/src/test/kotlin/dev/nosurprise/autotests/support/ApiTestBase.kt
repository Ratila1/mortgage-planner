package dev.nosurprise.autotests.support

import com.google.gson.JsonObject
import dev.nosurprise.autotests.api.ActuatorApi
import dev.nosurprise.autotests.api.CreateSmsRequestDto
import dev.nosurprise.autotests.api.SmsApi
import dev.nosurprise.autotests.api.VerifyRequestDto
import dev.nosurprise.autotests.support.AllureSteps.step
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeAll
import retrofit2.Response
import java.time.Duration
import java.util.UUID

/**
 * Общая база API-тестов: один раз на JVM пишет environment.properties для Allure,
 * раздаёт готовые Retrofit-клиенты и шаги, которые повторяются во всех сценариях
 * (создание SMS, чтение статуса, верификация, ожидание асинхронного статуса).
 */
abstract class ApiTestBase {

    protected val smsApi: SmsApi get() = ApiClientFactory.sms()
    protected val actuatorApi: ActuatorApi get() = ApiClientFactory.actuator()

    protected val serviceToken: String by lazy { JwtFactory.serviceToken() }
    protected val serviceAuthorization: String get() = "Bearer $serviceToken"

    /**
     * POST /api/v1/sms. null в [idempotencyKey] = заголовок не отправляется
     * (так проверяется 400 на отсутствующий ключ).
     */
    protected fun createSms(
        idempotencyKey: String? = UUID.randomUUID().toString(),
        phone: String,
        withAllureStep: Boolean = true,
    ): Response<JsonObject> {
        val call = { smsApi.create(idempotencyKey, serviceAuthorization, CreateSmsRequestDto(phone)).execute() }
        return if (withAllureStep) {
            step("POST /api/v1/sms (phone=$phone, key=$idempotencyKey)") { call() }
        } else {
            call()
        }
    }

    /** Создаёт SMS и возвращает id; любой код кроме 202 — провал предусловия. */
    protected fun createSmsOrFail(phone: String): UUID {
        val response = createSms(phone = phone)
        assertThat(response.code()).describedAs("создание SMS для $phone").isEqualTo(202)
        return UUID.fromString(Responses.bodyOf(response).get("id").asString)
    }

    protected fun getSms(id: UUID): JsonObject =
        Responses.bodyOf(smsApi.get(id, serviceAuthorization).execute())

    protected fun verify(id: UUID, code: String): JsonObject {
        val response = step("POST /api/v1/sms/$id/verify (code=$code)") {
            smsApi.verify(id, serviceAuthorization, VerifyRequestDto(code)).execute()
        }
        assertThat(response.code()).describedAs("верификация кода").isEqualTo(200)
        return Responses.bodyOf(response)
    }

    /** Ждёт асинхронный статус SMS: отправку делает фоновый dispatch, ответ API приходит раньше. */
    protected fun awaitStatus(id: UUID, expected: String, timeout: Duration): JsonObject {
        await().atMost(timeout).pollInterval(Duration.ofSeconds(1)).untilAsserted {
            assertThat(getSms(id).get("status").asString)
                .describedAs("статус SMS $id (ждём $expected)")
                .isEqualTo(expected)
        }
        return getSms(id)
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun prepareReportEnvironment() = AllureEnvironment.writeOnce()
    }
}
