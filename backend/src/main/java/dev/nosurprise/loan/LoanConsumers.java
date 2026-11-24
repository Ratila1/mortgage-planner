package dev.nosurprise.loan;

import dev.nosurprise.config.RabbitLoanConfig;
import dev.nosurprise.loan.application.LoanApplicationEvent;
import dev.nosurprise.loan.application.LoanHistoryChanged;
import dev.nosurprise.loan.contract.ContractService;
import dev.nosurprise.loan.history.CreditHistorySyncService;
import dev.nosurprise.loan.scoring.ScoringCompleted;
import dev.nosurprise.loan.scoring.ScoringResultService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumer-ы модуля loan (MICROLOANS.md §4.5). Логика — в сервисах; здесь только привязка к очередям.
 * Ошибка пробрасывается: 3 попытки, затем DLQ; кредитную историю после DLQ догоняет сверка.
 */
@Component
public class LoanConsumers {

    private final ContractService contracts;
    private final CreditHistorySyncService creditHistory;
    private final ScoringResultService scoring;

    public LoanConsumers(ContractService contracts, CreditHistorySyncService creditHistory,
                         ScoringResultService scoring) {
        this.contracts = contracts;
        this.creditHistory = creditHistory;
        this.scoring = scoring;
    }

    @RabbitListener(queues = RabbitLoanConfig.CONTRACT_Q)
    public void onApproved(LoanApplicationEvent event) {
        contracts.onApproved(event);
    }

    @RabbitListener(queues = RabbitLoanConfig.CREDIT_HISTORY_Q)
    public void onHistoryChanged(LoanHistoryChanged event) {
        creditHistory.sync(event.applicationId());
    }

    /** Результат скоринга из credit-policy (CREDIT_POLICY.md §6.2). */
    @RabbitListener(queues = RabbitLoanConfig.SCORING_RESULT_Q)
    public void onScoringCompleted(ScoringCompleted result) {
        scoring.apply(result);
    }
}
