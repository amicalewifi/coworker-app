package ch.amicalewifi.service;

import ch.amicalewifi.config.ZahlsProperties;
import ch.amicalewifi.model.ConfHourPackType;
import ch.amicalewifi.model.Member;
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

    public Optional<String> createPaymentLink(Member member, MembershipType membership) {
        if (!membership.hasPack() && membership.getPriceChf().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Zahls: formule {} sans prix, pas de lien créé", membership);
            return Optional.empty();
        }
        String referenceId = member.getId() + ":" + membership.name();
        String staticUrl = zahlsProperties.getGateways().get(membership.name());
        if (staticUrl != null && !staticUrl.isBlank()) {
            return buildStaticLink(staticUrl, referenceId, member);
        }
        int amountRappen = membership.getPriceChf().multiply(BigDecimal.valueOf(100)).intValue();
        return createApiLink(referenceId, "Renouvellement " + membership.getLabel(),
                amountRappen, successUrl, cancelUrl, member);
    }

    public Optional<String> createConfCreditPaymentLink(Member member, ConfHourPackType pack) {
        String referenceId = member.getId() + ":CONFCREDIT:" + pack.name();
        String staticUrl = zahlsProperties.getGateways().get("CONFCREDIT_" + pack.name());
        if (staticUrl != null && !staticUrl.isBlank()) {
            return buildStaticLink(staticUrl, referenceId, member);
        }
        int amountRappen = pack.getPriceChf().multiply(BigDecimal.valueOf(100)).intValue();
        return createApiLink(referenceId, "Salle de conférence · " + pack.getLabel(),
                amountRappen, confSuccessUrl, confCancelUrl, member);
    }

    public Optional<String> createPrintPackPaymentLink(Member member, PrintPackType pack) {
        String referenceId = member.getId() + ":PRINT:" + pack.name();
        String staticUrl = zahlsProperties.getGateways().get("PRINT_" + pack.name());
        if (staticUrl != null && !staticUrl.isBlank()) {
            return buildStaticLink(staticUrl, referenceId, member);
        }
        int amountRappen = pack.getPriceChf().multiply(BigDecimal.valueOf(100)).intValue();
        return createApiLink(referenceId, "Crédits impression · " + pack.getLabel(),
                amountRappen, printSuccessUrl, printCancelUrl, member);
    }

    /** Ajoute referenceId + infos membre à un lien de paiement statique zahls.ch. */
    private Optional<String> buildStaticLink(String baseLink, String referenceId, Member member) {
        try {
            StringBuilder url = new StringBuilder(baseLink);
            url.append(baseLink.contains("?") ? "&" : "?");
            url.append("referenceId=").append(URLEncoder.encode(referenceId, StandardCharsets.UTF_8));
            appendIfNotBlank(url, "contact_forename", member.getFirstName());
            appendIfNotBlank(url, "contact_surname",  member.getLastName());
            appendIfNotBlank(url, "contact_email",    member.getEmail());
            appendIfNotBlank(url, "contact_phone",    member.getPhone());
            appendIfNotBlank(url, "contact_street",   member.getAddress());
            appendIfNotBlank(url, "contact_postcode", member.getPostalCode());
            appendIfNotBlank(url, "contact_place",    member.getCity());
            log.info("Zahls lien statique complet: {}", url);
            return Optional.of(url.toString());
        } catch (Exception e) {
            log.error("Zahls buildStaticLink erreur: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Crée un lien dynamique via l'API zahls.ch (requiert une clé API valide). */
    private Optional<String> createApiLink(String referenceId, String purpose,
                                            int amountRappen, String successRedirect, String cancelRedirect,
                                            Member member) {
        try {
            String key = apiKey.trim();
            Map<String, String> params = new LinkedHashMap<>();
            params.put("amount",             String.valueOf(amountRappen));
            params.put("cancelRedirectUrl",  cancelRedirect);
            params.put("currency",           "CHF");
            params.put("failedRedirectUrl",  withQueryParam(cancelRedirect, "paid", "fail"));
            params.put("purpose",            purpose);
            params.put("referenceId",        referenceId);
            params.put("successRedirectUrl", successRedirect);
            params.put("vatRate",            "0");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("x-api-key", key);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            params.forEach(body::add);

            if (member != null) {
                addFieldIfNotBlank(body, "fields[forename][value]",  member.getFirstName());
                addFieldIfNotBlank(body, "fields[surname][value]",   member.getLastName());
                addFieldIfNotBlank(body, "fields[email][value]",     member.getEmail());
                addFieldIfNotBlank(body, "fields[phone][value]",     member.getPhone());
                addFieldIfNotBlank(body, "fields[street][value]",    member.getAddress());
                addFieldIfNotBlank(body, "fields[postcode][value]",  member.getPostalCode());
                addFieldIfNotBlank(body, "fields[place][value]",     member.getCity());
            }

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

    private void appendIfNotBlank(StringBuilder url, String param, String value) throws Exception {
        if (value != null && !value.isBlank())
            url.append("&").append(param).append("=").append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private void addFieldIfNotBlank(MultiValueMap<String, String> body, String key, String value) {
        if (value != null && !value.isBlank()) body.add(key, value);
    }

    private String withQueryParam(String url, String key, String value) {
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
