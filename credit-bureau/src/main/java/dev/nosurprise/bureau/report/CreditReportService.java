package dev.nosurprise.bureau.report;

import dev.nosurprise.bureau.common.ApiException;
import dev.nosurprise.bureau.common.ErrorCode;
import dev.nosurprise.bureau.ratelimit.ProviderRateLimiter;
import dev.nosurprise.bureau.security.ProviderPrincipal;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Запрос кредитной истории (CREDIT_BUREAU.md §7.2): параметры → идемпотентность → лимит →
 * Postgres монолита (кто заёмщик) → Mongo (его займы) → журнал → ответ.
 * Повтор по Idempotency-Key проверяется до лимита: повтор после сетевого сбоя не съедает квоту
 * и не пишется в журнал второй раз. Ретраев при расхождении Postgres и Mongo нет (решение 12).
 */
@Service
public class CreditReportService {

    private final ProviderRateLimiter rateLimiter;
    private final ApplicantLookup lookup;
    private final CreditHistoryReader reader;
    private final ReportRequestRepository journal;
    private final IdempotencyStore idempotency;
    private final MeterRegistry meters;
    private final Clock clock;

    public CreditReportService(ProviderRateLimiter rateLimiter, ApplicantLookup lookup, CreditHistoryReader reader,
                               ReportRequestRepository journal, IdempotencyStore idempotency, MeterRegistry meters,
                               Clock clock) {
        this.rateLimiter = rateLimiter;
        this.lookup = lookup;
        this.reader = reader;
        this.journal = journal;
        this.idempotency = idempotency;
        this.meters = meters;
        this.clock = clock;
    }

    public Result report(ProviderPrincipal provider, CreditReportRequest request, String idempotencyKey) {
        SearchQuery query;
        try {
            query = SearchQuery.of(request.phone(), request.lastName(), request.firstName(), request.middleName());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, ex.getMessage());
        }
        String queryHash = query.hash();

        if (idempotencyKey != null) {
            Optional<IdempotencyStore.Entry> previous = idempotency.find(provider.providerId(), idempotencyKey);
            if (previous.isPresent()) {
                if (!previous.get().queryHash().equals(queryHash)) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.IDEMPOTENCY_KEY_REUSED,
                            "Idempotency-Key was already used with other search parameters");
                }
                return new Result(previous.get().response(), null);
            }
        }

        ProviderRateLimiter.Quota quota = rateLimiter.consume(provider);

        // Кто заёмщик: до двух разных клиентов (§3.4). Песочница не видит боевых данных.
        Optional<StoredHistory> history;
        int matches;
        if (provider.sandbox()) {
            List<SandboxReports.FakeBorrower> found = SandboxReports.find(query);
            matches = found.size();
            history = matches == 1 ? SandboxReports.history(found.get(0)) : Optional.empty();
        } else {
            List<UUID> userIds = lookup.find(query);
            matches = userIds.size();
            history = matches == 1 ? Optional.of(reader.find(userIds.get(0))) : Optional.empty();
        }

        Instant now = clock.instant();
        UUID reportId = UUID.randomUUID();
        if (matches > 1) {
            // Однофамильцы: смешанную историю двух людей отдавать нельзя. Не оплачивается и не раскрывает,
            // сколько людей нашлось и кто они.
            record(provider, reportId, query, queryHash, ReportRequest.Result.AMBIGUOUS, false, null, now);
            count(provider, ReportRequest.Result.AMBIGUOUS);
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.AMBIGUOUS_SUBJECT,
                    "several borrowers match, refine the request with the second parameter (phone or full name)");
        }

        CreditReportResponse response = history
                .map(found -> CreditReportMapper.found(reportId, query.searchBy(), found, now))
                .orElseGet(() -> CreditReportMapper.notFound(reportId, now));
        ReportRequest.Result result = response.found() ? ReportRequest.Result.FOUND : ReportRequest.Result.NOT_FOUND;
        record(provider, reportId, query, queryHash, result, true, idempotencyKey, now);
        if (idempotencyKey != null) {
            idempotency.save(provider.providerId(), idempotencyKey, new IdempotencyStore.Entry(queryHash, response));
        }
        count(provider, result);
        return new Result(response, quota);
    }

    private void record(ProviderPrincipal provider, UUID reportId, SearchQuery query, String queryHash,
                        ReportRequest.Result result, boolean billable, String idempotencyKey, Instant now) {
        try {
            journal.saveAndFlush(new ReportRequest(reportId, provider.providerId(), provider.keyId(), query.searchBy(),
                    queryHash, result, billable, idempotencyKey, now));
        } catch (DataIntegrityViolationException ex) {
            // Два одновременных запроса с одним Idempotency-Key: в журнале уже есть строка, второй раз не пишем.
            if (idempotencyKey == null) {
                throw ex;
            }
        }
    }

    private void count(ProviderPrincipal provider, ReportRequest.Result result) {
        meters.counter("bureau.reports", "provider", provider.providerName(), "result", result.name()).increment();
    }

    /** quota == null — ответ из кэша идемпотентности, квота не расходовалась. */
    public record Result(CreditReportResponse response, ProviderRateLimiter.Quota quota) {
    }
}
