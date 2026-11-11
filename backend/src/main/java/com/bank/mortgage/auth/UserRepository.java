package com.bank.mortgage.auth;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<User, Long> {

    @Query("SELECT * FROM users WHERE email = :email")
    Mono<User> findByEmail(String email);

    @Query("SELECT * FROM users WHERE email_verification_token = :token")
    Mono<User> findByEmailVerificationToken(String token);

    @Query("SELECT * FROM users WHERE oauth2_provider = :provider AND oauth2_provider_id = :providerId")
    Mono<User> findByOAuth2ProviderAndProviderId(String provider, String providerId);

    @Query("SELECT EXISTS(SELECT 1 FROM users WHERE email = :email)")
    Mono<Boolean> existsByEmail(String email);
}