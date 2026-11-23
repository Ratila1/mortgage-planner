package dev.nosurprise.sms.telegram.messaging;

import dev.nosurprise.sms.telegram.service.BindingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class UserSyncConsumerTest {

    private static final UUID USER = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private final BindingService bindings = mock(BindingService.class);
    private final Message message = mock(Message.class);
    private final UserSyncConsumer consumer = new UserSyncConsumer(bindings);

    private static Map<String, Object> event(Object... pairs) {
        Map<String, Object> event = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            event.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return event;
    }

    @Test
    @DisplayName("user.phone.changed переписывает телефон привязки")
    void phoneChangedUpdatesBinding() {
        consumer.handle(event("userId", USER.toString(), "newPhone", "+375291234567"), message, "user.phone.changed");

        verify(bindings).updatePhone(USER, "+375291234567");
    }

    @Test
    @DisplayName("user.blocked отвязывает Telegram от пользователя")
    void blockedUnlinksBinding() {
        consumer.handle(event("userId", USER.toString()), message, "user.blocked");

        verify(bindings).unlink(USER);
    }

    @Test
    @DisplayName("событие без валидного userId пропускается целиком")
    void invalidUserIdIsIgnored() {
        consumer.handle(event("userId", "not-a-uuid"), message, "user.phone.changed");

        verifyNoInteractions(bindings);
    }

    @Test
    @DisplayName("пустой newPhone не затирает сохранённый номер")
    void blankNewPhoneKeepsBinding() {
        consumer.handle(event("userId", USER.toString(), "newPhone", "  "), message, "user.phone.changed");

        verify(bindings, never()).updatePhone(any(), anyString());
    }

    @Test
    @DisplayName("неизвестный тип события не трогает привязку")
    void unknownTypeIsIgnored() {
        consumer.handle(event("userId", USER.toString()), message, "user.deleted");

        verifyNoInteractions(bindings);
    }

    @Test
    @DisplayName("без routing key тип берётся из поля eventType")
    void fallsBackToEventTypeField() {
        consumer.handle(event("userId", USER.toString(), "eventType", "user.blocked"), message, null);

        verify(bindings).unlink(USER);
    }
}
