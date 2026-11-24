package dev.nosurprise.loan.type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanTypeDataTest {

    private static LoanTypeData data(String min, String max, int minDays, int maxDays, String rate, String modifier) {
        return new LoanTypeData("T", "D", new BigDecimal(min), new BigDecimal(max), minDays, maxDays,
                new BigDecimal(rate), new BigDecimal(modifier), true, new BigDecimal("0.60"));
    }

    @Test
    @DisplayName("корректный тип проходит валидацию, включая границы 7 и 30 дней")
    void valid() {
        assertThatCode(() -> data("100", "1000", 7, 30, "1", "150").validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("срок вне 7–30, min > max, отрицательные ставка и модификатор отклоняются")
    void invalid() {
        assertThatThrownBy(() -> data("100", "1000", 6, 30, "1", "0").validate()).isInstanceOf(InvalidLoanTypeException.class);
        assertThatThrownBy(() -> data("100", "1000", 7, 31, "1", "0").validate()).isInstanceOf(InvalidLoanTypeException.class);
        assertThatThrownBy(() -> data("100", "1000", 20, 10, "1", "0").validate()).isInstanceOf(InvalidLoanTypeException.class);
        assertThatThrownBy(() -> data("2000", "1000", 7, 30, "1", "0").validate()).isInstanceOf(InvalidLoanTypeException.class);
        assertThatThrownBy(() -> data("100", "1000", 7, 30, "-1", "0").validate()).isInstanceOf(InvalidLoanTypeException.class);
        assertThatThrownBy(() -> data("100", "1000", 7, 30, "1", "-5").validate()).isInstanceOf(InvalidLoanTypeException.class);
        LoanTypeData badScore = new LoanTypeData("T", "D", new BigDecimal("100"), new BigDecimal("1000"), 7, 30,
                BigDecimal.ONE, BigDecimal.ZERO, true, new BigDecimal("1.20"));
        assertThatThrownBy(badScore::validate).isInstanceOf(InvalidLoanTypeException.class);
    }
}
