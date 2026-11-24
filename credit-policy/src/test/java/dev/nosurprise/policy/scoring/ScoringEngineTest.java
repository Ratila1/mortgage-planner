package dev.nosurprise.policy.scoring;

import dev.nosurprise.policy.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Модель из CREDIT_POLICY.md §3: стоп-факторы, факторы, классы, PD, пороги и примеры из §3.5. */
class ScoringEngineTest {

    private static final RuleSet RULES = RulesFixture.v1();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final ScoringRequested.History NO_HISTORY = ScoringRequested.History.EMPTY;

    private static ScoringRequested.Applicant applicant(int age, String sex, String country, String city) {
        return new ScoringRequested.Applicant("Иванова", "Анна", null, "+375291234567",
                TODAY.minusYears(age), sex, country, city);
    }

    private static ScoringOutcome evaluate(ScoringRequested.Applicant a, ScoringRequested.History h, String purpose) {
        return ScoringEngine.evaluate(RULES, a, h, "Лечение", Optional.of(new BigDecimal(purpose)), false, TODAY);
    }

    @Test
    void seedRulesAreValid() {
        assertThat(RULES.validate()).isEmpty();
    }

    @Test
    void exampleWomanFromMinskWithGoodHistoryIsRecommended() {
        var history = new ScoringRequested.History(3, 3, 0, 0, false, 0);
        ScoringOutcome outcome = evaluate(applicant(45, "FEMALE", "BY", "Минск"), history, "0.8");

        assertThat(outcome.status()).isEqualTo(ScoringStatus.COMPLETED);
        assertThat(outcome.score()).isEqualByComparingTo("0.7850");
        assertThat(outcome.points()).isEqualTo(314);
        assertThat(outcome.ratingClass()).isEqualTo("B1");
        assertThat(outcome.decision()).isEqualTo(ScoringDecision.RECOMMEND);
        assertThat(outcome.breakdown()).hasSize(5);
    }

    @Test
    void exampleNewManFromGomelIsReview() {
        ScoringOutcome outcome = evaluate(applicant(30, "MALE", "BY", "гомель"), NO_HISTORY, "0.6");

        assertThat(outcome.score()).isEqualByComparingTo("0.4500");
        assertThat(outcome.points()).isEqualTo(180);
        assertThat(outcome.ratingClass()).isEqualTo("C3");
        assertThat(outcome.decision()).isEqualTo(ScoringDecision.REVIEW);
    }

    @Test
    void exampleManWithLateRepaymentsIsAutoRejected() {
        var history = new ScoringRequested.History(2, 0, 2, 12, false, 0);
        ScoringOutcome outcome = evaluate(applicant(25, "MALE", "BY", "Брест"), history, "0.3");

        assertThat(outcome.score()).isEqualByComparingTo("0.2325");
        assertThat(outcome.points()).isEqualTo(93);
        assertThat(outcome.ratingClass()).isEqualTo("E1");
        assertThat(outcome.decision()).isEqualTo(ScoringDecision.AUTO_REJECT);
    }

    @ParameterizedTest(name = "возраст {0} → {1}")
    @CsvSource({"18, 0.3", "39, 0.3", "40, 0.5", "60, 0.5", "61, 0.4", "90, 0.4"})
    void ageBandsIncludeBoundaries(int age, String expected) {
        ScoringOutcome outcome = evaluate(applicant(age, "FEMALE", "BY", "Минск"), NO_HISTORY, "0.5");
        FactorScore ageFactor = outcome.breakdown().stream().filter(f -> f.factor() == Factor.AGE).findFirst()
                .orElseThrow();
        assertThat(ageFactor.score()).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "возраст {0} — стоп-фактор")
    @CsvSource({"17", "91"})
    void ageOutsideRangeIsStopFactor(int age) {
        ScoringOutcome outcome = evaluate(applicant(age, "FEMALE", "BY", "Минск"), NO_HISTORY, "0.5");
        assertThat(outcome.ratingClass()).isEqualTo("F");
        assertThat(outcome.decision()).isEqualTo(ScoringDecision.AUTO_REJECT);
        assertThat(outcome.reasons()).containsExactly("AGE_OUT_OF_RANGE");
        assertThat(outcome.points()).isNull();
        assertThat(outcome.pd()).isNull();
    }

    @Test
    void blacklistAndCurrentOverdueAreStopFactorsReportedTogether() {
        var overdue = new ScoringRequested.History(1, 0, 0, 5, true, 0);
        ScoringOutcome outcome = ScoringEngine.evaluate(RULES, applicant(45, "FEMALE", "BY", "Минск"), overdue,
                "MEDICAL", Optional.of(BigDecimal.ONE), true, TODAY);
        assertThat(outcome.reasons()).containsExactly("BLACKLISTED", "CURRENT_OVERDUE");
        assertThat(outcome.ratingClass()).isEqualTo("F");
    }

