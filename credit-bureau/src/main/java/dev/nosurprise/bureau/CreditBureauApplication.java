package dev.nosurprise.bureau;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Кредитное бюро (CREDIT_BUREAU.md, часть B): отдаёт внешним банкам кредитную историю клиента
 * по телефону и/или ФИО заёмщика. Отдельный сервис: внешний трафик не попадает в монолит.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CreditBureauApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreditBureauApplication.class, args);
    }
}
