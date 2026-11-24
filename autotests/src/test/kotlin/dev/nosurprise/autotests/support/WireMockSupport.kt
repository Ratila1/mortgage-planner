package dev.nosurprise.autotests.support

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import dev.nosurprise.autotests.config.TestConfig

/**
 * WireMock живёт в JVM тестов, контейнер sms-verification-service ходит к нему через
 * `host.docker.internal` (overlay docker-compose.autotests.yml).
 */
object WireMockSupport {

    val server: WireMockServer by lazy {
        WireMockServer(
            options()
                .port(TestConfig.wireMockPort)
                // 0.0.0.0: запросы приходят не из localhost, а из docker-контейнера.
                .bindAddress("0.0.0.0")
        )
    }

    fun start() {
        if (!server.isRunning) server.start()
    }

    fun stop() {
        if (server.isRunning) server.stop()
    }

    fun reset() {
        server.resetAll()
    }

    fun baseUrl(): String = "http://localhost:${TestConfig.wireMockPort}"
}
