package ch.amicalewifi.repository;

import ch.amicalewifi.model.PrinterJob;
import ch.amicalewifi.model.PrintJobStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PrinterJobRepository extends JpaRepository<PrinterJob, UUID> {
    @EntityGraph(attributePaths = {"member"})
    List<PrinterJob> findByStatusOrderByCreatedAtAsc(PrintJobStatus status);

    @EntityGraph(attributePaths = {"member"})
    List<PrinterJob> findByMemberIdOrderByCreatedAtDesc(UUID memberId);

    @EntityGraph(attributePaths = {"member"})
    List<PrinterJob> findByStatusAndCreatedAtBetweenOrderByMemberLastNameAscCreatedAtDesc(
            PrintJobStatus status, LocalDateTime from, LocalDateTime to);

    @EntityGraph(attributePaths = {"member"})
    List<PrinterJob> findByCreatedAtBetweenOrderByMemberLastNameAscCreatedAtDesc(
            LocalDateTime from, LocalDateTime to);
}
