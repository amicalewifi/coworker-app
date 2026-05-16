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
import java.util.stream.Collectors;

/**
 * Décompte des unités de pack basé sur le temps de connexion WiFi.
 *
 * Modèle métier :
 *   < 30 min/jour                  → 0 unité
 *   30 min ≤ temps < 4 h 30 /jour  → 0.5 unité
 *   ≥ 4 h 30/jour                  → 1.0 unité
 *
 * Multi-appareil : on stocke l'UNION (un membre = une timeline). Avoir
 * deux appareils connectés en simultané ne consomme pas deux fois.
 * Permanents et essais (JOURNEE_ESSAI) ne consomment rien.
 *
 * Job de minuit : révoque les MACs des membres qui n'ont plus accès
 * (pack épuisé sans renouvellement, contrat expiré).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WifiUsagePoller {

    private final UnifiService              unifi;
    private final MemberWifiMacRepository   macRepo;
    private final MemberRepository          memberRepo;
    private final WifiDailyUsageRepository  usageRepo;
    private final WifiAccessService         accessService;

    @Value("${amicale.wifi.poll-interval-ms:120000}")        private long pollIntervalMs;
    @Value("${amicale.wifi.half-day-threshold-seconds:1800}") private int halfDaySec;
    @Value("${amicale.wifi.full-day-threshold-seconds:18000}") private int fullDaySec;

    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal FULL = new BigDecimal("1.0");

    /**
     * Poll régulier des clients connectés. Pour chaque membre détecté,
     * incrémente le compteur de secondes du jour et décompte les unités
     * de pack au passage des seuils (30 min, 4 h 30).
     */
    @Scheduled(fixedDelayString = "${amicale.wifi.poll-interval-ms:120000}")
    @Transactional
    public void poll() {
        List<Map<String, Object>> clients;
        try {
            clients = unifi.getConnectedClients();
        } catch (Exception e) {
            log.warn("WifiUsagePoller: UniFi indisponible — {}", e.getMessage());
            return;
        }
        if (clients.isEmpty()) return;

        List<String> connectedMacs = clients.stream()
                .map(c -> c.get("mac"))
                .filter(m -> m instanceof String)
                .map(m -> UnifiService.normalizeMac((String) m))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (connectedMacs.isEmpty()) return;

        List<MemberWifiMac> known = macRepo.findAllByMacIn(connectedMacs);
        if (known.isEmpty()) return;

        // Regroupe les MACs connectées par membre (union — une journée, peu importe
        // le nombre d'appareils).
        Map<UUID, List<MemberWifiMac>> byMember = known.stream()
                .collect(Collectors.groupingBy(m -> m.getMember().getId()));

        LocalDate today = LocalDate.now(ZURICH);
        LocalDateTime now = LocalDateTime.now();
        long maxDeltaSec = Math.min(pollIntervalMs / 1000 * 2, 600); // protège des pauses (max 10 min/poll)

        for (Map.Entry<UUID, List<MemberWifiMac>> entry : byMember.entrySet()) {
            UUID memberId = entry.getKey();
            Member member = memberRepo.findById(memberId).orElse(null);
            if (member == null || !member.isActive()) continue;

            WifiDailyUsage usage = usageRepo.findByMemberIdAndUsageDate(memberId, today)
                    .orElseGet(() -> usageRepo.save(WifiDailyUsage.builder()
                            .member(member).usageDate(today)
                            .seconds(0).unitsCharged(BigDecimal.ZERO)
                            .lastPollAt(now).build()));

            long deltaSec = Math.max(0, Duration.between(usage.getLastPollAt(), now).getSeconds());
            deltaSec = Math.min(deltaSec, maxDeltaSec);
            // Première observation de la journée : on ne crédite que la fenêtre du poll.
            if (usage.getSeconds() == 0 && deltaSec > pollIntervalMs / 1000) {
                deltaSec = pollIntervalMs / 1000;
            }

            usage.setSeconds(usage.getSeconds() + (int) deltaSec);
            usage.setLastPollAt(now);

            for (MemberWifiMac m : entry.getValue()) {
                m.setLastSeenAt(now);
            }
            macRepo.saveAll(entry.getValue());

            chargeIfThresholdCrossed(member, usage);
            usageRepo.save(usage);
        }
    }

    private void chargeIfThresholdCrossed(Member member, WifiDailyUsage usage) {
        if (member.isPermanent()) return;
        if (member.getMembership() == MembershipType.JOURNEE_ESSAI) return;

        BigDecimal alreadyCharged = usage.getUnitsCharged();

        if (usage.getSeconds() >= fullDaySec && alreadyCharged.compareTo(FULL) < 0) {
            BigDecimal delta = FULL.subtract(alreadyCharged);
            member.setPackUnitsUsed(member.getPackUnitsUsed().add(delta));
            member.setUpdatedAt(LocalDateTime.now());
            memberRepo.save(member);
            usage.setUnitsCharged(FULL);
            accessService.audit(member, null, "CHARGED_FULL",
                    "seconds=" + usage.getSeconds());
            log.info("WiFi pack full-day: {} (+{} unité, restant: {})",
                    member.getDisplayName(), delta, member.getPackUnitsRemaining());
        } else if (usage.getSeconds() >= halfDaySec && alreadyCharged.compareTo(HALF) < 0) {
            BigDecimal delta = HALF.subtract(alreadyCharged);
            member.setPackUnitsUsed(member.getPackUnitsUsed().add(delta));
            member.setUpdatedAt(LocalDateTime.now());
            memberRepo.save(member);
            usage.setUnitsCharged(HALF);
            accessService.audit(member, null, "CHARGED_HALF",
                    "seconds=" + usage.getSeconds());
            log.info("WiFi pack half-day: {} (+{} unité, restant: {})",
                    member.getDisplayName(), delta, member.getPackUnitsRemaining());
        }
    }

    /**
     * Job de minuit : pour chaque MAC enregistrée, si son membre n'a plus accès
     * (pack épuisé sans renouvellement, badge expiré…), on révoque l'autorisation
     * UniFi. À leur prochaine association, ces appareils seront renvoyés sur le
     * portail captif.
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "Europe/Zurich")
    @Transactional
    public void revokeExpiredAtMidnight() {
        log.info("WiFi revoke-job: démarrage");
        int revoked = 0;
        for (MemberWifiMac mac : macRepo.findAll()) {
            Member member = mac.getMember();
            if (accessService.hasAccess(member)) continue;
            if (unifi.unauthorizeGuest(mac.getMac())) {
                accessService.audit(member, mac.getMac(), "KICK_MIDNIGHT", null);
                revoked++;
            }
        }
        log.info("WiFi revoke-job: {} MAC(s) révoquée(s)", revoked);
    }
}
