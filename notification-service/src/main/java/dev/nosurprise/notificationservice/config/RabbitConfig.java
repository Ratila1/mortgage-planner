package dev.nosurprise.notificationservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String EVENTS_EXCHANGE = "domain.events";
    public static final String DLX_EXCHANGE = "domain.events.dlx";

    public static final String VERIFICATION_REQUESTED_RK = "verification.requested";
    public static final String VERIFICATION_REQUESTED_Q = "telegram.verification.q";

    public static final String USER_SYNC_RK = "user.phone.changed"; // fanout-ish via topic; also user.registered/blocked
    public static final String USER_SYNC_Q = "telegram.user-sync.q";

    @Bean
    Declarables declarables() {
        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX_EXCHANGE, true, false);

        Queue vq = QueueBuilder.durable(VERIFICATION_REQUESTED_Q)
                .deadLetterExchange(DLX_EXCHANGE).deadLetterRoutingKey(VERIFICATION_REQUESTED_RK).build();
        Queue vdlq = QueueBuilder.durable(VERIFICATION_REQUESTED_Q + ".dlq").build();

        Queue uq = QueueBuilder.durable(USER_SYNC_Q)
                .deadLetterExchange(DLX_EXCHANGE).deadLetterRoutingKey("user.phone.changed").build();
        Queue udlq = QueueBuilder.durable(USER_SYNC_Q + ".dlq").build();

        Binding vb = BindingBuilder.bind(vq).to(events).with(VERIFICATION_REQUESTED_RK);
        Binding vdb = BindingBuilder.bind(vdlq).to(dlx).with(VERIFICATION_REQUESTED_RK);

        Binding ub1 = BindingBuilder.bind(uq).to(events).with("user.registered");
        Binding ub2 = BindingBuilder.bind(uq).to(events).with("user.phone.changed");
        Binding ub3 = BindingBuilder.bind(uq).to(events).with("user.blocked");
        Binding ub4 = BindingBuilder.bind(uq).to(events).with("user.unblocked");
        Binding udb = BindingBuilder.bind(udlq).to(dlx).with("user.phone.changed");

        return new Declarables(events, dlx, vq, vdlq, uq, udlq, vb, vdb, ub1, ub2, ub3, ub4, udb);
    }

    @Bean
    MessageConverter jacksonJsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}