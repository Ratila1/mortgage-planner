package dev.nosurprise.autotests.support

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MultipartBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Контракт mortgage-planner backend — только то, что нужно для сидинга UI-теста (T4):
 * пользователь, профиль с телефоном, KYC, заявка и админское одобрение.
 *
 * <p>Тела — обычные Map: Gson сериализует их как JSON, а тесту не нужны типизированные DTO
 * чужого контракта (сравните с SmsApi: там DTO есть, потому что проверяется контракт SMS-сервиса).
 */
interface BackendApi {

    @POST("/api/auth/register")
    fun register(@Body body: JsonObject): Call<Void>

    @POST("/api/auth/login")
    fun login(@Body body: JsonObject): Call<JsonObject>

    @PUT("/api/me/profile")
    fun saveProfile(
        @Header("Authorization") authorization: String,
        @Body body: JsonObject,
    ): Call<JsonObject>

    @Multipart
    @POST("/api/me/kyc/passport")
    fun uploadPassport(
        @Header("Authorization") authorization: String,
        @Part file: MultipartBody.Part,
    ): Call<JsonObject>

    @POST("/api/loans/applications")
    fun submitApplication(
        @Header("Authorization") authorization: String,
        @Body body: JsonObject,
    ): Call<JsonObject>

    @GET("/api/loans/applications/{id}")
    fun application(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): Call<JsonObject>

    @GET("/api/notifications")
    fun notifications(@Header("Authorization") authorization: String): Call<JsonArray>

    @GET("/api/admin/loan-applications/{id}")
    fun adminApplication(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): Call<JsonObject>

    @POST("/api/admin/loan-applications/{id}/approve")
    fun approve(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body body: JsonObject,
    ): Call<JsonObject>
}
