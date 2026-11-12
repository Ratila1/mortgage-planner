package dev.nosurprise.integration.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP-клиент integration-тестов: ходит напрямую в поднятый backend на случайном порту
 * (не через nginx/frontend), не бросает исключения на 4xx/5xx и хранит cookie
 * (нужно для auth flow: refresh_token, logout).
 */
public class ApiClient {

    private final RestClient client;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public ApiClient(int port) {
        this.client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();
    }

    public ResponseEntity<String> get(String path) {
        return send(HttpMethod.GET, path, null, null);
    }

    public ResponseEntity<String> get(String path, String bearer) {
        return send(HttpMethod.GET, path, null, bearer);
    }

    public ResponseEntity<String> post(String path) {
        return send(HttpMethod.POST, path, null, null);
    }

    /** POST без тела, но с bearer-токеном (например, запуск экспорта PDF). */
    public ResponseEntity<String> postEmpty(String path, String bearer) {
        return send(HttpMethod.POST, path, null, bearer);
    }

    /** GET бинарного ответа (PDF): тело не декодируется в UTF-8. */
    public ResponseEntity<byte[]> getBytes(String path, String bearer) {
        ResponseEntity<byte[]> response = client.method(HttpMethod.GET)
                .uri(path)
                .headers(headers -> {
                    if (!cookies.isEmpty()) {
                        headers.set(HttpHeaders.COOKIE, cookieHeader());
                    }
                    headers.setBearerAuth(bearer);
                })
                .exchange((req, res) -> {
                    var stream = res.getBody();
                    byte[] body = stream == null ? new byte[0] : stream.readAllBytes();
                    return ResponseEntity.status(res.getStatusCode())
                            .headers(res.getHeaders())
                            .body(body);
                });
        storeCookies(response.getHeaders());
        return response;
    }

    public ResponseEntity<String> postJson(String path, String json) {
        return send(HttpMethod.POST, path, json, null);
    }

    public ResponseEntity<String> postJson(String path, String json, String bearer) {
        return send(HttpMethod.POST, path, json, bearer);
    }

    public ResponseEntity<String> putJson(String path, String json, String bearer) {
        return send(HttpMethod.PUT, path, json, bearer);
    }

    public ResponseEntity<String> delete(String path, String bearer) {
        return send(HttpMethod.DELETE, path, null, bearer);
    }

    public ResponseEntity<String> delete(String path) {
        return send(HttpMethod.DELETE, path, null, null);
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    public void removeCookie(String name) {
        cookies.remove(name);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String json, String bearer) {
        RestClient.RequestBodySpec request = client.method(method)
                .uri(path)
                .headers(headers -> {
                    if (!cookies.isEmpty()) {
                        headers.set(HttpHeaders.COOKIE, cookieHeader());
                    }
                    if (bearer != null) {
                        headers.setBearerAuth(bearer);
                    }
                });
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(json);
        }

        ResponseEntity<String> response = request.exchange((req, res) -> {
            String body = "";
            var stream = res.getBody();
            if (stream != null) {
                byte[] bytes = stream.readAllBytes();
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            return ResponseEntity.status(res.getStatusCode())
                    .headers(res.getHeaders())
                    .body(body);
        });

        storeCookies(response.getHeaders());
        return response;
    }

    private String cookieHeader() {
        StringBuilder header = new StringBuilder();
        cookies.forEach((name, value) -> {
            if (!header.isEmpty()) {
                header.append("; ");
            }
            header.append(name).append('=').append(value);
        });
        return header.toString();
    }

    /** Set-Cookie: сохраняем только пары name=value; пустое значение (Max-Age=0) — удаляем. */
    private void storeCookies(HttpHeaders headers) {
        var setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) {
            return;
        }
        for (String header : setCookies) {
            String pair = header.split(";", 2)[0].trim();
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String name = pair.substring(0, separator);
            String value = pair.substring(separator + 1);
            if (value.isEmpty()) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }
}
