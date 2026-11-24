package dev.nosurprise.autotests.support

import dev.nosurprise.autotests.config.TestConfig
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Allure environment.properties: фиксируем стенд в отчёте, чтобы прогон был воспроизводим.
 * Пишется один раз на JVM, значения секретов сюда не попадают.
 */
object AllureEnvironment {

    private const val FILE_NAME = "environment.properties"
    private var written = false

    @Synchronized
    fun writeOnce() {
        if (written) return
        written = true

        val resultsDir = Path.of(
            System.getProperty("allure.results.directory")
                ?: System.getProperty("allure.results.dir")
                ?: "build/allure-results"
        )
        Files.createDirectories(resultsDir)

        val content = TestConfig.properties().entries
            .sortedBy { it.key.toString() }
            .joinToString(separator = System.lineSeparator()) { "${it.key}=${it.value}" }

        Files.writeString(
            resultsDir.resolve(FILE_NAME),
            content + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }
}
