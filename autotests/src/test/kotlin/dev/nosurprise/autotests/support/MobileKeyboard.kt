package dev.nosurprise.autotests.support

import com.codeborne.selenide.WebDriverRunner
import io.appium.java_client.android.AndroidDriver

/**
 * Мягкая клавиатура Android перекрывает нижнюю часть страницы.
 *
 * <p>Клик ChromeDriver'а считается от центра элемента: если кнопка оказалась под клавиатурой, тап
 * уходит в неё, а страница остаётся как была — форма визуально заполнена, но запрос не отправлен.
 * Перед кликами по кнопкам ниже полей ввода клавиатуру убираем.
 *
 * <p>В десктопном браузере (T4) драйвер не [AndroidDriver], поэтому вызов ничего не делает.
 */
object MobileKeyboard {

    fun hideIfAndroid() {
        val driver = WebDriverRunner.getWebDriver()
        if (driver is AndroidDriver) {
            // Клавиатуры может не быть — Appium в этом случае возвращает ошибку, она не важна.
            runCatching { driver.hideKeyboard() }
        }
    }
}
