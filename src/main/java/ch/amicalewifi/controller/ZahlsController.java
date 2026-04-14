package ch.amicalewifi.controller;

import ch.amicalewifi.model.MembershipType;
import ch.amicalewifi.service.MemberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Webhook zahls.ch (Payrexx) — déclenché automatiquement après chaque paiement.
 *
 * zahls.ch envoie un POST JSON à cette URL quand un paiement est confirmé.
 * On extrait le referenceId "{memberId}:{MembershipType}" pour renouveler le pack.
 *
 * Configuration webhook dans le backend zahls.ch (login.zahls.ch) → Webhooks :
 *   URL : https://votre-domaine.ch/api/v1/zahls/webhook
 *   Format : JSON
 */
@RestController
@RequestMapping("/api/v1/zahls")
@RequiredArgsConstructor
@Slf4j
public class ZahlsController {

    private final MemberService memberService;

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(@RequestBody Map<String, Object> payload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> transaction = (Map<String, Object>) payload.get("transaction");
            if (transaction == null) {
                log.warn("Zahls webhook: payload sans 'transaction'");
                return ResponseEntity.ok().build();
            }

            String status = (String) transaction.get("status");
            if (!"confirmed".equalsIgnoreCase(status)) {
                log.debug("Zahls webhook: status='{}' ignoré", status);
                return ResponseEntity.ok().build();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> invoice = (Map<String, Object>) transaction.get("invoice");
            if (invoice == null) {
                log.warn("Zahls webhook: transaction sans 'invoice'");
                return ResponseEntity.ok().build();
            }

            String referenceId = (String) invoice.get("referenceId");
            if (referenceId == null || !referenceId.contains(":")) {
                log.warn("Zahls webhook: referenceId invalide: {}", referenceId);
                return ResponseEntity.ok().build();
            }

            // Format : "{memberId}:{MembershipType}"
            String[] parts = referenceId.split(":", 2);
            UUID memberId = UUID.fromString(parts[0]);
            MembershipType membership = MembershipType.valueOf(parts[1]);

            memberService.renewPack(memberId, membership);
            log.info("Zahls webhook: pack renouvelé — membre={} formule={}", memberId, membership);

        } catch (Exception e) {
            // On répond toujours 200 pour éviter les retentatives Payrexx
            log.error("Zahls webhook erreur: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }
}
