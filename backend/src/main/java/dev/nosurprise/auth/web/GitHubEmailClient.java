package dev.nosurprise.auth.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Достаёт email пользователя GitHub. Если почта в профиле скрыта, GitHub не отдаёт её
 * в /user — тогда берём основной подтверждённый адрес из /user/emails (scope user:email).
 */
@Component
public class GitHubEmailClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubEmailClient.class);

    private final ObjectProvider<OAuth2AuthorizedClientService> authorizedClients;
    private final RestClient gitHub = RestClient.create("https://api.github.com");

    public GitHubEmailClient(ObjectProvider<OAuth2AuthorizedClientService> authorizedClients) {
        this.authorizedClients = authorizedClients;
    }

    /** Основной подтверждённый email или null, если получить его не удалось. */
    public String findPrimaryVerifiedEmail(OAuth2AuthenticationToken authentication) {
        OAuth2AuthorizedClientService service = authorizedClients.getIfAvailable();
        if (service == null) {
            return null;
        }
        OAuth2AuthorizedClient client = service.loadAuthorizedClient(
                authentication.getAuthorizedClientRegistrationId(), authentication.getName());
        if (client == null) {
            return null;
        }
        try {
            List<Map<String, Object>> emails = gitHub.get()
                    .uri("/user/emails")
                    .header("Authorization", "Bearer " + client.getAccessToken().getTokenValue())
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (emails == null) {
                return null;
            }
            return emails.stream()
                    .filter(e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
                    .map(e -> String.valueOf(e.get("email")))
                    .findFirst()
                    .orElse(null);
        } catch (RestClientException ex) {
            log.warn("GitHub /user/emails request failed", ex);
            return null;
        }
    }
}
