package dev.nosurprise.kotlin.e2e

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import dev.nosurprise.kotlin.e2e.dto.CalcRequestDto
import dev.nosurprise.kotlin.e2e.dto.MortgageApi
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@Epic("Mortgage planner")
@Feature("WireMock")
@Tag("wiremock")
class MortgageClientWireMockTest {

    companion object {
        @JvmField
        @RegisterExtension
        val wm: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    private val api: MortgageApi by lazy {
        Retrofit.Builder()
            .baseUrl(wm.baseUrl() + "/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MortgageApi::class.java)
    }

    @Test
    fun clientSendsCalcRequestAndParsesStubbedResponse() {
        wm.stubFor(
            post(urlEqualTo("/api/calc")).willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"calcId":"11111111-1111-1111-1111-111111111111","loanAmount":"1000000.00",
                        "monthlyPayment":"88848.79","overpayment":"66185.48","totalPaid":"1066185.48",
                        "termMonths":12,"savings":null,"schedule":[{"month":1,"payment":"88848.79",
                        "principal":"78848.79","interest":"10000.00","earlyRepayment":"0.00","balance":"921151.21"}]}
                        """.trimIndent()
                    )
            )
        )

        val response = api.calculate(
            CalcRequestDto("1000000", "0", 12, "12", "ANNUITY", emptyList())
        ).execute()

        // что клиент разобрал в ответе
        assertTrue(response.isSuccessful, "code=${response.code()}")
        val body = response.body()!!
        assertEquals("88848.79", body.monthlyPayment)
        assertEquals(12, body.termMonths)
        assertEquals(1, body.schedule.size)

        // что клиент отправил на сервер
        wm.verify(
            1,
            postRequestedFor(urlEqualTo("/api/calc"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("\$.price", equalTo("1000000")))
                .withRequestBody(matchingJsonPath("\$.type", equalTo("ANNUITY")))
        )
    }
}