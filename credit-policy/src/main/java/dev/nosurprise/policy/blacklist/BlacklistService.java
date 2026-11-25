package dev.nosurprise.policy.blacklist;

import dev.nosurprise.policy.common.ApiException;
import dev.nosurprise.policy.common.ErrorCode;
import dev.nosurprise.policy.common.Names;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Чёрный список: ведёт только админ, автоматически никто не попадает (CREDIT_POLICY.md §7.4). */
@Service
public class BlacklistService {

    private final BlacklistRepository repository;
    private final Clock clock;

    public BlacklistService(BlacklistRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isBlacklisted(String phone, String lastName, String firstName, String middleName,
                                 LocalDate birthDate) {
        return repository.matches(blankToNull(phone), Names.normalize(lastName), Names.normalize(firstName),
                Names.normalize(middleName), birthDate, clock.instant());
    }

    @Transactional(readOnly = true)
    public Page<BlacklistEntry> search(String query, boolean activeOnly, int page, int size) {
        String q = Names.normalize(query);
        return repository.search(q == null ? null : "%" + q + "%", activeOnly, clock.instant(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Transactional
    public BlacklistEntry add(String phone, String lastName, String firstName, String middleName, LocalDate birthDate,
                              String reason, Instant expiresAt, UUID adminId) {
        String p = blankToNull(phone);
        String last = Names.normalize(lastName);
        String first = Names.normalize(firstName);
        if (p == null && (last == null || first == null || birthDate == null)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.BLACKLIST_KEY_REQUIRED,
                    "phone or lastName + firstName + birthDate is required");
        }
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "expiresAt must be in the future");
        }
        return repository.save(new BlacklistEntry(p, last, first, Names.normalize(middleName), birthDate,
                reason.trim(), adminId, expiresAt, now));
    }

    @Transactional
    public BlacklistEntry remove(UUID id, UUID adminId) {
        BlacklistEntry entry = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.BLACKLIST_ENTRY_NOT_FOUND,
                        "blacklist entry not found"));
        entry.remove(adminId, clock.instant());
        return repository.save(entry);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
