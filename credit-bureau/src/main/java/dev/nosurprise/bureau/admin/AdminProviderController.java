package dev.nosurprise.bureau.admin;

import dev.nosurprise.bureau.common.ApiException;
import dev.nosurprise.bureau.common.ErrorCode;
import dev.nosurprise.bureau.config.BureauProperties;
import dev.nosurprise.bureau.provider.Provider;
import dev.nosurprise.bureau.provider.ProviderService;
import dev.nosurprise.bureau.provider.ProviderStatus;
import dev.nosurprise.bureau.report.ReportRequest;
import dev.nosurprise.bureau.report.ReportRequestRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Админский API бюро (CREDIT_BUREAU.md §9): провайдеры, ключи, журнал запросов. Доступ — по X-Admin-Key
 * (BUREAU_ADMIN_KEY). Банк добавляется сюда после того, как с ним договорились.
 */
@RestController
@RequestMapping("/api/admin/providers")
@Tag(name = "Админ: провайдеры", description = "Управление банками-провайдерами и их ключами (X-Admin-Key)")
@SecurityRequirement(name = "adminKey")
public class AdminProviderController {

    private static final int MAX_PAGE_SIZE = 500;

    private final ProviderService providers;
    private final ReportRequestRepository journal;
    private final BureauProperties properties;

    public AdminProviderController(ProviderService providers, ReportRequestRepository journal,
                                   BureauProperties properties) {
        this.providers = providers;
        this.journal = journal;
        this.properties = properties;
    }

    @GetMapping
    @Operation(summary = "Все провайдеры с ключами (без секретов)")
    public List<AdminDtos.ProviderResponse> list() {
        return providers.list().stream().map(this::response).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Провайдер с ключами")
    public AdminDtos.ProviderResponse get(@PathVariable UUID id) {
        return response(providers.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Добавить провайдера", description = "Ключ выпускается отдельным запросом")
    public AdminDtos.ProviderResponse create(@Valid @RequestBody AdminDtos.CreateProviderRequest request) {
        int perMinute = request.requestsPerMinute() != null ? request.requestsPerMinute()
                : request.isSandbox() ? properties.sandboxRequestsPerMinute() : properties.defaultRequestsPerMinute();
        int perDay = request.requestsPerDay() != null ? request.requestsPerDay()
                : request.isSandbox() ? properties.sandboxRequestsPerDay() : properties.defaultRequestsPerDay();
        return response(providers.create(request.name(), request.isSandbox(), perMinute, perDay));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Изменить название и лимиты")
    public AdminDtos.ProviderResponse update(@PathVariable UUID id,
                                             @Valid @RequestBody AdminDtos.UpdateProviderRequest request) {
        return response(providers.update(id, request.name(), request.requestsPerMinute(), request.requestsPerDay(),
                request.version()));
    }

    @PostMapping("/{id}/suspend")
    @Operation(summary = "Приостановить доступ", description = "Действует сразу: следующий запрос получит 403")
    public AdminDtos.ProviderResponse suspend(@PathVariable UUID id) {
        return response(providers.changeStatus(id, ProviderStatus.SUSPENDED));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Возобновить доступ")
    public AdminDtos.ProviderResponse activate(@PathVariable UUID id) {
        return response(providers.changeStatus(id, ProviderStatus.ACTIVE));
    }

    @PostMapping("/{id}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Выпустить ключ", description = "Полный ключ показывается один раз")
    public AdminDtos.IssuedKeyResponse issueKey(@PathVariable UUID id) {
        ProviderService.IssuedKey issued = providers.issueKey(id);
        return new AdminDtos.IssuedKeyResponse(issued.key().getId(), issued.key().getPrefix(), issued.plainText());
    }

    @PostMapping("/{id}/keys/{keyId}/revoke")
    @Operation(summary = "Отозвать ключ", description = "Действует сразу")
    public AdminDtos.KeyResponse revokeKey(@PathVariable UUID id, @PathVariable UUID keyId) {
        return AdminDtos.KeyResponse.from(providers.revokeKey(id, keyId));
    }

    @GetMapping("/{id}/requests")
    @Operation(summary = "Журнал запросов за период", description = "from включительно, to — нет; время в UTC")
    public AdminDtos.RequestLogResponse requests(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        providers.get(id);
        if (!from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "from must be before to");
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<ReportRequest> items = journal
                .findByProviderIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(id, from, to,
                        PageRequest.of(safePage, safeSize));
        long total = journal.countByProviderIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(id, from, to);
        long billable = journal.countByProviderIdAndBillableTrueAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(id,
                from, to);
        return new AdminDtos.RequestLogResponse(id, from, to, total, billable, safePage, safeSize,
                items.getContent().stream().map(AdminDtos.RequestItem::from).toList());
    }

    private AdminDtos.ProviderResponse response(Provider provider) {
        return AdminDtos.ProviderResponse.from(provider, providers.keysOf(provider.getId()));
    }
}
