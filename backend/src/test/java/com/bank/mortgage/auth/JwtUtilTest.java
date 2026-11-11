package com.bank.mortgage.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.bank.mortgage.auth.User;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        // Secret must be at least 32 characters
        String secret = "this-is-a-test-secret-key-that-is-at-least-32-characters-long";
        jwtUtil = new JwtUtil(secret, 15, 30);
    }

    @Nested
    @DisplayName("Access Token")
    class AccessTokenTests {

        @Test
        @DisplayName("generateAccessToken_containsCorrectClaims")
        void generateAccessToken_containsCorrectClaims() {
            // given
            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );

            // when
            String token = jwtUtil.generateAccessToken(user);

            // then
            assertThat(token).isNotNull();
            assertThat(token).isNotEmpty();
            assertThat(jwtUtil.validateToken(token)).isTrue();
            assertThat(jwtUtil.isAccessToken(token)).isTrue();
            assertThat(jwtUtil.isRefreshToken(token)).isFalse();
            assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo(1L);
            assertThat(jwtUtil.getEmailFromToken(token)).isEqualTo("test@example.com");
            assertThat(jwtUtil.getRolesFromToken(token)).contains(Role.USER);
            assertThat(jwtUtil.getExpirationFromToken(token)).isAfter(Instant.now());
        }
    }

    @Nested
    @DisplayName("Refresh Token")
    class RefreshTokenTests {

        @Test
        @DisplayName("generateRefreshToken_containsCorrectClaims")
        void generateRefreshToken_containsCorrectClaims() {
            // given
            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );

            // when
            String token = jwtUtil.generateRefreshToken(user);

            // then
            assertThat(token).isNotNull();
            assertThat(token).isNotEmpty();
            assertThat(jwtUtil.validateToken(token)).isTrue();
            assertThat(jwtUtil.isRefreshToken(token)).isTrue();
            assertThat(jwtUtil.isAccessToken(token)).isFalse();
            assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo(1L);
            assertThat(jwtUtil.getEmailFromToken(token)).isEqualTo("test@example.com");
            assertThat(jwtUtil.getExpirationFromToken(token)).isAfter(Instant.now());
        }
    }

    @Nested
    @DisplayName("Token Validation")
    class ValidationTests {

        @Test
        @DisplayName("validateToken_withValidToken_returnsTrue")
        void validateToken_withValidToken_returnsTrue() {
            // given
            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            String token = jwtUtil.generateAccessToken(user);

            // when/then
            assertThat(jwtUtil.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("validateToken_withInvalidToken_returnsFalse")
        void validateToken_withInvalidToken_returnsFalse() {
            // when/then
            assertThat(jwtUtil.validateToken("invalid.token.string")).isFalse();
        }

        @Test
        @DisplayName("validateToken_withTamperedToken_returnsFalse")
        void validateToken_withTamperedToken_returnsFalse() {
            // given
            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            String token = jwtUtil.generateAccessToken(user);
            String tampered = token + "x"; // corrupt the signature

            // when/then
            assertThat(jwtUtil.validateToken(tampered)).isFalse();
        }
    }

    @Nested
    @DisplayName("Claims Extraction")
    class ClaimsTests {

        @Test
        @DisplayName("getUserIdFromToken_returnsCorrectUserId")
        void getUserIdFromToken_returnsCorrectUserId() {
            // given
            User user = new User(
                    42L, "test@example.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            String token = jwtUtil.generateAccessToken(user);

            // when
            Long userId = jwtUtil.getUserIdFromToken(token);

            // then
            assertThat(userId).isEqualTo(42L);
        }

        @Test
        @DisplayName("getEmailFromToken_returnsCorrectEmail")
        void getEmailFromToken_returnsCorrectEmail() {
            // given
            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            String token = jwtUtil.generateAccessToken(user);

            // when
            String email = jwtUtil.getEmailFromToken(token);

            // then
            assertThat(email).isEqualTo("test@example.com");
        }

        @Test
        @DisplayName("getRolesFromToken_returnsCorrectRoles")
        void getRolesFromToken_returnsCorrectRoles() {
            // given
            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER, Role.ADMIN)
            );
            String token = jwtUtil.generateAccessToken(user);

            // when
            Set<Role> roles = jwtUtil.getRolesFromToken(token);

            // then
            assertThat(roles).contains(Role.USER, Role.ADMIN);
        }

        @Test
        @DisplayName("getExpirationFromToken_returnsFutureInstant")
        void getExpirationFromToken_returnsFutureInstant() {
            // given
            User user = new User(
                    1L, "test@example.com", "hashed", "Test User",
                    true, null, null, null, null,
                    Instant.now(), Instant.now(), Set.of(Role.USER)
            );
            String token = jwtUtil.generateAccessToken(user);

            // when
            Instant expiry = jwtUtil.getExpirationFromToken(token);

            // then
            assertThat(expiry).isAfter(Instant.now());
        }
    }

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorTests {

        @Test
        @DisplayName("constructor_withNullSecret_throwsException")
        void constructor_withNullSecret_throwsException() {
            // when/then
            assertThatThrownBy(() -> new JwtUtil(null, 15, 30))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT secret must be configured");
        }

        @Test
        @DisplayName("constructor_withEmptySecret_throwsException")
        void constructor_withEmptySecret_throwsException() {
            // when/then
            assertThatThrownBy(() -> new JwtUtil("", 15, 30))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT secret must be configured");
        }

        @Test
        @DisplayName("constructor_withShortSecret_throwsException")
        void constructor_withShortSecret_throwsException() {
            // when/then
            assertThatThrownBy(() -> new JwtUtil("short", 15, 30))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32 characters");
        }

        @Test
        @DisplayName("constructor_withValidSecret_succeeds")
        void constructor_withValidSecret_succeeds() {
            // when/then
            assertThat(new JwtUtil("this-is-a-valid-secret-key-that-is-long-enough", 15, 30)).isNotNull();
        }
    }
}