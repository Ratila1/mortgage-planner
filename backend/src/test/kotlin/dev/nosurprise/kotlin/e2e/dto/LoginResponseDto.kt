package dev.nosurprise.kotlin.e2e.dto

data class LoginResponseDto(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long
)