package dev.nosurprise.mortgage.auth;

import java.math.BigDecimal;
import reactor.core.publisher.Mono;

public interface EmailService {
    Mono<Void> sendVerificationEmail(String email, String name, String token);
    Mono<Void> sendOtpEmail(String email, String code, OtpCode.OtpPurpose purpose);
    Mono<Void> sendRateChangeNotification(String email, String name, String bankName,
                                           BigDecimal oldRate, BigDecimal newRate,
                                           BigDecimal oldPayment, BigDecimal newPayment);
}







