package dev.nosurprise.autotests.ui.pages

import com.codeborne.selenide.Condition.disappear
import com.codeborne.selenide.Condition.enabled
import com.codeborne.selenide.Condition.visible
import com.codeborne.selenide.Selenide.`$`
import com.codeborne.selenide.Selenide.open
import dev.nosurprise.autotests.support.MobileKeyboard

/**
 * Страница входа (`/login`). Вход по паролю — режим по умолчанию, поля имеют id=email/password.
 */
class LoginPage {

    fun open(): LoginPage {
        open("/login")
        return this
    }

    fun login(email: String, password: String): LoginPage {
        `$`("#email").shouldBe(visible).setValue(email)
        `$`("#password").shouldBe(visible).setValue(password)
        // На Android клавиатура перекрывает кнопку — без этого клик теряется.
        MobileKeyboard.hideIfAndroid()
        `$`("button[type=submit]").shouldBe(enabled).click()
        // Успешный вход уводит со страницы: форма исчезает (SPA-навигация, без перезагрузки).
        `$`("#password").shouldBe(disappear)
        return this
    }
}
