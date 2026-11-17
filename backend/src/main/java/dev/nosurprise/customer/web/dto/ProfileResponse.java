package dev.nosurprise.customer.web.dto;

import dev.nosurprise.customer.ProfileView;

/** Профиль клиента. Деньги строками (INSTRUCTION.md §9). */
public record ProfileResponse(String lastName, String firstName, String middleName, String phone,
                              boolean phoneVerified, String monthlyIncome, Long version) {

    public static ProfileResponse from(ProfileView view) {
        return new ProfileResponse(view.lastName(), view.firstName(), view.middleName(), view.phone(),
                view.phoneVerified(), view.monthlyIncome().toPlainString(), view.version());
    }
}
