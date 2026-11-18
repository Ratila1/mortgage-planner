package dev.nosurprise.loan.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Запись прокола в отдельной транзакции (REQUIRES_NEW). Нужна там, где за проколом сразу следует ошибка
 * клиенту (неверный код, подпись без телефона): основная транзакция откатится, а прокол должен остаться.
 * Отдельный бин — чтобы вызов шёл через прокси и REQUIRES_NEW сработал.
 */
@Component
public class LoanIncidentRecorder {

    private final LoanEventRecorder recorder;

    public LoanIncidentRecorder(LoanEventRecorder recorder) {
        this.recorder = recorder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID applicationId, LoanEventType type, UUID actorId, Map<String, Object> details) {
        recorder.incident(applicationId, type, actorId, details);
    }
}
