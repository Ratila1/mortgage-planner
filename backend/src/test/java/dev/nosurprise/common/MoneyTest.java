package dev.nosurprise.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void roundsHalfUpToTwoDecimals() {
        assertEquals(new BigDecimal("10.01"), Money.round(new BigDecimal("10.005")));
        assertEquals(new BigDecimal("10.00"), Money.round(new BigDecimal("10.004")));
    }
}
