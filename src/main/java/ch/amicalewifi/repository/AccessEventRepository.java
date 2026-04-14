package ch.amicalewifi.repository;

import ch.amicalewifi.model.AccessEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AccessEventRepository extends JpaRepository<AccessEvent, UUID> {

    @Query("SELECT e FROM AccessEvent e LEFT JOIN FETCH e.member WHERE e.occurredAt >= :start AND e.occurredAt < :end ORDER BY e.occurredAt DESC")
    List<AccessEvent> findByDate(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    List<AccessEvent> findByMemberIdOrderByOccurredAtDesc(UUID memberId);
}
