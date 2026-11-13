package dev.nosurprise.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
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
 * retry (3 попытки всего: 1 первоначальная + 2 retry, паузы 1с/2с/4с) и
 * default-requeue-rejected=false — привязкой свойств из настоящего application.yml.
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
    @DisplayName("retry из application.yml: enabled, 3 попытки всего (1 + 2 retry), паузы 1с/2с/4с")
    void retryConfigurationFromApplicationYml() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("app-yml", applicationYmlProperties()));

        RabbitProperties.ListenerRetry retry = new RabbitProperties().getListener().getSimple().getRetry();
        Binder.get(env).bind("spring.rabbitmq.listener.simple.retry", Bindable.ofInstance(retry));

        assertThat(retry.isEnabled()).isTrue();
        // 3 попытки всего = 1 первоначальная + 2 retry (max-retries: 2)
        assertThat(retry.getMaxRetries()).isEqualTo(2);
        assertThat(retry.getInitialInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(retry.getMultiplier()).isEqualTo(2.0);
        assertThat(retry.getMaxInterval()).isEqualTo(Duration.ofSeconds(4));
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

}
