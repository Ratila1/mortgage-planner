package dev.nosurprise.autotests.api

import com.google.gson.JsonObject
import retrofit2.Call
import retrofit2.http.GET

/** Actuator sms-verification-service: smoke-проверка живости и readiness. */
interface ActuatorApi {

    @GET("/actuator/health")
    fun health(): Call<JsonObject>

    @GET("/actuator/health/readiness")
    fun readiness(): Call<JsonObject>
}
