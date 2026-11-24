package dev.nosurprise.policy.scoring;

import java.util.List;

/**
 * Классы рейтинга по скорбаллу 0–400 (CREDIT_POLICY.md §3.4), шаг 25 — как в кредитном отчёте Нацбанка:
 * класс получает скорбалл строго больше нижней границы. F — стоп-фактор, скорбалла нет.
 */
public final class RatingScale {

    public static final String STOP_CLASS = "F";
    public static final int MAX_POINTS = 400;

    private record Step(int above, String ratingClass) {
    }

    private static final List<Step> STEPS = List.of(
            new Step(375, "A1"), new Step(350, "A2"), new Step(325, "A3"),
            new Step(300, "B1"), new Step(275, "B2"), new Step(250, "B3"),
            new Step(225, "C1"), new Step(200, "C2"), new Step(175, "C3"),
            new Step(150, "D1"), new Step(125, "D2"), new Step(100, "D3"),
            new Step(75, "E1"), new Step(50, "E2"));

    private RatingScale() {
    }

    public static String classFor(int points) {
        for (Step step : STEPS) {
            if (points > step.above()) {
                return step.ratingClass();
            }
        }
        return "E3";
    }
}
