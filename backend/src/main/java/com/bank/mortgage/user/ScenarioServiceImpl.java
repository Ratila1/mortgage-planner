package com.bank.mortgage.user;

import com.bank.mortgage.calculator.CalculationRequest;
import com.bank.mortgage.calculator.CalculationResponse;
import com.bank.mortgage.calculator.EarlyRepayment;
import com.bank.mortgage.calculator.PaymentType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ScenarioServiceImpl implements ScenarioService {

    private final ScenarioRepository scenarioRepository;
    private final ScheduleRepository scheduleRepository;

    public ScenarioServiceImpl(ScenarioRepository scenarioRepository, ScheduleRepository scheduleRepository) {
        this.scenarioRepository = scenarioRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Override
    public Mono<Scenario> saveScenario(Long userId, String name, CalculationRequest request, CalculationResponse response) {
        String scheduleHash = computeScheduleHash(request);

        // Save schedule to MongoDB
        return scheduleRepository.findByScheduleHash(scheduleHash)
                .switchIfEmpty(
                        scheduleRepository.save(ScheduleDocument.builder()
                                .scheduleHash(scheduleHash)
                                .entries(response.schedule())
                                .build())
                )
                .then(
                        scenarioRepository.save(Scenario.builder()
                                .userId(userId)
                                .name(name)
                                .propertyPrice(request.propertyPrice())
                                .downPayment(request.downPayment())
                                .termMonths(request.termMonths())
                                .rate(request.rate())
                                .paymentType(request.type().name())
                                .earlyRepayments(request.earlyRepayments())
                                .monthlyPayment(response.monthlyPayment())
                                .overpayment(response.overpayment())
                                .totalPayment(response.totalPayment())
                                .scheduleHash(scheduleHash)
                                .build())
                );
    }

    @Override
    public Flux<Scenario> getUserScenarios(Long userId) {
        return scenarioRepository.findByUserId(userId)
                .sort(Comparator.comparing(Scenario::createdAt).reversed());
    }

    @Override
    public Mono<Scenario> getScenario(Long userId, String scenarioId) {
        return scenarioRepository.findByIdAndUserId(scenarioId, userId)
                .switchIfEmpty(Mono.error(new ScenarioNotFoundException("Scenario not found")));
    }

    @Override
    public Mono<Void> deleteScenario(Long userId, String scenarioId) {
        return scenarioRepository.findByIdAndUserId(scenarioId, userId)
                .switchIfEmpty(Mono.error(new ScenarioNotFoundException("Scenario not found")))
                .flatMap(scenarioRepository::delete)
                .then();
    }

    @Override
    public Mono<ComparisonResult> compareScenarios(Long userId, List<String> scenarioIds) {
        if (scenarioIds.size() < 2 || scenarioIds.size() > 3) {
            return Mono.error(new IllegalArgumentException("Must compare 2 or 3 scenarios"));
        }

        return scenarioRepository.findByUserIdAndIds(userId, scenarioIds)
                .collectList()
                .map(scenarios -> {
                    if (scenarios.size() != scenarioIds.size()) {
                        throw new ScenarioNotFoundException("One or more scenarios not found");
                    }
                    // Sort by the original order
                    Map<String, Scenario> scenarioMap = scenarios.stream()
                            .collect(Collectors.toMap(Scenario::id, s -> s));
                    List<Scenario> ordered = scenarioIds.stream()
                            .map(scenarioMap::get)
                            .toList();

                    List<ComparisonResult.ScenarioSummary> summaries = ordered.stream()
                            .map(s -> new ComparisonResult.ScenarioSummary(
                                    s.id(), s.name(), s.propertyPrice(), s.downPayment(),
                                    s.termMonths(), s.rate(), s.paymentType(),
                                    s.monthlyPayment(), s.overpayment(), s.totalPayment()
                            ))
                            .toList();

                    Map<String, BigDecimal> monthlyPaymentComparison = new HashMap<>();
                    Map<String, BigDecimal> overpaymentComparison = new HashMap<>();
                    Map<String, BigDecimal> totalPaymentComparison = new HashMap<>();
                    Map<String, Integer> termComparison = new HashMap<>();

                    for (Scenario s : ordered) {
                        monthlyPaymentComparison.put(s.name(), s.monthlyPayment());
                        overpaymentComparison.put(s.name(), s.overpayment());
                        totalPaymentComparison.put(s.name(), s.totalPayment());
                        termComparison.put(s.name(), s.termMonths());
                    }

                    return new ComparisonResult(
                            summaries,
                            monthlyPaymentComparison,
                            overpaymentComparison,
                            totalPaymentComparison,
                            termComparison
                    );
                });
    }

    private String computeScheduleHash(CalculationRequest request) {
        try {
            String data = request.propertyPrice() + "|" +
                    request.downPayment() + "|" +
                    request.termMonths() + "|" +
                    request.rate() + "|" +
                    request.type().name() + "|" +
                    (request.earlyRepayments() != null ? request.earlyRepayments().toString() : "");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes());
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class ScenarioNotFoundException extends RuntimeException {
        public ScenarioNotFoundException(String message) { super(message); }
    }
}