package dev.nosurprise.common.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class TwilioSmsSenderTest {

    private static final String BASE = "https://api.twilio.test";

    private static TwilioProperties props(String apiKeySid, String from, String messagingServiceSid) {
        return new TwilioProperties(BASE, "AC123", apiKeySid, "secret", from, messagingServiceSid, Duration.ofSeconds(5));
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":secret").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("POST Messages.json: form To/From/Body, Basic auth Account SID + Auth Token")
    void sendsWithFromNumber() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/2010-04-01/Accounts/AC123/Messages.json"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic("AC123")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "To", "+375291234567", "From", "+15017122661", "Body", "Код: 123456")))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sid\":\"SM1\",\"status\":\"queued\"}"));

        new TwilioSmsSender(builder, props(null, "+15017122661", null)).send("+375291234567", "Код: 123456");

        server.verify();
    }

    @Test
    @DisplayName("API-ключ и Messaging Service: логин — SID ключа, вместо From — MessagingServiceSid")
    void sendsWithApiKeyAndMessagingService() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/2010-04-01/Accounts/AC123/Messages.json"))
                .andExpect(header("Authorization", basic("SK999")))
                .andExpect(content().formDataContains(java.util.Map.of("MessagingServiceSid", "MG777")))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"sid\":\"SM2\",\"status\":\"accepted\"}"));

        new TwilioSmsSender(builder, props("SK999", null, "MG777")).send("+375291234567", "Код: 1");

        server.verify();
    }

    @Test
    @DisplayName("ошибка Twilio → SmsDeliveryException с кодом Twilio, без номера и текста")
    void errorFromTwilio() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/2010-04-01/Accounts/AC123/Messages.json"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":21211,\"message\":\"The 'To' number +375291234567 is not valid\"}"));
        TwilioSmsSender sender = new TwilioSmsSender(builder, props(null, "+15017122661", null));

        assertThatThrownBy(() -> sender.send("+375291234567", "Код: 123456"))
                .isInstanceOf(SmsDeliveryException.class)
                .hasMessageContaining("21211")
                .hasMessageNotContaining("+375291234567");
    }

    @Test
    @DisplayName("provider=twilio без ключей — приложение не стартует")
    void failFastWithoutCredentials() {
        assertThatThrownBy(() -> new TwilioProperties(null, "", null, "", "+1", null, null).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TwilioProperties(null, "AC1", null, "s", null, null, null).validate())
                .isInstanceOf(IllegalStateException.class);
    }
}
