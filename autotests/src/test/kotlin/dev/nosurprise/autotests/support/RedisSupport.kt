package dev.nosurprise.autotests.support

import dev.nosurprise.autotests.config.TestConfig
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import java.util.UUID

/**
 * OTP в БД не хранится: `sms_request.message` — маска, открытый код лежит только в Redis
 * (`sms:otp:code:{smsId}`, TTL = sms.otp-ttl). Тестам он нужен, чтобы пройти верификацию
 * так же, как это делает пользователь, получивший SMS.
 */
object RedisSupport {

    private const val CODE_KEY = "sms:otp:code:%s"
    private const val USED_KEY = "sms:otp:used:%s"

    private val password: String = System.getenv("REDIS_PASSWORD").orEmpty()

    /**
     * Дефолтные 2 с на чтение мало: когда рядом крутится Android-эмулятор, ответа на handshake
     * не хватает и прогон падает на `SocketTimeoutException`.
     */
    private val pool: JedisPool by lazy {
        val config = DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(5_000)
            .socketTimeoutMillis(10_000)
            .build()
        JedisPool(HostAndPort(TestConfig.redisHost, TestConfig.redisPort), config)
    }

    fun code(smsId: UUID): String = withJedis { jedis ->
        jedis.get(CODE_KEY.format(smsId))
            ?: throw AssertionError("В Redis нет кода для SMS $smsId: handoff истёк или dispatch не дошёл")
    }

    fun isUsed(smsId: UUID): Boolean = withJedis { jedis -> jedis.exists(USED_KEY.format(smsId)) }

    /** Код, которого заведомо нет: другая комбинация тех же шести цифр. */
    fun differentCode(code: String): String = "%06d".format((code.toInt() + 1) % 1_000_000)

    private fun <T> withJedis(block: (Jedis) -> T): T = pool.resource.use { jedis ->
        if (password.isNotBlank()) jedis.auth(password)
        block(jedis)
    }
}
