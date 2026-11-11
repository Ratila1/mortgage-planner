package dev.nosurprise.mortgage.config;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RetryPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RetryPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void republishToRetryExchange(Message originalMessage,
                                          String retryExchange,
                                          String retryRoutingKey,
                                          int maxAttempts,
                                          int baseDelayMs) {
        MessageProperties originalProps = originalMessage.getMessageProperties();
        Integer currentRetryCount = (Integer) originalProps.getHeaders().get("x-retry-count");
        int retryCount = (currentRetryCount != null) ? currentRetryCount + 1 : 1;

        if (retryCount > maxAttempts) {
            // Max attempts exceeded - let it go to DLQ (don't republish)
            return;
        }

        // Calculate exponential backoff delay: baseDelay * 2^(retryCount-1)
        long delayMs = (long) (baseDelayMs * Math.pow(2, retryCount - 1));

        // Build new message with incremented retry count and delay
        Message retryMessage = MessageBuilder
                .withBody(originalMessage.getBody())
                .copyProperties(originalProps)
                .setHeader("x-retry-count", retryCount)
                .setHeader("x-delay", delayMs)
                .setExpiration(String.valueOf(delayMs))
                .build();

        rabbitTemplate.convertAndSend(retryExchange, retryRoutingKey, retryMessage);
    }
}







