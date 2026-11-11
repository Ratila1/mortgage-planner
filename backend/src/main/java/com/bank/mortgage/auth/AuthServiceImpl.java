package com.bank.mortgage.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TransactionalOperator transactionalOperator;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthServiceImpl(UserRepository userRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           OtpCodeRepository otpCodeRepository,
                           JwtUtil jwtUtil,
                           PasswordEncoder passwordEncoder,
                           EmailService emailService,
                           TransactionalOperator transactionalOperator) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.transactionalOperator = transactionalOperator;
    }

    @Override
    public Mono<AuthResponse> register(RegisterRequest request) {
        validateRegisterRequest(request);
        return userRepository.existsByEmail(request.email())
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        return Mono.<AuthResponse>error(new EmailAlreadyExistsException("Email already registered"));
                    }
                    return createUserAndSendVerification(request);
                });
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (request.email() == null || request.email().trim().isEmpty()) {
            throw new IllegalArgumentException("Email must not be empty");
        }
        if (request.password() == null || request.password().isEmpty()) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        if (request.name() == null || request.name().trim().isEmpty()) {
            throw new IllegalArgumentException("Name must not be empty");
        }
    }

    private Mono<AuthResponse> createUserAndSendVerification(RegisterRequest request) {
        String verificationToken = generateSecureToken();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(24));

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .name(request.name())
                .emailVerified(false)
                .emailVerificationToken(verificationToken)
                .emailVerificationTokenExpiresAt(expiresAt)
                .roles(Set.of(Role.USER))
                .build();

        return userRepository.save(user)
                .flatMap(savedUser -> emailService.sendVerificationEmail(savedUser.email(), savedUser.name(), verificationToken)
                        .thenReturn(savedUser))
                .map(this::toAuthResponseWithoutTokens);
    }

    private AuthResponse toAuthResponseWithoutTokens(User user) {
        return new AuthResponse(null, null, user.id(), user.email(), user.name(), user.roles());
    }

    @Override
    public Mono<AuthResponse> login(LoginRequest request) {
        return userRepository.findByEmail(request.email())
                .switchIfEmpty(Mono.error(new InvalidCredentialsException("Invalid email or password")))
                .filter(user -> passwordEncoder.matches(request.password(), user.passwordHash()))
                .switchIfEmpty(Mono.error(new InvalidCredentialsException("Invalid email or password")))
                .filter(User::isEnabled)
                .switchIfEmpty(Mono.error(new EmailNotVerifiedException("Email not verified")))
                .flatMap(user -> generateTokensAndSaveRefresh(user));
    }

    @Override
    public Mono<AuthResponse> refresh(String refreshToken) {
        if (!jwtUtil.validateToken(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            return Mono.error(new InvalidTokenException("Invalid refresh token"));
        }

        String tokenHash = hashToken(refreshToken);
        Long userId = jwtUtil.getUserIdFromToken(refreshToken);

        return refreshTokenRepository.findValidByTokenHash(tokenHash)
                .switchIfEmpty(Mono.error(new InvalidTokenException("Refresh token revoked or expired")))
                .flatMap(storedToken -> {
                    // Revoke old token (rotation)
                    return refreshTokenRepository.revokeByTokenHash(tokenHash)
                            .then(userRepository.findById(userId))
                            .switchIfEmpty(Mono.error(new InvalidTokenException("User not found")))
                            .flatMap(user -> generateTokensAndSaveRefresh(user));
                });
    }

    @Override
    public Mono<Void> logout(String refreshToken) {
        if (!jwtUtil.validateToken(refreshToken) || !jwtUtil.isRefreshToken(refreshToken)) {
            return Mono.error(new InvalidTokenException("Invalid refresh token"));
        }
        String tokenHash = hashToken(refreshToken);
        return refreshTokenRepository.revokeByTokenHash(tokenHash).then();
    }

    @Override
    public Mono<Void> confirmEmail(String token) {
        return userRepository.findByEmailVerificationToken(token)
                .switchIfEmpty(Mono.error(new InvalidTokenException("Invalid or expired verification token")))
                .filter(user -> user.emailVerificationTokenExpiresAt().isAfter(Instant.now()))
                .switchIfEmpty(Mono.error(new InvalidTokenException("Verification token expired")))
                .flatMap(user -> {
                    User updated = new User(
                            user.id(), user.email(), user.passwordHash(), user.name(),
                            true, null, null,
                            user.oauth2Provider(), user.oauth2ProviderId(),
                            user.createdAt(), Instant.now(), user.roles()
                    );
                    return userRepository.save(updated).then();
                });
    }

    @Override
    public Mono<Void> requestOtp(OtpRequest request) {
        String code = generateOtpCode();
        String codeHash = hashOtpCode(code);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));

        OtpCode otpCode = OtpCode.builder()
                .email(request.email())
                .codeHash(codeHash)
                .purpose(request.purpose())
                .expiresAt(expiresAt)
                .build();

        return otpCodeRepository.save(otpCode)
                .flatMap(saved -> emailService.sendOtpEmail(request.email(), code, request.purpose()))
                .then();
    }

    @Override
    public Mono<AuthResponse> verifyOtp(OtpVerifyRequest request) {
        String codeHash = hashOtpCode(request.code());

        return otpCodeRepository.findLatestValidByEmailAndPurpose(request.email(), request.purpose())
                .filter(otp -> codeHash.equals(otp.codeHash()))
                .switchIfEmpty(Mono.error(new InvalidTokenException("Invalid or expired OTP code")))
                .flatMap(otp -> otpCodeRepository.markAsUsed(otp.id())
                        .then(handleOtpPurpose(request.email(), request.purpose())));
    }

    private Mono<AuthResponse> handleOtpPurpose(String email, OtpCode.OtpPurpose purpose) {
        return switch (purpose) {
            case LOGIN -> userRepository.findByEmail(email)
                    .switchIfEmpty(Mono.error(new InvalidCredentialsException("User not found")))
                    .filter(User::isEnabled)
                    .switchIfEmpty(Mono.error(new EmailNotVerifiedException("Email not verified")))
                    .flatMap(this::generateTokensAndSaveRefresh);
            case REGISTER_CONFIRM -> userRepository.findByEmail(email)
                    .switchIfEmpty(Mono.error(new InvalidCredentialsException("User not found")))
                    .flatMap(user -> {
                        User updated = new User(
                                user.id(), user.email(), user.passwordHash(), user.name(),
                                true, null, null,
                                user.oauth2Provider(), user.oauth2ProviderId(),
                                user.createdAt(), Instant.now(), user.roles()
                        );
                        return userRepository.save(updated)
                                .flatMap(this::generateTokensAndSaveRefresh);
                    });
            case EMAIL_CHANGE -> Mono.error(new UnsupportedOperationException("Email change not implemented"));
        };
    }

    @Override
    public Mono<AuthResponse> processOAuth2User(String provider, String providerId, String email, String name) {
        return userRepository.findByOAuth2ProviderAndProviderId(provider, providerId)
                .flatMap(user -> {
                    // Update name if changed
                    if (!user.name().equals(name)) {
                        User updated = new User(
                                user.id(), user.email(), user.passwordHash(), name,
                                user.emailVerified(), user.emailVerificationToken(),
                                user.emailVerificationTokenExpiresAt(),
                                user.oauth2Provider(), user.oauth2ProviderId(),
                                user.createdAt(), Instant.now(), user.roles()
                        );
                        return userRepository.save(updated);
                    }
                    return Mono.just(user);
                })
                .switchIfEmpty(
                        userRepository.findByEmail(email)
                                .flatMap(existingUser -> {
                                    // Link OAuth2 to existing account
                                    User updated = new User(
                                            existingUser.id(), existingUser.email(), existingUser.passwordHash(),
                                            existingUser.name(), existingUser.emailVerified(),
                                            existingUser.emailVerificationToken(),
                                            existingUser.emailVerificationTokenExpiresAt(),
                                            provider, providerId,
                                            existingUser.createdAt(), Instant.now(), existingUser.roles()
                                    );
                                    return userRepository.save(updated);
                                })
                                .switchIfEmpty(
                                        userRepository.save(User.builder()
                                                .email(email)
                                                .name(name)
                                                .emailVerified(true)
                                                .oauth2Provider(provider)
                                                .oauth2ProviderId(providerId)
                                                .roles(Set.of(Role.USER))
                                                .build())
                                )
                )
                .flatMap(this::generateTokensAndSaveRefresh);
    }

    private Mono<AuthResponse> generateTokensAndSaveRefresh(User user) {
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);
        String refreshTokenHash = hashToken(refreshToken);
        Instant expiresAt = jwtUtil.getExpirationFromToken(refreshToken);

        // Revoke any existing refresh tokens for this user
        return refreshTokenRepository.revokeAllByUserId(user.id())
                .then(refreshTokenRepository.save(RefreshToken.builder()
                        .userId(user.id())
                        .tokenHash(refreshTokenHash)
                        .expiresAt(expiresAt)
                        .build()))
                .thenReturn(new AuthResponse(
                        accessToken, refreshToken, user.id(), user.email(), user.name(), user.roles()
                ));
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String generateOtpCode() {
        int code = 100000 + secureRandom.nextInt(900000); // 6 digits
        return String.valueOf(code);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String hashOtpCode(String code) {
        return hashToken(code);
    }

    // Exception classes
    public static class EmailAlreadyExistsException extends RuntimeException {
        public EmailAlreadyExistsException(String message) { super(message); }
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException(String message) { super(message); }
    }

    public static class EmailNotVerifiedException extends RuntimeException {
        public EmailNotVerifiedException(String message) { super(message); }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) { super(message); }
    }
}