package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ScanService {

    private final MemberRepository      memberRepo;
    private final PresenceRepository    presenceRepo;
    private final AccessEventRepository eventRepo;

    /** Badge RFID Akuvox A05S — ouvre la porte, ne décompte pas de pack. */
    public ScanResult processBadgeAccess(String badgeUid) {
        log.info("Badge access (porte): {}", badgeUid);
        Member member = memberRepo.findByBadgeUid(badgeUid).orElse(null);
        if (member == null) {
            saveEvent(null, badgeUid, AccessEventType.NEW_MEMBER_CREATED, null, null, null);
            return new ScanResult.NewMember(badgeUid);
        }
        if (!member.isBadgeActive() ||
                (member.getBadgeExpires() != null && member.getBadgeExpires().isBefore(LocalDate.now()))) {
            saveEvent(member, badgeUid, AccessEventType.ENTRY_DENIED, null, null, "badge_expired");
            return new ScanResult.Denied("badge_expired", member);
        }
        // Le badge ouvre la porte et enregistre la présence. La consommation
        // d'unités de pack est désormais pilotée par le temps de connexion WiFi.
        Presence p = savePresence(member, PresenceType.FULL_DAY, LocalDate.now());
        saveEvent(member, badgeUid, AccessEventType.ENTRY_GRANTED, PresenceType.FULL_DAY, BigDecimal.ZERO, null);
        BigDecimal remaining = member.isPermanent() ? null : member.getPackUnitsRemaining();
        Integer halfDays    = member.isPermanent() ? null : member.getHalfDaysRemaining();
        return new ScanResult.Granted(member, p, remaining, halfDays);
    }

    private Presence savePresence(Member m, PresenceType type, LocalDate date) {
        return presenceRepo.findByMemberIdAndDateAndPresenceType(m.getId(), date, type)
                .orElseGet(() -> presenceRepo.save(Presence.builder()
                        .member(m).date(date).presenceType(type)
                        .status(PresenceStatus.ACTIVE).checkedInAt(LocalDateTime.now())
                        .unitsConsumed(type.getUnits()).unitaire(type.isUnitaire())
                        .build()));
    }

    private void saveEvent(Member member, String uid, AccessEventType type,
                           PresenceType pt, BigDecimal units, String reason) {
        eventRepo.save(AccessEvent.builder()
                .member(member).badgeUid(uid).eventType(type)
                .presenceType(pt).unitsConsumed(units).deniedReason(reason)
                .occurredAt(LocalDateTime.now()).build());
    }
}
