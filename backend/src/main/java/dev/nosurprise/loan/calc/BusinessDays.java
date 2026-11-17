package dev.nosurprise.loan.calc;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Рабочие дни для срока одобренного предложения (MICROLOANS.md §3.8): пн–пт в часовом поясе бизнеса,
 * праздники не учитываются. Считается каждый следующий пн–пт после дня решения, время суток сохраняется.
 * Пример: пятница 15:00 + 5 → следующая пятница 15:00; суббота 15:00 + 5 → следующая пятница 15:00.
 * Расчёт в поясе бизнеса, а не в UTC: иначе на границе суток срок сдвигается на день.
 */
public final class BusinessDays {

    private BusinessDays() {
    }

    public static Instant plus(Instant start, int businessDays, ZoneId zone) {
        ZonedDateTime cursor = start.atZone(zone);
        int added = 0;
        while (added < businessDays) {
            cursor = cursor.plusDays(1);
            DayOfWeek day = cursor.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return cursor.toInstant();
    }
}
