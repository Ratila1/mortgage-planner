package dev.nosurprise.sms.telegram.service;

import dev.nosurprise.sms.api.error.ConflictException;
import dev.nosurprise.sms.api.error.ValidationException;
import dev.nosurprise.sms.service.PhoneNormalizer;
import dev.nosurprise.sms.telegram.domain.TelegramBinding;
import dev.nosurprise.sms.telegram.domain.TelegramBindingRepository;
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
    private final MainBackendClient backend;
    private final TelegramBotService bot;
    private final TelegramEventPublisher events;
    private final PhoneNormalizer phoneNormalizer;

    public BindingService(TelegramBindingRepository repository, Clock clock, MainBackendClient backend,
                          TelegramBotService bot, TelegramEventPublisher events, PhoneNormalizer phoneNormalizer) {
        this.repository = repository;
        this.clock = clock;
        this.backend = backend;
        this.bot = bot;
        this.events = events;
        this.phoneNormalizer = phoneNormalizer;
    }

    /**
     * Resolves the user id by phone through the main backend, stores the binding
     * and notifies the user. Returns false when no account matches the phone or
     * when the phone is ambiguous (several accounts share it).
     */
    public boolean bindContact(long telegramId, String rawPhone, String username) {
        String phone = normalizeTelegramPhone(rawPhone);
        Optional<UUID> userId;
        try {
            userId = backend.findUserIdByPhone(phone);
        } catch (AmbiguousPhoneException ex) {
            bot.sendMessage(telegramId, "Несколько аккаунтов привязаны к этому номеру. Обратитесь в поддержку.");
            return false;
        }
        if (userId.isEmpty()) {
            bot.sendMessage(telegramId, "Аккаунт с таким номером телефона не найден.");
            return false;
        }
        link(userId.get(), phone, telegramId, username);
        bot.sendMessage(telegramId, "Аккаунт привязан. Коды подтверждения будут приходить сюда.");
        return true;
    }

    private String normalizeTelegramPhone(String rawPhone) {
        try {
            return phoneNormalizer.toE164(rawPhone);
        } catch (ValidationException ex) {
            // Telegram contacts often arrive as bare international digits, e.g. 375291234567.
            // The normalizer needs a leading '+' because no default region is configured;
            // retry only for that shape so national/garbage input stays invalid.
            String raw = rawPhone.trim();
            if (raw.matches("\\d{10,15}") && !raw.startsWith("0")) {
                return phoneNormalizer.toE164("+" + raw);
            }
            throw ex;
        }
    }

    @Transactional
    public TelegramBinding link(UUID userId, String phone, Long telegramId, String username) {
        Optional<TelegramBinding> byTelegram = repository.findByTelegramId(telegramId);
        if (byTelegram.isPresent() && !byTelegram.get().getUserId().equals(userId)) {
            throw new ConflictException("This Telegram account is already linked to another user");
        }

        Instant now = clock.instant();
        TelegramBinding binding = repository.findById(userId).orElse(null);
        if (binding == null) {
            binding = new TelegramBinding(userId, phone, telegramId, username, now);
        } else {
            // Re-linking after losing access to the old chat must move the code delivery target.
            // The findByTelegramId check above already rejected a chat owned by another user.
            binding.setPhone(phone);
            binding.setTelegramId(telegramId);
            binding.setUsername(username);
            binding.setUpdatedAt(now);
        }
        TelegramBinding saved = repository.save(binding);
        events.telegramLinked(userId, telegramId, phone);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<TelegramBinding> findByUser(UUID userId) {
        return repository.findById(userId);
    }

    @Transactional
    public void updatePhone(UUID userId, String newPhone) {
        repository.findById(userId).ifPresent(binding -> {
            binding.setPhone(newPhone);
            binding.setUpdatedAt(clock.instant());
            repository.save(binding);
        });
    }

    @Transactional
    public void unlink(UUID userId) {
        if (repository.existsById(userId)) {
            repository.deleteById(userId);
            events.telegramUnlinked(userId);
        }
    }
}
