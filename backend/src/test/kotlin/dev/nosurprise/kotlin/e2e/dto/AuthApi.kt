package dev.nosurprise.kotlin.e2e.dto;

import retrofit2.Call
import retrofit2.http.Body;
import retrofit2.http.DELETE
import retrofit2.http.GET;
import retrofit2.http.Header
import retrofit2.http.POST;
import retrofit2.http.Path

public interface AuthApi {
    @POST("api/auth/login")
    fun login(@Body request: LoginRequestDto): Call<LoginResponseDto>

    @GET("api/me")
    fun me(@Header("Authorization") authorization: String?): Call<MeDto>

    @POST("api/scenarios")
    fun createScenario(
        @Header("Authorization") authorization: String,
        @Body request: CreateScenarioRequestDto
    ): Call<ScenarioResponseDto>

    @DELETE("api/scenarios/{id}")
    fun deleteScenario(
        @Header("Authorization") authorization: String,
        @Path("id") id: String
    ): Call<Void>
}