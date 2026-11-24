package dev.nosurprise.kotlin.e2e.dto

data class CreateScenarioRequestDto(
    val name: String,
    val params: Map<String, Any>,
    val offerId: String?
)