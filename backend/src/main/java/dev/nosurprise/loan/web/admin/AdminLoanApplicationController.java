package dev.nosurprise.loan.web.admin;

import dev.nosurprise.common.Paging;
import dev.nosurprise.customer.kyc.KycContent;
import dev.nosurprise.loan.LoanProperties;
import dev.nosurprise.loan.application.LoanAdminService;
import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationService;
import dev.nosurprise.loan.application.LoanStatus;
import dev.nosurprise.loan.history.CreditHistoryQueryService;
import dev.nosurprise.loan.type.LoanType;
import dev.nosurprise.loan.type.LoanTypeService;
import dev.nosurprise.loan.web.dto.AdminApplicationCard;
import dev.nosurprise.loan.web.dto.AdminApplicationRow;
import dev.nosurprise.loan.web.dto.CreditHistoryResponse;
import dev.nosurprise.loan.web.dto.DecisionRequest;
import dev.nosurprise.loan.web.dto.DocumentResponse;
import dev.nosurprise.loan.web.dto.PageResponse;
import dev.nosurprise.loan.web.dto.RejectRequest;
import io.micrometer.core.annotation.Timed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Панель заявок в CRM админа (MICROLOANS.md §3.4, §4.7). */
@RestController
@RequestMapping("/api/admin/loan-applications")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class AdminLoanApplicationController {

    private final LoanAdminService admin;
    private final LoanApplicationService applications;
    private final LoanTypeService loanTypes;
    private final CreditHistoryQueryService creditHistory;
    private final LoanProperties properties;

    public AdminLoanApplicationController(LoanAdminService admin, LoanApplicationService applications,
                                          LoanTypeService loanTypes, CreditHistoryQueryService creditHistory,
                                          LoanProperties properties) {
        this.admin = admin;
        this.applications = applications;
        this.loanTypes = loanTypes;
        this.creditHistory = creditHistory;
        this.properties = properties;
    }

    /** Один запрос к loan_applications плюс один за названиями типов — без N+1 (§4.7). */
    @GetMapping
    PageResponse<AdminApplicationRow> list(@RequestParam(defaultValue = "SUBMITTED") LoanStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        Page<LoanApplication> result = admin.list(status, Paging.page(page), Paging.size(size));
        Map<UUID, LoanType> types = loanTypes.getAll(result.map(LoanApplication::getLoanTypeId).toSet());
        return PageResponse.of(result, a -> AdminApplicationRow.from(a,
                types.containsKey(a.getLoanTypeId()) ? types.get(a.getLoanTypeId()).getName() : null,
                properties.debtLoadWarningPercent()));
    }

    @GetMapping("/{id}")
    AdminApplicationCard card(@PathVariable UUID id) {
        return card(admin.get(id));
    }

    /** Фото паспорта — только по кнопке и только админу; просмотр пишется в лог (§5). Не кэшируется браузером. */
    @GetMapping("/{id}/passport")
    ResponseEntity<byte[]> passport(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        KycContent content = admin.passport(id, UUID.fromString(jwt.getSubject()), http.getRemoteAddr());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore())
                .body(content.bytes());
    }

    @PostMapping("/{id}/approve")
    AdminApplicationCard approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody DecisionRequest request) {
        return card(admin.approve(id, adminId(jwt), request.comment(), request.version()));
    }

    @PostMapping("/{id}/reject")
    AdminApplicationCard reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @Valid @RequestBody RejectRequest request) {
        return card(admin.reject(id, adminId(jwt), request.reason(), request.version()));
    }

    @PostMapping("/{id}/repaid")
    AdminApplicationCard repaid(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @Valid @RequestBody DecisionRequest request) {
        return card(admin.markRepaid(id, adminId(jwt), request.version()));
    }

    private AdminApplicationCard card(LoanApplication application) {
        String typeName = loanTypes.get(application.getLoanTypeId()).getName();
        List<DocumentResponse> documents = applications.documentsOf(application.getId()).stream()
                .map(DocumentResponse::from).toList();
        CreditHistoryResponse history = CreditHistoryResponse.from(creditHistory.forUser(application.getUserId()));
        return AdminApplicationCard.from(application, typeName, properties.debtLoadWarningPercent(), documents,
                history);
    }

    private static UUID adminId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
