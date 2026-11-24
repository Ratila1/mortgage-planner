package dev.nosurprise.policy.blacklist;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Чёрный список")
@Validated
@RestController
@RequestMapping("/api/admin/blacklist")
public class BlacklistController {

    private final BlacklistService blacklist;
    private final Clock clock;

    public BlacklistController(BlacklistService blacklist, Clock clock) {
        this.blacklist = blacklist;
        this.clock = clock;
    }

    public record AddEntryRequest(
            @Pattern(regexp = "^\\+\\d{10,15}$", message = "must be in format +375291234567") String phone,
            @Size(max = 100) String lastName,
            @Size(max = 100) String firstName,
            @Size(max = 100) String middleName,
            @Past LocalDate birthDate,
            @NotBlank @Size(max = 500) String reason,
            Instant expiresAt) {
    }

    public record EntryResponse(UUID id, String phone, String lastName, String firstName, String middleName,
                                LocalDate birthDate, String reason, UUID addedBy, Instant createdAt,
                                Instant expiresAt, Instant removedAt, UUID removedBy, boolean active) {

        static EntryResponse from(BlacklistEntry e, Instant now) {
            return new EntryResponse(e.getId(), e.getPhone(), e.getLastNameNorm(), e.getFirstNameNorm(),
                    e.getMiddleNameNorm(), e.getBirthDate(), e.getReason(), e.getAddedBy(), e.getCreatedAt(),
                    e.getExpiresAt(), e.getRemovedAt(), e.getRemovedBy(), e.isActive(now));
        }
    }

    public record EntryPage(List<EntryResponse> items, long total, int page, int size) {
    }

    @Operation(summary = "Записи чёрного списка (поиск по телефону или фамилии)")
    @GetMapping
    public EntryPage search(@RequestParam(required = false) String query,
                            @RequestParam(defaultValue = "true") boolean activeOnly,
                            @RequestParam(defaultValue = "0") @Min(0) int page,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Instant now = clock.instant();
        Page<BlacklistEntry> result = blacklist.search(query, activeOnly, page, size);
        return new EntryPage(result.map(e -> EntryResponse.from(e, now)).getContent(), result.getTotalElements(),
                page, size);
    }

    @Operation(summary = "Внести в чёрный список: телефон или фамилия + имя + дата рождения, причина обязательна")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntryResponse add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddEntryRequest request) {
        BlacklistEntry entry = blacklist.add(request.phone(), request.lastName(), request.firstName(),
                request.middleName(), request.birthDate(), request.reason(), request.expiresAt(),
                UUID.fromString(jwt.getSubject()));
        return EntryResponse.from(entry, clock.instant());
    }

    @Operation(summary = "Снять запись (не удаляется: остаётся, кто и когда снял)")
    @DeleteMapping("/{id}")
    public EntryResponse remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return EntryResponse.from(blacklist.remove(id, UUID.fromString(jwt.getSubject())), clock.instant());
    }
}
