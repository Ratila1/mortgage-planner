package dev.nosurprise.kotlin.e2e.dto

data class CalcResponseDto(
    val calcId: String,
    val loanAmount: String,
    val monthlyPayment: String,
    val overpayment: String,
    val totalPaid: String,
    val termMonths: Int,
    val savings: Any?,
    val schedule: List<ScheduleRowDto>
)