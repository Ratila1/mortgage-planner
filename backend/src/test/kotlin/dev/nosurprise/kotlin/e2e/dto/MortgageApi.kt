package dev.nosurprise.kotlin.e2e.dto;

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

public interface MortgageApi {
    @POST("api/calc")
    fun calculate(@Body request: CalcRequestDto): Call<CalcResponseDto>

    @GET("api/calc/{calcId}")
    fun getCalculation(@Path("calcId") calcId: String): Call<CalcResponseDto>
}