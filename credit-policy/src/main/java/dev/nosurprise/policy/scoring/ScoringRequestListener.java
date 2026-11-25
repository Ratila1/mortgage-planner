package dev.nosurprise.policy.scoring;

import dev.nosurprise.policy.config.RabbitConfig;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Очередь запросов скоринга. Сначала результат сохраняется (транзакция ScoringService), потом публикуется
 * с подтверждением брокера. Сбой публикации → исключение → повтор доставки → сохранённый результат
 * публикуется ещё раз. Поэтому отдельный outbox не нужен (CREDIT_POLICY.md §7.2).
 */
@Component
public class ScoringRequestListener {

    private static final long CONFIRM_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    private final ScoringService scoring;
    private final RabbitTemplate rabbit;

    public ScoringRequestListener(ScoringService scoring, RabbitTemplate rabbit) {
        this.scoring = scoring;
        this.rabbit = rabbit;
    }

    @RabbitListener(queues = RabbitConfig.SCORING_REQUEST_Q)
    public void on(ScoringRequested request) {
        if (request.requestId() == null || request.applicationId() == null || request.userId() == null) {
            // Повтор не поможет: сообщение сразу в DLQ.
            throw new AmqpRejectAndDontRequeueException("scoring request without requestId/applicationId/userId");
        }
        ScoringCompleted completed = scoring.toMessage(scoring.score(request));
        rabbit.invoke(operations -> {
            operations.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, ScoringCompleted.ROUTING_KEY, completed);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }
}
