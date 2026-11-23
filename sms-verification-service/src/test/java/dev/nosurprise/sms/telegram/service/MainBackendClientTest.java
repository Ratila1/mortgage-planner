package dev.nosurprise.sms.telegram.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MainBackendClientTest {

    private static final UUID USER = UUID.fromString("5cdd374b-2d0a-4d61-915d-76c612b493e4");

    private HttpServer server;
    private final AtomicReference<String> rawQuery = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    @BeforeEach
    void startServer() throws IOException {
        responseStatus.set(200);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/users/by-phone", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = ("{\"userId\":\"" + USER + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), responseStatus.get() == 200 ? body.length : -1);
            try (OutputStream out = exchange.getResponseBody()) {
                if (responseStatus.get() == 200) out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("плюс в номере кодируется в query при поиске пользователя")
    void encodesPlusInPhoneQuery() {
        MainBackendClient client = new MainBackendClient(baseUrl(), Duration.ofSeconds(2), "test-token");

        assertThat(client.findUserIdByPhone("+375336405030")).contains(USER);
        assertThat(rawQuery.get()).isEqualTo("phone=%2B375336405030");
    }

    @Test
    @DisplayName("404 по номеру по-прежнему означает отсутствие пользователя")
    void notFoundMeansNoUser() {
        responseStatus.set(404);
        MainBackendClient client = new MainBackendClient(baseUrl(), Duration.ofSeconds(2), "test-token");

        assertThat(client.findUserIdByPhone("+375336405030")).isEmpty();
        assertThat(rawQuery.get()).isEqualTo("phone=%2B375336405030");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
