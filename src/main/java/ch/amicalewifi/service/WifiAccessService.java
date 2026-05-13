package ch.amicalewifi.service;

import ch.amicalewifi.model.*;
import ch.amicalewifi.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;

/**
 * Lie une MAC à un membre (portail captif UniFi), et décide si on appelle
 * l'API d'autorisation invité d'UniFi (cmd/stamgr).
 *
 * Règle d'accès — un membre a droit au WiFi si :
 *   - badge actif, non expiré, ET
 *   - contrat permanent, OU
 *   - pack avec unités restantes > 0, OU
 *   - journée déjà décomptée aujourd'hui (on laisse finir la journée).
 *
 * Durée d'autorisation passée à UniFi : jusqu'à expiration du pack
 * (packExpires). Un job de minuit (WifiUsagePoller) révoque les membres
 * qui n'ont plus accès le lendemain.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class WifiAccessService {

    private final MemberWifiMacRepository  macRepo;
    private final WifiAuditRepository      auditRepo;
    private final WifiDailyUsageRepository usageRepo;
    private final UnifiService             unifi;

    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    /**
     * Attache la MAC au membre. En cas de conflit (MAC déjà liée à un autre
     * membre — ex: appareil revendu), réassigne au nouveau membre et écrit
     * un événement REASSIGNED dans wifi_audit.
     */
    public void bindMacToMember(String macRaw, Member member) {
        String mac = UnifiService.normalizeMac(macRaw);
        if (mac == null) {
            log.warn("bindMacToMember: MAC invalide {}", macRaw);
            return;
        }
        macRepo.findByMac(mac).ifPresentOrElse(existing -> {
            if (!existing.getMember().getId().equals(member.getId())) {
                Member previous = existing.getMember();
                log.info("MAC {} réassignée de {} → {}", mac,
                        previous.getEmail(), member.getEmail());
                audit(previous, mac, "REASSIGNED",
                        "Réassignée à " + member.getEmail());
                existing.setMember(member);
                existing.setCreatedAt(LocalDateTime.now());
                macRepo.save(existing);
            } else {
                existing.setLastSeenAt(LocalDateTime.now());
                macRepo.save(existing);
            }
        }, () -> {
            macRepo.save(MemberWifiMac.builder()
                    .member(member).mac(mac).build());
            audit(member, mac, "BOUND", null);
        });
    }

    /**
     * Tente d'autoriser la MAC sur UniFi. Si le membre n'a pas d'accès,
     * ne fait rien (l'utilisateur peut toujours visiter coworker.amicalewifi.ch
     * grâce au walled garden, mais pas le reste d'Internet).
     *
     * @return true si l'autorisation a été demandée à UniFi
     */
    public boolean tryAuthorize(Member member, String macRaw) {
        if (!hasAccess(member)) {
            log.info("WiFi refusé (pas d'accès) : {} · {}", member.getDisplayName(), macRaw);
            audit(member, macRaw, "DENIED_NO_PACK", null);
            return false;
        }
        long minutes = minutesUntilPackExpires(member);
        boolean ok = unifi.authorizeGuest(macRaw, minutes);
        audit(member, macRaw, ok ? "AUTHORIZED" : "DENIED_NO_PACK",
                ok ? ("minutes=" + minutes) : "UniFi authorize-guest failed");
        return ok;
    }

    /** Révoque une MAC sur UniFi (ex: suppression d'appareil, kick de minuit). */
    public void revoke(Member member, String macRaw, String reason) {
        if (unifi.unauthorizeGuest(macRaw)) {
            audit(member, macRaw, "UNAUTHORIZED", reason);
        }
    }

    /**
     * Vrai si le membre a actuellement droit au WiFi.
     * Note : on accepte aussi le cas « déjà décompté aujourd'hui » : si le
     * pack est tombé à 0 en cours de journée, on laisse finir la journée.
     */
    public boolean hasAccess(Member m) {
        if (m == null || !m.isActive()) return false;
        if (!m.isBadgeActive()) return false;
        if (m.getBadgeExpires() != null
                && m.getBadgeExpires().isBefore(LocalDate.now(ZURICH))) return false;
        if (m.isPermanent()) return true;
        if (m.getPackUnitsRemaining() != null
                && m.getPackUnitsRemaining().compareTo(BigDecimal.ZERO) > 0) return true;
        return usageRepo.findByMemberIdAndUsageDate(m.getId(), LocalDate.now(ZURICH))
                .map(u -> u.getUnitsCharged().compareTo(BigDecimal.ZERO) > 0)
                .orElse(false);
    }

    /**
     * Durée d'autorisation à passer à UniFi (minutes).
     * - Permanent sans packExpires : sentinelle ~10 ans.
     * - Sinon : nombre de minutes jusqu'à fin de packExpires (23:59 Europe/Zurich).
     */
    public long minutesUntilPackExpires(Member m) {
        long fallback = 60L * 24 * 365 * 10; // ~10 ans
        if (m.isPermanent() && m.getPackExpires() == null) return fallback;
        if (m.getPackExpires() == null) return 60L * 24; // sécurité : 24 h
        LocalDateTime now    = LocalDateTime.now(ZURICH);
        LocalDateTime expiry = m.getPackExpires().atTime(LocalTime.of(23, 59));
        long mins = Duration.between(now, expiry).toMinutes();
        return Math.max(60, mins); // au moins 1 h
    }

    public void audit(Member member, String mac, String event, String detail) {
        auditRepo.save(WifiAuditEvent.builder()
                .member(member).mac(mac == null ? null : UnifiService.normalizeMac(mac))
                .event(event).detail(detail).build());
    }
}
