package dev.nosurprise.bureau.provider;

import dev.nosurprise.bureau.config.BureauProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Песочница для проб через Swagger (CREDIT_BUREAU.md §9): если провайдеров ещё нет и задан BUREAU_SANDBOX_KEY,
 * при старте создаётся провайдер-песочница с этим ключом и маленьким лимитом. Песочница видит только
 * фиктивных клиентов (SandboxReports), боевые данные ей недоступны. Как FirstAdminBootstrap в монолите.
 */
@Component
public class SandboxBootstrap implements ApplicationRunner {

    static final String SANDBOX_NAME = "Sandbox";
    private static final Logger log = LoggerFactory.getLogger(SandboxBootstrap.class);

    private final ProviderService providers;
    private final BureauProperties properties;

    public SandboxBootstrap(ProviderService providers, BureauProperties properties) {
        this.providers = providers;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String key = properties.sandboxKey();
        if (key == null || key.isBlank() || !providers.isEmpty()) {
            return;
        }
        if (!ApiKeys.isWellFormed(key)) {
            log.error("BUREAU_SANDBOX_KEY is not in format nsb_<8 chars a-z0-9>_<43 chars base64url>, sandbox not created");
            return;
        }
        Provider sandbox = providers.create(SANDBOX_NAME, true, properties.sandboxRequestsPerMinute(),
                properties.sandboxRequestsPerDay());
        providers.registerKey(sandbox.getId(), key);
        log.info("sandbox provider created: {}", sandbox.getId());
    }
}
