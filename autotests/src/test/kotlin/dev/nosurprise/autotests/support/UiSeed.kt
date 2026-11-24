package dev.nosurprise.autotests.support

import com.google.gson.JsonObject
import dev.nosurprise.autotests.support.AllureSteps.step
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import retrofit2.Response
import java.sql.Timestamp
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * Подготовка данных для T4. Всё делается в обход UI: тест должен проверять саму панель
 * подтверждения телефона, а не форму регистрации и кабинет админа.
 *
 * <p>Почему не готовый админ из окружения: сидинг создаёт своего пользователя с ролью ADMIN,
 * чтобы тест не зависел от `APP_ADMIN_PASSWORD` в `.env`. Пароль своего пользователя тест знает,
 * поэтому оба логина (заявитель и админ) — обычные `POST /api/auth/login`.
 *
 * <p>Почта подтверждается UPDATE'ом в БД: письмо и Mailpit — не предмет этого теста,
 * а без `email_verified` backend не пускает логиниться.
 */
object UiSeed {

    /** Всё, что нужно UI-тесту: логин, телефон и заявка, ожидающая подписи. */
    data class ApprovedApplication(
        val email: String,
        val password: String,
        val phone: String,
        val applicationId: UUID,
    )

    /** «Последний шанс» из V14__seed_loan_types.sql: 100–1000 BYN, 7–14 дней. */
    private const val LOAN_TYPE_ID = "a2222222-2222-2222-2222-222222222222"
    private const val PASSWORD = "Autotest-12345"
    private const val READY_TIMEOUT_SECONDS = 60L

    /** 1×1 PNG: KYC-валидатор смотрит на сигнатуру файла, содержимое ему не важно. */
    private val PASSPORT_PNG: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    fun approvedApplication(): ApprovedApplication {
        val backend = ApiClientFactory.backend()
        val suffix = UUID.randomUUID().toString().take(8)
        val applicantEmail = "ui-applicant-$suffix@autotests.local"
        val adminEmail = "ui-admin-$suffix@autotests.local"
        val phone = Phones.unique()
        val profile = profile(phone)

        step("Сидинг: регистрация заявителя и админа") {
            listOf(applicantEmail, adminEmail).forEach { email ->
                val response = backend.register(json("email" to email, "password" to PASSWORD)).execute()
                assertThat(response.code()).describedAs("POST /api/auth/register ($email)").isEqualTo(202)
            }
        }

        step("Сидинг: подтверждение почты и роль ADMIN в БД") {
            val verified = SqlSupport.update(
                "update users set email_verified = true where email in (?, ?)",
                listOf(applicantEmail, adminEmail),
                schema = SqlSupport.BACKEND_SCHEMA,
            )
            assertThat(verified).describedAs("подтверждено пользователей").isEqualTo(2)
            SqlSupport.update(
                "insert into user_roles (user_id, role) select id, 'ADMIN' from users where email = ?",
                listOf(adminEmail),
                schema = SqlSupport.BACKEND_SCHEMA,
            )
        }

        val applicantToken = step("Сидинг: логин заявителя") { login(backend, applicantEmail) }
        val adminToken = step("Сидинг: логин админа") { login(backend, adminEmail) }

        step("Сидинг: профиль с телефоном $phone") {
            val response = backend.saveProfile(bearer(applicantToken), profile).execute()
            assertThat(response.code()).describedAs("PUT /api/me/profile").isEqualTo(200)
        }

        step("Сидинг: фото паспорта (без него заявку не примут)") {
            val part = MultipartBody.Part.createFormData(
                "file",
                "passport.png",
                PASSPORT_PNG.toRequestBody("image/png".toMediaType()),
            )
            val response = backend.uploadPassport(bearer(applicantToken), part).execute()
            assertThat(response.code()).describedAs("POST /api/me/kyc/passport").isEqualTo(201)
        }

        val applicationId = step("Сидинг: заявка на микрозайм") {
            val response = backend.submitApplication(
                bearer(applicantToken),
                json("loanTypeId" to LOAN_TYPE_ID, "amount" to 500, "termDays" to 10, "profile" to profile),
            ).execute()
            assertThat(response.code()).describedAs("POST /api/loans/applications").isEqualTo(201)
            UUID.fromString(bodyOf(response).get("id").asString)
        }

        step("Сидинг: одобрение админом") {
            val card = bodyOf(backend.adminApplication(bearer(adminToken), applicationId.toString()).execute())
            val version = card.get("version").asLong
            val approved = backend.approve(
                bearer(adminToken),
                applicationId.toString(),
                json("version" to version),
            ).execute()
            assertThat(approved.code()).describedAs("POST /api/admin/loan-applications/{id}/approve").isEqualTo(200)
            assertThat(bodyOf(approved).get("status").asString).isEqualTo("APPROVED")
        }

        step("Сидинг: ждём договор CONTRACT и уведомление LOAN_APPROVED") {
            awaitReady(backend, applicantToken, applicationId)
        }

        return ApprovedApplication(applicantEmail, PASSWORD, phone, applicationId)
    }

