package dev.nosurprise.bureau.report;

import dev.nosurprise.bureau.security.ProviderPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API для провайдеров (CREDIT_BUREAU.md §7.1). Доступ — по X-Api-Key, см. SecurityConfig. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Кредитная история", description = "Запрос кредитной истории заёмщика по телефону и/или ФИО")
public class CreditReportController {

    private final CreditReportService reports;

    public CreditReportController(CreditReportService reports) {
        this.reports = reports;
    }

    @PostMapping("/credit-reports")
    @Operation(summary = "Кредитная история клиента",
            description = """
                    Ищет заёмщика по телефону и/или ФИО: пришли оба — совпасть должны оба, пришёл один — ищем по нему.
                    ФИО — фамилия и имя обязательно, отчество сравнивается, только если прислали; регистр и «ё» не важны.
                    Присылайте оба параметра: ФИО и телефон в заявках не проверяются, поиск по обоим точнее.

                    Кредитная история — только займы: ACTIVE (действует) и REPAID (погашен).
                    Не найден — 200 с found=false (запрос учитывается в журнале).
                    Под параметры подходит несколько заёмщиков — 409 AMBIGUOUS_SUBJECT, уточните вторым параметром
                    (не оплачивается).

                    Лимиты — на провайдера, в минуту и в сутки; при превышении 429 и Retry-After.
                    В ответах X-RateLimit-Limit и X-RateLimit-Remaining (минутное окно).

                    Песочница (провайдер с sandbox=true) видит только фиктивных заёмщиков:
                    +375290000001 / Иванов Иван Иванович — платёжеспособный;
                    +375290000002 / Петров Пётр — действующий займ;
                    Тестов Тест (только ФИО) — два однофамильца, 409; любые другие данные — found=false.""",
            security = @SecurityRequirement(name = "apiKey"))
    @ApiResponse(responseCode = "200", description = "История найдена или клиент не найден (found=false)",
            content = @Content(schema = @Schema(implementation = CreditReportResponse.class), examples = {
                    @ExampleObject(name = "найден", value = """
                            {"reportId":"3f1c9a52-6a4e-4c3a-9f61-2b9e1a7d0c11","found":true,
                             "matchedBy":"PHONE_AND_NAME","totalLoans":2,
                             "loansByStatus":{"ACTIVE":1,"REPAID":1},
                             "loans":[{"status":"REPAID","amount":"300.00","currency":"BYN",
                                       "submittedAt":"2026-01-10T09:00:00Z","decidedAt":"2026-01-10T12:00:00Z",
                                       "closedAt":"2026-01-30T10:00:00Z"},
                                      {"status":"ACTIVE","amount":"800.00","currency":"BYN",
                                       "submittedAt":"2026-09-15T09:00:00Z","decidedAt":"2026-09-15T11:00:00Z",
                                       "closedAt":null}],
                             "generatedAt":"2026-11-18T10:00:00Z"}"""),
                    @ExampleObject(name = "не найден", value = """
                            {"reportId":"9b2d4c11-0e6f-4a0b-8d3e-5f7a1c2e9b40","found":false,
                             "generatedAt":"2026-11-18T10:00:00Z"}""")}))
    @ApiResponse(responseCode = "400", description = "Нет ни телефона, ни фамилии с именем, или неверный формат "
            + "(VALIDATION_FAILED)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Нет ключа или ключ неверный (INVALID_API_KEY)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Доступ провайдера приостановлен (PROVIDER_SUSPENDED)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Под параметры подходит несколько заёмщиков (AMBIGUOUS_SUBJECT)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Idempotency-Key уже использован с другими параметрами",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Превышен лимит запросов (RATE_LIMITED) "
            + "или слишком много неверных ключей с адреса (TOO_MANY_AUTH_FAILURES)",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Сервис временно недоступен, повторить позже",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<CreditReportResponse> report(
            @AuthenticationPrincipal ProviderPrincipal provider,
            @Valid @RequestBody CreditReportRequest request,
            @Parameter(in = ParameterIn.HEADER, name = "Idempotency-Key",
                    description = "Необязательно. Повтор с тем же ключом в течение 24 часов вернёт тот же ответ "
                            + "без повторного списания")
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(min = 1, max = 100) String idempotencyKey) {
        CreditReportService.Result result = reports.report(provider, request, idempotencyKey);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.quota() != null) {
            builder.header("X-RateLimit-Limit", String.valueOf(result.quota().limit()))
                    .header("X-RateLimit-Remaining", String.valueOf(result.quota().remaining()));
        }
        return builder.body(result.response());
    }
}
