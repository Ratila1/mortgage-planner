package dev.nosurprise.mortgage.user;

import dev.nosurprise.mortgage.auth.User;
import dev.nosurprise.mortgage.auth.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Mono<User> getCurrentUser(String email) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new UserNotFoundException("User not found")));
    }

    @Override
    public Mono<User> updateProfile(String email, String name, String newEmail) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new UserNotFoundException("User not found")))
                .flatMap(user -> {
                    if (newEmail != null && !newEmail.equals(user.email())) {
                        return userRepository.existsByEmail(newEmail)
                                .flatMap(exists -> {
                                    if (Boolean.TRUE.equals(exists)) {
                                        return Mono.<User>error(new EmailAlreadyExistsException("Email already in use"));
                                    }
                                    User updated = new User(
                                            user.id(), newEmail, user.passwordHash(), name,
                                            user.emailVerified(), user.emailVerificationToken(),
                                            user.emailVerificationTokenExpiresAt(),
                                            user.oauth2Provider(), user.oauth2ProviderId(),
                                            user.createdAt(), java.time.Instant.now(), user.roles()
                                    );
                                    return userRepository.save(updated);
                                });
                    } else {
                        User updated = new User(
                                user.id(), user.email(), user.passwordHash(), name,
                                user.emailVerified(), user.emailVerificationToken(),
                                user.emailVerificationTokenExpiresAt(),
                                user.oauth2Provider(), user.oauth2ProviderId(),
                                user.createdAt(), java.time.Instant.now(), user.roles()
                        );
                        return userRepository.save(updated);
                    }
                });
    }

    @Override
    public Mono<User> findById(Long id) {
        return userRepository.findById(id)
                .switchIfEmpty(Mono.error(new UserNotFoundException("User not found")));
    }

    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(String message) { super(message); }
    }

    public static class EmailAlreadyExistsException extends RuntimeException {
        public EmailAlreadyExistsException(String message) { super(message); }
    }
}







