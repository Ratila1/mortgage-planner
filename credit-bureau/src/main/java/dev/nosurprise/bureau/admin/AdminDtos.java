package dev.nosurprise.bureau.admin;

import dev.nosurprise.bureau.provider.Provider;
import dev.nosurprise.bureau.provider.ProviderKey;
import dev.nosurprise.bureau.report.ReportRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DTO админского API бюро (CREDIT_BUREAU.md §9). Сущности наружу не отдаются. */
public final class AdminDtos {

    private AdminDtos() {
    }

    @Schema(description = "Новый провайдер. Лимиты не указаны — берутся значения по умолчанию")
    public record CreateProviderRequest(
            @NotBlank @Size(max = 200) String name,
            // Boolean, а не boolean: Jackson 3 по умолчанию отклоняет отсутствующее примитивное поле
            // (FAIL_ON_NULL_FOR_PRIMITIVES), а банк заводится одним полем name.
            @Schema(description = "Песочница: видит только фиктивных заёмщиков; по умолчанию false") Boolean sandbox,
            @Min(1) @Max(100_000) Integer requestsPerMinute,
            @Min(1) @Max(10_000_000) Integer requestsPerDay) {

        public boolean isSandbox() {
            return Boolean.TRUE.equals(sandbox);
        }
    }

    public record UpdateProviderRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull @Min(1) @Max(100_000) Integer requestsPerMinute,
            @NotNull @Min(1) @Max(10_000_000) Integer requestsPerDay,
            @Schema(description = "Версия из последнего ответа — защита от перезаписи чужой правки") @NotNull Long version) {
    }

    public record KeyResponse(UUID id, String prefix, Instant createdAt, Instant lastUsedAt, Instant revokedAt) {

        static KeyResponse from(ProviderKey key) {
            return new KeyResponse(key.getId(), key.getPrefix(), key.getCreatedAt(), key.getLastUsedAt(),
                    key.getRevokedAt());
        }
    }

    public record ProviderResponse(UUID id, String name, String status, boolean sandbox, int requestsPerMinute,
                                   int requestsPerDay, Instant createdAt, Instant updatedAt, Long version,
                                   List<KeyResponse> keys) {

        static ProviderResponse from(Provider p, List<ProviderKey> keys) {
            return new ProviderResponse(p.getId(), p.getName(), p.getStatus().name(), p.isSandbox(),
                    p.getRequestsPerMinute(), p.getRequestsPerDay(), p.getCreatedAt(), p.getUpdatedAt(),
                    p.getVersion(), keys.stream().map(KeyResponse::from).toList());
        }
    }

    @Schema(description = "Выпущенный ключ. apiKey показывается один раз — сохраните и передайте банку")
    public record IssuedKeyResponse(UUID keyId, String prefix, String apiKey) {
    }

    @Schema(description = "Запрос из журнала: по каким параметрам искали и что ответили (без самих ФИО и телефона)")
    public record RequestItem(UUID reportId, UUID keyId, String searchBy, String result, boolean billable,
                              Instant createdAt) {

        static RequestItem from(ReportRequest r) {
            return new RequestItem(r.getId(), r.getKeyId(), r.getSearchBy(), r.getResult(), r.isBillable(),
                    r.getCreatedAt());
        }
    }

    @Schema(description = "Журнал запросов провайдера за период [from, to): всего и сколько из них оплачиваемых")
    public record RequestLogResponse(UUID providerId, Instant from, Instant to, long total, long billable,
                                     int page, int size, List<RequestItem> items) {
    }
}
