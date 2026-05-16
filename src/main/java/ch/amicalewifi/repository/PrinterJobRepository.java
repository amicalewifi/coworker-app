package ch.amicalewifi.repository;

import ch.amicalewifi.model.PrinterJob;
import ch.amicalewifi.model.PrintJobStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    /**
     * Row-level lock for race-safe completion. Concurrent callers (claudine-proxy
     * pollAndComplete + the orphan-job sweeper) serialize here so a job cannot
     * be debited twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM PrinterJob j WHERE j.id = :id")
    Optional<PrinterJob> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Orphan jobs: PRINTING longer than the cutoff, with a Kyocera URI captured
     * so the sweeper can re-query the printer to finalize them.
     */
    @EntityGraph(attributePaths = {"member"})
    List<PrinterJob> findByStatusAndCreatedAtBeforeAndPrinterJobIdNotNull(
            PrintJobStatus status, LocalDateTime before);
}
