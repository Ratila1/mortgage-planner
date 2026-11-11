package dev.nosurprise.mortgage.notification;

import reactor.core.publisher.Mono;

public interface NotificationService {
    Mono<Void> sendEmailNotification(String email, String subject, String html);
}







