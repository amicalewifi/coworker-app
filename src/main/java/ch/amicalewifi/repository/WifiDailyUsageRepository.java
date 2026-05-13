package ch.amicalewifi.repository;

import ch.amicalewifi.model.WifiDailyUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WifiDailyUsageRepository extends JpaRepository<WifiDailyUsage, UUID> {

    Optional<WifiDailyUsage> findByMemberIdAndUsageDate(UUID memberId, LocalDate usageDate);
}
