package dev.nosurprise.common.sms;

import com.vonage.client.VonageClient;
import com.vonage.client.messages.MessageRequest;
import com.vonage.client.messages.MessageResponse;
import com.vonage.client.messages.MessagesClient;
import com.vonage.client.messages.sms.SmsTextRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VonageSmsSenderTest {

    private final VonageClient client = mock(VonageClient.class);
    private final MessagesClient messages = mock(MessagesClient.class);
    private final VonageSmsSender sender =
            new VonageSmsSender(client, new VonageProperties("key", "secret", "Vonage APIs"));

    @Test
    @DisplayName("номер уходит в Vonage без «+», текст — как есть")
    void sendsSmsTextRequest() {
        MessageResponse response = mock(MessageResponse.class);
        when(response.getMessageUuid()).thenReturn(UUID.randomUUID());
        when(client.getMessagesClient()).thenReturn(messages);
        when(messages.sendMessage(any())).thenReturn(response);

        sender.send("+375291234567", "Код: 123456");

        ArgumentCaptor<MessageRequest> request = ArgumentCaptor.forClass(MessageRequest.class);
        verify(messages).sendMessage(request.capture());
        assertThat(request.getValue()).isInstanceOf(SmsTextRequest.class);
        assertThat(request.getValue().getTo()).isEqualTo("375291234567");
    }

    @Test
    @DisplayName("ошибка Vonage → SmsDeliveryException, номер в сообщении замаскирован")
    void errorWrapped() {
        when(client.getMessagesClient()).thenReturn(messages);
        when(messages.sendMessage(any())).thenThrow(new IllegalStateException("invalid to 375291234567"));

        assertThatThrownBy(() -> sender.send("+375291234567", "Код: 123456"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageNotContaining("375291234567");
    }

    @Test
    @DisplayName("provider=vonage без ключей — приложение не стартует")
    void failFastWithoutCredentials() {
        assertThatThrownBy(() -> new VonageProperties("", "", null).validate())
                .isInstanceOf(IllegalStateException.class);
    }
}
