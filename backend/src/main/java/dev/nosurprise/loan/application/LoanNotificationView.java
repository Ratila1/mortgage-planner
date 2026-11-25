package dev.nosurprise.loan.application;

import java.util.UUID;

/** Данные заявки для уведомления клиенту: причина отказа, id договора и факт подписи при подаче. Без сумм и ФИО. */
public record LoanNotificationView(UUID applicationId, UUID userId, LoanStatus status, boolean signedAtSubmit,
                                   String decisionComment, UUID contractDocumentId) {
}
