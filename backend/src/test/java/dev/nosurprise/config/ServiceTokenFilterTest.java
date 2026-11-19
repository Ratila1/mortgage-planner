package dev.nosurprise.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ServiceTokenFilterTest {

    private final AuthenticationEntryPoint entryPoint = mock(AuthenticationEntryPoint.class);
    private final ServiceTokenFilter filter = new ServiceTokenFilter("secret", entryPoint);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("валидный service-token пускает запрос дальше")
    void validTokenPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ServiceTokenFilter.HEADER, "secret");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_SERVICE");
        verifyNoInteractions(entryPoint);
    }

    @Test
    @DisplayName("отсутствующий token отклоняется с 401")
    void missingTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        verify(entryPoint).commence(any(), any(), any());
    }

    @Test
    @DisplayName("неверный token отклоняется с 401")
    void invalidTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ServiceTokenFilter.HEADER, "wrong");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNull();
        verify(entryPoint).commence(any(), any(), any());
    }
}
