package dev.nosurprise.calc.service;

import dev.nosurprise.calc.cache.CalcCache;
import dev.nosurprise.calc.repository.CalculationDocument;
import dev.nosurprise.calc.repository.CalculationRepository;
import dev.nosurprise.calc.web.CalcRequestMapper;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.CalcResponse;
import dev.nosurprise.calc.web.dto.EarlyRepaymentRequest;
import dev.nosurprise.calculator.EarlyRepaymentMode;
import dev.nosurprise.calculator.EarlyRepaymentType;
import dev.nosurprise.calculator.InvalidCalculationException;
import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.MortgageCalculator;
import dev.nosurprise.calculator.CalculationResult;
import dev.nosurprise.calculator.PaymentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Слой service с моками репозитория и кэша: идемпотентность по paramsHash, Redis-кэш
 * (§6.3): hit по calcId без пересчёта, прогрев при Mongo hit, запись после сохранения,
 * отказоустойчивость кэша, TTL анонимного расчёта, 404 для неизвестного calcId,
 * проброс InvalidCalculationException.
 */
@ExtendWith(MockitoExtension.class)
class CalculatorServiceTest {

    private static final String SAVED_CALC_ID = "665f0f0f1e1e1e1e1e1e1e1e";
    private static final String EXISTING_CALC_ID = "aaaabbbbccccddddeeeeffff";

    @Mock
    CalculationRepository repository;

    @Mock
    CalcCache cache;

    @InjectMocks
    CalculatorService service;

    private CalcRequest request() {
        return new CalcRequest(
                new BigDecimal("1000000"), new BigDecimal("0"), 12,
                new BigDecimal("12"), PaymentType.ANNUITY, List.of());
    }

    private String requestHash() {
        return ParamsHasher.hash(CalcRequestMapper.toParams(request()));
    }

