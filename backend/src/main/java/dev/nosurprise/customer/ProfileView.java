package dev.nosurprise.customer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Профиль для других модулей и контроллеров: сущность наружу не отдаётся (INSTRUCTION.md §4). */
public record ProfileView(UUID userId, String lastName, String firstName, String middleName, String phone,
                          boolean phoneVerified, BigDecimal monthlyIncome, Instant updatedAt, Long version) {

    static ProfileView from(CustomerProfile profile) {
        return new ProfileView(profile.getUserId(), profile.getLastName(), profile.getFirstName(),
                profile.getMiddleName(), profile.getPhone(), profile.getPhoneVerifiedAt() != null,
                profile.getMonthlyIncome(), profile.getUpdatedAt(), profile.getVersion());
    }
}
