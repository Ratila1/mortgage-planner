package dev.nosurprise.policy.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI: /credit-policy/swagger-ui.html. Authorize — access-токен админа монолита (POST /api/auth/login). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI creditPolicyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Кредитная политика «Тмыв денег»")
                        .version("v1")
                        .description("""
                                Скоринг заявок на микрозайм: правила, справочник целей, чёрный список и журнал расчётов.
                                Сам расчёт запускается событием loan.scoring.requested из монолита (RabbitMQ);
                                здесь — админский API. Нужен access-токен пользователя с ролью ADMIN."""))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
