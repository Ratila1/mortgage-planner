package dev.nosurprise.customer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public CustomerService(CustomerProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<ProfileView> findProfile(UUID userId) {
        return repository.findById(userId).map(ProfileView::from);
    }

    @Transactional(readOnly = true)
    public List<ProfileView> findProfiles(Collection<UUID> userIds) {
        return repository.findAllById(userIds).stream().map(ProfileView::from).toList();
    }

    /**
     * Создаёт или обновляет профиль. Вызывается и из ЛК, и из подачи заявки: во втором случае
     * это часть транзакции подачи (§4.8), поэтому REQUIRED, а не отдельная транзакция.
     */
    @Transactional
    public ProfileView saveProfile(UUID userId, ProfileData data) {
        CustomerProfile profile = repository.findById(userId)
                .orElseGet(() -> new CustomerProfile(userId, clock.instant()));
        profile.apply(data, clock.instant());
        return ProfileView.from(repository.save(profile));
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