    @Test
    void stopFactorWinsOverMissingProfileData() {
        var noData = new ScoringRequested.Applicant("Иванов", "Иван", null, "+375291234567", null, null, null, null);
        ScoringOutcome outcome = ScoringEngine.evaluate(RULES, noData, NO_HISTORY, null, Optional.empty(), true, TODAY);
        assertThat(outcome.reasons()).containsExactly("BLACKLISTED");
    }

    @Test
    void missingProfileDataIsNotCalculatedAndGoesToReview() {
        var noCity = applicant(45, "FEMALE", "BY", " ");
        ScoringOutcome outcome = evaluate(noCity, NO_HISTORY, "0.5");
        assertThat(outcome.status()).isEqualTo(ScoringStatus.NOT_CALCULATED);
        assertThat(outcome.decision()).isEqualTo(ScoringDecision.REVIEW);
        assertThat(outcome.reasons()).containsExactly("MISSING_PROFILE_DATA");
    }

    @Test
    void missingPurposeScoreIsNotCalculated() {
        ScoringOutcome outcome = ScoringEngine.evaluate(RULES, applicant(45, "FEMALE", "BY", "Минск"), NO_HISTORY,
                "Последний шанс", Optional.empty(), false, TODAY);
        assertThat(outcome.status()).isEqualTo(ScoringStatus.NOT_CALCULATED);
        assertThat(outcome.reasons()).containsExactly("PURPOSE_SCORE_MISSING");
    }

    @Test
    void purposeScoreOutsideRangeIsNotCalculated() {
        ScoringOutcome outcome = ScoringEngine.evaluate(RULES, applicant(45, "FEMALE", "BY", "Минск"), NO_HISTORY,
                "На iPhone", Optional.of(new BigDecimal("1.5")), false, TODAY);
        assertThat(outcome.reasons()).containsExactly("PURPOSE_SCORE_MISSING");
    }

    @ParameterizedTest(name = "{0}, {1} → {2}")
    @CsvSource({"BY, Минск, 0.9", "by, minsk, 0.9", "BY, Гомель, 0.2", "BY, Homel, 0.2", "BY, Брест, 0.15",
            "PL, Warszawa, 0.5", "RU, Минск, 0.5"})
    void cityScores(String country, String city, String expected) {
        assertThat(ScoringEngine.cityScore(RULES.city(), country, city)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "история {0}/{1}/{2}/{3} → {4}")
    @CsvSource({
            "0, 0, 0, 0, 0.5",   // нет истории
            "3, 3, 0, 0, 0.8",   // бонус 0.3
            "9, 9, 0, 0, 0.9",   // бонус ограничен 0.4
            "1, 0, 1, 0, 0.25",  // одна просрочка
            "3, 0, 3, 0, 0.0",   // не ниже нуля
            "0, 0, 0, 2, 0.3"})  // только отказы за 12 месяцев
    void historyScore(int signed, int onTime, int late, int rejected, String expected) {
        var h = new ScoringRequested.History(signed, onTime, late, 0, false, rejected);
        assertThat(ScoringEngine.historyScore(RULES.history(), h)).isEqualByComparingTo(expected);
    }

    @ParameterizedTest(name = "score {0} → PD {1}")
    @CsvSource({"0.5, 0.100000", "0.6, 0.052632", "0.4, 0.181818", "1.0, 0.003460", "0.0, 0.780488"})
    void pdCalibrationHalvesOddsEveryStep(String score, String pd) {
        assertThat(ScoringEngine.pd(RULES.pd(), new BigDecimal(score))).isEqualByComparingTo(pd);
    }

    @ParameterizedTest(name = "{0} баллов → {1}")
    @CsvSource({"400, A1", "376, A1", "375, A2", "201, C2", "200, C3", "126, D2", "125, D3", "51, E2", "50, E3",
            "0, E3"})
    void ratingClassBoundariesFollowNationalBankScale(int points, String expected) {
        assertThat(RatingScale.classFor(points)).isEqualTo(expected);
    }

    @Test
    void decisionThresholdsSitOnClassBoundaries() {
        // 0.5 ровно — 200 баллов, C3: проходной порог «> 0.5» не пройден → REVIEW
        var history = new ScoringRequested.History(0, 0, 0, 0, false, 0);
        ScoringOutcome border = evaluate(applicant(45, "MALE", "PL", "Warszawa"), history, "0.425");
        assertThat(border.score()).isEqualByComparingTo("0.5000");
        assertThat(border.points()).isEqualTo(200);
        assertThat(border.decision()).isEqualTo(ScoringDecision.REVIEW);
    }

    @Test
    void breakdownContributionsSumToScore() {
        ScoringOutcome outcome = evaluate(applicant(45, "FEMALE", "BY", "Минск"),
                new ScoringRequested.History(3, 3, 0, 0, false, 0), "0.8");
        BigDecimal sum = outcome.breakdown().stream().map(FactorScore::contribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(outcome.score());
        assertThat(outcome.breakdown()).extracting(FactorScore::factor)
                .containsExactlyElementsOf(List.of(Factor.HISTORY, Factor.PURPOSE, Factor.AGE, Factor.CITY, Factor.SEX));
    }
}
