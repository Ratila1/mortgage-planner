package dev.nosurprise.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {

    List<CustomerProfile> findAllByPhone(String phone);
}
