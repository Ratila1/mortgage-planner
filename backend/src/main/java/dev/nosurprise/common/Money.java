package dev.nosurprise.common;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Единая политика денег: только BigDecimal, округление задано явно.
 * Промежуточные вычисления — в MC (высокая точность), наружу — scale 2 HALF_UP.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    private Money() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }
}
