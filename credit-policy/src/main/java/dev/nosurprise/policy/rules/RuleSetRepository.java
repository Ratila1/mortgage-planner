package dev.nosurprise.policy.rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RuleSetRepository extends JpaRepository<RuleSetEntity, Integer> {

    Optional<RuleSetEntity> findByActiveTrue();

    List<RuleSetEntity> findAllByOrderByVersionDesc();

    @Query("select coalesce(max(r.version), 0) from RuleSetEntity r")
    int maxVersion();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RuleSetEntity r set r.active = false where r.active = true")
    int deactivateAll();
}
