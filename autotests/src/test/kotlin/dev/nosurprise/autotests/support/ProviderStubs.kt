package dev.nosurprise.autotests.support

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import dev.nosurprise.autotests.config.TestConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Стабы внешних провайдеров на WireMock.
 *
 * <p>Стенд смотрит на WireMock через `host.docker.internal` (`docker-compose.autotests.yml`):
 * Twilio — доставка SMS, Telegram Bot API — отправка кода в чат.
 *
 * <p>Стабы SMS-провайдера скоупятся по номеру телефона (`withRequestBody(containing("To=..."))`):
 * в очереди могут лежать записи других тестов, и общий сценарий «первый вызов — ошибка» они бы украли.
 * У Telegram скоуп — `chat_id` в теле запроса.
 */
object ProviderStubs {

    /** Twilio Messages API: /2010-04-01/Accounts/{accountSid}/Messages.json */
    private const val TWILIO_PATH = "/2010-04-01/Accounts/[^/]+/Messages\\.json"

    /** Telegram Bot API: токен бота — часть пути (/bot<token>/sendMessage), как у настоящего API. */
    private val TELEGRAM_SEND_MESSAGE = "/bot" + TestConfig.telegramBotToken + "/sendMessage"

    // --- SMS-провайдер ---

    /** Первый вызов для номера падает 500, второй проходит; остальные номера — 500. */
    fun stubTwilioRetryOnce(phone: String) {
        val scenario = "twilio-$phone"
        WireMockSupport.server.stubFor(
            post(urlPathMatching(TWILIO_PATH))
                .withRequestBody(containing(encodedTo(phone)))
                .atPriority(1)
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("ok")
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":21610,"message":"unsubscribed recipient"}""")
                )
        )
        WireMockSupport.server.stubFor(
            post(urlPathMatching(TWILIO_PATH))
                .withRequestBody(containing(encodedTo(phone)))
                .atPriority(1)
                .inScenario(scenario)
                .whenScenarioStateIs("ok")
                .willReturn(twilioAccepted())
        )
        stubTwilioAlwaysFail()
    }

    /** Доставка для конкретного номера проходит; остальные номера — 500. */
    fun stubTwilioSuccess(phone: String) {
        WireMockSupport.server.stubFor(
            post(urlPathMatching(TWILIO_PATH))
                .withRequestBody(containing(encodedTo(phone)))
                .atPriority(1)
                .willReturn(twilioAccepted())
        )
        stubTwilioAlwaysFail()
    }

    /** Низкий приоритет: всё, что не попало в сценарий конкретного номера, провайдер отклоняет. */
    fun stubTwilioAlwaysFail() {
        WireMockSupport.server.stubFor(
            post(urlPathMatching(TWILIO_PATH))
                .atPriority(10)
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"code":20429,"message":"too many requests"}""")
                )
        )
    }

    fun twilioCalls(phone: String): List<LoggedRequest> = WireMockSupport.server.findAll(
        postRequestedFor(urlPathMatching(TWILIO_PATH))
            .withRequestBody(containing(encodedTo(phone)))
    )

    private fun twilioAccepted(): ResponseDefinitionBuilder = aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withBody("""{"sid":"SMautotests0001","status":"queued"}""")

    private fun encodedTo(phone: String) = "To=" + URLEncoder.encode(phone, UTF_8)

    // --- Telegram Bot API ---

    /** Первый sendMessage для чата падает 500, второй проходит; остальные чаты — 500. */
    fun stubTelegramRetryOnce(chatId: Long) {
        val scenario = "telegram-$chatId"
        WireMockSupport.server.stubFor(
            post(urlPathEqualTo(TELEGRAM_SEND_MESSAGE))
                .withRequestBody(containing(chatIdField(chatId)))
                .atPriority(1)
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("ok")
                .willReturn(telegramFailure())
        )
        WireMockSupport.server.stubFor(
            post(urlPathEqualTo(TELEGRAM_SEND_MESSAGE))
                .withRequestBody(containing(chatIdField(chatId)))
                .atPriority(1)
                .inScenario(scenario)
                .whenScenarioStateIs("ok")
                .willReturn(telegramOk())
        )
        stubTelegramFallbackFailure()
    }

    /** Каждый sendMessage для чата падает 500: проверяется исчерпание ретраев до DEAD. */
    fun stubTelegramAlwaysFails(chatId: Long) {
        WireMockSupport.server.stubFor(
            post(urlPathEqualTo(TELEGRAM_SEND_MESSAGE))
                .withRequestBody(containing(chatIdField(chatId)))
                .atPriority(1)
                .willReturn(telegramFailure())
        )
        stubTelegramFallbackFailure()
    }

    /** Низкий приоритет: незастабленный чат получает ошибку, а не 404 от WireMock. */
    private fun stubTelegramFallbackFailure() {
        WireMockSupport.server.stubFor(
            post(urlPathEqualTo(TELEGRAM_SEND_MESSAGE))
                .atPriority(10)
                .willReturn(telegramFailure())
        )
    }

    fun telegramCalls(chatId: Long): List<LoggedRequest> = telegramRequests()
        .filter { request -> request.bodyAsString.contains(chatIdField(chatId)) }

    /** Все вызовы Bot API без скоупа: для диагностики в сообщениях ассертов. */
    fun telegramRequests(): List<LoggedRequest> = WireMockSupport.server.findAll(
        postRequestedFor(urlPathEqualTo(TELEGRAM_SEND_MESSAGE))
    )

    /** Bot API сериализует chat_id числом: {"chat_id":123,"text":"..."}. */
    private fun chatIdField(chatId: Long) = "\"chat_id\":" + chatId

    private fun telegramOk(): ResponseDefinitionBuilder = aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("""{"ok":true,"result":{"message_id":1,"date":0,"text":"ok"}}""")

    private fun telegramFailure(): ResponseDefinitionBuilder = aResponse()
        .withStatus(500)
        .withHeader("Content-Type", "application/json")
        .withBody("""{"ok":false,"error_code":500,"description":"Internal Server Error"}""")
}
