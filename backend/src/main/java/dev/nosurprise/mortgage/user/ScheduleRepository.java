package dev.nosurprise.mortgage.user;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Query;
import reactor.core.publisher.Mono;

public interface ScheduleRepository extends ReactiveMongoRepository<ScheduleDocument, String> {

    @Query("{ 'scheduleHash': ?0 }")
    Mono<ScheduleDocument> findByScheduleHash(String scheduleHash);
}







