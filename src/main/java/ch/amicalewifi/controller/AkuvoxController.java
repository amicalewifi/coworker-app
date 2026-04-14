package ch.amicalewifi.controller;

import ch.amicalewifi.model.*;
import ch.amicalewifi.service.ScanResult;
import ch.amicalewifi.service.ScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoint HTTP pour l'Akuvox A05S (borne NFC/RFID d'entrée).
 *
 * Configuration sur l'A05S :
 *   URL : http://HOST:8081/api/v1/akuvox/access?apiKey=VOTRE_CLE
 *   Méthode : GET
 *   Paramètre carte : cardNo (l'A05S envoie le UID en hex ou décimal selon config)
 *
 * Réponse :
 *   {"code": 0, "msg": "access_granted", "member": "Prénom Nom"}  → porte ouvre
 *   {"code": 1, "msg": "pack_exhausted"}                          → porte reste fermée
 */
@RestController
@RequestMapping("/api/v1/akuvox")
@RequiredArgsConstructor
@Slf4j
public class AkuvoxController {

    private final ScanService scanService;

    @Value("${amicale.akuvox.api-key}") private String apiKey;

    @GetMapping("/access")
    public Map<String, Object> access(
            @RequestParam String cardNo,
            @RequestHeader(value = "X-Api-Key", required = false) String headerKey,
            @RequestParam(value = "apiKey",      required = false) String paramKey) {

        // Authentification : clé en header ou en query param
        String key = headerKey != null ? headerKey : paramKey;
        if (key == null || !apiKey.equals(key)) {
            log.warn("Akuvox: tentative non autorisée depuis badge {}", cardNo);
            return Map.of("code", 401, "msg", "unauthorized");
        }

        // Normaliser l'UID (hex majuscules, sans espaces)
        String uid = cardNo.trim().toUpperCase().replace(" ", "").replace(":", "");

        log.info("Akuvox A05S — badge: {}", uid);
        ScanResult result = scanService.processBadgeAccess(uid);

        if (result instanceof ScanResult.Granted g) {
            log.info("Akuvox — accès accordé: {}", g.member().getDisplayName());
            return Map.of(
                    "code",   0,
                    "msg",    "access_granted",
                    "member", g.member().getDisplayName(),
                    "pack",   g.packRemaining() != null ? g.packRemaining().toPlainString() + "j" : "illimité"
            );
        }

        if (result instanceof ScanResult.NewMember nm) {
            log.warn("Akuvox — carte inconnue: {}", nm.badgeUid());
            return Map.of("code", 1, "msg", "unknown_card");
        }

        ScanResult.Denied d = (ScanResult.Denied) result;
        log.info("Akuvox — accès refusé: {} ({})", uid, d.reason());
        return Map.of("code", 1, "msg", d.reason());
    }
}
