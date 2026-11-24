package dev.nosurprise.policy.blacklist;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface BlacklistRepository extends JpaRepository<BlacklistEntry, UUID> {

    /** Совпадение по телефону или по ФИО + дате рождения; отчество сравнивается, только если указано в записи. */
    @Query("""
            select count(e) > 0 from BlacklistEntry e
            where e.removedAt is null and (e.expiresAt is null or e.expiresAt > :now)
              and ((e.phone is not null and e.phone = :phone)
                or (e.lastNameNorm = :lastName and e.firstNameNorm = :firstName and e.birthDate = :birthDate
                    and (e.middleNameNorm is null or e.middleNameNorm = :middleName)))
            """)
    boolean matches(@Param("phone") String phone, @Param("lastName") String lastName,
                    @Param("firstName") String firstName, @Param("middleName") String middleName,
                    @Param("birthDate") LocalDate birthDate, @Param("now") Instant now);

    @Query("""
            select e from BlacklistEntry e
            where (:activeOnly = false or (e.removedAt is null and (e.expiresAt is null or e.expiresAt > :now)))
              and (:query is null or e.phone like :query or e.lastNameNorm like :query)
            """)
    Page<BlacklistEntry> search(@Param("query") String query, @Param("activeOnly") boolean activeOnly,
                                @Param("now") Instant now, Pageable pageable);
}
