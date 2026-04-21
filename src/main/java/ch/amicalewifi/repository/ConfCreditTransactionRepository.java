package ch.amicalewifi.repository;

import ch.amicalewifi.model.ConfCreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ConfCreditTransactionRepository extends JpaRepository<ConfCreditTransaction, UUID> {
    List<ConfCreditTransaction> findByMemberIdOrderByCreatedAtDesc(UUID memberId);
}
