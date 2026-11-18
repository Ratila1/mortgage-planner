package dev.nosurprise.notificationservice.service;

import dev.nosurprise.notificationservice.domain.TelegramBinding;
import dev.nosurprise.notificationservice.domain.TelegramBindingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class BindingService {
    private final TelegramBindingRepository repository;
    private final Clock clock;

    public BindingService(TelegramBindingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public TelegramBinding link(UUID userId, String phone, Long telegramId, String username) {
        Instant now = clock.instant();
        Optional<TelegramBinding> existing = repository.findById(userId);
        if (existing.isPresent()) {
            TelegramBinding b = existing.get();
            b.setPhone(phone);
            // telegram_id не перезаписываем, если уже привязан к другому — безопасность
            if (b.getTelegramId() == null) b = new TelegramBinding(userId, phone, telegramId, username, now);
            b.setUsername(username);
            b.setUpdatedAt(now);
            return repository.save(b);
        }
        return repository.save(new TelegramBinding(userId, phone, telegramId, username, now));
    }

    @Transactional(readOnly = true)
    public Optional<TelegramBinding> findByPhone(String phone) {
        return repository.findByPhone(phone);
    }

    @Transactional(readOnly = true)
    public Optional<TelegramBinding> findByUser(UUID userId) {
        return repository.findById(userId);
    }

    @Transactional
    public void updatePhone(UUID userId, String newPhone) {
        repository.findById(userId).ifPresent(b -> {
            b.setPhone(newPhone);
            b.setUpdatedAt(clock.instant());
            repository.save(b);
        });
    }

    @Transactional
    public void unlink(UUID userId) {
        repository.deleteById(userId);
    }
}