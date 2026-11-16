package dev.nosurprise.common.problem;

import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Единый формат ProblemDetail (RFC 7807, §9) для ответов, которые пишутся не через
 * @RestControllerAdvice (фильтры, entry point'ы): свойства (code и др.) — на верхнем
 * уровне JSON, как у GlobalExceptionHandler (Spring MVC разворачивает properties при
 * ответе через конвертер; при прямой Jackson-сериализации ProblemDetail они бы ушли
 * во вложенный объект properties). Значения кладутся в Map — без конкатенации строк,
 * так что кавычки/управляющие символы экранирует Jackson.
 */
public final class ProblemJson {

    private ProblemJson() {
    }

    public static void write(ObjectMapper objectMapper, jakarta.servlet.http.HttpServletResponse response,
                             HttpStatus status, String detail, Map<String, ?> properties) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        if (properties != null) {
            body.putAll(properties);
        }
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
