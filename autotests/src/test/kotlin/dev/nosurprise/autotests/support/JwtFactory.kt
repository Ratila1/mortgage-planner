package dev.nosurprise.autotests.support

import dev.nosurprise.autotests.config.TestConfig
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HS256-токены того же формата, что подписывает backend: общий JWT_SECRET, sub — сервис или пользователь.
 * Реализовано на JDK (Mac + Base64), чтобы не тащить JWT-библиотеку только ради подписи в тестах.
 */
object JwtFactory {

    /** Service-JWT: sub=sms-client. Только он допускается на всех эндпоинтах SMS API. */
    fun serviceToken(ttlSeconds: Long = 300): String = token(TestConfig.serviceSubject, ttlSeconds)

    /** Пользовательский JWT бэкенда: sub — id пользователя. На SMS API должен получать 401. */
    fun userToken(ttlSeconds: Long = 300): String = token(UUID.randomUUID().toString(), ttlSeconds)

    fun token(subject: String, ttlSeconds: Long = 300): String {
        require(TestConfig.jwtSecret.isNotBlank()) {
            "JWT_SECRET не задан: положите его в .env или передайте -Djwt.secret"
        }
        val now = Instant.now().epochSecond
        val header = encode("""{"alg":"HS256","typ":"JWT"}""")
        val payload = encode("""{"sub":"$subject","iat":$now,"exp":${now + ttlSeconds}}""")
        val signingInput = "$header.$payload"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(TestConfig.jwtSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val signature = encode(mac.doFinal(signingInput.toByteArray(StandardCharsets.UTF_8)))

        return "$signingInput.$signature"
    }

    private fun encode(value: String) = encode(value.toByteArray(StandardCharsets.UTF_8))

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
