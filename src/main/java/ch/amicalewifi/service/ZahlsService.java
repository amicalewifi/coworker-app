package ch.amicalewifi.service;

import ch.amicalewifi.model.ConfHourPackType;
import ch.amicalewifi.model.MembershipType;
import ch.amicalewifi.model.PrintPackType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Intégration zahls.ch (Payrexx) — création de liens de paiement pour le renouvellement
 * de pack depuis l'appli mobile membre.
 *
 * API Payrexx (base zahls.ch) :
 *   POST {baseUrl}/Gateway/?instance={instance}
 *   Body form-encoded : tous les paramètres + ApiSignature
 *   ApiSignature = base64(HMAC-SHA256(apiKey, phpSerialize(ksort(params))))
 *
 * Après paiement, zahls.ch envoie un webhook POST JSON à /api/v1/zahls/webhook
 * avec transaction.status == "confirmed" et invoice.referenceId == "{memberId}:{MembershipType}"
 */
@Service
@Slf4j
public class ZahlsService {

    @Value("${amicale.zahls.instance}")                                   private String instance;
    @Value("${amicale.zahls.api-key}")                                    private String apiKey;
    @Value("${amicale.zahls.base-url:https://api.zahls.ch/v1.14}")        private String baseUrl;
    @Value("${amicale.zahls.success-url}")                                private String successUrl;
    @Value("${amicale.zahls.cancel-url}")                                 private String cancelUrl;
    @Value("${amicale.zahls.print-success-url}")                          private String printSuccessUrl;
    @Value("${amicale.zahls.print-cancel-url}")                           private String printCancelUrl;
    @Value("${amicale.zahls.conf-success-url}")                           private String confSuccessUrl;
    @Value("${amicale.zahls.conf-cancel-url}")                            private String confCancelUrl;

    private final RestTemplate rest = new RestTemplate();

    /**
     * Crée un lien de paiement zahls.ch pour le renouvellement d'un pack membre.
     *
     * @param memberId   UUID du membre
     * @param membership Formule souhaitée
     * @return URL de paiement zahls.ch, ou empty si erreur
     */
    public Optional<String> createPaymentLink(UUID memberId, MembershipType membership) {
        if (!membership.hasPack() && membership.getPriceChf().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Zahls: formule {} sans prix, pas de lien créé", membership);
            return Optional.empty();
        }
        int amountRappen = membership.getPriceChf().multiply(BigDecimal.valueOf(100)).intValue();
        String referenceId = memberId + ":" + membership.name();
        return createLink(referenceId, "Renouvellement " + membership.getLabel(),
                amountRappen, successUrl, cancelUrl);
    }

    public Optional<String> createConfCreditPaymentLink(UUID memberId, ConfHourPackType pack) {
        int amountRappen = pack.getPriceChf().multiply(BigDecimal.valueOf(100)).intValue();
        return createLink(memberId + ":CONFCREDIT:" + pack.name(),
                "Salle de conférence · " + pack.getLabel(),
                amountRappen, confSuccessUrl, confCancelUrl);
    }

    public Optional<String> createPrintPackPaymentLink(UUID memberId, PrintPackType pack) {
        int amountRappen = pack.getPriceChf().multiply(BigDecimal.valueOf(100)).intValue();
        String referenceId = memberId + ":PRINT:" + pack.name();
        return createLink(referenceId, "Crédits impression · " + pack.getLabel(),
                amountRappen, printSuccessUrl, printCancelUrl);
    }

    private Optional<String> createLink(String referenceId, String purpose,
                                         int amountRappen, String successRedirect, String cancelRedirect) {
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("amount",               String.valueOf(amountRappen));
            params.put("cancelRedirectUrl",    cancelRedirect);
            params.put("chargeOnAuthentication", "0");
            params.put("currency",             "CHF");
            params.put("preAuthorization",     "0");
            params.put("purpose",              purpose);
            params.put("referenceId",          referenceId);
            params.put("successRedirectUrl",   successRedirect);
            params.put("vatRate",              "0");

            params.put("ApiSignature", buildSignature(params));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            params.forEach(body::add);

            String url = baseUrl + "/Gateway/?instance=" + instance;
            ResponseEntity<Map> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), Map.class);

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("Zahls createLink HTTP {}: {}", resp.getStatusCode(), resp.getBody());
                return Optional.empty();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.getBody().get("data");
            if (data == null || data.isEmpty()) {
                log.warn("Zahls: réponse sans data — {}", resp.getBody());
                return Optional.empty();
            }

            String link = (String) data.get(0).get("link");
            log.info("Zahls lien créé: {} ref={}", link, referenceId);
            return Optional.ofNullable(link);

        } catch (Exception e) {
            log.error("Zahls createLink erreur: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Signe les paramètres selon le SDK officiel Payrexx :
     * base64(HMAC-SHA256(apiKey, http_build_query(ksort(params))))
     * http_build_query = query string URL-encodée (espaces → +, RFC 1738)
     */
    private String buildQueryString(TreeMap<String, String> sorted) {
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (qs.length() > 0) qs.append("&");
            qs.append(rfc3986Encode(e.getKey()))
              .append("=")
              .append(rfc3986Encode(e.getValue()));
        }
        return qs.toString();
    }

    private static String rfc3986Encode(String s) {
        // PHP http_build_query uses RFC 1738 (spaces → +), matching URLEncoder default behaviour
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String buildSignature(Map<String, String> params) throws Exception {
        String qs = buildQueryString(new TreeMap<>(params));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(qs.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}
