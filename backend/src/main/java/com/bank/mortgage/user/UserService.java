package com.bank.mortgage.user;

import com.bank.mortgage.auth.User;
import reactor.core.publisher.Mono;

public interface UserService {
    Mono<User> getCurrentUser(String email);
    Mono<User> updateProfile(String email, String name, String newEmail);
    Mono<User> findById(Long id);
}