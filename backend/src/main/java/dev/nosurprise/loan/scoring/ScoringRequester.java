package dev.nosurprise.loan.scoring;

import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.customer.ProfileView;
import dev.nosurprise.loan.LoanProperties;
import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanRoutingKeys;
import dev.nosurprise.loan.application.ScoringHistoryRow;
import dev.nosurprise.loan.type.LoanType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Запрос скоринга (CREDIT_POLICY.md §6.1): новый requestId в заявке + событие loan.scoring.requested в outbox,
 * в текущей транзакции. Сохраняет заявку вызывающий: при подаче — вместе с ней самой.
 */
@Component
public class ScoringRequester {

    private final LoanApplicationRepository repository;
    private final OutboxPublisher outbox;
    private final LoanProperties properties;
    private final Clock clock;

    public ScoringRequester(LoanApplicationRepository repository, OutboxPublisher outbox, LoanProperties properties,
                            Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void request(LoanApplication application, LoanType type, ProfileView profile) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, properties.businessZone());
        Instant since = ZonedDateTime.ofInstant(now, properties.businessZone()).minusMonths(12).toInstant();
        ScoringHistoryRow row = repository.scoringHistory(application.getUserId(), application.getId(), today, since);
        UUID requestId = UUID.randomUUID();
        application.requestScoring(requestId, now);

        var terms = application.getTerms();
        var applicant = new ScoringRequested.Applicant(profile.lastName(), profile.firstName(), profile.middleName(),
                profile.phone(), profile.birthDate(), profile.sex() == null ? null : profile.sex().name(),
                profile.country(), profile.city());
        var loan = new ScoringRequested.Loan(type.getId(), type.getName(), type.getPurposeScore(),
                application.getAmount(), application.getTermDays(), terms.getTotal(), profile.monthlyIncome());
        var history = new ScoringRequested.History((int) row.getSignedLoans(), (int) row.getRepaidOnTime(),
                (int) row.getRepaidLate(), row.getMaxOverdueDays(), row.getOverdueNow(),
                (int) row.getRejectedLast12Months());
        outbox.publish(LoanRoutingKeys.SCORING_REQUESTED, LoanRoutingKeys.AGGREGATE, application.getId(),
                new ScoringRequested(UUID.randomUUID(), now, requestId, application.getId(), application.getUserId(),
                        applicant, loan, history));
    }
}
