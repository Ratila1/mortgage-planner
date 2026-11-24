package dev.nosurprise.autotests.ui.pages

import com.codeborne.selenide.Condition.disappear
import com.codeborne.selenide.Condition.enabled
import com.codeborne.selenide.Condition.visible
import com.codeborne.selenide.Selenide.`$`
import com.codeborne.selenide.Selenide.`$x`
import com.codeborne.selenide.Selenide.open
import dev.nosurprise.autotests.support.MobileKeyboard

/**
 * Уведомление об одобрении заявки (`/notifications`): внутри карточки — подпись договора,
 * а до подтверждения телефона — панель `PhoneVerifyPanel`.
 */
class NotificationsPage {

    fun open(): NotificationsPage {
        open("/notifications")
        return this
    }

    fun shouldShowApprovedCard(): NotificationsPage {
        approvedCard().shouldBe(visible)
        return this
    }

    fun shouldShowPhonePanel(): NotificationsPage {
        phonePanelTitle().shouldBe(visible)
        codeInput().shouldBe(visible)
        return this
    }

    fun requestCode(): NotificationsPage {
        getCodeButton().shouldBe(visible, enabled).click()
        return this
    }

    /** После 202 от бэка: подсказка про SMS и кнопка с обратным отсчётом «Получить код (Nс)». */
    fun shouldShowCodeSent(): NotificationsPage {
        codeSentHint().shouldBe(visible)
        cooldownButton().shouldBe(visible)
        return this
    }

    fun enterCode(code: String): NotificationsPage {
        codeInput().shouldBe(visible).setValue(code)
        return this
    }

    fun confirm(): NotificationsPage {
        MobileKeyboard.hideIfAndroid()
        confirmButton().shouldBe(visible, enabled).click()
        return this
    }

    /** Панель исчезла, вместо неё — блок «Чтобы подписать договор…» (телефон подтверждён). */
    fun shouldShowSigningUnlocked(): NotificationsPage {
        phonePanelTitle().shouldBe(disappear)
        signingHint().shouldBe(visible)
        return this
    }

    private fun approvedCard() = `$x`("//p[contains(., 'Вашу заявку на микрозайм одобрили')]")

    private fun phonePanelTitle() = `$x`("//p[normalize-space()='Подтвердите телефон']")

    private fun getCodeButton() = `$x`("//button[normalize-space()='Получить код']")

    private fun cooldownButton() = `$x`("//button[contains(normalize-space(), 'Получить код (')]")

    private fun codeSentHint() = `$x`("//p[contains(., 'Код отправлен. Введите цифры из SMS')]")

    private fun codeInput() = `$`("input[placeholder='Код из SMS']")

    private fun confirmButton() = `$x`("//button[normalize-space()='Подтвердить']")

    private fun signingHint() = `$x`("//p[contains(., 'Чтобы подписать договор')]")
}
