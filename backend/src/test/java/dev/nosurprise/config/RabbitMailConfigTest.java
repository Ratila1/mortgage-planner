package dev.nosurprise.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Конфигурация RabbitMQ без реального брокера (§8):
 * топология (exchange, очереди, DLX-аргументы, bindings) — из бина Declarables;
 * retry (3 попытки, 1с/2с/4с) и default-requeue-rejected=false — привязкой свойств
 * из настоящего application.yml.
 */
class RabbitMailConfigTest {

    @Test
    @DisplayName("топология: mail.send.q с DLX domain.events.dlx, DLQ и bindings по mail.requested")
    void topologyDeclarations() {
        Declarables declarables = new RabbitMailConfig().rabbitMailDeclarables();

        Queue mailQ = declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .filter(q -> q.getName().equals(RabbitMailConfig.MAIL_SEND_Q))
                .findFirst().orElseThrow();
        assertThat(mailQ.isDurable()).isTrue();
        assertThat(mailQ.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitMailConfig.DLX_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", RabbitMailConfig.MAIL_REQUESTED_RK);

        Queue dlq = declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .filter(q -> q.getName().equals(RabbitMailConfig.MAIL_SEND_DLQ))
                .findFirst().orElseThrow();
        assertThat(dlq.isDurable()).isTrue();

        assertThat(declarables.getDeclarables().stream()
                .filter(Exchange.class::isInstance)
                .map(Exchange.class::cast)
                .map(Exchange::getName))
                .containsExactlyInAnyOrder(RabbitMailConfig.EVENTS_EXCHANGE, RabbitMailConfig.DLX_EXCHANGE);

        assertThat(declarables.getDeclarables().stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast))
                .anySatisfy(b -> {
                    assertThat(b.getExchange()).isEqualTo(RabbitMailConfig.EVENTS_EXCHANGE);
                    assertThat(b.getDestination()).isEqualTo(RabbitMailConfig.MAIL_SEND_Q);
                    assertThat(b.getRoutingKey()).isEqualTo(RabbitMailConfig.MAIL_REQUESTED_RK);
                })
                .anySatisfy(b -> {
                    assertThat(b.getExchange()).isEqualTo(RabbitMailConfig.DLX_EXCHANGE);
                    assertThat(b.getDestination()).isEqualTo(RabbitMailConfig.MAIL_SEND_DLQ);
                    assertThat(b.getRoutingKey()).isEqualTo(RabbitMailConfig.MAIL_REQUESTED_RK);
                });
    }

    private Properties applicationYmlProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    @Test
    @DisplayName("retry из application.yml: enabled, 3 попытки, паузы 1с/2с/4с")
    void retryConfigurationFromApplicationYml() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("app-yml", applicationYmlProperties()));

        RetrySpec spec = new RetrySpec();
        Binder.get(env).bind("spring.rabbitmq.listener.simple.retry", Bindable.ofInstance(spec));

        assertThat(spec.enabled).isTrue();
        assertThat(spec.maxAttempts).isEqualTo(3);
        assertThat(spec.initialInterval).isEqualTo(Duration.ofSeconds(1));
        assertThat(spec.multiplier).isEqualTo(2.0);
        assertThat(spec.maxInterval).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("default-requeue-rejected=false: исчерпание retry уходит в DLQ, а не в очередь")
    void requeueRejectedDisabled() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("app-yml", applicationYmlProperties()));

        Boolean requeueRejected = Binder.get(env)
                .bind("spring.rabbitmq.listener.simple.default-requeue-rejected", Bindable.of(Boolean.class))
                .get();

        assertThat(requeueRejected).isFalse();
    }

    /** Зеркало свойств spring.rabbitmq.listener.simple.retry из application.yml. */
    static class RetrySpec {
        boolean enabled;
        int maxAttempts;
        Duration initialInterval;
        double multiplier;
        Duration maxInterval;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getInitialInterval() { return initialInterval; }
        public void setInitialInterval(Duration initialInterval) { this.initialInterval = initialInterval; }
        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
        public Duration getMaxInterval() { return maxInterval; }
        public void setMaxInterval(Duration maxInterval) { this.maxInterval = maxInterval; }
    }
}
