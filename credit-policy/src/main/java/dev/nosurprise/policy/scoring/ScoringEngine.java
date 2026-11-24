package dev.nosurprise.policy.scoring;

import dev.nosurprise.policy.common.Names;
import dev.nosurprise.policy.rules.RuleSet;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Расчёт скоринга (CREDIT_POLICY.md §3.1). Чистая функция без Spring и БД: один и тот же вход и версия правил
 * всегда дают один и тот же результат, поэтому любой расчёт можно воспроизвести по журналу.
 */
public final class ScoringEngine {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal POINTS_SCALE = BigDecimal.valueOf(RatingScale.MAX_POINTS);

    private ScoringEngine() {
    }

    /**
     * @param purposeLabel название типа займа — цель (для расшифровки)
     * @param purposeScore балл цели из типа займа; пусто или вне 0–1 — скорбалл не рассчитывается
     * @param blacklisted  заёмщик найден в активном чёрном списке
     * @param today        дата расчёта в поясе Europe/Minsk — от неё считается возраст
     */
    public static ScoringOutcome evaluate(RuleSet rules, ScoringRequested.Applicant applicant,
                                          ScoringRequested.History history, String purposeLabel,
                                          Optional<BigDecimal> purposeScore,
                                          boolean blacklisted, LocalDate today) {
        ScoringRequested.History h = history == null ? ScoringRequested.History.EMPTY : history;
        Integer age = applicant.birthDate() == null ? null : Period.between(applicant.birthDate(), today).getYears();

        // 1. Стоп-факторы: отказ без расчёта баллов, класс F. Собираем все, чтобы админ видел полную картину.
        List<String> stops = new ArrayList<>();
        if (age != null && (age < rules.age().min() || age > rules.age().max())) {
            stops.add(ScoringReason.AGE_OUT_OF_RANGE.name());
        }
        if (blacklisted) {
            stops.add(ScoringReason.BLACKLISTED.name());
        }
        if (h.overdueNow()) {
            stops.add(ScoringReason.CURRENT_OVERDUE.name());
        }
        if (!stops.isEmpty()) {
            return ScoringOutcome.stop(stops);
        }

        // 2. Нехватка данных: скорбалл не рассчитывается, решает человек.
        BigDecimal sexScore = applicant.sex() == null ? null : rules.sex().get(applicant.sex().toUpperCase(Locale.ROOT));
        if (age == null || sexScore == null || isBlank(applicant.country()) || isBlank(applicant.city())) {
            return ScoringOutcome.notCalculated(ScoringReason.MISSING_PROFILE_DATA);
        }
        if (purposeScore.isEmpty() || purposeScore.get().signum() < 0
                || purposeScore.get().compareTo(BigDecimal.ONE) > 0) {
            return ScoringOutcome.notCalculated(ScoringReason.PURPOSE_SCORE_MISSING);
        }

        // 3. Факторы → средневзвешенное.
        RuleSet.Weights w = rules.weights();
        List<FactorScore> factors = List.of(
                factor(Factor.HISTORY, historyLabel(h), historyScore(rules.history(), h), w.history()),
                factor(Factor.PURPOSE, purposeLabel, purposeScore.get(), w.purpose()),
                factor(Factor.AGE, age + " лет", ageScore(rules.age(), age), w.age()),
                factor(Factor.CITY, applicant.country().toUpperCase(Locale.ROOT) + ", " + applicant.city().trim(),
                        cityScore(rules.city(), applicant.country(), applicant.city()), w.city()),
                factor(Factor.SEX, applicant.sex().toUpperCase(Locale.ROOT), sexScore, w.sex()));

        BigDecimal weighted = factors.stream().map(FactorScore::contribution).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal score = weighted.divide(w.total(), MC).setScale(4, RoundingMode.HALF_UP);
        int points = score.multiply(POINTS_SCALE).setScale(0, RoundingMode.HALF_UP).intValueExact();
        String ratingClass = RatingScale.classFor(points);
        BigDecimal pd = pd(rules.pd(), score);
        ScoringDecision decision = points <= rules.decision().autoRejectMaxPoints() ? ScoringDecision.AUTO_REJECT
                : points <= rules.decision().reviewMaxPoints() ? ScoringDecision.REVIEW
                : ScoringDecision.RECOMMEND;
        return new ScoringOutcome(ScoringStatus.COMPLETED, score, points, ratingClass, pd, decision, List.of(),
                factors);
    }

    /** Фактор истории (§3.3): нейтрально без истории, бонус за погашенные вовремя, штрафы за просрочки и отказы. */
    static BigDecimal historyScore(RuleSet.History rules, ScoringRequested.History h) {
        if (h.signedLoans() == 0 && h.rejectedLast12Months() == 0) {
            return rules.noHistory();
        }
        BigDecimal bonus = rules.perRepaidOnTime().multiply(BigDecimal.valueOf(h.repaidOnTime()))
                .min(rules.maxRepaidBonus());
        BigDecimal value = rules.base().add(bonus)
                .subtract(rules.perRepaidLate().multiply(BigDecimal.valueOf(h.repaidLate())))
                .subtract(rules.perRejectedLast12Months().multiply(BigDecimal.valueOf(h.rejectedLast12Months())));
        return clamp(value);
    }

    static BigDecimal ageScore(RuleSet.Age rules, int age) {
        return rules.bands().stream()
                .filter(band -> band.from() <= age && age <= band.to())
                .map(RuleSet.AgeBand::score)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("age " + age + " is not covered by rule bands"));
    }

    static BigDecimal cityScore(RuleSet.City rules, String country, String city) {
        if (!rules.homeCountry().equalsIgnoreCase(country.trim())) {
            return rules.foreign();
        }
        return rules.cities().getOrDefault(Names.normalize(city), rules.otherHomeCountry());
    }

    /**
     * Логистическая калибровка (§3.4): odds = e^(a + b·score), PD = 1 / (1 + odds),
     * b = ln 2 / doubleOddsStep, a = ln((1 − anchorPd) / anchorPd) − b·anchorScore.
     */
    static BigDecimal pd(RuleSet.Pd rules, BigDecimal score) {
        double b = Math.log(2) / rules.doubleOddsStep().doubleValue();
        double anchorPd = rules.anchorPd().doubleValue();
        double a = Math.log((1 - anchorPd) / anchorPd) - b * rules.anchorScore().doubleValue();
        double value = 1 / (1 + Math.exp(a + b * score.doubleValue()));
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static FactorScore factor(Factor factor, String value, BigDecimal score, BigDecimal weight) {
        return new FactorScore(factor, value, score, weight, score.multiply(weight, MC));
    }

    private static String historyLabel(ScoringRequested.History h) {
        if (h.signedLoans() == 0 && h.rejectedLast12Months() == 0) {
            return "нет истории";
        }
        return "погашено вовремя " + h.repaidOnTime() + ", с просрочкой " + h.repaidLate()
                + ", отказов за 12 мес. " + h.rejectedLast12Months();
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
