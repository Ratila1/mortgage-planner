package dev.nosurprise.sms.telegram.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramWebhookSecretFilterTest {

    private static final String SECRET = "random-secret-from-env";

    @Test
    @DisplayName("запрос с верным secret-token проходит дальше")
    void validSecretPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/telegram/webhook");
        request.addHeader(TelegramWebhookSecretFilter.HEADER, SECRET);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TelegramWebhookSecretFilter(SECRET).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("запрос без secret-token отклоняется с 401")
    void missingSecretIsRejected() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TelegramWebhookSecretFilter(SECRET)
                .doFilter(new MockHttpServletRequest("POST", "/telegram/webhook"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("неверный secret-token отклоняется с 401")
    void wrongSecretIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/telegram/webhook");
        request.addHeader(TelegramWebhookSecretFilter.HEADER, "forged");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TelegramWebhookSecretFilter(SECRET).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("незаданный секрет отклоняет любые запросы")
    void emptyConfiguredSecretFailsClosed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/telegram/webhook");
        request.addHeader(TelegramWebhookSecretFilter.HEADER, SECRET);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TelegramWebhookSecretFilter("").doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
