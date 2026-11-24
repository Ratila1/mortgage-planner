package dev.nosurprise.autotests.support

import com.google.gson.GsonBuilder
import dev.nosurprise.autotests.api.ActuatorApi
import dev.nosurprise.autotests.api.SmsApi
import dev.nosurprise.autotests.config.TestConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Duration

/** Фабрика API-клиентов тестов: один OkHttp на все Retrofit-интерфейсы. */
object ApiClientFactory {

    private val gson = GsonBuilder().setLenient().create()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .callTimeout(Duration.ofSeconds(60))
            .followRedirects(false)
            .addInterceptor(AllureHttpInterceptor())
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
            .build()
    }

    fun actuator(baseUrl: String = TestConfig.smsBaseUrl): ActuatorApi = retrofit(baseUrl).create(ActuatorApi::class.java)

    fun sms(baseUrl: String = TestConfig.smsBaseUrl): SmsApi = retrofit(baseUrl).create(SmsApi::class.java)

    /** Backend mortgage-planner: сидинг данных для UI-тестов. */
    fun backend(baseUrl: String = TestConfig.backendBaseUrl): BackendApi = retrofit(baseUrl).create(BackendApi::class.java)

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
}
