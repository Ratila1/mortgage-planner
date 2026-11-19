package dev.nosurprise.loan.signing;

import dev.nosurprise.common.outbox.OutboxMessage;
import dev.nosurprise.common.outbox.OutboxPublisher;
import dev.nosurprise.common.otp.OtpCooldown;
import dev.nosurprise.customer.CustomerService;
import dev.nosurprise.customer.ProfileView;
import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanApplicationRepository;
import dev.nosurprise.loan.application.LoanApplicationService;
import dev.nosurprise.loan.application.LoanEventRecorder;
import dev.nosurprise.loan.application.LoanIncidentRecorder;
import dev.nosurprise.loan.application.LoanStatus;
import dev.nosurprise.loan.contract.LoanDocument;
import dev.nosurprise.loan.contract.LoanDocumentRepository;
import dev.nosurprise.smsclient.SmsClient;
import dev.nosurprise.smsclient.dto.SmsResponse;
import dev.nosurprise.telegram.TelegramRoutingKeys;
import dev.nosurprise.telegram.TelegramVerificationRepository;
import dev.nosurprise.telegram.VerificationRequested;
import dev.nosurprise.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SigningServiceTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID APP_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SMS_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String PHONE = "+375291234567";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final LoanApplicationService applications = mock(LoanApplicationService.class);
    private final LoanApplicationRepository repository = mock(LoanApplicationRepository.class);
    private final LoanDocumentRepository documents = mock(LoanDocumentRepository.class);
    private final LoanSignatureRepository signatures = mock(LoanSignatureRepository.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final UserService users = mock(UserService.class);
    private final SmsClient smsClient = mock(SmsClient.class);
    private final OtpCooldown cooldown = mock(OtpCooldown.class);
    private final LoanEventRecorder recorder = mock(LoanEventRecorder.class);
    private final LoanIncidentRecorder incidents = mock(LoanIncidentRecorder.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final TelegramVerificationRepository verifications = mock(TelegramVerificationRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private SigningService service;

    @BeforeEach
    void setUp() {
        LoanApplication application = mock(LoanApplication.class);
        when(application.getStatus()).thenReturn(LoanStatus.APPROVED);
        when(application.isOfferExpired(NOW)).thenReturn(false);
        when(applications.getOwned(USER_ID, APP_ID)).thenReturn(application);

        LoanDocument contract = mock(LoanDocument.class);
        when(contract.getSha256()).thenReturn("contract-sha256");
        when(documents.findByApplicationIdAndType(APP_ID, LoanDocument.TYPE_CONTRACT))
                .thenReturn(Optional.of(contract));

        ProfileView profile = new ProfileView(USER_ID, "Doe", "John", null, PHONE, true,
                BigDecimal.TEN, NOW, 1L);
        when(customers.findProfile(USER_ID)).thenReturn(Optional.of(profile));
        when(smsClient.createSms(anyString(), eq(PHONE)))
                .thenReturn(SmsResponse.builder().id(SMS_ID).build());
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service = new SigningService(applications, repository, documents, signatures, customers, users,
                smsClient, cooldown, recorder, incidents, outbox, verifications,
                transactionManager, Clock.fixed(NOW, ZoneOffset.UTC), redisTemplate);
    }

    @Test
    @DisplayName("verification.requested содержит smsId созданной SMS, а не новый случайный идентификатор")
    void publishesCreatedSmsIdInVerificationRequested() {
        UUID verificationId = service.requestCode(USER_ID, APP_ID);

        assertThat(verificationId).isNotEqualTo(SMS_ID);
        ArgumentCaptor<OutboxMessage> message = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outbox).publish(eq(TelegramRoutingKeys.VERIFICATION_REQUESTED), eq("telegram-verification"),
                eq(verificationId), message.capture());
        assertThat(message.getValue()).isInstanceOf(VerificationRequested.class);
        VerificationRequested event = (VerificationRequested) message.getValue();
        assertThat(event.smsId()).isEqualTo(SMS_ID);
        assertThat(event.verificationId()).isEqualTo(verificationId);
        assertThat(event.userId()).isEqualTo(USER_ID);
    }
}
