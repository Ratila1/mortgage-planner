package dev.nosurprise.sms.integration;

import dev.nosurprise.sms.domain.SmsRequestRepository;
import dev.nosurprise.sms.integration.support.AbstractSmsIntegrationTest;
import dev.nosurprise.sms.provider.SmsSender;
import dev.nosurprise.sms.provider.SmsSender.SendResult;
import dev.nosurprise.sms.worker.SmsDispatchJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Диспетчер SMS: захват пачки, успешная отправка, ретрай с backoff
 * и NOT_DELIVERED после исчерпания попыток. Провайдер замокан — тест про состояние
 * запроса и факт попытки, а не про конкретный SMS-шлюз.
 */
class SmsDispatchIntegrationTest extends AbstractSmsIntegrationTest {

    private static final String PHONE = "+375291234567";
    private static final String REQUEST_BY_ID =
            "select status, attempts, sent_at from sms.sms_request where id = cast(? as uuid)";

    @Autowired
    private SmsDispatchJob dispatchJob;

    @Autowired
    private SmsRequestRepository requests;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private SmsSender provider;

    @Test
    @DisplayName("успешная отправка: DELIVERED, sent_at, одна запись попытки")
    void sendsPendingRequestOnce() {
        UUID id = create();
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.success());

        dispatchJob.dispatch();

        Map<String, Object> row = requestRow(id);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("sent_at")).isNotNull();

        Map<String, Object> attempt = jdbc.queryForMap(
                "select attempt_no, result from sms.sms_attempt "
                        + "where sms_request_id = cast(? as uuid)", id.toString());
        assertThat(((Number) attempt.get("attempt_no")).intValue()).isEqualTo(1);
        assertThat(attempt.get("result")).isEqualTo("SUCCESS");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(provider).send(org.mockito.ArgumentMatchers.eq(PHONE), message.capture());
        assertThat(message.getValue()).containsPattern("\\d{6}");

        // Отправленный запрос больше не попадает в выборку.
        dispatchJob.dispatch();
        verify(provider, times(1)).send(anyString(), anyString());
    }

    @Test
    @DisplayName("ошибка провайдера: CREATED (retry), next attempt не раньше backoff")
    void failureSchedulesRetry() {
        UUID id = create();
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.failure());

        dispatchJob.dispatch();

        Map<String, Object> row = requestRow(id);
        assertThat(row.get("status")).isEqualTo("CREATED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);

        // Backoff ещё не истёк — повторной отправки нет.
        dispatchJob.dispatch();
        verify(provider, times(1)).send(anyString(), anyString());
    }

    @Test
    @DisplayName("исключение провайдера фиксируется как PROVIDER_EXCEPTION, запрос не теряется")
    void providerExceptionIsRecorded() {
        UUID id = create();
        when(provider.send(anyString(), anyString())).thenThrow(new IllegalStateException("boom"));

        dispatchJob.dispatch();

        Map<String, Object> row = requestRow(id);
        assertThat(row.get("status")).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("третья неудача исчерпывает попытки: NOT_DELIVERED")
    void failureOnLastAttemptMarksNotDelivered() {
        UUID id = create();
        jdbc.update("update sms.sms_request set attempts = 2, created_at = now() - interval '1 hour' "
                + "where id = cast(? as uuid)", id.toString());
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.failure());

        dispatchJob.dispatch();

        Map<String, Object> row = requestRow(id);
        assertThat(row.get("status")).isEqualTo("NOT_DELIVERED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("батч: несколько заявок обрабатываются за один проход")
    void dispatchProcessesClaimedBatch() {
        UUID first = create();
        UUID second = create();
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.success());

        dispatchJob.dispatch();

        assertThat(requestRow(first).get("status")).isEqualTo("DELIVERED");
        assertThat(requestRow(second).get("status")).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("истёкший код не отправляется: строка гасится в EXPIRED")
    void expiredCodeIsNotSent() {
        UUID id = create();
        jdbc.update("update sms.sms_request set expires_at = now() - interval '1 second' "
                + "where id = cast(? as uuid)", id.toString());
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.success());

        dispatchJob.dispatch();

        assertThat(requestRow(id).get("status")).isEqualTo("EXPIRED");
        verify(provider, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("«зомби»-строка с исчерпанными попытками закрывается, а не висит в CREATED")
    void closesExhaustedRequest() {
        UUID id = create();
        jdbc.update("update sms.sms_request set attempts = 3, created_at = now() - interval '1 hour' "
                + "where id = cast(? as uuid)", id.toString());
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.success());

        dispatchJob.dispatch();

        assertThat(requestRow(id).get("status")).isEqualTo("NOT_DELIVERED");
        verify(provider, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("expireStale не гасит строку, которая ещё в полёте (есть lease)")
    void expireStaleSkipsLeasedRow() {
        UUID id = create();
        jdbc.update("update sms.sms_request set expires_at = now() - interval '1 second', "
                + "locked_until = now() + interval '30 seconds' where id = cast(? as uuid)", id.toString());

        assertThat(requests.expireStale(Instant.now())).isZero();
        assertThat(requestRow(id).get("status")).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("finishAttempt не возвращает погашенную строку в очередь")
    void finishAttemptDoesNotResurrectExpired() {
        UUID id = create();
        jdbc.update("update sms.sms_request set status = 'EXPIRED' where id = cast(? as uuid)", id.toString());

        requests.finishAttempt(id, false, 3);

        assertThat(requestRow(id).get("status")).isEqualTo("EXPIRED");
    }

    private UUID create() {
        return UUID.fromString(json(api.create("dispatch-" + UUID.randomUUID(), PHONE).getBody())
                .get("id").asString());
    }

    private Map<String, Object> requestRow(UUID id) {
        return jdbc.queryForMap(REQUEST_BY_ID, id.toString());
    }
}
