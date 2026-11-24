package dev.nosurprise.autotests.tests.mobile

import com.codeborne.selenide.Configuration
import com.codeborne.selenide.Selenide
import com.codeborne.selenide.WebDriverRunner
import com.codeborne.selenide.logevents.SelenideLogger
import dev.nosurprise.autotests.config.TestConfig
import dev.nosurprise.autotests.support.ApiTestBase
import dev.nosurprise.autotests.support.WireMockSupport
import dev.nosurprise.autotests.ui.PhoneVerificationScenario
import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.android.options.UiAutomator2Options
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.net.URI

/**
 * T5: тот же сценарий, что в T4, но в мобильном Chrome на Android-эмуляторе.
 *
 * <p>Page Objects и шаги — общие ([PhoneVerificationScenario]); меняются только драйвер
 * ([AndroidDriver] подставляется в Selenide через [WebDriverRunner.setWebDriver]), адрес стенда
 * (`10.0.2.2` — loopback хост-машины из эмулятора) и таймауты.
 *
 * <p>Включается флагом `-Dappium=true`; без него тест скипается, чтобы обычный прогон
 * не требовал ни Appium-сервера, ни эмулятора.
 *
 * <p>Требуется на машине: Android emulator с Chrome, `appium` на `:4723` и драйвер `uiautomator2`.
 * Если Appium не может сам подобрать chromedriver, путь к нему задаётся `-Dappium.chromedriver=<...>.exe`.
 */
@Epic("Микрозаймы")
@Feature("Подтверждение телефона")
@Story("Мобильный Chrome через Appium")
@Tag("mobile")
@EnabledIfSystemProperty(named = "appium", matches = "true")
@DisplayName("Mobile: подтверждение телефона в мобильном Chrome")
class PhoneVerifyMobileTest : ApiTestBase() {

    @Test
    @Severity(SeverityLevel.CRITICAL)
    @Description("Мобильный Chrome: панель «Подтвердите телефон» принимает код из SMS, подпись разблокируется")
    fun `sms code confirms phone in mobile chrome`() = PhoneVerificationScenario.run()

    companion object {
        @JvmStatic
        @BeforeAll
        fun configureMobileBrowser() {
            WireMockSupport.start()
            // 10.0.2.2 — хост-машина глазами Android-эмулятора.
            Configuration.baseUrl = TestConfig.mobileBaseUrl
            // Мобильный Chrome и эмулятор медленнее десктопа: те же ожидания, но с запасом.
            Configuration.timeout = TestConfig.uiTimeout.toMillis() * 2
            SelenideLogger.addListener("allure", AllureSelenide().screenshots(true).savePageSource(true))
            // Свой драйвер: Selenide использует его вместо локального Chrome.
            WebDriverRunner.setWebDriver(androidDriver())
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            Selenide.closeWebDriver()
            WireMockSupport.stop()
        }

        private fun androidDriver(): AndroidDriver {
            // Капабилити через amend: у UiAutomator2Options сеттеры возвращают void, а amend — сам options.
            val options = UiAutomator2Options()
                .amend("platformName", "Android")
                .amend("appium:deviceName", TestConfig.appiumDeviceName)
                .amend("browserName", TestConfig.appiumBrowser)
                .amend("appium:noReset", true)
            // Явный драйвер вместо автоскачивания: на закрытых сетях и нестандартных сборках Chrome
            // Appium не находит версию (например, Chrome 124 в эмуляторе), и сессия не создаётся.
            if (TestConfig.appiumChromedriver.isNotBlank()) {
                options.amend("appium:chromedriverExecutable", TestConfig.appiumChromedriver)
            } else {
                options.amend("appium:chromedriverAutodownload", true)
            }
            return AndroidDriver(URI(TestConfig.appiumUrl).toURL(), options)
        }
    }
}
