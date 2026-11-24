package dev.nosurprise.customer.web.dto;

import dev.nosurprise.customer.ProfileView;
import java.time.LocalDate;

/** Профиль клиента. Деньги строками (INSTRUCTION.md §9). Поля скоринга null у профилей до V17. */
public record ProfileResponse(String lastName, String firstName, String middleName, String phone,
                              boolean phoneVerified, String monthlyIncome, LocalDate birthDate, String sex,
                              String country, String city, Long version) {

    public static ProfileResponse from(ProfileView view) {
        return new ProfileResponse(view.lastName(), view.firstName(), view.middleName(), view.phone(),
                view.phoneVerified(), view.monthlyIncome().toPlainString(), view.birthDate(),
                view.sex() == null ? null : view.sex().name(), view.country(), view.city(), view.version());
    }
}
