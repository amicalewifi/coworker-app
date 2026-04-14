package ch.amicalewifi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Intégration UniFi Cloud — création de vouchers hotspot WiFi.
 *
 * Lorsqu'un membre confirme sa présence via l'appli mobile, un voucher
 * à usage unique est généré et affiché pour qu'il se connecte au réseau
 * invité du coworking.
 *
 * API : POST {baseUrl}/api/s/{site}/cmd/hotspot
 *       Header : X-API-KEY
 *       Body   : {"cmd":"create-voucher","expire":<min>,"n":1,"quota":1,"note":"<nom>"}
 *
 * Après création, un second appel GET /api/s/{site}/stat/voucher?create_time=<ts>
 * récupère le code lisible (ex: "12345-67890").
 */
@Service
@Slf4j
public class UnifiService {

    @Value("${amicale.unifi.api-key}")               private String apiKey;
    @Value("${amicale.unifi.site:default}")          private String site;
    @Value("${amicale.unifi.base-url:https://unifi.ui.com/proxy/network}") private String baseUrl;
    @Value("${amicale.unifi.voucher-expire-minutes:600}") private int expireMinutes;

    private final RestTemplate rest = new RestTemplate();

    /**
     * Crée un voucher hotspot WiFi à usage unique pour le membre.
     *
     * @param memberNote nom affiché dans le portail UniFi (ex: "Julien Reuse")
     * @return code du voucher (ex: "12345-67890"), ou empty si erreur
     */
    public Optional<String> createVoucher(String memberNote) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY", apiKey);

            // Étape 1 : créer le voucher
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cmd",    "create-voucher");
            body.put("expire", expireMinutes);
            body.put("n",      1);
            body.put("quota",  1);   // usage unique
            body.put("note",   memberNote);

            String createUrl = baseUrl + "/api/s/" + site + "/cmd/hotspot";
            ResponseEntity<Map> createResp = rest.exchange(
                    createUrl, HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);

            if (!createResp.getStatusCode().is2xxSuccessful() || createResp.getBody() == null) {
                log.warn("UniFi create-voucher échoué: {}", createResp.getStatusCode());
                return Optional.empty();
            }

            // Récupérer le create_time pour chercher le voucher
            List<?> data = (List<?>) createResp.getBody().get("data");
            if (data == null || data.isEmpty()) {
                log.warn("UniFi create-voucher: réponse vide");
                return Optional.empty();
            }
            Object createTime = ((Map<?, ?>) data.get(0)).get("create_time");
            if (createTime == null) {
                log.warn("UniFi create-voucher: create_time manquant");
                return Optional.empty();
            }

            // Étape 2 : récupérer le code du voucher
            String statUrl = baseUrl + "/api/s/" + site + "/stat/voucher?create_time=" + createTime;
            ResponseEntity<Map> statResp = rest.exchange(
                    statUrl, HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);

            if (!statResp.getStatusCode().is2xxSuccessful() || statResp.getBody() == null) {
                log.warn("UniFi stat/voucher échoué: {}", statResp.getStatusCode());
                return Optional.empty();
            }

            List<?> vouchers = (List<?>) statResp.getBody().get("data");
            if (vouchers == null || vouchers.isEmpty()) {
                log.warn("UniFi: aucun voucher trouvé pour create_time={}", createTime);
                return Optional.empty();
            }

            String code = (String) ((Map<?, ?>) vouchers.get(0)).get("code");
            if (code != null && code.length() == 10) {
                // Formater "12345-67890" si renvoyé sans tiret
                code = code.substring(0, 5) + "-" + code.substring(5);
            }
            log.info("UniFi voucher créé: {} pour {}", code, memberNote);
            return Optional.ofNullable(code);

        } catch (Exception e) {
            log.warn("UniFi voucher non disponible: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
