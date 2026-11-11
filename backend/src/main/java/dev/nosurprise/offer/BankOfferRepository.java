package dev.nosurprise.offer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BankOfferRepository extends JpaRepository<BankOffer, UUID> {
    List<BankOffer> findAllByActiveTrue();
}