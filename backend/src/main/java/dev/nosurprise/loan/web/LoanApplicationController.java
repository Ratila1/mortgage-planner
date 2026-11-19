package dev.nosurprise.loan.web;

import dev.nosurprise.common.Paging;
import dev.nosurprise.customer.web.dto.CodeRequest;
import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationService;
import dev.nosurprise.loan.contract.ContractService;
import dev.nosurprise.loan.contract.DocumentNotFoundException;
import dev.nosurprise.loan.contract.LoanDocument;
import dev.nosurprise.loan.signing.SigningService;
import dev.nosurprise.loan.type.LoanType;
import dev.nosurprise.loan.type.LoanTypeService;
import dev.nosurprise.loan.web.dto.ApplicationResponse;
import dev.nosurprise.loan.web.dto.DocumentResponse;
import dev.nosurprise.loan.web.dto.PageResponse;
import dev.nosurprise.loan.web.dto.SubmitApplicationRequest;
import io.micrometer.core.annotation.Timed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

/** Заявки клиента в личном кабинете (MICROLOANS.md §3.3, §3.13, §4.6). */
@RestController
@RequestMapping("/api/loans/applications")
@Timed(value = "nosurprise.controller", description = "Controller endpoint latency")
public class LoanApplicationController {

    private final LoanApplicationService applications;
    private final LoanTypeService loanTypes;
    private final SigningService signing;
    private final ContractService contracts;

    public LoanApplicationController(LoanApplicationService applications, LoanTypeService loanTypes,
                                     SigningService signing, ContractService contracts) {
        this.applications = applications;
        this.loanTypes = loanTypes;
        this.signing = signing;
        this.contracts = contracts;
    }

    @PostMapping
    ResponseEntity<ApplicationResponse> submit(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody SubmitApplicationRequest request) {
        LoanApplication application = applications.submit(userId(jwt), request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(card(application));
    }

    /** История микрозаймов клиента, новые сверху (§3.12). */
    @GetMapping
    PageResponse<ApplicationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        Page<LoanApplication> result = applications.listOwned(userId(jwt), Paging.page(page), Paging.size(size));
        Map<UUID, LoanType> types = loanTypes.getAll(result.map(LoanApplication::getLoanTypeId).toSet());
        return PageResponse.of(result, a -> ApplicationResponse.from(a, typeName(types, a), List.of()));
    }

    @GetMapping("/{id}")
    ApplicationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return card(applications.getOwned(userId(jwt), id));
    }

    @PostMapping("/{id}/cancel")
    ApplicationResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return card(applications.cancel(userId(jwt), id));
    }

    @GetMapping("/{id}/documents/{documentId}")
    ResponseEntity<Resource> document(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                      @PathVariable UUID documentId) {
        // Проверка владельца: чужая заявка → 404.
        applications.getOwned(userId(jwt), id);
        LoanDocument document = applications.documentsOf(id).stream()
                .filter(d -> d.getId().equals(documentId)).findFirst()
                .orElseThrow(() -> new DocumentNotFoundException("document not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contract-" + id + ".pdf\"")
                .body(contracts.load(document));
    }

    @PostMapping("/{id}/signature")
    ResponseEntity<Map<String, UUID>> requestSignatureCode(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID verificationId = signing.requestCode(userId(jwt), id);
        return ResponseEntity.accepted().body(Map.of("verificationId", verificationId));
    }

    @PostMapping("/{id}/signature/confirm")
    ApplicationResponse confirmSignature(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                         @Valid @RequestBody CodeRequest request, HttpServletRequest http) {
        LoanApplication signed = signing.confirm(userId(jwt), id, request.code(), http.getRemoteAddr(),
                http.getHeader(HttpHeaders.USER_AGENT));
        return card(signed);
    }

    private ApplicationResponse card(LoanApplication application) {
        String typeName = loanTypes.get(application.getLoanTypeId()).getName();
        List<DocumentResponse> documents = applications.documentsOf(application.getId()).stream()
                .map(DocumentResponse::from).toList();
        return ApplicationResponse.from(application, typeName, documents);
    }

    private static String typeName(Map<UUID, LoanType> types, LoanApplication application) {
        LoanType type = types.get(application.getLoanTypeId());
        return type == null ? null : type.getName();
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
