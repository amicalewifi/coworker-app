package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${amicale.business.opening-hour}") private int openH;
    @Value("${amicale.business.closing-hour}") private int closeH;

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
        if (member.isPermanent()) {
            Presence p = savePresence(member, PresenceType.FULL_DAY, LocalDate.now());
            saveEvent(member, badgeUid, AccessEventType.ENTRY_GRANTED, PresenceType.FULL_DAY, null, null);
            return new ScanResult.Granted(member, p, null, null);
        }
        saveEvent(member, badgeUid, AccessEventType.ENTRY_GRANTED, null, null, null);
        return new ScanResult.Granted(member, null, member.getPackUnitsRemaining(), member.getHalfDaysRemaining());
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

        if (member.isPermanent()) {
            Presence p = savePresence(member, presenceType, date);
            saveEvent(member, uid, AccessEventType.ENTRY_GRANTED, presenceType, new BigDecimal("1.0"), null);
            return new ScanResult.Granted(member, p, null, null);
        }

        if (member.getMembership() == MembershipType.JOURNEE_ESSAI) {
            Presence p = savePresence(member, PresenceType.TRIAL, date);
            saveEvent(member, uid, AccessEventType.ENTRY_GRANTED, PresenceType.TRIAL, BigDecimal.ZERO, null);
            return new ScanResult.Granted(member, p, null, null);
        }

        // Only enforce opening hours for same-day registration
        if (!date.isAfter(LocalDate.now())) {
            int hour = LocalTime.now().getHour();
            if (hour < openH || hour >= closeH) {
                saveEvent(member, uid, AccessEventType.ENTRY_DENIED, null, null, "outside_hours");
                return new ScanResult.Denied("outside_hours", member);
            }
        }

        BigDecimal needed    = presenceType.getUnits();
        BigDecimal remaining = member.getPackUnitsRemaining() != null
                ? member.getPackUnitsRemaining() : BigDecimal.ZERO;
        if (remaining.compareTo(needed) < 0) {
            saveEvent(member, uid, AccessEventType.ENTRY_DENIED, presenceType, null, "pack_exhausted");
            return new ScanResult.Denied("pack_exhausted", member);
        }

        boolean alreadyCheckedIn = presenceRepo.findByMemberIdAndDateAndPresenceType(
                member.getId(), date, presenceType).isPresent();

        Presence p = savePresence(member, presenceType, date);

        Member updated;
        if (alreadyCheckedIn) {
            updated = member;
        } else {
            member.setPackUnitsUsed(member.getPackUnitsUsed().add(needed));
            updated = memberRepo.save(member);
        }
        saveEvent(member, uid, AccessEventType.ENTRY_GRANTED, presenceType, alreadyCheckedIn ? BigDecimal.ZERO : needed, null);

        return new ScanResult.Granted(updated, p, updated.getPackUnitsRemaining(), updated.getHalfDaysRemaining());
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
