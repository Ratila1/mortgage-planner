package dev.nosurprise.policy.common;

import java.util.Locale;

/** Нормализация ФИО и городов для сравнения: обрезка пробелов, нижний регистр, ё → е. */
public final class Names {

    private Names() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
