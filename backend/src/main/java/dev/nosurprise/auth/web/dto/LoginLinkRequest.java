package dev.nosurprise.auth.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginLinkRequest(@NotBlank @Email String email) {
}
