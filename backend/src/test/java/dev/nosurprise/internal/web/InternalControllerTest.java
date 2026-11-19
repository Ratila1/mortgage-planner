package dev.nosurprise.internal.web;

import dev.nosurprise.common.problem.GlobalExceptionHandler;
import dev.nosurprise.config.ServiceTokenFilter;
import dev.nosurprise.customer.AmbiguousPhoneException;
import dev.nosurprise.customer.CustomerService;
import dev.nosurprise.loan.signing.SigningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalControllerTest {

    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VERIFICATION = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final CustomerService customers = mock(CustomerService.class);
    private final SigningService signing = mock(SigningService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalController(customers, signing))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new ServiceTokenFilter("secret", (request, response, ex) -> response.setStatus(401)))
            .build();

    @Test
    @DisplayName("поиск пользователя по телефону доступен с service-token")
    void findUserByPhone() throws Exception {
        when(customers.findUserIdByPhone("+375291234567")).thenReturn(Optional.of(USER));

        mvc.perform(get("/api/internal/users/by-phone")
                        .param("phone", "+375291234567")
                        .header(ServiceTokenFilter.HEADER, "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER.toString()));
    }

    @Test
    @DisplayName("неизвестный номер возвращает 404")
    void unknownPhoneReturns404() throws Exception {
        when(customers.findUserIdByPhone("+375290000000")).thenReturn(Optional.empty());

        mvc.perform(get("/api/internal/users/by-phone")
                        .param("phone", "+375290000000")
                        .header(ServiceTokenFilter.HEADER, "secret"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("неоднозначный номер возвращает 409 вместо 500")
    void ambiguousPhoneReturns409() throws Exception {
        when(customers.findUserIdByPhone("+375291234567")).thenThrow(new AmbiguousPhoneException("+375291234567"));

        mvc.perform(get("/api/internal/users/by-phone")
                        .param("phone", "+375291234567")
                        .header(ServiceTokenFilter.HEADER, "secret"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMBIGUOUS_PHONE"));
    }

    @Test
    @DisplayName("запрос без service-token отклоняется")
    void requestWithoutTokenIsRejected() throws Exception {
        mvc.perform(post("/api/internal/verifications/" + VERIFICATION + "/complete"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(signing);
    }

    @Test
    @DisplayName("завершение верификации вызывает подпись договора")
    void completeVerification() throws Exception {
        mvc.perform(post("/api/internal/verifications/" + VERIFICATION + "/complete")
                        .header(ServiceTokenFilter.HEADER, "secret"))
                .andExpect(status().isOk());

        verify(signing).completeTelegramVerification(VERIFICATION);
    }
}
