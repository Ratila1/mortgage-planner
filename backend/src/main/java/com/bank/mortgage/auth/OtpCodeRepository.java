package com.bank.mortgage.auth;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface OtpCodeRepository extends ReactiveCrudRepository<OtpCode, Long> {

    @Query("SELECT * FROM otp_codes WHERE email = :email AND purpose = :purpose AND used = FALSE AND expires_at > NOW() ORDER BY created_at DESC LIMIT 1")
    Mono<OtpCode> findLatestValidByEmailAndPurpose(String email, OtpCode.OtpPurpose purpose);

    @Query("UPDATE otp_codes SET used = TRUE WHERE id = :id")
    Mono<Integer> markAsUsed(Long id);
}