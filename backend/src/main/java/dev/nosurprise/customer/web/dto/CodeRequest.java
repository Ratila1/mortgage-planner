package dev.nosurprise.customer.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Одноразовый 6-значный код из SMS. */
public record CodeRequest(@NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
}
