package ch.amicalewifi.repository;

import ch.amicalewifi.model.MemberWifiMac;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemberWifiMacRepository extends JpaRepository<MemberWifiMac, UUID> {

    Optional<MemberWifiMac> findByMac(String mac);

    List<MemberWifiMac> findAllByMemberIdOrderByCreatedAtAsc(UUID memberId);

    List<MemberWifiMac> findAllByMacIn(List<String> macs);
}
