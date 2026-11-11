package com.bank.mortgage.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

@Table("users")
public record User(
        @Id Long id,
        String email,
        String passwordHash,
        String name,
        boolean emailVerified,
        String emailVerificationToken,
        Instant emailVerificationTokenExpiresAt,
        String oauth2Provider,
        String oauth2ProviderId,
        Instant createdAt,
        Instant updatedAt,
        Set<Role> roles
) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return emailVerified;
    }

    public static UserBuilder builder() {
        return new UserBuilder();
    }

    public static class UserBuilder {
        private Long id;
        private String email;
        private String passwordHash;
        private String name;
        private boolean emailVerified = false;
        private String emailVerificationToken;
        private Instant emailVerificationTokenExpiresAt;
        private String oauth2Provider;
        private String oauth2ProviderId;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = Instant.now();
        private Set<Role> roles = Set.of(Role.USER);

        public UserBuilder id(Long id) { this.id = id; return this; }
        public UserBuilder email(String email) { this.email = email; return this; }
        public UserBuilder passwordHash(String passwordHash) { this.passwordHash = passwordHash; return this; }
        public UserBuilder name(String name) { this.name = name; return this; }
        public UserBuilder emailVerified(boolean emailVerified) { this.emailVerified = emailVerified; return this; }
        public UserBuilder emailVerificationToken(String token) { this.emailVerificationToken = token; return this; }
        public UserBuilder emailVerificationTokenExpiresAt(Instant expiresAt) { this.emailVerificationTokenExpiresAt = expiresAt; return this; }
        public UserBuilder oauth2Provider(String provider) { this.oauth2Provider = provider; return this; }
        public UserBuilder oauth2ProviderId(String providerId) { this.oauth2ProviderId = providerId; return this; }
        public UserBuilder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public UserBuilder updatedAt(Instant updatedAt) { this.updatedAt = updatedAt; return this; }
        public UserBuilder roles(Set<Role> roles) { this.roles = roles; return this; }

        public User build() {
            return new User(id, email, passwordHash, name, emailVerified,
                    emailVerificationToken, emailVerificationTokenExpiresAt,
                    oauth2Provider, oauth2ProviderId, createdAt, updatedAt, roles);
        }
    }
}