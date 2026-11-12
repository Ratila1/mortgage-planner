package dev.nosurprise.auth.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * GET /api/auth/oauth/providers — какие кнопки входа через соцсети показывать на фронте.
 * Пустой список, если бэкенд запущен без профиля oauth.
 */
@RestController
public class OAuthProvidersController {

    private final ObjectProvider<ClientRegistrationRepository> registrations;

    public OAuthProvidersController(ObjectProvider<ClientRegistrationRepository> registrations) {
        this.registrations = registrations;
    }

    @GetMapping("/api/auth/oauth/providers")
    List<String> providers() {
        List<String> result = new ArrayList<>();
        if (registrations.getIfAvailable() instanceof Iterable<?> all) {
            for (Object registration : all) {
                result.add(((ClientRegistration) registration).getRegistrationId());
            }
        }
        return result;
    }
}
