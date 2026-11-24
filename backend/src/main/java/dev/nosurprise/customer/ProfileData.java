package dev.nosurprise.customer;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Данные формы профиля: ФИО, телефон, месячная зарплата в BYN и данные для скоринга (CREDIT_POLICY.md §4). */
public record ProfileData(String lastName, String firstName, String middleName, String phone,
                          BigDecimal monthlyIncome, LocalDate birthDate, Sex sex, String country, String city) {
}
