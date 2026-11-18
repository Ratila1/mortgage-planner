package dev.nosurprise.common;

/** Ограничение параметров страницы: отрицательная страница и огромный size не доходят до БД. */
public final class Paging {

    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    public static int page(int page) {
        return Math.max(page, 0);
    }

    public static int size(int size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }
}
