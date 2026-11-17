package dev.nosurprise.loan.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDaysTest {

    private static final ZoneId MINSK = ZoneId.of("Europe/Minsk");

    private static Instant minsk(String local) {
        return LocalDateTime.parse(local).atZone(MINSK).toInstant();
    }

    @Test
    @DisplayName("понедельник + 5 рабочих дней → следующий понедельник, время сохраняется")
    void monday() {
        assertThat(BusinessDays.plus(minsk("2026-11-16T10:00"), 5, MINSK)).isEqualTo(minsk("2026-11-23T10:00"));
    }

    @Test
    @DisplayName("пятница 15:00 + 5 → следующая пятница 15:00")
    void friday() {
        assertThat(BusinessDays.plus(minsk("2026-11-20T15:00"), 5, MINSK)).isEqualTo(minsk("2026-11-27T15:00"));
    }

    @Test
    @DisplayName("суббота 15:00 + 5 → следующая пятница 15:00")
    void saturday() {
        assertThat(BusinessDays.plus(minsk("2026-11-21T15:00"), 5, MINSK)).isEqualTo(minsk("2026-11-27T15:00"));
    }

    @Test
    @DisplayName("граница суток считается в поясе бизнеса: суббота 01:30 по Минску — это пятница 22:30 UTC")
    void dayBoundaryInBusinessZone() {
        // В Минске уже суббота → +5 рабочих дней = пятница 01:30. Если бы считали в UTC (пятница 22:30),
        // вышла бы пятница 22:30 UTC = суббота 01:30 по Минску — на день позже.
        assertThat(BusinessDays.plus(minsk("2026-11-21T01:30"), 5, MINSK)).isEqualTo(minsk("2026-11-27T01:30"));
    }
}
