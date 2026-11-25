package dev.nosurprise.policy.common;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * RFC 7807 с полем code, как в монолите. Фильтры безопасности работают до MVC, поэтому пишут ответ сами —
 * через этот же класс, чтобы формат ошибок был одинаковым везде.
 */
@Component
public class Problems {

    private final ObjectMapper objectMapper;

    public Problems(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static ProblemDetail of(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code.name());
        return problem;
    }

    public void write(HttpServletResponse response, HttpStatus status, ErrorCode code, String detail,
                      long retryAfterSeconds) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        response.getWriter().write(objectMapper.writeValueAsString(of(status, code, detail)));
    }
}
