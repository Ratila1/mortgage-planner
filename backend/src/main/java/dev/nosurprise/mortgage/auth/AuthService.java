package dev.nosurprise.mortgage.auth;

import reactor.core.publisher.Mono;

public interface AuthService {
    Mono<AuthResponse> register(RegisterRequest request);
    Mono<AuthResponse> login(LoginRequest request);
    Mono<AuthResponse> refresh(String refreshToken);
    Mono<Void> logout(String refreshToken);
    Mono<Void> confirmEmail(String token);
    Mono<Void> requestOtp(OtpRequest request);
    Mono<AuthResponse> verifyOtp(OtpVerifyRequest request);
    Mono<AuthResponse> processOAuth2User(String provider, String providerId, String email, String name);
}







