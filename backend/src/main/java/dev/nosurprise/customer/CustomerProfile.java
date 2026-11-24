package dev.nosurprise.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Профиль клиента: бизнес-данные человека (MICROLOANS.md §4.2). Отделён от users, который отвечает
 * только за безопасность. Общий первичный ключ user_id = users.id; email здесь не хранится,
 * чтобы две копии не разъехались.
 */
@Entity
@Table(name = "customer_profile")
public class CustomerProfile {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "middle_name")
    private String middleName;

    @Column(nullable = false)
    private String phone;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "monthly_income", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyIncome;

    /** Данные для скоринга (CREDIT_POLICY.md §4). У профилей, созданных до V17, пустые. */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    private Sex sex;

    /** ISO-код страны: BY, PL… */
    private String country;

    private String city;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected CustomerProfile() {
    }

    public CustomerProfile(UUID userId, Instant now) {
        this.userId = userId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Применяет данные формы. Смена номера сбрасывает подтверждение: иначе код подписи ушёл бы
     * на номер, который клиент не подтверждал (§3.3, §6).
     */
    void apply(ProfileData data, Instant now) {
        if (phone != null && !Objects.equals(phone, data.phone())) {
            phoneVerifiedAt = null;
        }
        lastName = data.lastName();
        firstName = data.firstName();
        middleName = data.middleName();
        phone = data.phone();
        monthlyIncome = data.monthlyIncome();
        birthDate = data.birthDate();
        sex = data.sex();
        country = data.country();
        city = data.city();
        updatedAt = now;
    }

    void markPhoneVerified(Instant now) {
        phoneVerifiedAt = now;
        updatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getLastName() {
        return lastName;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public String getPhone() {
        return phone;
    }

    public Instant getPhoneVerifiedAt() {
        return phoneVerifiedAt;
    }

    public BigDecimal getMonthlyIncome() {
        return monthlyIncome;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public Sex getSex() {
        return sex;
    }

    public String getCountry() {
        return country;
    }

    public String getCity() {
        return city;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
