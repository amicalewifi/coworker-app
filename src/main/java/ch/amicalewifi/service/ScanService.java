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

    public ScanResult processScan(String badgeUid, PresenceType presenceType) {
        log.info("Scan badge: {} · {}", badgeUid, presenceType);
        Member member = memberRepo.findByBadgeUid(badgeUid).orElse(null);
        if (member == null) {
            saveEvent(null, badgeUid, AccessEventType.NEW_MEMBER_CREATED, null, null, null);
            return new ScanResult.NewMember(badgeUid);
        }
        return processScanForMember(member, badgeUid, presenceType);
    }

    public ScanResult processScanByToken(UUID qrToken, PresenceType presenceType) {
        log.info("Scan QR: {} · {}", qrToken, presenceType);
        Member member = memberRepo.findByQrToken(qrToken).orElse(null);
        if (member == null) {
            return new ScanResult.Denied("token_invalid", null);
        }
        return processScanForMember(member, "qr:" + qrToken, presenceType);
    }

    private ScanResult processScanForMember(Member member, String uid, PresenceType presenceType) {
        if (!member.isBadgeActive() ||
                (member.getBadgeExpires() != null && member.getBadgeExpires().isBefore(LocalDate.now()))) {
            saveEvent(member, uid, AccessEventType.ENTRY_DENIED, null, null, "badge_expired");
            return new ScanResult.Denied("badge_expired", member);
        }

        if (member.isPermanent()) {
            Presence p = savePresence(member, presenceType);
            saveEvent(member, uid, AccessEventType.ENTRY_GRANTED, presenceType, new BigDecimal("1.0"), null);
            return new ScanResult.Granted(member, p, null, null);
        }

        if (member.getMembership() == MembershipType.JOURNEE_ESSAI) {
            Presence p = savePresence(member, PresenceType.TRIAL);
            saveEvent(member, uid, AccessEventType.ENTRY_GRANTED, PresenceType.TRIAL, BigDecimal.ZERO, null);
            return new ScanResult.Granted(member, p, null, null);
        }

        int hour = LocalTime.now().getHour();
        if (hour < openH || hour >= closeH) {
            saveEvent(member, uid, AccessEventType.ENTRY_DENIED, null, null, "outside_hours");
            return new ScanResult.Denied("outside_hours", member);
        }

        BigDecimal needed    = presenceType.getUnits();
        BigDecimal remaining = member.getPackUnitsRemaining() != null
                ? member.getPackUnitsRemaining() : BigDecimal.ZERO;
        if (remaining.compareTo(needed) < 0) {
            saveEvent(member, uid, AccessEventType.ENTRY_DENIED, presenceType, null, "pack_exhausted");
            return new ScanResult.Denied("pack_exhausted", member);
        }

        Presence p = savePresence(member, presenceType);
        saveEvent(member, uid, AccessEventType.ENTRY_GRANTED, presenceType, needed, null);

        Member updated = memberRepo.findById(member.getId()).orElse(member);
        return new ScanResult.Granted(updated, p, updated.getPackUnitsRemaining(), updated.getHalfDaysRemaining());
    }

    private Presence savePresence(Member m, PresenceType type) {
        return presenceRepo.save(Presence.builder()
                .member(m).date(LocalDate.now()).presenceType(type)
                .status(PresenceStatus.ACTIVE).checkedInAt(LocalDateTime.now())
                .unitsConsumed(type.getUnits()).unitaire(type.isUnitaire())
                .build());
    }

    private void saveEvent(Member member, String uid, AccessEventType type,
                           PresenceType pt, BigDecimal units, String reason) {
        eventRepo.save(AccessEvent.builder()
                .member(member).badgeUid(uid).eventType(type)
                .presenceType(pt).unitsConsumed(units).deniedReason(reason)
                .occurredAt(LocalDateTime.now()).build());
    }
}
