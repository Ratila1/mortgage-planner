package dev.nosurprise.loan.type;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoanTypeRepository extends JpaRepository<LoanType, UUID> {

    List<LoanType> findAllByActiveTrueOrderByName();

    List<LoanType> findAllByOrderByName();
}
