package dev.nosurprise.autotests.ui

import com.codeborne.selenide.WebDriverRunner
import dev.nosurprise.autotests.support.AllureSteps.step
import dev.nosurprise.autotests.support.ProviderStubs
import dev.nosurprise.autotests.support.RedisSupport
import dev.nosurprise.autotests.support.UiSeed
import dev.nosurprise.autotests.support.WireMockSupport
import dev.nosurprise.autotests.ui.pages.LoginPage
import dev.nosurprise.autotests.ui.pages.NotificationsPage
import io.qameta.allure.Allure
import org.assertj.core.api.Assertions.assertThat
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import java.io.ByteArrayInputStream

/**
 * Сценарий подтверждения телефона кодом из SMS, общий для T4 (Chrome) и T5 (мобильный Chrome).
 *
 * <p>Отличаются только драйвер и адрес стенда, поэтому шаги, Page Objects и скриншоты живут здесь
 * в одном экземпляре: тест настраивает браузер и вызывает [run].
 *
 * <p>Данные готовятся в обход UI ([UiSeed]): регистрация, профиль с телефоном, KYC и заявка
 * `APPROVED` с договором. В браузере проверяется только то, что относится к подтверждению телефона.
 */
object PhoneVerificationScenario {

    fun run() {
        val account = UiSeed.approvedApplication()
        // SMS уходит в WireMock: стенд работает в профиле twilio, и без стаба код не доедет.
        WireMockSupport.reset()
        ProviderStubs.stubTwilioSuccess(account.phone)

        step("Логин: ${account.email}") {
            LoginPage().open().login(account.email, account.password)
        }

        val page = step("Открыть /notifications и дождаться карточки одобрения") {
            NotificationsPage().open().shouldShowApprovedCard()
        }
        step("Дождаться панели «Подтвердите телефон»") { page.shouldShowPhonePanel() }
        screenshot("Панель «Подтвердите телефон»")

        step("Нажать «Получить код»") { page.requestCode() }
        step("Проверить подтверждение отправки и кулдаун кнопки") { page.shouldShowCodeSent() }
        screenshot("Код отправлен, кнопка в кулдауне 60 с")

        val code = step("Дождаться DELIVERED и прочитать OTP из Redis") {
            val smsId = UiSeed.awaitDeliveredSms(account.phone)
            assertThat(ProviderStubs.twilioCalls(account.phone))
                .describedAs("SMS ушла через провайдера (стаб Twilio)")
                .isNotEmpty()
            RedisSupport.code(smsId)
        }
        assertThat(code).describedAs("код из SMS").matches("\\d{6}")

        step("Ввести код и нажать «Подтвердить»") { page.enterCode(code).confirm() }
        step("Проверить, что панель исчезла и подпись доступна") { page.shouldShowSigningUnlocked() }
        screenshot("Телефон подтверждён, подпись договора доступна")

        step("Проверить phone_verified_at в БД") {
            assertThat(UiSeed.phoneVerifiedAt(account.phone))
                .describedAs("customer_profile.phone_verified_at для ${account.phone}")
                .isNotNull()
        }
    }

    /** Скриншот снимается с текущего драйвера: у Selenide и у Appium это один и тот же WebDriver. */
    private fun screenshot(name: String) {
        val bytes = (WebDriverRunner.getWebDriver() as TakesScreenshot)
            .getScreenshotAs(OutputType.BYTES)
        Allure.addAttachment(name, "image/png", ByteArrayInputStream(bytes), ".png")
    }
}
