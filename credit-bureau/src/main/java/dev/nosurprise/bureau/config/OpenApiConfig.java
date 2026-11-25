package dev.nosurprise.bureau.config;

import dev.nosurprise.bureau.security.ApiKeyAuthenticationFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI для банков (CREDIT_BUREAU.md §7.5): /bureau/swagger-ui.html. Кнопка Authorize — ключ X-Api-Key,
 * запросы можно отправлять прямо из браузера. Две группы: provider (публичная) и admin (нужен X-Admin-Key).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI bureauOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Кредитное бюро «Тмыв денег»")
                        .version("v1")
                        .description("""
                                Кредитная история заёмщиков для банков-партнёров: поиск по телефону и/или ФИО.

                                Доступ — по API-ключу в заголовке X-Api-Key; ключ выдаёт администратор бюро
                                после заключения договора. Лимиты запросов — на провайдера, в минуту и в сутки.
                                Для проб используйте ключ песочницы: она отвечает только по фиктивным заёмщикам
                                (+375290000001, +375290000002, «Тестов Тест»)."""))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(ApiKeyAuthenticationFilter.API_KEY_HEADER)
                                .description("Ключ провайдера: nsb_<prefix>_<secret>"))
                        .addSecuritySchemes("adminKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(ApiKeyAuthenticationFilter.ADMIN_KEY_HEADER)
                                .description("Ключ администратора бюро (BUREAU_ADMIN_KEY)")));
    }

    @Bean
    GroupedOpenApi providerApi() {
        return GroupedOpenApi.builder().group("provider").displayName("API для банков")
                .pathsToMatch("/api/v1/**").build();
    }

    @Bean
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin").displayName("Админ бюро")
                .pathsToMatch("/api/admin/**").build();
    }
}
