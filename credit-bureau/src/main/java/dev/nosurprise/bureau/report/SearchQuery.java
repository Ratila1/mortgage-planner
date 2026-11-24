package dev.nosurprise.bureau.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Параметры поиска заёмщика после нормализации (CREDIT_BUREAU.md §3.1, §3.3). Чистая функция, без Spring.
 * <ul>
 *   <li>телефон: без пробелов, скобок и дефисов, формат {@code +375291234567};</li>
 *   <li>ФИО: trim, нижний регистр, «ё» → «е» — как выражения во VIEW bureau_applicant_lookup монолита.</li>
 * </ul>
 * Пришли оба параметра — совпадать должны оба; один — ищем по нему. Отчество сравнивается, только если прислали.
 */
public record SearchQuery(String phone, String lastName, String firstName, String middleName) {

    private static final String PHONE_REGEX = "^\\+\\d{10,15}$";

    public enum SearchBy { PHONE, NAME, PHONE_AND_NAME }

    /** Из сырого запроса банка. Неверная комбинация или формат → IllegalArgumentException с текстом для 400. */
    public static SearchQuery of(String rawPhone, String rawLastName, String rawFirstName, String rawMiddleName) {
        String phone = normalizePhone(rawPhone);
        String lastName = normalizeName(rawLastName);
        String firstName = normalizeName(rawFirstName);
        String middleName = normalizeName(rawMiddleName);
        if (phone != null && !phone.matches(PHONE_REGEX)) {
            throw new IllegalArgumentException("phone must be in format +375291234567");
        }
        if ((lastName == null) != (firstName == null)) {
            throw new IllegalArgumentException("lastName and firstName must be sent together");
        }
        if (middleName != null && lastName == null) {
            throw new IllegalArgumentException("middleName requires lastName and firstName");
        }
        if (phone == null && lastName == null) {
            throw new IllegalArgumentException("send phone and/or lastName + firstName");
        }
        return new SearchQuery(phone, lastName, firstName, middleName);
    }

    public SearchBy searchBy() {
        if (phone != null && lastName != null) {
            return SearchBy.PHONE_AND_NAME;
        }
        return phone != null ? SearchBy.PHONE : SearchBy.NAME;
    }

    /** Совпадает ли заявитель (уже нормализованный) с запросом — для песочницы, та же логика, что SQL. */
    public boolean matches(String applicantPhone, String applicantLastName, String applicantFirstName,
                           String applicantMiddleName) {
        boolean phoneOk = phone == null || phone.equals(applicantPhone);
        boolean nameOk = lastName == null || (lastName.equals(applicantLastName)
                && firstName.equals(applicantFirstName)
                && (middleName == null || middleName.equals(Objects.requireNonNullElse(applicantMiddleName, ""))));
        return phoneOk && nameOk;
    }

    /** sha256 нормализованных параметров — в журнал вместо самих ФИО и телефона (§6.1). */
    public String hash() {
        String canonical = String.join("|", nz(phone), nz(lastName), nz(firstName), nz(middleName));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.replaceAll("[\\s()\\-]", "");
    }

    /** Та же нормализация, что replace(lower(x), 'ё', 'е') во VIEW монолита. */
    static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
