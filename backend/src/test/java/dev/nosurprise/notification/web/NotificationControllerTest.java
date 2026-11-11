package dev.nosurprise.notification.web;

import dev.nosurprise.notification.NotificationService;
import dev.nosurprise.notification.web.dto.NotificationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@ContextConfiguration(classes = {NotificationController.class, dev.nosurprise.config.SecurityConfig.class, dev.nosurprise.config.JwtPropertiesConfig.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-0123456789abcdef0123456789",
        "app.jwt.access-ttl=15m",
        "app.jwt.refresh-ttl=30d"
})
class NotificationControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationService service;

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    @DisplayName("GET /api/notifications: 200, список своих")
    void list() throws Exception {
        when(service.list(USER_ID)).thenReturn(List.of(new NotificationResponse(
                UUID.randomUUID(), "PDF_READY", null, false, Instant.now())));

        mockMvc.perform(get("/api/notifications")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PDF_READY"));
    }

    @Test
    @DisplayName("POST /api/notifications/{id}/read: 204")
    void read() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(service).markRead(USER_ID, id);

        mockMvc.perform(post("/api/notifications/" + id + "/read")
                        .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject(USER_ID.toString()))))
                .andExpect(status().isNoContent());
    }
}