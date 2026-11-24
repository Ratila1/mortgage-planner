package dev.nosurprise.autotests.api

import com.google.gson.JsonObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.UUID

/**
 * Контракт sms-verification-service (service-to-service, JWT sub=sms-client).
 * Возвращаем Call<JsonObject>: тесты вызывают execute() и проверяют HTTP-код,
 * включая 401/409/422 (на них Retrofit бросил бы исключение при прямом возврате тела).
 * Типизированные DTO появятся вместе с T1.
 */
interface SmsApi {

    @POST("/api/v1/sms")
    fun create(
        // null = заголовок не отправляется: так проверяется 400 на отсутствующий Idempotency-Key.
        @Header("Idempotency-Key") idempotencyKey: String?,
        @Header("Authorization") authorization: String?,
        @Body body: CreateSmsRequestDto,
    ): Call<JsonObject>

    @GET("/api/v1/sms/{id}")
    fun get(
        @Path("id") id: UUID,
        @Header("Authorization") authorization: String?,
    ): Call<JsonObject>

    @POST("/api/v1/sms/{id}/verify")
    fun verify(
        @Path("id") id: UUID,
        @Header("Authorization") authorization: String?,
        @Body body: VerifyRequestDto,
    ): Call<JsonObject>
}

data class CreateSmsRequestDto(val phone: String)

data class VerifyRequestDto(val code: String)
