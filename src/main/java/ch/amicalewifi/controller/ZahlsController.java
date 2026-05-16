package ch.amicalewifi.controller;

import ch.amicalewifi.model.ConfHourPackType;
import ch.amicalewifi.model.MembershipType;
import ch.amicalewifi.model.PrintPackType;
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

            @SuppressWarnings("unchecked")
            Map<String, Object> invoice = (Map<String, Object>) transaction.get("invoice");
            String referenceId = invoice != null ? (String) invoice.get("referenceId") : null;

            // Statuts non-confirmés : on logge mais on n'agit pas.
            // Pour refunded/chargeback on log au niveau WARN avec un appel à
            // action admin (retrait manuel des crédits/pack accordés) — l'auto-
            // revocation est volontairement hors scope pour éviter les
            // double-soustractions sur bugs de duplicata webhook.
            if (!"confirmed".equalsIgnoreCase(status)) {
                if ("refunded".equalsIgnoreCase(status) || "chargeback".equalsIgnoreCase(status)) {
                    log.warn("Zahls webhook: paiement remboursé (status={}, ref={}) — ACTION ADMIN REQUISE : retirer manuellement les crédits/pack accordés",
                            status, referenceId);
                } else {
                    log.info("Zahls webhook: status non-confirmé ignoré (status={}, ref={})",
                            status, referenceId);
                }
                return ResponseEntity.ok().build();
            }

            if (invoice == null) {
                log.warn("Zahls webhook: transaction confirmée sans 'invoice'");
                return ResponseEntity.ok().build();
            }
            if (referenceId == null || !referenceId.contains(":")) {
                log.warn("Zahls webhook: referenceId invalide: {}", referenceId);
                return ResponseEntity.ok().build();
            }

            // Format : "{memberId}:{MembershipType}"  ou  "{memberId}:PRINT:{PrintPackType}"
            String[] parts   = referenceId.split(":", 2);
            UUID     memberId = UUID.fromString(parts[0]);
            String   kind    = parts[1];

            if (kind.startsWith("PRINT:")) {
                PrintPackType pack = PrintPackType.valueOf(kind.substring(6));
                memberService.addPrintCredits(memberId, pack);
                log.info("Zahls webhook: crédits impression — membre={} pack={}", memberId, pack);
            } else if (kind.startsWith("CONFCREDIT:")) {
                ConfHourPackType pack = ConfHourPackType.valueOf(kind.substring(11));
                memberService.addConfCredits(memberId, pack);
                log.info("Zahls webhook: crédits conf — membre={} pack={}", memberId, pack);
            } else {
                MembershipType membership = MembershipType.valueOf(kind);
                memberService.renewPack(memberId, membership);
                log.info("Zahls webhook: pack renouvelé — membre={} formule={}", memberId, membership);
            }

        } catch (Exception e) {
            // On répond toujours 200 pour éviter les retentatives Payrexx
            log.error("Zahls webhook erreur: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }
}
