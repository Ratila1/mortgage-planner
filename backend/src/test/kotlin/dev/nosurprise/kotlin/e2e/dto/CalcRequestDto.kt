package dev.nosurprise.kotlin.e2e.dto

data class CalcRequestDto(
    val price: String,
    val downPayment: String,
    val termMonths: Int,
    val annualRate: String,
    val type: String,
    val earlyRepayments: List<Any>
)