package dev.nosurprise.kotlin.ui

import com.codeborne.selenide.Condition.text
import com.codeborne.selenide.Configuration
import com.codeborne.selenide.Selenide.`$`
import com.codeborne.selenide.Selenide.executeJavaScript
import com.codeborne.selenide.Selenide.open
import com.codeborne.selenide.SelenideElement
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Epic("Mortgage planner")
@Feature("Web UI")
@Tag("ui")
class WebCalculatorSelenideTest {

    init {
        Configuration.baseUrl = System.getProperty("base.url", "http://localhost:8080")
        Configuration.browser = System.getProperty("browser", "chrome")
        Configuration.headless = System.getProperty("headless", "true").toBoolean()
        Configuration.timeout = 15000
    }

    @Test
    fun webCalculatorShowsExpectedPayment() {
        open("/")

        val price = `$`("#price")
        val down = `$`("#down")
        val term = `$`("#term")
        val rate = `$`("#rate")

        price.value = "1000000"
        down.value = "0"
        setValueWithEvents(term, "1")
        rate.value = "12"

        `$`("body").shouldHave(text("88 848,79"))
    }

    private fun setValueWithEvents(element: SelenideElement, value: String) {
        executeJavaScript<Any>(
            """
            const el = arguments[0];
            const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
            setter.call(el, arguments[1]);
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
            """.trimIndent(),
            element,
            value
        )
    }
}