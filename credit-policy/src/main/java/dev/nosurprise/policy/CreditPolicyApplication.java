package dev.nosurprise.policy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Сервис кредитной политики: скоринг заявок, правила, цели, чёрный список (CREDIT_POLICY.md). */
@SpringBootApplication
public class CreditPolicyApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreditPolicyApplication.class, args);
    }
}
