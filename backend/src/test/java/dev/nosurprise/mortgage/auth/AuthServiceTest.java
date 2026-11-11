package dev.nosurprise.mortgage.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private OtpCodeRepository otpCodeRepository;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;
    @Mock
    private TransactionalOperator transactionalOperator;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService = new AuthServiceImpl(
                userRepository,
                refreshTokenRepository,
                otpCodeRepository,
                jwtUtil,
                passwordEncoder,
                emailService,
                transactionalOperator
        );
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes());
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Nested
    @DisplayName("Регистрация (К8)")
    class RegisterTests {

        @Test
        @DisplayName("register_withNewEmail_createsUserAndSendsVerification")
        void register_withNewEmail_createsUserAndSendsVerification() {
            // given
            RegisterRequest request = new RegisterRequest("test@example.com", "password123", "Test User");
            when(userRepository.existsByEmail("test@example.com")).thenReturn(Mono.just(false));
            when(passwordEncoder.encode("password123")).thenReturn("hashed_password");
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                return Mono.just(new User(
                        1L, user.email(), user.passwordHash(), user.name(),
                        user.emailVerified(), user.emailVerificationToken(),
                        user.emailVerificationTokenExpiresAt(),
                        user.oauth2Provider(), user.oauth2ProviderId(),
                        user.createdAt(), user.updatedAt(), user.roles()
                ));
            });
            when(emailService.sendVerificationEmail(anyString(), anyString(), anyString())).thenReturn(Mono.empty());

            // when
            Mono<AuthResponse> result = authService.register(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.accessToken()).isNull();
                        assertThat(response.refreshToken()).isNull();
                        assertThat(response.email()).isEqualTo("test@example.com");
                        assertThat(response.name()).isEqualTo("Test User");
                        assertThat(response.roles()).contains(Role.USER);
                    })
                    .verifyComplete();

            verify(userRepository).save(any(User.class));
            verify(emailService).sendVerificationEmail(eq("test@example.com"), eq("Test User"), anyString());
        }

        @Test
        @DisplayName("register_withExistingEmail_throwsEmailAlreadyExistsException")
        void register_withExistingEmail_throwsEmailAlreadyExistsException() {
            // given
            RegisterRequest request = new RegisterRequest("existing@example.com", "password123", "Test User");
            when(userRepository.existsByEmail("existing@example.com")).thenReturn(Mono.just(true));

            // when/then
            StepVerifier.create(authService.register(request))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.EmailAlreadyExistsException &&
                                    throwable.getMessage().contains("already registered"))
                    .verify();
        }

        @Test
        @DisplayName("register_withEmptyEmail_throwsException")
        void register_withEmptyEmail_throwsException() {
            // given
            RegisterRequest request = new RegisterRequest("", "password123", "Test User");

            // when/then
            assertThatThrownBy(() -> authService.register(request).block())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("register_withEmptyPassword_throwsException")
        void register_withEmptyPassword_throwsException() {
            // given
            RegisterRequest request = new RegisterRequest("test@example.com", "", "Test User");

            // when/then
            assertThatThrownBy(() -> authService.register(request).block())
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("register_withNullEmail_throwsException")
        void register_withNullEmail_throwsException() {
            // given
            RegisterRequest request = new RegisterRequest(null, "password123", "Test User");

            // when/then
            assertThatThrownBy(() -> authService.register(request).block())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Подтверждение email (К8)")
    class ConfirmEmailTests {

        @Test
        @DisplayName("confirmEmail_withValidToken_verifiesEmail")
        void confirmEmail_withValidToken_verifiesEmail() {
            // given
            String token = "valid-token";
            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    false, token, Instant.now().plusSeconds(3600),
                    null, null, Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            when(userRepository.findByEmailVerificationToken(token)).thenReturn(Mono.just(user));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            // when
            Mono<Void> result = authService.confirmEmail(token);

            // then
            StepVerifier.create(result).verifyComplete();
            verify(userRepository).save(argThat(u -> u.emailVerified() && u.emailVerificationToken() == null));
        }

        @Test
        @DisplayName("confirmEmail_withExpiredToken_throwsException")
        void confirmEmail_withExpiredToken_throwsException() {
            // given
            String token = "expired-token";
            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    false, token, Instant.now().minusSeconds(3600),
                    null, null, Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            when(userRepository.findByEmailVerificationToken(token)).thenReturn(Mono.just(user));

            // when/then
            StepVerifier.create(authService.confirmEmail(token))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidTokenException &&
                                    throwable.getMessage().contains("expired"))
                    .verify();
        }

        @Test
        @DisplayName("confirmEmail_withNonExistentToken_throwsException")
        void confirmEmail_withNonExistentToken_throwsException() {
            // given
            when(userRepository.findByEmailVerificationToken("unknown")).thenReturn(Mono.empty());

            // when/then
            StepVerifier.create(authService.confirmEmail("unknown"))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidTokenException &&
                                    throwable.getMessage().contains("Invalid or expired"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Логин (К9)")
    class LoginTests {

        @Test
        @DisplayName("login_withValidCredentials_returnsAccessAndRefreshTokens")
        void login_withValidCredentials_returnsAccessAndRefreshTokens() {
            // given
            LoginRequest request = new LoginRequest("test@example.com", "password123");
            User user = new User(
                    1L, "test@example.com", "hashed_password", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(user));
            when(passwordEncoder.matches("password123", "hashed_password")).thenReturn(true);
            when(jwtUtil.generateAccessToken(any(User.class))).thenReturn("access-token");
            when(jwtUtil.generateRefreshToken(any(User.class))).thenReturn("refresh-token");
            when(jwtUtil.getExpirationFromToken("refresh-token")).thenReturn(Instant.now().plusSeconds(86400 * 30));
            when(refreshTokenRepository.revokeAllByUserId(1L)).thenReturn(Mono.empty());
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(Mono.empty());

            // when
            Mono<AuthResponse> result = authService.login(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.accessToken()).isEqualTo("access-token");
                        assertThat(response.refreshToken()).isEqualTo("refresh-token");
                        assertThat(response.userId()).isEqualTo(1L);
                        assertThat(response.email()).isEqualTo("test@example.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("login_withInvalidPassword_throwsInvalidCredentialsException")
        void login_withInvalidPassword_throwsInvalidCredentialsException() {
            // given
            LoginRequest request = new LoginRequest("test@example.com", "wrong_password");
            User user = new User(
                    1L, "test@example.com", "hashed_password", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(user));
            when(passwordEncoder.matches("wrong_password", "hashed_password")).thenReturn(false);

            // when/then
            StepVerifier.create(authService.login(request))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidCredentialsException)
                    .verify();
        }

        @Test
        @DisplayName("login_withUnverifiedEmail_throwsEmailNotVerifiedException")
        void login_withUnverifiedEmail_throwsEmailNotVerifiedException() {
            // given
            LoginRequest request = new LoginRequest("test@example.com", "password123");
            User user = new User(
                    1L, "test@example.com", "hashed_password", "Test User",
                    false, "token", Instant.now().plusSeconds(3600),
                    null, null, Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(user));
            when(passwordEncoder.matches("password123", "hashed_password")).thenReturn(true);

            // when/then
            StepVerifier.create(authService.login(request))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.EmailNotVerifiedException)
                    .verify();
        }

        @Test
        @DisplayName("login_withNonExistentUser_throwsInvalidCredentialsException")
        void login_withNonExistentUser_throwsInvalidCredentialsException() {
            // given
            LoginRequest request = new LoginRequest("unknown@example.com", "password123");
            when(userRepository.findByEmail("unknown@example.com")).thenReturn(Mono.empty());

            // when/then
            StepVerifier.create(authService.login(request))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidCredentialsException)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Refresh токен и ротация (К9)")
    class RefreshTests {

        @Test
        @DisplayName("refresh_withInvalidToken_throwsException")
        void refresh_withInvalidToken_throwsException() {
            // given
            String refreshToken = "invalid-token";
            when(jwtUtil.validateToken(refreshToken)).thenReturn(false);

            // when/then
            StepVerifier.create(authService.refresh(refreshToken))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidTokenException)
                    .verify();
        }

        @Test
        @DisplayName("refresh_withAccessTokenInsteadOfRefresh_throwsException")
        void refresh_withAccessTokenInsteadOfRefresh_throwsException() {
            // given
            String accessToken = "access-token";
            when(jwtUtil.validateToken(accessToken)).thenReturn(true);
            when(jwtUtil.isRefreshToken(accessToken)).thenReturn(false);

            // when/then
            StepVerifier.create(authService.refresh(accessToken))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidTokenException)
                    .verify();
        }

        @Test
        @DisplayName("refresh_withRevokedToken_throwsException")
        void refresh_withRevokedToken_throwsException() {
            // given
            String refreshToken = "revoked-token";
            String tokenHash = hashToken(refreshToken);
            when(jwtUtil.validateToken(refreshToken)).thenReturn(true);
            when(jwtUtil.isRefreshToken(refreshToken)).thenReturn(true);
            when(jwtUtil.getUserIdFromToken(refreshToken)).thenReturn(1L);
            when(refreshTokenRepository.findValidByTokenHash(tokenHash)).thenReturn(Mono.empty());

            // when/then
            StepVerifier.create(authService.refresh(refreshToken))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidTokenException &&
                                    throwable.getMessage().contains("revoked or expired"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Logout (К9)")
    class LogoutTests {

        @Test
        @DisplayName("logout_withValidToken_revokesRefreshToken")
        void logout_withValidToken_revokesRefreshToken() {
            // given
            String refreshToken = "valid-refresh-token";
            String tokenHash = hashToken(refreshToken);
            when(jwtUtil.validateToken(refreshToken)).thenReturn(true);
            when(jwtUtil.isRefreshToken(refreshToken)).thenReturn(true);
            when(refreshTokenRepository.revokeByTokenHash(tokenHash)).thenReturn(Mono.empty());

            // when
            Mono<Void> result = authService.logout(refreshToken);

            // then
            StepVerifier.create(result).verifyComplete();
            verify(refreshTokenRepository).revokeByTokenHash(tokenHash);
        }

        @Test
        @DisplayName("logout_withInvalidToken_throwsException")
        void logout_withInvalidToken_throwsException() {
            // given
            String refreshToken = "invalid-token";
            when(jwtUtil.validateToken(refreshToken)).thenReturn(false);

            // when/then
            StepVerifier.create(authService.logout(refreshToken))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidTokenException)
                    .verify();
        }
    }

    @Nested
    @DisplayName("OAuth2 (К10)")
    class OAuth2Tests {

        @Test
        @DisplayName("processOAuth2User_withNewUser_createsAndReturnsTokens")
        void processOAuth2User_withNewUser_createsAndReturnsTokens() {
            // given
            when(userRepository.findByOAuth2ProviderAndProviderId("google", "google-123")).thenReturn(Mono.empty());
            when(userRepository.findByEmail("test@gmail.com")).thenReturn(Mono.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User u = invocation.getArgument(0);
                return Mono.just(new User(
                        1L, u.email(), u.passwordHash(), u.name(),
                        u.emailVerified(), u.emailVerificationToken(),
                        u.emailVerificationTokenExpiresAt(),
                        u.oauth2Provider(), u.oauth2ProviderId(),
                        u.createdAt(), u.updatedAt(), u.roles()
                ));
            });
            when(jwtUtil.generateAccessToken(any(User.class))).thenReturn("access-token");
            when(jwtUtil.generateRefreshToken(any(User.class))).thenReturn("refresh-token");
            when(jwtUtil.getExpirationFromToken(anyString())).thenReturn(Instant.now().plusSeconds(86400 * 30));
            when(refreshTokenRepository.revokeAllByUserId(1L)).thenReturn(Mono.empty());
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(Mono.empty());

            // when
            Mono<AuthResponse> result = authService.processOAuth2User("google", "google-123", "test@gmail.com", "Test User");

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.accessToken()).isEqualTo("access-token");
                        assertThat(response.refreshToken()).isEqualTo("refresh-token");
                        assertThat(response.email()).isEqualTo("test@gmail.com");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("processOAuth2User_withExistingOAuth2User_updatesAndReturnsTokens")
        void processOAuth2User_withExistingOAuth2User_updatesAndReturnsTokens() {
            // given
            User existing = new User(
                    1L, "test@gmail.com", "hashed", "Old Name",
                    true, null, null, "google", "google-123",
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            when(userRepository.findByOAuth2ProviderAndProviderId("google", "google-123")).thenReturn(Mono.just(existing));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(jwtUtil.generateAccessToken(any(User.class))).thenReturn("access-token");
            when(jwtUtil.generateRefreshToken(any(User.class))).thenReturn("refresh-token");
            when(jwtUtil.getExpirationFromToken(anyString())).thenReturn(Instant.now().plusSeconds(86400 * 30));
            when(refreshTokenRepository.revokeAllByUserId(1L)).thenReturn(Mono.empty());
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(Mono.empty());

            // when
            Mono<AuthResponse> result = authService.processOAuth2User("google", "google-123", "test@gmail.com", "New Name");

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.name()).isEqualTo("New Name");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("processOAuth2User_withExistingEmailLinksAccount")
        void processOAuth2User_withExistingEmailLinksAccount() {
            // given
            User existing = new User(
                    1L, "test@gmail.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            when(userRepository.findByOAuth2ProviderAndProviderId("github", "github-456")).thenReturn(Mono.empty());
            when(userRepository.findByEmail("test@gmail.com")).thenReturn(Mono.just(existing));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(jwtUtil.generateAccessToken(any(User.class))).thenReturn("access-token");
            when(jwtUtil.generateRefreshToken(any(User.class))).thenReturn("refresh-token");
            when(jwtUtil.getExpirationFromToken(anyString())).thenReturn(Instant.now().plusSeconds(86400 * 30));
            when(refreshTokenRepository.revokeAllByUserId(1L)).thenReturn(Mono.empty());
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(Mono.empty());

            // when
            Mono<AuthResponse> result = authService.processOAuth2User("github", "github-456", "test@gmail.com", "Test User");

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.email()).isEqualTo("test@gmail.com");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("OTP / Magic Link (К11)")
    class OtpTests {

        @Test
        @DisplayName("requestOtp_withValidEmail_sendsCode")
        void requestOtp_withValidEmail_sendsCode() {
            // given
            OtpRequest request = new OtpRequest("test@example.com", OtpCode.OtpPurpose.LOGIN);
            when(otpCodeRepository.save(any(OtpCode.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(emailService.sendOtpEmail(anyString(), anyString(), any(OtpCode.OtpPurpose.class))).thenReturn(Mono.empty());

            // when
            Mono<Void> result = authService.requestOtp(request);

            // then
            StepVerifier.create(result).verifyComplete();
            verify(otpCodeRepository).save(any(OtpCode.class));
            verify(emailService).sendOtpEmail(eq("test@example.com"), anyString(), eq(OtpCode.OtpPurpose.LOGIN));
        }

        @Test
        @DisplayName("verifyOtp_withValidCode_returnsTokens")
        void verifyOtp_withValidCode_returnsTokens() {
            // given
            OtpVerifyRequest request = new OtpVerifyRequest("test@example.com", "123456", OtpCode.OtpPurpose.LOGIN);
            String codeHash = hashToken("123456");
            OtpCode otpCode = OtpCode.builder()
                    .id(1L)
                    .email("test@example.com")
                    .codeHash(codeHash)
                    .purpose(OtpCode.OtpPurpose.LOGIN)
                    .expiresAt(Instant.now().plusSeconds(600))
                    .used(false)
                    .build();

            when(otpCodeRepository.findLatestValidByEmailAndPurpose("test@example.com", OtpCode.OtpPurpose.LOGIN))
                    .thenReturn(Mono.just(otpCode));
            when(otpCodeRepository.markAsUsed(1L)).thenReturn(Mono.empty());

            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            when(userRepository.findByEmail("test@example.com")).thenReturn(Mono.just(user));
            when(jwtUtil.generateAccessToken(user)).thenReturn("access-token");
            when(jwtUtil.generateRefreshToken(user)).thenReturn("refresh-token");
            when(jwtUtil.getExpirationFromToken(anyString())).thenReturn(Instant.now().plusSeconds(86400 * 30));
            when(refreshTokenRepository.revokeAllByUserId(1L)).thenReturn(Mono.empty());
            when(refreshTokenRepository.save(any(RefreshToken.class))).thenReturn(Mono.empty());

            // when
            Mono<AuthResponse> result = authService.verifyOtp(request);

            // then
            StepVerifier.create(result)
                    .assertNext(response -> {
                        assertThat(response.accessToken()).isEqualTo("access-token");
                        assertThat(response.refreshToken()).isEqualTo("refresh-token");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("verifyOtp_withInvalidCode_throwsException")
        void verifyOtp_withInvalidCode_throwsException() {
            // given
            OtpVerifyRequest request = new OtpVerifyRequest("test@example.com", "wrong_code", OtpCode.OtpPurpose.LOGIN);
            OtpCode otpCode = OtpCode.builder()
                    .id(1L)
                    .email("test@example.com")
                    .codeHash(hashToken("correct_code"))
                    .purpose(OtpCode.OtpPurpose.LOGIN)
                    .expiresAt(Instant.now().plusSeconds(600))
                    .used(false)
                    .build();
            when(otpCodeRepository.findLatestValidByEmailAndPurpose("test@example.com", OtpCode.OtpPurpose.LOGIN))
                    .thenReturn(Mono.just(otpCode));

            // when/then
            StepVerifier.create(authService.verifyOtp(request))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidTokenException &&
                                    throwable.getMessage().contains("Invalid or expired"))
                    .verify();
        }

        @Test
        @DisplayName("verifyOtp_withExpiredCode_throwsException")
        void verifyOtp_withExpiredCode_throwsException() {
            // given
            OtpVerifyRequest request = new OtpVerifyRequest("test@example.com", "123456", OtpCode.OtpPurpose.LOGIN);
            when(otpCodeRepository.findLatestValidByEmailAndPurpose("test@example.com", OtpCode.OtpPurpose.LOGIN))
                    .thenReturn(Mono.empty());

            // when/then
            StepVerifier.create(authService.verifyOtp(request))
                    .expectErrorMatches(throwable ->
                            throwable instanceof AuthServiceImpl.InvalidTokenException)
                    .verify();
        }
    }
}







