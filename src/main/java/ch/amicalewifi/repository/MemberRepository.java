package ch.amicalewifi.repository;

import ch.amicalewifi.model.Member;
import ch.amicalewifi.model.MembershipType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemberRepository extends JpaRepository<Member, UUID> {

    Optional<Member> findByEmail(String email);
    Optional<Member> findByBadgeUid(String uid);
    Optional<Member> findByQrToken(UUID token);
    Optional<Member> findByPrintToken(UUID token);
    Optional<Member> findByWifiMac(String wifiMac);
    List<Member>     findByActiveTrueOrderByLastNameAsc();

    @Query("SELECT m FROM Member m ORDER BY m.active DESC, m.lastName ASC")
    List<Member>     findAllOrderByActiveDescLastNameAsc();
    boolean          existsByEmail(String email);
    boolean          existsByBadgeUid(String uid);
    List<Member>     findByMembershipAndActiveTrue(MembershipType membership);

    @Query("""
        SELECT m FROM Member m WHERE m.active = true
          AND m.membership IN ('PACK_5J','PACK_10J','PACK_15J')
          AND (m.packExpires <= :alertDate
            OR (m.packUnitsTotal - m.packUnitsUsed) <= 1.0)
        ORDER BY m.packExpires ASC NULLS LAST
    """)
    List<Member> findPackAlerts(@Param("alertDate") LocalDate alertDate);
}
