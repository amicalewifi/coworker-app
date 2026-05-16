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
 *   - pack tout juste épuisé : on tolère 30 min de grâce après le décompte
 *     qui a vidé le pack (cf. {@code packExhaustedAt}).
 *
 * Durée d'autorisation passée à UniFi : jusqu'à expiration du pack
 * (packExpires). WifiUsagePoller révoque activement les membres dont la
 * grâce a expiré, et un job de minuit nettoie le reste.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class WifiAccessService {

    private final MemberWifiMacRepository  macRepo;
    private final WifiAuditRepository      auditRepo;
    private final UnifiService             unifi;

    private static final long GRACE_MINUTES = 30;

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

    /**
     * Autorise (ou ré-autorise) toutes les MAC liées au membre. Appelé après
     * un renouvellement de pack: sans cela, les appareils déjà enregistrés
     * resteraient désautorisés à UniFi jusqu'à ce que le membre clique
     * manuellement sur "Ajouter cet appareil" depuis chaque appareil.
     *
     * Pour chaque MAC, on délègue à {@link #tryAuthorize(Member, String)} —
     * qui gère le gating hasAccess() et écrit AUTHORIZED ou DENIED_NO_PACK
     * dans wifi_audit.
     */
    public void authorizeAllDevices(Member member) {
        if (member == null) return;
        for (MemberWifiMac mac : macRepo.findAllByMemberIdOrderByCreatedAtAsc(member.getId())) {
            tryAuthorize(member, mac.getMac());
        }
    }

    /**
     * Désautorise toutes les MAC liées au membre auprès d'UniFi. Appelé quand
     * un appareil est retiré (revoke explicite) ou quand le pack expire.
     * On ne supprime pas les bindings ici — la table {@code member_wifi_macs}
     * reste la liste persistante des appareils du membre.
     */
    public void unauthorizeAllDevices(Member member) {
        if (member == null) return;
        for (MemberWifiMac mac : macRepo.findAllByMemberIdOrderByCreatedAtAsc(member.getId())) {
            if (unifi.unauthorizeGuest(mac.getMac())) {
                audit(member, mac.getMac(), "REVOKED", null);
            }
        }
    }

    /**
     * Vrai si le membre a actuellement droit au WiFi.
     * Après que le pack soit tombé à zéro, on tolère 30 min de grâce
     * (cf. {@link Member#getPackExhaustedAt()}) avant de couper l'accès.
     */
    public boolean hasAccess(Member m) {
        if (m == null || !m.isActive()) return false;
        if (!m.isBadgeActive()) return false;
        if (m.getBadgeExpires() != null
                && m.getBadgeExpires().isBefore(LocalDate.now(ZURICH))) return false;
        if (m.isPermanent()) return true;
        if (m.getPackUnitsRemaining() != null
                && m.getPackUnitsRemaining().compareTo(BigDecimal.ZERO) > 0) return true;
        LocalDateTime exhaustedAt = m.getPackExhaustedAt();
        if (exhaustedAt == null) return false;
        return Duration.between(exhaustedAt, LocalDateTime.now()).toMinutes() < GRACE_MINUTES;
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
