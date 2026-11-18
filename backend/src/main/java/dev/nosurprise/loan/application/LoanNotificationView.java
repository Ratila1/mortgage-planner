package dev.nosurprise.loan.application;

import java.util.UUID;

/** Данные заявки для уведомления клиенту: причина отказа и id договора. Без сумм и ФИО. */
public record LoanNotificationView(UUID applicationId, UUID userId, LoanStatus status, String decisionComment,
                                   UUID contractDocumentId) {
}
