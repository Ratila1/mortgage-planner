package dev.nosurprise.customer;

import dev.nosurprise.telegram.event.PhoneChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Публичный сервис профиля клиента (MICROLOANS.md §4.2). Профиль создаётся лениво, при первом сохранении.
 */
@Service
public class CustomerService {

    private final CustomerProfileRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public CustomerService(CustomerProfileRepository repository, Clock clock, ApplicationEventPublisher events) {
        this.repository = repository;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Optional<ProfileView> findProfile(UUID userId) {
        return repository.findById(userId).map(ProfileView::from);
    }

    @Transactional(readOnly = true)
    public List<ProfileView> findProfiles(Collection<UUID> userIds) {
        return repository.findAllById(userIds).stream().map(ProfileView::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findUserIdByPhone(String phone) {
        if (phone == null || phone.isBlank()) return Optional.empty();
        List<CustomerProfile> profiles = repository.findAllByPhone(phone.trim());
        // The phone column is not unique. Never bind to an arbitrary account when it is shared.
        if (profiles.size() > 1) throw new AmbiguousPhoneException(phone.trim());
        return profiles.stream().findFirst().map(CustomerProfile::getUserId);
    }

    /**
     * Создаёт или обновляет профиль. Вызывается и из ЛК, и из подачи заявки: во втором случае
     * это часть транзакции подачи (§4.8), поэтому REQUIRED, а не отдельная транзакция.
     */
    @Transactional
    public ProfileView saveProfile(UUID userId, ProfileData data) {
        CustomerProfile profile = repository.findById(userId)
                .orElseGet(() -> new CustomerProfile(userId, clock.instant()));
        String previousPhone = profile.getPhone();
        profile.apply(data, clock.instant());
        ProfileView saved = ProfileView.from(repository.save(profile));
        if (!java.util.Objects.equals(previousPhone, data.phone())) {
            events.publishEvent(new PhoneChangedEvent(userId, previousPhone, data.phone()));
        }
        return saved;
    }

    /** Профиль заполнен полностью — иначе ProfileIncompleteException. */
    @Transactional(readOnly = true)
    public ProfileView requireCompleteProfile(UUID userId) {
        return findProfile(userId).orElseThrow(ProfileIncompleteException::new);
    }

    @Transactional
    public void markPhoneVerified(UUID userId, String phone) {
        CustomerProfile profile = repository.findById(userId).orElseThrow(ProfileIncompleteException::new);
        // Номер мог поменяться, пока код шёл: подтверждаем только тот номер, на который код отправлен.
        if (!phone.equals(profile.getPhone())) {
            throw new dev.nosurprise.common.otp.CodeInvalidException("phone has changed since the code was sent");
        }
        profile.markPhoneVerified(clock.instant());
        repository.save(profile);
    }
}
