package dev.nosurprise.auth.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Одноразовый токен из письма (подтверждение почты или вход по ссылке). */
public record LinkConsumeRequest(@NotBlank String token) {
}
