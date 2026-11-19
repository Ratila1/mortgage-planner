package dev.nosurprise.customer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PHONE = "+375291234567";
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final CustomerProfileRepository repository = mock(CustomerProfileRepository.class);
    private final CustomerService service = new CustomerService(repository,
            Clock.fixed(NOW, ZoneOffset.UTC), mock(ApplicationEventPublisher.class));

    @Test
    @DisplayName("уникальный номер возвращает владельца")
    void uniquePhoneReturnsOwner() {
        CustomerProfile profile = mock(CustomerProfile.class);
        when(profile.getUserId()).thenReturn(USER);
        when(repository.findAllByPhone(PHONE)).thenReturn(List.of(profile));

        assertThat(service.findUserIdByPhone(PHONE)).isEqualTo(Optional.of(USER));
    }

    @Test
    @DisplayName("неизвестный номер возвращает пустой результат")
    void unknownPhoneReturnsEmpty() {
        when(repository.findAllByPhone(PHONE)).thenReturn(List.of());

        assertThat(service.findUserIdByPhone(PHONE)).isEmpty();
    }

    @Test
    @DisplayName("одинаковый номер у нескольких профилей — ошибка, а не произвольный выбор")
    void duplicatedPhoneIsRejected() {
        when(repository.findAllByPhone(PHONE)).thenReturn(List.of(
                mock(CustomerProfile.class), mock(CustomerProfile.class)));

        assertThatThrownBy(() -> service.findUserIdByPhone(PHONE))
                .isInstanceOf(AmbiguousPhoneException.class);
    }
}
