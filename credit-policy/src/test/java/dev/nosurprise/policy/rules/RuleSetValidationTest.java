package dev.nosurprise.policy.rules;

import dev.nosurprise.policy.scoring.RulesFixture;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSetValidationTest {

    private static final RuleSet V1 = RulesFixture.v1();

    @Test
    void ageBandsMustCoverEveryYearExactlyOnce() {
        RuleSet gap = new RuleSet(V1.weights(), new RuleSet.Age(18, 90, List.of(
                new RuleSet.AgeBand(18, 39, new BigDecimal("0.3")),
                new RuleSet.AgeBand(41, 90, new BigDecimal("0.5")))),
                V1.city(), V1.sex(), V1.history(), V1.decision(), V1.pd());
        assertThat(gap.validate()).anyMatch(e -> e.contains("year 40"));
    }

    @Test
    void scoresMustBeWithinZeroAndOne() {
        RuleSet bad = new RuleSet(V1.weights(), V1.age(),
                new RuleSet.City("BY", V1.city().cities(), new BigDecimal("1.5"), V1.city().foreign()),
                V1.sex(), V1.history(), V1.decision(), V1.pd());
        assertThat(bad.validate()).anyMatch(e -> e.contains("city.otherHomeCountry"));
    }

    @Test
    void decisionThresholdsMustBeOrdered() {
        RuleSet bad = new RuleSet(V1.weights(), V1.age(), V1.city(), V1.sex(), V1.history(),
                new RuleSet.Decision(250, 200), V1.pd());
        assertThat(bad.validate()).anyMatch(e -> e.startsWith("decision"));
    }

    @Test
    void strictTaskThresholdIsAValidRuleSet() {
        // «< 0.5 — отказ» из задания: серая зона исчезает новой версией правил, без правки кода.
        RuleSet strict = new RuleSet(V1.weights(), V1.age(), V1.city(), V1.sex(), V1.history(),
                new RuleSet.Decision(200, 200), V1.pd());
        assertThat(strict.validate()).isEmpty();
    }
}
