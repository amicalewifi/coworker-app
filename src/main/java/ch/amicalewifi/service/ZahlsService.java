package ch.amicalewifi.service;

import ch.amicalewifi.config.ZahlsProperties;
import ch.amicalewifi.model.ConfHourPackType;
import ch.amicalewifi.model.MembershipType;
import ch.amicalewifi.model.PrintPackType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Intégration zahls.ch (Payrexx) — liens de paiement pour le renouvellement de pack.
 *
 * Stratégie :
 *   1. Si un lien statique est configuré dans amicale.zahls.gateways.{TYPE},
 *      on l'utilise directement en ajoutant ?referenceId={memberId}:{TYPE}
 *      (Payrexx transmet ce paramètre au webhook automatiquement).
 *   2. Sinon, on crée un lien dynamique via l'API zahls.ch.
 */
@Service
@Slf4j
public class ZahlsService {

    @Value("${amicale.zahls.instance}")                            private String instance;
    @Value("${amicale.zahls.api-key}")                             private String apiKey;
    @Value("${amicale.zahls.base-url:https://api.zahls.ch/v1.14}") private String baseUrl;
    @Value("${amicale.zahls.success-url}")                         private String successUrl;
    @Value("${amicale.zahls.cancel-url}")                          private String cancelUrl;
    @Value("${amicale.zahls.print-success-url}")                   private String printSuccessUrl;
    @Value("${amicale.zahls.print-cancel-url}")                    private String printCancelUrl;
    @Value("${amicale.zahls.conf-success-url}")                    private String confSuccessUrl;
    @Value("${amicale.zahls.conf-cancel-url}")                     private String confCancelUrl;

    @Autowired
    private ZahlsProperties zahlsProperties;

    private final RestTemplate rest = new RestTemplate();

    public Optional<String> createPaymentLink(UUID memberId, MembershipType membership) {
        if (!membership.hasPack() && membership.getPriceChf().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Zahls: formule {} sans prix, pas de lien créé", membership);
            return Optional.empty();
        }
        String referenceId = memberId + ":" + membership.name();
        String staticUrl = zahlsProperties.getGateways().get(membership.name());
        if (staticUrl != null && !staticUrl.isBlank()) {
            return buildStaticLink(staticUrl, referenceId);
        }
        int amountRappen = membership.getPriceChf().multiply(BigDecimal.valueOf(100)).intValue();
        return createApiLink(referenceId, "Renouvellement " + membership.getLabel(),
                amountRappen, successUrl, cancelUrl);
    }

    public Optional<String> createConfCreditPaymentLink(UUID memberId, ConfHourPackType pack) {
        String referenceId = memberId + ":CONFCREDIT:" + pack.name();
        String staticUrl = zahlsProperties.getGateways().get("CONFCREDIT_" + pack.name());
        if (staticUrl != null && !staticUrl.isBlank()) {
            return buildStaticLink(staticUrl, referenceId);
        }
        int amountRappen = pack.getPriceChf().multiply(BigDecimal.valueOf(100)).intValue();
        return createApiLink(referenceId, "Salle de conférence · " + pack.getLabel(),
                amountRappen, confSuccessUrl, confCancelUrl);
    }

    public Optional<String> createPrintPackPaymentLink(UUID memberId, PrintPackType pack) {
        String referenceId = memberId + ":PRINT:" + pack.name();
        String staticUrl = zahlsProperties.getGateways().get("PRINT_" + pack.name());
        if (staticUrl != null && !staticUrl.isBlank()) {
            return buildStaticLink(staticUrl, referenceId);
        }
        int amountRappen = pack.getPriceChf().multiply(BigDecimal.valueOf(100)).intValue();
        return createApiLink(referenceId, "Crédits impression · " + pack.getLabel(),
                amountRappen, printSuccessUrl, printCancelUrl);
    }

    /** Ajoute referenceId à un lien de paiement statique zahls.ch. */
    private Optional<String> buildStaticLink(String baseLink, String referenceId) {
        try {
            String encoded = URLEncoder.encode(referenceId, StandardCharsets.UTF_8);
            String url = baseLink + (baseLink.contains("?") ? "&" : "?") + "referenceId=" + encoded;
            log.info("Zahls lien statique: {} ref={}", baseLink, referenceId);
            return Optional.of(url);
        } catch (Exception e) {
            log.error("Zahls buildStaticLink erreur: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Crée un lien dynamique via l'API zahls.ch (requiert une clé API valide). */
    private Optional<String> createApiLink(String referenceId, String purpose,
                                            int amountRappen, String successRedirect, String cancelRedirect) {
        try {
            String key = apiKey.trim();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("amount",             String.valueOf(amountRappen));
            params.put("cancelRedirectUrl",  cancelRedirect);
            params.put("currency",           "CHF");
            params.put("purpose",            purpose);
            params.put("referenceId",        referenceId);
            params.put("successRedirectUrl", successRedirect);
            params.put("vatRate",            "0");

            params.put("ApiSignature", buildSignature(params, key));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setBasicAuth(instance.trim(), key);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            params.forEach(body::add);

            String url = baseUrl + "/Gateway/?instance=" + instance.trim();
            ResponseEntity<Map> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), Map.class);

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("Zahls createApiLink HTTP {}: {}", resp.getStatusCode(), resp.getBody());
                return Optional.empty();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.getBody().get("data");
            if (data == null || data.isEmpty()) {
                log.warn("Zahls: réponse sans data — {}", resp.getBody());
                return Optional.empty();
            }

            String link = (String) data.get(0).get("link");
            log.info("Zahls lien API créé: {} ref={}", link, referenceId);
            return Optional.ofNullable(link);

        } catch (Exception e) {
            log.error("Zahls createApiLink erreur: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    private String buildQueryString(TreeMap<String, String> sorted) {
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (qs.length() > 0) qs.append("&");
            qs.append(URLEncoder.encode(e.getKey(),   StandardCharsets.UTF_8))
              .append("=")
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return qs.toString();
    }

    private String buildSignature(Map<String, String> params, String key) throws Exception {
        String qs = buildQueryString(new TreeMap<>(params));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(qs.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}
