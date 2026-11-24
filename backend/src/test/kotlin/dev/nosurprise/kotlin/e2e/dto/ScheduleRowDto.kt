package dev.nosurprise.kotlin.e2e.dto

data class ScheduleRowDto(
    val month: Int,
    val payment: String,
    val principal: String,
    val interest: String,
    val earlyRepayment: String,
    val balance: String
)