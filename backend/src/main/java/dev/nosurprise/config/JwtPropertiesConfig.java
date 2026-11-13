package dev.nosurprise.config;

import dev.nosurprise.auth.service.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрация свойств JWT (§7): app.jwt из application.yml, секрет — только env JWT_SECRET.
 * Валидация секрета (fail-fast) в компактном конструкторе JwtProperties.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtPropertiesConfig {
}
