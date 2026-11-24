package dev.nosurprise.mobile

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Единственный инструментальный тест: открывает мобильный сайт в WebView
 * внутри Android-эмулятора и проверяет, что страница реально отрисовалась
 * в мобильной вёрстке и что расчёт пришёл с бэкенда.
 *
 * Перед запуском должен быть поднят `docker compose up` (nginx на :8080)
 * и запущен эмулятор.
 */
@RunWith(AndroidJUnit4::class)
class MobileSiteTest {

    @Test(timeout = 120_000)
    fun mobileSiteRendersCalculatorInMobileLayout() {
        val webViewRef = AtomicReference<WebView>()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { webViewRef.set(it.webView) }
            val webView = checkNotNull(webViewRef.get()) { "WebView не создался" }

            waitFor("страница загрузилась и React отрисовал h1") {
                webView.evalJs("document.readyState === 'complete' && !!document.querySelector('h1')") == "true"
            }
            // "Ежемесячный платёж" и "Переплата банку" рисуются только после
            // ответа POST /api/calc, т.е. проверяют связку эмулятор → nginx → backend.
            // Сравниваем без регистра: eyebrow в CSS написан капсом (text-transform).
            try {
                waitFor("пришёл расчёт с бэкенда") {
                    webView.evalJs(
                        "(() => { const t = document.body.innerText.toLowerCase(); " +
                            "return t.includes('ежемесячный платёж') && t.includes('переплата банку'); })()",
                    ) == "true"
                }
            } catch (e: AssertionError) {
                throw AssertionError(
                    e.message + "\n--- innerText ---\n" +
                        webView.evalJs("document.body.innerText").take(1500),
                    e,
                )
            }

            // evaluateJavascript возвращает JSON-строку, внутри которой наш JSON.
            val page = JSONObject(JSONTokener(webView.evalJs(SNAPSHOT_JS)).nextValue() as String)

            assertTrue(
                "Заголовок страницы не тот: ${page.optString("title")}",
                page.optString("title").contains("Ипотека"),
            )
            assertTrue(
                "H1 не отрисовался: '${page.optString("heading")}'",
                page.optString("heading").contains("Ипотека без"),
            )
            assertTrue(
                "Мобильное меню (гамбургер) не видно — вёрстка не мобильная",
                page.optBoolean("menuVisible"),
            )
            assertTrue(
                "Ширина окна не мобильная: ${page.optInt("width")}px",
                page.optInt("width") in 320..600,
            )
            assertTrue(
                "Горизонтальный скролл: контент ${page.optInt("scrollWidth")}px при окне ${page.optInt("width")}px",
                page.optInt("scrollWidth") <= page.optInt("width") + 1,
            )
            assertEquals(
                "Ошибка API на странице",
                "",
                page.optString("apiError"),
            )
        }
    }

    private fun waitFor(description: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        throw AssertionError("Не дождались за ${timeoutMs}ms: $description")
    }

    /** Выполняет JS в WebView и возвращает результат как строку ("true"/"false"/JSON). */
    private fun WebView.evalJs(script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        post {
            evaluateJavascript(script) { value ->
                result.set(value)
                latch.countDown()
            }
        }
        check(latch.await(20, TimeUnit.SECONDS)) { "JS не ответил за 20s: $script" }
        return result.get().orEmpty()
    }

    private companion object {
        const val SNAPSHOT_JS = """
            (() => {
              const h1 = document.querySelector('h1');
              const menu = document.querySelector('button[aria-label="Меню"]');
              const errorNode = document.querySelector('[role="alert"]');
              return JSON.stringify({
                title: document.title,
                heading: h1 ? h1.innerText.replace(/\s+/g, ' ').trim() : '',
                width: window.innerWidth,
                scrollWidth: document.documentElement.scrollWidth,
                menuVisible: !!menu &&
                  menu.offsetParent !== null &&
                  getComputedStyle(menu).display !== 'none' &&
                  menu.getBoundingClientRect().width > 0,
                apiError: errorNode ? errorNode.innerText.trim() : ''
              });
            })()
        """
    }
}