    /**
     * Бэкенд отправляет SMS через sms-verification-service, доставку делает фоновый dispatch:
     * код в Redis появляется сразу, а `verify` до доставки вернёт NOT_READY, поэтому ждём статус.
     */
    fun awaitDeliveredSms(phone: String): UUID {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted {
            assertThat(latestSmsStatus(phone)).describedAs("статус SMS на $phone").isEqualTo("DELIVERED")
        }
        return SqlSupport.query(
            "select id from sms_request where phone = ? order by created_at desc limit 1",
            listOf(phone),
        ) { rs -> UUID.fromString(rs.getString(1)) }.first()
    }

    /** `customer_profile.phone_verified_at` — доказательство, что подтверждение дошло до БД. */
    fun phoneVerifiedAt(phone: String): Timestamp? = SqlSupport.query(
        "select phone_verified_at from customer_profile where phone = ?",
        listOf(phone),
        schema = SqlSupport.BACKEND_SCHEMA,
    ) { rs -> rs.getTimestamp(1) }.firstOrNull()

    private fun latestSmsStatus(phone: String): String? = SqlSupport.query(
        "select status from sms_request where phone = ? order by created_at desc limit 1",
        listOf(phone),
    ) { rs -> rs.getString(1) }.firstOrNull()

    private fun login(backend: BackendApi, email: String): String {
        val response = backend.login(json("email" to email, "password" to PASSWORD)).execute()
        assertThat(response.code()).describedAs("POST /api/auth/login ($email)").isEqualTo(200)
        return bodyOf(response).get("accessToken").asString
    }

    /** Договор рисует отдельный consumer после approve, уведомление — ещё один: ждём оба. */
    private fun awaitReady(backend: BackendApi, token: String, applicationId: UUID) {
        await().atMost(Duration.ofSeconds(READY_TIMEOUT_SECONDS)).pollInterval(Duration.ofSeconds(1)).untilAsserted {
            val card = bodyOf(backend.application(bearer(token), applicationId.toString()).execute())
            val documents = card.getAsJsonArray("documents").map { it.asJsonObject.get("type").asString }
            assertThat(documents).describedAs("документы заявки $applicationId").contains("CONTRACT")

            val notifications = backend.notifications(bearer(token)).execute().body()
                ?: throw AssertionError("GET /api/notifications вернул пустое тело")
            assertThat(notifications.map { it.asJsonObject.get("type").asString })
                .describedAs("уведомления пользователя")
                .contains("LOAN_APPROVED")
        }
    }

    /** Отчество не отправляем: backend принимает его отсутствие, а null-ы Gson выкидывает сам. */
    private fun profile(phone: String): JsonObject = json(
        "lastName" to "Тестов",
        "firstName" to "Авто",
        "phone" to phone,
        "monthlyIncome" to 2500.5,
    )

    /** Плоский JSON без null-ов: Retrofit не принимает Map с wildcard, а DTO чужого контракта не нужны. */
    private fun json(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> addProperty(key, value)
                is Number -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                is JsonObject -> add(key, value)
                else -> throw IllegalArgumentException("Неподдерживаемое значение для '$key': ${value::class}")
            }
        }
    }

    private fun bearer(token: String) = "Bearer $token"

    private fun bodyOf(response: Response<JsonObject>): JsonObject = Responses.bodyOf(response)
}
