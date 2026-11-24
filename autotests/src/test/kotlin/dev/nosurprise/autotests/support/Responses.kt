package dev.nosurprise.autotests.support

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import retrofit2.Response

/**
 * Разбор ответов Retrofit, общий для всех тестов: у `Call<JsonObject>` тело есть только на 2xx,
 * а проверяемые коды (400/409/422/429) приходят в `errorBody`.
 */
object Responses {

    fun bodyOf(response: Response<JsonObject>): JsonObject = response.body()
        ?: throw AssertionError(
            "Пустое тело ответа: HTTP ${response.code()} ${response.errorBody()?.string().orEmpty()}"
        )

    fun errorOf(response: Response<JsonObject>): JsonObject {
        val raw = response.errorBody()?.string().orEmpty()
        if (raw.isBlank()) throw AssertionError("Пустое тело ошибки: HTTP ${response.code()}")
        return JsonParser.parseString(raw).asJsonObject
    }
}
