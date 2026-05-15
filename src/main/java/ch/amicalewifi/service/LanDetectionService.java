package ch.amicalewifi.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Détermine si une requête HTTP arrive depuis le LAN du coworking ou
 * d'ailleurs (cellulaire, WiFi maison, etc.).
 *
 * Mécanique: l'IP publique du routeur Swisscom côté coworking (côté WAN
 * du tunnel WireGuard que le VPS établit pour atteindre le contrôleur
 * UniFi) est l'IP source visible pour toute requête NATée depuis le LAN.
 * On la lit depuis un fichier que met à jour toutes les 5 min un cron sur
 * le hôte (cf. coworker-deploy/wg-peer-export.sh).
 *
 * Le fichier est lu paresseusement avec un cache mémoire de 60 s pour
 * éviter un read disque par requête. Si le fichier est absent ou vide
 * (déploiement initial, panne du cron), on renvoie {@code false} pour
 * toute requête — meilleur faux-négatif qu'un faux-positif.
 */
@Service
@Slf4j
public class LanDetectionService {

    @Value("${amicale.wifi.coworker-wan-ip-file:/app/state/wg-peer-ip}")
    private String wgPeerIpPath;

    private static final long CACHE_TTL_MS = 60_000L;

    private final AtomicReference<CacheEntry> cache =
            new AtomicReference<>(new CacheEntry(null, 0L));

    public boolean isLanRequest(HttpServletRequest request) {
        if (request == null) return false;
        String coworkerWanIp = getCoworkerWanIp();
        if (coworkerWanIp == null || coworkerWanIp.isBlank()) return false;
        String remote = request.getRemoteAddr();
        return coworkerWanIp.equals(remote);
    }

    private String getCoworkerWanIp() {
        long now = System.currentTimeMillis();
        CacheEntry current = cache.get();
        if (now < current.expiresAt()) return current.ip();
        String ip = readFromFile();
        cache.set(new CacheEntry(ip, now + CACHE_TTL_MS));
        return ip;
    }

    private String readFromFile() {
        try {
            String content = Files.readString(Path.of(wgPeerIpPath)).trim();
            return content.isEmpty() ? null : content;
        } catch (IOException e) {
            log.debug("LAN detection: cannot read WG peer IP from {}: {}",
                    wgPeerIpPath, e.getMessage());
            return null;
        }
    }

    private record CacheEntry(String ip, long expiresAt) {}
}
