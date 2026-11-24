package dev.nosurprise.kotlin.e2e

import dev.nosurprise.calc.repository.CalculationDocument
import dev.nosurprise.integration.support.AbstractIntegrationTest
import dev.nosurprise.kotlin.e2e.dto.AuthApi
import dev.nosurprise.kotlin.e2e.dto.CalcRequestDto
import dev.nosurprise.kotlin.e2e.dto.CreateScenarioRequestDto
import dev.nosurprise.kotlin.e2e.dto.LoginRequestDto
import dev.nosurprise.kotlin.e2e.dto.MortgageApi
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal
import java.util.*

@Epic("Mortgage planner")
@Feature("Kotlin auto-tests")
@Tag("integration")
class MortgagePlannerKotlinIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    private val mortgageApi by lazy { retrofit("http://localhost:$port/").create(MortgageApi::class.java) }
    private val authApi by lazy { retrofit("http://localhost:$port/").create(AuthApi::class.java) }

    @Test
    fun calculationApiReturnsExpectedPayment() {
        val body = mortgageApi.calculate(annuityRequest()).execute().bodyOrFail("POST /api/calc")

        assertEquals("1000000.00", body.loanAmount)
        assertEquals("88848.79", body.monthlyPayment)
        assertEquals(12, body.termMonths)
        assertEquals(12, body.schedule.size)
        assertEquals("0.00", body.schedule.last().balance)

        val loaded = mortgageApi.getCalculation(body.calcId).execute().bodyOrFail("GET /api/calc/{id}")
        assertEquals("88848.79", loaded.monthlyPayment)
    }

    @Test
    fun calculationApiRejectsInvalidInput() {
        val response = mortgageApi.calculate(annuityRequest().copy(termMonths = 0)).execute()

        assertEquals(400, response.code())
    }

    @Test
    fun authAndScenarioApiFlow() {
        val token = loginAsAdmin()

        val anonymous = authApi.me(null).execute()
        assertFalse(anonymous.isSuccessful)
        assertEquals(401, anonymous.code())

        val me = authApi.me(bearer(token)).execute().bodyOrFail("GET /api/me")
        assertEquals(ADMIN_EMAIL, me.email)
        assertTrue(me.emailVerified)
        assertTrue("ADMIN" in me.roles)

        val scenario = authApi.createScenario(bearer(token), scenarioRequest("kotlin-it"))
            .execute().bodyOrFail("POST /api/scenarios")

        val deleted = authApi.deleteScenario(bearer(token), scenario.id).execute()
        assertTrue(deleted.isSuccessful, "DELETE /api/scenarios/{id}, code=${deleted.code()}")
    }

    @Test
    fun scenarioAndCalculationPersistedInDatabases() {
        val token = loginAsAdmin()
        val scenario = authApi.createScenario(bearer(token), scenarioRequest("kotlin-db"))
            .execute().bodyOrFail("POST /api/scenarios")

        try {
            val dbCount = jdbc.queryForObject(
                "select count(*) from saved_scenarios where id = cast(? as uuid)",
                Int::class.javaObjectType,
                scenario.id
            )
            assertEquals(1, dbCount)

            val paramsJson = jdbc.queryForObject(
                "select params::text from saved_scenarios where id = cast(? as uuid)",
                String::class.java,
                scenario.id
            )
            assertNotNull(paramsJson)
            assertTrue(paramsJson!!.contains("\"price\""))

            val calcDoc = mongoTemplate.findById(scenario.calcId, CalculationDocument::class.java)
            assertNotNull(calcDoc)
            assertEquals(BigDecimal("88848.79"), calcDoc!!.summary().monthlyPayment())
        } finally {
            authApi.deleteScenario(bearer(token), scenario.id).execute()
        }
    }

    // ---- helpers ----

    private fun retrofit(baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    private fun bearer(token: String) = "Bearer $token"

    private fun loginAsAdmin(): String =
        authApi.login(LoginRequestDto(ADMIN_EMAIL, ADMIN_PASSWORD))
            .execute()
            .bodyOrFail("admin login")
            .accessToken

    private fun annuityRequest() = CalcRequestDto(
        price = "1000000",
        downPayment = "0",
        termMonths = 12,
        annualRate = "12",
        type = "ANNUITY",
        earlyRepayments = emptyList()
    )

    private fun scenarioRequest(prefix: String) = CreateScenarioRequestDto(
        name = "$prefix-${UUID.randomUUID()}",
        params = mapOf(
            "price" to "1000000",
            "downPayment" to "0",
            "termMonths" to 12,
            "annualRate" to "12",
            "type" to "ANNUITY",
            "earlyRepayments" to emptyList<Any>()
        ),
        offerId = null
    )

    private fun <T> Response<T>.bodyOrFail(what: String): T {
        assertTrue(isSuccessful, "$what failed: code=${code()}, error=${errorBody()?.string()}")
        return body() ?: error("$what returned an empty body")
    }
}