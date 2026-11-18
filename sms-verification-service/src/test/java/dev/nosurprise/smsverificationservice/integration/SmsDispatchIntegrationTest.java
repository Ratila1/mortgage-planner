package dev.nosurprise.smsverificationservice.integration;

import dev.nosurprise.smsverificationservice.domain.SmsRequestRepository;
import dev.nosurprise.smsverificationservice.integration.support.AbstractSmsIntegrationTest;
import dev.nosurprise.smsverificationservice.provider.DispatchSmsProvider;
import dev.nosurprise.smsverificationservice.provider.DispatchSmsProvider.SendResult;
import dev.nosurprise.smsverificationservice.worker.SmsDispatchJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Диспетчер SMS: захват пачки с лизой, успешная отправка, ретрай с backoff
 * и NOT_SEND после исчерпания попыток. Провайдер замокан — тест про состояние
 * запроса и аудит попыток, а не про конкретный SMS-шлюз.
 */
class SmsDispatchIntegrationTest extends AbstractSmsIntegrationTest {

    private static final String PHONE = "+375291234567";
    private static final String REQUEST_BY_ID =
            "select status, attempts, provider_message_id, sent_at, last_error, locked_until, locked_by, next_attempt_at "
                    + "from sms.sms_request where id = cast(? as uuid)";

    @Autowired
    private SmsDispatchJob dispatchJob;

    @Autowired
    private SmsRequestRepository requests;

    @MockitoBean
    private DispatchSmsProvider provider;

    @Test
    @DisplayName("успешная отправка: SUCCESS, sent_at, provider id и одна запись аудита")
    void sendsPendingRequestOnce() {
        UUID id = create();
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.success("prov-1"));

        dispatchJob.dispatch();

        Map<String, Object> row = requestRow(id);
        assertThat(row.get("status")).isEqualTo("SUCCESS");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("provider_message_id")).isEqualTo("prov-1");
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("locked_until")).isNull();
        assertThat(row.get("next_attempt_at")).isNull();

        Map<String, Object> attempt = jdbc.queryForMap(
                "select attempt_no, result, provider_message_id, latency_ms from sms.sms_attempt "
                        + "where sms_request_id = cast(? as uuid)", id.toString());
        assertThat(((Number) attempt.get("attempt_no")).intValue()).isEqualTo(1);
        assertThat(attempt.get("result")).isEqualTo("SUCCESS");
        assertThat(attempt.get("provider_message_id")).isEqualTo("prov-1");
        assertThat(attempt.get("latency_ms")).isNotNull();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(provider).send(org.mockito.ArgumentMatchers.eq(PHONE), message.capture());
        assertThat(message.getValue()).containsPattern("\\d{6}");

        // Отправленный запрос больше не попадает в выборку.
        dispatchJob.dispatch();
        verify(provider, times(1)).send(anyString(), anyString());
    }

    @Test
    @DisplayName("ошибка провайдера: IN_PROGRESS, next_attempt_at в будущем, аудит FAIL")
    void failureSchedulesRetry() {
        UUID id = create();
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.failure("PROVIDER_ERROR"));

        dispatchJob.dispatch();

        Map<String, Object> row = requestRow(id);
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo("PROVIDER_ERROR");
        assertThat(row.get("next_attempt_at")).isNotNull();
        assertThat(row.get("locked_until")).isNull();

        Map<String, Object> attempt = jdbc.queryForMap(
                "select result, error_code from sms.sms_attempt where sms_request_id = cast(? as uuid)",
                id.toString());
        assertThat(attempt.get("result")).isEqualTo("FAIL");
        assertThat(attempt.get("error_code")).isEqualTo("PROVIDER_ERROR");

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
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("last_error")).isEqualTo("PROVIDER_EXCEPTION");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("третья неудача исчерпывает попытки: NOT_SEND, next_attempt_at очищен")
    void failureOnLastAttemptMarksNotSend() {
        UUID id = create();
        jdbc.update("update sms.sms_request set attempts = 2, next_attempt_at = now() "
                + "where id = cast(? as uuid)", id.toString());
        when(provider.send(anyString(), anyString())).thenReturn(SendResult.failure("PROVIDER_ERROR"));

        dispatchJob.dispatch();

        Map<String, Object> row = requestRow(id);
        assertThat(row.get("status")).isEqualTo("NOT_SEND");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3);
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("locked_until")).isNull();
    }

    @Test
    @DisplayName("lease: второй инстанс не захватывает ту же запись, после истечения лиза — захватывает")
    void claimBatchRespectsLease() {
        UUID id = create();

        assertThat(requests.claimBatch("instance-a", 10, 60_000)).containsExactly(id);
        Map<String, Object> firstClaim = requestRow(id);
        assertThat(firstClaim.get("locked_by")).isEqualTo("instance-a");
        assertThat(firstClaim.get("locked_until")).isNotNull();
        assertThat(((Number) firstClaim.get("attempts")).intValue()).isEqualTo(1);

        assertThat(requests.claimBatch("instance-b", 10, 60_000)).isEmpty();

        jdbc.update("update sms.sms_request set locked_until = now() - interval '1 second' "
                + "where id = cast(? as uuid)", id.toString());
        assertThat(requests.claimBatch("instance-b", 10, 60_000)).containsExactly(id);

        Map<String, Object> secondClaim = requestRow(id);
        assertThat(secondClaim.get("locked_by")).isEqualTo("instance-b");
        assertThat(((Number) secondClaim.get("attempts")).intValue()).isEqualTo(2);

        requests.finishAttempt(id, true, null, null, "prov-lease");

        Map<String, Object> finished = requestRow(id);
        assertThat(finished.get("status")).isEqualTo("SUCCESS");
        assertThat(finished.get("provider_message_id")).isEqualTo("prov-lease");
        assertThat(finished.get("locked_until")).isNull();
        assertThat(finished.get("sent_at")).isNotNull();
    }

    private UUID create() {
        return UUID.fromString(json(api.create("dispatch-" + UUID.randomUUID(), PHONE).getBody())
                .get("id").asString());
    }

    private Map<String, Object> requestRow(UUID id) {
        return jdbc.queryForMap(REQUEST_BY_ID, id.toString());
    }
}
