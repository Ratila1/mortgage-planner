package dev.nosurprise.customer.web.dto;

import dev.nosurprise.customer.ProfileData;
import dev.nosurprise.customer.Sex;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Форма профиля. Зарплата строго больше нуля: на неё делится ПДН (§3.7). Телефон — в международном формате.
 * Дата рождения, пол, страна и город обязательны: без них не посчитать скоринг (CREDIT_POLICY.md §4).
 * Возраст здесь не ограничиваем — это стоп-фактор скоринга (вне 18–90 → автоотказ).
 */
public record ProfileRequest(
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Size(max = 100) String firstName,
        @Size(max = 100) String middleName,
        @NotBlank @Pattern(regexp = "^\\+\\d{10,15}$", message = "must be in format +375291234567") String phone,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal monthlyIncome,
        @NotNull @Past LocalDate birthDate,
        @NotNull Sex sex,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "ISO country code, e.g. BY") String country,
        @NotBlank @Size(max = 100) String city
) {

    public ProfileData toData() {
        String middle = middleName == null || middleName.isBlank() ? null : middleName.trim();
        return new ProfileData(lastName.trim(), firstName.trim(), middle, phone, monthlyIncome, birthDate, sex,
                country.toUpperCase(Locale.ROOT), city.trim());
    }
}
