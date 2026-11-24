package dev.nosurprise.bureau.report;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Запрос истории (CREDIT_BUREAU.md §7.1). Нужен телефон и/или пара «фамилия + имя»; оба параметра — точнее.
 * Параметры — в теле, а не в URL: не попадают в access-логи и историю прокси.
 */
@Schema(description = "Телефон и/или ФИО заёмщика. Пришли оба — совпасть должны оба; один — ищем по нему")
public record CreditReportRequest(
        @Schema(description = "Телефон в международном формате; пробелы, скобки и дефисы допустимы",
                example = "+375290000001")
        @Size(max = 32) String phone,
        @Schema(description = "Фамилия; вместе с именем", example = "Иванов")
        @Size(max = 100) String lastName,
        @Schema(description = "Имя; вместе с фамилией", example = "Иван")
        @Size(max = 100) String firstName,
        @Schema(description = "Отчество; сравнивается, только если прислали")
        @Size(max = 100) String middleName) {
}
