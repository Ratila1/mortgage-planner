package dev.nosurprise.smsverificationservice.telegram.service;

import dev.nosurprise.smsverificationservice.api.error.ConflictException;
import dev.nosurprise.smsverificationservice.service.PhoneNormalizer;
import dev.nosurprise.smsverificationservice.telegram.domain.TelegramBinding;
import dev.nosurprise.smsverificationservice.telegram.domain.TelegramBindingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BindingServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final TelegramBindingRepository repository = mock(TelegramBindingRepository.class);
    private final MainBackendClient backend = mock(MainBackendClient.class);
    private final TelegramBotService bot = mock(TelegramBotService.class);
    private final TelegramEventPublisher events = mock(TelegramEventPublisher.class);
    private final PhoneNormalizer normalizer = mock(PhoneNormalizer.class);
    private final BindingService service = new BindingService(repository,
            Clock.fixed(NOW, ZoneOffset.UTC), backend, bot, events, normalizer);

    @Test
    @DisplayName("новый Telegram-аккаунт привязывается к пользователю")
    void linksNewAccount() {
        when(repository.findById(USER)).thenReturn(Optional.empty());
        when(repository.save(any(TelegramBinding.class))).thenAnswer(inv -> inv.getArgument(0));

        TelegramBinding binding = service.link(USER, "+375291234567", 42L, "ivan");

        assertThat(binding.getUserId()).isEqualTo(USER);
        assertThat(binding.getTelegramId()).isEqualTo(42L);
        assertThat(binding.getLinkedAt()).isEqualTo(NOW);
        verify(events).telegramLinked(USER, 42L, "+375291234567");
    }

    @Test
    @DisplayName("неоднозначный номер не привязывается, а объясняет причину")
    void ambiguousPhoneIsNotBound() {
        when(normalizer.toE164("+375291234567")).thenReturn("+375291234567");
        when(backend.findUserIdByPhone("+375291234567")).thenThrow(new AmbiguousPhoneException());

        assertThat(service.bindContact(42L, "+375291234567", "ivan")).isFalse();

        verify(bot).sendMessage(42L, "Несколько аккаунтов привязаны к этому номеру. Обратитесь в поддержку.");
        verify(repository, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("один Telegram-аккаунт нельзя привязать к двум пользователям")
    void rejectsTelegramAccountLinkedToAnotherUser() {
        when(repository.findByTelegramId(42L)).thenReturn(Optional.of(
                new TelegramBinding(OTHER, "+375291234567", 42L, "ivan", NOW)));

        assertThatThrownBy(() -> service.link(USER, "+375291234567", 42L, "ivan"))
                .isInstanceOf(ConflictException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("повторная привязка переносит код на новый Telegram-аккаунт")
    void relinkUpdatesTelegramId() {
        TelegramBinding binding = new TelegramBinding(USER, "+375291111111", 42L, "ivan", NOW);
        when(repository.findById(USER)).thenReturn(Optional.of(binding));
        when(repository.findByTelegramId(99L)).thenReturn(Optional.empty());
        when(repository.save(any(TelegramBinding.class))).thenAnswer(inv -> inv.getArgument(0));

        TelegramBinding saved = service.link(USER, "+375292222222", 99L, "ivan2");

        assertThat(saved.getTelegramId()).isEqualTo(99L);
        assertThat(saved.getPhone()).isEqualTo("+375292222222");
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        verify(events).telegramLinked(USER, 99L, "+375292222222");
    }

    @Test
    @DisplayName("смена номера обновляет привязку")
    void updatesPhone() {
        TelegramBinding binding = new TelegramBinding(USER, "+375291111111", 42L, "ivan", NOW);
        when(repository.findById(USER)).thenReturn(Optional.of(binding));

        service.updatePhone(USER, "+375292222222");

        assertThat(binding.getPhone()).isEqualTo("+375292222222");
        assertThat(binding.getUpdatedAt()).isEqualTo(NOW);
    }
}
