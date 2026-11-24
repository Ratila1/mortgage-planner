package dev.nosurprise.policy.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Набор правил скоринга одной версии (CREDIT_POLICY.md §3.2–§3.4): веса, баллы по значениям, пороги решений,
 * калибровка PD. Хранится в rule_sets.rules как JSON; расчёт идёт только по объекту этого класса.
 */
public record RuleSet(Weights weights, Age age, City city, Map<String, BigDecimal> sex, History history,
                      Decision decision, Pd pd) {

    public record Weights(BigDecimal history, BigDecimal purpose, BigDecimal age, BigDecimal city, BigDecimal sex) {

        public BigDecimal total() {
            return history.add(purpose).add(age).add(city).add(sex);
        }
    }

    /** Возраст в полных годах; диапазоны включительные: {from: 40, to: 60} содержит и 40, и 60. */
    public record Age(int min, int max, List<AgeBand> bands) {
    }

    public record AgeBand(int from, int to, BigDecimal score) {
    }

    /** Ключи cities — нормализованные названия (нижний регистр, ё → е). */
    public record City(String homeCountry, Map<String, BigDecimal> cities, BigDecimal otherHomeCountry,
                       BigDecimal foreign) {
    }

    public record History(BigDecimal noHistory, BigDecimal base, BigDecimal perRepaidOnTime,
                          BigDecimal maxRepaidBonus, BigDecimal perRepaidLate, BigDecimal perRejectedLast12Months) {
    }

    /** points ≤ autoRejectMaxPoints → AUTO_REJECT; ≤ reviewMaxPoints → REVIEW; выше → RECOMMEND. */
    public record Decision(int autoRejectMaxPoints, int reviewMaxPoints) {
    }

    /** При score = anchorScore PD = anchorPd; каждые +doubleOddsStep score вдвое уменьшают шансы невозврата. */
    public record Pd(BigDecimal anchorScore, BigDecimal anchorPd, BigDecimal doubleOddsStep) {
    }

    /** Проверка перед сохранением новой версии: пустой список — правила корректны. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (weights == null || age == null || city == null || sex == null || history == null || decision == null
                || pd == null) {
            errors.add("all sections are required: weights, age, city, sex, history, decision, pd");
            return errors;
        }
        for (BigDecimal w : List.of(nz(weights.history()), nz(weights.purpose()), nz(weights.age()),
                nz(weights.city()), nz(weights.sex()))) {
            if (w.signum() < 0) {
                errors.add("weights must not be negative");
                break;
            }
        }
        if (weights.history() == null || weights.purpose() == null || weights.age() == null || weights.city() == null
                || weights.sex() == null || weights.total().signum() <= 0) {
            errors.add("all five weights are required and their sum must be positive");
        }
        if (age.min() < 0 || age.max() < age.min()) {
            errors.add("age: min/max are invalid");
        }
        if (age.bands() == null || age.bands().isEmpty()) {
            errors.add("age: bands are required");
        } else {
            for (int year = age.min(); year <= age.max(); year++) {
                int y = year;
                long matches = age.bands().stream().filter(b -> b.from() <= y && y <= b.to()).count();
                if (matches != 1) {
                    errors.add("age: every year from min to max must be covered by exactly one band (year " + y + ")");
                    break;
                }
            }
            age.bands().forEach(b -> checkScore(errors, "age band " + b.from() + "-" + b.to(), b.score()));
        }
        if (city.homeCountry() == null || city.homeCountry().length() != 2) {
            errors.add("city: homeCountry must be an ISO country code");
        }
        if (city.cities() != null) {
            city.cities().forEach((name, score) -> checkScore(errors, "city " + name, score));
        }
        checkScore(errors, "city.otherHomeCountry", city.otherHomeCountry());
        checkScore(errors, "city.foreign", city.foreign());
        checkScore(errors, "sex.FEMALE", sex.get("FEMALE"));
        checkScore(errors, "sex.MALE", sex.get("MALE"));
        checkScore(errors, "history.noHistory", history.noHistory());
        checkScore(errors, "history.base", history.base());
        for (BigDecimal step : List.of(nz(history.perRepaidOnTime()), nz(history.maxRepaidBonus()),
                nz(history.perRepaidLate()), nz(history.perRejectedLast12Months()))) {
            if (step.signum() < 0) {
                errors.add("history: steps are magnitudes and must not be negative");
                break;
            }
        }
        if (decision.autoRejectMaxPoints() < 0 || decision.reviewMaxPoints() < decision.autoRejectMaxPoints()
                || decision.reviewMaxPoints() > 400) {
            errors.add("decision: 0 <= autoRejectMaxPoints <= reviewMaxPoints <= 400");
        }
        if (pd.anchorScore() == null || pd.anchorPd() == null || pd.doubleOddsStep() == null
                || pd.anchorPd().signum() <= 0 || pd.anchorPd().compareTo(BigDecimal.ONE) >= 0
                || pd.doubleOddsStep().signum() <= 0) {
            errors.add("pd: anchorPd must be in (0, 1) and doubleOddsStep positive");
        }
        return errors;
    }

    private static void checkScore(List<String> errors, String name, BigDecimal score) {
        if (score == null || score.signum() < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            errors.add(name + ": score must be between 0 and 1");
        }
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
