package com.bank.mortgage.auth;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface RefreshTokenRepository extends ReactiveCrudRepository<RefreshToken, Long> {

    @Query("SELECT * FROM refresh_tokens WHERE token_hash = :tokenHash AND revoked = FALSE AND expires_at > NOW()")
    Mono<RefreshToken> findValidByTokenHash(String tokenHash);

    @Query("SELECT * FROM refresh_tokens WHERE user_id = :userId AND revoked = FALSE")
    Mono<RefreshToken> findValidByUserId(Long userId);

    @Query("UPDATE refresh_tokens SET revoked = TRUE WHERE user_id = :userId AND revoked = FALSE")
    Mono<Integer> revokeAllByUserId(Long userId);

    @Query("UPDATE refresh_tokens SET revoked = TRUE WHERE token_hash = :tokenHash")
    Mono<Integer> revokeByTokenHash(String tokenHash);
}