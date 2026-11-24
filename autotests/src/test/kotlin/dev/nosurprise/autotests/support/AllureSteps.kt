package dev.nosurprise.autotests.support

import io.qameta.allure.Allure
import io.qameta.allure.Allure.ThrowableRunnable

/**
 * Обёртка над Allure.step: у Kotlin лямбда неоднозначно выводится между перегрузками
 * `ThrowableRunnable<T>` и `ThrowableRunnableVoid`, поэтому тип указываем явно.
 */
object AllureSteps {

    fun <T> step(name: String, block: () -> T): T = Allure.step(name, ThrowableRunnable<T> { block() })
}