    private CalculationDocument document(String id) {
        LoanParams params = CalcRequestMapper.toParams(request());
        CalculationResult result = new MortgageCalculator().calculate(params);
        return new CalculationDocument(id, ParamsHasher.hash(params), null,
                params, result.summary(), result.savings(), result.schedule(),
                Instant.now(), Instant.now().plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("первый расчёт сохраняется и возвращает calcId")
    void calculateSavesAndReturnsCalcId() {
        when(repository.findByParamsHash(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request());

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
        assertThat(response.loanAmount()).isEqualTo("1000000.00");
        assertThat(response.savings()).isNull();
        assertThat(response.schedule()).hasSize(12);

        ArgumentCaptor<CalculationDocument> captor = ArgumentCaptor.forClass(CalculationDocument.class);
        verify(repository).save(captor.capture());
        CalculationDocument saved = captor.getValue();
        assertThat(saved.paramsHash()).hasSize(64);
        assertThat(saved.userId()).isNull();
        // Анонимный расчёт: expireAt = createdAt + 7 дней.
        assertThat(saved.expireAt()).isEqualTo(saved.createdAt().plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("повторный расчёт с тем же paramsHash возвращает существующий calcId без сохранения")
    void repeatedParamsReuseExistingDocument() {
        CalculationDocument existing = document(EXISTING_CALC_ID);
        when(repository.findByParamsHash(any())).thenReturn(Optional.of(existing));

        CalcResponse response = service.calculate(request());

        assertThat(response.calcId()).isEqualTo(EXISTING_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("redis hit + документ по calcId найден → существующий calcId без пересчёта")
    void redisHitReturnsExistingCalcId() {
        CalculationDocument existing = document(EXISTING_CALC_ID);
        when(cache.findCalcId(requestHash())).thenReturn(Optional.of(EXISTING_CALC_ID));
        when(repository.findById(EXISTING_CALC_ID)).thenReturn(Optional.of(existing));

        CalcResponse response = service.calculate(request());

        assertThat(response.calcId()).isEqualTo(EXISTING_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
        verify(repository, never()).findByParamsHash(any());
        verify(repository, never()).save(any());
        verify(cache, never()).save(anyString(), anyString());
    }

    @Test
    @DisplayName("redis hit, но документа по calcId нет → evict устаревшей записи, пересчёт, сохранение, запись в кэш")
    void staleRedisEntryEvictedAndRecalculated() {
        when(cache.findCalcId(requestHash())).thenReturn(Optional.of("111122223333444455556666"));
        when(repository.findById("111122223333444455556666")).thenReturn(Optional.empty());
        when(repository.findByParamsHash(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request());

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        InOrder inOrder = inOrder(repository, cache);
        inOrder.verify(cache).evict(requestHash());
        inOrder.verify(repository).save(any());
        inOrder.verify(cache).save(eq(requestHash()), eq(SAVED_CALC_ID));
    }

    @Test
    @DisplayName("redis miss + mongo miss → расчёт, сохранение, запись calcId в кэш после сохранения")
    void cacheMissComputesSavesAndCaches() {
        when(cache.findCalcId(any())).thenReturn(Optional.empty());
        when(repository.findByParamsHash(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request());

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        InOrder inOrder = inOrder(repository, cache);
        inOrder.verify(repository).save(any());
        inOrder.verify(cache).save(requestHash(), SAVED_CALC_ID);
    }

    @Test
    @DisplayName("redis miss + mongo hit → существующий расчёт без пересчёта, кэш прогрет")
    void mongoHitWarmsRedisCache() {
        when(cache.findCalcId(any())).thenReturn(Optional.empty());
        CalculationDocument existing = document(EXISTING_CALC_ID);
        when(repository.findByParamsHash(requestHash())).thenReturn(Optional.of(existing));

        CalcResponse response = service.calculate(request());

        assertThat(response.calcId()).isEqualTo(EXISTING_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
        verify(repository, never()).save(any());
        verify(cache).save(requestHash(), EXISTING_CALC_ID);
    }

    @Test
    @DisplayName("сбой Redis при поиске не ломает расчёт — идёт обычный поток")
    void redisFailureDoesNotBreakCalculation() {
        when(cache.findCalcId(any())).thenThrow(new IllegalStateException("redis down"));
        when(repository.findByParamsHash(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request());

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
    }

    @Test
    @DisplayName("сбой Redis при записи после успешного сохранения не ломает ответ")
    void cacheSaveFailureDoesNotBreakCalculation() {
        when(cache.findCalcId(any())).thenReturn(Optional.empty());
        when(repository.findByParamsHash(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });
        doThrow(new IllegalStateException("redis down")).when(cache).save(anyString(), anyString());

        CalcResponse response = service.calculate(request());

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
    }

    @Test
    @DisplayName("get по существующему calcId возвращает сохранённый расчёт")
    void getReturnsSavedCalculation() {
        CalculationDocument existing = document(EXISTING_CALC_ID);
        when(repository.findById(EXISTING_CALC_ID)).thenReturn(Optional.of(existing));

        CalcResponse response = service.get(EXISTING_CALC_ID);

        assertThat(response.calcId()).isEqualTo(EXISTING_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
    }

    @Test
    @DisplayName("get по неизвестному calcId → CalcNotFoundException")
    void getUnknownCalcId() {
        when(repository.findById("aaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("aaaaaaaaaaaaaaaaaaaaaaaa"))
                .isInstanceOf(CalcNotFoundException.class);
    }

    @Test
    @DisplayName("get с некорректным calcId (не ObjectId) → CalcNotFoundException без похода в репозиторий")
    void getMalformedCalcId() {
        assertThatThrownBy(() -> service.get("not-an-objectid"))
                .isInstanceOf(CalcNotFoundException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("досрочные погашения попадают в расчёт и в savings")
    void withEarlyRepayments() {
        CalcRequest withEarly = new CalcRequest(
                new BigDecimal("1000000"), new BigDecimal("0"), 12,
                new BigDecimal("12"), PaymentType.ANNUITY,
                List.of(new EarlyRepaymentRequest(EarlyRepaymentType.ONE_TIME, 3, null, null, null,
                        new BigDecimal("5000000"), EarlyRepaymentMode.REDUCE_TERM)));
        when(repository.findByParamsHash(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(withEarly);

        assertThat(response.schedule()).hasSize(3);
        assertThat(response.savings()).isNotNull();
        assertThat(response.savings().monthsSaved()).isEqualTo(9);
    }

    @Test
    @DisplayName("нарушение правил расчёта (downPayment >= price) → InvalidCalculationException")
    void invalidParamsThrowInvalidCalculationException() {
        CalcRequest invalid = new CalcRequest(
                new BigDecimal("1000000"), new BigDecimal("1000000"), 12,
                new BigDecimal("12"), PaymentType.ANNUITY, List.of());

        assertThatThrownBy(() -> service.calculate(invalid))
                .isInstanceOf(InvalidCalculationException.class);
        verify(repository, never()).save(any());
    }
}
