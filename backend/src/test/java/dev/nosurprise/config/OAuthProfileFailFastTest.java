package dev.nosurprise.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthProfileFailFastTest {

    @Test
    @DisplayName("профиль oauth без client-id падает при старте")
    void oauthProfileFailsWithoutSecrets() {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=oauth")
                .withUserConfiguration(SecurityConfig.class, JwtPropertiesConfig.class);
        runner.run(context -> assertThat(context).hasFailed());
    }
}