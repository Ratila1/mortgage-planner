package dev.nosurprise.mortgage.notification;

import dev.nosurprise.mortgage.calculator.CalculationRequest;
import dev.nosurprise.mortgage.calculator.CalculatorService;
import dev.nosurprise.mortgage.calculator.PaymentType;
import dev.nosurprise.mortgage.config.RetryPublisher;
import dev.nosurprise.mortgage.offer.RateChangedEvent;
import dev.nosurprise.mortgage.user.Scenario;
import dev.nosurprise.mortgage.user.ScenarioRepository;
import dev.nosurprise.mortgage.auth.EmailService;
import dev.nosurprise.mortgage.auth.User;
import dev.nosurprise.mortgage.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

@Component
public class RateChangeConsumer {

    private static final Logger log = LoggerFactory.getLogger(RateChangeConsumer.class);

    private final ScenarioRepository scenarioRepository;
    private final UserRepository userRepository;
    private final CalculatorService calculatorService;
    private final EmailService emailService;
    private final RetryPublisher retryPublisher;

    @Value("${rabbitmq.exchange.rate-changed-retry}")
    private String retryExchange;

    @Value("${rabbitmq.routing-key.rate-changed-retry}")
    private String retryRoutingKey;

    @Value("${rabbitmq.retry.max-attempts:5}")
    private int maxRetryAttempts;

    @Value("${rabbitmq.retry.base-delay-ms:2000}")
    private int baseDelayMs;

    public RateChangeConsumer(ScenarioRepository scenarioRepository,
                              UserRepository userRepository,
                              CalculatorService calculatorService,
                              EmailService emailService,
                              RetryPublisher retryPublisher) {
        this.scenarioRepository = scenarioRepository;
        this.userRepository = userRepository;
        this.calculatorService = calculatorService;
        this.emailService = emailService;
        this.retryPublisher = retryPublisher;
    }

    @RabbitListener(queues = "${rabbitmq.queue.rate-changed}")
    public void handleRateChanged(Message message, RateChangedEvent event) {
        log.info("Received rate change event for offer {}: {} -> {}", event.offerId(), event.oldRate(), event.newRate());

        processEvent(event)
                .doOnError(e -> {
                    log.error("Error processing rate change event, scheduling retry", e);
                    retryPublisher.republishToRetryExchange(message, retryExchange, retryRoutingKey, maxRetryAttempts, baseDelayMs);
                })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    private Mono<Void> processEvent(RateChangedEvent event) {
        // Find all scenarios that used this offer's rate
        // For simplicity, we find scenarios with matching rate (in real app, we'd link scenario to offer)
        Flux<Scenario> scenarios = scenarioRepository.findAll()
                .filter(s -> s.rate().compareTo(event.oldRate()) == 0);

        return scenarios.flatMap(scenario -> processScenario(scenario, event))
                .onErrorResume(e -> {
                    log.error("Error processing rate change for scenario", e);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> processScenario(Scenario scenario, RateChangedEvent event) {
        return userRepository.findById(scenario.userId())
                .switchIfEmpty(Mono.error(new IllegalStateException("User not found for scenario " + scenario.id())))
                .flatMap(user -> {
                    // Recalculate with new rate
                    CalculationRequest newRequest = new CalculationRequest(
                            scenario.propertyPrice(),
                            scenario.downPayment(),
                            scenario.termMonths(),
                            event.newRate(),
                            PaymentType.valueOf(scenario.paymentType()),
                            scenario.earlyRepayments()
                    );

                    return calculatorService.calculate(newRequest)
                            .flatMap(response -> {
                                // Send notification
                                return emailService.sendRateChangeNotification(
                                        user.email(), user.name(), event.bankName(),
                                        event.oldRate(), event.newRate(),
                                        scenario.monthlyPayment(), response.monthlyPayment()
                                ).then();
                            });
                });
    }
}







