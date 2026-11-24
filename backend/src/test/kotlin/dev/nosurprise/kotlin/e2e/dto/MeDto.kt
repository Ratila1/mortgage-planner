package dev.nosurprise.kotlin.e2e.dto

data class MeDto(
    val id: String,
    val email: String,
    val emailVerified: Boolean,
    val roles: List<String>
)