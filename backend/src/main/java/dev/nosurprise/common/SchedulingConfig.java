package dev.nosurprise.common;

import dev.nosurprise.common.outbox.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Задачи по расписанию: relay outbox, истечение предложений, очистка KYC, сверка с Mongo (MICROLOANS.md).
 * Задачи безопасны при нескольких инстансах: строки берутся через FOR UPDATE SKIP LOCKED
 * или меняются одним условным UPDATE.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class SchedulingConfig {
}
