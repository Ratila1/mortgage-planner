package dev.nosurprise.autotests.config

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties

/**
 * Конфигурация прогона: берём из -D, затем из переменных окружения, затем из значения по умолчанию.
 * Секреты (JWT_SECRET) никогда не печатаются и не попадают в отчёт — только признак «задан/не задан».
 */
object TestConfig {

    val smsBaseUrl: String = setting("sms.baseUrl", "SMS_BASE_URL", "http://localhost:8091")
    val frontendBaseUrl: String = setting("frontend.baseUrl", "FRONTEND_BASE_URL", "http://localhost:8080")
    val backendBaseUrl: String = setting("backend.baseUrl", "BACKEND_BASE_URL", "http://localhost:8080")
    val wireMockPort: Int = setting("wiremock.port", "WIREMOCK_PORT", "18080").toInt()

    /**
     * Токен бота стенда: часть пути Telegram Bot API (/bot<token>/sendMessage).
     * Значение по умолчанию совпадает с overlay docker-compose.autotests.yml; в отчёт не пишется.
     */
    val telegramBotToken: String =
        setting("telegram.botToken", "AUTOTESTS_TELEGRAM_TOKEN", "123456:AAautotestsTelegramToken")
    /** Ожидание элементов UI: SPA подгружает данные после рендера, дефолтных 4 с Selenide мало. */
    val uiTimeout: Duration = Duration.ofSeconds(setting("ui.timeout", "UI_TIMEOUT", "20").toLong())

    /** T5 (Appium) включается флагом `-Dappium=true`: без сервера Appium тест скипается. */
    val appiumUrl: String = setting("appium.url", "APPIUM_URL", "http://localhost:4723")
    val appiumDeviceName: String = setting("appium.deviceName", "APPIUM_DEVICE_NAME", "emulator-5554")
    val appiumBrowser: String = setting("appium.browser", "APPIUM_BROWSER", "Chrome")

    /**
     * Явный путь к chromedriver.exe. Нужен, когда автоскачивание недоступно или Appium не подбирает
     * драйвер под версию Chrome из эмулятора. Пусто — полагаемся на автоскачивание Appium.
     */
    val appiumChromedriver: String = setting("appium.chromedriver", "APPIUM_CHROMEDRIVER", "")

    /** Адрес стенда глазами эмулятора: 10.0.2.2 — loopback хост-машины. */
    val mobileBaseUrl: String = setting("mobile.baseUrl", "MOBILE_BASE_URL", "http://10.0.2.2:8080")

    val redisHost: String = setting("redis.host", "REDIS_HOST", "localhost")
    val redisPort: Int = setting("redis.port", "REDIS_PORT", "6379").toInt()
    val postgresPort: Int = setting("postgres.port", "POSTGRES_PORT", "5432").toInt()
    val postgresDatabase: String = setting("postgres.database", "POSTGRES_DB", "nosurprise")
    val postgresSchema: String = setting("postgres.schema", "DB_SCHEMA", "sms")

    /** Учётка БД: из env, иначе из .env (compose подставляет те же значения в контейнер). */
    val postgresUser: String by lazy {
        setting("postgres.user", "POSTGRES_USER", "").ifBlank { dotEnv("POSTGRES_USER") }.ifBlank { "nosurprise" }
    }
    val postgresPassword: String by lazy {
        setting("postgres.password", "POSTGRES_PASSWORD", "").ifBlank { dotEnv("POSTGRES_PASSWORD") }.ifBlank { "nosurprise" }
    }

    /** Секрет HS256, общий с backend. Читается из env или из .env в корне репозитория. */
    val jwtSecret: String by lazy {
        setting("jwt.secret", "JWT_SECRET", "").ifBlank { dotEnv("JWT_SECRET") }
    }

    val serviceSubject: String = "sms-client"

    fun properties(): Properties = Properties().apply {
        setProperty("sms.baseUrl", smsBaseUrl)
        setProperty("frontend.baseUrl", frontendBaseUrl)
        setProperty("backend.baseUrl", backendBaseUrl)
        setProperty("wiremock.port", wireMockPort.toString())
        setProperty("ui.timeout.seconds", uiTimeout.seconds.toString())
        setProperty("mobile.baseUrl", mobileBaseUrl)
        setProperty("redis.host", redisHost)
        setProperty("redis.port", redisPort.toString())
        setProperty("postgres.port", postgresPort.toString())
        setProperty("postgres.database", postgresDatabase)
        setProperty("postgres.schema", postgresSchema)
        setProperty("jwt.secret.defined", jwtSecret.isNotBlank().toString())
    }

    private fun setting(systemProperty: String, envName: String, default: String): String =
        System.getProperty(systemProperty)
            ?: System.getenv(envName)
            ?: default

    /** Читает ключ из ../.env (запуск из autotests/). Значение в лог не выводится. */
    private fun dotEnv(key: String): String {
        val file: Path = Path.of("..", ".env").normalize()
        if (!Files.isRegularFile(file)) return ""
        return Files.readAllLines(file)
            .firstOrNull { line -> line.substringBefore('=').trim() == key }
            ?.substringAfter('=', "")
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            .orEmpty()
    }
}
