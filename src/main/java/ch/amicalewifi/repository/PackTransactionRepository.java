package ch.amicalewifi.repository;

import ch.amicalewifi.model.PackTransaction;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PackTransactionRepository extends JpaRepository<PackTransaction, UUID> {

    @EntityGraph(attributePaths = {"member"})
    List<PackTransaction> findByMemberIdOrderByCreatedAtDesc(UUID memberId);

    @EntityGraph(attributePaths = {"member"})
    List<PackTransaction> findByCreatedAtBetweenOrderByMemberLastNameAscCreatedAtDesc(
            LocalDateTime from, LocalDateTime to);
}
