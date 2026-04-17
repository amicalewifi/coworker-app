package ch.amicalewifi.repository;

import ch.amicalewifi.model.PrintCreditTransaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PrintCreditTransactionRepository extends JpaRepository<PrintCreditTransaction, UUID> {

    @EntityGraph(attributePaths = {"member"})
    List<PrintCreditTransaction> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime from, LocalDateTime to);

    @EntityGraph(attributePaths = {"member"})
    List<PrintCreditTransaction> findByMemberIdOrderByCreatedAtDesc(UUID memberId);
}
