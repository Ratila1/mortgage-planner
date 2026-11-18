package dev.nosurprise.loan;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

/**
 * Настройки микрозаймов (app.loan.*):
 * offerTtlBusinessDays — срок подписания одобренного предложения в рабочих днях (§3.8);
 * businessZone — часовой пояс, в котором считаются рабочие дни;
 * debtLoadWarningPercent — порог ПДН, выше которого админ видит предупреждение (§3.7);
 * creditHistoryReconcileDelay — сколько ждать после последнего события, прежде чем сверка сочтёт копию отставшей (§4.9).
 */
@ConfigurationProperties(prefix = "app.loan")
public record LoanProperties(int offerTtlBusinessDays, ZoneId businessZone, BigDecimal debtLoadWarningPercent,
                             Duration creditHistoryReconcileDelay) {

    public LoanProperties {
        if (offerTtlBusinessDays <= 0) {
            offerTtlBusinessDays = 5;
        }
        if (businessZone == null) {
            businessZone = ZoneId.of("Europe/Minsk");
        }
        if (debtLoadWarningPercent == null) {
            debtLoadWarningPercent = BigDecimal.valueOf(50);
        }
        if (creditHistoryReconcileDelay == null) {
            creditHistoryReconcileDelay = Duration.ofMinutes(15);
        }
    }
}
