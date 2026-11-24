package dev.nosurprise.autotests.support

import io.qameta.allure.Allure
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Кладёт в Allure пару «запрос → ответ». Заголовки авторизации маскируются:
 * отчёт уходит команде, токены в нём не нужны.
 */
class AllureHttpInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        attachRequest(request)
        val response = chain.proceed(request)
        attachResponse(response)
        return response
    }

    private fun attachRequest(request: Request) {
        val text = buildString {
            appendLine("${request.method} ${request.url}")
            maskedHeaders(request).forEach { (name, value) -> appendLine("$name: $value") }
            request.body?.let { body ->
                appendLine()
                appendLine(bodyText(body))
            }
        }
        attach("HTTP request", text)
    }

    private fun attachResponse(response: Response) {
        val body = response.peekBody(MAX_BODY_BYTES.toLong()).string()
        val text = buildString {
            appendLine("HTTP ${response.code} ${response.message}")
            response.headers.forEach { (name, value) -> appendLine("$name: $value") }
            appendLine()
            appendLine(body.ifBlank { "<empty>" })
        }
        attach("HTTP response", text)
    }

    private fun attach(name: String, text: String) {
        Allure.addAttachment(
            name,
            "text/plain",
            ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)),
            ".txt",
        )
    }

    private fun maskedHeaders(request: Request): Map<String, String> =
        request.headers.names().associateWith { name ->
            if (name.lowercase() in SECRET_HEADERS) "***" else request.header(name).orEmpty()
        }

    private fun bodyText(body: okhttp3.RequestBody): String = try {
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        buffer.readUtf8().take(MAX_BODY_BYTES)
    } catch (e: RuntimeException) {
        "<body not readable: ${e.javaClass.simpleName}>"
    }

    private companion object {
        private const val MAX_BODY_BYTES = 64 * 1024
        private val SECRET_HEADERS = setOf("authorization", "cookie", "set-cookie", "idempotency-key")
    }
}
