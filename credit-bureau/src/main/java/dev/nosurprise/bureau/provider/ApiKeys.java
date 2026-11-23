package dev.nosurprise.bureau.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Формат API-ключа провайдера (CREDIT_BUREAU.md §6.2): {@code nsb_<prefix>_<secret>}.
 * prefix — 8 символов [a-z0-9], по нему ищется строка в базе; secret — 32 случайных байта в base64url.
 * Хранится только sha256(secret). Медленный хеш (bcrypt) не нужен: секрет случайный и длинный, перебрать
 * его невозможно, а sha256 не тормозит каждый запрос. Чистая функция, без Spring.
 */
public final class ApiKeys {

    private static final String PREFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PREFIX_LENGTH = 8;
    private static final int SECRET_BYTES = 32;
    private static final Pattern FORMAT = Pattern.compile("^nsb_([a-z0-9]{8})_([A-Za-z0-9_-]{43})$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    /** Новый ключ: полный текст (показывается один раз) и то, что хранится в базе. */
    public static Generated generate() {
        StringBuilder prefix = new StringBuilder(PREFIX_LENGTH);
        for (int i = 0; i < PREFIX_LENGTH; i++) {
            prefix.append(PREFIX_ALPHABET.charAt(RANDOM.nextInt(PREFIX_ALPHABET.length())));
        }
        byte[] secretBytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        return new Generated("nsb_" + prefix + "_" + secret, prefix.toString(), hash(secret));
    }

    /** Разбор ключа из заголовка. Пусто — формат неверный (такой ключ даже не ищем в базе). */
    public static Optional<Parsed> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(raw.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(matcher.group(1), matcher.group(2)));
    }

    public static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Сравнение за постоянное время: по времени ответа не подобрать хеш посимвольно. */
    public static boolean matches(String secret, String storedHash) {
        return MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** Ключ песочницы задаётся в env и тоже должен быть в формате nsb_… — иначе его нельзя будет предъявить. */
    public static boolean isWellFormed(String raw) {
        return parse(raw).isPresent();
    }

    public record Generated(String plainText, String prefix, String hash) {
    }

    public record Parsed(String prefix, String secret) {
    }
}
