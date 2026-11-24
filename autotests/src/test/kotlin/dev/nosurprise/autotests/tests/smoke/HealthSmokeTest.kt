package dev.nosurprise.autotests.tests.smoke

import dev.nosurprise.autotests.config.TestConfig
import dev.nosurprise.autotests.support.AllureSteps.step
import dev.nosurprise.autotests.support.ApiTestBase
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
import java.util.UUID

/**
 * Smoke этапа 1: сервис поднялся, readiness принимает трафик, закрытый API отвечает 401 без токена.
 * Тест намеренно не требует JWT — это проверка «стенд живой», а не бизнес-логики.
 */
@Epic("SMS-верификация")
@Feature("Живость сервиса")
@Story("Smoke после поднятия стенда")
@Tag("smoke")
@DisplayName("Smoke: sms-verification-service готов принимать запросы")
class HealthSmokeTest : ApiTestBase() {

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @Description("GET /actuator/health и /actuator/health/readiness отдают 200 и status=UP")
    fun `health and readiness report UP`() {
        val health = step("GET /actuator/health") { actuatorApi.health().execute() }
        assertThat(health.code())
            .describedAs("HTTP-код /actuator/health")
            .isEqualTo(200)
        assertThat(health.body()?.get("status")?.asString)
            .describedAs("status в /actuator/health")
            .isEqualTo("UP")

        val readiness = step("GET /actuator/health/readiness") { actuatorApi.readiness().execute() }
        assertThat(readiness.code())
            .describedAs("HTTP-код /actuator/health/readiness")
            .isEqualTo(200)
        assertThat(readiness.body()?.get("status")?.asString)
            .describedAs("status в readiness")
            .isEqualTo("UP")
    }

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @Description("SMS API закрыт deny-by-default: без service-JWT любой запрос получает 401")
    fun `sms api is closed without service jwt`() {
        val id = UUID.randomUUID()

        val response = step("GET /api/v1/sms/$id без Authorization") {
            smsApi.get(id, authorization = null).execute()
        }

        assertThat(response.code())
            .describedAs("SMS API без токена (стенд: ${TestConfig.smsBaseUrl})")
            .isEqualTo(401)
    }
}
