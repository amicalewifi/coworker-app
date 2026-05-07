package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class WifiPresenceService {

    private final UnifiService       unifiService;
    private final MemberRepository   memberRepo;
    private final PresenceRepository presenceRepo;

    @Value("${amicale.unifi.wifi-presence-enabled:true}")  private boolean enabled;
    @Value("${amicale.unifi.wifi-presence-am-end:12:30}")  private String amEndStr;

    private static final int DETECTION_THRESHOLD = 3;

    // memberId → DayDetection
    private final Map<UUID, DayDetection> detections = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${amicale.unifi.wifi-presence-poll-ms:180000}")
    @Transactional
    public void poll() {
        if (!enabled) return;
        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();
        LocalTime amEnd = LocalTime.parse(amEndStr);

        // Purge entries from previous days
        detections.entrySet().removeIf(e -> !e.getValue().date.equals(today));

        List<String> macs = unifiService.getConnectedClientMacs();
        if (macs.isEmpty()) return;

        for (String mac : macs) {
            memberRepo.findByWifiMac(mac).ifPresent(member -> {
                if (!member.isActive()) return;
                DayDetection d = detections.computeIfAbsent(member.getId(), id -> new DayDetection(today));

                if (now.isBefore(amEnd)) {
                    d.amCount++;
                } else {
                    d.pmCount++;
                }

                handleDetection(member, d, today, amEnd);
            });
        }
    }

    private void handleDetection(Member member, DayDetection d, LocalDate today, LocalTime amEnd) {
        List<Presence> existing = presenceRepo.findActiveByMemberAndDate(member.getId(), today);
        boolean hasFullDay = existing.stream().anyMatch(p -> p.getPresenceType() == PresenceType.FULL_DAY);
        if (hasFullDay) return;

        boolean hasHalfAm = existing.stream().anyMatch(p -> p.getPresenceType() == PresenceType.HALF_AM);
        boolean hasHalfPm = existing.stream().anyMatch(p -> p.getPresenceType() == PresenceType.HALF_PM);

        if (d.amCount == DETECTION_THRESHOLD && !hasHalfAm) {
            createPresence(member, PresenceType.HALF_AM, today);
        }

        if (d.pmCount == DETECTION_THRESHOLD) {
            if (hasHalfAm) {
                upgradeToFullDay(member, existing.stream()
                        .filter(p -> p.getPresenceType() == PresenceType.HALF_AM)
                        .findFirst().orElseThrow());
            } else if (!hasHalfPm) {
                createPresence(member, PresenceType.HALF_PM, today);
            }
        }
    }

    private void createPresence(Member member, PresenceType type, LocalDate date) {
        if (member.getMembership() == MembershipType.JOURNEE_ESSAI) return;

        BigDecimal needed    = type.getUnits();
        BigDecimal remaining = member.getPackUnitsRemaining() != null ? member.getPackUnitsRemaining() : BigDecimal.ZERO;

        if (!member.isPermanent() && remaining.compareTo(needed) < 0) {
            log.info("WiFi présence refusée (pack épuisé): {} · {}", member.getDisplayName(), type);
            return;
        }

        presenceRepo.save(Presence.builder()
                .member(member).date(date).presenceType(type)
                .status(PresenceStatus.ACTIVE).checkedInAt(LocalDateTime.now())
                .unitsConsumed(needed).unitaire(false).build());

        if (!member.isPermanent()) {
            member.setPackUnitsUsed(member.getPackUnitsUsed().add(needed));
            memberRepo.save(member);
        }
        log.info("WiFi présence créée: {} · {}", member.getDisplayName(), type);
    }

    private void upgradeToFullDay(Member member, Presence halfAm) {
        BigDecimal extra = new BigDecimal("0.5");
        if (!member.isPermanent()) {
            BigDecimal remaining = member.getPackUnitsRemaining() != null ? member.getPackUnitsRemaining() : BigDecimal.ZERO;
            if (remaining.compareTo(extra) < 0) {
                log.info("WiFi upgrade HALF_AM→FULL_DAY refusé (pack épuisé): {}", member.getDisplayName());
                return;
            }
            member.setPackUnitsUsed(member.getPackUnitsUsed().add(extra));
            memberRepo.save(member);
        }
        halfAm.setPresenceType(PresenceType.FULL_DAY);
        halfAm.setUnitsConsumed(new BigDecimal("1.0"));
        presenceRepo.save(halfAm);
        log.info("WiFi upgrade HALF_AM→FULL_DAY: {}", member.getDisplayName());
    }

    private static class DayDetection {
        final LocalDate date;
        int amCount;
        int pmCount;
        DayDetection(LocalDate date) { this.date = date; }
    }
}
