package dev.nosurprise.loan;

import dev.nosurprise.config.RabbitLoanConfig;
import dev.nosurprise.loan.application.LoanApplicationEvent;
import dev.nosurprise.loan.application.LoanHistoryChanged;
import dev.nosurprise.loan.contract.ContractService;
import dev.nosurprise.loan.history.CreditHistorySyncService;
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

    public LoanConsumers(ContractService contracts, CreditHistorySyncService creditHistory) {
        this.contracts = contracts;
        this.creditHistory = creditHistory;
    }

    @RabbitListener(queues = RabbitLoanConfig.CONTRACT_Q)
    public void onApproved(LoanApplicationEvent event) {
        contracts.onApproved(event);
    }

    @RabbitListener(queues = RabbitLoanConfig.CREDIT_HISTORY_Q)
    public void onHistoryChanged(LoanHistoryChanged event) {
        creditHistory.sync(event.applicationId());
    }
}
