package ch.amicalewifi.controller;

import ch.amicalewifi.model.Member;
import ch.amicalewifi.model.PrinterJob;
import ch.amicalewifi.service.IppPrintService;
import ch.amicalewifi.service.IppPrintService.InsufficientPrintCreditsException;
import ch.amicalewifi.service.IppPrintService.InvalidPrintRequestException;
import ch.amicalewifi.service.IppPrintService.InvalidPrintTokenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Endpoints HTTP appelés exclusivement par le container CUPS broker
 * (amicale-broker.py) sur le réseau loopback du VPS. Authentifié par un
 * header X-Print-Broker-Key partagé entre l'app et le broker (généré au
 * bootstrap, stocké dans .env). Pas exposé via Caddy.
 *
 * Le broker fait : authenticate -> submit -> [print sur Kyocera] -> complete/error.
 */
@RestController
@RequestMapping("/api/v1/print")
@RequiredArgsConstructor
@Slf4j
public class IppBrokerController {

    private final IppPrintService ippPrintService;

    @Value("${amicale.print.broker-key}") private String brokerKey;

    @PostMapping("/authenticate")
    public ResponseEntity<?> authenticate(@RequestHeader(value = "X-Print-Broker-Key", required = false) String key,
                                          @RequestBody AuthenticateRequest req) {
        if (!checkKey(key)) return unauthorized();
        Member m = ippPrintService.authenticate(req.token());
        return ResponseEntity.ok(new AuthenticateResponse(
                m.getId(), m.getDisplayName(), m.getEmail(),
                m.getPrintQuota() - m.getPrintUsed()));
    }

    @PostMapping("/submit")
    public ResponseEntity<?> submit(@RequestHeader(value = "X-Print-Broker-Key", required = false) String key,
                                    @RequestBody SubmitRequest req) {
        if (!checkKey(key)) return unauthorized();
        PrinterJob job = ippPrintService.submit(
                req.token(), req.pages(), req.filename(),
                req.copies() == 0 ? 1 : req.copies(),
                req.color(), req.duplex(), req.submittedUsername());
        int factor = req.color() ? colorFactor : bwFactor;
        return ResponseEntity.ok(new SubmitResponse(job.getId(), req.pages() * (req.copies() == 0 ? 1 : req.copies()) * factor));
    }

    @PostMapping("/{jobId}/complete")
    public ResponseEntity<?> complete(@RequestHeader(value = "X-Print-Broker-Key", required = false) String key,
                                      @PathVariable UUID jobId,
                                      @RequestBody(required = false) CompleteRequest req) {
        if (!checkKey(key)) return unauthorized();
        IppPrintService.CompleteOverride override = null;
        if (req != null && (req.pages() != null || req.copies() != null
                || req.color() != null || req.duplex() != null)) {
            override = new IppPrintService.CompleteOverride(
                    req.pages(), req.copies(), req.color(), req.duplex());
        }
        ippPrintService.complete(jobId, override);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/error")
    public ResponseEntity<?> error(@RequestHeader(value = "X-Print-Broker-Key", required = false) String key,
                                   @PathVariable UUID jobId,
                                   @RequestBody(required = false) ErrorRequest req) {
        if (!checkKey(key)) return unauthorized();
        ippPrintService.error(jobId, req != null ? req.message() : null);
        return ResponseEntity.noContent().build();
    }

    private boolean checkKey(String provided) {
        if (brokerKey == null || brokerKey.isBlank() || "CHANGE_ME".equals(brokerKey)) {
            log.error("amicale.print.broker-key non configuré — tous les appels broker sont refusés");
            return false;
        }
        if (provided == null || !brokerKey.equals(provided)) {
            log.warn("Print broker: clé absente ou invalide");
            return false;
        }
        return true;
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "broker_key_invalid"));
    }

    @Value("${amicale.business.print-color-factor:2}") private int colorFactor;
    @Value("${amicale.business.print-bw-factor:1}")    private int bwFactor;

    @ExceptionHandler(InvalidPrintTokenException.class)
    public ResponseEntity<Map<String, String>> handleInvalidToken(InvalidPrintTokenException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_token", "message", e.getMessage()));
    }

    @ExceptionHandler(InsufficientPrintCreditsException.class)
    public ResponseEntity<Map<String, String>> handleInsufficient(InsufficientPrintCreditsException e) {
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(Map.of("error", "insufficient_credits", "message", e.getMessage()));
    }

    @ExceptionHandler(InvalidPrintRequestException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequest(InvalidPrintRequestException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", "invalid_request", "message", e.getMessage()));
    }

    public record AuthenticateRequest(UUID token) {}
    public record AuthenticateResponse(UUID memberId, String displayName, String email, int creditsRemaining) {}
    public record SubmitRequest(UUID token, int pages, String filename, int copies,
                                boolean color, boolean duplex, String submittedUsername) {}
    public record SubmitResponse(UUID jobId, int creditsCost) {}
    public record ErrorRequest(String message) {}
    /** Body optionnel pour /complete : permet le deferred billing
     *  (claudine-proxy fournit pages/copies/color/duplex APRÈS le job). Champs
     *  nullables — chaque champ non-null override la valeur stockée à /submit. */
    public record CompleteRequest(Integer pages, Integer copies,
                                   Boolean color, Boolean duplex) {}
}
