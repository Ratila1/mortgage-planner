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
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Слой service с моками репозитория и кэша: идемпотентность по (paramsHash, userId),
 * ownerScope кэша (§6.3): hit по calcId без пересчёта, прогрев при Mongo hit, запись после
 * сохранения, отказоустойчивость кэша, TTL анонимного расчёта, изоляция владельцев
 * («усыновление» анонимного документа запрещено), гонка duplicate key, 404 для чужого
 * calcId, история, проброс InvalidCalculationException.
 */
@ExtendWith(MockitoExtension.class)
class CalculatorServiceTest {

    private static final String SAVED_CALC_ID = "665f0f0f1e1e1e1e1e1e1e1e";
    private static final String EXISTING_CALC_ID = "aaaabbbbccccddddeeeeffff";
    private static final String ANON_SCOPE = "anon";

    @Mock
    CalculationRepository repository;

    @Mock
    CalcCache cache;

    @org.mockito.Spy
    Clock clock = Clock.systemUTC();

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
        return document(id, null);
    }

    private CalculationDocument document(String id, String userId) {
        LoanParams params = CalcRequestMapper.toParams(request());
        CalculationResult result = new MortgageCalculator().calculate(params);
        return new CalculationDocument(id, ParamsHasher.hash(params), userId,
                params, result.summary(), result.savings(), result.schedule(),
                Instant.now(), userId == null ? Instant.now().plus(Duration.ofDays(7)) : null);
    }

    @Test
    @DisplayName("первый анонимный расчёт сохраняется и возвращает calcId, expireAt = now + 7 дней")
    void calculateSavesAndReturnsCalcId() {
        when(cache.findCalcId(eq(ANON_SCOPE), anyString())).thenReturn(Optional.empty());
        when(repository.findByParamsHashAndUserId(anyString(), isNull())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request(), null);

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
        verify(cache).save(ANON_SCOPE, requestHash(), SAVED_CALC_ID);
    }

    @Test
    @DisplayName("авторизованный расчёт: userId в документе, без expireAt, кэш в scope пользователя")
    void authorizedCalculationUsesOwnerScope() {
        UUID userId = UUID.randomUUID();
        when(cache.findCalcId(eq(userId.toString()), anyString())).thenReturn(Optional.empty());
        when(repository.findByParamsHashAndUserId(anyString(), eq(userId.toString())))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request(), userId);

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        ArgumentCaptor<CalculationDocument> captor = ArgumentCaptor.forClass(CalculationDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(userId.toString());
        assertThat(captor.getValue().expireAt()).isNull();
        verify(cache).save(userId.toString(), requestHash(), SAVED_CALC_ID);
    }

    @Test
    @DisplayName("повторный расчёт с тем же paramsHash возвращает существующий calcId без сохранения")
    void repeatedParamsReuseExistingDocument() {
        CalculationDocument existing = document(EXISTING_CALC_ID);
        when(cache.findCalcId(eq(ANON_SCOPE), anyString())).thenReturn(Optional.empty());
        when(repository.findByParamsHashAndUserId(anyString(), isNull())).thenReturn(Optional.of(existing));

        CalcResponse response = service.calculate(request(), null);

        assertThat(response.calcId()).isEqualTo(EXISTING_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
        verify(repository, never()).save(any());
        verify(cache).save(ANON_SCOPE, requestHash(), EXISTING_CALC_ID);
    }

    @Test
    @DisplayName("mongo hit по (paramsHash, userId) — не усыновляет анонимный документ")
    void userDoesNotAdoptAnonymousDocument() {
        UUID userId = UUID.randomUUID();
        // В Mongo есть анонимный документ с тем же paramsHash.
        when(cache.findCalcId(eq(userId.toString()), anyString())).thenReturn(Optional.empty());
        when(repository.findByParamsHashAndUserId(anyString(), eq(userId.toString())))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request(), userId);

        ArgumentCaptor<CalculationDocument> captor = ArgumentCaptor.forClass(CalculationDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(userId.toString());
        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
    }

    @Test
    @DisplayName("redis hit + документ по calcId найден → существующий calcId без пересчёта")
    void redisHitReturnsExistingCalcId() {
        CalculationDocument existing = document(EXISTING_CALC_ID);
        when(cache.findCalcId(ANON_SCOPE, requestHash())).thenReturn(Optional.of(EXISTING_CALC_ID));
        when(repository.findById(EXISTING_CALC_ID)).thenReturn(Optional.of(existing));

        CalcResponse response = service.calculate(request(), null);

        assertThat(response.calcId()).isEqualTo(EXISTING_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
        verify(repository, never()).findByParamsHashAndUserId(anyString(), any());
        verify(repository, never()).save(any());
        verify(cache, never()).save(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("redis hit, но документа по calcId нет → evict устаревшей записи, пересчёт, сохранение, запись в кэш")
    void staleRedisEntryEvictedAndRecalculated() {
        when(cache.findCalcId(ANON_SCOPE, requestHash())).thenReturn(Optional.of("111122223333444455556666"));
        when(repository.findById("111122223333444455556666")).thenReturn(Optional.empty());
        when(repository.findByParamsHashAndUserId(anyString(), isNull())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request(), null);

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        InOrder inOrder = inOrder(repository, cache);
        inOrder.verify(cache).evict(ANON_SCOPE, requestHash());
        inOrder.verify(repository).save(any());
        inOrder.verify(cache).save(ANON_SCOPE, requestHash(), SAVED_CALC_ID);
    }

    @Test
    @DisplayName("redis miss + mongo miss → расчёт, сохранение, запись calcId в кэш после сохранения")
    void cacheMissComputesSavesAndCaches() {
        when(cache.findCalcId(eq(ANON_SCOPE), anyString())).thenReturn(Optional.empty());
        when(repository.findByParamsHashAndUserId(anyString(), isNull())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request(), null);

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        InOrder inOrder = inOrder(repository, cache);
        inOrder.verify(repository).save(any());
        inOrder.verify(cache).save(ANON_SCOPE, requestHash(), SAVED_CALC_ID);
    }

    @Test
    @DisplayName("сбой Redis при поиске не ломает расчёт — идёт обычный поток")
    void redisFailureDoesNotBreakCalculation() {
        when(cache.findCalcId(eq(ANON_SCOPE), anyString())).thenThrow(new IllegalStateException("redis down"));
        when(repository.findByParamsHashAndUserId(anyString(), isNull())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(request(), null);

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
    }

    @Test
    @DisplayName("сбой Redis при записи после успешного сохранения не ломает ответ")
    void cacheSaveFailureDoesNotBreakCalculation() {
        when(cache.findCalcId(eq(ANON_SCOPE), anyString())).thenReturn(Optional.empty());
        when(repository.findByParamsHashAndUserId(anyString(), isNull())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });
        doThrow(new IllegalStateException("redis down")).when(cache).save(anyString(), anyString(), anyString());

        CalcResponse response = service.calculate(request(), null);

        assertThat(response.calcId()).isEqualTo(SAVED_CALC_ID);
        assertThat(response.monthlyPayment()).isEqualTo("88848.79");
    }

    @Test
    @DisplayName("гонка: DuplicateKeyException → возврат уже сохранённого документа")
    void duplicateKeyRaceReturnsWinner() {
        UUID userId = UUID.randomUUID();
        CalculationDocument winner = document(EXISTING_CALC_ID, userId.toString());
        when(cache.findCalcId(eq(userId.toString()), anyString())).thenReturn(Optional.empty());
        when(repository.findByParamsHashAndUserId(anyString(), eq(userId.toString())))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.save(any())).thenThrow(new DuplicateKeyException("E11000"));

        CalcResponse response = service.calculate(request(), userId);

        assertThat(response.calcId()).isEqualTo(EXISTING_CALC_ID);
        verify(cache).save(userId.toString(), requestHash(), EXISTING_CALC_ID);
    }

    @Test
    @DisplayName("get: анонимный документ виден всем, включая анонима")
    void getAnonymousDocumentIsPublic() {
        CalculationDocument anonymous = document(EXISTING_CALC_ID, null);
        when(repository.findById(EXISTING_CALC_ID)).thenReturn(Optional.of(anonymous));

        CalcResponse response = service.get(EXISTING_CALC_ID, null);

        assertThat(response.calcId()).isEqualTo(EXISTING_CALC_ID);
    }

    @Test
    @DisplayName("get: документ пользователя виден владельцу, чужой/аноним → CalcNotFoundException")
    void getOwnedDocumentVisibility() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        CalculationDocument owned = document(EXISTING_CALC_ID, owner.toString());
        when(repository.findById(EXISTING_CALC_ID)).thenReturn(Optional.of(owned));

        assertThat(service.get(EXISTING_CALC_ID, owner).calcId()).isEqualTo(EXISTING_CALC_ID);
        assertThatThrownBy(() -> service.get(EXISTING_CALC_ID, stranger))
                .isInstanceOf(CalcNotFoundException.class);
        assertThatThrownBy(() -> service.get(EXISTING_CALC_ID, null))
                .isInstanceOf(CalcNotFoundException.class);
    }

    @Test
    @DisplayName("get по неизвестному calcId → CalcNotFoundException")
    void getUnknownCalcId() {
        when(repository.findById("aaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("aaaaaaaaaaaaaaaaaaaaaaaa", null))
                .isInstanceOf(CalcNotFoundException.class);
    }

    @Test
    @DisplayName("get с некорректным calcId (не ObjectId) → CalcNotFoundException без похода в репозиторий")
    void getMalformedCalcId() {
        assertThatThrownBy(() -> service.get("not-an-objectid", null))
                .isInstanceOf(CalcNotFoundException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("history: последние расчёты пользователя без графика платежей")
    void historyReturnsOwnedCalculationsWithoutSchedule() {
        UUID userId = UUID.randomUUID();
        CalculationDocument doc = document(EXISTING_CALC_ID, userId.toString());
        when(repository.findTop50ByUserIdOrderByCreatedAtDesc(userId.toString()))
                .thenReturn(List.of(doc));

        List<CalcResponse> history = service.history(userId);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).calcId()).isEqualTo(EXISTING_CALC_ID);
        assertThat(history.get(0).schedule()).isNull();
    }

    @Test
    @DisplayName("history: аноним → пустой список без похода в репозиторий")
    void historyAnonymousIsEmpty() {
        assertThat(service.history(null)).isEmpty();
        verify(repository, never()).findTop50ByUserIdOrderByCreatedAtDesc(anyString());
    }

    @Test
    @DisplayName("досрочные погашения попадают в расчёт и в savings")
    void withEarlyRepayments() {
        CalcRequest withEarly = new CalcRequest(
                new BigDecimal("1000000"), new BigDecimal("0"), 12,
                new BigDecimal("12"), PaymentType.ANNUITY,
                List.of(new EarlyRepaymentRequest(EarlyRepaymentType.ONE_TIME, 3, null, null, null,
                        new BigDecimal("5000000"), EarlyRepaymentMode.REDUCE_TERM)));
        when(cache.findCalcId(eq(ANON_SCOPE), anyString())).thenReturn(Optional.empty());
        when(repository.findByParamsHashAndUserId(anyString(), isNull())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            CalculationDocument doc = invocation.getArgument(0);
            return new CalculationDocument(SAVED_CALC_ID, doc.paramsHash(), doc.userId(),
                    doc.params(), doc.summary(), doc.savings(), doc.schedule(), doc.createdAt(), doc.expireAt());
        });

        CalcResponse response = service.calculate(withEarly, null);

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

        assertThatThrownBy(() -> service.calculate(invalid, null))
                .isInstanceOf(InvalidCalculationException.class);
        verify(repository, never()).save(any());
    }
}
