package dev.nosurprise.loan.web.dto;

import dev.nosurprise.loan.contract.LoanDocument;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(UUID id, String type, String downloadUrl, Instant createdAt) {

    public static DocumentResponse from(LoanDocument document) {
        return new DocumentResponse(document.getId(), document.getType(),
                "/api/loans/applications/" + document.getApplicationId() + "/documents/" + document.getId(),
                document.getCreatedAt());
    }
}
