package ch.amicalewifi.repository;

import ch.amicalewifi.model.Presence;
import ch.amicalewifi.model.PresenceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface PresenceRepository extends JpaRepository<Presence, UUID> {

    @Query("SELECT p FROM Presence p JOIN FETCH p.member WHERE p.date = :date AND p.status = 'ACTIVE' ORDER BY p.checkedInAt ASC")
    List<Presence> findTodayActive(@Param("date") LocalDate date);

    List<Presence> findByMemberIdOrderByDateDescCheckedInAtDesc(UUID memberId);

    boolean existsByMemberIdAndDateAndPresenceType(UUID memberId, LocalDate date, PresenceType type);

    @Query("SELECT COUNT(p) FROM Presence p WHERE p.date = :date AND p.status = 'ACTIVE'")
    long countTodayActive(@Param("date") LocalDate date);
}
