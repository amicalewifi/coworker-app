package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

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

    public ScanResult processScanByToken(UUID qrToken, PresenceType presenceType) {
        return processScanByToken(qrToken, presenceType, LocalDate.now());
    }

    public ScanResult processScanByToken(UUID qrToken, PresenceType presenceType, LocalDate date) {
        log.info("Scan QR: {} · {} · {}", qrToken, presenceType, date);
        Member member = memberRepo.findByQrToken(qrToken).orElse(null);
        if (member == null) {
            return new ScanResult.Denied("token_invalid", null);
        }
        return processScanForMember(member, "qr:" + qrToken, presenceType, date);
    }

    private ScanResult processScanForMember(Member member, String uid, PresenceType presenceType) {
        return processScanForMember(member, uid, presenceType, LocalDate.now());
    }

    private ScanResult processScanForMember(Member member, String uid, PresenceType presenceType, LocalDate date) {
        if (!member.isBadgeActive() ||
                (member.getBadgeExpires() != null && member.getBadgeExpires().isBefore(LocalDate.now()))) {
            saveEvent(member, uid, AccessEventType.ENTRY_DENIED, null, null, "badge_expired");
            return new ScanResult.Denied("badge_expired", member);
        }

        // Le décompte des unités de pack est désormais piloté par le temps de
        // connexion WiFi (WifiUsagePoller). Le badge / QR ne consomme plus
        // d'unités : il enregistre uniquement la présence à des fins
        // analytiques et le passage de porte.
        PresenceType effectiveType = member.getMembership() == MembershipType.JOURNEE_ESSAI
                ? PresenceType.TRIAL : presenceType;
        Presence p = savePresence(member, effectiveType, date);
        saveEvent(member, uid, AccessEventType.ENTRY_GRANTED, effectiveType, BigDecimal.ZERO, null);
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
