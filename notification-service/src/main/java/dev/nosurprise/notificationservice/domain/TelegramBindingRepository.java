package dev.nosurprise.notificationservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TelegramBindingRepository extends JpaRepository<TelegramBinding, UUID> {
    Optional<TelegramBinding> findByPhone(String phone);
    Optional<TelegramBinding> findByTelegramId(Long telegramId);
}