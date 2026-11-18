package dev.nosurprise.customer;

import java.math.BigDecimal;

/** Данные формы профиля: ФИО, телефон, месячная зарплата в BYN. */
public record ProfileData(String lastName, String firstName, String middleName, String phone,
                          BigDecimal monthlyIncome) {
}
