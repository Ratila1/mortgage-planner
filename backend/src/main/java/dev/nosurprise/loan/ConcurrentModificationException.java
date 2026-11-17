package dev.nosurprise.loan;

/**
 * Клиент прислал устаревшую version (кто-то уже изменил запись) → 409 CONCURRENT_MODIFICATION (§7.7).
 * Явная проверка даёт понятную ошибку сразу; @Version страхует на уровне БД при гонке.
 */
public class ConcurrentModificationException extends RuntimeException {

    public ConcurrentModificationException() {
        super("record was modified by someone else, reload and try again");
    }

    public static void check(Long current, long expected) {
        if (current == null || current != expected) {
            throw new ConcurrentModificationException();
        }
    }
}
