package dev.nosurprise.autotests.support

import java.util.concurrent.ThreadLocalRandom

object Phones {

    /**
     * Валидный белорусский мобильный: +375 29 XXXXXXX. Уникален на каждый тест-метод:
     * лимит сервиса считается на номер (3/мин), иначе соседний кейс получал бы 429.
     */
    fun unique(): String =
        "+37529" + "%07d".format(ThreadLocalRandom.current().nextInt(1_000_000, 10_000_000))
}
