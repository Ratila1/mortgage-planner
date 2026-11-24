package dev.nosurprise.autotests.tests.ui

import com.codeborne.selenide.Configuration
import com.codeborne.selenide.logevents.SelenideLogger
import dev.nosurprise.autotests.config.TestConfig
import dev.nosurprise.autotests.support.ApiTestBase
import dev.nosurprise.autotests.support.WireMockSupport
import dev.nosurprise.autotests.ui.PhoneVerificationScenario
import io.qameta.allure.Description
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Severity
import io.qameta.allure.SeverityLevel
import io.qameta.allure.Story
import io.qameta.allure.selenide.AllureSelenide
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * T4: подтверждение телефона кодом из SMS в реальном UI (`http://localhost:8080`).
 *
 * <p>Сценарий — в [PhoneVerificationScenario], он же используется мобильным T5: здесь задаётся
 * только десктопный Chrome. Драйвер подбирает Selenium Manager. По умолчанию headless:
 * `gradle test -Ptags=ui -Dselenide.headless=false` покажет окно.
 */
@Epic("Микрозаймы")
@Feature("Подтверждение телефона")
@Story("OTP-панель в уведомлении об одобрении")
@Tag("ui")
@DisplayName("UI: подтверждение телефона кодом из SMS")
class PhoneVerifyUiTest : ApiTestBase() {

    @Test
    @Severity(SeverityLevel.BLOCKER)
    @Description("Код из SMS подтверждает телефон: панель исчезает, подпись договора разблокируется, phone_verified_at заполнен")
    fun `sms code confirms phone and unlocks contract signing`() = PhoneVerificationScenario.run()

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureBrowser() {
            WireMockSupport.start()
            Configuration.baseUrl = TestConfig.frontendBaseUrl
            Configuration.browser = "chrome"
            Configuration.headless = System.getProperty("selenide.headless", "true").toBoolean()
            Configuration.browserSize = "1440x900"
            Configuration.timeout = TestConfig.uiTimeout.toMillis()
            SelenideLogger.addListener("allure", AllureSelenide().screenshots(true).savePageSource(true))
        }

        @JvmStatic
        @AfterAll
        fun tearDown() = WireMockSupport.stop()
    }
}
