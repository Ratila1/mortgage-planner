package dev.nosurprise.mobile

import com.codeborne.selenide.Condition.exactText
import com.codeborne.selenide.Condition.text
import com.codeborne.selenide.Condition.value
import com.codeborne.selenide.Condition.visible
import com.codeborne.selenide.Configuration
import com.codeborne.selenide.Selenide
import com.codeborne.selenide.appium.SelenideAppium
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The same base scenario as android/BaseScenarioTest, but driven externally:
 * Appium starts Chrome on the emulator, Selenide does the clicks and assertions.
 *
 * Requires: docker compose up, running emulator, `appium --port 4723`.
 */
class AppiumBaseScenarioTest {

    @BeforeEach
    fun setUp() {
        Configuration.browser = ChromeDriverProvider::class.java.name
        Configuration.timeout = 25_000
        Configuration.reportsFolder = "build/reports/screenshots"
        SelenideAppium.launchApp()
    }

    @AfterEach
    fun tearDown() {
        Selenide.closeWebDriver()
    }

    @Test
    fun baseScenarioWorksAndMakesScreenshots() {
        Selenide.open(SITE_URL)

        // Step 1: the page and the first calculation are rendered
        Selenide.`$`("h1").shouldBe(visible).shouldHave(text("Ипотека без"))
        Selenide.`$`("body").shouldHave(text("Переплата банку"))
        val initialPayment = Selenide.`$`("main p.text-4xl span").text()
        check(initialPayment.isNotBlank()) { "Payment is not rendered" }
        Selenide.screenshot("01-home")

        // Step 2: change the parameters (React-controlled inputs need a native setter + events)
        Selenide.executeJavaScript<Any>(
            """
            const set = (id, v) => {
              const el = document.getElementById(id);
              Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, String(v));
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
            };
            set('price', 300000); set('down', 60000); set('rate', 10); set('term', 20);
            """.trimIndent(),
        )
        Selenide.`$`("#price").shouldHave(value("300000"))
        Selenide.`$`("#down").shouldHave(value("60000"))
        Selenide.`$`("#rate").shouldHave(value("10"))

        Selenide.`$`("main p.text-4xl span").shouldNotHave(exactText(initialPayment))
        Thread.sleep(700) // AnimatedNumber finishes in ~0.5s

        val payment = Selenide.`$`("main p.text-4xl span").text()
        check(payment.isNotBlank() && payment != initialPayment) { "Payment was not recalculated: $payment" }
        Selenide.`$`("body").shouldHave(text("Переплата банку"))
        check(Selenide.`$$`(".recharts-wrapper").size() > 0) { "Recharts chart is not rendered" }
        check(Selenide.`$$`("table tbody tr").size() > 0) { "Payment schedule is empty" }

        scrollTo("main p.text-4xl", "02-result")
        scrollTo(".recharts-wrapper", "03-chart")

        // Step 3: press "Развернуть всё" and scroll to the payment schedule
        val rowsBefore = Selenide.`$$`("table tbody tr").size()
        Selenide.`$$`("button").findBy(text("Развернуть всё")).click()
        check(Selenide.`$$`("table tbody tr").size() > rowsBefore) { "Schedule did not expand" }
        scrollTo("table", "04-schedule")
    }

    private fun scrollTo(selector: String, screenshotName: String) {
        Selenide.executeJavaScript<Any>(
            "const el = document.querySelector('$selector'); " +
                "if (el) window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - 100);",
        )
        Thread.sleep(400)
        Selenide.screenshot(screenshotName)
    }

    private companion object {
        const val SITE_URL = "http://10.0.2.2:8080/"
    }
}
